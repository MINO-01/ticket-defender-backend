# BL-01 bulk-ledger registration boundary

Status: **implemented only for registration, read, code-only error read, and cancellation of a `CREATED` job.**

BL-01 is separate from the existing JSON Track 1 quantity-ledger import. That endpoint retains its 1,000-row and 2 MiB limits, and its analysis algorithm and FastAPI contract are unchanged.

## Active state transitions

`CREATED -> CANCELLED` is the sole active transition. The persisted model reserves names for no worker state; it cannot create `UPLOADED`, `VALIDATING`, `STAGING`, `READY_TO_PUBLISH`, `PUBLISHING`, `PUBLISHED`, `FAILED_RETRYABLE`, or `REJECTED` jobs. No upload-session, complete-upload, retry, file-processing, analysis, or publication endpoint exists in BL-01.

## Contract and isolation

- The caller never supplies a tenant ID. Every scope event is authorized server-side and its active verified performance is checked.
- Registration and cancellation require `TENANT_ADMIN`; read endpoints reuse the administrator read roles and return foreign or out-of-event-scope jobs as `404`.
- The existing synthetic import gate is checked before registration. An environment switch alone cannot admit operational data: the isolated datasource, isolation ID, HMAC configuration, and server-registered synthetic tenant remain required.
- BL-01 does not read a file and does not HMAC any customer reference. Existing HMAC purpose strings, key versions, and Track 2 reservation-token compatibility are unchanged.
- A job records only declared metadata and code-only errors. It stores no account, reservation, ticket, payment, address, device, IP, raw object key, or secret.

## Idempotency

The request digest uses trimmed metadata and a sorted `(eventId, performanceId)` scope manifest. Reordering scopes is equivalent. The `Idempotency-Key` digest is unique per creating membership. A repeated key or `(tenant, sourceSystem, sourceBatchId)` returns the existing job only if the normalized request digest matches; otherwise it is `409`. MySQL unique constraints are the final concurrent-registration guard.

## BL-02 and BL-03 decisions still required

BL-02 must define private object upload, immutable object-version binding, streaming CSV rules, source-system HMAC namespaces, and whether Track 2 reservation tokens are compatible. BL-03 must define ticket/change deduplication, per-scope generation/revision heads, outbox handling, a fixed analysis-ledger version, and whether several files publish atomically. CHANGE_FEED cursor ordering and the distinct semantics of cancellation, refund, correction, and REMOVE remain unapproved.

## Isolated MySQL test naming

The existing `TD_ISSUE32_MYSQL_*` variable names are shared CI infrastructure names inherited from the Issue #32 MySQL suite; BL-01 does not connect to an Issue #32 feature database. The BL-01 MySQL tests accept only a loopback database named `td_issue32_ci` (GitHub Actions) or `td_issue32_bl01` (the disposable local container). A port is deliberately not hard-coded, so a local isolated test can avoid an occupied host port. Renaming the shared CI variables would require a coordinated repository-wide CI change and is outside BL-01.

`BulkLedgerImportLocalMySqlBootstrapTest` is an explicit local-only helper: it requires both `TD_BL01_LOCAL_BOOTSTRAP=true` and the separate `td_issue32_bl01_http` database name. It is never invoked by CI. It creates the complete current JPA schema solely in the disposable database; BL tables are then dropped and recreated by the BL-01 migration test before HTTP tests run with `ddl-auto=validate`. CI continues to use the full ordered historical migration chain.
