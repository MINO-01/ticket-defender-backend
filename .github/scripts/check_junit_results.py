#!/usr/bin/env python3
"""Fail CI when a required test is skipped or the known skip set changes."""

import argparse
from collections import Counter
from pathlib import Path
import sys
import xml.etree.ElementTree as ET


CORE_SKIPS = {
    "com.ticket.defender_core.DefenderCoreApplicationTests": (
        1, "Explicitly disabled full context smoke test"
    ),
    "com.ticket.defender_core.adapter.out.pdf.PdfGeneratorAdapterTest": (
        1, "Manual PDF font and layout inspection"
    ),
    "com.ticket.defender_core.integration.Track1ConcurrentMySqlTest": (
        1, "Runs after schema migration in the isolated MySQL CI job"
    ),
    "com.ticket.defender_core.integration.Track1TenantMigrationMySqlTest": (
        1, "Runs in its own isolated legacy MySQL CI job"
    ),
    "com.ticket.defender_core.integration.Track1LiveIntegrationTest": (
        2, "Requires a live FastAPI analysis service"
    ),
    "com.ticket.defender_core.integration.FanReportMigrationMySqlTest": (
        1, "Runs in the isolated MySQL CI job"
    ),
    "com.ticket.defender_core.integration.FanReportSchemaValidateMySqlTest": (
        5, "Runs in the isolated MySQL CI job"
    ),
    "com.ticket.defender_core.integration.S3EvidenceObjectStorageMinioTest": (
        4, "Runs in the isolated MinIO and MySQL CI job"
    ),
    "com.ticket.defender_core.integration.FanReportMinioMySqlTest": (
        3, "Runs in the isolated MinIO and MySQL CI job"
    ),
    "com.ticket.defender_core.integration.CaseMigrationMySqlTest": (
        1, "Runs after W02 migration on the isolated MySQL CI schema"
    ),
    "com.ticket.defender_core.integration.CaseConcurrentMySqlTest": (
        2, "Runs after W04 migration on the isolated MySQL CI schema"
    ),
    "com.ticket.defender_core.integration.CaseW05MigrationMySqlTest": (
        1, "Runs after W04 migration on the isolated MySQL CI schema"
    ),
    "com.ticket.defender_core.integration.CaseW05SchemaValidateMySqlTest": (
        1, "Runs after W05 migration on the isolated MySQL CI schema"
    ),
    "com.ticket.defender_core.integration.CaseW05ConcurrentMySqlTest": (
        6, "Runs after W05 migration on the isolated MySQL CI schema"
    ),
    "com.ticket.defender_core.integration.CaseW05MinioMySqlTest": (
        2, "Runs with private MinIO and isolated MySQL in the integration CI job"
    ),
    "com.ticket.defender_core.integration.CaseW06MigrationMySqlTest": (
        1, "Runs after W05 migration on the isolated MySQL CI schema"
    ),
    "com.ticket.defender_core.integration.CaseW06SchemaValidateMySqlTest": (
        1, "Runs after W06 migration on the isolated MySQL CI schema"
    ),
    "com.ticket.defender_core.integration.CaseW06ConcurrentMySqlTest": (
        4, "Runs after W06 migration on the isolated MySQL CI schema"
    ),
    "com.ticket.defender_core.integration.CaseW06MinioMySqlTest": (
        1, "Runs with private MinIO and isolated MySQL in the integration CI job"
    ),
    "com.ticket.defender_core.integration.S3EvidenceIntegrityVersionedMinioTest": (
        1, "Runs against a disposable versioned MinIO bucket in the integration CI job"
    ),
}

MYSQL_SUITES = {
    "mysql-migration": (
        "com.ticket.defender_core.integration.FanReportMigrationMySqlTest", 1
    ),
    "mysql-schema": (
        "com.ticket.defender_core.integration.FanReportSchemaValidateMySqlTest", 5
    ),
    "case-migration": (
        "com.ticket.defender_core.integration.CaseMigrationMySqlTest", 1
    ),
    "case-concurrency": (
        "com.ticket.defender_core.integration.CaseConcurrentMySqlTest", 2
    ),
    "case-w05-migration": (
        "com.ticket.defender_core.integration.CaseW05MigrationMySqlTest", 1
    ),
    "case-w05-schema": (
        "com.ticket.defender_core.integration.CaseW05SchemaValidateMySqlTest", 1
    ),
    "case-w05-concurrency": (
        "com.ticket.defender_core.integration.CaseW05ConcurrentMySqlTest", 6
    ),
    "case-w05-minio": (
        "com.ticket.defender_core.integration.CaseW05MinioMySqlTest", 2
    ),
    "case-w06-migration": (
        "com.ticket.defender_core.integration.CaseW06MigrationMySqlTest", 1
    ),
    "case-w06-schema": (
        "com.ticket.defender_core.integration.CaseW06SchemaValidateMySqlTest", 1
    ),
    "case-w06-concurrency": (
        "com.ticket.defender_core.integration.CaseW06ConcurrentMySqlTest", 4
    ),
    "case-w06-minio-mysql": (
        "com.ticket.defender_core.integration.CaseW06MinioMySqlTest", 1
    ),
    "case-w06-versioned-minio": (
        "com.ticket.defender_core.integration.S3EvidenceIntegrityVersionedMinioTest", 1
    ),
    "track1-concurrency": (
        "com.ticket.defender_core.integration.Track1ConcurrentMySqlTest", 1
    ),
    "track1-migration": (
        "com.ticket.defender_core.integration.Track1TenantMigrationMySqlTest", 1
    ),
    "minio-storage": (
        "com.ticket.defender_core.integration.S3EvidenceObjectStorageMinioTest", 4
    ),
    "minio-mysql": (
        "com.ticket.defender_core.integration.FanReportMinioMySqlTest", 3
    ),
}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("profile", choices=("core", *MYSQL_SUITES))
    parser.add_argument(
        "--results", type=Path, default=Path("build/test-results/test")
    )
    args = parser.parse_args()

    files = sorted(args.results.glob("TEST-*.xml"))
    if not files:
        print(f"No JUnit XML results found in {args.results}", file=sys.stderr)
        return 1

    cases = []
    for file in files:
        root = ET.parse(file).getroot()
        cases.extend(root.findall("testcase"))

    failures = [
        case for case in cases
        if case.find("failure") is not None or case.find("error") is not None
    ]
    skipped = [case for case in cases if case.find("skipped") is not None]
    passed = len(cases) - len(failures) - len(skipped)
    print(
        f"JUnit {args.profile}: total={len(cases)} passed={passed} "
        f"failed={len(failures)} skipped={len(skipped)}"
    )
    for case in skipped:
        print(f"SKIP {case.get('classname')}.{case.get('name')}")

    if failures:
        print("JUnit failures detected", file=sys.stderr)
        return 1

    if args.profile == "core":
        actual = Counter(case.get("classname") for case in skipped)
        expected = Counter({name: count for name, (count, _) in CORE_SKIPS.items()})
        for name, (count, reason) in CORE_SKIPS.items():
            print(f"EXPECTED SKIP {count} {name}: {reason}")
        if actual != expected or passed == 0:
            print(f"Unexpected skip set: {actual - expected}; missing: {expected - actual}",
                  file=sys.stderr)
            return 1
    else:
        expected_class, expected_count = MYSQL_SUITES[args.profile]
        if skipped or len(cases) != expected_count or any(
            case.get("classname") != expected_class for case in cases
        ):
            print("Required integration tests did not all run", file=sys.stderr)
            return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
