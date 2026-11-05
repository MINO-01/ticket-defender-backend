# Track 2 candidate Case-source approval gate

`LISTING_CANDIDATE_MATCH` is an immutable investigative source, never an approved fraud finding. Snapshot
capture records its immutable run, analysis, evidence, performance-link, and reservation-source bindings and
rechecks their current applicability.

The approval-basis policy for candidate executions is not approved in this issue. Consequently W08 approval
validation and any W09 eligibility path fail closed with `APPROVAL_BASIS_BLOCKED` whenever a Case includes this
source type. This does not mutate a stored Snapshot, ReportVersion, or ApprovalDecision. A later policy change
must add its own contract, review criteria, and regressions before enabling approval eligibility.
