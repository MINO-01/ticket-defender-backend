package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.out.api.dto.FastApiClusterResponse;
import com.ticket.defender_core.adapter.out.api.dto.Track1PurchaseLimitAnalysisRequest;
import com.ticket.defender_core.adapter.out.api.dto.Track1PurchaseLimitAnalysisResponse;
import com.ticket.defender_core.adapter.out.persistence.PurchaseLimitPolicyRepository;
import com.ticket.defender_core.domain.PurchaseLimitPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * BL-06 consumes only the immutable version carried by an outbox row.  It deliberately does
 * not follow the publication head, hold a DB transaction during HTTP, or turn missing signals
 * into a successful empty analysis.
 */
@Service
public class BulkLedgerTrack1OutboxConsumer {
    private static final int PAGE_SIZE = 1_000;
    private static final int MAX_TICKETS = 10_000;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final SyntheticReservationImportGate gate;
    private final Track1PurchaseLimitAnalysisService analysis;
    private final PurchaseLimitPolicyRepository policies;
    private final boolean enabled;
    private final boolean cancelledTicketTransferEnabled;

    public BulkLedgerTrack1OutboxConsumer(JdbcTemplate jdbc, TransactionTemplate tx, SyntheticReservationImportGate gate,
                                          Track1PurchaseLimitAnalysisService analysis, PurchaseLimitPolicyRepository policies,
                                          @Value("${ticket-defender.bulk-ledger-track1.enabled:false}") boolean enabled,
                                          @Value("${ticket-defender.bulk-ledger-upload.cancelled-ticket-transfer.enabled:false}") boolean cancelledTicketTransferEnabled) {
        this.jdbc = jdbc; this.tx = tx; this.gate = gate; this.analysis = analysis; this.policies = policies; this.enabled = enabled;
        this.cancelledTicketTransferEnabled = cancelledTicketTransferEnabled;
    }

    @Scheduled(fixedDelayString = "${ticket-defender.bulk-ledger-track1.worker-delay:PT30S}")
    public void scheduled() {
        if (enabled) consumeOne();
    }

    /** Visible to the isolated worker test; one call owns at most one short lease. */
    public void consumeOne() {
        Claim claim = tx.execute(ignored -> claim());
        if (claim == null) return;
        try {
            Input input = tx.execute(ignored -> input(claim));
            if (input == null) return; // input() persisted a fail-closed BLOCKED result.
            Track1PurchaseLimitAnalysisResponse response = analysis.analyzeTrustedLedgerInput(input.request());
            tx.executeWithoutResult(ignored -> complete(claim, input, response));
        } catch (InvalidInput failure) {
            tx.executeWithoutResult(ignored -> block(claim, failure.code()));
        } catch (Retryable failure) {
            tx.executeWithoutResult(ignored -> retry(claim, failure.code()));
        } catch (ResourceAccessException | HttpServerErrorException unavailable) {
            tx.executeWithoutResult(ignored -> retry(claim, "ANALYZER_UNAVAILABLE"));
        } catch (RuntimeException failure) {
            tx.executeWithoutResult(ignored -> block(claim, "ANALYSIS_FAILED"));
        }
    }

