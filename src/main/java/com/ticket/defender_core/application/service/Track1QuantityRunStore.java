package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.in.web.dto.Track1QuantityDto;
import com.ticket.defender_core.adapter.out.api.dto.FastApiClusterResponse;
import com.ticket.defender_core.adapter.out.api.dto.MacroAnalysisRequest;
import com.ticket.defender_core.adapter.out.api.dto.MacroAnalysisResponse;
import com.ticket.defender_core.adapter.out.api.dto.Track1PurchaseLimitAnalysisRequest;
import com.ticket.defender_core.adapter.out.api.dto.Track1PurchaseLimitAnalysisResponse;
import com.ticket.defender_core.adapter.out.persistence.PurchaseLimitPolicyRepository;
import com.ticket.defender_core.adapter.out.persistence.TenantEventRepository;
import com.ticket.defender_core.adapter.out.persistence.Track1PurchaseLedgerBatchRepository;
import com.ticket.defender_core.adapter.out.persistence.Track1PurchaseLedgerRowRepository;
import com.ticket.defender_core.adapter.out.persistence.Track1PurchaseLimitFindingRepository;
import com.ticket.defender_core.adapter.out.persistence.Track1PurchaseLimitFindingReservationRepository;
import com.ticket.defender_core.adapter.out.persistence.Track1PurchaseLimitRunClusterRepository;
import com.ticket.defender_core.adapter.out.persistence.Track1PurchaseLimitRunRepository;
import com.ticket.defender_core.adapter.out.persistence.VerifiedPerformanceRepository;
import com.ticket.defender_core.domain.PurchaseLimitPolicy;
import com.ticket.defender_core.domain.TenantEvent;
import com.ticket.defender_core.domain.Track1PurchaseLedgerBatch;
import com.ticket.defender_core.domain.Track1PurchaseLedgerRow;
import com.ticket.defender_core.domain.Track1PurchaseLimitFinding;
import com.ticket.defender_core.domain.Track1PurchaseLimitFindingReservation;
import com.ticket.defender_core.domain.Track1PurchaseLimitRun;
import com.ticket.defender_core.domain.Track1PurchaseLimitRunCluster;
import com.ticket.defender_core.domain.VerifiedPerformance;
import com.ticket.defender_core.security.AdminRole;
import com.ticket.defender_core.security.AnalysisIngestScope;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** 분석 실행의 짧은 DB 트랜잭션만 담당합니다. FastAPI 호출은 이 클래스의 트랜잭션 밖에서 일어납니다. */
@Component
@RequiredArgsConstructor
class Track1QuantityRunStore {
    private static final Set<AdminRole> RUN_ROLES = Set.of(AdminRole.TENANT_ADMIN, AdminRole.ANALYST);
    /** HTTP 호출 제한시간보다 충분히 긴 값입니다. 이보다 오래 열린 실행은 비정상 종료로 봅니다. */
    private static final Duration STALE_AFTER = Duration.ofMinutes(2);
    private static final String DISPLAY_PURPOSE = "TRACK1_QTY_DISPLAY";

    private final CandidatePipelineAccess access;
    private final SyntheticReservationImportGate gate;
    private final Track1PurchaseLimitAnalysisService analysis;
    private final VerifiedPerformanceRepository performances;
    private final TenantEventRepository events;
    private final PurchaseLimitPolicyRepository policies;
    private final Track1PurchaseLedgerBatchRepository batches;
    private final Track1PurchaseLedgerRowRepository rows;
    private final Track1PurchaseLimitRunRepository runs;
    private final Track1PurchaseLimitFindingRepository findings;
    private final Track1PurchaseLimitFindingReservationRepository findingReservations;
    private final Track1PurchaseLimitRunClusterRepository clusters;
    private final MacroAuditPersistenceService macroAudits;

