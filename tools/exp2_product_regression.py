"""Compare product-level exp2 snapshots without printing member identifiers.

Snapshots are exported from completed synthetic Backend runs. They contain only
account-reference HMACs, per-candidate S values, and run metadata. Keep snapshot
files outside the repository. Candidate IDs are intentionally ignored because
they may be run-specific; each candidate is matched by its sorted account-HMAC
set.

Example input shape (use synthetic HMAC values only)::

    {
      "schemaVersion": "ticket-defender-exp2-product-snapshot-v1",
      "metadata": {
        "inputCsvSha256": "<64 hex characters>",
        "backendCommit": "<commit>", "dataCommit": "<commit>",
        "analysisScope": {"eventIds": [], "performanceIds": [],
                          "analysisStartAt": "...", "analysisEndAt": "..."},
        "hmac": {"algorithm": "HMAC-SHA256", "purpose": "...",
                 "keyVersion": "..."},
        "analysisStatus": "COMPLETED",
        "algorithmVersion": "gds-2.6.6;...",
        "scoreConfig": {"scoreVersion": "score-v2-exp2-multi-performance-recurrence",
                        "projectionVersion": "...", "highThreshold": "0.40",
                        "mediumThreshold": "0.20"},
        "schemaState": "exp2 baseline schema"
      },
      "candidates": [{"accountHmacs": ["<64 hex characters>"], "scoreS": "0.4"}]
    }
"""

from __future__ import annotations

import argparse
from decimal import Decimal, InvalidOperation
import hashlib
import json
from pathlib import Path
import re
import sys
from typing import Any


SNAPSHOT_VERSION = "ticket-defender-exp2-product-snapshot-v1"
EXP2_SCORE_VERSION = "score-v2-exp2-multi-performance-recurrence"
EXP2_PROJECTION_VERSION = "projection-v2.2-exp2-ipwindow-softhub"
_SHA256 = re.compile(r"^[0-9a-fA-F]{64}$")
_COMMIT = re.compile(r"^[0-9a-fA-F]{7,40}$")


class SnapshotError(ValueError):
    """A snapshot is incomplete, unsafe, or not comparable."""


def _canonical(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), default=str)


def _inside(path: Path, root: Path) -> bool:
    return path == root or root in path.parents


def _external_file(path: Path, repository_root: Path, field: str) -> Path:
    resolved = path.expanduser().resolve()
    if _inside(resolved, repository_root):
        raise SnapshotError(f"{field} must be stored outside the repository")
    return resolved


def _read_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"), parse_float=Decimal)
    except (OSError, json.JSONDecodeError) as error:
        raise SnapshotError(f"cannot read {path.name} as UTF-8 JSON") from error
    if not isinstance(value, dict):
        raise SnapshotError(f"{path.name} must contain a JSON object")
    return value


def _require_text(mapping: dict[str, Any], name: str, where: str) -> str:
    value = mapping.get(name)
    if not isinstance(value, str) or not value.strip():
        raise SnapshotError(f"{where}.{name} is required")
    return value.strip()


def _canonical_scope(value: Any) -> str:
    if not isinstance(value, dict) or not value:
        raise SnapshotError("metadata.analysisScope is required")
    allowed = {"eventIds", "performanceIds", "ledgerVersionIds", "analysisStartAt", "analysisEndAt", "analysisAsOf"}
    if not set(value).issubset(allowed):
        raise SnapshotError("metadata.analysisScope contains unsupported fields")
    scope = dict(value)
    for key in ("eventIds", "performanceIds", "ledgerVersionIds"):
        if key in scope:
            ids = scope[key]
            if not isinstance(ids, list) or any(not isinstance(item, str) or not item for item in ids):
                raise SnapshotError(f"metadata.analysisScope.{key} must be a string list")
            if len(ids) != len(set(ids)):
                raise SnapshotError(f"metadata.analysisScope.{key} must not contain duplicates")
            scope[key] = sorted(ids)
    return _canonical(scope)


def _score_config(value: Any) -> str:
    if not isinstance(value, dict):
        raise SnapshotError("metadata.scoreConfig is required")
    allowed = {"scoreVersion", "projectionVersion", "highThreshold", "mediumThreshold"}
    if set(value) != allowed:
        raise SnapshotError("metadata.scoreConfig must contain only the frozen exp2 score fields")
    if value.get("scoreVersion") != EXP2_SCORE_VERSION:
        raise SnapshotError("snapshot scoreVersion is not the frozen exp2 version")
    if value.get("projectionVersion") != EXP2_PROJECTION_VERSION:
        raise SnapshotError("snapshot projectionVersion is not the frozen exp2 projection")
    try:
        high = Decimal(str(value.get("highThreshold")))
        medium = Decimal(str(value.get("mediumThreshold")))
    except (InvalidOperation, ValueError):
        raise SnapshotError("metadata.scoreConfig thresholds must be decimal numbers") from None
    if high != Decimal("0.40") or medium != Decimal("0.20"):
        raise SnapshotError("snapshot thresholds do not match the frozen exp2 values")
    normalized = {
        "scoreVersion": value["scoreVersion"],
        "projectionVersion": value["projectionVersion"],
        "highThreshold": format(high.normalize(), "f"),
        "mediumThreshold": format(medium.normalize(), "f"),
    }
    return _canonical(normalized)


