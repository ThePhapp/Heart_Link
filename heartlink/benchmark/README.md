# Heartlink benchmark baseline

This directory contains the reproducible workload used by Phase 2 tasks T01 and T02.
The benchmark is intended to compare Phase 1 and Phase 2 on the same source
revision, runtime settings, dataset shape, random trace, and response classes.

## Reproducibility contract

Before running the notebook, record these values in the generated `env.json`:

- source repository, branch, and pinned commit (`REF`);
- Java, PostgreSQL, operating system, CPU and memory;
- JVM options, PostgreSQL options, application port and database port;
- dataset size, benchmark duration, warm-up duration, and random seed;
- scenario-specific concurrency and data-shape settings.

The notebook must use a pinned commit when `REF` is provided. A moving branch
may be used only for an exploratory run and must be labelled as such in the
results. Quick mode is a pipeline smoke test; its numbers must not be used as
performance evidence.

## T02 baseline evidence contract

The reportable baseline must run the pinned notebook with `HL_QUICK` unset. The
focused evidence set is:

- S1 recommendation latency against swipe-history size, including `EXPLAIN
  (ANALYZE, BUFFERS)` output;
- S4 uniform versus Zipf hot-profile contention;
- S7e polling load, plus the existing S3 correctness smoke checks.

Every request record must retain its HTTP status and one response class:
`created_201`, `conflict_409`, `rate_limited_429`, `unavailable_503`,
`other_2xx`, `other_4xx`, `other_5xx`, `timeout_or_exception`, or `other`.
The result directory must contain raw request files, `summary.csv`,
`status_counts.csv`, `explain_recommendations.json`, `env.json`,
`evidence_manifest.json`, and `baseline_report.md`.

The notebook validates that each summary row is backed by the matching raw
request rows. Set `HL_REPLAY_CASE` to a scenario name (for example
`S4_zipf`) to rerun one deterministic case and write a separate replay raw
file. A replay is evidence only when it uses the same pinned revision,
configuration, seed, and dataset reset contract.

The report must state whether it is a Kaggle or local run and must not compare
local CPU/RAM numbers as if they were Kaggle measurements. Missing application
metrics are labelled as external process measurements rather than inferred
from latency alone.

## Dataset isolation

Each scenario owns a separate ID range or creates a fresh database state before
it runs. A reset must affect only the benchmark database. Scenario setup must be
safe to repeat, and a failed scenario must not silently become input data for a
later scenario.

## Trace and result rules

- Random choices are derived from the configured seed and scenario name.
- Store the effective configuration and source revision beside every result.
- Keep raw request records, SQL plans, and summary tables together.
- Report HTTP 201, 409, 429, 503, timeout, and 5xx separately.
- Do not count rejected requests as successful throughput.
- Label local runs separately from Kaggle runs; do not compare them as the same
  machine.

## Suggested commands

Run the notebook sequentially. Set `HL_QUICK=1` only to verify the pipeline:

```bash
HL_QUICK=1 jupyter nbconvert --to notebook --execute benchmark/heartlink_kaggle_bench.ipynb \
  --output /tmp/heartlink-benchmark-smoke.ipynb
```

For a reportable run, leave `HL_QUICK` unset, set `HL_REF` to the commit being
measured, and preserve the generated results directory and `env.json`.

To verify one deterministic replay after the full run, set for example
`HL_REF=<commit> HL_REPLAY_CASE=S4_zipf` and preserve the generated
`raw_S4_zipf_*_replay.csv.gz` plus `replay_summary.csv`.
