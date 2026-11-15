# BL-03 candidate ledger preparation

BL-03 prepares private ledger candidates from a verified `READY_TO_PUBLISH` import. It does not change a public ledger head, transition a candidate to `PUBLISHED`, emit an outbox message, or invoke Track 1/2 analysis.

## v1 and v2 input boundary

The existing `v1` CSV contract remains staging-only and retains its original headers and Track 2 reservation-reference HMAC. It has no ticket state, quantity-unit, source checkpoint, or source sequence, so BL-03 never infers those facts and never creates a candidate from v1.

`v2` adds explicit `quantity_unit`, `ticket_status`, and `source_version` to every row. `TICKET` rows must have quantity `1`; `ORDER_LINE` remains an explicit source fact. Allowed ticket statuses are `ACTIVE`, `CANCELLED`, and `REFUNDED`. `UNKNOWN` is not a candidate-ledger fact.

For a v2 full snapshot, each scope provides `scopeAsOf`, `cursorEnd`, and `sourceSequenceEnd`; this records the checkpoint from which a later feed may start. A v2 change feed provides `cursorStart`, `cursorEnd`, and an inclusive contiguous `sourceSequenceStart`/`sourceSequenceEnd` range. Each feed row also provides `source_event_id`, `source_sequence`, `source_changed_at`, and `change_action`.

## Candidate data model

`td_bulk_ledger_candidate_imports` records the preparation outcome independently from the import's staging status. `PREPARING`, `READY`, and `REJECTED` never mean published. `td_bulk_ledger_versions` stores a per-performance generation and revision, input import/source provenance, cursor checkpoint, and base-version link. `td_bulk_ledger_ticket_versions` stores immutable HMAC-only ticket facts. `td_bulk_ledger_change_events` stores source event identity, digest, and sequence.

FULL_SNAPSHOT creates a new generation with revision `0` and all of that snapshot's tickets. Snapshot candidates verify complete scope counts, explicit facts, and duplicate ticket tokens before becoming `READY`; an explicitly declared zero-row scope is valid. Previous generations and all existing analysis data remain untouched.

CHANGE_FEED can create a revision only from a row in the reserved `td_bulk_ledger_publication_heads` contract whose version is `PUBLISHED`. BL-03 contains no writer for that table, so production feeds remain blocked until the publication issue supplies the atomic head transition. The MySQL integration test inserts an isolated synthetic head only to validate application rules; it does not implement publication.

The feed cursor must equal the published base cursor, the first sequence must be the base sequence plus one, and staged rows must be contiguous through the declared end. `(tenant, performance, sourceSystem, sourceEventId)` and `(tenant, performance, sourceSystem, sourceSequence)` are unique. A repeated event or an event ID with different content is rejected; a retry of the same import returns its existing candidate rather than writing again.

Only `UPSERT` is currently an approved application action. `REMOVE`, `CANCEL`, and `REFUND` actions fail closed with `CHANGE_ACTION_POLICY_PENDING`: they are not translated into deletion, cancellation, refund, or a normal transaction. An UPSERT carries its explicit post-change ticket status and quantity. Revision rows contain only tickets changed by that feed; unchanged tickets remain in the published base version.

## Pending publication contract

The next issue must atomically set public heads only after every scope's candidate is `READY`, change candidate/version state to `PUBLISHED`, preserve cursor and generation/revision lineage, and create any outbox/analysis work. It must also define externally approved semantics for `REMOVE`, `CANCEL`, and `REFUND`. Until then, no candidate is exposed through administrator public-ledger reads or analysis inputs.