def _hmac_conditions(value: Any) -> str:
    if not isinstance(value, dict):
        raise SnapshotError("metadata.hmac is required")
    allowed = {"algorithm", "purpose", "keyVersion"}
    if set(value) != allowed:
        raise SnapshotError("metadata.hmac must contain algorithm, purpose, and keyVersion only")
    if value.get("algorithm") != "HMAC-SHA256":
        raise SnapshotError("metadata.hmac.algorithm must be HMAC-SHA256")
    for key in allowed:
        _require_text(value, key, "metadata.hmac")
    return _canonical(value)


def _normalize_candidate_rows(value: Any) -> list[dict[str, Any]]:
    if not isinstance(value, list):
        raise SnapshotError("candidates must be a JSON list")
    normalized: list[dict[str, Any]] = []
    seen_sets: set[tuple[str, ...]] = set()
    for index, candidate in enumerate(value):
        label = f"candidates[{index}]"
        if not isinstance(candidate, dict):
            raise SnapshotError(f"{label} must be an object")
        if set(candidate) != {"accountHmacs", "scoreS"}:
            raise SnapshotError(f"{label} contains unsupported fields; do not include raw references")
        accounts = candidate.get("accountHmacs")
        if not isinstance(accounts, list) or not accounts:
            raise SnapshotError(f"{label}.accountHmacs must be a non-empty list")
        if any(not isinstance(account, str) or not _SHA256.fullmatch(account) for account in accounts):
            raise SnapshotError(f"{label}.accountHmacs must contain only 64-character hexadecimal HMACs")
        account_set = tuple(sorted(account.lower() for account in accounts))
        if len(account_set) != len(set(account_set)):
            raise SnapshotError(f"{label}.accountHmacs must not contain duplicates")
        if account_set in seen_sets:
            raise SnapshotError("snapshot contains a duplicate candidate account set")
        seen_sets.add(account_set)
        try:
            score = Decimal(str(candidate.get("scoreS")))
        except (InvalidOperation, ValueError):
            raise SnapshotError(f"{label}.scoreS must be a decimal number") from None
        if not score.is_finite() or score < 0 or score > 1:
            raise SnapshotError(f"{label}.scoreS must be between 0 and 1")
        score_text = format(score.normalize(), "f")
        normalized.append({"accountHmacs": list(account_set), "scoreS": score_text})
    normalized.sort(key=lambda row: (tuple(row["accountHmacs"]), row["scoreS"]))
    return normalized


def _validated_snapshot(path: Path) -> dict[str, Any]:
    snapshot = _read_json(path)
    if set(snapshot) != {"schemaVersion", "metadata", "candidates"}:
        raise SnapshotError(f"{path.name} contains unsupported top-level fields")
    if snapshot.get("schemaVersion") != SNAPSHOT_VERSION:
        raise SnapshotError(f"{path.name} has an unsupported schemaVersion")
    metadata = snapshot.get("metadata")
    if not isinstance(metadata, dict):
        raise SnapshotError("metadata is required")
    if set(metadata) != {
        "inputCsvSha256", "backendCommit", "dataCommit", "analysisScope", "hmac", "scoreConfig",
        "schemaState", "analysisStatus", "algorithmVersion"
    }:
        raise SnapshotError("metadata contains unsupported fields")
    csv_sha = _require_text(metadata, "inputCsvSha256", "metadata")
    if not _SHA256.fullmatch(csv_sha):
        raise SnapshotError("metadata.inputCsvSha256 must be a SHA-256 hex digest")
    for key in ("backendCommit", "dataCommit"):
        commit = _require_text(metadata, key, "metadata")
        if not _COMMIT.fullmatch(commit):
            raise SnapshotError(f"metadata.{key} must be a Git commit hash")
    schema_state = _require_text(metadata, "schemaState", "metadata")
    if metadata.get("analysisStatus") != "COMPLETED":
        raise SnapshotError("product run must have COMPLETED status")
    algorithm_version = _require_text(metadata, "algorithmVersion", "metadata")
    if not algorithm_version.startswith("gds-2.6.6;"):
        raise SnapshotError("product run must report the verified GDS 2.6.6 algorithm version")
    return {
        "metadata": {
            "inputCsvSha256": csv_sha.lower(),
            "analysisScope": _canonical_scope(metadata.get("analysisScope")),
            "hmac": _hmac_conditions(metadata.get("hmac")),
            "scoreConfig": _score_config(metadata.get("scoreConfig")),
            "backendCommit": metadata["backendCommit"].strip(),
            "dataCommit": metadata["dataCommit"].strip(),
            "schemaState": schema_state,
            "analysisStatus": metadata["analysisStatus"],
            "algorithmVersion": algorithm_version,
        },
        "candidates": _normalize_candidate_rows(snapshot.get("candidates")),
    }


