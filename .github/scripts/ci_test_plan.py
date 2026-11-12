#!/usr/bin/env python3
"""Select CI integration suites conservatively from the complete changed-file set."""
from __future__ import annotations

import argparse
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class Plan:
    mysql_suite: str
    run_minio: bool
    run_legacy: bool
    reason: str


def select(files: list[str], force_full: bool = False) -> Plan:
    paths = [path.replace("\\", "/").lower() for path in files if path.strip()]
    if force_full:
        return Plan("full", True, True, "main, scheduled, or manually requested full regression")
    if not paths:
        return Plan("full", True, True, "no changed-file evidence; fail closed")
    if all(path.startswith("docs/") and not path.startswith("docs/database/") for path in paths):
        return Plan("none", False, False, "documentation-only change")
    if any(path.startswith(".github/") or path in {"build.gradle", "settings.gradle", "gradle.properties"} for path in paths):
        return Plan("full", True, True, "CI or build infrastructure change")
    if any(token in path for path in paths for token in ("securityconfig", "/security/", "tenantpurpose", "syntheticreservationimportgate", "caseerrorhandler", "admintenantselection")):
        return Plan("full", True, True, "shared authentication, tenant, or security boundary change")
    if any(token in path for path in paths for token in ("evidence", "minio", "objectstorage", "/s3", "officialpdf", "approval")):
        return Plan("full", True, False, "evidence, Object Lock, approval, or PDF boundary change")
    if all("bulkledger" in path or "bulk-ledger" in path or "bulkladger" in path for path in paths):
        return Plan("bl01", False, False, "BL-01-only code, test, or schema change")
    if all("track1" in path or "purchaseledger" in path or "purchase-limit" in path for path in paths):
        return Plan("track1", False, True, "Track 1-only code, test, or schema change")
    if any(path.startswith("docs/database/") or "/repository/" in path or "/persistence/" in path for path in paths):
        return Plan("full", False, False, "database or persistence change without a narrow owned suite")
    if any(path.startswith("src/main/java/") or path.startswith("src/test/java/") for path in paths):
        return Plan("full", False, False, "business-code change without a narrow owned suite")
    return Plan("full", True, True, "unclassified change; fail closed")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--files", type=Path, required=True)
    parser.add_argument("--force-full", action="store_true")
    parser.add_argument("--github-output", type=Path)
    args = parser.parse_args()
    plan = select(args.files.read_text(encoding="utf-8").splitlines(), args.force_full)
    values = {
        "mysql_suite": plan.mysql_suite,
        "run_mysql": str(plan.mysql_suite != "none").lower(),
        "run_minio": str(plan.run_minio).lower(),
        "run_legacy": str(plan.run_legacy).lower(),
        "reason": plan.reason,
    }
    lines = [f"{key}={value}" for key, value in values.items()]
    print("\n".join(lines))
    if args.github_output:
        with args.github_output.open("a", encoding="utf-8") as output:
            output.write("\n".join(lines) + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
