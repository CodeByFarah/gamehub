# AI architecture

## The constraint that shapes everything

The AI provider is slow, costs money per call, is occasionally wrong, and is
sometimes absent entirely. Every decision here follows from treating it as a
dependency that cannot be trusted, rather than as a library call.

**The application must work without it.** With `GEMINI_API_KEY` empty the
service starts normally and the AI endpoints return useful results. That is a
supported operating mode, not a degraded build.

Requiring a key to boot would make a paid third-party dependency a hard
prerequisite for running a game platform. AI here is a feature, not the
product.

## The port

```java
public interface AiClient {
    SearchIntent extractSearchIntent(String prompt, CatalogueVocabulary vocabulary);
    AssistantAnswer answer(String question, List<GameContext> context);
    boolean isAvailable();
}
```

Nothing above this interface knows Gemini exists. `AiService` asks for a
structured intent; the adapter decides how to get one.

That buys three things:

- The provider can be swapped without touching a service or a test.
- The deterministic fallback is **just another implementation**, so degraded
  mode is a real code path rather than a special case wired through
  if-statements.
- Unit tests use a stub and need no network, no key and no budget.

`ArchitectureTest.applicationUsesPortsForAi` fails the build if a service ever
imports the adapter directly.

### The contract: implementations never throw

A timeout, a rate limit, malformed JSON or an open circuit are all normal
operating conditions for something that calls a third party over the internet.
They are reported by returning a result marked `degraded`, never by throwing.

That rule is what keeps a slow provider from becoming a slow API.

## Two-stage design

```
  "a competitive multiplayer game I can play for 20 minutes"
                          |
                          v
            +---------------------------+
            |  Stage 1: the model       |  decides WHAT to search for
            |  prompt -> SearchIntent   |
            +-------------+-------------+
                          |  validated, field by field
                          v
            +---------------------------+
            |  Stage 2: Postgres        |  does the searching
            |  indexed filter query     |
            +---------------------------+
```

**The model never produces SQL, never sees the database, and never influences
which rows a user is allowed to see.**

That split is the entire security posture. A prompt injection can at worst
make the filters odd; it cannot exfiltrate data or bypass authorisation,
because model output never reaches a place where those are decided.

It is also what makes the feature testable: intent extraction is verified
against a stub, and execution against a real database, with neither test
needing the other half.

## Never trusting the output

Gemini is asked for JSON via a response schema, which removes one class of
failure: the model cannot return prose in a markdown fence. But a schema
constrains *shape*, not *meaning*, and nothing stops a syntactically perfect
genre that does not exist.

So every field is validated:

| Field | Validation | On failure |
|---|---|---|
| `genre` | Must be in the real catalogue vocabulary | Dropped, becomes no filter |
| `tags` | Each intersected with the vocabulary | Unknown tags removed |
| `maxSessionMinutes` | Between 1 and 600 | **Discarded, not clamped** |
| `minRating` | Between 0 and 5 | Discarded |
| `confidence` | Clamped to 0 to 1 | Clamped |
| `interpretation` | Truncated to 300 chars | Truncated |

A hallucinated genre becomes an **absent filter**, not a query that silently
matches nothing and looks like a broken search.

Out-of-range numbers are discarded rather than clamped on purpose: clamping
would invent a constraint the user never expressed.

The vocabulary is passed *into* the prompt, so the model is constrained to
values that exist. Without it, a model will happily return a genre like "cozy
roguelike deckbuilder".

## Layered protection

```
  request
     |
     v  1. timeout       8s, well inside the request budget
     v  2. retry         3 attempts, exponential with jitter
     v  3. circuit       opens at 50 percent failure over 20 calls
     v  4. validation    every field checked
     v  5. fallback      deterministic keyword extraction
     |
     v  always a useful answer
```

**Retry sits inside the circuit breaker.** The other way round, the breaker
would count one logical failure three times and trip on a single unlucky
request.

Retries are deliberately few. Retrying a request that is timing out because
the provider is overloaded adds load to an overloaded provider; the circuit
breaker is the mechanism meant to handle sustained failure.