    private Claim claim() {
        List<Claim> rows = jdbc.query("""
                select id,tenant_id,event_id,performance_id,version_id from td_bulk_ledger_publication_outbox
                where event_type='LEDGER_PUBLISHED'
                  and (status in ('PENDING','RETRYABLE') or (status='PROCESSING' and lease_until < utc_timestamp(6)))
                  and (lease_until is null or lease_until < utc_timestamp(6))
                order by created_at,id limit 1 for update skip locked
                """, (rs, n) -> new Claim(uuid(rs.getString(1)), uuid(rs.getString(2)), uuid(rs.getString(3)),
                uuid(rs.getString(4)), uuid(rs.getString(5)), "bl06-" + UUID.randomUUID(), 0));
        if (rows.isEmpty()) return null;
        Claim bare = rows.getFirst();
        int updated = jdbc.update("""
                update td_bulk_ledger_publication_outbox set status='PROCESSING',lease_owner=?,
                  lease_until=date_add(utc_timestamp(6),interval 5 minute),fence_token=fence_token+1,
                  delivery_attempt_count=delivery_attempt_count+1,last_error_code=null
                where id=? and (status in ('PENDING','RETRYABLE') or (status='PROCESSING' and lease_until < utc_timestamp(6)))
                """, bare.owner(), bare.outboxId().toString());
        if (updated != 1) return null;
        Long fence = jdbc.queryForObject("select fence_token from td_bulk_ledger_publication_outbox where id=? and lease_owner=?",
                Long.class, bare.outboxId().toString(), bare.owner());
        // A previous worker may still receive an HTTP response, but its fenced completion can no longer write.
        jdbc.update("""
                update track1_purchase_limit_runs r join td_bulk_ledger_track1_analysis_jobs j on j.run_id=r.id
                set r.execution_status='INTERRUPTED',r.error_code='ANALYSIS_INTERRUPTED',r.active_ledger_version_id=null,r.completed_at=utc_timestamp(6)
                where j.outbox_id=? and r.execution_status='IN_PROGRESS'
                """, bare.outboxId().toString());
        jdbc.update("""
                insert into td_bulk_ledger_track1_analysis_jobs(id,outbox_id,tenant_id,event_id,performance_id,version_id,status,attempt_count,created_at,updated_at)
                values(?,?,?,?,?,?,'PENDING',0,utc_timestamp(6),utc_timestamp(6))
                on duplicate key update updated_at=values(updated_at)
                """, UUID.randomUUID().toString(), bare.outboxId().toString(), bare.tenantId().toString(), bare.eventId().toString(),
                bare.performanceId().toString(), bare.versionId().toString());
        return new Claim(bare.outboxId(), bare.tenantId(), bare.eventId(), bare.performanceId(), bare.versionId(), bare.owner(), fence);
    }

