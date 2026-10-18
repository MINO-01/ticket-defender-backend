package com.ticket.defender_core.adapter.out.persistence;

import com.ticket.defender_core.domain.AuditStatus;
import com.ticket.defender_core.domain.EvidenceType;
import com.ticket.defender_core.domain.MacroAnalysisEvidence;
import com.ticket.defender_core.domain.MacroAuditEvidence;
import com.ticket.defender_core.domain.MacroEvidenceKey;
import com.ticket.defender_core.domain.TicketAudit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DataJpaTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:macro-audit-test;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false"
})
class MacroAuditEvidenceRepositoryTest {

    private static final String RESERVATION_NO = "RES-1001";
    private static final String ACCOUNT_ID = "uid-1001";
    private static final String EVENT_ID = "event-2026-001";
    private static final UUID TENANT_A = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID TENANT_B = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID EVENT_A = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID EVENT_B = UUID.fromString("44444444-4444-4444-8444-444444444444");

    @Autowired
    private TicketAuditRepository ticketAuditRepository;

    @Autowired
    private MacroAuditEvidenceRepository macroAuditEvidenceRepository;

    @Autowired
    private JdbcTemplate jdbc;

    /** 같은 매크로 식별 조합은 한 번만 저장됩니다. */
    @Test
    @DisplayName("같은 공연·예매·계정의 매크로 증거는 DB 유니크 키로 중복 저장을 차단한다")
    void saveMacroEvidence_rejectsDuplicateIdentityAtDatabase() {
        // given
        ticketAuditRepository.saveAndFlush(macroAudit("cluster-1"));
        TicketAudit duplicate = macroAudit("cluster-2");

        // when / then
        assertThrows(DataIntegrityViolationException.class,
                () -> ticketAuditRepository.saveAndFlush(duplicate));
    }

    @Test
    void sameExternalIdentifiersInDifferentTenantsHaveIndependentEvidence() {
        ticketAuditRepository.saveAndFlush(macroAudit("cluster-a", TENANT_A, EVENT_A));
        ticketAuditRepository.saveAndFlush(macroAudit("cluster-b", TENANT_B, EVENT_B));

        List<MacroAuditEvidence> firstTenant = macroAuditEvidenceRepository
                .findAllByTenantIdAndInternalEventId(TENANT_A, EVENT_A);
        List<MacroAuditEvidence> secondTenant = macroAuditEvidenceRepository
                .findAllByTenantIdAndInternalEventId(TENANT_B, EVENT_B);
        assertThat(firstTenant).hasSize(1);
        assertThat(secondTenant).hasSize(1);
        assertThat(firstTenant.getFirst().getEvidenceKey()).isNotEqualTo(secondTenant.getFirst().getEvidenceKey());
        assertThat(firstTenant.getFirst().getReservationNo()).isEqualTo(secondTenant.getFirst().getReservationNo());
    }

    @Test
    void legacyUnattributedEvidenceDoesNotBlockOrAppearInScopedLookup() {
        jdbc.update("INSERT INTO ticket_audit (account_id,reservation_no,evidence_type,status) VALUES (?,?,?,?)",
                ACCOUNT_ID, RESERVATION_NO, "MACRO_GRAPH", "FRAUD_DETECTED");
        Long auditId = jdbc.queryForObject("SELECT MAX(id) FROM ticket_audit", Long.class);
        String legacyKey = MacroEvidenceKey.create(EVENT_ID, RESERVATION_NO, ACCOUNT_ID);
        jdbc.update("""
                INSERT INTO macro_audit_evidence (audit_id,evidence_key,event_id,reservation_no,account_id,
                cluster_id,risk_score,analysis_algorithm,analysis_algorithm_version,analysis_request_id,
                analysis_completed_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """, auditId, legacyKey, EVENT_ID, RESERVATION_NO, ACCOUNT_ID,
                "legacy-cluster", 0.91, "LOUVAIN", "1.0.0", "legacy-request",
                LocalDateTime.parse("2026-10-08T10:15:30"));

        ticketAuditRepository.saveAndFlush(macroAudit("cluster-new"));

        assertThat(macroAuditEvidenceRepository.findAllByTenantIdAndInternalEventId(TENANT_A, EVENT_A))
                .hasSize(1)
                .allSatisfy(item -> assertThat(item.getEvidenceKey()).isNotEqualTo(legacyKey));
        MacroAuditEvidence legacy = macroAuditEvidenceRepository.findAllByEvidenceKeyIn(List.of(legacyKey)).getFirst();
        assertThat(legacy.getTenantId()).isNull();
        assertThat(legacy.getInternalEventId()).isNull();
    }