    /** call이 null이면 재생이거나 입력 단계에서 끝난 실행이므로 외부 호출을 하지 않습니다. */
    record Opened(UUID runId, UUID tenantId, UUID eventId, String externalEventRef, Track1PurchaseLimitAnalysisRequest call,
                  Track1QuantityDto.AnalysisRun finished) { }

    @Transactional
    public Opened open(UUID eventId, String idempotencyKey, Track1QuantityDto.RunRequest input) {
        CandidatePipelineAccess.Scope scope = access.event(eventId, RUN_ROLES);
        if (input == null || input.performanceId() == null || input.ledgerBatchId() == null) throw CandidatePipelineSupport.invalid();
        analysis.requireEnabled();
        String keyDigest = CandidatePipelineSupport.idempotencyDigest(idempotencyKey);
        LocalDateTime now = now();
        UUID isolationId = gate.requireSyntheticScope(scope.tenantId(), now);
        String requestDigest = CandidatePipelineSupport.sha256("TRACK1_QTY_RUN", input.performanceId().toString(), input.ledgerBatchId().toString());

        var replay = runs.findByCreatedByMembershipIdAndEventIdAndIdempotencyKeyDigest(scope.membershipId(), scope.eventId(), keyDigest);
        if (replay.isPresent()) {
            if (!replay.get().getRequestDigest().equals(requestDigest)) throw conflict("IDEMPOTENCY_KEY_REUSED");
            return finished(replay.get());
        }

        VerifiedPerformance performance = performances.lockByIdAndScope(input.performanceId(), scope.tenantId(), scope.eventId())
                .orElseThrow(Track1QuantityRunStore::notFound);
        if (performance.getStatus() != VerifiedPerformance.Status.ACTIVE) throw conflict("PERFORMANCE_WITHDRAWN");
        Track1PurchaseLedgerBatch batch = batches.findByIdAndTenantIdAndEventId(input.ledgerBatchId(), scope.tenantId(), scope.eventId())
                .filter(found -> found.getPerformanceId().equals(performance.getId()))
                .orElseThrow(Track1QuantityRunStore::notFound);
        if (batch.getStatus() != Track1PurchaseLedgerBatch.Status.ACCEPTED) throw conflict("LEDGER_BATCH_REJECTED");
        if (!isolationId.equals(batch.getSyntheticIsolationId())) throw conflict("LEDGER_ISOLATION_MISMATCH");
        closeStaleOrRefuse(batch.getId(), now);

        TenantEvent event = events.findById(scope.eventId()).filter(found -> found.getTenantId().equals(scope.tenantId()))
                .orElseThrow(Track1QuantityRunStore::notFound);
        List<Track1PurchaseLedgerRow> ledger = rows.findByBatchIdOrderBySourceRowIndexAsc(batch.getId());
        Decision decision = decide(batch, ledger, policies.findByTenantIdAndEventIdOrderByPerformanceIdAsc(scope.tenantId(), scope.eventId()), performance.getId());
        Track1PurchaseLimitRun run = new Track1PurchaseLimitRun(scope.tenantId(), scope.eventId(), performance.getId(), batch.getId(),
                scope.membershipId(), keyDigest, requestDigest, batch.getSourceVersion(),
                decision.policy() == null ? null : decision.policy().getId(),
                decision.policy() == null ? null : decision.policy().getPolicyVersion(),
                decision.reason(), decision.excludedRows(), now);
        try {
            runs.saveAndFlush(run);
        } catch (DataIntegrityViolationException collision) {
            throw conflict("TRACK1_QUANTITY_RUN_CONCURRENT");
        }
        String integrityFailure = integrityFailure(batch, ledger);
        if (integrityFailure != null || decision.payload().isEmpty()) {
            run.fail(Track1PurchaseLimitRun.Execution.INPUT_REJECTED,
                    integrityFailure != null ? integrityFailure : "NO_EFFECTIVE_TICKETS_AFTER_NETTING", now);
            runs.saveAndFlush(run);
            return finished(run);
        }
        return new Opened(run.getId(), scope.tenantId(), scope.eventId(), event.getExternalEventRef(),
                request(run, batch, event, performance.getId(), decision), null);
    }

