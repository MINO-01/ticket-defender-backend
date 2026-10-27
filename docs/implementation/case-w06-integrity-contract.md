# W06 original evidence integrity contract

Issue #38 adds a technical verification record. It does not approve a Case, extend fan report retention, copy an image, enable Object Lock, or issue a PDF. W05 snapshots and drafts stay immutable and their existing `approvalEligible=false` and `renderable=false` responses remain unchanged.

## Existing contract and decisions

W02 calculates SHA-256 over validated upload bytes and stores it in `evidence_assets`. The private intake bucket has versioning disabled because recovery deletes by object key. `object_version` contains the S3 version ID when one is returned, otherwise an ETag; old rows have no type discriminator. W05 captures HEAD size, MIME, and this opaque marker in manifest schema 1, with `source_integrity=METADATA_ONLY` or `NO_FILE`. HEAD is not a byte hash. The existing metadata account has `s3:GetObject` because S3 requires that permission for HEAD; the upload account has Put/Delete but no Get. W06 uses the metadata account for bounded GET and does not broaden the upload account. A future versioned production bucket would require a separately reviewed `s3:GetObjectVersion` read grant; the current W02 policy does not grant it.

| Byte check | Result | Decision |
| --- | --- | --- |
| HEAD and stored digest | Confirms metadata and a previously stored digest, not current bytes | Insufficient alone |
| Stream GET and recalculate SHA-256 | Confirms bytes read at that instant, with a bounded memory buffer and byte limit | Implement |
| GET by genuine S3 version ID | Binds a read to one version, if the backend returns and grants it | Use when present; a version can still be deleted |
| Copy into a preservation bucket | Could isolate the original, but changes personal-data storage and deletion duties | Do not activate without retention and legal decisions |

| Preservation option | Current choice |
| --- | --- |
| Existing W02 object and verification history | Use for technical checks, classify as unprotected or versioned but mutable |
| Separate versioned bucket | Validate with synthetic objects in integration tests only; no automatic customer-data copy |
| Separate private preservation bucket | Deferred until access, deletion, lifecycle, and retention policy is approved |
| Object Lock | Deferred; requires compatible bucket creation, versioning, provider support, retention mode and legal basis |

The existing AWS SDK for Java 2.x and JDK `MessageDigest` suffice. A MinIO-specific SDK or extra hash dependency would duplicate the adapter and introduce another version/permission interpretation without improving byte verification.

## Verification and record boundary

Only an authenticated REVIEWER with current membership and event access can request a check. The server resolves the Case, current snapshot, source reference, fan report, and EvidenceAsset before reading the server-owned key. A caller cannot supply a key, URL, digest, or tenant ID. A snapshot manifest must still bind that source and its stored asset metadata. Non-STORED, expired, cross-scope, or changed data cannot be treated as a successful check.

The byte stream is read before a DB write transaction. The adapter uses a genuine `versionId` for a versioned GET, or an ETag precondition for an unversioned GET. It compares response metadata, reads at most the configured 20 MiB ceiling plus one byte, calculates SHA-256, and closes the stream. A matching digest only proves the bytes received during this read. ETag is never called a version ID or a SHA-256. Missing object, digest mismatch, size mismatch, expiry, and storage failure remain separate outcomes. A storage failure never becomes `MATCH` or an ordinary missing object.

Before persisting the append-only result, the write transaction reloads Case and all source rows, reconstructs the stored snapshot manifest from current DB state without another HEAD, and checks the requested Case version, scope, source ownership, state, stored digest/size/marker and retention. The stored capture time is used for the manifest comparison so ordinary passage of time can be recorded distinctly as `RETENTION_EXPIRED`; current time is checked again before commit. The Case version, verification record and CaseActivity commit together. Determinate outcomes also commit an idempotency result ID in that transaction. `STORAGE_UNAVAILABLE` commits the failed verification and activity and increments the Case version, but deliberately omits the reusable idempotency result. A metadata change produces a conflict instead of recording a stale successful check. This DB recheck cannot prevent a different transaction from changing source rows after the recheck, and an unversioned object can change after GET. Neither limitation is hidden by the verification result.

The record references the scoped Case, snapshot, source, fan report and asset. It stores a digest of the server-owned object key rather than exposing the key in API responses. It records expected and observed byte digests and sizes, marker kind, method, verification status, preservation status and timestamp. Verification attempts append rows; they never rewrite an earlier record or W05 manifest. The Case idempotency namespace is `EVIDENCE_VERIFY`, separate from Case creation and W02 intake. For `MATCH`, `HASH_MISMATCH`, `SIZE_MISMATCH`, `OBJECT_MISSING` and the other determinate outcomes, the same actor, Case, key and request return the recorded result after current access checks; a changed request returns 409. After `STORAGE_UNAVAILABLE`, the client reads the latest Case version and may retry with the same key and an updated `expectedCaseVersion`, causing another storage GET. Once that retry produces a determinate result, repeating its exact request replays the stored result without another verification record or CaseActivity. A 503 caused before or during a failed database transaction may leave no committed verification, so not every 503 increments the Case version. A different key starts a new attempt. Case and activity revision changes use `@Version`; concurrent commands with the same expected version do not both commit.

## Eligibility boundary

The read-only assessment checks current Case state and revision, snapshot ID and digest, report ID/revision/content digest/template label, source scope and state, latest relevant verification records, object accessibility, and retention. File-free graph and fan-report sources are evaluated by their stored identity and current DB state; no invented file digest or version is assigned to them. The response separates technical integrity results from operational approval eligibility and supplies reason codes. An outage is `DEPENDENCY_UNAVAILABLE`, not `NO_EVIDENCE` or a fraud decision.

The W05 `draft-input-v1` label is a logical draft-input schema, not a retained rendering artifact. No approved privacy retention period, legal-hold basis, irreversible object preservation, approval workflow, or actual PDF template exists. Consequently eligibility remains `BLOCKED` even if a recent byte check succeeds. W07 must decide and implement lifecycle, object preservation, source invalidation, approval authorization, and the exact binding to a durable approved snapshot and report. Existing official PDF issuance remains HTTP 409.