def _result_hash(candidates: list[dict[str, Any]]) -> str:
    payload = _canonical(candidates).encode("utf-8")
    return hashlib.sha256(payload).hexdigest()


def _compare_candidates(before: list[dict[str, Any]], after: list[dict[str, Any]]) -> dict[str, Any]:
    before_by_set = {tuple(row["accountHmacs"]): row["scoreS"] for row in before}
    after_by_set = {tuple(row["accountHmacs"]): row["scoreS"] for row in after}
    common = before_by_set.keys() & after_by_set.keys()
    return {
        "candidateSetsBefore": len(before),
        "candidateSetsAfter": len(after),
        "candidateSetsAdded": len(after_by_set.keys() - before_by_set.keys()),
        "candidateSetsRemoved": len(before_by_set.keys() - after_by_set.keys()),
        "candidateSetsWithScoreChange": sum(before_by_set[key] != after_by_set[key] for key in common),
    }


def compare_snapshots(before_path: Path, after_path: Path, report_path: Path, repository_root: Path) -> dict[str, Any]:
    """Validate and compare two externally stored, pseudonymous product snapshots."""
    root = repository_root.resolve()
    before_file = _external_file(before_path, root, "baseline snapshot")
    after_file = _external_file(after_path, root, "candidate snapshot")
    report_file = _external_file(report_path, root, "report")
    before = _validated_snapshot(before_file)
    after = _validated_snapshot(after_file)
    before_meta, after_meta = before["metadata"], after["metadata"]
    comparable_fields = ("inputCsvSha256", "analysisScope", "hmac", "scoreConfig", "algorithmVersion")
    changed_conditions = [field for field in comparable_fields if before_meta[field] != after_meta[field]]
    if changed_conditions:
        raise SnapshotError("snapshots use different input or analysis conditions: " + ", ".join(changed_conditions))

    before_hash = _result_hash(before["candidates"])
    after_hash = _result_hash(after["candidates"])
    counts = _compare_candidates(before["candidates"], after["candidates"])
    report = {
        "status": "PASS" if before_hash == after_hash else "FAIL",
        "comparison": "exact candidate account-set and S-value equality",
        "inputCsvSha256": before_meta["inputCsvSha256"],
        "analysisScopeSha256": hashlib.sha256(before_meta["analysisScope"].encode("utf-8")).hexdigest(),
        "hmacConditionsSha256": hashlib.sha256(before_meta["hmac"].encode("utf-8")).hexdigest(),
        "scoreConfigSha256": hashlib.sha256(before_meta["scoreConfig"].encode("utf-8")).hexdigest(),
        "schemaStateBefore": before_meta["schemaState"],
        "schemaStateAfter": after_meta["schemaState"],
        "algorithmVersion": before_meta["algorithmVersion"],
        "backendCommitBefore": before_meta["backendCommit"],
        "backendCommitAfter": after_meta["backendCommit"],
        "dataCommitBefore": before_meta["dataCommit"],
        "dataCommitAfter": after_meta["dataCommit"],
        "normalizedOutputSha256Before": before_hash,
        "normalizedOutputSha256After": after_hash,
        **counts,
    }
    report_file.parent.mkdir(parents=True, exist_ok=True)
    report_file.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return report


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Compare synthetic product-level exp2 regression snapshots")
    parser.add_argument("--baseline", required=True, type=Path, help="External baseline snapshot JSON")
    parser.add_argument("--candidate", required=True, type=Path, help="External post-change snapshot JSON")
    parser.add_argument("--report", required=True, type=Path, help="External summary report JSON")
    parser.add_argument("--repository-root", type=Path, default=Path(__file__).resolve().parents[1])
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        report = compare_snapshots(args.baseline, args.candidate, args.report, args.repository_root)
    except SnapshotError as error:
        print(f"INVALID: {error}", file=sys.stderr)
        return 2
    print(
        " ".join(
            (
                f"status={report['status']}",
                f"sha256_before={report['normalizedOutputSha256Before']}",
                f"sha256_after={report['normalizedOutputSha256After']}",
                f"candidate_sets_before={report['candidateSetsBefore']}",
                f"candidate_sets_after={report['candidateSetsAfter']}",
                f"added={report['candidateSetsAdded']}",
                f"removed={report['candidateSetsRemoved']}",
                f"score_changes={report['candidateSetsWithScoreChange']}",
                f"report={args.report}",
            )
        )
    )
    return 0 if report["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