    @Transactional
    public Track1QuantityDto.AnalysisRun fail(UUID runId, Track1PurchaseLimitRun.Execution status, String code) {
        Track1PurchaseLimitRun run = runs.findById(runId).orElseThrow(Track1QuantityRunStore::notFound);
        if (run.isOpen()) {
            run.fail(status, code, now());
            runs.saveAndFlush(run);
        }
        return view(run, List.of(), Map.of(), List.of());
    }

    @Transactional
    public Track1QuantityDto.AnalysisRun complete(Opened opened, Track1PurchaseLimitAnalysisResponse response) {
        Track1PurchaseLimitRun run = runs.findById(opened.runId()).orElseThrow(Track1QuantityRunStore::notFound);
        if (!run.isOpen()) return viewOne(run); // 지연 응답은 이미 닫힌 실행을 덮어쓰지 않습니다.
        Track1PurchaseLimitAnalysisResponse.PurchaseLimitEvaluation evaluation = response.purchaseLimitEvaluations().getFirst();
        if (evaluation.status() == Track1PurchaseLimitAnalysisResponse.EvaluationStatus.EVALUATED) {
            for (Track1PurchaseLimitAnalysisResponse.AccountLimitFinding item : evaluation.accountLimitFindings()) {
                Track1PurchaseLimitFinding finding = findings.saveAndFlush(new Track1PurchaseLimitFinding(run.getId(), item.accountId(),
                        item.effectiveTicketQuantity(), item.maxTicketsPerAccount(), item.policyVersion()));
                findingReservations.saveAll(item.reservationQuantities().stream().map(reservation ->
                        new Track1PurchaseLimitFindingReservation(finding.getId(), reservation.reservationNo(), reservation.effectiveTicketQuantity())).toList());
            }
        }
        clusters.saveAll(response.clusters().stream().map(cluster -> new Track1PurchaseLimitRunCluster(run.getId(), cluster.clusterId(),
                cluster.riskScore(), cluster.members().size())).toList());
        persistClusterEvidence(opened, response);
        run.succeed(evaluation.status().name(), response.algorithm(), response.algorithmVersion(), response.clusters().size(),
                response.analyzedAt().withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime(), now());
        runs.saveAndFlush(run);
        return viewOne(run);
    }

    @Transactional(readOnly = true)
    public Track1QuantityDto.Page<Track1QuantityDto.AnalysisRun> list(UUID eventId, int page, int pageSize) {
        Track1QuantityLedgerService.validatePage(page, pageSize);
        CandidatePipelineAccess.Scope scope = access.event(eventId, Track1QuantityLedgerService.VIEW_ROLES);
        var found = runs.findByTenantIdAndEventId(scope.tenantId(), scope.eventId(),
                PageRequest.of(page, pageSize, Sort.by("createdAt").descending().and(Sort.by("id").descending())));
        return new Track1QuantityDto.Page<>(views(found.getContent()), page, pageSize, found.hasNext());
    }

    @Transactional(readOnly = true)
    public Track1QuantityDto.AnalysisRun get(UUID eventId, UUID runId) {
        CandidatePipelineAccess.Scope scope = access.event(eventId, Track1QuantityLedgerService.VIEW_ROLES);
        return viewOne(runs.findByIdAndTenantIdAndEventId(runId, scope.tenantId(), scope.eventId()).orElseThrow(Track1QuantityRunStore::notFound));
    }

