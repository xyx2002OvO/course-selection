# Transaction and admission comparison

> Historical experiment notes. On 2026-09-10 the application and loadtest entry
> point were restored to the 12-partition / 6-consumer / batch-100 version at the
> user's request. The switches and comparison commands below are no longer active.
> The pre-rollback sources and scripts are archived in
> `out/before-rollback-20260910-101531.zip`. Historical run data is unchanged.

The original historical report remains in `out/REPORT.md`. New runs write to
`out/<timestamp>-<id>/`; never use a prior run's summary as the current result.

## Changes

- Confirmation concurrency defaults to 3, independently of result projection.
  Kafka retains 12 partitions. API and worker pools remain at 16 in loadtest.
- `CONFIRM_SUMMARY_QUERY=false` selects the original three rule queries; `true`
  selects the aggregate query. Both retain the student lock and READ_COMMITTED.
- `ADMISSION_FAST_PATH=true` attempts a plain INSERT. A newly inserted request
  skips the subsequent locked read. A duplicate still locks and validates the
  stored identity and state. MySQL affected-row flags are not used to distinguish
  inserts. Lock failures/deadlocks propagate for retry; they are not duplicates.
  The switch is initially off pending load-test selection.
- `BACKLOG_HEADROOM_SECONDS=30` checks the earliest due score atomically in the
  reservation Lua script. New reservations receive 429 before writing stock or
  leases when that deadline is within 30 seconds. Existing IDs remain retryable.
  A value of 0 disables this guard for capacity comparisons. The due index also
  includes committed results awaiting projection: this is conservative protection,
  not an exact estimate of database queue latency. It does not guarantee completion
  before expiry or replace reconciliation.
- `selection.confirm.duration` measures the proxied confirmation call, including
  connection acquisition and transaction commit/rollback, for both success and
  failure. It is not a unique-success counter; retries can contribute samples.

## Controlled runs

All variants use 15 seconds at 50 requests/s, followed by 60 seconds at 400,
12 courses, disjoint student ranges, and a 1-second client timeout.

| Variant | Rule query | Confirm concurrency | Fast admission | Backlog headroom |
| --- | --- | --- | --- | --- |
| A | Original | 3 | Off | 0 |
| B | Aggregate | 3 | Off | 0 |
| C | Aggregate | 4 | Off | 0 |
| D | Aggregate | 3 | On | 0 |
| E | Aggregate | 3 | On | 30 seconds |

Run from the project directory. Each run resets only this demo's compose stack
and its database/Redis volumes, just as the original loadtest runner did.

```powershell
.\loadtest\run.ps1 -Rebuild -Scenario pipeline -Variant A
.\loadtest\compare.ps1 -Variants A,B,C -Repeats 3
.\loadtest\compare.ps1 -Variants B,D,E -Repeats 3
.\loadtest\summarize.ps1 | Format-Table -AutoSize
```

Do not run integration tests or another load generator concurrently with the
comparison. Review the resolved compose configuration in each run directory.
Changing worker pool size is a separate experiment; named variants fix it at 16.

## Evidence and acceptance

- `pipeline-summary.json`: k6 metrics with a steady-phase breakdown. 202 counts
  divided by 60 give steady HTTP admission throughput. 200 is tracked separately.
  429, timeouts, rejected checks and dropped iterations never count as admission.
- `k6-events.log`: includes `LOAD_START_MS`, taken inside k6 setup. Host launch
  timestamps include container startup and must not be used as the load window.
- `database.csv`: timestamped request counts. Use SUCCESS deltas divided by actual
  elapsed sample time within the steady window. `summarize.ps1` reports that
  sampled window explicitly; it is not the whole test or the drain rate.
- `metrics.jsonl`: API/worker connection pressure and confirmation call timing.
  Sampling gaps are recorded. Sampling itself consumes resources and must be
  enabled identically across variants.
- `database-at-stop.txt`, `database-final.txt`: state, outbox and stock checks.
  These are multiple statements; counts can advance between them while draining.
  `invariant-violations.txt` checks stock/enrollment/request/result consistency
  in one statement. A zero count does not establish fault-recovery correctness.
- `k6-exit-code.txt`, `sampling-error.txt`, `first-failure.json`: failures remain
  visible. The runner exits with an error for failed thresholds, incomplete drain,
  sampling failure, or database invariant violations.

The initial SLO is >=99% on-time steady admission, p95 <1 second and zero dropped
iterations. Also compare cancellation count, oldest pending age and terminal
latency; a faster drain after sacrificing admission is not a throughput win.
Backlog-protected runs are intentionally separate from unthrottled capacity runs.
