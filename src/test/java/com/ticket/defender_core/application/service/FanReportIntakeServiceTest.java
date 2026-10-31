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
import org.springframework.web.multipart.MultipartFile;

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
    private static final String KEY = "88888888-8888-4888-8888-888888888888";
    private static final String RECOVERY_SECRET = "A".repeat(43);
    private static final String OTHER_RECOVERY_SECRET = "B".repeat(42) + "A";
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
        FanReportReceipt receipt = service.submit(submission(), List.of(), KEY, RECOVERY_SECRET);
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

        FanReportReceipt receipt = service.submit(submission(), List.of(file), KEY, RECOVERY_SECRET);
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
    void identicalReplayRecoversSameCodeWithoutNewReportAndDifferentBodyConflicts() {
        FanReportReceipt first = service.submit(submission(), List.of(), KEY, RECOVERY_SECRET);
        ArgumentCaptor<FanReport> saved = ArgumentCaptor.forClass(FanReport.class);
        verify(persistence).reserve(saved.capture(), anyList());
        saved.getValue().markReceived();
        when(reports.findByIdempotencyKeyDigest(anyString())).thenReturn(Optional.of(saved.getValue()));

        FanReportReceipt replay = service.submit(submission(), List.of(), KEY, RECOVERY_SECRET);
        assertThat(replay.receiptNumber()).isEqualTo(first.receiptNumber());
        assertThat(replay.lookupCode()).isEqualTo(first.lookupCode());
        assertThat(replay.replayed()).isTrue();
        assertThatThrownBy(() -> service.submit(submission(), List.of(), KEY, OTHER_RECOVERY_SECRET))
                .isInstanceOf(FanReportApiException.class).hasMessage("REPORT_NOT_FOUND");
        when(reports.findByReceiptNumber(first.receiptNumber())).thenReturn(Optional.of(saved.getValue()));
        assertThat(service.lookup(new FanReportLookupRequest(replay.receiptNumber(), replay.lookupCode()))
                .intakeStatus()).isEqualTo(FanReport.IntakeStatus.RECEIVED);
        assertThatThrownBy(() -> service.submit(new FanReportSubmission(EVENT,
                submission().performanceAt(), null, FanReport.ReportType.OTHER,
                "A different synthetic report description", null, null, null, null), List.of(), KEY, RECOVERY_SECRET))
                .isInstanceOf(FanReportApiException.class).hasMessage("IDEMPOTENCY_CONFLICT");
        verify(persistence, times(1)).reserve(any(), anyList());
    }

    @Test
    void completedReplaySurvivesClosedEventAndStorageOutageButNewIntakeDoesNot() {
        MockMultipartFile file = new MockMultipartFile("evidence", "synthetic.png", "image/png", new byte[]{1});
        List<MultipartFile> files = List.of(file);
        when(images.validate(files)).thenReturn(List.of(new ValidatedImage(new byte[]{1}, "image/png", 1,
                "a".repeat(64))));
        when(storage.put(anyString(), any())).thenReturn("etag");
        FanReportReceipt first = service.submit(submission(), files, KEY, RECOVERY_SECRET);
        ArgumentCaptor<FanReport> saved = ArgumentCaptor.forClass(FanReport.class);
        verify(persistence).reserve(saved.capture(), anyList());
        saved.getValue().markReceived();
        when(reports.findByIdempotencyKeyDigest(crypto.idempotencyDigest(KEY)))
                .thenReturn(Optional.of(saved.getValue()));
        clearInvocations(eventLookup, storage);
        doThrow(new FanReportApiException(org.springframework.http.HttpStatus.NOT_FOUND, "EVENT_NOT_AVAILABLE"))
                .when(eventLookup).requireOpenEvent(EVENT);
        doThrow(new IllegalStateException("synthetic object store outage")).when(storage).checkReady();

        FanReportReceipt replay = service.submit(submission(), files, KEY, RECOVERY_SECRET);
        assertThat(replay.receiptNumber()).isEqualTo(first.receiptNumber());
        assertThat(replay.lookupCode()).isEqualTo(first.lookupCode());
        assertThat(replay.replayed()).isTrue();
        assertThatThrownBy(() -> service.submit(submission(), files, KEY, OTHER_RECOVERY_SECRET))
                .isInstanceOf(FanReportApiException.class).hasMessage("REPORT_NOT_FOUND");
        assertThatThrownBy(() -> service.submit(new FanReportSubmission(EVENT,
                submission().performanceAt(), null, FanReport.ReportType.OTHER,
                "A different synthetic report description", null, null, null, null), files, KEY, RECOVERY_SECRET))
                .isInstanceOf(FanReportApiException.class).hasMessage("IDEMPOTENCY_CONFLICT");
        verifyNoInteractions(eventLookup, storage);

        String newKey = "99999999-9999-4999-8999-999999999999";
        assertThatThrownBy(() -> service.submit(submission(), files, newKey, RECOVERY_SECRET))
                .isInstanceOf(FanReportApiException.class).hasMessage("EVENT_NOT_AVAILABLE");
        doReturn(new TenantEvent(EVENT, TENANT, "ref", "Event", AccessRecordStatus.ACTIVE))
                .when(eventLookup).requireOpenEvent(EVENT);
        assertThatThrownBy(() -> service.submit(submission(), files, newKey, RECOVERY_SECRET))
                .isInstanceOf(IllegalStateException.class).hasMessage("synthetic object store outage");
        verify(persistence, times(1)).reserve(any(), anyList());
    }

    @Test
    void rejectsPredictableOrMalformedIdempotencyKey() {
        assertThatThrownBy(() -> service.submit(submission(), List.of(), "synthetic-key-123456", RECOVERY_SECRET))
                .isInstanceOf(FanReportApiException.class).hasMessage("INVALID_IDEMPOTENCY_KEY");
        assertThatThrownBy(() -> service.submit(submission(), List.of(), KEY, "short"))
                .isInstanceOf(FanReportApiException.class).hasMessage("INVALID_RECOVERY_SECRET");
        verifyNoInteractions(eventLookup, storage, persistence);
    }

    @Test
    void anotherReportsCredentialsCannotRecoverReceiptOrLookupCode() {
        String otherKey = "77777777-7777-4777-8777-777777777777";
        FanReportReceipt first = service.submit(submission(), List.of(), KEY, RECOVERY_SECRET);
        FanReportReceipt second = service.submit(submission(), List.of(), otherKey, OTHER_RECOVERY_SECRET);
        ArgumentCaptor<FanReport> saved = ArgumentCaptor.forClass(FanReport.class);
        verify(persistence, times(2)).reserve(saved.capture(), anyList());
        FanReport firstReport = saved.getAllValues().get(0);
        FanReport secondReport = saved.getAllValues().get(1);
        firstReport.markReceived();
        secondReport.markReceived();
        when(reports.findByIdempotencyKeyDigest(crypto.idempotencyDigest(KEY)))
                .thenReturn(Optional.of(firstReport));
        when(reports.findByIdempotencyKeyDigest(crypto.idempotencyDigest(otherKey)))
                .thenReturn(Optional.of(secondReport));
        when(reports.findByReceiptNumber(first.receiptNumber())).thenReturn(Optional.of(firstReport));

        assertThatThrownBy(() -> service.submit(submission(), List.of(), KEY, OTHER_RECOVERY_SECRET))
                .isInstanceOf(FanReportApiException.class).hasMessage("REPORT_NOT_FOUND");
        assertThatThrownBy(() -> service.submit(submission(), List.of(), otherKey, RECOVERY_SECRET))
                .isInstanceOf(FanReportApiException.class).hasMessage("REPORT_NOT_FOUND");
        assertThatThrownBy(() -> service.lookup(new FanReportLookupRequest(first.receiptNumber(),
                second.lookupCode())))
                .isInstanceOf(FanReportApiException.class).hasMessage("REPORT_NOT_FOUND");
        verify(persistence, times(2)).reserve(any(), anyList());
    }

    @Test
    void preChangeIdempotencyDigestCannotCreateDuplicateReport() {
        when(reports.findByIdempotencyKeyDigest(crypto.legacyIdempotencyDigest(KEY)))
                .thenReturn(Optional.of(mock(FanReport.class)));
        assertThatThrownBy(() -> service.submit(submission(), List.of(), KEY, RECOVERY_SECRET))
                .isInstanceOf(FanReportApiException.class).hasMessage("LEGACY_RECOVERY_UNAVAILABLE");
        verify(persistence, never()).reserve(any(), anyList());
    }

    @Test
    void databaseReservationFailureDoesNotUploadAndObjectFailureSchedulesCleanup() {
        doThrow(new DataAccessResourceFailureException("synthetic db outage"))
                .when(persistence).reserve(any(), anyList());
        assertThatThrownBy(() -> service.submit(submission(), List.of(), KEY, RECOVERY_SECRET))
                .isInstanceOf(DataAccessResourceFailureException.class);
        verify(storage, never()).put(anyString(), any());

        reset(persistence);
        MockMultipartFile file = new MockMultipartFile("evidence", "synthetic.png", "image/png", new byte[]{1});
        when(images.validate(anyList())).thenReturn(List.of(new ValidatedImage(new byte[]{1}, "image/png", 1, "a".repeat(64))));
        when(storage.put(anyString(), any())).thenThrow(new IllegalStateException("synthetic storage outage"));
        assertThatThrownBy(() -> service.submit(submission(), List.of(file), KEY, RECOVERY_SECRET))
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
        // 최종 커밋 여부와 정리 결과를 다시 확인할 수 없으면 확정 실패로 단정하지 않는다.
        assertThatThrownBy(() -> service.submit(submission(), List.of(file), KEY, RECOVERY_SECRET))
                .isInstanceOf(FanReportApiException.class).hasMessage("INTAKE_OUTCOME_UNKNOWN");
        verify(persistence).cleanStale(any(), any());
        verify(storage, never()).delete(anyString(), any());
    }

    @Test
    void finalizationAcknowledgementLossIsResolvedAsReceivedByReRead() {
        doAnswer(invocation -> {
            throw new DataAccessResourceFailureException("synthetic lost commit acknowledgement");
        }).when(persistence).markReceived(any(), anyMap());
        ArgumentCaptor<FanReport> saved = ArgumentCaptor.forClass(FanReport.class);
        doAnswer(invocation -> {
            FanReport report = invocation.getArgument(0);
            when(reports.findById(report.getFanReportId())).thenAnswer(read -> {
                // 커밋은 이미 반영되었고 응답만 유실된 상황을 재현한다.
                if (report.getIntakeStatus() == FanReport.IntakeStatus.PENDING_STORAGE) report.markReceived();
                return Optional.of(report);
            });
            return null;
        }).when(persistence).reserve(saved.capture(), anyList());

        FanReportReceipt receipt = service.submit(submission(), List.of(), KEY, RECOVERY_SECRET);
        assertThat(receipt.intakeStatus()).isEqualTo(FanReport.IntakeStatus.RECEIVED);
        assertThat(receipt.fanReportId()).isEqualTo(saved.getValue().getFanReportId());
        assertThat(receipt.replayed()).isFalse();
        verify(persistence, times(1)).reserve(any(), anyList());
    }

    @Test
    void finalizationReconciledToStorageFailedIsConfirmedFailure() {
        doThrow(new DataAccessResourceFailureException("synthetic rejected finalization"))
                .when(persistence).markReceived(any(), anyMap());
        doAnswer(invocation -> {
            FanReport report = invocation.getArgument(0);
            when(reports.findById(report.getFanReportId())).thenAnswer(read -> {
                if (report.getIntakeStatus() == FanReport.IntakeStatus.PENDING_STORAGE) report.markStorageFailed();
                return Optional.of(report);
            });
            return null;
        }).when(persistence).reserve(any(), anyList());

        assertThatThrownBy(() -> service.submit(submission(), List.of(), KEY, RECOVERY_SECRET))
                .isInstanceOf(FanReportApiException.class).hasMessage("INTAKE_STORAGE_UNAVAILABLE");
    }

    @Test
    void uploadFailureWithoutPersistedFailureStateIsOutcomeUnknown() {
        MockMultipartFile file = new MockMultipartFile("evidence", "synthetic.png", "image/png", new byte[]{1});
        when(images.validate(anyList())).thenReturn(List.of(new ValidatedImage(new byte[]{1}, "image/png", 1, "a".repeat(64))));
        when(storage.put(anyString(), any())).thenThrow(new IllegalStateException("synthetic storage outage"));
        doThrow(new DataAccessResourceFailureException("synthetic db outage")).when(persistence).markStorageFailed(any());
        doThrow(new DataAccessResourceFailureException("synthetic db outage")).when(persistence).markCleaned(any());

        assertThatThrownBy(() -> service.submit(submission(), List.of(file), KEY, RECOVERY_SECRET))
                .isInstanceOf(FanReportApiException.class).hasMessage("INTAKE_OUTCOME_UNKNOWN");
        verify(storage).delete(anyString(), isNull());
    }

    @Test
    void persistedStorageFailedReplayRequiresNewKeyAndPendingReplayStaysInProgress() {
        FanReportReceipt first = service.submit(submission(), List.of(), KEY, RECOVERY_SECRET);
        ArgumentCaptor<FanReport> saved = ArgumentCaptor.forClass(FanReport.class);
        verify(persistence).reserve(saved.capture(), anyList());
        FanReport report = saved.getValue();
        when(reports.findByIdempotencyKeyDigest(crypto.idempotencyDigest(KEY))).thenReturn(Optional.of(report));

        assertThatThrownBy(() -> service.submit(submission(), List.of(), KEY, RECOVERY_SECRET))
                .isInstanceOf(FanReportApiException.class).hasMessage("INTAKE_IN_PROGRESS");
        report.markStorageFailed();
        assertThatThrownBy(() -> service.submit(submission(), List.of(), KEY, RECOVERY_SECRET))
                .isInstanceOf(FanReportApiException.class).hasMessage("INTAKE_STORAGE_UNAVAILABLE");
        assertThatThrownBy(() -> service.submit(submission(), List.of(), KEY, OTHER_RECOVERY_SECRET))
                .isInstanceOf(FanReportApiException.class).hasMessage("REPORT_NOT_FOUND");
        assertThat(first.fanReportId()).isEqualTo(report.getFanReportId());
        // 서버는 새 키를 대신 만들지 않으므로 기존 키 재요청은 새 접수를 예약하지 않는다.
        verify(persistence, times(1)).reserve(any(), anyList());
    }

    @Test
    void reservationConflictWithoutVisibleRowIsOutcomeUnknown() {
        doThrow(new org.springframework.dao.DataIntegrityViolationException("synthetic unique conflict"))
                .when(persistence).reserve(any(), anyList());
        assertThatThrownBy(() -> service.submit(submission(), List.of(), KEY, RECOVERY_SECRET))
                .isInstanceOf(FanReportApiException.class).hasMessage("INTAKE_OUTCOME_UNKNOWN");
        verify(storage, never()).put(anyString(), any());
    }

    @Test
    void lookupRequiresCorrectSecretAndHidesOtherReportData() {
        FanReportReceipt receipt = service.submit(submission(), List.of(), KEY, RECOVERY_SECRET);
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
