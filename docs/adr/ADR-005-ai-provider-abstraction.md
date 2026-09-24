# ADR-005: A port for AI, with a deterministic fallback

**Status:** Accepted · **Date:** 2026-09-22

## Context

GameHub uses an LLM for natural-language discovery and a game assistant. That
provider is slow, costs money per call, is occasionally wrong, and is
sometimes absent entirely — a reviewer cloning this repository will not have
an API key.

A feature that makes the whole application unrunnable without a paid
third-party credential is the wrong coupling. AI here is a feature, not the
product.

## Decision

An `AiClient` port in the application layer, with two implementations:

- `GeminiAiClient` — the real provider, wrapped in timeout, retry, circuit
  breaker and **field-by-field response validation**.
- `FallbackAiClient` — deterministic keyword extraction. No network, no key,
  no cost.

Selected at startup by whether `GEMINI_API_KEY` is set. **A missing key is a
supported operating mode, not a misconfiguration.**

Implementations never throw for provider trouble. Failures are reported by
returning a result marked `degraded`.

## Alternatives

**Call the SDK directly from the service.** Simplest. Rejected because it
makes every test require a key or a mocked HTTP client, and it makes degraded
mode a set of if-statements scattered through business logic rather than a
code path.

**Spring AI.** A real abstraction over several providers, and a reasonable
choice. Rejected because it is a large dependency for two call sites, and its
own abstraction would still need wrapping to get the validation and
degradation behaviour this design requires.

**Fail the request when AI is unavailable.** Honest, and much simpler.
Rejected because the fallback genuinely produces useful results for the
queries players actually type — "a quick competitive game" needs no model to
interpret.

**Require the key to boot.** Rejected: it makes a paid dependency a hard
prerequisite for running a game platform.

## Consequences

**Good.** The application runs with no Google account. The provider is
swappable without touching a service or a test. Degraded mode is a real
implementation rather than a branch, so it is exercised by the same code path
in tests. `ArchitectureTest` enforces that no service imports the adapter.

Validation is where model output stops being trusted: a hallucinated genre
becomes an **absent filter**, not a query that silently matches nothing and
looks like broken search.

**Bad.** Two implementations to maintain, and the fallback is meaningfully
worse — it cannot interpret anything the keyword rules do not cover. The port
is shaped by what Gemini can do, so a provider with different capabilities
might not fit cleanly. There is no evaluation harness measuring whether intent
extraction is *correct*, only whether it returned something usable; that is
the largest gap in this design.

**Deliberate.** Both responses carry `degraded`, so the UI can be honest and
silent degradation is countable. Retry sits **inside** the circuit breaker —
the other way round, the breaker would count one logical failure three times
and trip on a single unlucky request.
