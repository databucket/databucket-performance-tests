# Databucket performance tests

Load tests of the Databucket API with [Gatling](https://gatling.io) (Java DSL), run with Gradle. They are run by hand
against a Databucket you start yourself; no CI runs them.

## Running

Start Databucket, e.g. the docker compose of [databucket-server](https://github.com/databucket/databucket-server)
(http://databucket.localhost, user `super` / `super`). Then, in this repository:

```
./gradlew prepareData -Prows=100000          # project "perf", user "perf-admin", bucket "orders" with 100k orders
./gradlew gatlingRun --simulation pl.databucket.perf.CreateModifyReleaseSimulation
./gradlew gatlingRun --simulation pl.databucket.perf.SearchModifyReleaseSimulation -Prules=paid-warszawa
```

The report: `build/reports/gatling/<simulation>-<time>/index.html` (the path is printed at the end). The run fails
(`BUILD FAILED`) when the assertions fail: 95th percentile above `-Pp95`, or more failed requests than `-PmaxFailedPercent`.

`prepareData` is idempotent: it creates what is missing and inserts orders until the bucket has `-Prows` rows (by the
API, 1000 per call; ~4 s per 20k rows locally). Run it again with a higher `-Prows` to grow the bucket.

## Scenarios

**1. Create, modify, release** (`CreateModifyReleaseSimulation`)

1. insert an order, reserved by the user (`POST /api/bucket/orders`, `reserved: true`)
2. random pause (`-PdelayMin` … `-PdelayMax` ms)
3. modify the row (`PUT /api/bucket/orders/{id}`, `propertiesToSet`)
4. random pause
5. modify the row and release it (`PUT`, `propertiesToSet` + `reserved: false`)

**2. Search, modify, release** (`SearchModifyReleaseSimulation`)

1. find a free row matching the rules and reserve it: `POST /api/bucket/orders/reserve?limit=1` with the rules (search
   and reservation in one call, safe when many users take rows at the same time; `-Psort=id` or `random`)
2. random pause
3. modify the row
4. random pause
5. modify the row and release it

No free row matching the rules is a failed request (KO), followed by a pause (the user doesn't retry at once).

The scenarios change only `$.perf` (`step`, `at`), never the fields the rules use, so the data doesn't run out.

### Rule sets

`src/gatling/resources/rules.json`, chosen before the test with `-Prules=<name>` (default `new-orders`). Add your own:

```json
"paid-warszawa": {
  "description": "PAID orders from Warszawa",
  "rules": [["$.status", "==", "PAID"], ["$.city", "==", "Warszawa"]]
}
```

| Name | Rules |
|---|---|
| `new-orders` | status NEW (5 %) |
| `paid-warszawa` | two conditions (and) |
| `customer` | one customer: very few rows (1 in 20k, 20 in 1M), shows what happens when rows run out |
| `total-range` | number range |
| `shipped-or-cancelled-krakow` | `or` + a condition |
| `email-like` | `like` |
| `item` | value in an array (`["sku-42", "in", "$.items"]`) |

A rule set must match at least as many rows as there are users, otherwise reservations fail (KO).

### The data

Orders as in `Orders.java`: row n has customer `customer-(n % 50000)`, status NEW 5 % / PAID 60 % / SHIPPED 30 % /
CANCELLED 5 %, total 0-1000, one of six cities, email, address, two items.

## Settings

`-Pname=value` (or environment variables `PERF_NAME`, e.g. `PERF_BASE_URL`); defaults in brackets.

| Setting | |
|---|---|
| `baseUrl` (`http://databucket.localhost`) | Databucket under test |
| `user`, `password` (`perf-admin`, `Perf-Passw0rd!`) | the user of the API (created by prepareData as ADMIN) |
| `project`, `bucket` (`perf`, `orders`) | where the data is |
| `users` (10) | virtual users working at the same time (closed model: a finished user is replaced) |
| `rampUp` (10), `duration` (60) | seconds to reach `users`, then seconds of steady load |
| `delayMin`, `delayMax` (500, 2000) | the random pause between the steps, ms |
| `rules` (`new-orders`), `sort` (`id`) | scenario 2: the rule set and the order of the reserved rows |
| `p95` (1000), `maxFailedPercent` (1) | assertions |
| `rows` (100000), `history` (true) | prepareData: rows in the bucket, history of a new bucket |
| `superUser`, `superPassword` (`super`, `super`) | prepareData: the SUPER admin creating the project and the user |

All virtual users share one token (signed in before the load, not measured).

Examples:

```
./gradlew gatlingRun --simulation pl.databucket.perf.SearchModifyReleaseSimulation -Prules=total-range -Pusers=50 -Pduration=300
./gradlew gatlingRun --simulation pl.databucket.perf.CreateModifyReleaseSimulation -PdelayMin=0 -PdelayMax=0 -Pusers=20
```

Failed requests with their responses in the console: set `io.gatling.http.engine.response` to `DEBUG` in
`src/gatling/resources/logback.xml`.
