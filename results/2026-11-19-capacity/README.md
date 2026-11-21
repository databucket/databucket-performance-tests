# Capacity: how many reservations per second (2026-11-19)

**Question:** how many reservations per second can Databucket 3.18.1 handle at most, on 1M rows, when the virtual
users don't pause?

**Answer:** about **220 reservation cycles per second** (reserve → modify → modify and release, ~670 requests/s) with
`sort=id`, reached at **50 users** (p95 108 ms); more users don't give more, only longer response times. With
`sort=random` about **80 per second** (the random order is the costly part). This is the capacity of one laptop running
Databucket, PostgreSQL, Caddy and Gatling together, with the defaults (30 concurrent requests / DB connections).

## Setup

- Databucket 3.18.1, data and indexes as in [2026-11-19-10min-3.18.1](../2026-11-19-10min-3.18.1/README.md)
  (1,000,000 users, 20 % reserved by the admin, indexed properties `type`, `country`, `origin`, `status`, `group`).
- **Only the scenario search** (`-Pmix=search:100`): find a free user by `premium-pl` and reserve it
  (`/reserve?limit=1&count=false`), modify it, modify it and release it. **No pauses** (`-PdelayMin=0 -PdelayMax=0`):
  every user sends its next request as soon as the previous one is answered.
- Levels of 10, 50, 100 and 200 users, each ramp-up 10 s + 120 s; series `sort=id` (the free row with the lowest id:
  all users go for the same first free rows and skip the ones locked by others) and `sort=random` (reservations spread
  over the whole bucket). `prepareData` (clean-up) before every run.
- Command per level: `./gradlew gatlingRun -Pmix=search:100 -Pusers=<n> -PrampUp=10 -Pduration=120 -PdelayMin=0
  -PdelayMax=0 -Prules=premium-pl -PcountAvailable=false -Psort=<id|random> -Pp95=100000 -PmaxFailedPercent=100`
  (the assertions relaxed so that a saturated level still finishes).

## Results

Reservations per second = reservations of the run / 130 s. 0 failed requests except where noted.

| sort | users | reservations | **reservations/s** | requests/s | p50 | p95 | p99 | report |
|---|---|---|---|---|---|---|---|---|
| id | 10 | 23,435 | **180** | 541 | 17 ms | 22 ms | 25 ms | [id-10-users](https://databucket.github.io/databucket-performance-tests/results/2026-11-19-capacity/id-10-users/report/index.html) |
| id | 50 | 29,006 | **223** | 670 | 69 ms | 108 ms | 132 ms | [id-50-users](https://databucket.github.io/databucket-performance-tests/results/2026-11-19-capacity/id-50-users/report/index.html) (repeated, see note) |
| id | 100 | 27,134 | **209** | 627 | 139 ms | 278 ms | 405 ms | [id-100-users](https://databucket.github.io/databucket-performance-tests/results/2026-11-19-capacity/id-100-users/report/index.html) |
| id | 200 | 26,145 | **201** | 600 | 286 ms | 598 ms | 862 ms | [id-200-users](https://databucket.github.io/databucket-performance-tests/results/2026-11-19-capacity/id-200-users/report/index.html) |
| random | 10 | 7,975 | **61** | 184 | 22 ms | 125 ms | 167 ms | [random-10-users](https://databucket.github.io/databucket-performance-tests/results/2026-11-19-capacity/random-10-users/report/index.html) |
| random | 50 | 9,896 | **76** | 229 | 145 ms | 495 ms | 627 ms | [random-50-users](https://databucket.github.io/databucket-performance-tests/results/2026-11-19-capacity/random-50-users/report/index.html) |
| random | 100 | 10,462 | **80** | 240 | 355 ms | 677 ms | 805 ms | [random-100-users](https://databucket.github.io/databucket-performance-tests/results/2026-11-19-capacity/random-100-users/report/index.html) |
| random | 200 | 10,127 | **78** | 233 | 796 ms | 1,181 ms | 1,431 ms | [random-200-users](https://databucket.github.io/databucket-performance-tests/results/2026-11-19-capacity/random-200-users/report/index.html) |

## Conclusions

1. **The maximum is ~220 reservation cycles per second (~670 requests/s)** with `sort=id`. From 10 to 50 users the
   throughput still grows a little (180 → 223/s) while p50 goes 17 → 69 ms; above 50 it falls slightly (lock waiting
   and context switching) and the response times only grow: at 200 users p95 is 0.6 s.
2. **A test that reserves without pausing should keep to ~50 parallel users** on such a server; beyond that it gets
   no more data, only slower answers. With realistic pauses (the 10-minute run: 0.5-2 s between the steps) 100 users
   need ~11 reservations/s, about 5 % of the capacity.
3. **`sort=random` costs ~3×** (~80/s, p95 already 125 ms at 10 users): every reservation orders the matching free rows
   randomly. Use it only when the test needs randomly chosen rows.
4. No reservation failed and no row was handed out twice under full load (`SKIP LOCKED`).
5. The numbers are for one laptop with everything on it and the default pool of 30; a server with Databucket and
   PostgreSQL on their own machines, or a bigger `DATABUCKET_MAX_CONCURRENT_REQUESTS`, would go higher. Not measured.

## Note: one run repeated

The first run of `sort=id` with 50 users froze for 12.5 minutes (21:59:04-22:11:37 local time): neither Databucket nor
PostgreSQL wrote a single log line in that time (not even the periodic checkpoints), Gatling got 16 timeouts; the whole
Docker VM stood still (the laptop or Rancher Desktop sleeping), not Databucket. The level was repeated; the repeat is
kept here, the frozen run is not.