    /** 활성 실행이 남아 있으면 거절하고, 프로세스 중단으로 방치된 실행만 INTERRUPTED로 닫아 재시도를 허용합니다. */
    private void closeStaleOrRefuse(UUID batchId, LocalDateTime now) {
        runs.findByActiveLedgerBatchId(batchId).ifPresent(active -> {
            if (active.getCreatedAt().plus(STALE_AFTER).isAfter(now)) throw conflict("TRACK1_QUANTITY_RUN_IN_PROGRESS");
            active.fail(Track1PurchaseLimitRun.Execution.INTERRUPTED, "ANALYSIS_INTERRUPTED", now);
            runs.saveAndFlush(active);
        });
    }

    private static String integrityFailure(Track1PurchaseLedgerBatch batch, List<Track1PurchaseLedgerRow> ledger) {
        if (ledger.size() != batch.getAcceptedRowCount()) return "LEDGER_INTEGRITY_MISMATCH";
        List<Track1QuantityLedgerService.DigestRow> digestRows = ledger.stream().map(row -> (Track1QuantityLedgerService.DigestRow) new StoredRow(row)).toList();
        return Track1QuantityLedgerService.rowsDigest(digestRows).equals(batch.getRowsDigest()) ? null : "LEDGER_INTEGRITY_MISMATCH";
    }

    private record StoredRow(Track1PurchaseLedgerRow row) implements Track1QuantityLedgerService.DigestRow {
        @Override public int index() { return row.getSourceRowIndex(); }
        @Override public List<String> canonical() {
            return Track1QuantityLedgerService.canonical(row.getSourceRowIndex(), row.getAccountRefHmac(), row.getReservationRefHmac(), row.getTicketRefHmac(),
                    row.getPurchasedAt(), row.getTicketQuantity(), row.getQuantityUnit(), row.getTicketStatus(),
                    new Track1PurchaseLedgerRow.Signals(row.getPaymentHmac(), row.getAddressHmac(), row.getDeviceHmac(), row.getIpHmac()));
        }
    }

    /** 정책과 수량 완전성을 판단합니다. 확인되지 않은 규칙은 Data에 평가 불가 상태로 그대로 전달합니다. */
    private static Decision decide(Track1PurchaseLedgerBatch batch, List<Track1PurchaseLedgerRow> ledger, List<PurchaseLimitPolicy> all, UUID performanceId) {
        PurchaseLimitPolicy policy = selectPolicy(ledger, all, performanceId);
        if (policy == null) return Decision.unconfirmed(ledger, "NO_CONFIRMED_ACCOUNT_POLICY");
        // Data v2는 회차 단위로만 합산하므로 공연 전체 합산 정책을 한 회차에 적용하면 수량이 축소됩니다.
        if (policy.getApplicationScope() == PurchaseLimitPolicy.ApplicationScope.EVENT) return Decision.unconfirmed(ledger, "EVENT_SCOPE_AGGREGATION_NOT_SUPPORTED");
        if (policy.getCancellationRefundRule() != PurchaseLimitPolicy.CancellationRefundRule.NETTED) return Decision.incomplete(policy, ledger, "GROSS_RULE_NOT_SUPPORTED");
        if (!batch.isCompleteSnapshot()) return Decision.incomplete(policy, ledger, "INCOMPLETE_SNAPSHOT");
        boolean ready = ledger.stream().allMatch(row -> PurchaseLimitQuantityInputContract.assess(policy, individual(row)).status()
                == PurchaseLimitQuantityInputContract.AssessmentStatus.READY_FOR_INDIVIDUAL_TICKET_V2);
        if (!ready) return Decision.incomplete(policy, ledger, "UNKNOWN_TICKET_STATUS");
        // NETTED는 취소와 환불이 이미 제외된 수량을 뜻하므로, 상태가 명시된 행만 정책 근거로 제외합니다.
        List<Track1PurchaseLedgerRow> active = ledger.stream().filter(row -> row.getTicketStatus() == Track1PurchaseLedgerRow.TicketStatus.ACTIVE).toList();
        return new Decision(policy, true, active, ledger.size() - active.size(), null);
    }