    private Input input(Claim claim) {
        gate.requireSyntheticScope(claim.tenantId(), LocalDateTime.now(ZoneOffset.UTC));
        Version version = publishedVersion(claim);
        if (version == null) return blockedInput(claim, "LEDGER_VERSION_NOT_PUBLISHED");
        List<UUID> lineage = lineage(version);
        if (lineage == null) return blockedInput(claim, "LEDGER_LINEAGE_INVALID");
        List<Ticket> tickets = tickets(lineage, claim.versionId());
        if (tickets.isEmpty()) return blockedInput(claim, "NO_EFFECTIVE_TICKETS");
        if (tickets.size() >= MAX_TICKETS) return blockedInput(claim, "DATA_INPUT_TOO_LARGE");
        if (tickets.stream().anyMatch(t -> !validQuantityUnit(t.unit()) || t.account() == null
                || (!cancelledTicketTransferEnabled || !isCancelled(t)) && t.signalMissing()))
            return blockedInput(claim, "LEDGER_SIGNAL_OR_STATUS_INCOMPLETE");
        String externalEvent = jdbc.queryForObject("select external_event_ref from events where id=? and tenant_id=?", String.class,
                claim.eventId().toString(), claim.tenantId().toString());
        UUID requestId = UUID.randomUUID();
        Decision decision = decide(version, tickets, policies.findByTenantIdAndEventIdOrderByPerformanceIdAsc(claim.tenantId(), claim.eventId()), claim.performanceId());
        if (decision.payload().isEmpty()) return blockedInput(claim, "NO_EFFECTIVE_TICKETS_AFTER_NETTING");
        List<Ticket> activePayload = activePayload(decision.payload(), cancelledTicketTransferEnabled);
        if (activePayload.isEmpty()) return blockedInput(claim, "NO_EFFECTIVE_ACTIVE_TICKETS");
        List<Track1PurchaseLimitAnalysisRequest.Ticket> payload = activePayload.stream().map(t ->
                new Track1PurchaseLimitAnalysisRequest.Ticket(t.account(), t.reservation(), externalEvent, claim.performanceId(),
                        t.purchasedAt().atOffset(ZoneOffset.UTC), t.quantity(), t.payment(), t.address(), t.device(), t.ip(),
                        Track1PurchaseLimitAnalysisRequest.QuantityInputType.INDIVIDUAL_TICKET, t.ticket(),
                        Track1PurchaseLimitAnalysisRequest.TicketStatus.valueOf(t.status()), t.sourceVersion(),
                        version.asOf() == null ? null : version.asOf().atOffset(ZoneOffset.UTC))).toList();
        boolean policyConfirmed = decision.policy() != null;
        var scope = new Track1PurchaseLimitAnalysisRequest.PerformanceScope(claim.eventId(), externalEvent, claim.performanceId(),
                policyConfirmed ? Track1PurchaseLimitAnalysisRequest.PolicyVerificationStatus.CONFIRMED : Track1PurchaseLimitAnalysisRequest.PolicyVerificationStatus.POLICY_NOT_CONFIRMED,
                policyConfirmed ? confirmedPolicy(decision.policy()) : null,
                decision.complete() ? Track1PurchaseLimitAnalysisRequest.QuantityDataStatus.COMPLETE
                        : policyConfirmed ? Track1PurchaseLimitAnalysisRequest.QuantityDataStatus.INCOMPLETE : Track1PurchaseLimitAnalysisRequest.QuantityDataStatus.UNKNOWN,
                decision.complete() ? decision.quantitySourceVersion() : null,
                decision.complete() ? version.asOf().atOffset(ZoneOffset.UTC) : null,
                decision.complete() ? Track1PurchaseLimitAnalysisRequest.CancellationHandling.NETTED : Track1PurchaseLimitAnalysisRequest.CancellationHandling.NOT_CONFIRMED,
                Track1PurchaseLimitAnalysisRequest.DuplicateHandling.DEDUPLICATED, decision.reason());
        List<Track1PurchaseLimitAnalysisRequest.CancelledTicket> cancelled = List.of();
        if (cancelledTicketTransferEnabled) {
            Set<String> keyVersions = activePayload.stream().map(Ticket::keyVersion).collect(java.util.stream.Collectors.toSet());
            if (keyVersions.size() != 1 || keyVersions.contains(null) || keyVersions.contains(""))
                return blockedInput(claim, "CANCELLED_TICKET_HMAC_CONTRACT_MISMATCH");
            cancelled = cancelledTickets(lineage, keyVersions.iterator().next(), externalEvent, claim.performanceId());
            if (activePayload.size() + cancelled.size() > MAX_TICKETS)
                return blockedInput(claim, "DATA_INPUT_TOO_LARGE");
        }
        UUID run = UUID.randomUUID();
        Integer previousAttempts = jdbc.queryForObject("select attempt_count from td_bulk_ledger_track1_analysis_jobs where outbox_id=? for update",
                Integer.class, claim.outboxId().toString());
        if (previousAttempts == null) throw new Retryable("ANALYSIS_JOB_MISSING");
        int attemptNo = previousAttempts + 1;
        jdbc.update("""
                insert into track1_purchase_limit_runs(id,tenant_id,event_id,performance_id,ledger_batch_id,ledger_version_id,publication_outbox_id,
                 active_ledger_batch_id,active_ledger_version_id,created_by_membership_id,idempotency_key_digest,request_digest,analysis_request_id,
                 ledger_source_version,policy_id,policy_version,evaluation_reason,execution_status,netted_excluded_row_count,created_at,row_version)
                select ?,?,?,?,?,?,?,null,?,p.requester_membership_id,?,?,?,?,?,?,?,'IN_PROGRESS',?,utc_timestamp(6),0
                from td_bulk_ledger_publications p where p.id=(select publication_id from td_bulk_ledger_publication_outbox where id=?)
                """, run.toString(), claim.tenantId().toString(), claim.eventId().toString(), claim.performanceId().toString(), null,
                claim.versionId().toString(), claim.outboxId().toString(), claim.versionId().toString(),
                attemptIdempotencyDigest(claim.outboxId(), attemptNo),
                CandidatePipelineSupport.sha256("BL06_REQUEST", claim.versionId().toString(), Integer.toString(attemptNo)), requestId.toString(), claim.versionId().toString(),
                policyConfirmed ? decision.policy().getId().toString() : null,
                policyConfirmed ? decision.policy().getPolicyVersion() : null, decision.reason(), decision.excludedRows(), claim.outboxId().toString());
        jdbc.update("update td_bulk_ledger_track1_analysis_jobs set status='PROCESSING',attempt_count=attempt_count+1,run_id=?,updated_at=utc_timestamp(6) where outbox_id=?",
                run.toString(), claim.outboxId().toString());
        return new Input(run, new Track1PurchaseLimitAnalysisRequest(requestId.toString(), List.of(scope), payload, cancelled));
    }

