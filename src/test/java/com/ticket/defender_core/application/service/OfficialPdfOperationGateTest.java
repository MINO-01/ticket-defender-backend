package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.out.persistence.CaseSourceReferenceRepository;
import com.ticket.defender_core.adapter.out.persistence.EvidenceAssetRepository;
import com.ticket.defender_core.adapter.out.persistence.FanReportRepository;
import com.ticket.defender_core.adapter.out.persistence.OfficialPdfSyntheticTenantRepository;
import com.ticket.defender_core.domain.Case;
import com.ticket.defender_core.domain.CaseSourceReference;
import com.ticket.defender_core.domain.EvidenceAsset;
import com.ticket.defender_core.domain.FanReport;
import com.ticket.defender_core.domain.OfficialPdfSyntheticTenant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/** 설정값만으로는 공식 PDF 발급이 열리지 않고, 서버가 확인할 수 있는 격리 근거와 합성 고객사 등록이 모두 필요함을 검증합니다. */
class OfficialPdfOperationGateTest {
    private static final String ISOLATION = UUID.randomUUID().toString();
    private static final String MYSQL = "jdbc:mysql://127.0.0.1:13307/td_issue32_ci?useSSL=false";
    private static final String MINIO = "http://127.0.0.1:19000";
    private static final LocalDateTime REGISTERED = LocalDateTime.now(ZoneOffset.UTC).minusHours(1);

    private final OfficialPdfSyntheticTenantRepository registry = mock(OfficialPdfSyntheticTenantRepository.class);
    private final CaseSourceReferenceRepository sources = mock(CaseSourceReferenceRepository.class);
    private final FanReportRepository reports = mock(FanReportRepository.class);
    private final EvidenceAssetRepository assets = mock(EvidenceAssetRepository.class);
    private final UUID tenant = UUID.randomUUID();
    private final UUID caseId = UUID.randomUUID();

    @BeforeEach
    void registry() {
        given(registry.findById(tenant)).willReturn(Optional.of(
                new OfficialPdfSyntheticTenant(tenant, UUID.fromString(ISOLATION), REGISTERED)));
        given(sources.findByCaseIdOrderByLinkedAtAscIdAsc(caseId)).willReturn(List.of());
    }

    private OfficialPdfOperationGate gate(boolean enabled, String mode, String isolation, String datasource,
            String officialEndpoint, String fanReportEndpoint, String preservationEndpoint) {
        return new OfficialPdfOperationGate(enabled, mode, isolation, datasource, officialEndpoint,
                fanReportEndpoint, preservationEndpoint, registry, sources, reports, assets);
    }

    private OfficialPdfOperationGate verified() {
        return gate(true, "SYNTHETIC_ISOLATED", ISOLATION, MYSQL, MINIO, MINIO, MINIO);
    }

    private static void assertCode(Runnable call, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(CaseApiException.class,
                error -> assertThat(error.code()).isEqualTo(code));
    }

    @Test
    void defaultAndFlagOnlyAndModeStringOnlyConfigurationsStayBlocked() {
        assertCode(gate(false, "DISABLED", "", "", "", "", "")::requireEnabled, "OFFICIAL_PDF_DISABLED");
        assertCode(gate(true, "DISABLED", "", MYSQL, MINIO, MINIO, MINIO)::requireEnabled,
                "OFFICIAL_PDF_OPERATION_NOT_APPROVED");
        // 모드 문자열만 지정하고 격리 식별자가 없으면 서버 소유 근거와 대조할 수 없습니다.
        OfficialPdfOperationGate modeOnly = gate(true, "SYNTHETIC_ISOLATED", "", MYSQL, MINIO, MINIO, MINIO);
        assertCode(modeOnly::requireEnabled, "OFFICIAL_PDF_ISOLATION_UNVERIFIED");
        assertThat(modeOnly.enabledForSyntheticIsolation()).isFalse();
        assertCode(gate(true, "SYNTHETIC_ISOLATED", "not-a-uuid", MYSQL, MINIO, MINIO, MINIO)::requireEnabled,
                "OFFICIAL_PDF_ISOLATION_UNVERIFIED");
    }

