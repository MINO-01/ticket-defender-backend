package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.in.web.dto.PurchaseLimitPolicyDto;
import com.ticket.defender_core.adapter.out.persistence.*;
import com.ticket.defender_core.domain.*;
import com.ticket.defender_core.security.AdminRole;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException; import org.springframework.http.HttpStatus; import org.springframework.stereotype.Service; import org.springframework.transaction.annotation.Transactional;
import java.time.*; import java.util.*;

@Service @RequiredArgsConstructor
public class PurchaseLimitPolicyService {
    private static final Set<AdminRole> MANAGE_ROLES = Set.of(AdminRole.TENANT_ADMIN);
    private final CandidatePipelineAccess access; private final VerifiedPerformanceRepository performances;
    private final PurchaseLimitPolicyRepository policies; private final PurchaseLimitPolicyAuditRepository audits;
    @Transactional public PurchaseLimitPolicyDto.View create(UUID eventId, PurchaseLimitPolicyDto.Create input) {
        CandidatePipelineAccess.Scope scope=access.event(eventId, MANAGE_ROLES); require(input);
        VerifiedPerformance performance=performances.lockByIdAndScope(input.performanceId(),scope.tenantId(),eventId).orElseThrow(PurchaseLimitPolicyService::notFound);
        if (performance.getStatus()!=VerifiedPerformance.Status.ACTIVE) throw conflict("PERFORMANCE_WITHDRAWN");
        if (policies.findByTenantIdAndEventIdAndActivePerformanceId(scope.tenantId(),eventId,input.performanceId()).isPresent()) throw conflict("PURCHASE_LIMIT_POLICY_CONFLICT");
        if (policies.existsByTenantIdAndEventIdAndPerformanceIdAndPolicyVersion(scope.tenantId(),eventId,input.performanceId(),input.policyVersion())) throw conflict("PURCHASE_LIMIT_POLICY_VERSION_REUSE");
        LocalDateTime now=now(); PurchaseLimitPolicy policy;
        try { policy=policies.saveAndFlush(new PurchaseLimitPolicy(UUID.randomUUID(),scope.tenantId(),eventId,input.performanceId(),input.maxTicketsPerAccount(),input.policyVersion(),input.effectiveFrom(),input.effectiveUntil(),input.policySource(),input.verificationBasis(),scope.membershipId(),now)); }
        catch (DataIntegrityViolationException e) { throw conflict("PURCHASE_LIMIT_POLICY_CONFLICT"); }
        audits.saveAndFlush(new PurchaseLimitPolicyAudit(policy, PurchaseLimitPolicyAudit.Action.CREATED,scope.membershipId(),UUID.randomUUID(),now)); return view(policy);
    }
    @Transactional public PurchaseLimitPolicyDto.View revise(UUID eventId, UUID id, PurchaseLimitPolicyDto.Revise input) {
        CandidatePipelineAccess.Scope scope=access.event(eventId,MANAGE_ROLES); require(input); PurchaseLimitPolicy p=policies.lockByIdAndScope(id,scope.tenantId(),eventId).orElseThrow(PurchaseLimitPolicyService::notFound);
        if (p.getRevision()!=input.expectedRevision()) throw conflict("PURCHASE_LIMIT_POLICY_REVISION_CONFLICT"); LocalDateTime now=now();
        try { p.revise(input.maxTicketsPerAccount(),input.policyVersion(),input.effectiveFrom(),input.effectiveUntil(),input.policySource(),input.verificationBasis(),scope.membershipId(),now); } catch (IllegalStateException e) { throw conflict("PURCHASE_LIMIT_POLICY_WITHDRAWN"); } catch (IllegalArgumentException e) { throw conflict("PURCHASE_LIMIT_POLICY_VERSION_REUSE"); }
        policies.saveAndFlush(p); audits.saveAndFlush(new PurchaseLimitPolicyAudit(p,PurchaseLimitPolicyAudit.Action.REVISED,scope.membershipId(),UUID.randomUUID(),now)); return view(p);
    }
    @Transactional public PurchaseLimitPolicyDto.View withdraw(UUID eventId, UUID id, PurchaseLimitPolicyDto.Withdraw input) {
        CandidatePipelineAccess.Scope scope=access.event(eventId,MANAGE_ROLES); if(input==null||input.expectedRevision()<1) throw invalid(); PurchaseLimitPolicy p=policies.lockByIdAndScope(id,scope.tenantId(),eventId).orElseThrow(PurchaseLimitPolicyService::notFound);
        if(p.getRevision()!=input.expectedRevision()) throw conflict("PURCHASE_LIMIT_POLICY_REVISION_CONFLICT"); LocalDateTime now=now(); try { p.withdraw(scope.membershipId(),now); } catch(IllegalStateException e) { throw conflict("PURCHASE_LIMIT_POLICY_WITHDRAWN"); }
        policies.saveAndFlush(p); audits.saveAndFlush(new PurchaseLimitPolicyAudit(p,PurchaseLimitPolicyAudit.Action.WITHDRAWN,scope.membershipId(),UUID.randomUUID(),now)); return view(p);
    }
    @Transactional(readOnly=true) public List<PurchaseLimitPolicyDto.View> list(UUID eventId) { CandidatePipelineAccess.Scope scope=access.event(eventId,MANAGE_ROLES); return policies.findByTenantIdAndEventIdOrderByPerformanceIdAsc(scope.tenantId(),eventId).stream().map(PurchaseLimitPolicyService::view).toList(); }
    private static void require(PurchaseLimitPolicyDto.Create i) { if(i==null||i.performanceId()==null||i.maxTicketsPerAccount()<1||bad(i.policyVersion(),128)||i.effectiveFrom()==null||bad(i.policySource(),512)||bad(i.verificationBasis(),512)||(i.effectiveUntil()!=null&&!i.effectiveUntil().isAfter(i.effectiveFrom()))) throw invalid(); }
    private static void require(PurchaseLimitPolicyDto.Revise i) { if(i==null||i.expectedRevision()<1||i.maxTicketsPerAccount()<1||bad(i.policyVersion(),128)||i.effectiveFrom()==null||bad(i.policySource(),512)||bad(i.verificationBasis(),512)||(i.effectiveUntil()!=null&&!i.effectiveUntil().isAfter(i.effectiveFrom()))) throw invalid(); }
    private static boolean bad(String v,int n){return v==null||v.isBlank()||v.length()>n;} private static LocalDateTime now(){return LocalDateTime.now(ZoneOffset.UTC);} private static CaseApiException invalid(){return new CaseApiException(HttpStatus.BAD_REQUEST,"INVALID_REQUEST");} private static CaseApiException notFound(){return new CaseApiException(HttpStatus.NOT_FOUND,"RESOURCE_NOT_FOUND");} private static CaseApiException conflict(String c){return new CaseApiException(HttpStatus.CONFLICT,c);}
    private static PurchaseLimitPolicyDto.View view(PurchaseLimitPolicy p){return new PurchaseLimitPolicyDto.View(p.getId(),p.getEventId(),p.getPerformanceId(),p.getMaxTicketsPerAccount(),p.getPolicyVersion(),p.getEffectiveFrom(),p.getEffectiveUntil(),p.getStatus(),p.getPolicySource(),p.getVerificationBasis(),p.getRevision(),p.getChangedAt());}
}
