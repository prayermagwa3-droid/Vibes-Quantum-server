#!/usr/bin/env python3
"""Offline, deterministic benchmark runner for Vibes Quantum answer snapshots.

Input files are JSON only; the runner makes no network calls and never invokes a model.
The candidate and optional baseline contain answers keyed by dataset case ID. It evaluates
both against the *same deterministic sample* and emits a machine-readable release-gate report.
Lexical metrics measure reference alignment only; they are not a factuality certificate.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
import sys
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

MAX_DATASET_CASES = 100_000
MAX_TEXT_CHARS = 20_000
STOP_WORDS = {
    "the", "and", "for", "with", "from", "that", "this", "you", "your", "are", "was",
    "were", "what", "when", "where", "how", "why", "does", "can", "will", "have", "has",
    "had", "but", "not", "into", "than", "then", "their", "there", "they", "them", "its",
}


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def load_json(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise ValueError(f"Cannot read valid JSON from {path.name}: {type(exc).__name__}") from exc


def normalize(text: str) -> str:
    return " ".join(text.lower().split()).rstrip(".!? ")


# Unicode-aware word matching using only Python's standard library.
def word_tokens(text: str) -> set[str]:
    return {token for token in re.findall(r"[^\W_]+", text.casefold(), flags=re.UNICODE) if len(token) >= 2 and token not in STOP_WORDS}


def stable_sample(cases: list[dict[str, Any]], dataset_version: str, sample_size: int) -> list[dict[str, Any]]:
    """Deterministic, category-balanced round-robin sample; input order cannot bias results."""
    require(1 <= sample_size <= 10_000, "sample_size must be between 1 and 10000")
    buckets: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for case in cases:
        key = hashlib.sha256(f"{dataset_version}:{case['id']}".encode()).hexdigest()
        copy = dict(case)
        copy["_sample_key"] = key
        buckets[case["category"]].append(copy)
    for bucket in buckets.values():
        bucket.sort(key=lambda item: item["_sample_key"])
    picked: list[dict[str, Any]] = []
    category_names = sorted(buckets)
    while len(picked) < min(sample_size, len(cases)):
        advanced = False
        for category in category_names:
            if buckets[category] and len(picked) < min(sample_size, len(cases)):
                picked.append(buckets[category].pop(0))
                advanced = True
        if not advanced:
            break
    for item in picked:
        item.pop("_sample_key", None)
    return picked


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def validate_dataset(dataset: Any) -> tuple[str, list[dict[str, Any]]]:
    require(isinstance(dataset, dict), "Dataset must be a JSON object")
    version = dataset.get("dataset_version")
    cases = dataset.get("cases")
    require(isinstance(version, str) and 1 <= len(version) <= 128, "dataset_version is required")
    require(isinstance(cases, list) and 1 <= len(cases) <= MAX_DATASET_CASES, "cases must contain 1..100000 entries")
    ids: set[str] = set()
    normalized: list[dict[str, Any]] = []
    for index, case in enumerate(cases):
        require(isinstance(case, dict), f"case {index} must be an object")
        case_id, category, prompt, reference = (case.get(key) for key in ("id", "category", "prompt", "reference"))
        require(isinstance(case_id, str) and re.fullmatch(r"[A-Za-z0-9_.:-]{1,128}", case_id), f"case {index} has invalid id")
        require(case_id not in ids, f"duplicate case id: {case_id}")
        ids.add(case_id)
        require(isinstance(category, str) and 1 <= len(category) <= 80 and not any(ord(ch) < 32 for ch in category), f"case {case_id} has invalid category")
        require(isinstance(prompt, str) and 1 <= len(prompt) <= MAX_TEXT_CHARS, f"case {case_id} has invalid prompt")
        require(isinstance(reference, str) and 1 <= len(reference) <= MAX_TEXT_CHARS, f"case {case_id} has invalid reference")
        require(isinstance(case.get("critical", False), bool), f"case {case_id} critical must be boolean")
        normalized.append({"id": case_id, "category": category, "prompt": prompt, "reference": reference, "critical": case.get("critical", False)})
    return version, normalized


def validate_run(run: Any, dataset_version: str, label: str, known_ids: set[str]) -> dict[str, Any]:
    require(isinstance(run, dict), f"{label} run must be a JSON object")
    version = run.get("dataset_version")
    require(version == dataset_version, f"{label} dataset_version must match {dataset_version}")
    answers = run.get("answers")
    require(isinstance(answers, dict), f"{label} answers must be an object keyed by case ID")
    require(len(answers) <= MAX_DATASET_CASES, f"{label} contains too many answers")
    clean: dict[str, str] = {}
    for key, value in answers.items():
        require(isinstance(key, str) and len(key) <= 128, f"{label} has an invalid answer ID")
        require(key in known_ids, f"{label} contains unknown answer IDs")
        require(isinstance(value, str) and len(value) <= MAX_TEXT_CHARS, f"{label} answer {key} must be a string within the size limit")
        clean[key] = value
    return {"run_id": str(run.get("run_id", label))[:128], "engine_version": str(run.get("engine_version", "unknown"))[:128], "dataset_version": version, "answers": clean}


def score_case(reference: str, answer: str) -> dict[str, float]:
    ref_norm, answer_norm = normalize(reference), normalize(answer)
    ref_tokens, actual_tokens = word_tokens(reference), word_tokens(answer)
    intersection = len(ref_tokens & actual_tokens)
    precision = intersection / len(actual_tokens) if actual_tokens else 0.0
    recall = intersection / len(ref_tokens) if ref_tokens else 0.0
    f1 = (2 * precision * recall / (precision + recall)) if precision + recall else 0.0
    return {
        "exact_match": 1.0 if ref_norm and answer_norm and ref_norm == answer_norm else 0.0,
        "reference_recall": recall,
        "answer_precision": precision,
        "lexical_f1": f1,
        "non_empty": 1.0 if answer.strip() else 0.0,
    }


def evaluate(run: dict[str, Any], cases: list[dict[str, Any]]) -> dict[str, Any]:
    metric_names = ("exact_match", "reference_recall", "answer_precision", "lexical_f1", "non_empty")
    per_case: list[dict[str, Any]] = []
    metric_values: dict[str, list[float]] = {name: [] for name in metric_names}
    category_values: dict[str, list[float]] = defaultdict(list)
    critical_failures: list[str] = []
    missing_answers: list[str] = []
    for case in cases:
        answer = run["answers"].get(case["id"], "")
        metrics = score_case(case["reference"], answer)
        for name, value in metrics.items():
            metric_values[name].append(value)
        category_values[case["category"]].append(metrics["exact_match"])
        if not answer.strip():
            missing_answers.append(case["id"])
        if case["critical"] and metrics["exact_match"] < 1.0:
            critical_failures.append(case["id"])
        per_case.append({"id": case["id"], "category": case["category"], "critical": case["critical"], "metrics": metrics, "answer_present": bool(answer.strip())})
    averages = {name: (sum(values) / len(values) if values else 0.0) for name, values in metric_values.items()}
    return {
        "run_id": run["run_id"],
        "engine_version": run["engine_version"],
        "evaluated": len(cases),
        "metrics": averages,
        "category_exact_match": {category: sum(values) / len(values) for category, values in sorted(category_values.items())},
        "critical_failures": critical_failures,
        "missing_answers": missing_answers,
        "per_case": per_case,
    }


def run_benchmark(dataset: Any, candidate_obj: Any, baseline_obj: Any | None = None, *, sample_size: int = 100, max_regression: float = 0.02, min_exact_match: float = 0.0, max_critical_failures: int = 0, require_complete_answers: bool = True) -> dict[str, Any]:
    require(isinstance(sample_size, int) and 1 <= sample_size <= 10_000, "sample_size must be between 1 and 10000")
    require(math.isfinite(max_regression) and 0.0 <= max_regression <= 1.0, "max_regression must be between 0 and 1")
    require(math.isfinite(min_exact_match) and 0.0 <= min_exact_match <= 1.0, "min_exact_match must be between 0 and 1")
    require(isinstance(max_critical_failures, int) and 0 <= max_critical_failures <= 100_000, "max_critical_failures is invalid")
    dataset_version, all_cases = validate_dataset(dataset)
    known_ids = {case["id"] for case in all_cases}
    candidate = validate_run(candidate_obj, dataset_version, "candidate", known_ids)
    baseline = validate_run(baseline_obj, dataset_version, "baseline", known_ids) if baseline_obj is not None else None
    sampled = stable_sample(all_cases, dataset_version, min(sample_size, len(all_cases)))
    candidate_result = evaluate(candidate, sampled)
    baseline_result = evaluate(baseline, sampled) if baseline else None
    candidate_score = candidate_result["metrics"]["exact_match"]
    baseline_score = baseline_result["metrics"]["exact_match"] if baseline_result else None
    regression = max(0.0, baseline_score - candidate_score) if baseline_score is not None else 0.0
    reasons: list[str] = []
    if len(candidate_result["critical_failures"]) > max_critical_failures:
        reasons.append("Critical failure count exceeded the configured threshold")
    if require_complete_answers and candidate_result["missing_answers"]:
        reasons.append("Candidate is missing one or more sampled answers")
    if baseline_result is not None and baseline_result["missing_answers"]:
        reasons.append("Baseline has missing sampled answers and is not a complete comparison")
    if candidate_score < min_exact_match:
        reasons.append("Candidate exact-match score is below the configured minimum")
    if baseline is not None and regression > max_regression:
        reasons.append("Candidate regressed against the same sampled baseline by more than the configured threshold")
    return {
        "schema_version": 1,
        "created_at": utc_now(),
        "dataset_version": dataset_version,
        "sample_size_requested": sample_size,
        "sample_size_evaluated": len(sampled),
        "sampled_case_ids": [case["id"] for case in sampled],
        "baseline": baseline_result,
        "candidate": candidate_result,
        "baseline_exact_match": baseline_score,
        "candidate_exact_match": candidate_score,
        "regression": regression,
        "promoted": not reasons,
        "gate_reasons": reasons,
        "notes": [
            "Lexical/reference metrics are regression signals, not proof of factual correctness.",
            "Critical cases require exact normalized reference match unless the dataset's reference is revised by a human reviewer.",
            "Candidate and baseline are scored on the same deterministic, category-balanced sample.",
        ],
    }


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, required=True, help="Dataset JSON with dataset_version and cases[]")
    parser.add_argument("--candidate", type=Path, required=True, help="Candidate output JSON with matching dataset_version and answers object")
    parser.add_argument("--baseline", type=Path, help="Optional baseline output JSON for same-dataset comparison")
    parser.add_argument("--output", type=Path, required=True, help="Output JSON report path")
    parser.add_argument("--sample-size", type=int, default=100)
    parser.add_argument("--max-regression", type=float, default=0.02)
    parser.add_argument("--min-exact-match", type=float, default=0.0)
    parser.add_argument("--max-critical-failures", type=int, default=0)
    parser.add_argument("--allow-missing-answers", action="store_true", help="Do not block solely on omitted answers")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        dataset = load_json(args.dataset)
        candidate = load_json(args.candidate)
        baseline = load_json(args.baseline) if args.baseline else None
        report = run_benchmark(
            dataset, candidate, baseline, sample_size=args.sample_size,
            max_regression=args.max_regression, min_exact_match=args.min_exact_match,
            max_critical_failures=args.max_critical_failures,
            require_complete_answers=not args.allow_missing_answers,
        )
        args.output.parent.mkdir(parents=True, exist_ok=True)
        temp = args.output.with_suffix(args.output.suffix + ".tmp")
        temp.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        temp.replace(args.output)
        print(json.dumps({key: report[key] for key in ("promoted", "candidate_exact_match", "baseline_exact_match", "regression", "gate_reasons", "sample_size_evaluated", "output") if key != "output"}, indent=2))
        return 0 if report["promoted"] else 2
    except (ValueError, OSError) as exc:
        print(f"benchmark_lab: {exc}", file=sys.stderr)
        return 3


if __name__ == "__main__":
    raise SystemExit(main())