    /** 팬 제보 반려는 매크로 감사 상태에 영향을 주지 않습니다. */
    @Test
    @DisplayName("팬 제보 반려 쿼리는 같은 예매 번호의 매크로 감사 내역을 변경하지 않는다")
    void rejectFanDuplicates_leavesMacroAuditUntouched() {
        // given
        TicketAudit approvedReport = fanReport("reporter-first");
        TicketAudit laterReport = fanReport("reporter-later");
        TicketAudit macroAudit = macroAudit("cluster-1");
        ticketAuditRepository.saveAndFlush(approvedReport);
        ticketAuditRepository.saveAndFlush(laterReport);
        ticketAuditRepository.saveAndFlush(macroAudit);

        // when
        List<TicketAudit> candidates = ticketAuditRepository
                .findByReservationNoAndIdNotAndEvidenceTypeAndStatus(
                        RESERVATION_NO,
                        approvedReport.getId(),
                        EvidenceType.FAN_REPORT,
                        AuditStatus.FRAUD_DETECTED
                );
        int updatedCount = ticketAuditRepository.rejectDuplicateAudits(
                RESERVATION_NO,
                approvedReport.getId(),
                EvidenceType.FAN_REPORT,
                AuditStatus.DUPLICATED,
                AuditStatus.FRAUD_DETECTED
        );

        // then
        assertThat(candidates).extracting(TicketAudit::getId).containsExactly(laterReport.getId());
        assertThat(updatedCount).isEqualTo(1);
        assertThat(ticketAuditRepository.findById(laterReport.getId()).orElseThrow().getStatus())
                .isEqualTo(AuditStatus.DUPLICATED);
        assertThat(ticketAuditRepository.findById(macroAudit.getId()).orElseThrow().getStatus())
                .isEqualTo(AuditStatus.FRAUD_DETECTED);
    }

    /** 팬 제보는 매크로 증거 없이 저장됩니다. */
    @Test
    @DisplayName("팬 제보는 매크로 전용 증거 키 인덱스에 영향을 주지 않는다")
    void saveFanReport_doesNotRequireMacroEvidence() {
        // given / when
        TicketAudit fanReport = ticketAuditRepository.saveAndFlush(fanReport("reporter-only"));

        // then
        assertThat(fanReport.getEvidenceType()).isEqualTo(EvidenceType.FAN_REPORT);
        assertThat(fanReport.getMacroAuditEvidence()).isNull();
        assertThat(macroAuditEvidenceRepository.count()).isZero();
    }

    /** 테스트용 매크로 감사 내역을 만듭니다. */
    private TicketAudit macroAudit(String clusterId) {
        return macroAudit(clusterId, TENANT_A, EVENT_A);
    }

    private TicketAudit macroAudit(String clusterId, UUID tenantId, UUID internalEventId) {
        return TicketAudit.createMacroAudit(new MacroAnalysisEvidence(
                ACCOUNT_ID,
                RESERVATION_NO,
                EVENT_ID,
                "a".repeat(64),
                null,
                "b".repeat(64),
                null,
                clusterId,
                0.91,
                "LOUVAIN",
                "1.0.0",
                "request-" + clusterId,
                LocalDateTime.parse("2026-10-08T10:15:30"),
                tenantId,
                internalEventId
        ));
    }

    /** 테스트용 팬 제보 내역을 만듭니다. */
    private TicketAudit fanReport(String reporterId) {
        return TicketAudit.createVlmAudit(
                RESERVATION_NO,
                ACCOUNT_ID,
                0.95,
                reporterId,
                List.of("evidence.png"),
                LocalDateTime.parse("2026-10-08T10:20:00")
        );
    }
}
