#!/usr/bin/env python3
"""Compare SQLPERF captures: python compare.py --baseline a.json b.json --candidate c.json d.json [--check]

Each side may hold several capture runs. A query's figure is the median of the per-run medians; the range across runs is
its noise band. --check exits 1 if a candidate median is above the baseline's worst run by more than the tolerance
(baseline_max * FACTOR + SLACK_MS), so a regression must beat the observed noise, not a single lucky run.
"""
import argparse, json, statistics, sys

FACTOR, SLACK_MS = 1.5, 0.5


def load(paths):
    runs = [json.load(open(p, encoding="utf-8")) for p in paths]
    out = {}
    for r in runs:
        for q in r["queries"]:
            out.setdefault(q["id"], []).append(0.0 if q.get("const_lookup") else q["median_ms"])
    return out, runs


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--baseline", nargs="+", required=True)
    ap.add_argument("--candidate", nargs="+", required=True)
    ap.add_argument("--check", action="store_true")
    a = ap.parse_args()
    base, _ = load(a.baseline)
    cand, _ = load(a.candidate)
    print(f"| query | baseline median ms [min-max] (n={len(a.baseline)}) | candidate median ms [min-max] (n={len(a.candidate)}) | change |")
    print("|---|---:|---:|---:|")
    failures = []
    for qid in base:
        b, c = base[qid], cand.get(qid)
        if not c: continue
        bm, cm = statistics.median(b), statistics.median(c)
        change = "n/a (const)" if bm == 0 and cm == 0 else f"{(cm / bm - 1) * 100:+.0f}%" if bm else "n/a"
        print(f"| {qid} | {bm:.3f} [{min(b):.3f}-{max(b):.3f}] | {cm:.3f} [{min(c):.3f}-{max(c):.3f}] | {change} |")
        if cm > max(b) * FACTOR + SLACK_MS:
            failures.append((qid, cm, max(b)))
    if a.check and failures:
        print("\nREGRESSION:", *(f"{q}: {c:.3f} ms > tolerance from baseline max {m:.3f} ms" for q, c, m in failures), sep="\n  ")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
