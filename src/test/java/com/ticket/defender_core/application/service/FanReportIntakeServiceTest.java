package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.in.web.dto.FanReportLookupRequest;
import com.ticket.defender_core.adapter.in.web.dto.FanReportReceipt;
import com.ticket.defender_core.adapter.in.web.dto.FanReportSubmission;
import com.ticket.defender_core.adapter.out.persistence.FanReportRepository;
import com.ticket.defender_core.domain.AccessRecordStatus;
import com.ticket.defender_core.domain.EvidenceAsset;
import com.ticket.defender_core.domain.FanReport;
import com.ticket.defender_core.domain.TenantEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FanReportIntakeServiceTest {
    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID EVENT = UUID.randomUUID();
    private static final String KEY = "synthetic-key-123456";
    private final PublicEventLookup eventLookup = mock(PublicEventLookup.class);
    private final EvidenceImageValidator images = mock(EvidenceImageValidator.class);
    private final FanReportRepository reports = mock(FanReportRepository.class);
    private final FanReportPersistence persistence = mock(FanReportPersistence.class);
    private final EvidenceObjectStorage storage = mock(EvidenceObjectStorage.class);
    private final FanReportCrypto crypto = new FanReportCrypto(false, "");
    private FanReportIntakeService service;

    @BeforeEach
    void setUp() {
        crypto.initialize();
        service = new FanReportIntakeService(eventLookup, images, crypto, reports, persistence, storage);
        ReflectionTestUtils.setField(service, "retentionDays", 90);
        when(eventLookup.requireOpenEvent(EVENT)).thenReturn(
                new TenantEvent(EVENT, TENANT, "ref", "Event", AccessRecordStatus.ACTIVE));
    }

    @Test
    void acceptsReportWithoutEvidenceAndNeverCreatesAudit() {
        FanReportReceipt receipt = service.submit(submission(), List.of(), KEY);
        assertThat(receipt.intakeStatus()).isEqualTo(FanReport.IntakeStatus.RECEIVED);
        assertThat(receipt.lookupCode()).hasSizeGreaterThan(40);
        verify(persistence).reserve(any(), eq(List.of()));
        verify(persistence).markReceived(eq(receipt.fanReportId()), eq(java.util.Map.of()));
        verify(storage).checkReady();
        verify(storage, never()).put(anyString(), any());
    }

    @Test
    void storesScopedMetadataThenUploadsServerGeneratedKey() {
        MockMultipartFile file = new MockMultipartFile("evidence", "user/secret.png", "image/png", new byte[]{1});
        when(images.validate(anyList())).thenReturn(List.of(new ValidatedImage(new byte[]{1}, "image/png", 1, "a".repeat(64))));
        when(storage.put(anyString(), any())).thenReturn("etag-1");

        FanReportReceipt receipt = service.submit(submission(), List.of(file), KEY);
        ArgumentCaptor<FanReport> capturedReport = ArgumentCaptor.forClass(FanReport.class);
        @SuppressWarnings("unchecked") ArgumentCaptor<List<EvidenceAsset>> capturedAssets = ArgumentCaptor.forClass(List.class);
        verify(persistence).reserve(capturedReport.capture(), capturedAssets.capture());
        FanReport stored = capturedReport.getValue();
        EvidenceAsset asset = capturedAssets.getValue().getFirst();
        assertThat(stored.getFanReportId()).isEqualTo(receipt.fanReportId());
        assertThat(stored.getTenantId()).isEqualTo(TENANT);
        assertThat(stored.getEventId()).isEqualTo(EVENT);
        assertThat(stored.getVerifiedPerformanceRef()).isNull();
        assertThat(stored.getIntakeStatus()).isEqualTo(FanReport.IntakeStatus.PENDING_STORAGE);
        assertThat(asset.getFanReportId()).isEqualTo(stored.getFanReportId());
        assertThat(asset.getObjectKey()).doesNotContain("user", "secret");
        assertThat(asset.getSha256()).isEqualTo("a".repeat(64));
        assertThat(asset.getRetentionUntil()).isEqualTo(asset.getCreatedAt().plusDays(90));
        verify(storage).put(eq(asset.getObjectKey()), any());
        verify(persistence).markReceived(eq(receipt.fanReportId()), eq(java.util.Map.of(asset.getEvidenceId(), "etag-1")));
    }

    @Test
    void identicalReplayReturnsReceiptWithoutReissuingSecretAndDifferentBodyConflicts() {
        FanReportReceipt first = service.submit(submission(), List.of(), KEY);
        ArgumentCaptor<FanReport> saved = ArgumentCaptor.forClass(FanReport.class);
        verify(persistence).reserve(saved.capture(), anyList());
        saved.getValue().markReceived();
        when(reports.findByIdempotencyKeyDigest(anyString())).thenReturn(Optional.of(saved.getValue()));

        FanReportReceipt replay = service.submit(submission(), List.of(), KEY);
        assertThat(replay.receiptNumber()).isEqualTo(first.receiptNumber());
        assertThat(replay.lookupCode()).isNull();
        assertThatThrownBy(() -> service.submit(new FanReportSubmission(EVENT,
                submission().performanceAt(), null, FanReport.ReportType.OTHER,
                "A different synthetic report description", null, null, null, null), List.of(), KEY))
                .isInstanceOf(FanReportApiException.class).hasMessage("IDEMPOTENCY_CONFLICT");
        verify(persistence, times(1)).reserve(any(), anyList());
    }

    @Test
    void databaseReservationFailureDoesNotUploadAndObjectFailureSchedulesCleanup() {
        doThrow(new DataAccessResourceFailureException("synthetic db outage"))
                .when(persistence).reserve(any(), anyList());
        assertThatThrownBy(() -> service.submit(submission(), List.of(), KEY))
                .isInstanceOf(DataAccessResourceFailureException.class);
        verify(storage, never()).put(anyString(), any());

        reset(persistence);
        MockMultipartFile file = new MockMultipartFile("evidence", "synthetic.png", "image/png", new byte[]{1});
        when(images.validate(anyList())).thenReturn(List.of(new ValidatedImage(new byte[]{1}, "image/png", 1, "a".repeat(64))));
        when(storage.put(anyString(), any())).thenThrow(new IllegalStateException("synthetic storage outage"));
        assertThatThrownBy(() -> service.submit(submission(), List.of(file), KEY))
                .isInstanceOf(FanReportApiException.class).hasMessage("INTAKE_STORAGE_UNAVAILABLE");
        verify(persistence).markStorageFailed(any());
        verify(storage).delete(anyString(), isNull());
        verify(persistence).markCleaned(any());
    }

    @Test
    void databaseFinalizationFailureUsesLockedReconciliationBeforeDeleting() {
        MockMultipartFile file = new MockMultipartFile("evidence", "synthetic.png", "image/png", new byte[]{1});
        when(images.validate(anyList())).thenReturn(List.of(new ValidatedImage(new byte[]{1}, "image/png", 1, "a".repeat(64))));
        when(storage.put(anyString(), any())).thenReturn("etag");
        doThrow(new DataAccessResourceFailureException("synthetic db outage"))
                .when(persistence).markReceived(any(), anyMap());
        assertThatThrownBy(() -> service.submit(submission(), List.of(file), KEY))
                .isInstanceOf(FanReportApiException.class).hasMessage("INTAKE_STORAGE_UNAVAILABLE");
        verify(persistence).cleanStale(any(), any());
        verify(storage, never()).delete(anyString(), any());
    }

    @Test
    void lookupRequiresCorrectSecretAndHidesOtherReportData() {
        FanReportReceipt receipt = service.submit(submission(), List.of(), KEY);
        ArgumentCaptor<FanReport> saved = ArgumentCaptor.forClass(FanReport.class);
        verify(persistence).reserve(saved.capture(), anyList());
        saved.getValue().markReceived();
        when(reports.findByReceiptNumber(receipt.receiptNumber())).thenReturn(Optional.of(saved.getValue()));
        assertThat(service.lookup(new FanReportLookupRequest(receipt.receiptNumber(), receipt.lookupCode()))
                .intakeStatus()).isEqualTo(FanReport.IntakeStatus.RECEIVED);
        assertThatThrownBy(() -> service.lookup(new FanReportLookupRequest(receipt.receiptNumber(), "wrong")))
                .isInstanceOf(FanReportApiException.class).hasMessage("REPORT_NOT_FOUND");
        assertThatThrownBy(() -> service.lookup(new FanReportLookupRequest("another-receipt", receipt.lookupCode())))
                .isInstanceOf(FanReportApiException.class).hasMessage("REPORT_NOT_FOUND");
    }

    private FanReportSubmission submission() {
        return new FanReportSubmission(EVENT, LocalDateTime.parse("2026-11-01T19:30:00"), "VIP",
                FanReport.ReportType.PREMIUM_RESALE, "A synthetic suspected listing, with no personal details.",
                "https://example.test/listing", "example", "seller", "450000");
    }
}
