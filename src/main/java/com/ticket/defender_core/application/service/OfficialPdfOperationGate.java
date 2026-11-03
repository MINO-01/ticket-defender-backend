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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 공식 PDF 발급의 합성 격리 게이트입니다. 기능 플래그와 운영 모드 문자열만으로는 발급을 열지 않습니다.
 *
 * <p>환경 조건: 기능 활성화, {@code SYNTHETIC_ISOLATED} 모드, UUID 격리 식별자, loopback MySQL 또는
 * 메모리 H2 데이터소스, 공식 PDF와 제보 원본과 W07 보존 저장소 endpoint가 모두 비어 있거나 loopback이어야 합니다.
 * 범위 조건: Case 고객사가 서버 소유 등록부에 같은 격리 식별자로 등록되어 있고, Case와 모든 출처가 등록 이후에
 * 생성되어야 합니다. 하나라도 확인할 수 없으면 차단합니다.
 *
 * <p>한계: 이 조건은 실제 개인정보가 아니라는 사실을 증명하지 않습니다. DB 관리자의 등록 행 삽입이나 loopback
 * 프록시 위장은 막을 수 없으며, 설정 하나로 실데이터 발급이 열리지 않도록 여러 독립 조치를 요구할 뿐입니다.
 * 실운영 발급은 별도 정책 승인 전까지 허용하지 않습니다.
 */
@Component
public class OfficialPdfOperationGate {
    static final String DISABLED = "OFFICIAL_PDF_DISABLED";
    static final String NOT_APPROVED = "OFFICIAL_PDF_OPERATION_NOT_APPROVED";
    static final String ISOLATION_UNVERIFIED = "OFFICIAL_PDF_ISOLATION_UNVERIFIED";
    static final String SCOPE_UNVERIFIED = "OFFICIAL_PDF_SYNTHETIC_SCOPE_UNVERIFIED";
    private static final String SYNTHETIC_ISOLATED = "SYNTHETIC_ISOLATED";
    private static final Set<String> LOOPBACK = Set.of("127.0.0.1", "localhost", "[::1]", "::1");
    /** 단일 호스트 MySQL URL만 허용합니다. 쉼표 다중 호스트나 사용자 정보는 호스트로 해석하지 않습니다. */
    private static final Pattern MYSQL = Pattern.compile("^jdbc:mysql://([^/:,@?]+|\\[::1\\])(?::\\d{1,5})?(?:/.*)?$");

    private final boolean enabled;
    private final String operationalMode;
    private final UUID isolationId;
    private final boolean isolatedEnvironment;
    private final OfficialPdfSyntheticTenantRepository syntheticTenants;
    private final CaseSourceReferenceRepository sources;
    private final FanReportRepository fanReports;
    private final EvidenceAssetRepository assets;

    public OfficialPdfOperationGate(
            @Value("${ticket-defender.official-pdf.enabled:false}") boolean enabled,
            @Value("${ticket-defender.official-pdf.operational-mode:DISABLED}") String operationalMode,
            @Value("${ticket-defender.official-pdf.synthetic-isolation-id:}") String isolationId,
            @Value("${spring.datasource.url:}") String datasourceUrl,
            @Value("${ticket-defender.official-pdf.storage.endpoint:}") String officialPdfEndpoint,
            @Value("${ticket-defender.fan-report.storage.endpoint:}") String fanReportEndpoint,
            @Value("${ticket-defender.evidence-preservation.storage.endpoint:}") String preservationEndpoint,
            OfficialPdfSyntheticTenantRepository syntheticTenants,
            CaseSourceReferenceRepository sources,
            FanReportRepository fanReports,
            EvidenceAssetRepository assets) {
        this.enabled = enabled;
        this.operationalMode = operationalMode;
        this.isolationId = parseUuid(isolationId);
        this.isolatedEnvironment = this.isolationId != null && isolatedDatasource(datasourceUrl)
                && unsetOrLoopback(officialPdfEndpoint) && unsetOrLoopback(fanReportEndpoint)
                && unsetOrLoopback(preservationEndpoint);
        this.syntheticTenants = syntheticTenants;
        this.sources = sources;
        this.fanReports = fanReports;
        this.assets = assets;
    }

    /** 환경 조건만 확인합니다. 고객사 범위는 {@link #requireSyntheticScope(Case)}로 따로 확인해야 합니다. */
    public boolean enabledForSyntheticIsolation() {
        return enabled && SYNTHETIC_ISOLATED.equals(operationalMode) && isolatedEnvironment;
    }

    public void requireEnabled() {
        if (!enabled) throw conflict(DISABLED);
        if (!SYNTHETIC_ISOLATED.equals(operationalMode)) throw conflict(NOT_APPROVED);
        if (!isolatedEnvironment) throw conflict(ISOLATION_UNVERIFIED);
    }

