# Testing

## Strategy

Three tiers, split by what they need rather than by what they are called.

| Tier | Needs | Runs in | Count |
|---|---|---|---|
| Unit + architecture | Nothing | ~15s | **64, passing** |
| Integration | Docker | minutes | Written, **NOT EXECUTED** |
| Concurrency | Docker | minutes | Written, **NOT EXECUTED** |
| Load | Docker + k6 + running backend | minutes | Written, **NOT MEASURED** |

The split is by **JUnit tag**, not filename convention, so a misnamed class
cannot quietly skip CI:

```kotlin
tasks.named<Test>("test") {
    useJUnitPlatform { excludeTags("integration", "concurrency") }
}
```

A developer without Docker still gets a meaningful signal from `./gradlew
test`, and CI caches the two differently.

## Verified results

```
$ ./gradlew test
BUILD SUCCESSFUL in 16s
TOTAL tests=64  failures=0
```

| Suite | Tests | Covers |
|---|---|---|
| `GreedyMatchmakingEngineTest` | 15 | Disjointness, acceptability, determinism, starvation, degenerate input |
| `MatchCandidateScorerTest` | 13 | Symmetry, monotonicity, clamping, patience, policy validation |
| `LeaderboardScoreCodecTest` | 11 | Round-trip exactness, ordering, range enforcement |
| `RecommendationScorerTest` | 11 | Score range, filtering, ranking, Jaccard, decay |
| `AchievementEvaluatorTest` | 7 | Threshold inclusivity, per-session vs cumulative rules |
| `ArchitectureTest` | 7 | Layering, framework isolation, injection style |

## Properties, not examples

The domain tests assert **properties over seeded random populations**, not
happy paths. For matchmaking:

- No player appears in two matches, across populations of 2, 3, 17, 50, 500.
- Every committed pairing clears its own acceptance threshold.
- Shuffling the input leaves the output identical. This one matters: the
  engine reads from Redis, whose iteration order is not guaranteed, so if
  order mattered two instances could disagree about the same queue.
- The longest waiter is matched even when a tighter cluster exists.
- 1000 tickets in one rating band stays bounded rather than going quadratic.

Seeds are fixed, so a failure is reproducible rather than a flake.

## A test that caught a real design flaw

`ArchitectureTest.controllersDoNotTouchPersistence` **failed on its first
run**, with 5 violations:

```
Method <LeaderboardController.forGame(...)> gets field
<LeaderboardEntity$Scope.GAME> in (LeaderboardController.java:54)
```

`Scope` and `Period` were nested inside the JPA entity, so the controller's
signature depended on the persistence layer. That is a genuine leak: renaming
a column would have become a breaking API change.

The fix moved them into `domain.leaderboard` as first-class enums. The rule
was not relaxed.

## A test that was wrong, and the code that was right

`latencyUsesTheWorseOfThePair` failed because I chose 300ms and 160ms — both
saturate the 150ms budget clamp, so both scored exactly 0.3.

The production code was correct: clamping is deliberate, so one 2000ms outlier
cannot swamp skill and region. The test was rewritten with mean-preserving
values (20/140 vs 80/80, both under the budget) so it actually tests
worse-of-pair versus average, and a second test now pins the clamp itself.

The code was not changed to make a test pass.

## Why Testcontainers and not H2

Nearly every interesting behaviour here is Postgres-specific and invisible
against a generic in-memory database:

- `FOR UPDATE SKIP LOCKED` — the outbox relay. H2 does not implement it.
- `ON CONFLICT DO NOTHING` — idempotency for consumers and achievements.
- Partial unique indexes — the entire matchmaking concurrency defence.
- `websearch_to_tsquery` and trigram similarity — the search implementation.

A suite passing against H2 would prove nothing about any of them, and would
give false confidence precisely where the risk is concentrated.

Containers start once per JVM, not per class. Isolation comes from
transactional rollback and unique data per test.

## Concurrency tests

Both follow the same shape, documented in the class Javadoc: problem, race
condition, solution, why it works, tradeoffs, failure cases.

**`CloudSaveConcurrencyTest`** — 16 threads, same version, one slot. Asserts
exactly one acceptance, not "at least one": every other outcome is a lost
write.

**`MatchmakingConcurrencyTest`** — 12 simultaneous joins by one player.
Asserts one ticket in the database, and that a constraint violation never
escapes untranslated as a 500.

Both use a `CyclicBarrier`. Without it threads start staggered, the first
finishes before the last begins, the race never happens, and the test passes
**vacuously** — which is worse than no test, because it looks like coverage.

## What is deliberately not tested

- **Getters, setters, and Lombok output.** Testing generated code measures the
  code generator.
- **Spring wiring.** A context that fails to start fails every integration
  test already.
- **MapStruct implementations.** Excluded from coverage for the same reason.
- **Third-party libraries.** Postgres does not need our tests to prove
  `SKIP LOCKED` works; the integration tests assert our *use* of it.
- **Exhaustive controller validation.** One test per validation *mechanism*,
  not per annotated field.

Coverage excludes `config`, `dto` and generated mappers. Including them would
inflate the number while measuring nothing, and a coverage figure that
flatters is worse than none.

## How to run

```bash
cd backend
./gradlew test              # no Docker needed, ~15s
./gradlew integrationTest   # requires Docker
./gradlew check             # both
./gradlew test -PtcReuse=true   # reuse containers between runs
```

Container reuse is opt-in per developer and **off in CI**, where a fresh
environment matters more than startup time.

## Gaps

Stated rather than hidden:

- **No controller-layer tests.** `@WebMvcTest` slices for status codes and
  error envelopes are missing.
- **No Kafka consumer integration tests.** The idempotency ledger is unit-
  reasoned but not verified against a real broker replaying a record.
- **No failure-injection tests.** The fallback paths for Redis and Gemini are
  written but never exercised by an induced failure. Toxiproxy is the obvious
  tool.
- **No Android tests**, because there is no Android app.
- **No mutation testing.** PIT would say whether these assertions are as
  strong as they look.
