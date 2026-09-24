# ADR-001: Java 21 and Spring Boot for the backend

**Status:** Accepted · **Date:** 2026-09-21

## Context

GameHub needs a backend that handles a read-heavy catalogue, a write-heavy
event pipeline, scheduled background work, and three infrastructure
dependencies. It must be horizontally scalable and it must be something a
reviewer can read without learning a niche stack first.

The workload is almost entirely **I/O-bound**: nearly every request is blocked
on Postgres, Redis or Kafka rather than computing anything.

## Decision

Java 21 with Spring Boot 3.4.

## Alternatives

**Kotlin + Ktor.** More concise, and the Android client is Kotlin so there
would be language symmetry. Rejected because the Kafka, JPA and Actuator
integrations are markedly less mature, and this project leans on all three.
The symmetry argument is weaker than it sounds: the client shares no code with
the server.

**Go.** Excellent for this shape of service and genuinely cheaper to run.
Rejected because the ecosystem for what this project actually exercises
(Testcontainers, Flyway, Micrometer, mature Kafka consumers with dead-letter
support) would mean hand-rolling more infrastructure and less of the thing
being demonstrated.

**Node/TypeScript.** Fast to write. Rejected for the concurrency story: this
system needs real parallelism for the matchmaker tick and consumer threads,
and worker threads are a poor fit.

**Quarkus or Micronaut.** Faster startup and lower memory than Spring, which
matters on Cloud Run cold starts. Rejected because the operational maturity
and the sheer volume of well-understood patterns in Spring outweigh a
cold-start difference this project never measured.

## Consequences

**Good.** Java 21 virtual threads are close to ideal here — a blocked virtual
thread costs almost nothing, which is exactly this workload. Records and
sealed interfaces made the domain model and the event hierarchy expressible
without ceremony; the sealed `GameHubEvent` gives compiler-checked exhaustive
switches, so adding an event type breaks the build at every place that must
decide about it.

Spring Boot supplied the Kafka error handler with dead-letter routing,
Testcontainers integration, and Actuator metrics without writing any of them.

**Bad.** Cold start is slower than Go or Quarkus, which costs a first-request
penalty when Cloud Run has scaled to zero. Memory footprint is larger.
Annotation-driven magic hides behaviour: the proxy-based `@Transactional`
self-invocation trap caught this project twice, once in the outbox relay and
once in the match commit path, and in both cases the code looked correct.

**Accepted.** `-Werror` with `-Xlint:all` is on, so the compiler is treated as
a linter rather than a suggestion box.
