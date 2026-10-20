package com.ticket.defender_core.application.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ticket.defender_core.adapter.out.persistence.ReservationRepository;
import com.ticket.defender_core.adapter.out.persistence.TicketAuditRepository;
import com.ticket.defender_core.domain.AuditStatus;
import com.ticket.defender_core.domain.EvidenceType;
import com.ticket.defender_core.domain.Reservation;
import com.ticket.defender_core.domain.TicketAudit;
import com.ticket.defender_core.security.EventScopeLookup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VlmAnalysisServiceTest {
    private static final UUID TENANT_A = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID TENANT_B = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID EVENT_A = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final LocalDateTime RECEIVED_AT = LocalDateTime.parse("2026-10-08T10:20:00");

    @Mock private ReservationRepository reservations;
    @Mock private TicketAuditRepository audits;
    @Mock private EventScopeLookup events;
    @InjectMocks private VlmAnalysisService service;

    @Test
    void legacyUnscopedCallNeverQueriesOrSaves() {
        service.processVlmReport(parsed());

        verifyNoInteractions(events, reservations, audits);
    }

    @Test
    void missingOrUnusableExtractionIsUnverifiableWithoutLookup() {
        VlmAnalysisService.VlmParsedData lowScore = new VlmAnalysisService.VlmParsedData(
                "VIP", "10", "5", 0.84, "reporter-1", List.of("image-1"), RECEIVED_AT);
        VlmAnalysisService.VlmParsedData missingSeat = new VlmAnalysisService.VlmParsedData(
                "VIP", "10", null, 0.95, "reporter-1", List.of("image-1"), RECEIVED_AT);

        assertThat(service.processVlmReport(lowScore, scope())).isEqualTo(VlmVerificationStatus.UNVERIFIABLE);
        assertThat(service.processVlmReport(missingSeat, scope())).isEqualTo(VlmVerificationStatus.UNVERIFIABLE);
        verifyNoInteractions(events, reservations, audits);
    }

    @Test
    void missingPerformanceOrTenantScopeNeverQueries() {
        assertThat(service.processVlmReport(parsed(), null)).isEqualTo(VlmVerificationStatus.UNVERIFIABLE);
        assertThat(service.processVlmReport(parsed(),
                new VlmAnalysisService.ReportScope(TENANT_A, EVENT_A, " ")))
                .isEqualTo(VlmVerificationStatus.UNVERIFIABLE);
        assertThat(service.processVlmReport(parsed(),
                new VlmAnalysisService.ReportScope(null, EVENT_A, "round-1")))
                .isEqualTo(VlmVerificationStatus.UNVERIFIABLE);
        verifyNoInteractions(events, reservations, audits);
    }

    @Test
    void eventInAnotherTenantOrInactiveEventNeverQueriesReservations() {
        when(events.findTenantId(EVENT_A)).thenReturn(Optional.of(TENANT_B), Optional.empty());

        assertThat(service.processVlmReport(parsed(), scope())).isEqualTo(VlmVerificationStatus.UNVERIFIABLE);
        assertThat(service.processVlmReport(parsed(), scope())).isEqualTo(VlmVerificationStatus.UNVERIFIABLE);
        verifyNoInteractions(reservations, audits);
    }

    @Test
    void exactlyOneCandidateIsSavedWithoutFraudFinding() {
        when(events.findTenantId(EVENT_A)).thenReturn(Optional.of(TENANT_A));
        when(reservations.findTop2ByTenantIdAndEventIdAndPerformanceRefAndZoneAndRowNumAndSeatNum(
                TENANT_A, EVENT_A, "round-1", "VIP", "10", "5"))
                .thenReturn(List.of(reservation("res-1")));

        assertThat(service.processVlmReport(parsed(), scope())).isEqualTo(VlmVerificationStatus.MATCH_CANDIDATE);

        ArgumentCaptor<TicketAudit> saved = ArgumentCaptor.forClass(TicketAudit.class);
        verify(audits).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(AuditStatus.MATCH_CANDIDATE);
        assertThat(saved.getValue().getDetectedAt()).isNull();
        assertThat(saved.getValue().getEvidenceType()).isEqualTo(EvidenceType.FAN_REPORT);
        assertThat(saved.getValue().getReservationNo()).isEqualTo("res-1");
        assertThat(saved.getValue().getAccountId()).isEqualTo("account-1");
        assertThat(saved.getValue().getMappingScore()).isEqualTo(0.95);
        assertThat(saved.getValue().getReporterId()).isEqualTo("reporter-1");
        assertThat(saved.getValue().getReportReceivedAt()).isEqualTo(RECEIVED_AT);
    }

    @Test
    void noCandidateIsDistinctFromLookupFailure() {
        when(events.findTenantId(EVENT_A)).thenReturn(Optional.of(TENANT_A));
        when(reservations.findTop2ByTenantIdAndEventIdAndPerformanceRefAndZoneAndRowNumAndSeatNum(
                TENANT_A, EVENT_A, "round-1", "VIP", "10", "5"))
                .thenReturn(List.of())
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));

        assertThat(service.processVlmReport(parsed(), scope())).isEqualTo(VlmVerificationStatus.NO_MATCH);
        assertThat(service.processVlmReport(parsed(), scope())).isEqualTo(VlmVerificationStatus.LOOKUP_FAILED);
        verifyNoInteractions(audits);
    }

    @Test
    void eventScopeLookupFailureDoesNotBecomeNoMatch() {
        when(events.findTenantId(EVENT_A)).thenThrow(new DataAccessResourceFailureException("database unavailable"));

        assertThat(service.processVlmReport(parsed(), scope())).isEqualTo(VlmVerificationStatus.LOOKUP_FAILED);
        verifyNoInteractions(reservations, audits);
    }

    @Test
    void duplicateSeatCandidatesAreUnverifiable() {
        when(events.findTenantId(EVENT_A)).thenReturn(Optional.of(TENANT_A));
        when(reservations.findTop2ByTenantIdAndEventIdAndPerformanceRefAndZoneAndRowNumAndSeatNum(
                TENANT_A, EVENT_A, "round-1", "VIP", "10", "5"))
                .thenReturn(List.of(reservation("res-1"), reservation("res-2")));

        assertThat(service.processVlmReport(parsed(), scope())).isEqualTo(VlmVerificationStatus.UNVERIFIABLE);
        verifyNoInteractions(audits);
    }

    @Test
    void extractionFailureNeverCreatesAnAudit() {
        VlmAnalysisService.VlmParsedData failed = new VlmAnalysisService.VlmParsedData(
                null, null, null, null, "reporter-1", List.of("image-1"), RECEIVED_AT,
                VlmAnalysisService.ExtractionStatus.FAILED);

        assertThat(service.processVlmReport(failed, scope())).isEqualTo(VlmVerificationStatus.EXTRACTION_FAILED);
        verifyNoInteractions(events, reservations, audits);
    }

    @Test
    void operationalLogContainsStatusWithoutReporterAccountOrImage() {
        Logger logger = (Logger) LoggerFactory.getLogger(VlmAnalysisService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            when(events.findTenantId(EVENT_A)).thenReturn(Optional.of(TENANT_A));
            when(reservations.findTop2ByTenantIdAndEventIdAndPerformanceRefAndZoneAndRowNumAndSeatNum(
                    TENANT_A, EVENT_A, "round-1", "VIP", "10", "5"))
                    .thenReturn(List.of(reservation("res-1")));

            assertThat(service.processVlmReport(parsed(), scope())).isEqualTo(VlmVerificationStatus.MATCH_CANDIDATE);
            assertThat(appender.list).hasSize(1);
            String message = appender.list.getFirst().getFormattedMessage();
            assertThat(message).contains("MATCH_CANDIDATE", "요청 ID:");
            assertThat(message).doesNotContain("reporter-1", "account-1", "image-1", "res-1", "VIP");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private VlmAnalysisService.VlmParsedData parsed() {
        return new VlmAnalysisService.VlmParsedData(
                "VIP", "10", "5", 0.95, "reporter-1", List.of("image-1"), RECEIVED_AT);
    }

    private VlmAnalysisService.ReportScope scope() {
        return new VlmAnalysisService.ReportScope(TENANT_A, EVENT_A, "round-1");
    }

    private Reservation reservation(String reservationNo) {
        return new Reservation(TENANT_A, EVENT_A, "round-1", reservationNo,
                "account-1", "VIP", "10", "5", 100_000L);
    }
}
