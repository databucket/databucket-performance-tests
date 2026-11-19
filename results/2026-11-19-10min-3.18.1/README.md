# 10 minutes on Databucket 3.18.1 (2026-11-19)

**Question:** does the load stay as fast over **10 minutes** (instead of 2) on the released Databucket 3.18.1, with the
indexes set in the bucket's settings (indexed properties, new in 3.18.0) instead of by hand, and with `/get` without
counting (new in 3.18.1)?

**Answer:** yes. **p50 10 ms, p95 15 ms, p99 19 ms, 126 req/s, 0 failed requests of 79,915**, steady from the 2nd
minute to the end (p95 13-15 ms every minute). Practically the same as the 2-minute run of 2026-11-18 (p95 12 ms), with
4.5× more requests.

## Setup

As run "B no count" of [2026-11-18-reserve-without-count](../2026-11-18-reserve-without-count/README.md) (laptop,
docker compose, PostgreSQL 18 defaults, 1,000,000 users, 20 % reserved by the admin, 100 virtual users, mix
create 40 / search 30 / delete 30, pauses 500-2000 ms, rules `premium-pl`, reservation with `count=false`), with:

- **Databucket 3.18.1** (released; the 2026-11-18 run used the branch with `/reserve?count`, 3.16.2 + 1 change).
- **Indexes from the bucket's settings**: the six indexes made by hand on 2026-11-18 were dropped and the bucket got
  `indexedProperties` `type`, `country`, `origin`, `status`, `group` (text) through the API, as a user does it in
  Settings → Buckets (at most 5; `age` isn't used by these rules). Saving the bucket built the 5 indexes on 1M rows in
  3.7 s. These indexes are `text_pattern_ops` and the rules write an indexed path as a literal (3.18.0).
- **`-PcountTotal=false`** (new setting): the scenario delete finds the created users with `get?limit=20&count=false`.
- **Duration 600 s** (instead of 120) after a ramp-up of 30 s.
- Before the run: `./gradlew prepareData -Prows=1000000` (clean-up), `VACUUM FULL ANALYZE` and `CHECKPOINT` of the
  bucket table.

Command: `./gradlew gatlingRun -Pusers=100 -PrampUp=30 -Pduration=600 -Prules=premium-pl -PcountAvailable=false
-PcountTotal=false` → [run](run/report/index.html) (settings, console).

## Results

0 failed requests.

| | 2026-11-18 B no count (2 min) | **2026-11-19, 3.18.1 (10 min)** |
|---|---|---|
| requests | 17,742 | **79,915** |
| throughput | 116.0 req/s | **126.1 req/s** |
| p50 | 9 ms | **10 ms** |
| p95 | 12 ms | **15 ms** |
| p99 | 18 ms | **19 ms** |
| max | 175 ms | **131 ms** |

Per step, p50 / p95 (ms):

| Step | 2026-11-18 (2 min) | 2026-11-19 (10 min) |
|---|---|---|
| search 1. find and reserve | 9 / 13 | 11 / 16 |
| delete 1. find created users | 8 / 10 | 8 / 11 |
| create 1. insert a user (reserved) | 8 / 11 | 10 / 14 |
| modify / modify and release (both scenarios) | 9 / 12-13 | 11 / 15 |
| delete 3. delete a user | 8 / 11 | 8 / 13 |

Over time (from the report's chart "Response Time Percentiles over Time", averages per minute):

| minute | 0 (ramp-up) | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| p50 | 14 | 10 | 10 | 10 | 10 | 10 | 10 | 10 | 10 | 10 | 10 |
| p95 | 32 | 13 | 14 | 15 | 14 | 13 | 13 | 14 | 14 | 13 | 15 |
| req/s | 97 | 129 | 129 | 130 | 131 | 130 | 129 | 130 | 131 | 129 | 118 |

## Conclusions

1. **No slow-down over time**: after the ramp-up every minute has p50 10 ms and p95 13-15 ms. The longer run doesn't
   reveal anything the 2-minute run hid.
2. **The indexes set in the bucket's settings work as well as those made by hand** (same response times), and they
   are built in seconds when the bucket is saved.
3. **Dead rows don't matter here**: after the run the table had 48,103 dead row versions (history table: 21,012), below
   the threshold of autovacuum (20 %), which didn't run, and the response times didn't grow. Matches the separate
   measurement of 2026-11-19 (400k dead versions: `/get` and reservation unchanged), so autovacuum isn't tuned.
4. Throughput is still set by the pauses of the virtual users, not by the server (the 10 % higher req/s than on
   2026-11-18 is the longer full-load part, not a faster server).
5. The +1-3 ms against 2026-11-18 is within the noise of a laptop with Docker; `/get?count=false` for delete (8 / 11 ms)
   changes little here, because the created rows waiting for delete are few (the count is cheap). It matters when
   many rows match (1M rows, 650,000 matching: 195 → 30 ms, see databucket-server PR #262).
