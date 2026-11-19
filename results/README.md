# Results

Kept runs, one folder per measurement: a `README.md` with the question, the setup and the conclusions, and a folder
per run with its Gatling report (`report/index.html`, opens from the disk), its settings and console output.

Keep a run: `./gradlew keepReport -Pname=<measurement>/<run>` right after `gatlingRun` (copies the last report and its
`settings.txt`); add the console output and describe the measurement in its `README.md`.

| Date | Measurement | Result |
|---|---|---|
| 2026-11-18 | [1M rows: without and with indexes on the properties](2026-11-18-1m-rows-indexes/README.md) | p95 3158 → 135 ms, throughput 59 → 113 req/s |
| 2026-11-18 | [1M rows: rules on a tag vs rules on properties](2026-11-18-1m-rows-tags/README.md) (repeated with `VACUUM FULL` before each run) | p95: properties without indexes 4853 ms, tag 130 ms, properties with indexes 50 ms |
| 2026-11-18 | [Reservation without counting the free rows](2026-11-18-reserve-without-count/README.md) (`/reserve?count=false`, server branch feature/reserve-count-option) | reservation p95 155 → 13 ms; whole load p95 121 → 12 ms |
| 2026-11-19 | [10 minutes on Databucket 3.18.1](2026-11-19-10min-3.18.1/README.md) (indexes from the bucket's settings, `/get` and `/reserve` without counting) | p50 10 ms, p95 15 ms, 126 req/s, 0 failed of 79,915; steady every minute |
