#!/usr/bin/env bash
set -euo pipefail

# Test outputs are deleted before each selected Gradle test. build.gradle makes
# Test non-cacheable/up-to-date false, so a restored Gradle build cache can only
# reuse compilation/dependencies and can never report a stale JUnit success.
run_test() {
  local profile="$1"
  local pattern="$2"
  rm -rf build/test-results/test build/reports/tests/test
  bash ./gradlew test --tests "$pattern" --build-cache
  python3 .github/scripts/check_junit_results.py "$profile"
}

migration_prefix() {
  run_test mysql-migration '*FanReportMigrationMySqlTest'
  run_test case-migration '*CaseMigrationMySqlTest'
  run_test case-w05-migration '*CaseW05MigrationMySqlTest'
  run_test case-w06-migration '*CaseW06MigrationMySqlTest'
  run_test case-w07-migration '*CaseW07MigrationMySqlTest'
  run_test case-w08a-migration '*CaseW08aMigrationMySqlTest'
  run_test case-w08b-migration '*CaseW08bMigrationMySqlTest'
  run_test case-w09-migration '*CaseW09MigrationMySqlTest'
  run_test track2-listing-migration '*Track2ListingAnalysisMigrationMySqlTest'
  run_test track2-candidate-migration '*Track2CandidatePipelineMigrationMySqlTest'
  run_test case-w10-candidate-source-migration '*CaseW10Track2CandidateSourceMigrationMySqlTest'
  run_test admin-evidence-content-migration '*AdminEvidenceContentMigrationMySqlTest'
  run_test official-pdf-synthetic-migration '*OfficialPdfSyntheticIsolationMigrationMySqlTest'
}

schema_prefix() {
  run_test mysql-schema '*FanReportSchemaValidateMySqlTest'
  run_test case-w05-schema '*CaseW05SchemaValidateMySqlTest'
}

bl01_suite() {
  migration_prefix
  schema_prefix
  run_test track1-quantity-migration '*Track1QuantityMigrationMySqlTest'
  run_test track1-quantity-http '*Track1QuantityHttpMySqlTest'
  run_test bulk-ledger-import-migration '*BulkLedgerImportMigrationMySqlTest'
  run_test bulk-ledger-import-http '*BulkLedgerImportHttpMySqlTest'
}

mysql_full() {
  bl01_suite
  run_test case-w05-concurrency '*CaseW05ConcurrentMySqlTest'
  run_test case-w06-schema '*CaseW06SchemaValidateMySqlTest'
  run_test case-w07-schema '*CaseW07SchemaValidateMySqlTest'
  run_test case-w08a-schema '*CaseW08aSchemaValidateMySqlTest'
  run_test case-w08b-schema '*CaseW08bSchemaValidateMySqlTest'
  run_test case-w09-schema '*CaseW09SchemaValidateMySqlTest'
  run_test track2-listing-schema '*Track2ListingAnalysisSchemaValidateMySqlTest'
  run_test track2-candidate-schema '*Track2CandidatePipelineSchemaValidateMySqlTest'
  run_test case-w10-candidate-source-schema '*CaseW10Track2CandidateSourceSchemaValidateMySqlTest'
  run_test track2-candidate-http '*Track2CandidatePipelineHttpMySqlTest'
  run_test track2-candidate-concurrency '*Track2CandidatePipelineConcurrentMySqlTest'
  run_test case-w06-concurrency '*CaseW06ConcurrentMySqlTest'
  run_test case-w07-concurrency '*CaseW07ConcurrentMySqlTest'
  run_test case-w08a-concurrency '*CaseW08aConcurrentMySqlTest'
  run_test case-w08b-concurrency '*CaseW08bConcurrentMySqlTest'
  run_test case-w09-concurrency '*CaseW09ConcurrentMySqlTest'
  run_test track2-listing-concurrency '*Track2ListingAnalysisConcurrentMySqlTest'
  run_test admin-fan-report-read '*AdminFanReportReadMySqlTest'
  run_test admin-event-read '*AdminEventReadMySqlTest'
  run_test case-w05-schema '*CaseW05SchemaValidateMySqlTest'
  run_test case-concurrency '*CaseConcurrentMySqlTest'
  run_test track1-concurrency '*Track1ConcurrentMySqlTest'
}

minio_full() {
  migration_prefix
  schema_prefix
  run_test case-w07-schema '*CaseW07SchemaValidateMySqlTest'
  run_test case-w08a-schema '*CaseW08aSchemaValidateMySqlTest'
  run_test case-w08b-schema '*CaseW08bSchemaValidateMySqlTest'
  run_test case-w09-schema '*CaseW09SchemaValidateMySqlTest'
  run_test track2-listing-schema '*Track2ListingAnalysisSchemaValidateMySqlTest'
  run_test minio-storage '*S3EvidenceObjectStorageMinioTest'
  run_test case-w09-minio-storage '*S3OfficialPdfStorageMinioTest'
  run_test minio-mysql '*FanReportMinioMySqlTest'
  run_test fan-report-retry-minio-mysql '*FanReportRetryContractMySqlTest'
  run_test case-w05-minio '*CaseW05MinioMySqlTest'
  run_test case-w06-minio-mysql '*CaseW06MinioMySqlTest'
  run_test case-w06-versioned-minio '*S3EvidenceIntegrityVersionedMinioTest'
  run_test case-w07-object-lock-minio '*S3EvidencePreservationObjectLockMinioTest'
  run_test case-w07-minio-mysql '*CaseW07MinioMySqlTest'
  python3 .github/scripts/check_junit_results.py case-w08a-minio-mysql
  python3 .github/scripts/check_junit_results.py case-w08b-minio-mysql
  run_test case-w09-minio-mysql '*CaseW09MinioMySqlTest'
  run_test track2-listing-minio-mysql '*Track2ListingAnalysisMinioMySqlTest'
  run_test admin-evidence-content-minio-mysql '*AdminEvidenceContentMinioMySqlTest'
}

case "${1:?suite required}" in
  bl01) bl01_suite ;;
  track1) migration_prefix; schema_prefix; run_test track1-quantity-migration '*Track1QuantityMigrationMySqlTest'; run_test track1-quantity-http '*Track1QuantityHttpMySqlTest' ;;
  full) mysql_full ;;
  minio-full) minio_full ;;
  legacy) run_test track1-migration '*Track1TenantMigrationMySqlTest' ;;
  *) echo "unknown CI suite: $1" >&2; exit 2 ;;
esac