    private static PurchaseLimitQuantityInputContract.IndividualTicket individual(Track1PurchaseLedgerRow row) {
        return new PurchaseLimitQuantityInputContract.IndividualTicket(row.getAccountRefHmac(), row.getReservationRefHmac(), row.getTicketRefHmac(),
                row.getPurchasedAt().toInstant(ZoneOffset.UTC), row.getTicketQuantity(),
                PurchaseLimitQuantityInputContract.TicketStatus.valueOf(row.getTicketStatus().name()),
                row.getSourceVersion(), row.getSourceAsOf().toInstant(ZoneOffset.UTC));
    }

    /**
     * 선택기는 시점 하나로 유효 정책을 고릅니다. 구매 시각 범위와 각 정책 시작 시각을 차례로 넣어,
     * 구매가 기간 밖인 경우에도 정책 미확인으로 뭉개지지 않고 POLICY_TIME_NOT_APPLICABLE이 나오게 합니다.
     */
    private static PurchaseLimitPolicy selectPolicy(List<Track1PurchaseLedgerRow> ledger, List<PurchaseLimitPolicy> all, UUID performanceId) {
        List<LocalDateTime> moments = new ArrayList<>();
        ledger.stream().map(Track1PurchaseLedgerRow::getPurchasedAt).min(Comparator.naturalOrder()).ifPresent(moments::add);
        ledger.stream().map(Track1PurchaseLedgerRow::getPurchasedAt).max(Comparator.naturalOrder()).ifPresent(moments::add);
        all.stream().filter(policy -> policy.getStatus() == PurchaseLimitPolicy.Status.ACTIVE)
                .map(PurchaseLimitPolicy::getEffectiveFrom).sorted().forEach(moments::add);
        for (LocalDateTime moment : moments) {
            PurchaseLimitPolicySelector.Result result = PurchaseLimitPolicySelector.select(all, new PurchaseLimitPolicySelector.Context(
                    performanceId, null, PurchaseLimitPolicy.LimitSubject.ACCOUNT, false, moment));
            if (result.status() == PurchaseLimitPolicySelector.Status.SELECTED) return result.policy();
        }
        return null;
    }

    private static Track1PurchaseLimitAnalysisRequest request(Track1PurchaseLimitRun run, Track1PurchaseLedgerBatch batch, TenantEvent event, UUID performanceId, Decision decision) {
        PurchaseLimitPolicy policy = decision.policy();
        boolean confirmed = policy != null;
        boolean complete = decision.complete();
        OffsetDateTime asOf = batch.getSourceAsOf().atOffset(ZoneOffset.UTC);
        var scope = new Track1PurchaseLimitAnalysisRequest.PerformanceScope(batch.getEventId(), event.getExternalEventRef(), performanceId,
                confirmed ? Track1PurchaseLimitAnalysisRequest.PolicyVerificationStatus.CONFIRMED : Track1PurchaseLimitAnalysisRequest.PolicyVerificationStatus.POLICY_NOT_CONFIRMED,
                confirmed ? new Track1PurchaseLimitAnalysisRequest.ConfirmedPolicy(policy.getMaxTicketsPerAccount(), policy.getPolicyVersion(),
                        policy.getEffectiveFrom().atOffset(ZoneOffset.UTC), policy.getEffectiveUntil() == null ? null : policy.getEffectiveUntil().atOffset(ZoneOffset.UTC)) : null,
                complete ? Track1PurchaseLimitAnalysisRequest.QuantityDataStatus.COMPLETE
                        : confirmed ? Track1PurchaseLimitAnalysisRequest.QuantityDataStatus.INCOMPLETE : Track1PurchaseLimitAnalysisRequest.QuantityDataStatus.UNKNOWN,
                batch.getSourceVersion(), asOf,
                complete ? Track1PurchaseLimitAnalysisRequest.CancellationHandling.NETTED : Track1PurchaseLimitAnalysisRequest.CancellationHandling.NOT_CONFIRMED,
                Track1PurchaseLimitAnalysisRequest.DuplicateHandling.DEDUPLICATED, decision.reason());
        List<Track1PurchaseLimitAnalysisRequest.Ticket> tickets = decision.payload().stream().map(row -> new Track1PurchaseLimitAnalysisRequest.Ticket(
                row.getAccountRefHmac(), row.getReservationRefHmac(), event.getExternalEventRef(), performanceId, row.getPurchasedAt().atOffset(ZoneOffset.UTC),
                row.getTicketQuantity(), row.getPaymentHmac(), row.getAddressHmac(), row.getDeviceHmac(), row.getIpHmac(),
                Track1PurchaseLimitAnalysisRequest.QuantityInputType.INDIVIDUAL_TICKET, row.getTicketRefHmac(),
                Track1PurchaseLimitAnalysisRequest.TicketStatus.valueOf(row.getTicketStatus().name()), row.getSourceVersion(), row.getSourceAsOf().atOffset(ZoneOffset.UTC))).toList();
        return new Track1PurchaseLimitAnalysisRequest(run.getAnalysisRequestId().toString(), List.of(scope), tickets);
    }

