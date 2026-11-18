# Reservation without counting the free rows (2026-11-18)

**Question:** how much faster is the reservation (`POST /api/bucket/{bucket}/reserve`) when it doesn't count all free
rows still matching the rules (`available` in the response)? The previous measurement
([2026-11-18-1m-rows-tags](../2026-11-18-1m-rows-tags/README.md)) showed that count takes most of the step.

**Answer:** with `count=false` (new in databucket-server, branch `feature/reserve-count-option`) the reservation is
**~10× faster: p95 155 → 13 ms (properties with indexes), 148 → 15 ms (tag)**, and the whole mixed load drops to
**p95 12-14 ms** (from 112-121 ms).

## Setup

As in [2026-11-18-1m-rows-tags](../2026-11-18-1m-rows-tags/README.md) (1M users, 10 tags of 100k rows, 20 % reserved
by the admin, 100 virtual users, ramp-up 30 s + 120 s, mix create 40 / search 30 / delete 30), with:

- Databucket built from `feature/reserve-count-option` (3.16.2 + the parameter `count` of `/reserve`).
- The 6 expression indexes on the properties (`type`, `country`, `origin`, `status`, `group`, `age`) in all four runs
  (the tag runs don't use them).
- **Before every run** (new protocol): `./gradlew prepareData -Prows=1000000` (now also deletes the rows created by
  earlier runs and releases rows left reserved by testers: the scenario create makes rows faster than delete removes
  them, see "Note" below) and `VACUUM FULL ANALYZE` of the bucket table.
- `-PcountAvailable=false` makes the scenario search call `/reserve?limit=1&count=false`.

| Run | Folder | Search rules | Count of free rows |
|---|---|---|---|
| B count | [b-properties-indexes-count](b-properties-indexes-count/report/index.html) | `premium-pl` (properties) | yes (as before) |
| B no count | [b-properties-indexes-no-count](b-properties-indexes-no-count/report/index.html) | `premium-pl` | **no** |
| D count | [d-tags-count](d-tags-count/report/index.html) | tag `perf-tag-03` | yes |
| D no count | [d-tags-no-count](d-tags-no-count/report/index.html) | tag `perf-tag-03` | **no** |

## Results

All runs: 0 failed requests.

| | B count | B no count | D count | D no count |
|---|---|---|---|---|
| requests | 16,936 | 17,742 | 17,357 | 17,694 |
| throughput | 110.7 req/s | 116.0 req/s | 113.4 req/s | 115.7 req/s |
| p50 | 24 ms | **9 ms** | 13 ms | **10 ms** |
| p95 | 121 ms | **12 ms** | 112 ms | **14 ms** |
| p99 | 151 ms | **18 ms** | 136 ms | **21 ms** |
| **search 1. find and reserve** p50 / p95 | 121 / 155 ms | **9 / 13 ms** | 115 / 148 ms | **11 / 15 ms** |

Throughput hardly changes: it is set by the pauses of the virtual users (500-2000 ms between the steps), not by the
server; the response times show the gain.

## Conclusions

1. **Counting the free rows is ~90 % of a reservation** when many rows match (80,000 for the tag, ~24,000 for
   `premium-pl`): 115-121 ms → 9-11 ms at p50 without it.
2. Clients that don't need `available` (most tests: they reserve and use the row) should send `count=false`. The
   default stays `true` (the response is as before), `limit=0` always counts (the way to ask only for the number).
3. With `count=false` and indexes, tag and properties are equally fast (13 vs 15 ms at p95).

## Note: two runs repeated

- Two runs (B no count, D no count) froze for ~90 s once: no line in the log of Databucket, Caddy answered 502,
  PostgreSQL was in a long checkpoint right after `VACUUM FULL` (rewriting ~400 MB). An I/O stall of the Docker VM on
  the laptop, not Databucket; both runs were repeated (the repeats are in this folder; the frozen ones are not kept).
- A first attempt without the clean-up showed `delete 1. find created users` at p50 2.9 s: ~9,500 rows created by
  earlier runs had piled up at the end of the table (the highest `data_id`), and PostgreSQL chose to walk the primary
  key in order (`ORDER BY data_id LIMIT 20`) expecting the matching rows to be spread evenly: it read the whole
  table (EXPLAIN: 1.1 s). A real case for Databucket too: rules matching only the newest rows, sorted by id ascending.
  The clean-up in prepareData now gives every run the same start.
