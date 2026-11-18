# Results

Kept runs, one folder per measurement: a `README.md` with the question, the setup and the conclusions, and a folder
per run with its Gatling report (`report/index.html`, opens from the disk), its settings and console output.

Keep a run: `./gradlew keepReport -Pname=<measurement>/<run>` right after `gatlingRun` (copies the last report and its
`settings.txt`); add the console output and describe the measurement in its `README.md`.

| Date | Measurement | Result |
|---|---|---|
| 2026-11-18 | [1M rows: without and with indexes on the properties](2026-11-18-1m-rows-indexes/README.md) | p95 3158 → 135 ms, throughput 59 → 113 req/s |