    /** 기존 군집 조회와 Case 출처 연결이 그대로 동작하도록 예매 단위로 합쳐 Macro 증거에 저장합니다. 기존 Case는 건드리지 않습니다. */
    private void persistClusterEvidence(Opened opened, Track1PurchaseLimitAnalysisResponse response) {
        if (response.clusters().isEmpty()) return;
        Map<String, MacroAnalysisRequest.Ticket> tickets = new LinkedHashMap<>();
        for (Track1PurchaseLimitAnalysisRequest.Ticket ticket : opened.call().tickets()) {
            tickets.putIfAbsent(ticket.accountId() + ":" + ticket.reservationNo(), new MacroAnalysisRequest.Ticket(ticket.accountId(), ticket.reservationNo(),
                    ticket.eventId(), ticket.paymentHash(), ticket.addressHash(), ticket.deviceIdHash(), ticket.ipHash()));
        }
        macroAudits.persistDetectedAudits(
                new MacroAnalysisResponse(response.requestId(), MacroAnalysisResponse.Status.COMPLETED, response.algorithm(), response.algorithmVersion(), response.analyzedAt(), response.clusters()),
                new MacroAnalysisRequest(response.requestId(), List.copyOf(tickets.values())),
                new AnalysisIngestScope(opened.tenantId(), Map.of(opened.externalEventRef(), opened.eventId())));
    }

    private Opened finished(Track1PurchaseLimitRun run) {
        return new Opened(run.getId(), run.getTenantId(), run.getEventId(), null, null, viewOne(run));
    }

    private Track1QuantityDto.AnalysisRun viewOne(Track1PurchaseLimitRun run) { return views(List.of(run)).getFirst(); }

    private List<Track1QuantityDto.AnalysisRun> views(List<Track1PurchaseLimitRun> items) {
        if (items.isEmpty()) return List.of();
        Collection<UUID> ids = items.stream().map(Track1PurchaseLimitRun::getId).toList();
        Map<UUID, List<Track1PurchaseLimitFinding>> findingsByRun = findings.findByRunIdIn(ids).stream().collect(Collectors.groupingBy(Track1PurchaseLimitFinding::getRunId));
        List<UUID> findingIds = findingsByRun.values().stream().flatMap(List::stream).map(Track1PurchaseLimitFinding::getId).toList();
        Map<UUID, List<Track1PurchaseLimitFindingReservation>> reservations = findingIds.isEmpty() ? Map.of()
                : findingReservations.findByFindingIdIn(findingIds).stream().collect(Collectors.groupingBy(Track1PurchaseLimitFindingReservation::getFindingId));
        Map<UUID, List<Track1PurchaseLimitRunCluster>> clustersByRun = clusters.findByRunIdIn(ids).stream().collect(Collectors.groupingBy(Track1PurchaseLimitRunCluster::getRunId));
        return items.stream().map(run -> view(run, findingsByRun.getOrDefault(run.getId(), List.of()), reservations, clustersByRun.getOrDefault(run.getId(), List.of()))).toList();
    }

