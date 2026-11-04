package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.in.web.dto.CandidatePipelineDto;
import com.ticket.defender_core.adapter.out.persistence.ReservationCandidateRepository;
import com.ticket.defender_core.adapter.out.persistence.ReservationDataSourceRepository;
import com.ticket.defender_core.adapter.out.persistence.ReservationImportBatchRepository;
import com.ticket.defender_core.adapter.out.persistence.ReservationImportErrorRepository;
import com.ticket.defender_core.adapter.out.persistence.VerifiedPerformanceRepository;
import com.ticket.defender_core.domain.ReservationImportBatch;
import com.ticket.defender_core.domain.VerifiedPerformance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReservationImportServiceTest {
    private final UUID tenantId = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private final UUID eventId = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private final UUID memberId = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private final UUID performanceId = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private CandidatePipelineAccess access;
    private SyntheticReservationImportGate gate;
    private VerifiedPerformanceRepository performances;
    private ReservationImportBatchRepository batches;
    private ReservationImportErrorRepository errors;
    private ReservationCandidateRepository candidates;
    private ReservationDataSourceRepository sources;
    private ReservationImportService service;

    @BeforeEach
    void setup() {
        access = mock(CandidatePipelineAccess.class);
        gate = mock(SyntheticReservationImportGate.class);
        performances = mock(VerifiedPerformanceRepository.class);
        batches = mock(ReservationImportBatchRepository.class);
        errors = mock(ReservationImportErrorRepository.class);
        candidates = mock(ReservationCandidateRepository.class);
        sources = mock(ReservationDataSourceRepository.class);
        service = new ReservationImportService(access, gate, performances, batches, errors, candidates, sources);
        when(access.event(eq(eventId), any())).thenReturn(new CandidatePipelineAccess.Scope(tenantId, eventId, memberId, null));
        when(gate.requireSyntheticScope(eq(tenantId), any())).thenReturn(UUID.randomUUID());
        when(gate.referenceHasher()).thenReturn(new TenantPurposeReferenceHasher(
                "synthetic-only-master-key-material-that-is-long-enough", "v1"));
        VerifiedPerformance performance = new VerifiedPerformance(performanceId, tenantId, eventId, "round-1",
                "synthetic source verified", "performance-v1", memberId, LocalDateTime.now());
        when(performances.lockByIdAndScope(performanceId, tenantId, eventId)).thenReturn(Optional.of(performance));
        when(batches.findByCreatedByMembershipIdAndEventIdAndIdempotencyKeyDigest(eq(memberId), eq(eventId), any()))
                .thenReturn(Optional.empty());
        when(batches.findByTenantIdAndEventIdAndPerformanceIdAndSourceVersion(tenantId, eventId, performanceId,
                "source-v1")).thenReturn(Optional.empty());
        when(errors.findByBatchIdOrderByRowIndexAscCodeAsc(any())).thenReturn(List.of());
    }

    @Test
    void incompleteSnapshotIsRejectedAtomicallyAndCanNeverFeedNoMatch() {
        CandidatePipelineDto.ImportResult result = service.importSnapshot(eventId, UUID.randomUUID().toString(),
                new CandidatePipelineDto.ImportReservations(performanceId, "source-v1", false,
                        List.of(new CandidatePipelineDto.ReservationRow("synthetic-res-1", "VIP", "10", "5"))));

        ArgumentCaptor<ReservationImportBatch> stored = ArgumentCaptor.forClass(ReservationImportBatch.class);
        verify(batches).saveAndFlush(stored.capture());
        assertThat(stored.getValue().getStatus()).isEqualTo(ReservationImportBatch.Status.REJECTED);
        assertThat(stored.getValue().isCompleteSnapshot()).isFalse();
        assertThat(result.status()).isEqualTo(ReservationImportBatch.Status.REJECTED);
        verify(candidates, never()).saveAll(any());
        verify(sources, never()).saveAndFlush(any());
    }

    @Test
    void acceptedCompleteSnapshotStoresOnlyDerivedCandidateRows() {
        when(sources.lockByScope(tenantId, eventId, performanceId)).thenReturn(Optional.empty());

        CandidatePipelineDto.ImportResult result = service.importSnapshot(eventId, UUID.randomUUID().toString(),
                new CandidatePipelineDto.ImportReservations(performanceId, "source-v1", true,
                        List.of(new CandidatePipelineDto.ReservationRow("synthetic-res-1", " VIP ", "10", "5"))));

        assertThat(result.status()).isEqualTo(ReservationImportBatch.Status.ACCEPTED);
        assertThat(result.acceptedRowCount()).isEqualTo(1);
        verify(candidates).saveAll(any());
        verify(sources).saveAndFlush(any());
    }

    @Test
    void concurrentUniqueKeyCollisionIsReportedAsRetryableImportConflict() {
        doThrow(new DataIntegrityViolationException("synthetic unique collision")).when(batches).saveAndFlush(any());

        assertThatThrownBy(() -> service.importSnapshot(eventId, UUID.randomUUID().toString(),
                new CandidatePipelineDto.ImportReservations(performanceId, "source-v1", true,
                        List.of(new CandidatePipelineDto.ReservationRow("synthetic-res-1", "VIP", "10", "5")))))
                .isInstanceOfSatisfying(CaseApiException.class,
                        error -> assertThat(error.code()).isEqualTo("RESERVATION_IMPORT_CONCURRENT_UPDATE"));
    }

    @Test
    void sameKeyReplaysTheSameBodyButRejectsDifferentPipeDelimitedSeatFields() {
        CandidatePipelineDto.ImportReservations first = snapshot("same-reservation", "VIP|A", "10", "1");
        CandidatePipelineDto.ImportReservations different = snapshot("same-reservation", "VIP", "A|10", "1");

        assertSameKeyReplaysOnlySameBody(first, different);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " "})
    void nullOrBlankReferenceDoesNotReplayAsTheLiteralPlaceholder(String missingReference) {
        CandidatePipelineDto.ImportReservations first = snapshot(missingReference, "VIP", "10", "1");
        CandidatePipelineDto.ImportReservations different = snapshot("<blank>", "VIP", "10", "1");

        assertSameKeyReplaysOnlySameBody(first, different);
    }

    @Test
    void nullSeatFieldDoesNotReplayAsTheLiteralNullString() {
        CandidatePipelineDto.ImportReservations first = snapshot("same-reservation", "VIP", "10", null);
        CandidatePipelineDto.ImportReservations different = snapshot("same-reservation", "VIP", "10", "null");

        assertSameKeyReplaysOnlySameBody(first, different);
    }

    private void assertSameKeyReplaysOnlySameBody(CandidatePipelineDto.ImportReservations first,
            CandidatePipelineDto.ImportReservations different) {
        when(sources.lockByScope(tenantId, eventId, performanceId)).thenReturn(Optional.empty());
        String key = UUID.randomUUID().toString();
        CandidatePipelineDto.ImportResult initial = service.importSnapshot(eventId, key, first);
        ArgumentCaptor<ReservationImportBatch> stored = ArgumentCaptor.forClass(ReservationImportBatch.class);
        verify(batches).saveAndFlush(stored.capture());
        when(batches.findByCreatedByMembershipIdAndEventIdAndIdempotencyKeyDigest(eq(memberId), eq(eventId), any()))
                .thenReturn(Optional.of(stored.getValue()));

        CandidatePipelineDto.ImportResult replay = service.importSnapshot(eventId, key, first);
        assertThat(replay.batchId()).isEqualTo(initial.batchId());
        assertThatThrownBy(() -> service.importSnapshot(eventId, key, different))
                .isInstanceOfSatisfying(CaseApiException.class,
                        error -> {
                            assertThat(error.status().value()).isEqualTo(409);
                            assertThat(error.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
                        });
    }

    private CandidatePipelineDto.ImportReservations snapshot(String reservationReference, String zone, String row,
            String seat) {
        return new CandidatePipelineDto.ImportReservations(performanceId, "source-v1", true,
                List.of(new CandidatePipelineDto.ReservationRow(reservationReference, zone, row, seat)));
    }
}
