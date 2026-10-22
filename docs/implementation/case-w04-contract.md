# W04 Case foundation contract

Status: approved for Backend Issue #34 only. This document does not approve the shared Specs DEC-09, DEC-12 or DEC-13 proposals.

## Scope and provenance

Case is a human investigation with its own UUID and state. TicketAudit is an analysis/audit record; its FRAUD_DETECTED value is not a human verdict. Creating a Case never calls FastAPI, creates an approval, confirms fraud or enables PDF issuance.

GRAPH_CLUSTER sources refer to **STORED_CLUSTER_EVIDENCE** by the server-verified `(tenantId, eventId, analysisRequestId, clusterId)` tuple. The server verifies a nonempty set of persisted MacroAuditEvidence members with consistent algorithm, version, completion time and risk. Older rows without tenant/event scope are ineligible. `analysisRequestId` is a per-call correlation UUID and is not a persisted `analysisRunId`. Existing evidence-key deduplication means stored members may be only a subset of an analysis response. No full run or complete cluster claim is made.

FAN_REPORT uses `fanReportId`. EVIDENCE_ASSET uses `evidenceId` together with its actual `fanReportId`. Only a non-expired RECEIVED report and a non-expired STORED asset in the same tenant/event may be linked. Asset linking additionally checks the private object by HEAD, including length, MIME and the saved opaque ETag/version marker where available. The client cannot provide an object key. Missing objects and storage outages have distinct errors. HEAD is a point-in-time check; deletion or replacement between HEAD and transaction commit remains possible. An ETag is not an immutable object version or a SHA-256 proof. Original asset bytes remain outside Case tables and are never exposed by these APIs.

An initial set of verified sources starts `evidenceRevision` at 1. This is a source-set revision, not an EvidenceSnapshot or approval-ready manifest. No source-addition API exists in W04. Assignee changes and ordinary state transitions change `caseVersion`, not `evidenceRevision`.

## Duplicate and follow-up policy

The same source is unique within one Case. Before creating another Case that cites a previously linked source, the server checks existing Cases. Reuse is allowed only as an explicit follow-up to a DISMISSED or CLOSED Case with matching `relatedCaseId` and `relationReason`; reuse in another active Case is rejected. The new Case supplies and verifies its own sources. Status, approval and activity history are never copied. The per-Case database uniqueness constraint and request-idempotency uniqueness are atomic. The cross-Case duplicate check is application-side and can race across server instances; it is not a global uniqueness guarantee. A future approved policy would need a serialized source claim or equivalent database constraint.

## Authorization and commands

Tenant is derived from the server's event and active administrator membership, never from a request body. Case list requires `eventId` and queries only that event in the repository. REVIEWER and TENANT_ADMIN may read granted-event Cases. ANALYST may read only Cases they created or are assigned to, using the repository predicate. APPROVER has no general W04 Case read grant. Create requires ANALYST or REVIEWER; transition and assignee commands require REVIEWER. Target assignees are active ANALYST or REVIEWER memberships in the same tenant with a current grant for the event. The public API calls this UUID `assigneeMembershipId`, not `assigneeUserId`.

Only OPEN→TRIAGED, TRIAGED→IN_REVIEW, IN_REVIEW→NEEDS_EVIDENCE, NEEDS_EVIDENCE→IN_REVIEW and IN_REVIEW→DISMISSED are executable. Every mutation requires a UUIDv4 Idempotency-Key, an expectedCaseVersion and an audit reason. State-specific business evidence is required. NEEDS_EVIDENCE→IN_REVIEW cannot claim newly attached evidence in W04; it records why requested evidence could not be obtained. DISMISSED means an investigation was dismissed, not a legal innocence finding. Analysis failure or UNVERIFIABLE alone is insufficient. Direct READY_FOR_APPROVAL, APPROVED_FOR_REPORT, CLOSED or REOPEN commands are rejected.

Case rows use JPA @Version. A state/assignee change and its append-only CaseActivity inserts are committed or rolled back in one MySQL transaction. Idempotency is separate: `(tenantId, actorMembershipId, operation, targetCaseId, keyDigest)` is unique; canonical request digest distinguishes replay from conflict. The stored response contains only Case metadata, not request body or secrets. Current authorization is checked before replay. Records live with their Case; deletion must be tied to an approved Case retention policy. W02 fan-report idempotency keys and tables are separate.

