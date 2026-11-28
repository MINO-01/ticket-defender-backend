# exp3 resource retention approval v1

This additive API records the tenant administrator's approval of an external source retention policy for `refund_account_hash`. It does not change exp2 scoring or enable exp3 by itself. Apply `bulk-ledger-exp3-resource-retention-migration.sql` before deploying code that uses this API.

## Access and routes

- `POST /api/v1/admin/events/{eventId}/exp3-resource-retention-policies` creates an immutable approval. Only a `TENANT_ADMIN` for the event may call it.
- `GET /api/v1/admin/events/{eventId}/exp3-resource-retention-policies` lists approvals in that tenant and event.
- Responses set `Cache-Control: no-store`.

The service derives tenant and approver membership from authenticated event access. Request fields cannot choose the tenant, event, approver or resource type. The resource type is fixed to `refund_account_hash`. Versions cannot be reused within the event and supplier; effective periods cannot overlap for the same scope. A later source policy needs a new version and a non-overlapping effective period.

## Create body

```json
{
  "supplierNamespace": "SUPPLIER_CODE",
  "policyVersion": "provider-policy-v2",
  "retentionDays": 30,
  "effectiveFrom": "2026-11-01T00:00:00Z",
  "effectiveUntil": null,
  "policySource": "Synthetic provider policy reference",
  "verificationBasis": "Tenant administrator verified source version and validity"
}
```

`retentionDays` must be positive. `effectiveUntil`, when present, must be after `effectiveFrom`. The immutable approval record stores its UUID, scope, policy version, retention duration, source, verification basis, approving membership ID and approval time. These values record who approved which source policy; they are not independent proof that the provider follows it.

## Analysis transfer

For each active ticket, Backend sends `refundAccountObservedAt` and the matching approved policy ID, version, retention duration, effective period and approval time only when a policy for the same tenant, event and supplier is effective at `analysisAsOf`. It sends no raw refund account value. Data requires the observation and analysis times to fall inside the policy validity and the observation to remain within `retentionDays` of `analysisAsOf`. Phone and address resource types remain ineligible for exp3 E2 until a separate approved retention basis exists.

The migration is additive and is not applied automatically by the application.
