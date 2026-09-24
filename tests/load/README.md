# Load tests

k6 scripts for the endpoints whose performance actually matters.

## Status of the numbers in this repository

**NOT MEASURED.** No load test in this directory has been executed, so
`results/` contains no data and no figure appears anywhere in the
documentation. Every performance claim in this project is either backed by a
recorded run or is absent. There are no estimated, extrapolated or
illustrative numbers.

The scripts are complete and runnable. Producing real figures requires a
running backend and a working k6 binary.

## Running

```bash
# 1. Infrastructure
docker compose up -d

# 2. Backend, in another shell
cd backend && ./gradlew bootRun

# 3. Seed load-test accounts (idempotent)
k6 run tests/load/seed-users.js

# 4. A scenario
k6 run tests/load/browse-catalogue.js
k6 run tests/load/leaderboard-read.js
k6 run tests/load/matchmaking-join.js
k6 run tests/load/session-write.js

# Everything, with results written to results/
./tests/load/run-all.sh
```

## Why these four scenarios

Each one exercises a different bottleneck, so a regression in any single layer
shows up in exactly one of them:

| Script | Exercises | The question it answers |
|---|---|---|
| `browse-catalogue.js` | Redis cache, Postgres pagination | Does the cache actually absorb the read volume? |
| `leaderboard-read.js` | Redis ZSET ranking | Does `ZREVRANK` stay flat as the board grows? |
| `matchmaking-join.js` | Write path, partial unique index, tick loop | Does queue depth stay bounded under sustained joins? |
| `session-write.js` | Transactional outbox, Kafka relay | Does the outbox backlog drain as fast as it fills? |

`session-write.js` is the most important of the four. It is the only one that
puts the whole event pipeline under pressure, and the outbox backlog gauge is
the clearest signal of whether the asynchronous design holds up.

## Thresholds

Every script declares `thresholds`, so k6 exits non-zero when a target is
missed. That makes these usable as a gate in CI rather than as a report
someone has to read and interpret.

The targets are stated as service-level objectives, not as predictions:

- p95 under 200ms for cached reads
- p95 under 500ms for writes
- error rate under 1%

They are deliberately modest. A target nobody can miss is not a gate, and a
target chosen to flatter the numbers is worse than none.

## Interpreting a run

Look at p95 and p99, not the mean. The mean hides the tail, and the tail is
what a player experiences as the app being slow.

Watch the Grafana dashboard at http://localhost:3000 during the run. The
`gamehub_outbox_backlog` gauge climbing and not recovering means the relay is
losing to the write rate, which no amount of request latency will tell you.

## Known limitation

Running k6 on the same machine as the backend means the load generator and the
system under test compete for CPU, so the numbers are conservative and are not
comparable to a distributed run. Any recorded result must state where it ran.
