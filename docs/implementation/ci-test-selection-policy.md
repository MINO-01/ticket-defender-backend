# CI test selection policy

`Backend CI` always runs the Java 21 build and the exact core JUnit skip audit. It does not apply a workflow-level path filter, so the required checks never remain pending because a workflow was skipped.

## PR selection

`ci_test_plan.py` reads the complete PR file set. It selects an ordered MySQL suite and, when required, the MinIO suite.

| Change class | MySQL | MinIO | Notes |
| --- | --- | --- | --- |
| BL-01-only API, test, or migration | ordered BL-01 prefix through migration and HTTP | no | Includes every predecessor migration; the order is explicit. |
| Track 1-only change | ordered Track 1 migration and HTTP plus legacy migration | no | Retains the 1,000-row/2 MiB contract coverage. |
| Evidence, S3, Object Lock, approval, PDF | full | full | Preservation and issuance boundaries remain coupled. |
| Authentication, RBAC, tenant scope, synthetic gate | full | full | Shared security code can affect all protected routes. |
| Generic persistence/business code | full | no | A narrow owner is not assumed from a filename. |
| CI/build infrastructure or unknown path | full | full | Fail closed. |
| Documentation only | none | none | Java build and skip audit still run. |

Changed tests participate in the same classification. The classifier tests cover BL-01, MinIO, `SecurityConfig`, a historic migration, CI infrastructure, documents, and an unclassified file.

## Full regression

Pushes to `main`, the weekly scheduled run, and manual `workflow_dispatch` run the complete MySQL, MinIO, and Track 1 legacy suites. The dispatch input defaults to the full suite. A branch-protection rule should require `ci-required` in addition to the existing `java21-build`, `mysql8-integration`, `mysql8-track1-legacy`, and `minio-integration` check names; repository settings were not changed by this repository commit.

The final `ci-required` job verifies that every suite selected by the plan succeeded and that every unselected suite was intentionally skipped. It cannot turn a skipped mandatory suite into a pass.

## Caching and result integrity

Gradle's dependency/compilation cache is retained. `Test` outputs are explicitly non-cacheable and never up-to-date. Before each selected integration test, `run_ci_suite.sh` deletes the JUnit XML directory, executes exactly one test selector, and invokes `check_junit_results.py`. The checker rejects failed tests, unexpected core skips, and every integration skip or count/class mismatch. This separates safe build caching from unsafe reuse of old test results.

The ordered migration commands deliberately remain separate: combining selectors would not guarantee their order or preserve migration-state dependencies. Removing `--rerun-tasks --no-daemon` permits a single Gradle daemon to reuse compilation work within each job without allowing test-result reuse.
