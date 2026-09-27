# Current projection performance experiment

## Setup

The benchmark starts from a read-only copy of the personal SQLite database
(971 transactions, 18,213 event-log datoms). It adds deterministic, balanced
two-posting transactions in 5,000-transaction steps, reaching 100,971 total
transactions. Synthetic records use the existing accounts and span 2024–2026.
The source database is not modified.

The read comparison covers 15 representative commands based on the personal
`hledger` Makefile. Each timing is a complete CLI invocation, including process
startup, database open, state loading, report calculation, and JSON output.
At larger sizes the sweep has one sample per command and configuration, so
individual read timings are indicative. Both arms include the same linear-time
posting/tag grouping improvement; this isolates the projection comparison from
an older quadratic association scan.

The write follow-up uses eight independent copies of the 100,971-transaction
snapshot per write command and configuration. The projected seed is backfilled
before those copies are made. Each timed process therefore performs one write
against a fresh database copy instead of accumulating earlier writes in the
same database. Timings include process startup and SQLite open/close, but not
copying the database or the initial projection backfill.

## Read results

The table shows the median across the 15 report commands at each checkpoint.

| Transactions | Event-log fold | Current projection | Change |
|---:|---:|---:|---:|
| 971 | 0.12s | 0.06s | -53.5% |
| 50,971 | 4.88s | 2.67s | -45.3% |
| 100,971 | 9.17s | 4.58s | -50.0% |

The aggregate median first crossed one second at 10,971 transactions without
the projection and at 25,971 with it. It crossed five seconds at 30,971 without
the projection and did not cross five seconds by 100,971 with the projection.
The projection's cold backfill took 12.58 seconds at 100,971 transactions.

Some reports remain expensive because the projection avoids folding the log,
but does not remove report-specific work. At 100,971 transactions,
`balance_sheet_usd` took 15.73s with the projection versus 16.35s without, and
`register_all` took 14.56s versus 17.49s.

## Isolated write results at 100,971 transactions

Values are medians of eight fresh-copy samples. Ranges show the minimum and
maximum observed time.

| Write | Event-log only | With projection |
|---|---:|---:|
| `add_transaction` | 36.2ms (16.7–46.3ms) | 32.2ms (28.2–49.9ms) |
| `add_account` | 24.3ms (15.8–154.2ms) | 34.4ms (28.0–48.7ms) |
| `add_price` | 22.5ms (17.0–26.8ms) | 35.0ms (29.8–43.2ms) |

These isolated samples show a modest write cost for account and price updates,
not the roughly 180ms cluster seen in an earlier run that repeated writes in
one database copy. That earlier pattern was not reproduced with fresh copies;
its precise cause is unknown. The 154ms baseline account sample also shows
substantial timing noise. No `VACUUM`, `REINDEX`, or `ANALYZE` operation is
issued by the timed write path. The measured `wal_autocheckpoint` setting was
1,000 pages; observed post-process WAL sidecars were generally tens of
kilobytes, though these observations cannot rule out a transient checkpoint.

## Takeaway

The current projection cuts the aggregate read median roughly in half at
100,971 transactions, with a one-time backfill cost. Isolated writes add about
10–13ms to the account and price command medians in this experiment. More
write samples on the target machine would help characterize tail latency.
