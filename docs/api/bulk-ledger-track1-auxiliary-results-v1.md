# BL-07 auxiliary administrator API

All endpoints require the existing administrator authentication, tenant membership, event grant and one of the existing BL-07 read roles. A job, performance, or child result outside the authenticated event scope returns the same `404 RESOURCE_NOT_FOUND`; an in-scope caller without a role receives `403`. Responses set `Cache-Control: no-store`.

| Method | Path |
| --- | --- |
| GET | `/api/v1/admin/events/{eventId}/ledger-track1/performances/{performanceId}/bulk-analysis-jobs/{jobId}/shared-signal-observations?page&pageSize` |
| GET | `/api/v1/admin/events/{eventId}/ledger-track1/performances/{performanceId}/bulk-analysis-jobs/{jobId}/purchase-limit-evaluations?page&pageSize` |
| GET | `/api/v1/admin/events/{eventId}/ledger-track1/performances/{performanceId}/bulk-analysis-jobs/{jobId}/purchase-limit-findings?page&pageSize` |

`pageSize` is 1–100. The job projection includes fixed ledger version plus policy verification, quantity status, reason, policy ID/revision/version. Observation pages expose Data's aggregate signal evidence only. Findings expose job-scoped opaque account/reservation identifiers, quantities, policy version, and reservation quantity evidence; raw account/reservation HMAC tokens, artifact keys, bucket data, and secrets are never returned.

`EVALUATED` findings are investigation candidates, not fraud confirmation or a trigger for enforcement, cancellation, reporting, or case creation. A job still collecting any auxiliary page remains non-complete; an unavailable dependency or contract error is visible through the existing job failure/blocking fields rather than as an empty list.
