import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("benchmark_lab.py")
spec = importlib.util.spec_from_file_location("benchmark_lab", SCRIPT)
benchmark_lab = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = benchmark_lab
spec.loader.exec_module(benchmark_lab)


def dataset():
    return {
        "dataset_version": "bench-v1",
        "cases": [
            {"id": "facts-1", "category": "facts", "prompt": "What is two plus two?", "reference": "Four", "critical": True},
            {"id": "facts-2", "category": "facts", "prompt": "Name a color", "reference": "Blue", "critical": False},
            {"id": "code-1", "category": "code", "prompt": "Print hi", "reference": "print hi", "critical": False},
            {"id": "safety-1", "category": "safety", "prompt": "Safe answer", "reference": "Ask a trusted adult", "critical": True},
            {"id": "code-2", "category": "code", "prompt": "Return 7", "reference": "return seven", "critical": False},
        ],
    }


def run(answers, run_id="run-1", version="bench-v1"):
    return {"run_id": run_id, "engine_version": run_id, "dataset_version": version, "answers": answers}


class BenchmarkLabTests(unittest.TestCase):
    def test_deterministic_sample_is_order_independent_and_category_balanced(self):
        ds = dataset()
        first = benchmark_lab.stable_sample(ds["cases"], ds["dataset_version"], 3)
        second = benchmark_lab.stable_sample(list(reversed(ds["cases"])), ds["dataset_version"], 3)
        self.assertEqual([x["id"] for x in first], [x["id"] for x in second])
        self.assertGreaterEqual(len({x["category"] for x in first}), 2)

    def test_exact_match_and_lexical_metrics_are_computed_for_selected_sample(self):
        ds = dataset()
        candidate = run({"facts-1": "Four!", "facts-2": "Blue", "code-1": "print hi", "safety-1": "Ask a trusted adult", "code-2": "return seven"})
        report = benchmark_lab.run_benchmark(ds, candidate, sample_size=5)
        self.assertEqual(1.0, report["candidate_exact_match"])
        self.assertEqual(1.0, report["candidate"]["metrics"]["lexical_f1"])
        self.assertTrue(report["promoted"])
        self.assertEqual(5, report["sample_size_evaluated"])

    def test_missing_answers_fail_closed(self):
        report = benchmark_lab.run_benchmark(dataset(), run({"facts-1": "Four"}), sample_size=5)
        self.assertFalse(report["promoted"])
        self.assertIn("Candidate is missing one or more sampled answers", report["gate_reasons"])
        self.assertGreaterEqual(len(report["candidate"]["missing_answers"]), 1)

    def test_critical_failure_blocks_release(self):
        candidate = run({"facts-1": "Five", "facts-2": "Blue", "code-1": "print hi", "safety-1": "No idea", "code-2": "return seven"})
        report = benchmark_lab.run_benchmark(dataset(), candidate, sample_size=5)
        self.assertFalse(report["promoted"])
        self.assertEqual(2, len(report["candidate"]["critical_failures"]))
        self.assertTrue(any("Critical failure" in reason for reason in report["gate_reasons"]))

    def test_baseline_and_candidate_are_scored_on_the_same_sample(self):
        ds = dataset()
        base = run({"facts-1": "Four", "facts-2": "Blue", "code-1": "print hi", "safety-1": "Ask a trusted adult", "code-2": "return seven"}, "baseline")
        cand = run({"facts-1": "Four", "facts-2": "Red", "code-1": "print hi", "safety-1": "Ask a trusted adult", "code-2": "return seven"}, "candidate")
        report = benchmark_lab.run_benchmark(ds, cand, base, sample_size=5, max_regression=0.0)
        self.assertEqual(report["sampled_case_ids"], [x["id"] for x in report["candidate"]["per_case"]])
        self.assertEqual(report["sampled_case_ids"], [x["id"] for x in report["baseline"]["per_case"]])
        self.assertAlmostEqual(0.2, report["regression"])
        self.assertFalse(report["promoted"])

    def test_incomplete_baseline_cannot_be_used_to_promote_candidate(self):
        ds = dataset()
        base = run({"facts-1": "Four"}, "baseline")
        candidate = run({"facts-1": "Four", "facts-2": "Blue", "code-1": "print hi", "safety-1": "Ask a trusted adult", "code-2": "return seven"})
        report = benchmark_lab.run_benchmark(ds, candidate, base, sample_size=5)
        self.assertFalse(report["promoted"])
        self.assertTrue(any("Baseline has missing" in reason for reason in report["gate_reasons"]))

    def test_candidate_and_dataset_versions_must_match(self):
        with self.assertRaisesRegex(ValueError, "dataset_version"):
            benchmark_lab.run_benchmark(dataset(), run({}, version="old-version"))

    def test_duplicate_dataset_ids_are_rejected(self):
        ds = dataset()
        ds["cases"].append(dict(ds["cases"][0]))
        with self.assertRaisesRegex(ValueError, "duplicate case id"):
            benchmark_lab.run_benchmark(ds, run({}))

    def test_unknown_answer_ids_are_rejected(self):
        with self.assertRaisesRegex(ValueError, "unknown answer IDs"):
            benchmark_lab.run_benchmark(dataset(), run({"not-a-case": "anything"}))

    def test_credentials_or_sensitive_content_are_not_echoed_in_reports(self):
        candidate = run({"facts-1": "api_key='123456789012345678901234567890'"})
        report = benchmark_lab.run_benchmark(dataset(), candidate, sample_size=1)
        serialized = json.dumps(report)
        self.assertNotIn("123456789012345678901234567890", serialized)
        # Reports include metrics and case IDs, not candidate answer content.
        self.assertNotIn("api_key", serialized)

    def test_cli_writes_report_and_returns_gate_status(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            ds_path, cand_path, out_path = root / "dataset.json", root / "candidate.json", root / "report.json"
            ds_path.write_text(json.dumps(dataset()), encoding="utf-8")
            cand_path.write_text(json.dumps(run({"facts-1": "Four", "facts-2": "Blue", "code-1": "print hi", "safety-1": "Ask a trusted adult", "code-2": "return seven"})), encoding="utf-8")
            proc = subprocess.run([sys.executable, str(SCRIPT), "--dataset", str(ds_path), "--candidate", str(cand_path), "--output", str(out_path), "--sample-size", "5"], capture_output=True, text=True)
            self.assertEqual(0, proc.returncode, proc.stderr)
            self.assertTrue(out_path.exists())
            self.assertTrue(json.loads(out_path.read_text(encoding="utf-8"))["promoted"])

    def test_cli_uses_distinct_exit_code_when_gate_blocks(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            ds_path, cand_path, out_path = root / "dataset.json", root / "candidate.json", root / "report.json"
            ds_path.write_text(json.dumps(dataset()), encoding="utf-8")
            cand_path.write_text(json.dumps(run({"facts-1": "Wrong"})), encoding="utf-8")
            proc = subprocess.run([sys.executable, str(SCRIPT), "--dataset", str(ds_path), "--candidate", str(cand_path), "--output", str(out_path), "--sample-size", "5"], capture_output=True, text=True)
            self.assertEqual(2, proc.returncode)
            self.assertFalse(json.loads(out_path.read_text(encoding="utf-8"))["promoted"])

    def test_unsafe_limits_are_rejected(self):
        with self.assertRaisesRegex(ValueError, "max_regression"):
            benchmark_lab.run_benchmark(dataset(), run({}), max_regression=2.0)


if __name__ == "__main__":
    unittest.main()
