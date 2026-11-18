# 1M rows: rules on a tag vs rules on properties (2026-11-18)

**Question:** how does a load that finds and reserves rows only by their **tag** (a column of the table with its own
index) compare with one that uses rules on **JSON properties**, without and with indexes on those properties?

**Answer:** rules on a tag need no extra index and are **~35× faster at p95 than properties without indexes
(4853 → 130 ms)**, but properties **with** expression indexes are faster still (50 ms, 114 vs 81 req/s): finding the
row is quick in both, the difference is the **count of all free matching rows that every reservation makes**
(`available` in the response): 80,000 rows of the tag vs ~24,000 of the property rule.

Repeats the measurement [2026-11-18-1m-rows-indexes](../2026-11-18-1m-rows-indexes/README.md) on the same data after
it got tags, with `VACUUM FULL` before every run (see "Bloat" below: it changed the results of that measurement).

## Setup

As in [2026-11-18-1m-rows-indexes](../2026-11-18-1m-rows-indexes/README.md) (laptop, docker compose, Databucket 3.16.2,
PostgreSQL 18 defaults, `max-concurrent-requests` 30), with these changes of the data and the runs:

- **Tags** (`./gradlew prepareData`, idempotent): `perf-tag-01` … `perf-tag-10` on the 1,000,000 rows of prepareData,
  by the last digit of `$.group`: **100,000 rows per tag**, ~20,000 of them reserved by the admin (20 % overall). Tag
  `perf-created` for rows created during a run with `-Pfilter=tags`. (10 updates by rules through the API, 2 min.)
- **`VACUUM FULL ANALYZE`** of the bucket table before every run: the same physical state (no dead rows) for all.
- Load as before: 100 virtual users, ramp-up 30 s + 120 s, mix create 40 % / search 30 % / delete 30 %, pauses
  500-2000 ms; `settings.txt` of each run has the exact rules.

| Run | Folder | Rules of search (find and reserve) / delete (find created rows) | Indexes |
|---|---|---|---|
| A2 | [a2-properties-no-indexes](a2-properties-no-indexes/report/index.html) | `premium-pl`: `$.type == PREMIUM`, `$.country == PL` (~3 %) / `$.origin == perf-create`, `reserved == false` | standard only: `data_id`, `(tag_id, reserved)` |
| B2 | [b2-properties-indexes](b2-properties-indexes/report/index.html) | as A2 | + expression indexes on `type`, `country`, `origin`, `status`, `group`, `age` |
| D | [d-tags-no-property-indexes](d-tags-no-property-indexes/report/index.html) | `tagId == perf-tag-03` (10 %) / `tagId == perf-created`, `reserved == false` | standard only |

Commands: A2 and B2 `./gradlew gatlingRun -Pusers=100 -PrampUp=30 -Pduration=120 -Prules=premium-pl`,
D `./gradlew gatlingRun -Pusers=100 -PrampUp=30 -Pduration=120 -Pfilter=tags -Ptag=perf-tag-03`.

## Results

All runs: 0 failed requests.

| | A2: properties, no indexes | D: tag | B2: properties + indexes |
|---|---|---|---|
| requests | 6,188 | 12,444 | 17,509 |
| throughput | 39.9 req/s | 81.3 req/s | **114.4 req/s** |
| p50 | 930 ms | 15 ms | **14 ms** |
| p95 | 4,853 ms | 130 ms | **50 ms** |
| p99 | 5,790 ms | 242 ms | **71 ms** |

95th percentile per step (ms):

| Step | A2 | D | B2 |
|---|---|---|---|
| search 1. find and reserve | 5,690 | 203 | **71** |
| delete 1. find created users | 5,897 | 22 | **20** |
| create 1. insert a user (reserved) | 1,619 | 30 | **22** |
| modify / modify and release (both scenarios) | ~1,500-1,600 | 30-38 | **23** |
| delete 3. delete a user | 1,579 | 24 | **21** |

## Conclusions

1. **A tag is the cheapest way to make a big bucket searchable**: the existing `(tag_id, reserved)` index does the work,
   nothing to set up (D vs A2: p95 4853 → 130 ms, throughput ×2).
2. **Properties with expression indexes are as fast or faster** (B2) and keep the flexibility of the rules: the
   "indexed properties" feature is worth it.
3. **The reservation counts all free matching rows every time** (`available` in the response of `/reserve`,
   `DataService.countFreeData`): for the tag that is ~80,000 rows, **77 ms of the ~130 ms** of the step (EXPLAIN: picking
   the row itself `ORDER BY data_id LIMIT 1 FOR UPDATE SKIP LOCKED` takes 0.06 ms). The more rows match, the slower
   the reservation, even with indexes. Ideas: count only on request (a parameter), or count up to a limit
   (`SELECT count(*) FROM (... LIMIT 1000)`, "1000+").
4. **Bloat matters.** The first measurement (B, same indexes as B2) had the reservation at p95 171 ms and the whole
   load at 135 ms; after `VACUUM FULL` (B2) 71 ms and 50 ms. The 200,000 reservations of prepareData and the earlier
   runs had left the table with dead rows that every search had to skip. Autovacuum defaults are too lazy for a table
   updated this much (every reservation and release is an UPDATE, with history a second write) - worth tuning
   (`autovacuum_vacuum_scale_factor` per bucket table) or documenting.
5. A2 is slower than A of the first measurement (p95 4853 vs 3158 ms) although the table was compact: run-to-run
   variance of a saturated system on a laptop (all 30 DB connections busy with full scans); the conclusion (no indexes
   = seconds) stands.

## Files

Each run folder: `report/index.html` (Gatling report), `settings.txt`, `console.txt`.