    @Test
    void nonLoopbackDatabaseOrEvidenceStorageCannotClaimSyntheticIsolation() {
        for (String datasource : List.of("", "jdbc:mysql://db.internal:3306/prod",
                "jdbc:mysql://127.0.0.1:3306,db.internal:3306/prod", "jdbc:mysql://10.0.0.5/prod",
                "jdbc:h2:file:/var/data/prod", "jdbc:postgresql://127.0.0.1/prod",
                "jdbc:mysql://127.0.0.1.evil.example:3306/prod")) {
            assertCode(gate(true, "SYNTHETIC_ISOLATED", ISOLATION, datasource, MINIO, MINIO, MINIO)::requireEnabled,
                    "OFFICIAL_PDF_ISOLATION_UNVERIFIED");
        }
        String remote = "https://s3.ap-northeast-2.amazonaws.com";
        assertCode(gate(true, "SYNTHETIC_ISOLATED", ISOLATION, MYSQL, remote, MINIO, MINIO)::requireEnabled,
                "OFFICIAL_PDF_ISOLATION_UNVERIFIED");
        assertCode(gate(true, "SYNTHETIC_ISOLATED", ISOLATION, MYSQL, MINIO, remote, MINIO)::requireEnabled,
                "OFFICIAL_PDF_ISOLATION_UNVERIFIED");
        assertCode(gate(true, "SYNTHETIC_ISOLATED", ISOLATION, MYSQL, MINIO, MINIO, remote)::requireEnabled,
                "OFFICIAL_PDF_ISOLATION_UNVERIFIED");
        assertCode(gate(true, "SYNTHETIC_ISOLATED", ISOLATION, MYSQL, "http://user@127.0.0.1:19000", MINIO, MINIO)
                ::requireEnabled, "OFFICIAL_PDF_ISOLATION_UNVERIFIED");
    }

    @Test
    void loopbackMysqlOrInMemoryH2WithLoopbackOrUnsetStorageIsAnIsolatedEnvironment() {
        assertThatCode(verified()::requireEnabled).doesNotThrowAnyException();
        assertThat(verified().enabledForSyntheticIsolation()).isTrue();
        assertThatCode(gate(true, "SYNTHETIC_ISOLATED", ISOLATION, "jdbc:mysql://localhost/td", "", "", "")
                ::requireEnabled).doesNotThrowAnyException();
        assertThatCode(gate(true, "SYNTHETIC_ISOLATED", ISOLATION, "jdbc:h2:mem:w09;MODE=MySQL", MINIO, "", "")
                ::requireEnabled).doesNotThrowAnyException();
    }

    @Test
    void verifiedEnvironmentStillRequiresServerRegisteredSyntheticTenantWithSameIsolation() {
        Case target = target(REGISTERED.plusMinutes(5));
        assertThatCode(() -> verified().requireSyntheticScope(target)).doesNotThrowAnyException();

        Case unregistered = mock(Case.class);
        given(unregistered.getTenantId()).willReturn(UUID.randomUUID());
        given(unregistered.getId()).willReturn(caseId);
        given(unregistered.getCreatedAt()).willReturn(REGISTERED.plusMinutes(5));
        assertCode(() -> verified().requireSyntheticScope(unregistered), "OFFICIAL_PDF_SYNTHETIC_SCOPE_UNVERIFIED");

        OfficialPdfOperationGate otherIsolation = gate(true, "SYNTHETIC_ISOLATED", UUID.randomUUID().toString(),
                MYSQL, MINIO, MINIO, MINIO);
        assertCode(() -> otherIsolation.requireSyntheticScope(target), "OFFICIAL_PDF_SYNTHETIC_SCOPE_UNVERIFIED");
        // 환경 검증이 실패하면 고객사 등록이 있어도 범위 검증으로 넘어가지 않습니다.
        assertCode(() -> gate(true, "SYNTHETIC_ISOLATED", ISOLATION, "jdbc:mysql://db.internal/prod", MINIO, MINIO,
                MINIO).requireSyntheticScope(target), "OFFICIAL_PDF_ISOLATION_UNVERIFIED");
    }

