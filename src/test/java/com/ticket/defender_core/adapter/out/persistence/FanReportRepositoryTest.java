package com.ticket.defender_core.adapter.out.persistence;

import com.ticket.defender_core.domain.AccessRecordStatus;
import com.ticket.defender_core.domain.EvidenceAsset;
import com.ticket.defender_core.domain.FanReport;
import com.ticket.defender_core.domain.Tenant;
import com.ticket.defender_core.domain.TenantEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:fan-report-repository;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class FanReportRepositoryTest {
    @Autowired private TenantRepository tenants;
    @Autowired private TenantEventRepository events;
    @Autowired private FanReportRepository reports;
    @Autowired private EvidenceAssetRepository assets;

    @Test
    void publicIntakeDefaultsClosedAndReportEvidenceIdsAreDistinct() {
        UUID tenant = UUID.randomUUID();
        UUID event = UUID.randomUUID();
        tenants.save(new Tenant(tenant, "Synthetic tenant", AccessRecordStatus.ACTIVE));
        TenantEvent storedEvent = events.saveAndFlush(new TenantEvent(event, tenant, "ref", "Synthetic event",
                AccessRecordStatus.ACTIVE));
        assertThat(storedEvent.isPublicIntakeEnabled()).isFalse();

        FanReport report = report(UUID.randomUUID(), tenant, event, "FR-synthetic-1", "a".repeat(64));
        reports.saveAndFlush(report);
        EvidenceAsset asset = new EvidenceAsset(UUID.randomUUID(), report.getFanReportId(),
                "fan-reports/synthetic/asset", "image/png", 4, "b".repeat(64),
                LocalDateTime.parse("2026-10-20T12:00:00"), LocalDateTime.parse("2027-01-18T12:00:00"));
        assets.saveAndFlush(asset);
        assertThat(asset.getEvidenceId()).isNotEqualTo(report.getFanReportId());
        assertThat(assets.findByFanReportIdOrderByCreatedAtAsc(report.getFanReportId()))
                .extracting(EvidenceAsset::getEvidenceId).containsExactly(asset.getEvidenceId());
        assertThat(reports.findByReceiptNumber(report.getReceiptNumber()))
                .map(FanReport::getFanReportId).contains(report.getFanReportId());
        assertThat(reports.findByIdempotencyKeyDigest(report.getIdempotencyKeyDigest()))
                .map(FanReport::getFanReportId).contains(report.getFanReportId());
    }

    @Test
    void receiptAndIdempotencyDigestsAreUnique() {
        UUID tenant = UUID.randomUUID();
        UUID event = UUID.randomUUID();
        reports.saveAndFlush(report(UUID.randomUUID(), tenant, event, "FR-synthetic-2", "c".repeat(64)));
        assertThatThrownBy(() -> reports.saveAndFlush(report(UUID.randomUUID(), tenant, event,
                "FR-synthetic-3", "c".repeat(64)))).isInstanceOf(DataIntegrityViolationException.class);
    }

    private FanReport report(UUID id, UUID tenant, UUID event, String receipt, String keyDigest) {
        return new FanReport(id, receipt, tenant, event, LocalDateTime.parse("2026-11-01T19:30:00"),
                null, FanReport.ReportType.OTHER, "A synthetic suspected listing description", null,
                null, null, null, LocalDateTime.parse("2026-10-20T12:00:00"), keyDigest,
                "d".repeat(64), "e".repeat(64));
    }
}