    private List<Track1PurchaseLimitAnalysisRequest.CancelledTicket> cancelledTickets(List<UUID> lineage,
            String keyVersion, String eventId, UUID performanceId) {
        String marks = String.join(",", java.util.Collections.nCopies(lineage.size(), "?"));
        String sql = """
            with ranked as (
              select tv.*,v.revision_no,row_number() over(partition by tv.ticket_reference_hmac order by v.revision_no desc) rn
              from td_bulk_ledger_ticket_versions tv join td_bulk_ledger_versions v on v.id=tv.version_id
              where tv.version_id in (%s)
            )
            select r.ticket_reference_hmac,r.account_reference_hmac,r.hmac_key_version,r.seat_zone,r.seat_row,r.seat_number,r.cancelled_at
            from ranked r where r.rn=1 and r.ticket_status in ('CANCELLED','REFUNDED')
              and r.cancelled_at is not null and r.hmac_key_version=?
            order by r.ticket_reference_hmac limit %d
            """.formatted(marks, MAX_TICKETS + 1);
        ArrayList<Object> args = new ArrayList<>();
        lineage.forEach(id -> args.add(id.toString()));
        args.add(keyVersion);
        List<Track1PurchaseLimitAnalysisRequest.CancelledTicket> result = jdbc.query(sql, (rs, n) -> {
            String ticketHmac = rs.getString(1), accountHmac = rs.getString(2), rowKey = rs.getString(3);
            Timestamp cancelledAt = rs.getTimestamp(7);
            if (ticketHmac == null || !ticketHmac.matches("[0-9a-f]{64}") || accountHmac == null
                    || !accountHmac.matches("[0-9a-f]{64}") || !keyVersion.equals(rowKey) || cancelledAt == null)
                throw new InvalidInput("CANCELLED_TICKET_IDENTITY_INVALID");
            return new Track1PurchaseLimitAnalysisRequest.CancelledTicket(accountHmac, ticketHmac, eventId,
                    performanceId, rs.getString(4), rs.getString(5), rs.getString(6),
                    cancelledAt.toLocalDateTime().atOffset(ZoneOffset.UTC));
        }, args.toArray());
        if (result.size() > MAX_TICKETS) throw new InvalidInput("CANCELLED_TICKET_LIMIT_EXCEEDED");
        return result;
    }

    private static boolean isCancelled(Ticket ticket) {
        return "CANCELLED".equals(ticket.status()) || "REFUNDED".equals(ticket.status());
    }

    static List<Ticket> activePayload(List<Ticket> payload, boolean cancelledTicketTransferEnabled) {
        return cancelledTicketTransferEnabled ? payload.stream().filter(ticket -> !isCancelled(ticket)).toList() : payload;
    }

