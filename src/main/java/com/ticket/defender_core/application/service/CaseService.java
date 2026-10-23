package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.in.web.dto.CaseDto;
import com.ticket.defender_core.adapter.out.persistence.CaseActivityRepository;
import com.ticket.defender_core.adapter.out.persistence.CaseIdempotencyRepository;
import com.ticket.defender_core.adapter.out.persistence.CaseRepository;
import com.ticket.defender_core.adapter.out.persistence.CaseSourceReferenceRepository;
import com.ticket.defender_core.domain.Case;
import com.ticket.defender_core.domain.CaseIdempotencyRecord;
import com.ticket.defender_core.security.AdminAccessService;
import com.ticket.defender_core.security.AdminActor;
import com.ticket.defender_core.security.AdminResourceScope;
import com.ticket.defender_core.security.AdminRole;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.HexFormat;

@Service
@RequiredArgsConstructor
public class CaseService {
    public static final UUID CREATE_TARGET = new UUID(0L, 0L);
    private static final Pattern KEY = Pattern.compile(
            "(?i)[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
    private static final Set<AdminRole> CREATE_ROLES = Set.of(AdminRole.ANALYST, AdminRole.REVIEWER);
    private static final Set<AdminRole> VIEW_ROLES = Set.of(AdminRole.TENANT_ADMIN, AdminRole.ANALYST, AdminRole.REVIEWER);
    private static final Set<AdminRole> REVIEWER = Set.of(AdminRole.REVIEWER);

    private final AdminAccessService access;
    private final CaseMembershipAccess memberships;
    private final CaseWriteService writer;
    private final CaseRepository cases;
    private final CaseSourceReferenceRepository sources;
    private final CaseActivityRepository activities;
    private final CaseIdempotencyRepository idempotency;
    private final ObjectMapper json;

    public CaseDto.View create(CaseDto.Create input, String key) {
        if (input == null || input.eventId() == null || input.type() == null) throw invalid();
        if ((input.graphClusters() != null && input.graphClusters().stream().anyMatch(Objects::isNull))
                || (input.fanReportIds() != null && input.fanReportIds().stream().anyMatch(Objects::isNull))
                || (input.evidenceIds() != null && input.evidenceIds().stream().anyMatch(Objects::isNull)))
            throw invalid();
        AdminActor actor = access.requireEventAccess(input.eventId(), CREATE_ROLES);
        UUID memberId = memberships.actor(actor).getId();
        Prepared prepared = prepare(key, canonical(input));
        CaseDto.View replay = replay(actor.tenantId(), actor.eventId(), memberId,
                "CREATE", CREATE_TARGET, prepared);
        if (replay != null) return replay;
        try {
            return writer.create(input, memberId, prepared.keyDigest(), prepared.requestDigest());
        } catch (DataIntegrityViolationException collision) {
            CaseDto.View committed = replay(actor.tenantId(), actor.eventId(), memberId,
                    "CREATE", CREATE_TARGET, prepared);
            if (committed != null) return committed;
            throw collision;
        }
    }

    public CaseDto.View transition(UUID caseId, CaseDto.Transition input, String key) {
        if (input == null) throw invalid();
        Authorized current = authorizedForChange(caseId);
        Prepared prepared = prepare(key, input);
        CaseDto.View replay = replay(current.scope().tenantId(), current.scope().eventId(), current.memberId(),
                "TRANSITION", caseId, prepared);
        if (replay != null) return replay;
        try {
            return writer.transition(caseId, input, current.memberId(),
                    prepared.keyDigest(), prepared.requestDigest());
        } catch (OptimisticLockingFailureException collision) {
            CaseDto.View committed = replay(current.scope().tenantId(), current.scope().eventId(),
                    current.memberId(), "TRANSITION", caseId, prepared);
            if (committed != null) return committed;
            throw conflict("CASE_VERSION_CONFLICT");
        } catch (DataIntegrityViolationException collision) {
            CaseDto.View committed = replay(current.scope().tenantId(), current.scope().eventId(), current.memberId(),
                    "TRANSITION", caseId, prepared);
            if (committed != null) return committed;
            throw collision;
        }
    }

    public CaseDto.View assign(UUID caseId, CaseDto.Assign input, String key) {
        if (input == null) throw invalid();
        Authorized current = authorizedForChange(caseId);
        Prepared prepared = prepare(key, input);
        CaseDto.View replay = replay(current.scope().tenantId(), current.scope().eventId(), current.memberId(),
                "ASSIGN", caseId, prepared);
        if (replay != null) return replay;
        try {
            return writer.assign(caseId, input, current.memberId(),
                    prepared.keyDigest(), prepared.requestDigest());
        } catch (OptimisticLockingFailureException collision) {
            CaseDto.View committed = replay(current.scope().tenantId(), current.scope().eventId(),
                    current.memberId(), "ASSIGN", caseId, prepared);
            if (committed != null) return committed;
            throw conflict("CASE_VERSION_CONFLICT");
        } catch (DataIntegrityViolationException collision) {
            CaseDto.View committed = replay(current.scope().tenantId(), current.scope().eventId(), current.memberId(),
                    "ASSIGN", caseId, prepared);
            if (committed != null) return committed;
            throw collision;
        }
    }

    @Transactional(readOnly = true)
    public CaseDto.Page list(UUID eventId, Case.Status status, int page, int pageSize) {
        page(page, pageSize);
        AdminActor actor = access.requireEventAccess(eventId, VIEW_ROLES);
        UUID memberId = memberships.actor(actor).getId();
        Set<AdminRole> roles = memberships.roles(memberId);
        UUID onlyOwn = roles.contains(AdminRole.REVIEWER) || roles.contains(AdminRole.TENANT_ADMIN)
                ? null : memberId;
        var found = cases.findVisible(actor.tenantId(), eventId, status, onlyOwn,
                PageRequest.of(page, pageSize, Sort.by("createdAt").descending().and(Sort.by("id").ascending())));
        return new CaseDto.Page(found.stream().map(CaseDto.View::from).toList(), page, pageSize, found.hasNext());
    }

    @Transactional(readOnly = true)
    public CaseDto.View detail(UUID caseId) {
        return CaseDto.View.from(authorizedForView(caseId));
    }

    @Transactional(readOnly = true)
    public List<CaseDto.Source> sources(UUID caseId) {
        authorizedForView(caseId);
        return sources.findByCaseIdOrderByLinkedAtAscIdAsc(caseId).stream()
                .map(CaseDto.Source::from).toList();
    }

    @Transactional(readOnly = true)
    public CaseDto.Activities activities(UUID caseId, int page, int pageSize) {
        page(page, pageSize);
        authorizedForView(caseId);
        var found = activities.findByCaseId(caseId,
                PageRequest.of(page, pageSize, Sort.by("occurredAt").ascending().and(Sort.by("id").ascending())));
        return new CaseDto.Activities(found.stream().map(CaseDto.Activity::from).toList(),
                page, pageSize, found.hasNext());
    }

    private Authorized authorizedForChange(UUID caseId) {
        Case target = cases.findById(caseId).orElseThrow(CaseService::notFound);
        AdminResourceScope scope = new AdminResourceScope(target.getTenantId(), target.getEventId());
        AdminActor actor = access.requireResourceAccess(scope, null, REVIEWER);
        return new Authorized(scope, memberships.actor(actor).getId());
    }

    private Case authorizedForView(UUID caseId) {
        Case target = cases.findById(caseId).orElseThrow(CaseService::notFound);
        AdminActor actor = access.requireResourceAccess(
                new AdminResourceScope(target.getTenantId(), target.getEventId()), null, VIEW_ROLES);
        UUID memberId = memberships.actor(actor).getId();
        Set<AdminRole> roles = memberships.roles(memberId);
        if (!roles.contains(AdminRole.REVIEWER) && !roles.contains(AdminRole.TENANT_ADMIN)
                && !memberId.equals(target.getCreatedByMembershipId())
                && !memberId.equals(target.getAssigneeMembershipId())) throw notFound();
        return target;
    }

    private Prepared prepare(String suppliedKey, Object request) {
        if (suppliedKey == null || !KEY.matcher(suppliedKey).matches())
            throw new CaseApiException(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY");
        return new Prepared(sha256(suppliedKey.toLowerCase()), sha256(json.writeValueAsString(request)));
    }

    private CaseDto.Create canonical(CaseDto.Create input) {
        List<CaseDto.GraphCluster> graph = input.graphClusters() == null ? null
                : input.graphClusters().stream()
                .sorted(Comparator.comparing(CaseDto.GraphCluster::analysisRequestId,
                        Comparator.nullsFirst(String::compareTo))
                        .thenComparing(CaseDto.GraphCluster::clusterId,
                                Comparator.nullsFirst(String::compareTo))).toList();
        List<UUID> reports = input.fanReportIds() == null ? null
                : input.fanReportIds().stream().sorted().toList();
        List<UUID> assets = input.evidenceIds() == null ? null
                : input.evidenceIds().stream().sorted().toList();
        return new CaseDto.Create(input.eventId(), input.type(), graph, reports, assets,
                input.reason(), input.relatedCaseId(), input.relationReason());
    }

    private CaseDto.View replay(UUID tenantId, UUID eventId, UUID memberId, String operation,
            UUID targetId, Prepared prepared) {
        CaseIdempotencyRecord stored = idempotency
                .findByTenantIdAndActorMembershipIdAndOperationAndTargetCaseIdAndKeyDigest(
                        tenantId, memberId, operation, targetId, prepared.keyDigest()).orElse(null);
        if (stored == null) return null;
        if (!stored.getRequestDigest().equals(prepared.requestDigest()))
            throw conflict("IDEMPOTENCY_KEY_REUSED");
        Case resultCase = cases.findById(stored.getResultCaseId()).orElseThrow(CaseService::notFound);
        if (!tenantId.equals(resultCase.getTenantId()) || !eventId.equals(resultCase.getEventId())
                || (!operation.equals("CREATE") && !targetId.equals(resultCase.getId()))
                || (operation.equals("CREATE") && !memberId.equals(resultCase.getCreatedByMembershipId())))
            throw notFound();
        return json.readValue(stored.getResponseJson(), CaseDto.View.class);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void page(int page, int size) {
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE)
            throw new CaseApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
    }

    private static CaseApiException notFound() {
        return new CaseApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
    }

    private static CaseApiException invalid() {
        return new CaseApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED");
    }

    private static CaseApiException conflict(String code) {
        return new CaseApiException(HttpStatus.CONFLICT, code);
    }

    private record Prepared(String keyDigest, String requestDigest) { }
    private record Authorized(AdminResourceScope scope, UUID memberId) { }
}
