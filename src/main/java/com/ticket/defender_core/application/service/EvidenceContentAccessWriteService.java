package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.out.persistence.AdminMembershipRepository;
import com.ticket.defender_core.adapter.out.persistence.EvidenceAccessAuditEventRepository;
import com.ticket.defender_core.adapter.out.persistence.EvidenceAssetRepository;
import com.ticket.defender_core.adapter.out.persistence.FanReportRepository;
import com.ticket.defender_core.adapter.out.persistence.MembershipEventGrantRepository;
import com.ticket.defender_core.domain.AccessRecordStatus;
import com.ticket.defender_core.domain.AdminMembershipEntity;
import com.ticket.defender_core.domain.EvidenceAccessAuditEvent;
import com.ticket.defender_core.domain.EvidenceAsset;
import com.ticket.defender_core.domain.FanReport;
import com.ticket.defender_core.security.AdminAccessService;
import com.ticket.defender_core.security.AdminActor;
import com.ticket.defender_core.security.AdminResourceScope;
import com.ticket.defender_core.security.AdminRole;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 관리자 원본 열람의 DB 작업만 짧은 트랜잭션으로 나눠 처리합니다.
 * 저장소 읽기는 이 클래스 밖에서 트랜잭션 없이 수행합니다.
 */
@Service
@RequiredArgsConstructor
public class EvidenceContentAccessWriteService {
    /** 기존 관리자 팬 제보 조회와 Listing 분석 이력 API와 같은 역할입니다. */
    static final Set<AdminRole> ROLES = Set.of(AdminRole.ANALYST, AdminRole.REVIEWER);
    private static final Set<String> MEDIA_TYPES = Set.of("image/jpeg", "image/png");

    private final AdminAccessService access;
    private final CaseMembershipAccess memberships;
    private final FanReportRepository reports;
    private final EvidenceAssetRepository assets;
    private final EvidenceAccessAuditEventRepository audits;
    private final AdminMembershipRepository membershipRecords;
    private final MembershipEventGrantRepository grants;

    /** 열람 시점에 확인한 원본 정보입니다. objectKey는 서버 내부에서만 쓰고 응답과 감사 기록에 넣지 않습니다. */
    public record Source(UUID evidenceId, String objectKey, String objectVersion, String mediaType, long byteSize,
                         String sha256, EvidenceAsset.StorageStatus storageStatus, LocalDateTime assetRetentionUntil,
                         FanReport.IntakeStatus intakeStatus, LocalDateTime reportRetentionUntil) {
        boolean sameObject(Source other) {
            return other != null && evidenceId.equals(other.evidenceId) && objectKey.equals(other.objectKey)
                    && Objects.equals(objectVersion, other.objectVersion) && mediaType.equals(other.mediaType)
                    && byteSize == other.byteSize && sha256.equals(other.sha256);
        }
    }

    /** 권한 확인을 통과한 요청의 범위입니다. source가 null이면 증거가 이 제보 소속으로 확인되지 않은 것입니다. */
    public record Checked(UUID tenantId, UUID eventId, UUID fanReportId, UUID actorMembershipId, Source source) { }

    /** 1~4단계. 저장된 제보 범위로 권한을 확인하고 증거 소속을 확인합니다. 범위 밖 제보는 404로 숨깁니다. */
    @Transactional(readOnly = true)
    public Checked authorize(UUID fanReportId, UUID evidenceId) {
        FanReport report = reports.findById(fanReportId).orElseThrow(EvidenceContentAccessWriteService::notFound);
        UUID member = member(report);
        Source source = assets.findById(evidenceId)
                .filter(asset -> fanReportId.equals(asset.getFanReportId()))
                .map(asset -> source(report, asset)).orElse(null);
        return new Checked(report.getTenantId(), report.getEventId(), fanReportId, member, source);
    }

    /**
     * 9~10단계. 원본 검증이 끝난 뒤 권한과 저장 상태를 다시 확인하고 같은 트랜잭션에서 성공 감사를 저장합니다.
     * 이 메서드가 정상 종료해야만 이미지 바이트를 응답합니다.
     */
    @Transactional
    public void succeed(UUID requestId, Checked checked, long maxBytes, LocalDateTime now) {
        FanReport report = reports.findById(checked.fanReportId())
                .orElseThrow(EvidenceContentAccessWriteService::notFound);
        if (!member(report).equals(checked.actorMembershipId())) throw notFound();
        Source current = assets.findById(checked.source().evidenceId())
                .filter(asset -> report.getFanReportId().equals(asset.getFanReportId()))
                .map(asset -> source(report, asset)).orElseThrow(EvidenceContentAccessWriteService::notFound);
        String rejected = precheck(current, now, maxBytes);
        if (rejected != null) throw new CaseApiException(HttpStatus.UNPROCESSABLE_ENTITY, rejected);
        if (!current.sameObject(checked.source())) throw new CaseApiException(HttpStatus.CONFLICT, "SOURCE_CHANGED");
        audits.saveAndFlush(new EvidenceAccessAuditEvent(requestId, checked.tenantId(), checked.eventId(),
                checked.fanReportId(), current.evidenceId(), checked.actorMembershipId(),
                EvidenceAccessAuditEvent.Outcome.SUCCEEDED, EvidenceAccessAuditEvent.VERIFIED, now));
    }

