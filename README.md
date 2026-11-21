# Databucket performance tests

Load tests of the Databucket API with [Gatling](https://gatling.io) (Java DSL), run with Gradle. They are run by hand
against a Databucket you start yourself; no CI runs them.

**Results:** the measurements on 1 million records are in [results](results/README.md); their Gatling reports open in the
browser at **https://databucket.github.io/databucket-performance-tests/** (GitHub Pages), and what they mean is on the
[Performance](https://github.com/databucket/databucket-server/wiki/Performance) page of the Databucket wiki.

## Running

Start Databucket, e.g. the docker compose of [databucket-server](https://github.com/databucket/databucket-server)
(http://databucket.localhost, user `super` / `super`). Then, in this repository:

```
./gradlew prepareData                      # project, accounts, bucket "users" with 100k rows, 20 % reserved
./gradlew gatlingRun                       # 100 virtual users, mix create 40 % / search 30 % / delete 30 %
./gradlew gatlingRun -Pusers=500 -Pmix=create:20,search:70,delete:10 -Prules=premium-pl -Pduration=600
```

The report: `build/reports/gatling/databucketsimulation-<time>/index.html` (the path is printed at the end). The run
fails (`BUILD FAILED`) when the assertions fail: 95th percentile above `-Pp95`, or more failed requests than
`-PmaxFailedPercent`.

Keep a report with its settings: `./gradlew keepReport -Pname=<measurement>/<run>` → `results/<measurement>/<run>/`
(see [results](results/README.md)).

## How a test runs

- **Accounts**: a pool of `-Ptesters` accounts (`perf-tester-0001` …, ROBOT in the project, created by prepareData).
  Every virtual user takes the next account and **signs in with it** (its own token). With more users than accounts the
  accounts are used again from the first one.
- **Start**: the users of every scenario start evenly within `-PrampUp` seconds, each after a random wait of up to
  `-PstartJitter` ms, so they don't sign in at the same moment.
- **Mix**: `-Pmix=create:40,search:30,delete:30` splits the `-Pusers` among the scenarios (the shares add up to 100).
  `-Pmix=search:100` runs one scenario only.
- Every user repeats its scenario until `rampUp + duration` is over; the rounds running then are finished.
- A failed step ends the round (the rest is skipped) and is followed by a pause.

## Scenarios

Between the steps: a random pause of `-PdelayMin` … `-PdelayMax` ms.

**create** — a user is created, changed and released

1. insert a user, reserved (`POST /api/bucket/users`, `reserved: true`; marked `origin: "perf-create"`)
2. pause
3. modify it (`PUT /api/bucket/users/{id}`, `propertiesToSet`)
4. pause
5. modify it and release the reservation (`PUT`, `propertiesToSet` + `reserved: false`)

**search** — a user is found by rules and reserved, changed and released

1. find a free user matching the rules and reserve it, in one call (`POST /api/bucket/users/reserve?limit=1`
   with the rules of `-Prules`); no free user is a failed request (KO)
2. pause
3. modify it
4. pause
5. modify it and release the reservation

**delete** — users created by the scenario create are found and deleted

1. find users created by the scenario create and already released by it (`POST /api/bucket/users/get?limit=20`,
   rules `origin == perf-create` and `reserved == false`)
2. pause (when nothing is found: only the pause, and the next round)
3. delete one of them, chosen at random (`DELETE /api/bucket/users/{id}`; a user deleted by another tester in the
   meantime is not an error: `Removed 0 data row(s)`)

The scenario delete deletes only what the scenario create has finished, so it **never deletes faster than users are
created** (it waits when there is nothing to delete). The rows of prepareData are never deleted.

The scenario search takes only the rows of prepareData (its rules get `["$.origin", "==", null]` added), so it doesn't
compete with create and delete for their rows. The scenarios change only `$.perf` (`step`, `by`, `at`), never the
fields the rules use, so the data doesn't run out.

`-Psort=id` (default) reserves the free row with the lowest id: after release the same rows are taken again (in a 60 s
test with 30 search users: ~800 reservations of 30 rows). `-Psort=random` spreads the reservations over the whole
bucket (a costlier query: it is part of what is measured).

### Filter: properties or tags

`-Pfilter=properties` (default) uses the rule set `-Prules` (below) for search and `$.origin` for delete.
`-Pfilter=tags` uses only tags: search reserves rows of the tag `-Ptag` (default `perf-tag-03`; prepareData spreads
`-Ptags` = 10 tags over its rows, 10 % each), create gives new rows the tag `perf-created` and delete finds them by it.

### Rule sets

`src/gatling/resources/rules.json`, chosen before the test with `-Prules=<name>` (default `active`). Add your own:

```json
"premium-pl": {
  "description": "PREMIUM users from PL",
  "rules": [["$.type", "==", "PREMIUM"], ["$.country", "==", "PL"]]
}
```

| Name | Rules |
|---|---|
| `active` | status ACTIVE (60 %) |
| `new` | status NEW (10 %) |
| `premium-pl` | two conditions (and) |
| `age-range` | number range |
| `blocked-or-inactive-de` | `or` + a condition |
| `group` | one group: few rows (20 in 100k), shows what happens when free rows run out |
| `login-like` | `like` |
| `seller` | value in an array (`["seller", "in", "$.roles"]`) |

A rule set must match more free rows than there are search users, otherwise reservations fail (KO).

## The data (prepareData)

`./gradlew prepareData` is idempotent: it creates what is missing and can be run again with other numbers.

- project `-Pproject` (`perf`), its ADMIN `-Padmin` (`perf-admin`) and `-Ptesters` accounts (ROBOT), all with
  `-Ppassword`; 1000 accounts take ~45 s,
- bucket `-Pbucket` (`users`, history on; `-Phistory=false` for a new bucket without history),
- `-Prows` users (by the API, 1000 per call; ~10 s per 100k locally), as in `TestUsers.java`: login, email, status
  ACTIVE 60 % / NEW 10 % / BLOCKED 15 % / INACTIVE 15 %, type PREMIUM 20 %, one of six countries, age 18-79,
  group (5000 groups), two roles,
- `-PreservedPercent` (20) of them reserved by the admin at the start: not available to the testers,
- clean-up: rows created by earlier runs deleted, rows left reserved by testers released (run prepareData before every
  measured run, so they all start from the same state; add `VACUUM FULL ANALYZE` of the table for the same physical state).

## Settings

`-Pname=value` (or environment variables `PERF_NAME`, e.g. `PERF_BASE_URL`); defaults in brackets.

| Setting | |
|---|---|
| `baseUrl` (`http://databucket.localhost`) | Databucket under test |
| `project`, `bucket` (`perf`, `users`) | where the data is |
| `testers` (1000), `testerPrefix` (`perf-tester-`), `password` (`Perf-Passw0rd!`) | the pool of accounts |
| `users` (100) | virtual users, each with its own account |
| `rampUp` (30), `duration` (120) | seconds to start all users, then seconds of full load |
| `startJitter` (1000) | random wait of a user before signing in, ms |
| `mix` (`create:40,search:30,delete:30`) | share of the users per scenario, % |
| `delayMin`, `delayMax` (500, 2000) | random pause between the steps, ms |
| `filter` (`properties`), `tag` (`perf-tag-03`) | rules on properties or only on tags (see above) |
| `rules` (`active`), `sort` (`id`) | scenario search: rule set, order of the reserved rows (`id`, `random`) |
| `countAvailable` (true) | scenario search: `false` reserves with `count=false` (no count of the free rows; Databucket 3.17.0+) |
| `countTotal` (true) | scenario delete: `false` finds the created users with `get?count=false` (no count of all matching rows; Databucket 3.18.1+) |
| `p95` (1000), `maxFailedPercent` (1) | assertions |
| `rows` (100000), `reservedPercent` (20), `tags` (10), `history` (true), `admin` (`perf-admin`) | prepareData |
| `superUser`, `superPassword` (`super`, `super`) | prepareData: the SUPER admin creating the project and accounts |

Failed requests with their responses in the console: set `io.gatling.http.engine.response` to `DEBUG` in
`src/gatling/resources/logback.xml`.
