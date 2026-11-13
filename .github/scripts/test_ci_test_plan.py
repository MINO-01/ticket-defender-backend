#!/usr/bin/env python3
import unittest

from ci_test_plan import select


class CiTestPlanTest(unittest.TestCase):
    def assertPlan(self, files, mysql, minio, legacy=False):
        plan = select(files)
        self.assertEqual(mysql, plan.mysql_suite)
        self.assertEqual(minio, plan.run_minio)
        self.assertEqual(legacy, plan.run_legacy)

    def test_existing_bl01_files_are_fail_closed(self):
        self.assertPlan(["src/main/java/a/BulkLedgerImportService.java", "docs/database/bulk-ledger-import-bl01-migration.sql"], "full", True)

    def test_bulk_upload_is_fail_closed(self):
        self.assertPlan(["src/main/java/a/BulkLedgerUploadService.java"], "full", True)

    def test_bulk_staging_migration_is_fail_closed(self):
        self.assertPlan(["docs/database/bulk-ledger-staging-migration.sql"], "full", True)

    def test_bulk_publication_is_fail_closed(self):
        self.assertPlan(["src/main/java/a/BulkLedgerPublicationService.java"], "full", True)

    def test_minio_storage(self):
        self.assertPlan(["src/main/java/a/S3EvidenceObjectStorage.java"], "full", True)

    def test_security_config(self):
        self.assertPlan(["src/main/java/a/SecurityConfig.java"], "full", True, True)

    def test_existing_migration(self):
        self.assertPlan(["docs/database/case-w07-migration.sql"], "full", False)

    def test_ci_infrastructure(self):
        self.assertPlan([".github/workflows/backend-ci.yml"], "full", True, True)

    def test_docs_only(self):
        self.assertPlan(["docs/api/example.md"], "none", False)

    def test_unknown_is_fail_closed(self):
        self.assertPlan(["tools/unclassified-input.txt"], "full", True, True)


if __name__ == "__main__":
    unittest.main()
