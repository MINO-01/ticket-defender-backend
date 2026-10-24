package com.ticket.defender_core.integration;

import com.ticket.defender_core.adapter.out.persistence.EvidenceSnapshotRepository;
import com.ticket.defender_core.adapter.out.persistence.ReportVersionRepository;
import com.ticket.defender_core.domain.ReportVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs after the additive W05 SQL on a disposable MySQL 8 CI database. */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@EnabledIfEnvironmentVariable(named = "TD_ISSUE32_MYSQL_URL",
        matches = "jdbc:mysql://127\\.0\\.0\\.1:13307/td_issue32.*")
class CaseW05SchemaValidateMySqlTest {
    @Autowired private EvidenceSnapshotRepository snapshots;
    @Autowired private ReportVersionRepository drafts;
    @Autowired private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("TD_ISSUE32_MYSQL_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("TD_ISSUE32_MYSQL_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("TD_ISSUE32_MYSQL_PASSWORD"));
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    }

    @Test
    void validatesMappingsAndReadsMigratedVersions() {
        String snapshotId = jdbc.queryForObject("SELECT id FROM evidence_snapshots "
                + "WHERE manifest_sha256=? AND manifest_json=?", String.class, "a".repeat(64), "{}");
        var snapshot = snapshots.findById(UUID.fromString(snapshotId)).orElseThrow();
        String draftId = jdbc.queryForObject("SELECT id FROM report_versions "
                + "WHERE evidence_snapshot_id=? AND content_sha256=?", String.class,
                snapshotId, "b".repeat(64));
        var draft = drafts.findById(UUID.fromString(draftId)).orElseThrow();
        assertThat(snapshot.getCaseId()).isEqualTo(draft.getCaseId());
        assertThat(snapshot.getTenantId()).isEqualTo(draft.getTenantId());
        assertThat(snapshot.getEventId()).isEqualTo(draft.getEventId());
        assertThat(snapshot.getEvidenceRevision()).isEqualTo(1);
        assertThat(draft.getEvidenceRevision()).isEqualTo(1);
        assertThat(draft.getRevision()).isEqualTo(1);
        assertThat(draft.getStatus()).isEqualTo(ReportVersion.Status.DRAFT.name());
    }
}