    private static Track1QuantityDto.AnalysisRun view(Track1PurchaseLimitRun run, List<Track1PurchaseLimitFinding> runFindings,
            Map<UUID, List<Track1PurchaseLimitFindingReservation>> reservations, List<Track1PurchaseLimitRunCluster> runClusters) {
        boolean succeeded = run.getExecutionStatus() == Track1PurchaseLimitRun.Execution.SUCCEEDED;
        Track1QuantityDto.Evaluation evaluation = !succeeded ? null : new Track1QuantityDto.Evaluation(run.getEvaluationStatus(), run.getEvaluationReason(), run.getPolicyVersion(),
                runFindings.stream().sorted(Comparator.comparing(Track1PurchaseLimitFinding::getAccountRefHmac)).map(finding -> new Track1QuantityDto.Finding(
                        display(run.getId(), "A", finding.getAccountRefHmac()), finding.getEffectiveTicketQuantity(), finding.getMaxTicketsPerAccount(), finding.getPolicyVersion(),
                        reservations.getOrDefault(finding.getId(), List.of()).stream().sorted(Comparator.comparing(Track1PurchaseLimitFindingReservation::getReservationRefHmac))
                                .map(item -> new Track1QuantityDto.ReservationQuantity(display(run.getId(), "R", item.getReservationRefHmac()), item.getEffectiveTicketQuantity())).toList())).toList());
        return new Track1QuantityDto.AnalysisRun(run.getId(), run.getEventId(), run.getPerformanceId(), run.getLedgerBatchId(), run.getLedgerSourceVersion(),
                externalStatus(run), run.getErrorCode(), run.isRetryable(), run.getPolicyId(), run.getPolicyVersion(), run.getAlgorithmVersion(), run.getAnalysisRequestId(), run.getClusterCount(),
                runClusters.stream().sorted(Comparator.comparing(Track1PurchaseLimitRunCluster::getClusterId)).map(item -> new Track1QuantityDto.Cluster(item.getClusterId(), item.getRiskScore(), item.getMemberCount())).toList(),
                run.getNettedExcludedRowCount(), run.getCreatedAt().atOffset(ZoneOffset.UTC), run.getCompletedAt() == null ? null : run.getCompletedAt().atOffset(ZoneOffset.UTC), evaluation);
    }

    private static String externalStatus(Track1PurchaseLimitRun run) {
        return switch (run.getExecutionStatus()) {
            case IN_PROGRESS -> "RUNNING";
            case SUCCEEDED -> "SUCCEEDED";
            default -> "FAILED";
        };
    }

    /** 저장 HMAC은 재식별 위험이 있어 실행별로 분리한 표시 토큰만 응답합니다. */
    private static String display(UUID runId, String kind, String hmac) {
        return kind + "-" + CandidatePipelineSupport.sha256(DISPLAY_PURPOSE, runId.toString(), kind, hmac).substring(0, 32);
    }

    private static LocalDateTime now() { return LocalDateTime.now(ZoneOffset.UTC); }
    private static CaseApiException conflict(String code) { return new CaseApiException(HttpStatus.CONFLICT, code); }
    private static CaseApiException notFound() { return new CaseApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND"); }

    private record Decision(PurchaseLimitPolicy policy, boolean complete, List<Track1PurchaseLedgerRow> payload, int excludedRows, String reason) {
        static Decision unconfirmed(List<Track1PurchaseLedgerRow> ledger, String reason) { return new Decision(null, false, ledger, 0, reason); }
        static Decision incomplete(PurchaseLimitPolicy policy, List<Track1PurchaseLedgerRow> ledger, String reason) { return new Decision(policy, false, ledger, 0, reason); }
    }
}
