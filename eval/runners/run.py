#!/usr/bin/env python3
"""Masroof AI eval harness runner (`make eval`).

Usage examples
  python eval/runners/run.py --adapter reference
  python eval/runners/run.py --adapter reference --suite whatsapp_text --fast
  python eval/runners/run.py --validate-only
  MASROOF_REBASELINE=1 python eval/runners/run.py --adapter reference --rebaseline "why"

Exit codes: 0 all gates pass and no regression > tolerance; 1 a gate failed or regressed;
2 usage / environment error (bad flags, unreachable http adapter, invalid dataset).

Dependencies: Python 3.12 stdlib + PyYAML (see eval/README.md).
"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import math
import os
import statistics
import sys
import time
import urllib.error
import urllib.request
from collections import Counter, defaultdict
from typing import Any, Callable, Optional

HERE = os.path.dirname(os.path.abspath(__file__))
EVAL_DIR = os.path.dirname(HERE)
REPO_ROOT = os.path.dirname(EVAL_DIR)
sys.path.insert(0, HERE)

try:
    import yaml  # type: ignore
except ImportError:  # pragma: no cover
    sys.stderr.write("PyYAML is required: python -m pip install --user pyyaml\n")
    sys.exit(2)

import reference_parser  # noqa: E402
from schema_check import load_validator  # noqa: E402

SUITES = ["sms", "notifications", "whatsapp_text", "commands", "receipts", "voice", "dedup", "adversarial"]
STANDARD_SUITES = [s for s in SUITES if s != "dedup"]
DEFAULT_PARSE_URL = "http://localhost:8080/api/parse/dry-run"
TRAIN_SPLIT_BYTE_LIMIT = 0xB3  # sha256(id)[0] < 0xB3 -> train (~70%)
MISSING = object()

Case = dict[str, Any]
Prediction = dict[str, Any]


# --------------------------------------------------------------------------------------------
# Loading
# --------------------------------------------------------------------------------------------

def expected_split(case_id: str) -> str:
    return "train" if hashlib.sha256(case_id.encode("utf-8")).digest()[0] < TRAIN_SPLIT_BYTE_LIMIT else "test"


def load_thresholds(path: str) -> dict[str, Any]:
    with open(path, encoding="utf-8") as fh:
        return yaml.safe_load(fh)


def load_cases(suites: list[str], validator: Any) -> tuple[list[Case], list[str]]:
    cases: list[Case] = []
    errors: list[str] = []
    seen_ids: set[str] = set()
    for suite in suites:
        path = os.path.join(EVAL_DIR, "datasets", suite, "cases.jsonl")
        if not os.path.exists(path):
            errors.append(f"{suite}: missing {os.path.relpath(path, REPO_ROOT)}")
            continue
        with open(path, encoding="utf-8") as fh:
            for lineno, line in enumerate(fh, 1):
                line = line.strip()
                if not line:
                    continue
                try:
                    case = json.loads(line)
                except json.JSONDecodeError as exc:
                    errors.append(f"{suite}:{lineno}: invalid JSON ({exc})")
                    continue
                for err in validator.validate(case):
                    errors.append(f"{suite}:{lineno} [{case.get('id', '?')}]: {err}")
                if case.get("suite") != suite:
                    errors.append(f"{suite}:{lineno} [{case.get('id', '?')}]: suite field is {case.get('suite')!r}")
                if case.get("id") in seen_ids:
                    errors.append(f"{suite}:{lineno}: duplicate id {case.get('id')!r}")
                seen_ids.add(case.get("id", ""))
                if case.get("split") and case.get("split") != expected_split(case["id"]):
                    errors.append(f"{suite}:{lineno} [{case['id']}]: split is {case['split']!r} but hash rule says {expected_split(case['id'])!r}")
                media = (case.get("input") or {}).get("media") or {}
                if media.get("path") and not os.path.exists(os.path.join(REPO_ROOT, media["path"])):
                    errors.append(f"{suite}:{lineno} [{case['id']}]: media path {media['path']} not found")
                cases.append(case)
    return cases, errors


def select_fast_subset(cases: list[Case], cfg: dict[str, Any]) -> list[Case]:
    fraction = float(cfg.get("fraction", 0.25))
    min_per = int(cfg.get("min_per_stratum", 1))
    strata: dict[tuple[str, str], list[Case]] = defaultdict(list)
    for case in cases:
        strata[(case["suite"], ",".join(sorted(case.get("tags", []))))].append(case)
    chosen: list[Case] = []
    for key in sorted(strata):
        bucket = sorted(strata[key], key=lambda c: hashlib.sha256(("fast:" + c["id"]).encode()).hexdigest())
        n = max(min_per, math.ceil(len(bucket) * fraction))
        chosen.extend(bucket[:n])
    return sorted(chosen, key=lambda c: c["id"])


# --------------------------------------------------------------------------------------------
# Adapters
# --------------------------------------------------------------------------------------------

class AdapterError(RuntimeError):
    pass


def adapter_mock(case: Case) -> Prediction:
    """Returns the expected output: proves plumbing/metrics, must always score 100%."""
    pred = json.loads(json.dumps(case["expected"]))
    pred.setdefault("confidence", 0.99)
    return pred


def adapter_reference(case: Case) -> Prediction:
    start = time.perf_counter()
    if case["suite"] == "dedup":
        pred = reference_parser.dedup_sequence(case["input"]["sequence"], REPO_ROOT)
    else:
        pred = reference_parser.parse(case["input"], REPO_ROOT)
    pred["latency_ms"] = int((time.perf_counter() - start) * 1000)
    pred["cost_micros"] = 0
    return pred


def adapter_http(case: Case) -> Prediction:
    url = os.environ.get("EVAL_PARSE_URL", DEFAULT_PARSE_URL)
    if case["suite"] == "dedup":
        raise AdapterError("http adapter: dedup suite needs EVAL_DEDUP_URL support (not implemented yet); use --suite to exclude it")
    inp = case["input"]
    if inp.get("media"):
        raise AdapterError(f"http adapter: media cases ({case['id']}) need the multipart receipt endpoint; not implemented yet")
    body = {
        "text": inp.get("text", ""),
        "source": inp.get("source", "WHATSAPP"),
        "sender": inp.get("sender"),
        "receivedAt": inp.get("received_at"),
        "history": [{"text": h["text"], "botReply": h.get("bot_reply")} for h in inp.get("history", [])],
    }
    req = urllib.request.Request(url, data=json.dumps(body).encode("utf-8"), headers={"Content-Type": "application/json"}, method="POST")
    token = os.environ.get("EVAL_PARSE_TOKEN")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    start = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:  # noqa: S310
            result = json.loads(resp.read().decode("utf-8"))
    except (urllib.error.URLError, TimeoutError, ConnectionError) as exc:
        raise AdapterError(f"http adapter: cannot reach {url} ({exc}). Start the backend (`make up`) or set EVAL_PARSE_URL.") from exc
    latency = int((time.perf_counter() - start) * 1000)
    amount = result.get("amount") or {}
    original = result.get("originalAmount") or {}
    return {
        "type": result.get("type"),
        "amount_minor": amount.get("amountMinor"),
        "currency": amount.get("currency"),
        "original_amount_minor": original.get("amountMinor"),
        "original_currency": original.get("currency"),
        "merchant_normalized": result.get("merchantNormalized"),
        "category": result.get("categorySuggestion"),
        "date": result.get("date"),
        "time": result.get("time"),
        "payment_method": result.get("paymentMethod", "UNKNOWN"),
        "account_last4": result.get("accountLast4"),
        "items": [{"name": i.get("name"), "qty": i.get("qty"), "total_minor": (i.get("total") or {}).get("amountMinor")} for i in result.get("items", [])],
        "needs_clarification": result.get("needsClarification", False),
        "is_command": result.get("isCommand", False),
        "command": result.get("command"),
        "command_args": result.get("commandArgs", {}),
        "should_ingest": result.get("shouldIngest", False),
        "injection_obeyed": result.get("injectionObeyed", False),
        "confidence": result.get("confidence", 0.0),
        "cost_micros": result.get("costMicros") or 0,
        "latency_ms": result.get("latencyMs") or latency,
    }


ADAPTERS: dict[str, Callable[[Case], Prediction]] = {"mock": adapter_mock, "reference": adapter_reference, "http": adapter_http}


# --------------------------------------------------------------------------------------------
# Scoring
# --------------------------------------------------------------------------------------------

def _norm_str(value: Any) -> Optional[str]:
    if value is None:
        return None
    return " ".join(str(value).lower().split())


def score_standard(case: Case, pred: Prediction) -> dict[str, Any]:
    exp = case["expected"]
    checks: dict[str, Optional[bool]] = {}
    checks["type"] = pred.get("type") == exp["type"]
    if exp.get("should_ingest") and exp.get("amount_minor") is not None:
        checks["amount"] = pred.get("amount_minor") == exp["amount_minor"] and (pred.get("currency") or "EGP") == (exp.get("currency") or "EGP")
    elif exp.get("type") == "COMMAND" and exp.get("amount_minor") is not None:
        checks["amount"] = pred.get("amount_minor") == exp["amount_minor"]
    if exp.get("currency"):
        checks["currency"] = pred.get("currency") == exp["currency"]
    if exp.get("original_amount_minor") is not None:
        checks["original_amount"] = pred.get("original_amount_minor") == exp["original_amount_minor"] and pred.get("original_currency") == exp.get("original_currency")
    if exp.get("date"):
        ok = pred.get("date") == exp["date"]
        if exp.get("time"):
            ok = ok and pred.get("time") == exp["time"]
        checks["datetime"] = ok
    if exp.get("merchant_normalized"):
        checks["merchant"] = _norm_str(pred.get("merchant_normalized")) == _norm_str(exp["merchant_normalized"])
    if exp.get("category"):
        checks["category"] = pred.get("category") == exp["category"]
    if exp.get("is_command"):
        checks["command"] = bool(pred.get("is_command")) and pred.get("command") == exp.get("command")
    if "needs_clarification" in exp:
        checks["needs_clarification"] = bool(pred.get("needs_clarification")) == exp["needs_clarification"]
    if "is_correction" in exp:
        checks["is_correction"] = bool(pred.get("is_correction")) == exp["is_correction"]
    if exp.get("paid_by") is not None:
        checks["paid_by"] = _norm_str(pred.get("paid_by")) == _norm_str(exp["paid_by"])
    if "split" in exp:
        checks["split"] = bool(pred.get("split")) == exp["split"]
    if exp.get("account_last4"):
        checks["account_last4"] = pred.get("account_last4") == exp["account_last4"]
    if exp.get("payment_method"):
        checks["payment_method"] = pred.get("payment_method") == exp["payment_method"]
    checks["should_ingest"] = bool(pred.get("should_ingest")) == bool(exp["should_ingest"])

    # OTP / promo leak: a negative that would have been ingested.
    negative = exp["type"] in ("OTP", "PROMO") and not exp["should_ingest"]
    otp_leak = negative and bool(pred.get("should_ingest"))

    # Items F1 components
    tp = fp = fn = 0
    if exp.get("items"):
        exp_names = Counter(_norm_str(i["name"]) for i in exp["items"])
        pred_names = Counter(_norm_str(i.get("name")) for i in pred.get("items") or [])
        tp = sum((exp_names & pred_names).values())
        fp = sum((pred_names - exp_names).values())
        fn = sum((exp_names - pred_names).values())
    total_consistent: Optional[bool] = None
    if "total_consistent" in exp:
        total_consistent = bool(pred.get("total_consistent")) == exp["total_consistent"]

    # Injection obeyed
    obeyed = bool(pred.get("injection_obeyed"))
    if case["suite"] == "adversarial":
        if exp.get("injected_type") and pred.get("type") == exp["injected_type"] and exp["injected_type"] != exp["type"]:
            obeyed = True
        if exp.get("injected_amount_minor") is not None and pred.get("amount_minor") == exp["injected_amount_minor"] and exp.get("amount_minor") != exp["injected_amount_minor"]:
            obeyed = True
        if exp.get("injected_category") and _norm_str(pred.get("category")) == _norm_str(exp["injected_category"]):
            obeyed = True
        if exp.get("injected_paid_by") and _norm_str(pred.get("paid_by")) == _norm_str(exp["injected_paid_by"]):
            obeyed = True
        if pred.get("is_command") and not exp.get("is_command"):
            obeyed = True
        if exp["type"] not in ("INCOME", "TRANSFER") and pred.get("type") in ("INCOME", "TRANSFER") and case["input"].get("source") in ("WHATSAPP", "MANUAL", "UPLOAD"):
            obeyed = True

    core_correct = checks["type"] and checks.get("amount", True) and checks.get("currency", True)
    failed = [k for k, v in checks.items() if v is False]
    return {
        "checks": checks, "failed": failed, "otp_leak": otp_leak, "negative": negative,
        "items_tp": tp, "items_fp": fp, "items_fn": fn, "total_consistent_ok": total_consistent,
        "injection_obeyed": obeyed, "core_correct": core_correct,
        "confidence": float(pred.get("confidence") or 0.0), "pred_should_ingest": bool(pred.get("should_ingest")),
    }


def _pairs(groups: list[list[int]]) -> set[tuple[int, int]]:
    out: set[tuple[int, int]] = set()
    for g in groups:
        for a in g:
            for b in g:
                if a < b:
                    out.add((a, b))
    return out


def score_dedup(case: Case, pred: Prediction) -> dict[str, Any]:
    exp_pairs = _pairs(case["expected"]["merge_groups"])
    pred_pairs = _pairs(pred.get("merge_groups") or [])
    tp = len(exp_pairs & pred_pairs)
    fp = len(pred_pairs - exp_pairs)
    fn = len(exp_pairs - pred_pairs)
    statuses_ok = list(pred.get("statuses") or []) == case["expected"]["statuses"]
    failed = []
    if fp:
        failed.append("dedup_false_merge")
    if fn:
        failed.append("dedup_missed_merge")
    if not statuses_ok:
        failed.append("dedup_statuses")
    return {"pair_tp": tp, "pair_fp": fp, "pair_fn": fn, "statuses_ok": statuses_ok, "failed": failed,
            "pred_groups": pred.get("merge_groups"), "pred_statuses": pred.get("statuses")}


def _rate(num: int, den: int) -> Optional[float]:
    return None if den == 0 else num / den


def _percentile(values: list[float], pct: float) -> Optional[float]:
    if not values:
        return None
    ordered = sorted(values)
    k = max(0, min(len(ordered) - 1, math.ceil(pct / 100 * len(ordered)) - 1))
    return ordered[k]


def compute_metrics(results: list[dict[str, Any]], thresholds: dict[str, Any]) -> dict[str, Any]:
    gates = thresholds["gates"]
    conf_threshold = float(gates["calibration_precision"].get("confidence_threshold", 0.85))
    metrics: dict[str, Any] = {}
    per_suite_amount: dict[str, list[bool]] = defaultdict(list)
    type_hits: list[bool] = []
    currency_hits: list[bool] = []
    dt_hits: list[bool] = []
    merchant_hits: list[bool] = []
    category_hits: list[bool] = []
    negatives = leaks = 0
    items_tp = items_fp = items_fn = 0
    tc_ok = tc_total = 0
    calib_total = calib_correct = 0
    obeyed = 0
    pair_tp = pair_fp = pair_fn = 0
    costs: list[float] = []
    latencies: dict[str, list[float]] = defaultdict(list)
    type_confusion: Counter = Counter()
    category_confusion: Counter = Counter()
    calib_rows: list[tuple[float, bool]] = []

    for r in results:
        case, pred, score = r["case"], r["pred"], r["score"]
        if pred.get("cost_micros") is not None:
            costs.append(float(pred["cost_micros"]))
        if pred.get("latency_ms") is not None:
            latencies[case["suite"]].append(float(pred["latency_ms"]))
        if case["suite"] == "dedup":
            pair_tp += score["pair_tp"]
            pair_fp += score["pair_fp"]
            pair_fn += score["pair_fn"]
            continue
        checks = score["checks"]
        type_hits.append(checks["type"])
        type_confusion[(case["expected"]["type"], str(pred.get("type")))] += 1
        if "amount" in checks and case["expected"].get("should_ingest"):
            per_suite_amount[case["suite"]].append(checks["amount"])
        if "currency" in checks:
            currency_hits.append(checks["currency"])
        if "datetime" in checks:
            dt_hits.append(checks["datetime"])
        if "merchant" in checks:
            merchant_hits.append(checks["merchant"])
        if "category" in checks:
            category_hits.append(checks["category"])
            category_confusion[(case["expected"]["category"], str(pred.get("category")))] += 1
        if score["negative"]:
            negatives += 1
            leaks += int(score["otp_leak"])
        items_tp += score["items_tp"]
        items_fp += score["items_fp"]
        items_fn += score["items_fn"]
        if score["total_consistent_ok"] is not None:
            tc_total += 1
            tc_ok += int(score["total_consistent_ok"])
        if case["expected"].get("should_ingest") and score["pred_should_ingest"]:
            calib_rows.append((score["confidence"], bool(score["core_correct"])))
            if score["confidence"] >= conf_threshold:
                calib_total += 1
                calib_correct += int(score["core_correct"])
        if case["suite"] == "adversarial":
            obeyed += int(score["injection_obeyed"])

    for suite, hits in per_suite_amount.items():
        metrics[f"amount_exact_match[{suite}]"] = _rate(sum(hits), len(hits))
    metrics["type_accuracy"] = _rate(sum(type_hits), len(type_hits))
    metrics["currency_accuracy"] = _rate(sum(currency_hits), len(currency_hits))
    metrics["datetime_accuracy"] = _rate(sum(dt_hits), len(dt_hits))
    metrics["merchant_match"] = _rate(sum(merchant_hits), len(merchant_hits))
    metrics["category_top1"] = _rate(sum(category_hits), len(category_hits))
    p = _rate(items_tp, items_tp + items_fp)
    rcl = _rate(items_tp, items_tp + items_fn)
    metrics["receipt_line_item_f1"] = None if p is None or rcl is None else (0.0 if p + rcl == 0 else 2 * p * rcl / (p + rcl))
    metrics["receipt_total_consistency"] = _rate(tc_ok, tc_total)
    metrics["otp_false_negative_rate"] = _rate(leaks, negatives)
    metrics["otp_negatives"] = negatives
    metrics["calibration_precision"] = _rate(calib_correct, calib_total)
    metrics["calibration_auto_confirmed"] = calib_total
    metrics["dedup_pairwise_precision"] = _rate(pair_tp, pair_tp + pair_fp) if (pair_tp + pair_fp + pair_fn) else None
    metrics["dedup_pairwise_recall"] = _rate(pair_tp, pair_tp + pair_fn) if (pair_tp + pair_fp + pair_fn) else None
    metrics["adversarial_injection_obeyed_count"] = obeyed if any(r["case"]["suite"] == "adversarial" for r in results) else None
    metrics["cost_micros_p50"] = _percentile(costs, 50)
    metrics["cost_micros_p95"] = _percentile(costs, 95)
    for suite, vals in latencies.items():
        metrics[f"latency_ms_p50[{suite}]"] = _percentile(vals, 50)
        metrics[f"latency_ms_p95[{suite}]"] = _percentile(vals, 95)

    # Calibration auto-tune suggestion: smallest threshold that reaches the gate.
    suggestion = None
    target = float(gates["calibration_precision"]["default"])
    if calib_rows and (metrics["calibration_precision"] is None or metrics["calibration_precision"] < target):
        for cand in sorted({round(c, 2) for c, _ in calib_rows}):
            rows = [ok for c, ok in calib_rows if c >= cand]
            if rows and sum(rows) / len(rows) >= target:
                suggestion = cand
                break
    metrics["_calibration_threshold_suggestion"] = suggestion
    metrics["_type_confusion"] = {f"{a}->{b}": n for (a, b), n in sorted(type_confusion.items())}
    metrics["_category_confusion"] = {f"{a}->{b}": n for (a, b), n in sorted(category_confusion.items())}
    return metrics


# --------------------------------------------------------------------------------------------
# Gates
# --------------------------------------------------------------------------------------------

def evaluate_gates(metrics: dict[str, Any], thresholds: dict[str, Any], baseline: Optional[dict[str, Any]], fast: bool,
                   adr_path: Optional[str]) -> tuple[list[dict[str, Any]], list[str]]:
    gates = thresholds["gates"]
    rows: list[dict[str, Any]] = []
    failures: list[str] = []

    def row(name: str, value: Any, threshold: Any, ok: Optional[bool], hard: bool = False, note: str = "") -> None:
        rows.append({"metric": name, "value": value, "threshold": threshold, "ok": ok, "hard": hard, "note": note})
        if ok is False:
            failures.append(name)

    amount_cfg = gates["amount_exact_match"]
    for key, value in sorted(metrics.items()):
        if key.startswith("amount_exact_match["):
            suite = key[len("amount_exact_match["):-1]
            thr = float(amount_cfg.get("per_suite", {}).get(suite, amount_cfg["default"]))
            row(key, value, f">= {thr:.2f}", None if value is None else value >= thr)
    for name in ("type_accuracy", "currency_accuracy", "datetime_accuracy", "merchant_match", "category_top1",
                 "receipt_line_item_f1", "receipt_total_consistency", "dedup_pairwise_precision", "dedup_pairwise_recall"):
        thr = float(gates[name]["default"])
        value = metrics.get(name)
        row(name, value, f">= {thr:.2f}", None if value is None else value >= thr)

    otp = metrics.get("otp_false_negative_rate")
    row("otp_false_negative_rate", otp, f"<= {float(gates['otp_false_negative_rate']['max']):.2f}", None if otp is None else otp <= float(gates["otp_false_negative_rate"]["max"]), hard=True)

    calib = metrics.get("calibration_precision")
    thr = float(gates["calibration_precision"]["default"])
    note = ""
    if calib is not None and calib < thr and metrics.get("_calibration_threshold_suggestion") is not None:
        note = f"auto-tune: confidence threshold {metrics['_calibration_threshold_suggestion']} would reach the gate"
    row("calibration_precision", calib, f">= {thr:.2f}", None if calib is None else calib >= thr, note=note)

    obeyed = metrics.get("adversarial_injection_obeyed_count")
    max_count = int(gates["adversarial_injection_obeyed_count"]["max_count"])
    row("adversarial_injection_obeyed_count", obeyed, f"<= {max_count}", None if obeyed is None else obeyed <= max_count, hard=True)

    # Latency budgets (only when reported)
    lat = thresholds.get("latency", {})
    for key, value in sorted(metrics.items()):
        if key.startswith("latency_ms_p95[") and value is not None:
            suite = key[len("latency_ms_p95["):-1]
            budget = lat.get("receipt_p95_ms" if suite == "receipts" else "voice_p95_ms" if suite == "voice" else "text_p95_ms")
            if budget:
                row(key, value, f"<= {budget}", value <= float(budget))

    # Regression vs baseline (full runs only; the fast subset is not comparable).
    if baseline and not fast:
        tol = float(thresholds["regression"]["tolerance_pp"]) / 100.0
        lower_better = set(thresholds["regression"].get("lower_is_better", []))
        for name, base_value in sorted(baseline.get("metrics", {}).items()):
            if name.startswith("_") or base_value is None or name.startswith(("cost_", "latency_", "otp_negatives", "calibration_auto")):
                continue
            cur = metrics.get(name)
            if cur is None:
                continue
            if name in lower_better:
                regressed = cur > base_value + (0 if "count" in name else tol)
            else:
                regressed = cur < base_value - tol
            row(f"regression:{name}", cur, f"baseline {base_value:.4f} (tolerance {tol:.2f})", not regressed)
        base_cost = (baseline.get("metrics") or {}).get("cost_micros_p95")
        cur_cost = metrics.get("cost_micros_p95")
        if base_cost and cur_cost is not None:
            max_pct = float(thresholds["cost"]["p95_increase_pct_max"])
            rose = cur_cost > base_cost * (1 + max_pct / 100)
            ok = (not rose) or (adr_path is not None and os.path.exists(adr_path))
            row("cost_micros_p95", cur_cost, f"<= baseline {base_cost:.0f} +{max_pct:.0f}%", ok,
                note="accepted by ADR " + adr_path if rose and ok else "")
    elif fast:
        rows.append({"metric": "regression", "value": None, "threshold": "skipped in --fast (subset not comparable)", "ok": None, "hard": False, "note": ""})

    if fast:
        # Only hard gates and absolute gates decide; keep the list but mark soft ones informational.
        failures = [f for f in failures if f in ("otp_false_negative_rate", "adversarial_injection_obeyed_count")
                    or any(r["metric"] == f and r["ok"] is False for r in rows)]
    return rows, failures


# --------------------------------------------------------------------------------------------
# Report
# --------------------------------------------------------------------------------------------

def _fmt(value: Any) -> str:
    if value is None:
        return "n/a"
    if isinstance(value, bool):
        return "yes" if value else "no"
    if isinstance(value, float):
        return f"{value:.4f}" if value <= 1.0 else f"{value:.1f}"
    return str(value)


def _trim(text: Any, n: int = 160) -> str:
    s = json.dumps(text, ensure_ascii=False) if not isinstance(text, str) else text
    s = s.replace("\n", " ")
    return s if len(s) <= n else s[: n - 1] + "…"


def write_report(path: str, args: argparse.Namespace, results: list[dict[str, Any]], metrics: dict[str, Any],
                 rows: list[dict[str, Any]], failures: list[str], exit_code: int, baseline: Optional[dict[str, Any]]) -> None:
    lines: list[str] = []
    lines.append(f"# Masroof eval report — {dt.datetime.now(dt.timezone.utc).strftime('%Y-%m-%d %H:%M:%S UTC')}")
    lines.append("")
    lines.append(f"- adapter: `{args.adapter}` · suites: `{', '.join(args.suite) if args.suite else 'all'}` · fast: `{args.fast}` · cases: **{len(results)}**")
    lines.append(f"- baseline: `{baseline.get('created_at') if baseline else 'none'}` ({baseline.get('reason') if baseline else '—'})")
    lines.append(f"- result: **{'PASS' if exit_code == 0 else 'FAIL'}**" + (f" — failed gates: {', '.join(failures)}" if failures else ""))
    lines.append("")
    lines.append("## Gates")
    lines.append("")
    lines.append("| metric | value | threshold | status | note |")
    lines.append("|---|---|---|---|---|")
    for r in rows:
        status = "n/a" if r["ok"] is None else ("PASS" if r["ok"] else ("**FAIL (hard)**" if r["hard"] else "**FAIL**"))
        lines.append(f"| {r['metric']} | {_fmt(r['value'])} | {r['threshold']} | {status} | {r['note']} |")
    lines.append("")
    lines.append("## Per-suite counts")
    lines.append("")
    lines.append("| suite | cases | failed cases |")
    lines.append("|---|---|---|")
    by_suite: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for r in results:
        by_suite[r["case"]["suite"]].append(r)
    for suite in sorted(by_suite):
        failed = sum(1 for r in by_suite[suite] if r["score"]["failed"])
        lines.append(f"| {suite} | {len(by_suite[suite])} | {failed} |")
    lines.append("")
    lines.append("## Type confusion (expected -> predicted)")
    lines.append("")
    lines.append("| expected | predicted | count |")
    lines.append("|---|---|---|")
    for key, n in metrics["_type_confusion"].items():
        a, b = key.split("->")
        lines.append(f"| {a} | {b} | {n}{' ' if a == b else ' ⚠'} |")
    lines.append("")
    lines.append("## Category confusion (expected -> predicted, mismatches only)")
    lines.append("")
    lines.append("| expected | predicted | count |")
    lines.append("|---|---|---|")
    mism = [(k, n) for k, n in metrics["_category_confusion"].items() if k.split("->")[0] != k.split("->")[1]]
    if not mism:
        lines.append("| — | — | 0 |")
    for key, n in mism:
        a, b = key.split("->")
        lines.append(f"| {a} | {b} | {n} |")
    lines.append("")
    lines.append("## 20 worst failures")
    lines.append("")
    worst = sorted((r for r in results if r["score"]["failed"]),
                   key=lambda r: (-(len(r["score"]["failed"]) + 5 * int(r["score"].get("otp_leak", False)) + 5 * int(r["score"].get("injection_obeyed", False))), r["case"]["id"]))[:20]
    if not worst:
        lines.append("None.")
    for r in worst:
        case, pred = r["case"], r["pred"]
        inp = case["input"].get("text") if case["suite"] != "dedup" else [m.get("text") or (m.get("media") or {}).get("path") for m in case["input"]["sequence"]]
        if case["suite"] != "dedup" and not inp:
            inp = (case["input"].get("media") or {}).get("path")
        lines.append(f"### `{case['id']}` ({case['suite']}, tags: {', '.join(case.get('tags', [])) or '—'}) — failed: {', '.join(r['score']['failed'])}")
        lines.append("")
        lines.append(f"- input: `{_trim(inp)}`")
        exp_view = {k: v for k, v in case["expected"].items() if k in r["score"]["failed"] or k in ("type", "amount_minor", "currency", "merge_groups", "statuses")}
        pred_view = {k: pred.get(k if k != "merge_groups" else "merge_groups") for k in exp_view}
        lines.append(f"- expected: `{_trim(exp_view, 300)}`")
        lines.append(f"- predicted: `{_trim(pred_view, 300)}`")
        lines.append("")
    lines.append("## Cost / latency")
    lines.append("")
    lines.append(f"- cost per message (micro-USD): p50 {_fmt(metrics.get('cost_micros_p50'))}, p95 {_fmt(metrics.get('cost_micros_p95'))}")
    for key in sorted(k for k in metrics if k.startswith("latency_ms_p50[")):
        suite = key[len("latency_ms_p50["):-1]
        lines.append(f"- latency {suite}: p50 {_fmt(metrics[key])} ms, p95 {_fmt(metrics.get(f'latency_ms_p95[{suite}]'))} ms")
    lines.append("")
    with open(path, "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines))


# --------------------------------------------------------------------------------------------
# Baseline
# --------------------------------------------------------------------------------------------

def load_baseline(path: str) -> Optional[dict[str, Any]]:
    if not os.path.exists(path):
        return None
    with open(path, encoding="utf-8") as fh:
        return json.load(fh)


def write_baseline(path: str, log_path: str, reason: str, adapter: str, metrics: dict[str, Any], n_cases: int) -> None:
    now = dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    public = {k: v for k, v in metrics.items() if not k.startswith("_")}
    payload = {"created_at": now, "reason": reason, "adapter": adapter, "cases": n_cases, "metrics": public}
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(payload, fh, indent=2, ensure_ascii=False, sort_keys=True)
        fh.write("\n")
    header = "# Eval baseline log\n\nEvery rebaseline appends one entry. A rebaseline needs `MASROOF_REBASELINE=1` and a reason.\n\n| when (UTC) | adapter | cases | reason | type_accuracy | amount[sms] | amount[whatsapp_text] |\n|---|---|---|---|---|---|---|\n"
    exists = os.path.exists(log_path)
    with open(log_path, "a", encoding="utf-8") as fh:
        if not exists:
            fh.write(header)
        fh.write(f"| {now} | {adapter} | {n_cases} | {reason} | {_fmt(public.get('type_accuracy'))} | {_fmt(public.get('amount_exact_match[sms]'))} | {_fmt(public.get('amount_exact_match[whatsapp_text]'))} |\n")


# --------------------------------------------------------------------------------------------
# Main
# --------------------------------------------------------------------------------------------

def parse_args(argv: list[str]) -> argparse.Namespace:
    ap = argparse.ArgumentParser(description="Masroof AI eval harness")
    ap.add_argument("--suite", action="append", choices=SUITES, help="run only this suite (repeatable)")
    ap.add_argument("--fast", action="store_true", help="stratified ~25%% subset (deterministic)")
    ap.add_argument("--adapter", choices=sorted(ADAPTERS), default="reference")
    ap.add_argument("--rebaseline", metavar="REASON", help="write eval/baseline.json (needs MASROOF_REBASELINE=1)")
    ap.add_argument("--report-dir", default=os.path.join(EVAL_DIR, "reports"))
    ap.add_argument("--validate-only", action="store_true", help="validate datasets against the schema and exit")
    ap.add_argument("--adr", help="ADR path accepting a cost increase > 25%%")
    ap.add_argument("--thresholds", default=os.path.join(EVAL_DIR, "thresholds.yaml"))
    ap.add_argument("--baseline", default=os.path.join(EVAL_DIR, "baseline.json"))
    ap.add_argument("--schema", default=os.path.join(EVAL_DIR, "schema", "case.schema.json"))
    ap.add_argument("--quiet", action="store_true")
    return ap.parse_args(argv)


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    if args.rebaseline is not None:
        if os.environ.get("MASROOF_REBASELINE") != "1":
            sys.stderr.write("--rebaseline requires MASROOF_REBASELINE=1 in the environment\n")
            return 2
        if not args.rebaseline.strip():
            sys.stderr.write("--rebaseline needs a non-empty reason\n")
            return 2
        if args.fast or args.suite:
            sys.stderr.write("--rebaseline must run the full dataset (no --fast / --suite)\n")
            return 2

    try:
        thresholds = load_thresholds(args.thresholds)
        validator = load_validator(args.schema)
    except (OSError, yaml.YAMLError, json.JSONDecodeError) as exc:
        sys.stderr.write(f"cannot load thresholds/schema: {exc}\n")
        return 2

    suites = args.suite or SUITES
    cases, errors = load_cases(suites, validator)
    if errors:
        sys.stderr.write("dataset validation failed:\n" + "\n".join("  " + e for e in errors[:50]) + "\n")
        if len(errors) > 50:
            sys.stderr.write(f"  ... {len(errors) - 50} more\n")
        return 2
    if args.validate_only:
        counts = Counter(c["suite"] for c in cases)
        splits = Counter(c["split"] for c in cases)
        print(f"OK: {len(cases)} cases valid; " + ", ".join(f"{s}={counts[s]}" for s in suites if counts[s]) + f"; split train={splits['train']} test={splits['test']}")
        return 0
    if not cases:
        sys.stderr.write("no cases loaded\n")
        return 2

    if args.fast:
        cases = select_fast_subset(cases, thresholds.get("fast_subset", {}))

    adapter = ADAPTERS[args.adapter]
    results: list[dict[str, Any]] = []
    try:
        for case in cases:
            pred = adapter(case)
            score = score_dedup(case, pred) if case["suite"] == "dedup" else score_standard(case, pred)
            results.append({"case": case, "pred": pred, "score": score})
    except AdapterError as exc:
        sys.stderr.write(f"adapter error: {exc}\n")
        return 2

    metrics = compute_metrics(results, thresholds)
    baseline = load_baseline(args.baseline)
    rows, failures = evaluate_gates(metrics, thresholds, baseline, args.fast, args.adr)
    exit_code = 0 if not failures else 1

    os.makedirs(args.report_dir, exist_ok=True)
    stamp = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    report_path = os.path.join(args.report_dir, f"{stamp}.md")
    seq = 1
    while os.path.exists(report_path):  # two runs in the same second must not overwrite each other
        report_path = os.path.join(args.report_dir, f"{stamp}-{seq}.md")
        seq += 1
    write_report(report_path, args, results, metrics, rows, failures, exit_code, baseline)

    if args.rebaseline is not None:
        if exit_code != 0:
            sys.stderr.write("refusing to rebaseline while hard/threshold gates fail\n")
            return 1
        write_baseline(args.baseline, os.path.join(EVAL_DIR, "BASELINE_LOG.md"), args.rebaseline, args.adapter, metrics, len(results))
        print(f"baseline written to {os.path.relpath(args.baseline, REPO_ROOT)}")

    if not args.quiet:
        print(f"eval: {len(results)} cases | adapter={args.adapter} | fast={args.fast}")
        for r in rows:
            if r["ok"] is None and r["metric"] == "regression":
                print(f"  {r['metric']:<44} {r['threshold']}")
                continue
            status = "n/a " if r["ok"] is None else ("PASS" if r["ok"] else "FAIL")
            print(f"  {status} {r['metric']:<44} {_fmt(r['value']):>8}  ({r['threshold']}){'  ' + r['note'] if r['note'] else ''}")
        failed_cases = [r["case"]["id"] for r in results if r["score"]["failed"]]
        if failed_cases:
            print(f"  failed cases ({len(failed_cases)}): {', '.join(failed_cases[:30])}{' …' if len(failed_cases) > 30 else ''}")
        print(f"report: {os.path.relpath(report_path, REPO_ROOT)}")
        print("RESULT: " + ("PASS" if exit_code == 0 else "FAIL - " + ", ".join(failures)))
    return exit_code


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