    @Test
    void caseOrSourceEvidenceCreatedBeforeRegistrationIsNotTreatedAsSynthetic() {
        assertCode(() -> verified().requireSyntheticScope(target(REGISTERED.minusSeconds(1))),
                "OFFICIAL_PDF_SYNTHETIC_SCOPE_UNVERIFIED");

        UUID fanReportId = UUID.randomUUID();
        UUID evidenceId = UUID.randomUUID();
        CaseSourceReference source = mock(CaseSourceReference.class);
        given(source.getSourceType()).willReturn(CaseSourceReference.Type.EVIDENCE_ASSET);
        given(source.getTenantId()).willReturn(tenant);
        given(source.getFanReportId()).willReturn(fanReportId);
        given(source.getEvidenceId()).willReturn(evidenceId);
        given(source.getLinkedAt()).willReturn(REGISTERED.plusMinutes(6));
        given(sources.findByCaseIdOrderByLinkedAtAscIdAsc(caseId)).willReturn(List.of(source));
        FanReport report = mock(FanReport.class);
        given(report.getTenantId()).willReturn(tenant);
        given(report.getReceivedAt()).willReturn(REGISTERED.plusMinutes(1));
        given(reports.findById(fanReportId)).willReturn(Optional.of(report));
        EvidenceAsset asset = mock(EvidenceAsset.class);
        given(asset.getFanReportId()).willReturn(fanReportId);
        given(asset.getCreatedAt()).willReturn(REGISTERED.plusMinutes(1));
        given(assets.findById(evidenceId)).willReturn(Optional.of(asset));
        Case target = target(REGISTERED.plusMinutes(5));
        assertThatCode(() -> verified().requireSyntheticScope(target)).doesNotThrowAnyException();

        // 등록 전에 접수된 실제 제보를 나중에 합성 고객사로 등록해도 발급 근거로 쓰지 않습니다.
        given(report.getReceivedAt()).willReturn(REGISTERED.minusDays(1));
        assertCode(() -> verified().requireSyntheticScope(target), "OFFICIAL_PDF_SYNTHETIC_SCOPE_UNVERIFIED");
        given(report.getReceivedAt()).willReturn(REGISTERED.plusMinutes(1));
        given(asset.getCreatedAt()).willReturn(REGISTERED.minusDays(1));
        assertCode(() -> verified().requireSyntheticScope(target), "OFFICIAL_PDF_SYNTHETIC_SCOPE_UNVERIFIED");
        // 출처 행이 사라지거나 다른 고객사 소속이면 확인할 수 없으므로 차단합니다.
        given(assets.findById(evidenceId)).willReturn(Optional.empty());
        assertCode(() -> verified().requireSyntheticScope(target), "OFFICIAL_PDF_SYNTHETIC_SCOPE_UNVERIFIED");
        given(assets.findById(evidenceId)).willReturn(Optional.of(asset));
        given(asset.getCreatedAt()).willReturn(REGISTERED.plusMinutes(1));
        given(report.getTenantId()).willReturn(UUID.randomUUID());
        assertCode(() -> verified().requireSyntheticScope(target), "OFFICIAL_PDF_SYNTHETIC_SCOPE_UNVERIFIED");
    }

    @Test
    void storedClusterSourceAnalyzedBeforeRegistrationIsNotTreatedAsSynthetic() {
        CaseSourceReference cluster = mock(CaseSourceReference.class);
        given(cluster.getSourceType()).willReturn(CaseSourceReference.Type.STORED_CLUSTER_EVIDENCE);
        given(cluster.getTenantId()).willReturn(tenant);
        given(cluster.getLinkedAt()).willReturn(REGISTERED.plusMinutes(6));
        given(cluster.getAnalysisCompletedAt()).willReturn(REGISTERED.minusMinutes(1));
        given(sources.findByCaseIdOrderByLinkedAtAscIdAsc(caseId)).willReturn(List.of(cluster));
        assertCode(() -> verified().requireSyntheticScope(target(REGISTERED.plusMinutes(5))),
                "OFFICIAL_PDF_SYNTHETIC_SCOPE_UNVERIFIED");
        given(cluster.getAnalysisCompletedAt()).willReturn(REGISTERED.plusMinutes(1));
        assertThatCode(() -> verified().requireSyntheticScope(target(REGISTERED.plusMinutes(5))))
                .doesNotThrowAnyException();
    }

    private Case target(LocalDateTime createdAt) {
        Case target = mock(Case.class);
        given(target.getId()).willReturn(caseId);
        given(target.getTenantId()).willReturn(tenant);
        given(target.getCreatedAt()).willReturn(createdAt);
        return target;
    }
}
