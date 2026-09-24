# ADR-006: A modular monolith, not microservices

**Status:** Accepted · **Date:** 2026-09-21

## Context

GameHub fans out one `GameCompleted` event to three independent consumers:
statistics, achievements and recommendations. That is the shape people reach
for microservices to express, and a portfolio project has an obvious incentive
to show a service mesh.

## Decision

One deployable. Boundaries between `domain`, `application`, `infrastructure`
and `api` are enforced by **ArchUnit tests**, not by convention or by Gradle
subprojects.

## Alternatives

**Microservices.** One service per consumer. Rejected because the costs are
real and immediate while the benefits are hypothetical:

- One team, one release cadence. The coordination cost microservices buy down
  does not exist.
- All three consumers read the same tables. Splitting means either a shared
  database — the worst of both worlds — or a data-duplication project that is
  larger than the system it serves.
- Local development would need several processes, and every integration test
  would become a distributed test.

**Gradle subprojects.** Would enforce boundaries at *compile* time, which is
strictly stronger than a test. Rejected because it buys a build graph every
reviewer has to learn, and a module split that is painful to change once code
exists. The enforcement gap is small; the friction is not.

**Spring Modulith.** Almost exactly this decision, with tooling. Not adopted
because ArchUnit already covers the enforcement and adding a dependency for
documentation generation was not worth it. The reasoning is the same.

**No enforcement at all.** Layering by convention. Rejected because
conventions decay silently, and the violation this project actually hit proves
it: a controller referencing enums nested inside a JPA entity.

## Consequences

**Good.** One process to run, one to debug, one to deploy. Integration tests
exercise the whole system in one JVM. The `domain` package depends on nothing
but the JDK, which is what makes the matchmaking engine, the leaderboard codec
and the recommendation scorer testable with no container and no mocks.

Crucially, **the seams are real**: every consumer communicates only through
events and its own idempotency ledger, so extracting one later is a deployment
change rather than a rewrite. The outbox makes the transport swappable too.

**Bad.** Everything scales together — a read-heavy catalogue spike also scales
the consumers. One bad deployment affects everything. Nothing *forces* the
boundaries at compile time, so ArchUnit must actually be run, and a rule can
be deleted as easily as it can be added.

**Evidence it works.** `ArchitectureTest` failed on its first run with five
violations: `LeaderboardController` referenced `Scope` and `Period`, which
were nested inside `LeaderboardEntity`. That is a genuine leak of persistence
into the public API contract — renaming a column would have become a breaking
API change. The enums were moved into the domain. The rule was not relaxed.

**When to revisit.** If the matchmaker tick or the consumers need to scale
independently of the API, or if a second team takes ownership of one
subsystem. Both are real triggers; neither has happened.
