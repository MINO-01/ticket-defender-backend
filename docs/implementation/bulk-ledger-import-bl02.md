# BL-02 private bulk-ledger CSV staging

Status: implemented behind two fail-closed controls: the existing `SyntheticReservationImportGate` and an explicitly configured private versioned S3-compatible bucket. The feature accepts only disposable synthetic tenant data. It does not publish an immutable ledger version, call Track 1/2, issue a sanction, or generate a PDF.

## Object boundary

`upload-session` creates a server-derived `bulk-ledger-private/{importId}/upload.csv` key and returns a short-lived PUT capability with `Cache-Control: no-store`. Neither an object key nor a URL occurs in the job, error listing, or ordinary audit view. `complete-upload` heads and reads the object, requires a non-null S3 `versionId`, recomputes SHA-256 while streaming, and compares both byte count and declared digest. ETag is not accepted as a digest. Later parsing reads exactly that verified version; a changed current object cannot substitute it.

The separately configured bucket must be private and versioning-enabled. Configuration is intentionally disabled by default. A non-loopback endpoint must be HTTPS. Bucket lifecycle, retention and personal-data authority remain operational policy decisions; no automatic production activation is supplied.

## CSV contract

Input is UTF-8; one UTF-8 BOM is accepted. Decoder errors, duplicate/unexpected/missing headers, bad quotes, fields over 512 characters, and records over 8 KiB are rejected with codes only. RFC4180 quotation, embedded commas, and embedded line endings are parsed by Apache Commons CSV without loading the file into a list.

`FULL_SNAPSHOT` headers are `event_id,performance_id,reservation_reference,ticket_reference,account_reference,purchased_at,ticket_quantity`.
`CHANGE_FEED` additionally requires `source_event_id,source_changed_at,change_action`; only structural actions `UPSERT`, `REMOVE`, `CANCEL`, and `REFUND` are accepted. BL-02 does **not** apply those actions. Every row's scope and each scope row count must equal the registered manifest, and total rows must equal the declared count.

## HMAC and staging

The reservation reference uses the existing `TRACK2_RESERVATION_REFERENCE` purpose, key version, and canonicalization so Track 2 matching remains compatible. Ticket and account fields use separate BL-02 purposes. `sourceSystem` is deliberately not added to HMAC input: that namespace policy is still PENDING and a compatibility-affecting token is not fabricated. Raw references and master keys are never inserted, logged, or exposed.

Rows stream into fixed JDBC batches (default 1,000; configuration constrained to 1..2,000) with a short transaction per batch. `td_bulk_import_staging_rows` is private and separate from the published ledger. `(import_id, source_row_no)` makes retries idempotent. A validation rejection can leave private staging rows, but its job is `REJECTED` and it cannot be published.

## State and recovery

`CREATED -> UPLOAD_PENDING -> UPLOADED -> VALIDATING -> STAGING -> READY_TO_PUBLISH` is the successful path. `FAILED_RETRYABLE` is used for object-store/JDBC/system failures; `REJECTED` is for CSV validation; `CANCELLED` is terminal. `READY_TO_PUBLISH` is explicitly not published. Workers acquire a DB version/fence token and lease; a stale lease is reclaimed with a higher fence. Completion of an old fence fails in the domain model. No long database transaction covers S3 reads or CSV parsing.

## BL-03 hand-off and pending policy

BL-03 must define change-event application, cancellation/refund/REMOVE business meaning, source-system namespace policy, version publication/outbox, retained staging lifecycle, and an independently approved legal/contractual basis for any non-synthetic data. Production private-object retention and key rotation also require separate operational approval.
