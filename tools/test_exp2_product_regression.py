from __future__ import annotations

import json
from pathlib import Path
import tempfile
import unittest

from tools.exp2_product_regression import SNAPSHOT_VERSION, SnapshotError, compare_snapshots


class ProductExp2RegressionTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.repository = self.root / "repository"
        self.repository.mkdir()
        self.baseline_path = self.root / "baseline.json"
        self.candidate_path = self.root / "candidate.json"
        self.report_path = self.root / "report.json"

    def tearDown(self) -> None:
        self.temporary.cleanup()

    @staticmethod
    def snapshot(accounts: list[list[str]], scores: list[str], backend: str = "a" * 40) -> dict:
        return {
            "schemaVersion": SNAPSHOT_VERSION,
            "metadata": {
                "inputCsvSha256": "1" * 64,
                "backendCommit": backend,
                "dataCommit": "b" * 40,
                "analysisScope": {
                    "eventIds": ["event-b", "event-a"],
                    "performanceIds": ["performance-b", "performance-a"],
                    "analysisStartAt": "2026-01-01T00:00:00Z",
                    "analysisEndAt": "2026-02-01T00:00:00Z",
                },
                "hmac": {"algorithm": "HMAC-SHA256", "purpose": "TRACK1_CONNECTION", "keyVersion": "dev-v1"},
                "scoreConfig": {
                    "scoreVersion": "score-v2-exp2-multi-performance-recurrence",
                    "projectionVersion": "projection-v2.2-exp2-ipwindow-softhub",
                    "highThreshold": "0.40",
                    "mediumThreshold": "0.20",
                },
                "schemaState": "synthetic development schema",
                "analysisStatus": "COMPLETED",
                "algorithmVersion": "gds-2.6.6;projection-test;score-test",
            },
            "candidates": [
                {"accountHmacs": member_set, "scoreS": score}
                for member_set, score in zip(accounts, scores, strict=True)
            ],
        }

    def write_snapshot(self, path: Path, value: dict) -> None:
        path.write_text(json.dumps(value), encoding="utf-8")

    def test_matches_same_candidates_when_order_and_run_ids_differ(self) -> None:
        accounts_a = ["a" * 64, "b" * 64]
        accounts_b = ["c" * 64, "d" * 64]
        before = self.snapshot([accounts_a, accounts_b], ["0.40", "0.2250"])
        after = self.snapshot([list(reversed(accounts_b)), list(reversed(accounts_a))], ["0.225", "0.4000"], "c" * 40)
        self.write_snapshot(self.baseline_path, before)
        self.write_snapshot(self.candidate_path, after)

        report = compare_snapshots(self.baseline_path, self.candidate_path, self.report_path, self.repository)

        self.assertEqual(report["status"], "PASS")
        self.assertEqual(report["normalizedOutputSha256Before"], report["normalizedOutputSha256After"])
        self.assertEqual(report["candidateSetsAdded"], 0)
        self.assertEqual(report["candidateSetsRemoved"], 0)
        report_text = self.report_path.read_text(encoding="utf-8")
        self.assertNotIn("a" * 64, report_text)
        self.assertNotIn("b" * 64, report_text)

    def test_reports_set_and_score_changes_without_member_identifiers(self) -> None:
        self.write_snapshot(self.baseline_path, self.snapshot([["a" * 64, "b" * 64]], ["0.40"]))
        self.write_snapshot(self.candidate_path, self.snapshot([["a" * 64, "c" * 64]], ["0.42"], "c" * 40))

        report = compare_snapshots(self.baseline_path, self.candidate_path, self.report_path, self.repository)

        self.assertEqual(report["status"], "FAIL")
        self.assertEqual(report["candidateSetsAdded"], 1)
        self.assertEqual(report["candidateSetsRemoved"], 1)
        self.assertEqual(report["candidateSetsWithScoreChange"], 0)
        self.assertNotIn("a" * 64, self.report_path.read_text(encoding="utf-8"))

    def test_reports_score_change_when_account_set_is_unchanged(self) -> None:
        self.write_snapshot(self.baseline_path, self.snapshot([["a" * 64, "b" * 64]], ["0.40"]))
        self.write_snapshot(self.candidate_path, self.snapshot([["b" * 64, "a" * 64]], ["0.41"], "c" * 40))

        report = compare_snapshots(self.baseline_path, self.candidate_path, self.report_path, self.repository)

        self.assertEqual(report["status"], "FAIL")
        self.assertEqual(report["candidateSetsAdded"], 0)
        self.assertEqual(report["candidateSetsRemoved"], 0)
        self.assertEqual(report["candidateSetsWithScoreChange"], 1)

    def test_rejects_changed_input_or_hmac_conditions(self) -> None:
        before = self.snapshot([["a" * 64]], ["0.4"])
        after = self.snapshot([["a" * 64]], ["0.4"], "c" * 40)
        after["metadata"]["hmac"]["keyVersion"] = "dev-v2"
        self.write_snapshot(self.baseline_path, before)
        self.write_snapshot(self.candidate_path, after)

        with self.assertRaisesRegex(SnapshotError, "different input or analysis conditions"):
            compare_snapshots(self.baseline_path, self.candidate_path, self.report_path, self.repository)

    def test_rejects_proxy_or_wrong_exp2_configuration(self) -> None:
        before = self.snapshot([["a" * 64]], ["0.4"])
        after = self.snapshot([["a" * 64]], ["0.4"], "c" * 40)
        after["metadata"]["algorithmVersion"] = "offline-component-proxy"
        self.write_snapshot(self.baseline_path, before)
        self.write_snapshot(self.candidate_path, after)
        with self.assertRaisesRegex(SnapshotError, "verified GDS 2.6.6"):
            compare_snapshots(self.baseline_path, self.candidate_path, self.report_path, self.repository)

        after["metadata"]["algorithmVersion"] = "gds-2.6.6;projection-test;score-test"
        after["metadata"]["scoreConfig"]["highThreshold"] = "0.41"
        self.write_snapshot(self.candidate_path, after)
        with self.assertRaisesRegex(SnapshotError, "frozen exp2 values"):
            compare_snapshots(self.baseline_path, self.candidate_path, self.report_path, self.repository)

    def test_rejects_snapshot_inside_repository(self) -> None:
        internal_path = self.repository / "snapshot.json"
        self.write_snapshot(internal_path, self.snapshot([["a" * 64]], ["0.4"]))

        with self.assertRaisesRegex(SnapshotError, "outside the repository"):
            compare_snapshots(internal_path, self.candidate_path, self.report_path, self.repository)

    def test_rejects_duplicate_member_ids_and_candidate_sets(self) -> None:
        duplicate_account = self.snapshot([["a" * 64, "a" * 64]], ["0.4"])
        duplicate_set = self.snapshot([["a" * 64], ["a" * 64]], ["0.4", "0.5"])
        self.write_snapshot(self.baseline_path, duplicate_account)
        self.write_snapshot(self.candidate_path, self.snapshot([["b" * 64]], ["0.5"], "c" * 40))
        with self.assertRaisesRegex(SnapshotError, "must not contain duplicates"):
            compare_snapshots(self.baseline_path, self.candidate_path, self.report_path, self.repository)

        self.write_snapshot(self.baseline_path, duplicate_set)
        with self.assertRaisesRegex(SnapshotError, "duplicate candidate account set"):
            compare_snapshots(self.baseline_path, self.candidate_path, self.report_path, self.repository)


if __name__ == "__main__":
    unittest.main()
