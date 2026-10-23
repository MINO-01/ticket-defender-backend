# W05 evidence snapshot and report draft contract

This implements Issue #36 on top of the W04 Case model. It does not activate approval,
READY_FOR_APPROVAL, PDF rendering, or a new evidence revision. The existing Case source
references are the capture input. `evidenceRevision` remains 1 until a later, separately
authorized source-change command exists.

## Choices and boundaries

| Choice | Decision | Reason |
| --- | --- | --- |
| Snapshot storage | Separate append-only EvidenceSnapshot row with canonical manifest JSON | A JSON field on Case would overwrite history. A separate item table would add mutable joins without a W05 need. Source references remain the relational, scoped source identity. |
| Canonical digest | Fixed schema version and field order, sorted source keys, UTC timestamps, fixed fields per source type, explicit JSON null for present empty fields, SHA-256 of UTF-8 JSON excluding the digest | Default object serialization and input order alone are unstable. An external canonicalization package is unnecessary for this fixed schema. |
| Original image | Record the W02 upload SHA-256 and fresh private HEAD size, MIME, and opaque marker | W02 may store an ETag as `objectVersion`, and bucket versioning is disabled. HEAD does not authenticate original bytes or guarantee future retention. No approval eligibility follows from this capture. |
| Draft content | Store canonical input JSON and its SHA-256 with a logical `draft-input-v1` schema label | No immutable rendering template artifact exists. The label is not a deployable PDF template, so W05 drafts cannot be rendered or issued. |
| Revision allocation | Change the Case row under `@Version` in the same transaction and enforce unique case/revision keys | This serializes successful writes across instances without a long pessimistic lock. A loser receives 409 and does not silently retry its command. |
| Idempotency | Reuse Case idempotency records with separate operation names and stored response references | The tenant, actor, operation, case and key digest remain distinct from W02 intake. The current actor's access is checked before replay. |

The snapshot alternatives were a manifest column on Case (A), an independent snapshot row
(B), and a snapshot plus item rows (C). A loses historical revisions when replaced; C
provides item-level foreign keys but duplicates the existing scoped CaseSourceReference
relations and adds migration and lifecycle work. B is used for W05. For hashing, ordinary
serialization (A) does not define ordering; fixed schema and ordering (B) are sufficient;
an external canonicalization library (C) would add a dependency and still require a schema.

For original files, W05 uses the existing private key, upload SHA-256 and fresh HEAD metadata
(A) solely as a recorded reference. A storage version ID (B) would require enabled,
enforced bucket versioning and a different W02 operational contract. An immutable copy (C)
would introduce new storage, access, retention and legal-hold decisions. Neither B nor C is
silently enabled. Case `@Version` plus DB uniqueness is chosen over conditional SQL CAS and
pessimistic locking for short competing writes; a failed command is returned as 409 without
reusing a failed transaction or rerunning source analysis.

Snapshots capture only `IN_REVIEW` Cases. Each `(case,evidenceRevision)` has at most one
snapshot. Repeating the same idempotency key and body returns the prior result after current
authorization; another key gets 409. A draft also requires `IN_REVIEW` and the current
snapshot. The next draft revision is `max(revision)+1`. Neither command changes Case status
or evidence revision. Both increment Case version and append a CaseActivity in the same
transaction. A failed activity write rolls back the snapshot or draft as well.
ReportVersion stores the snapshot's evidence revision, content revision, and an independent
JPA `version` reserved for future status transitions. Its only current status is DRAFT.

Stored cluster references identify only the members currently persisted under
`(tenantId,eventId,analysisRequestId,clusterId)`. They do not prove a complete analysis run
or full cluster population. Source scope, current state and retention are rechecked when
capturing. Asset HEAD is performed against a server-owned key only; a missing object differs
from a storage outage. There is a time-of-check/time-of-use interval between HEAD and the
database commit. Snapshot row immutability does not make a referenced W02 object immutable.

Snapshot and draft DTOs omit a standalone tenant ID, object keys, source PII, private URLs,
and storage credentials. The snapshot manifest contains the authorized tenant and event IDs
to bind its digest to the verified scope. Idempotency records contain result IDs only, not a
second copy of the manifest or draft text. The current W04 read policy applies to metadata.
Original bytes remain outside this API. Snapshot and draft content remain private business
data and must be destroyed under a future Case retention and legal-hold policy. No retention
extension is introduced here. Case source invalidation on W02 object deletion, true immutable object
versions or immutable copies, template artifact retention, and approval eligibility are
operational blockers for W06 and external issuance.

The PROPOSED approval contract describes a future atomic READY_FOR_APPROVAL transition and
snapshot creation. W05 exposes a preparation command instead and does not claim that future
transaction boundary is implemented. It also does not implement the proposal's
`analysisRunId`, `findingId`, current snapshot pointer, ApprovalRequest, or PDF gates.
The draft detail route uses the proposed `/api/v1/report-versions/{reportVersionId}` path;
the snapshot creation and list routes are W05 scoped additions. The proposed global Case
permissions and approval state transitions remain unapproved.
List responses are capped to 100 records per page. Success and error responses use
`Cache-Control: no-store` because report input and investigation metadata are private.
The W05 branch has a narrow push CI trigger so MySQL and MinIO jobs run without creating a PR.
The trigger can be removed when the branch is merged; main and PR triggers remain in place.