Backoff is jittered. Without jitter, every instance that failed at the same
moment retries at the same moment, and a brief provider blip becomes a
self-inflicted thundering herd.

The 8s timeout is sized well below the inbound read timeout, so a slow
provider surfaces as a fast clean fallback rather than a request thread parked
long enough to exhaust the pool. A hung dependency taking down unrelated
endpoints is the classic way a nice-to-have becomes an outage.

## The fallback

Keyword and phrase matching against the catalogue vocabulary, plus a few
hand-written rules for the two dimensions that matter most: session length,
and whether the player wants company.

It is not clever, and it is not meant to be. It is correct, instant, free, and
**incapable of hallucinating a genre that does not exist**.

```
"a quick competitive game"
  -> multiplayer: true      (competitive is a multiplayer hint)
  -> maxSessionMinutes: 10  (quick)
  -> tags: [competitive]
  -> degraded: true
```

An explicit number beats a vague phrase: "20 minutes" is a stronger statement
of intent than "quick". Single-player hints are checked *before* multiplayer
ones, because phrases like "not multiplayer" and "solo story" both contain
multiplayer-adjacent words, and the explicit solo signal is more reliable.

For the assistant, the fallback deliberately does **not** attempt prose. A
template-generated sentence pretending to be an assistant reply would be worse
than saying plainly that AI is unavailable: it would look like the feature
working while giving the user nothing.

## Honesty in the response

Both AI responses carry `degraded`. The assistant additionally carries
`grounded`, true only when catalogue context was supplied, because a model
asked about a catalogue it has never seen will confidently invent entries.

Surfacing these means the UI can be honest, and it makes silent degradation
countable rather than invisible.

## Prompt injection

`sanitise()` strips common break-out phrasing and caps length. This is a
**reduction in attack surface, not a guarantee.** No input filter makes an LLM
injection-proof, and claiming otherwise would be the dangerous part.

The real containment is architectural: model output is only ever used as
search filters drawn from a fixed vocabulary, or as display text. It never
becomes a query, a command, a file path, or an authorisation decision. The
worst a successful injection achieves is a strange search result.

Prompts are length-capped at the DTO as well as at the provider, so an
oversized prompt is rejected at the edge before it costs anything.

## Secrets

The key is read from `GEMINI_API_KEY` and sent in the `x-goog-api-key`
**header**, never a query string. Query strings land in access logs, proxy
logs and browser history.

`AiProperties.toString()` is overridden to redact it. That override is the
only thing standing between a debug-level log statement and a credential in
the log store.

## Cost control

| Control | Value |
|---|---|
| Prompt length | 500 characters |
| Output tokens | 1024 |
| Temperature | 0.1 for extraction, 0.2 for answers |
| Rate limit | 20 per minute per principal, separate from the general 300 |
| Authentication | Required, unlike catalogue browsing |

The AI rate limit is separate because these calls cost real money and are far
slower than the rest of the API. One shared limit would have to be set
generously for normal traffic, leaving the expensive endpoints unprotected.

Anonymous access would make the endpoint a free proxy to a paid API, and the
rate limiter would have nothing to key on.

Temperature is low because this is extraction, not creative writing. A
deterministic answer to the same prompt is the desirable property.

## Observability

`gamehub_ai_request_total{result="success"|"failure"|"invalid"}`

`invalid` is separated from `failure` deliberately. A provider that is **up
but returning unusable output** is a different problem from one that is down,
and it needs a different response: a prompt change, not a page.

The AI failure alert is `severity: info` and never pages. The path degrades
rather than failing, so the alert is how we find out the provider is unhealthy
before the bill does.

## Limitations

- **No caching of AI responses.** Identical prompts cost twice. A prompt-hash
  cache is the obvious next step.
- **Static vocabulary.** It matches the seeded catalogue; a larger one would
  derive it from a cached DISTINCT query.
- **No streaming.** Assistant answers arrive whole.
- **No evaluation harness.** There is no measurement of how often intent
  extraction is *correct*, only of whether it returned something usable. That
  is the single biggest gap in this design.