## W04 transition rules

All five transitions require REVIEWER, `expectedCaseVersion` and a substantive `reason`. A stale version returns 409 `CASE_VERSION_CONFLICT`; an unsupported state pair returns 409 `CASE_STATE_CONFLICT`; missing business fields return 422 `VALIDATION_FAILED`. A successful transition records the prior and new status, actor membership, actor role at the time, version, reason and UTC time in CaseActivity in the same transaction.

| From | To | Additional input or evidence |
| --- | --- | --- |
| OPEN | TRIAGED | `classification`; assigned active and event-granted analyst/reviewer, or `unassignedReason` |
| TRIAGED | IN_REVIEW | A currently active assigned membership with the event grant |
| IN_REVIEW | NEEDS_EVIDENCE | `missingEvidence` and `requestedAction` |
| NEEDS_EVIDENCE | IN_REVIEW | `unavailableReason`; W04 cannot link new evidence during resumption |
| IN_REVIEW | DISMISSED | `dismissalBasis` and a detailed human review reason; analysis failure alone is insufficient |

Assignee changes require REVIEWER, `assigneeMembershipId`, `expectedCaseVersion` and reason. Activities include both previous and new membership IDs. The stored membership UUID is distinct from the JWT subject, and activity keeps the actor role as observed at command time. `@Immutable` prevents Hibernate updates to an existing activity; operational DB permissions and retention controls still need review before external release.

## Migration and verification order

Apply `docs/database/case-foundation-migration.sql` once after the existing administrator, Track 1 scope and fan-report migrations. The migration adds scoped indexes to existing source tables, then creates the four W04 tables and their scoped foreign keys. It does not rewrite stored TicketAudit, fan reports or evidence files. The MySQL CI job applies the script to a disposable schema containing synthetic pre-W04 rows, verifies those rows remain, checks scoped constraints, and then starts Hibernate with `ddl-auto=validate`. A separate test runs two REVIEWER commands at the same version against the migrated schema. The MinIO job repeats schema setup and checks the new private HEAD behavior. Local unit tests and JPA-generated H2 tables cannot establish that the SQL migration succeeds on MySQL 8. Runner success must be checked after a user-authorized push.

The current W02 bucket requires disabled versioning. HEAD compares stored length, MIME and an opaque ETag or version marker where present. It does not fetch and hash bytes at Case creation. A delete or replacement between HEAD and commit remains possible. No legal hold, periodic object retention purge, Case source invalidation after purge, or immutable evidence snapshot is provided by W04. These are operational release blockers for evidence-dependent approval and reporting.

S3 authorizes `HeadObject` with `s3:GetObject`; there is no native IAM permission that allows only HEAD while denying GET on the same object. W04 therefore uses separate metadata credentials (`FAN_REPORT_S3_HEAD_ACCESS_KEY` and `FAN_REPORT_S3_HEAD_SECRET_KEY`) scoped to the private evidence prefix, while the existing W02 writer keeps its write/delete policy. No credentials are embedded in source or committed CI configuration. An operator must provision, rotate and audit the metadata credential as a read-capable secret. Missing metadata credentials fail asset source verification closed with 503; they do not enable public downloads. The Case API can be used with public fan-report intake disabled if metadata credentials and the private endpoint are configured. Bucket ACL, encryption, retention and private network access remain deployment responsibilities.

## Existing proposal differences and operational holds

The Issue #20 OpenAPI uses nonexistent UUID `findingIds`/`analysisRunId`, a UUID `assigneeUserId`, optional list `eventId`, AUDITOR and fields backed by unimplemented IssuanceHold/ReportVersion. W04 uses graph cluster tuples, `assigneeMembershipId`, required eventId and only implemented roles/fields. This is a Backend-scoped contract adjustment; shared Specs remain PROPOSED.

Case source references do not extend W02 object retention. Current report and asset `retentionUntil` are recorded but automatic expiry deletion and legal hold are not implemented. Operators must block external release of evidence-dependent workflows until expiry, Case references, legal hold, actual object deletion, backups and audit are reconciled. No EvidenceSnapshot, ApprovalRequest, ApprovalDecision, ReportVersion or official PDF issuance is activated by W04.
