# Bulk Ledger Track 1 / Data job contract

`ticket-defender.bulk-ledger-track1.bulk.enabled=false` is the default. When explicitly enabled for synthetic, isolated operation, Spring fixes one published ledger version, writes UTF-8 `track1-limit-ndjson-v1` to a private versioned S3 object, and registers its immutable manifest with Data `POST /api/v2/clusters/jobs`.

The manifest uses the real S3 VersionId, SHA-256, count, ledger generation/revision, source version/as-of, `TRACK1_CONNECTION` purpose and one HMAC key version. Missing/mixed lineage, source, signal purpose/key version, or policy-bundle version leaves the job `BLOCKED`; no invented policy or netting state is sent. `CHANGE_FEED` remains `NOT_CONFIRMED` for cancellation handling.

Spring owns `PREPARING`, `REGISTERING`, `QUEUED`, `RUNNING`, `COLLECTING`, `COMPLETED`, `RETRYABLE`, `FAILED`, and `BLOCKED`; Data's state is stored separately. HTTP/S3 calls run outside transactions. Result cursor checkpoints and job fences make page writes repeatable. Completion requires every cluster and member page. Data's `sharedSignalObservationsStatus` and `purchaseLimitEvaluationsStatus` are persisted as `NOT_IMPLEMENTED`, never interpreted as zero findings.

The only Data-specific configuration is the existing internal Track 1 key plus `ticket-defender.bulk-ledger-track1.bulk.policy-bundle-version`, an operator supplied, real policy snapshot/bundle identifier. Without it the Data schema's mandatory `policyVersion` cannot honestly be populated, so registration is blocked. Existing upload storage must be explicitly enabled and versioned.
