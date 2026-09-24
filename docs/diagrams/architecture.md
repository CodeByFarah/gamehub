# Architecture diagrams

Mermaid, so they render on GitHub and stay diffable in review. An image would
be neither.

## System overview

```mermaid
graph TB
    subgraph client[Client]
        A[Android app<br/>Kotlin, Compose]
    end

    subgraph api[GameHub backend, stateless]
        C[Controllers<br/>validate, delegate]
        S[Services<br/>transaction boundary]
        D[Domain<br/>pure, no framework]
        C --> S
        S --> D
    end

    subgraph data[Data plane]
        PG[(PostgreSQL 16<br/>source of truth<br/>+ outbox)]
        RD[(Redis 7<br/>ZSETs, cache, leases)]
        KF[Kafka 3.9<br/>KRaft]
    end

    subgraph consumers[Consumers, same deployable]
        ST[Statistics]
        AC[Achievements]
        RC[Recommendations<br/>+ leaderboard writes]
    end

    subgraph external[External]
        GM[Gemini API]
    end

    A -->|HTTPS, Bearer JWT| C
    S --> PG
    S -->|always with a fallback| RD
    S -->|AiClient port| GM

    PG -->|outbox relay<br/>FOR UPDATE SKIP LOCKED| KF
    KF --> ST
    KF --> AC
    KF --> RC

    ST --> PG
    AC --> PG
    RC --> PG
    RC --> RD

    classDef truth fill:#1f4e5f,stroke:#0d2b36,color:#fff
    classDef cache fill:#5f1f3a,stroke:#360d1f,color:#fff
    class PG truth
    class RD cache
```

The colour split is the important part: **Postgres is the only hard
dependency.** Losing Redis costs latency; losing Kafka stops derived state
updating but loses nothing, because events are already durable in the outbox.

## The dual-write problem, and the outbox

```mermaid
sequenceDiagram
    participant C as Client
    participant S as SessionService
    participant PG as PostgreSQL
    participant R as Outbox relay
    participant K as Kafka
    participant X as Consumers

    C->>S: POST /sessions/{id}/complete
    rect rgb(240, 245, 250)
        note over S,PG: ONE transaction
        S->>PG: UPDATE game_sessions (close)
        S->>PG: INSERT game_events (outbox row)
    end
    PG-->>S: commit
    S-->>C: 200, immediately

    note over R: every 500ms, on every instance
    R->>PG: SELECT ... WHERE published_at IS NULL<br/>FOR UPDATE SKIP LOCKED
    R->>K: publish, awaited
    R->>PG: UPDATE published_at

    K->>X: at-least-once delivery
    note over X: INSERT processed_events<br/>ON CONFLICT DO NOTHING
```

If the process dies between publish and stamp, the row is republished. That
duplicate is expected, and the ledger absorbs it.

## Matchmaking tick

```mermaid
flowchart TD
    T[Scheduled tick, 1s<br/>on every instance] --> B{Active buckets?}
    B -->|none| Z[Return]
    B -->|for each| L{Acquire Redis lease<br/>per game+region}
    L -->|lost| Z
    L -->|won| P[Load WAITING pool<br/>oldest first]
    P --> E[GreedyMatchmakingEngine<br/>pure, in memory]
    E --> Q[Proposals, best cost first]
    Q --> CM[Conditional claim:<br/>UPDATE ... WHERE status='WAITING']
    CM -->|claimed = 2| OK[Commit match<br/>+ outbox event]
    CM -->|claimed < 2| RB[Rollback<br/>stale proposal]

    style CM fill:#5f1f3a,stroke:#360d1f,color:#fff
    style L fill:#3a3a3a,stroke:#1f1f1f,color:#fff
```

The **highlighted claim** is the safety mechanism. The lease is grey because
it is only an optimisation: if it fails open, three instances propose
overlapping pairings, the claim rejects the duplicates, and the outcome stays
correct.

## Leaderboard read, with fallback

```mermaid
flowchart LR
    RQ[GET leaderboard] --> RZ{Redis ZSET}
    RZ -->|hit| OUT1[servedFrom: REDIS]
    RZ -->|cold or down| PGQ[Read Postgres<br/>index scan]
    PGQ --> WARM[Warm top 1000]
    PGQ --> OUT2[servedFrom: POSTGRES]

    style OUT2 fill:#5f4e1f,stroke:#362b0d,color:#fff
```

`servedFrom` is in the response deliberately, so a silent degradation becomes
a countable one.

## AI request path

```mermaid
flowchart TD
    Q[Natural-language prompt] --> SAN[Sanitise + length cap]
    SAN --> CB{Circuit breaker}
    CB -->|open| FB[FallbackAiClient<br/>keyword extraction]
    CB -->|closed| G[Gemini, 8s timeout<br/>3 retries, jittered]
    G -->|error or timeout| FB
    G -->|response| V{Validate every field<br/>against catalogue vocabulary}
    V -->|invalid| FB
    V -->|valid| I[SearchIntent]
    FB --> I2[SearchIntent, degraded=true]

    I --> PGS[Postgres indexed filter]
    I2 --> PGS
    PGS --> R[Results]

    style V fill:#1f4e5f,stroke:#0d2b36,color:#fff
    style PGS fill:#1f5f2b,stroke:#0d360f,color:#fff
```

Two things this diagram is meant to make obvious:

1. **Every path reaches a result.** There is no branch that returns an error.
2. **The model never touches the database.** It produces filter parameters;
   Postgres does the searching. A prompt injection can make the filters odd —
   it cannot exfiltrate data or bypass authorisation.

## Deployment, cloud

```mermaid
graph TB
    U[Client] -->|HTTPS| CR[Cloud Run<br/>scales to zero<br/>max 5 instances]

    subgraph vpc[VPC, private only]
        SQL[(Cloud SQL<br/>Postgres 16<br/>no public IP)]
        MS[(Memorystore<br/>Redis, BASIC)]
    end

    CR -->|VPC connector| SQL
    CR -->|VPC connector| MS
    CR -->|per-secret IAM| SM[Secret Manager]
    CR -->|OTLP| CT[Cloud Trace]

    AR[Artifact Registry<br/>SHA-tagged images] -.->|deploy| CR

    KFX[Kafka: NOT DEPLOYED<br/>see deployment.md]
    style KFX fill:#5f1f1f,stroke:#360d0d,color:#fff,stroke-dasharray: 5 5
```

Kafka is drawn as absent on purpose. Managed Kafka on GCP has no
scale-to-zero tier and would cost more than everything else combined. The
outbox means the transport is swappable, and `deployment.md` sets out the
three options. A diagram showing Kafka that is not actually deployed would be
a lie.

## Instance scaling

```mermaid
graph LR
    LB[Load balancer] --> I1[Instance A]
    LB --> I2[Instance B]
    LB --> I3[Instance C]

    I1 --> SH[(Shared state:<br/>Postgres + Redis)]
    I2 --> SH
    I3 --> SH

    I1 -.->|relay, tick, sweeper| SH
    I2 -.->|same jobs| SH
    I3 -.->|same jobs| SH
```

All three run the same background jobs. Safety comes from `SKIP LOCKED`,
conditional claims and idempotent updates — not from electing one of them.

**The real ceiling is database connections, not CPU:** 20 per instance against
`max_connections = 200`. Beyond ~5–8 instances the answer is PgBouncer, not
more instances.