    /** 권한 확인 뒤 발생한 실패를 원래 요청과 분리된 트랜잭션으로 기록합니다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(UUID requestId, Checked checked, String code, LocalDateTime now) {
        audits.saveAndFlush(new EvidenceAccessAuditEvent(requestId, checked.tenantId(), checked.eventId(),
                checked.fanReportId(), checked.source() == null ? null : checked.source().evidenceId(),
                checked.actorMembershipId(), EvidenceAccessAuditEvent.Outcome.FAILED, code, now));
    }

    /**
     * 역할 부족(403)이나 다중 고객사 차단(409)으로 거부된 요청을 기록합니다.
     * 두 예외는 AdminAccessService가 제보 고객사의 활성 멤버십과 공연 권한을 확인한 뒤에만 던지므로, 같은 조건을 다시 확인해
     * 그 고객사의 멤버십으로만 기록합니다. 조건을 다시 확인하지 못하면 다른 범위의 정보를 남기지 않도록 기록하지 않습니다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean denied(UUID requestId, UUID fanReportId, UUID evidenceId, String code, LocalDateTime now) {
        FanReport report = reports.findById(fanReportId).orElse(null);
        if (report == null) return false;
        AdminMembershipEntity member = membershipRecords
                .findBySubjectAndTenantId(access.authenticatedSubject(), report.getTenantId())
                .filter(found -> AccessRecordStatus.ACTIVE.name().equals(found.getStatus())).orElse(null);
        if (member == null || grants.findAllByMembershipIdAndTenantId(member.getId(), report.getTenantId()).stream()
                .noneMatch(grant -> report.getEventId().equals(grant.getEventId()))) return false;
        UUID evidence = assets.findById(evidenceId).filter(asset -> fanReportId.equals(asset.getFanReportId()))
                .map(EvidenceAsset::getEvidenceId).orElse(null);
        audits.saveAndFlush(new EvidenceAccessAuditEvent(requestId, report.getTenantId(), report.getEventId(),
                fanReportId, evidence, member.getId(), EvidenceAccessAuditEvent.Outcome.FAILED, code, now));
        return true;
    }

    /** 5단계. 저장 대기와 실패, 보존 기한 만료, 허용하지 않은 형식, 설정 크기 초과를 원본을 읽기 전에 거절합니다. */
    static String precheck(Source source, LocalDateTime now, long maxBytes) {
        if (source.intakeStatus() == FanReport.IntakeStatus.PENDING_STORAGE
                || source.intakeStatus() == FanReport.IntakeStatus.STORAGE_FAILED
                || source.storageStatus() != EvidenceAsset.StorageStatus.STORED
                || source.objectVersion() == null || source.objectVersion().isBlank())
            return "SOURCE_NOT_AVAILABLE";
        if (!source.reportRetentionUntil().isAfter(now) || !source.assetRetentionUntil().isAfter(now))
            return "RETENTION_EXPIRED";
        if (!MEDIA_TYPES.contains(source.mediaType())) return "UNSUPPORTED_IMAGE_TYPE";
        if (source.byteSize() < 1 || source.byteSize() > maxBytes) return "EVIDENCE_CONTENT_TOO_LARGE";
        return null;
    }

    private UUID member(FanReport report) {
        AdminActor actor = access.requireResourceAccess(
                new AdminResourceScope(report.getTenantId(), report.getEventId()), null, ROLES);
        return memberships.actor(actor).getId();
    }

    private static Source source(FanReport report, EvidenceAsset asset) {
        return new Source(asset.getEvidenceId(), asset.getObjectKey(), asset.getObjectVersion(), asset.getMediaType(),
                asset.getByteSize(), asset.getSha256(), asset.getStorageStatus(), asset.getRetentionUntil(),
                report.getIntakeStatus(), report.getRetentionUntil());
    }

    private static CaseApiException notFound() {
        return new CaseApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
    }
}