    /**
     * 환경 조건과 함께 대상 Case가 서버 소유 합성 등록부 범위에 속하는지 확인합니다. 조회와 렌더링 준비처럼
     * 상태를 확정하지 않는 경로에서 사용하며 결과를 캐시하지 않습니다.
     */
    public void requireSyntheticScope(Case target) {
        requireSyntheticScope(target, false);
    }

    /**
     * 발급 예약, 최종화, 다운로드 감사처럼 상태를 확정하는 쓰기 트랜잭션 안에서 사용합니다. Case 잠금 다음에 등록
     * 행을 공유 잠금으로 읽어, 트랜잭션이 끝날 때까지 등록 해제가 확정되지 못하게 합니다. 이미 확정된 해제는
     * 최신 상태로 보이므로 차단됩니다. 호출자는 활성 쓰기 트랜잭션 안에 있어야 합니다.
     */
    public void requireSyntheticScopeLocked(Case target) {
        requireSyntheticScope(target, true);
    }

    private void requireSyntheticScope(Case target, boolean lockRegistration) {
        requireEnabled();
        if (target == null || target.getTenantId() == null || target.getCreatedAt() == null) throw conflict(SCOPE_UNVERIFIED);
        OfficialPdfSyntheticTenant registration = (lockRegistration
                ? syntheticTenants.findByTenantIdForShare(target.getTenantId())
                : syntheticTenants.findById(target.getTenantId())).orElse(null);
        if (registration == null || !isolationId.equals(registration.getIsolationId())) throw conflict(SCOPE_UNVERIFIED);
        LocalDateTime registeredAt = registration.getRegisteredAt();
        if (!notBefore(target.getCreatedAt(), registeredAt)) throw conflict(SCOPE_UNVERIFIED);
        for (CaseSourceReference source : sources.findByCaseIdOrderByLinkedAtAscIdAsc(target.getId())) {
            if (!createdAfterRegistration(target, source, registeredAt)) throw conflict(SCOPE_UNVERIFIED);
        }
    }

    /** 등록 전에 존재하던 실제 데이터를 나중에 합성 고객사로 등록해도 발급 근거로 쓰지 않도록 출처 생성 시각을 확인합니다. */
    private boolean createdAfterRegistration(Case target, CaseSourceReference source, LocalDateTime registeredAt) {
        if (!target.getTenantId().equals(source.getTenantId()) || !notBefore(source.getLinkedAt(), registeredAt))
            return false;
        return switch (source.getSourceType()) {
            case STORED_CLUSTER_EVIDENCE -> notBefore(source.getAnalysisCompletedAt(), registeredAt);
            case FAN_REPORT -> fanReportAfter(target, source.getFanReportId(), registeredAt);
            case EVIDENCE_ASSET -> fanReportAfter(target, source.getFanReportId(), registeredAt)
                    && evidenceAfter(source.getFanReportId(), source.getEvidenceId(), registeredAt);
        };
    }

    private boolean fanReportAfter(Case target, UUID fanReportId, LocalDateTime registeredAt) {
        if (fanReportId == null) return false;
        FanReport report = fanReports.findById(fanReportId).orElse(null);
        return report != null && target.getTenantId().equals(report.getTenantId())
                && notBefore(report.getReceivedAt(), registeredAt);
    }

    private boolean evidenceAfter(UUID fanReportId, UUID evidenceId, LocalDateTime registeredAt) {
        if (evidenceId == null) return false;
        EvidenceAsset asset = assets.findById(evidenceId).orElse(null);
        return asset != null && Objects.equals(fanReportId, asset.getFanReportId())
                && notBefore(asset.getCreatedAt(), registeredAt);
    }

    private static boolean notBefore(LocalDateTime value, LocalDateTime registeredAt) {
        return value != null && !value.isBefore(registeredAt);
    }

    /** 메모리 H2는 시작할 때 비어 있고 외부와 공유되지 않으므로 격리 DB로 봅니다. 파일 H2와 원격 DB는 허용하지 않습니다. */
    static boolean isolatedDatasource(String url) {
        if (url == null || url.isBlank()) return false;
        String value = url.trim();
        if (value.toLowerCase(Locale.ROOT).startsWith("jdbc:h2:mem:")) return true;
        Matcher matcher = MYSQL.matcher(value);
        return matcher.matches() && LOOPBACK.contains(matcher.group(1).toLowerCase(Locale.ROOT));
    }

    /** 저장소가 설정되지 않았으면 해당 경로는 동작하지 않으므로 허용하고, 설정되었다면 사용자 정보 없는 loopback만 허용합니다. */
    static boolean unsetOrLoopback(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) return true;
        try {
            URI uri = URI.create(endpoint.trim());
            return ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) && uri.getUserInfo() == null
                    && uri.getHost() != null && LOOPBACK.contains(uri.getHost().toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            UUID parsed = UUID.fromString(value.trim());
            return parsed.toString().equals(value.trim().toLowerCase(Locale.ROOT)) ? parsed : null;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static CaseApiException conflict(String code) {
        return new CaseApiException(HttpStatus.CONFLICT, code);
    }
}