    private Version publishedVersion(Claim claim) {
        List<Version> found = jdbc.query("""
                select v.id,v.base_version_id,v.generation_no,v.revision_no,v.source_as_of,j.mode,j.complete_snapshot
                from td_bulk_ledger_versions v join td_bulk_ledger_publication_history h on h.version_id=v.id
                  join td_bulk_import_jobs j on j.id=v.import_id and j.tenant_id=v.tenant_id
                where v.id=? and v.tenant_id=? and v.event_id=? and v.performance_id=? and v.status='PUBLISHED'
                """, (rs,n) -> new Version(uuid(rs.getString(1)), nullable(rs.getString(2)), rs.getLong(3), rs.getLong(4),
                rs.getTimestamp(5) == null ? null : rs.getTimestamp(5).toLocalDateTime(),
                "FULL_SNAPSHOT".equals(rs.getString(6)) && rs.getBoolean(7)), claim.versionId().toString(), claim.tenantId().toString(),
                claim.eventId().toString(), claim.performanceId().toString());
        return found.size() == 1 ? found.getFirst() : null;
    }

    private List<UUID> lineage(Version current) {
        ArrayList<UUID> ids = new ArrayList<>(); Version cursor = current;
        while (true) {
            ids.add(cursor.id());
            if (cursor.revision() == 0) return cursor.base() == null ? ids.reversed() : null;
            if (cursor.base() == null || cursor.revision() < 1) return null;
            List<Version> parent = jdbc.query("select id,base_version_id,generation_no,revision_no,source_as_of,false from td_bulk_ledger_versions where id=? and status='PUBLISHED'",
                    (rs,n) -> new Version(uuid(rs.getString(1)), nullable(rs.getString(2)), rs.getLong(3),rs.getLong(4),
                            rs.getTimestamp(5) == null ? null : rs.getTimestamp(5).toLocalDateTime(), rs.getBoolean(6)), cursor.base().toString());
            if (parent.size()!=1 || parent.getFirst().generation()!=cursor.generation() || parent.getFirst().revision()!=cursor.revision()-1) return null;
            cursor = parent.getFirst();
        }
    }
    private List<Ticket> tickets(List<UUID> lineage, UUID currentVersion) {
        String marks = String.join(",", java.util.Collections.nCopies(lineage.size(), "?"));
        ArrayList<Ticket> result = new ArrayList<>(); String after = "";
        while (result.size() < MAX_TICKETS) {
            String sql = """
                with ranked as (select tv.*,v.revision_no,row_number() over(partition by tv.ticket_reference_hmac order by v.revision_no desc) rn
                  from td_bulk_ledger_ticket_versions tv join td_bulk_ledger_versions v on v.id=tv.version_id where tv.version_id in (%s))
                select r.ticket_reference_hmac,r.reservation_reference_hmac,r.account_reference_hmac,r.hmac_key_version,r.purchased_at,r.ticket_quantity,
                       r.quantity_unit,r.ticket_status,r.source_version,s.payment_hmac,s.address_hmac,s.device_hmac,s.ip_hmac,s.hmac_key_version,s.source_version
                from ranked r left join td_bulk_ledger_track1_ticket_signals s on s.version_id=r.version_id and s.ticket_reference_hmac=r.ticket_reference_hmac
                where r.rn=1 and r.ticket_reference_hmac>? order by r.ticket_reference_hmac limit %d
                """.formatted(marks, PAGE_SIZE);
            List<Object> args = new ArrayList<>(); lineage.forEach(id -> args.add(id.toString())); args.add(after);
            List<Ticket> page = jdbc.query(sql, (rs,n) -> new Ticket(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),
                    rs.getTimestamp(5).toLocalDateTime(),rs.getInt(6),rs.getString(7),rs.getString(8),rs.getString(9),rs.getString(10),
                    rs.getString(11),rs.getString(12),rs.getString(13),rs.getString(14),rs.getString(15)), args.toArray());
            if (page.isEmpty()) break; result.addAll(page); after=page.getLast().ticket(); if (page.size()<PAGE_SIZE) break;
        }
        return result;
    }

    /**
     * A public version is usable for a limit evaluation only when it is itself a complete snapshot.
     * A historical baseline plus later change events proves lineage, but does not by itself prove that
     * all cancellation/refund outcomes have been reflected in the current source.  It therefore remains
     * an explicitly incomplete Data v2 input rather than being silently netted.
     */
    static Decision decide(Version version, List<Ticket> ledger, List<PurchaseLimitPolicy> all, UUID performanceId) {
        PurchaseLimitPolicy policy = selectPolicy(ledger, all, performanceId);
        if (policy == null) return Decision.unconfirmed(ledger, "NO_CONFIRMED_ACCOUNT_POLICY");
        if (policy.getApplicationScope() == PurchaseLimitPolicy.ApplicationScope.EVENT)
            return Decision.unconfirmed(ledger, "EVENT_SCOPE_AGGREGATION_NOT_SUPPORTED");
        if (policy.getCancellationRefundRule() != PurchaseLimitPolicy.CancellationRefundRule.NETTED)
            return Decision.incomplete(policy, ledger, "GROSS_RULE_NOT_SUPPORTED");
        if (!version.completeSnapshot() || version.asOf() == null)
            return Decision.incomplete(policy, ledger, "PUBLIC_LEDGER_SNAPSHOT_INCOMPLETE");
        if (ledger.stream().anyMatch(ticket -> ticket.purchasedAt().isAfter(version.asOf())))
            return Decision.incomplete(policy, ledger, "PUBLIC_LEDGER_SOURCE_AS_OF_INVALID");
        String sourceVersion = singleSourceVersion(ledger);
        if (sourceVersion == null)
            return Decision.incomplete(policy, ledger, "PUBLIC_LEDGER_SOURCE_VERSION_MIXED");
        boolean ready = ledger.stream().allMatch(ticket -> PurchaseLimitQuantityInputContract.assess(policy,
                individual(ticket, version.asOf())).status()
                == PurchaseLimitQuantityInputContract.AssessmentStatus.READY_FOR_INDIVIDUAL_TICKET_V2);
        if (!ready) return Decision.incomplete(policy, ledger, "PUBLIC_LEDGER_QUANTITY_INCOMPLETE");
        List<Ticket> active = ledger.stream().filter(ticket -> "ACTIVE".equals(ticket.status())).toList();
        return new Decision(policy, true, active, ledger.size() - active.size(), sourceVersion, null);
    }

    private static PurchaseLimitPolicy selectPolicy(List<Ticket> ledger, List<PurchaseLimitPolicy> all, UUID performanceId) {
        PurchaseLimitPolicy selected = null;
        for (Ticket ticket : ledger) {
            PurchaseLimitPolicySelector.Result result = PurchaseLimitPolicySelector.select(all,
                    new PurchaseLimitPolicySelector.Context(performanceId, null,
                            PurchaseLimitPolicy.LimitSubject.ACCOUNT, false, ticket.purchasedAt()));
            if (result.status() != PurchaseLimitPolicySelector.Status.SELECTED
                    || selected != null && !selected.getId().equals(result.policy().getId())) return null;
            selected = result.policy();
        }
        return selected;
    }

    private static String singleSourceVersion(List<Ticket> tickets) {
        List<String> versions = tickets.stream().map(Ticket::sourceVersion).filter(value -> value != null && !value.isBlank())
                .distinct().toList();
        return versions.size() == 1 && tickets.stream().allMatch(ticket -> ticket.sourceVersion() != null && !ticket.sourceVersion().isBlank())
                ? versions.getFirst() : null;
    }

    private static PurchaseLimitQuantityInputContract.IndividualTicket individual(Ticket ticket, LocalDateTime sourceAsOf) {
        return new PurchaseLimitQuantityInputContract.IndividualTicket(ticket.account(), ticket.reservation(), ticket.ticket(),
                ticket.purchasedAt().toInstant(ZoneOffset.UTC), ticket.quantity(),
                PurchaseLimitQuantityInputContract.TicketStatus.valueOf(ticket.status()), ticket.sourceVersion(),
                sourceAsOf.toInstant(ZoneOffset.UTC));
    }

    private static Track1PurchaseLimitAnalysisRequest.ConfirmedPolicy confirmedPolicy(PurchaseLimitPolicy policy) {
        return new Track1PurchaseLimitAnalysisRequest.ConfirmedPolicy(policy.getMaxTicketsPerAccount(), policy.getPolicyVersion(),
                policy.getEffectiveFrom().atOffset(ZoneOffset.UTC),
                policy.getEffectiveUntil() == null ? null : policy.getEffectiveUntil().atOffset(ZoneOffset.UTC));
    }

    private static boolean validQuantityUnit(String unit) { return "TICKET".equals(unit) || "ORDER_LINE".equals(unit); }

    private Input blockedInput(Claim c, String code) { block(c, code); return null; }
    private void complete(Claim c, Input input, Track1PurchaseLimitAnalysisResponse response) {
        requireLease(c); if (response == null || !input.request().requestId().equals(response.requestId()) || response.purchaseLimitEvaluations()==null || response.purchaseLimitEvaluations().size()!=1) throw new Retryable("ANALYZER_CONTRACT_VIOLATION");
        for (FastApiClusterResponse cluster : response.clusters()) jdbc.update("insert into track1_purchase_limit_run_clusters(id,run_id,cluster_id,risk_score,member_count) values(?,?,?,?,?)",
                UUID.randomUUID().toString(),input.runId().toString(),cluster.clusterId(),cluster.riskScore(),cluster.members().size());
        Track1PurchaseLimitAnalysisResponse.PurchaseLimitEvaluation evaluation=response.purchaseLimitEvaluations().getFirst();
        if (evaluation.status()==Track1PurchaseLimitAnalysisResponse.EvaluationStatus.EVALUATED) for (Track1PurchaseLimitAnalysisResponse.AccountLimitFinding finding:evaluation.accountLimitFindings()) {
            UUID findingId=UUID.randomUUID();
            jdbc.update("insert into track1_purchase_limit_findings(id,run_id,account_ref_hmac,effective_ticket_quantity,max_tickets_per_account,policy_version) values(?,?,?,?,?,?)",
                    findingId.toString(),input.runId().toString(),finding.accountId(),finding.effectiveTicketQuantity(),finding.maxTicketsPerAccount(),finding.policyVersion());
            for (Track1PurchaseLimitAnalysisResponse.ReservationQuantity reservation:finding.reservationQuantities()) jdbc.update("insert into track1_purchase_limit_finding_reservations(id,finding_id,reservation_ref_hmac,effective_ticket_quantity) values(?,?,?,?)",
                    UUID.randomUUID().toString(),findingId.toString(),reservation.reservationNo(),reservation.effectiveTicketQuantity());
        }
        for (Track1PurchaseLimitAnalysisResponse.SharedSignalObservation observation:response.sharedSignalObservations()) jdbc.update("""
                insert into track1_purchase_limit_shared_signal_observations(id,run_id,observation_id,scope_type,scope_id,signal_type,distinct_account_count,scope_account_count,distinct_reservation_count,total_ticket_quantity,ticket_quantity_status,payment_shared_account_count,device_shared_account_count,ip_network_type,purchase_window_seconds,excluded_from_louvain)
                values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,UUID.randomUUID().toString(),input.runId().toString(),observation.observationId(),observation.scopeType(),observation.scopeId(),observation.signalType().name(),observation.distinctAccountCount(),observation.scopeAccountCount(),observation.distinctReservationCount(),observation.totalTicketQuantity(),observation.ticketQuantityStatus().name(),observation.corroboration().paymentSharedAccountCount(),observation.corroboration().deviceSharedAccountCount(),observation.ipNetworkType(),observation.purchaseWindowSeconds(),observation.excludedFromLouvain());
        jdbc.update("update track1_purchase_limit_runs set execution_status='SUCCEEDED',evaluation_status=?,algorithm=?,algorithm_version=?,cluster_count=?,analyzed_at=?,completed_at=utc_timestamp(6),active_ledger_version_id=null where id=? and execution_status='IN_PROGRESS'",
                evaluation.status().name(),response.algorithm(),response.algorithmVersion(),response.clusters().size(),Timestamp.valueOf(response.analyzedAt().withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime()),input.runId().toString());
        finish(c, "SUCCEEDED", null, "DELIVERED");
    }
    private void retry(Claim c, String code) {
        closeOpenRun(c, "ANALYZER_UNAVAILABLE", code);
        Integer attempts=jdbc.queryForObject("select attempt_count from td_bulk_ledger_track1_analysis_jobs where outbox_id=?",Integer.class,c.outboxId().toString());
        if(attempts!=null && attempts>=3) { finish(c,"BLOCKED","ANALYZER_RETRY_EXHAUSTED","BLOCKED"); return; }
        finish(c, "RETRYABLE", code, "RETRYABLE");
    }
    private void block(Claim c, String code) { closeOpenRun(c, "INPUT_REJECTED", code); finish(c, "BLOCKED", code, "BLOCKED"); }
    private void closeOpenRun(Claim c, String status, String code) {
        jdbc.update("""
                update track1_purchase_limit_runs r join td_bulk_ledger_track1_analysis_jobs j on j.run_id=r.id
                set r.execution_status=?,r.error_code=?,r.active_ledger_version_id=null,r.completed_at=utc_timestamp(6)
                where j.outbox_id=? and r.execution_status='IN_PROGRESS'
                """, status, code, c.outboxId().toString());
    }
    private void finish(Claim c,String jobStatus,String code,String outboxStatus) {
        requireLease(c); jdbc.update("update td_bulk_ledger_track1_analysis_jobs set status=?,failure_code=?,updated_at=utc_timestamp(6) where outbox_id=?",jobStatus,code,c.outboxId().toString());
        jdbc.update("update td_bulk_ledger_publication_outbox set status=?,lease_owner=null,lease_until=null,last_error_code=? where id=? and fence_token=? and lease_owner=?",outboxStatus,code,c.outboxId().toString(),c.fence(),c.owner());
    }
    private void requireLease(Claim c) { Integer ok=jdbc.queryForObject("select count(*) from td_bulk_ledger_publication_outbox where id=? and status='PROCESSING' and fence_token=? and lease_owner=?",Integer.class,c.outboxId().toString(),c.fence(),c.owner()); if(ok==null||ok!=1) throw new Retryable("OUTBOX_LEASE_LOST"); }
    private static UUID uuid(String s){return UUID.fromString(s);} private static UUID nullable(String s){return s==null?null:uuid(s);}
    static String attemptIdempotencyDigest(UUID outboxId, int attemptNo) {
        if (outboxId == null || attemptNo < 1) throw new IllegalArgumentException("invalid outbox attempt");
        return CandidatePipelineSupport.sha256("BL06", outboxId.toString(), Integer.toString(attemptNo));
    }
    private record Claim(UUID outboxId,UUID tenantId,UUID eventId,UUID performanceId,UUID versionId,String owner,long fence){}
    static record Version(UUID id,UUID base,long generation,long revision,LocalDateTime asOf,boolean completeSnapshot){}
    static record Ticket(String ticket,String reservation,String account,String keyVersion,LocalDateTime purchasedAt,int quantity,String unit,String status,String sourceVersion,String payment,String address,String device,String ip,String signalVersion,String signalSourceVersion){ boolean signalMissing(){return signalVersion==null||!signalVersion.equals(keyVersion)||!sourceVersion.equals(signalSourceVersion)||(payment==null&&address==null&&device==null&&ip==null);} }
    static record Decision(PurchaseLimitPolicy policy, boolean complete, List<Ticket> payload, int excludedRows,
                           String quantitySourceVersion, String reason) {
        static Decision unconfirmed(List<Ticket> ledger, String reason) { return new Decision(null, false, ledger, 0, null, reason); }
        static Decision incomplete(PurchaseLimitPolicy policy, List<Ticket> ledger, String reason) { return new Decision(policy, false, ledger, 0, null, reason); }
    }
    private record Input(UUID runId,Track1PurchaseLimitAnalysisRequest request){}
    private static final class InvalidInput extends RuntimeException { private final String code; private InvalidInput(String code){this.code=code;} String code(){return code;} }
    private static final class Retryable extends RuntimeException { private final String code; private Retryable(String code){this.code=code;} String code(){return code;} }
}
