package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.out.persistence.ReservationDataSourceRepository;
import com.ticket.defender_core.domain.MacroAuditEvidence;
import com.ticket.defender_core.domain.ReservationDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 실제 Spring Data JPA 트랜잭션 경계에서 원장 조회 실패가 호출자 트랜잭션에 전파되지 않는지 검증한다. */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({Track1LedgerSeatLookupService.class, Track1LedgerSnapshotReader.class})
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:track1-ledger-tx;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false"
})
class Track1LedgerSeatLookupTransactionTest {
    private static final String KEY = "synthetic-only-master-key-material-that-is-long-enough";

    @Autowired private Track1LedgerSeatLookupService lookup;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoBean private ReservationDataSourceRepository sources;
    @MockitoBean private SyntheticReservationImportGate gate;

    @Test
    void repositoryFailureReturnsLedgerUnavailableWithoutPoisoningTheCallerTransaction() {
        UUID tenantId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID performanceId = UUID.randomUUID();
        when(gate.referenceHasherForRead()).thenReturn(Optional.of(new TenantPurposeReferenceHasher(KEY, "v1")));
        // currentBatchId가 null이면 실제 SimpleJpaRepository.findById가 트랜잭션 경계 안에서 예외를 던진다.
        ReservationDataSource source = mock(ReservationDataSource.class);
        AtomicReference<Integer> isolation = new AtomicReference<>();
        AtomicBoolean readOnly = new AtomicBoolean();
        when(sources.findByTenantIdAndEventIdAndPerformanceId(tenantId, eventId, performanceId)).thenAnswer(call -> {
            isolation.set(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
            readOnly.set(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            return Optional.of(source);
        });
        MacroAuditEvidence member = mock(MacroAuditEvidence.class);
        when(member.getAuditId()).thenReturn(17L);
        TransactionTemplate caller = new TransactionTemplate(transactionManager);
        caller.setReadOnly(true);

        Track1LedgerSeatLookupService.Result result = caller.execute(status -> {
            Track1LedgerSeatLookupService.Result found = lookup.lookup(tenantId, eventId, performanceId,
                    "ledger-sha256-fixed-v1", null, null, List.of(member));
            assertThat(status.isRollbackOnly()).isFalse();
            return found;
        });

        assertThat(isolation.get()).isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        assertThat(readOnly).isTrue();
        assertThat(result.ledgerAvailable()).isFalse();
        assertThat(result.seats()).extracting(Track1LedgerSeatLookupService.LedgerSeat::status)
                .containsExactly(Track1LedgerSeatLookupService.Status.LEDGER_UNAVAILABLE);
    }
}
