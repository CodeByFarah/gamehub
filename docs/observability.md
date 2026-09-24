# Observability

Three signals sharing one identifier: metrics say *something is wrong*, traces
say *where*, logs say *what*. The trace id is what joins them.

```
logging.pattern.console: "... [%X{traceId:-},%X{spanId:-}] ..."
```

Every log line carries the trace id, the error envelope returns it to the
client, and a user can quote it in a bug report. That is the whole point of
surfacing it.

## Metrics that matter

Standard HTTP and JVM metrics come from Actuator. These are the ones specific
to this system, and each exists because it answers a question that would
otherwise need a guess.

| Metric | Question |
|---|---|
| `gamehub_outbox_backlog` | Is the relay keeping up with the write rate? |
| `gamehub_outbox_published_total` | Is it publishing at all? |
| `gamehub_outbox_failed_total` | Are publishes failing? |
| `gamehub_consumer_events_total{result}` | What fraction of deliveries are duplicates? |
| `gamehub_matchmaking_queue_depth` | Are players accumulating? |
| `gamehub_matchmaking_matches_total` | Is the queue draining? |
| `gamehub_matchmaking_stale_proposals_total` | Are instances colliding? |
| `gamehub_matchmaking_tick_seconds` | Does a tick finish inside its interval? |
| `gamehub_cache_access_total{result}` | Is the cache earning its keep? |
| `gamehub_cloudsave_write_total{result}` | Is optimistic concurrency still the right choice? |
| `gamehub_ai_request_total{result}` | Is the provider healthy, or just wrong? |
| `gamehub_ai_latency_seconds` | How much latency does AI add? |

### Three that are worth explaining

**`cache_access{result}` has three values, not two.** `hit`, `miss` and
`error`. Folding `error` into `miss` would hide a Redis outage completely: the
system would look like it had a cold cache rather than a broken one.

**`ai_request{result}` separates `invalid` from `failure`.** A provider that
is up but returning unusable output is a different problem from one that is
down, and it needs a different response: a prompt change, not a page.

**`consumer_events{result="duplicate"}` is expected to be non-zero.** Kafka is
at-least-once, so a steady trickle is the system working. It is tracked
because a *spike* means heavy redelivery, which is worth investigating even
though nothing is incorrect.

### Gauges never throw

`outbox_backlog` returns `NaN` if the query fails. A gauge that threw would
break the scrape; one that returned zero would report a healthy empty backlog
during a database outage, which is the most misleading possible answer.

## Alerts

Every rule fires on a **symptom a user would notice**, not on a resource
number. High CPU is not an incident; slow requests are. Rules that page on
causes rather than symptoms are the ones people learn to ignore.

| Alert | Severity | Fires on |
|---|---|---|
| `BackendDown` | critical | No successful scrape for 1m |
| `HighErrorRate` | critical | 5xx above 5% for 5m |
| `HighLatencyP99` | warning | p99 above 1s for 5m |
| `OutboxBacklogGrowing` | warning | Backlog above 1000 for 5m |
| `OutboxStalled` | critical | Backlog above 100 **and** publish rate zero |
| `KafkaConsumerLag` | warning | Lag above 10000 |
| `MatchmakingQueueBacklog` | warning | Depth above 500 |
| `CacheHitRateCollapsed` | warning | Below 50% for 10m |
| `AiFailureRate` | info | Above 30% falling back |

### The pair worth noticing

`OutboxBacklogGrowing` and `OutboxStalled` look similar and are not:

- **Growing** — the relay is losing to the write rate. Scale, or find the slow
  consumer.
- **Stalled** — backlog exists and `rate(published) == 0`. The relay is not
  running at all. A completely different problem, and a critical one.

Throughput alone cannot distinguish them, which is why the second rule tests
two conditions together.

`AiFailureRate` is deliberately `info` and never pages. The AI path degrades
rather than failing, so this is how we learn the provider is unhealthy before
the bill does.

`CacheHitRateCollapsed` is also not an outage: every cached read has a
Postgres fallback. It is an early warning that the database is about to take
the full read load.

## Tracing

OpenTelemetry over OTLP to a collector, rather than a vendor SDK in the
application. That indirection is the point: swapping Jaeger for Tempo, or
adding a second destination, is a change to the collector config and not a
redeploy.

Sampling is 10% in deployed profiles and **100% locally**. The production
sample exists to control cost and volume; neither matters on one developer
machine, and a missing trace for the request you are debugging is maddening.

The collector also derives RED metrics from spans via the `spanmetrics`
connector, so request rate, errors and duration are available broken down by
span attributes without the application emitting them twice.

### Gap

**Traces do not propagate across the outbox boundary.** A trace ends when the
transaction commits, and a new one starts in the consumer. Fixing it means
storing the trace context on the outbox row and restoring it in the relay.
`observation-enabled: true` is set on the listener container, so the consumer
side is instrumented; the link is what is missing.

## Health

| Probe | Includes | Why |
|---|---|---|
| `/health/liveness` | Process only | Restarting because Redis is down fixes nothing and turns a degraded service into an unavailable one |
| `/health/readiness` | Postgres, Redis | An instance that cannot serve should leave the load balancer, not be killed |

Getting this backwards is a common and expensive mistake: a liveness probe
that includes dependencies turns a dependency outage into a cluster-wide crash
loop.

## Logging

Structured, INFO by default, DEBUG for `com.gamehub` locally.

Level discipline is deliberate:

- **ERROR** — needs a human. The unhandled-exception handler, a stalled
  outbox, a dead-lettered record.
- **WARN** — handled, but notable. A translated constraint violation, a Redis
  fallback, an AI degradation.
- **INFO** — meaningful state changes. Registration, match created,
  achievement unlocked, tickets expired.
- **DEBUG** — per-request detail.

A 404 or a version conflict is logged at WARN, not ERROR. Those are the system
working, and paging on them trains people to ignore alerts.

`System.out` is banned and the ban is enforced by `ArchitectureTest`. Console
output bypasses the logging configuration, so it carries no level, no
timestamp and no trace id.

## Local stack

`docker compose up -d` gives Prometheus (9090), Grafana (3000, anonymous
viewer) and the OTel collector (4317). Datasources and dashboards are
provisioned from files, so a fresh start gives every developer an identical
working Grafana with no setup.

Prometheus scrapes two backend targets, `backend:8080` and
`host.docker.internal:8080`, so the same config works whether the backend runs
in compose or from the IDE.

## Gaps

- **No log aggregation locally.** Logs go to stdout; no Loki or ELK.
- **No trace propagation through Kafka**, as above.
- **No SLO burn-rate alerts.** Thresholds are static rather than
  error-budget-based.
- **No exemplars** linking a metric spike to a specific trace.
- **No business dashboards.** Matches per hour, achievement unlock rate and
  DAU are all derivable from existing metrics but not charted.
