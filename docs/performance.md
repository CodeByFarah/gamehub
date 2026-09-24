# Performance

## Status: NOT MEASURED

**No load test in this repository has been executed.** There are no latency
figures, no throughput figures, and no percentiles anywhere in this project,
because producing them requires a running backend and a working k6 binary and
neither was available on the machine this was built on.

This document describes what would be measured and why. It contains no
numbers, and it will not contain any until a run produces them.

That is deliberate. An invented benchmark is worse than no benchmark: it
survives into a CV, gets asked about in an interview, and cannot be defended.

## What blocked measurement

| Blocker | Detail |
|---|---|
| Docker Linux engine | `docker info` returns HTTP 500. Without containers there is no Postgres, Redis or Kafka to load |
| k6 | Downloads and extracts, but execution is blocked by a Windows Application Control policy |

Both are environment problems, not project problems. The scripts are written
and syntax-checked (`node --check` passes on all six).

## The four scenarios and what each isolates

| Script | Bottleneck under test | The question |
|---|---|---|
| `browse-catalogue.js` | Redis cache, Postgres pagination | Does the cache actually absorb the read volume? |
| `leaderboard-read.js` | Redis ZSET ranking | Does `ZREVRANK` stay flat as rank depth grows? |
| `matchmaking-join.js` | Write path, partial unique index, tick loop | Does queue depth stay bounded under sustained joins? |
| `session-write.js` | Transactional outbox, Kafka relay | Does the backlog drain as fast as it fills? |

`session-write.js` is the most important. It is the only one that puts the
whole event pipeline under pressure, and the outbox backlog gauge is the
clearest signal of whether the asynchronous design holds up.

### Why leaderboard-read measures flatness, not latency

The interesting property is not the absolute number but the **curve**.
`ZREVRANK` is O(log N), so page 50 should cost roughly what page 0 costs. A
curve that climbs with depth means reads have fallen back to Postgres, where
rank is O(better players).

The script therefore splits latency into three trends (shallow, middle, deep)
and counts `servedFrom: REDIS` versus `POSTGRES` separately. The response
exposes that field precisely so the split is measurable rather than inferred.

## Targets

These are **service-level objectives, not predictions**. Nothing here is
derived from a measured run.

| Class | p95 | p99 | Errors |
|---|---|---|---|
| Cached reads | < 200ms | < 500ms | < 1% |
| Uncached reads | < 400ms | < 800ms | < 1% |
| Writes | < 500ms | < 1000ms | < 1% |

Deliberately modest. A target nobody can miss is not a gate, and a target
chosen to flatter the numbers is worse than none.

Every script declares these as k6 `thresholds`, so a run exits non-zero when
one is missed. That makes them usable as a CI gate rather than a report
somebody has to read and interpret.

## Why p95 and p99, never the mean

The mean hides the tail, and the tail is what a player experiences as the app
being slow. A p50 of 40ms with a p99 of 4s is a system where one request in a
hundred feels broken, and the mean will report it as fast.

## Load shapes, and why they differ per scenario

| Scenario | Executor | Reason |
|---|---|---|
| Browse | `ramping-vus` | A cold cache behaves completely differently from a warm one. Flat load would measure whichever state dominated |
| Leaderboard | `constant-arrival-rate` | Keeps pressure constant even as latency changes, which reveals degradation instead of masking it behind queued VUs |
| Matchmaking | `ramping-vus` | Queue depth is the output; ramping shows where it stops draining |
| Session write | `constant-arrival-rate` | The question is whether the pipeline keeps up with a *known* event rate |

Constant arrival rate matters more than it looks. With constant VUs, a slow
backend simply reduces the offered load and hides the problem.

## What to watch that k6 will not tell you

Request latency will not reveal the most likely failure here, because
`POST /sessions/{id}/complete` returns as soon as its transaction commits.
That is the whole point of the asynchronous design, and also its main risk.

During a run, watch Grafana:

| Metric | Failure signal |
|---|---|
| `gamehub_outbox_backlog` | Climbs and does not recover after the run |
| `gamehub_outbox_published_total` | Rate does not track the completion rate |
| `gamehub_consumer_events_total{result="duplicate"}` | Large fraction, meaning heavy redelivery |
| `gamehub_cache_access_total` | Hit rate collapsing |
| `gamehub_matchmaking_queue_depth` | Grows without bound |
| `kafka_consumer_..._records_lag_max` | Consumers falling behind |

## Known structural limits

These follow from the design and can be reasoned about without measuring:

**Database connections, not CPU, are the ceiling.** Each instance opens a
Hikari pool of 20 against a Postgres configured for 200. That caps instances
at roughly 5–8 with headroom, which is why Cloud Run `max_instances` is 5.
Beyond that the next step is PgBouncer in transaction mode, not more
instances.

**The matchmaker tick must finish inside one second.** `tick_seconds` p95 is
the metric; a tick that overruns means ticks overlap and the lease does all
the work.

**The outbox relay polls every 500ms.** That interval is the floor on how long
an event takes to reach a consumer, and therefore on how stale a derived
counter can be.

**Search is not cached.** Search terms have a long tail, so a cache would
mostly store entries never read again while evicting catalogue pages that
genuinely benefit. This read depends entirely on the GIN index.

## Deliberate performance decisions

| Decision | Cost | Benefit |
|---|---|---|
| Virtual threads | None meaningful here | Request threads are almost entirely blocked on I/O |
| Denormalised `popularity_score` | A write per session | Discovery sorts by it on every request; a `COUNT` would not hold up |
| Precomputed recommendations | Consumer work per game | Home screen renders from one indexed lookup |
| Partial indexes | None | Index size proportional to live rows, not history |
| No cache stampede guard | Duplicate queries on a miss | Avoids a distributed lock on every cached read |
| Base64 save payloads | 33% transfer overhead | One JSON envelope carrying version and checksum; bounded by the 1 MiB ceiling |

## How to produce real numbers

```bash
docker compose up -d
cd backend && ./gradlew bootRun      # separate shell

k6 run tests/load/seed-users.js
./tests/load/run-all.sh
```

Results land in `tests/load/results/` as JSON summaries. Any figure recorded
here afterwards **must** state the hardware, the date, and the commit it was
run against.

One caveat that must accompany any local result: running k6 on the same
machine as the backend means the load generator and the system under test
compete for CPU, so the numbers are conservative and not comparable to a
distributed run.
