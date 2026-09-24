# API

Base URL: `http://localhost:8080`
Interactive: `/swagger-ui.html` · OpenAPI JSON: `/v3/api-docs`

## Conventions

**Identity comes from the token, never the request.** No endpoint accepts a
user id as a parameter or body field. An endpoint that took caller identity
from the request is one forgotten authorisation check away from letting anyone
read another player data, and that mistake is easy to make and hard to spot in
review. Taking it from the verified token makes the mistake impossible to
express.

**Entities never cross the wire.** Every response is a DTO.
`ArchitectureTest` fails the build if an entity reaches the API package.

**404 rather than 403 for resources you do not own.** Returning 403 confirms
that a resource exists.

## Error envelope

One shape for every failure, so a client writes one error handler rather than
one per endpoint.

```json
{
  "timestamp": "2026-09-22T11:04:19.482Z",
  "status": 409,
  "error": "VERSION_CONFLICT",
  "message": "cloud save has been updated by another client",
  "path": "/api/cloud-saves",
  "traceId": "3f2b1c9d8e7a6b5c",
  "details": [],
  "meta": { "serverVersion": 18, "serverChecksum": "9f86d081" }
}
```

| Field | Notes |
|---|---|
| `error` | **Stable machine-readable code.** Clients branch on this. Never localised, never reworded, because changing it is a breaking API change even though it looks like prose |
| `message` | For developers. Safe to change |
| `traceId` | Also on every log line for the request. A user can quote it in a bug report |
| `details` | Field-level validation failures. Rejected values are **redacted**, never echoed |
| `meta` | Error-specific context, such as the server version on a conflict |

### Codes

`VALIDATION_FAILED` · `NOT_FOUND` · `CONFLICT` · `VERSION_CONFLICT` ·
`ALREADY_QUEUED` · `UNAUTHORIZED` · `FORBIDDEN` · `RATE_LIMITED` ·
`AI_UNAVAILABLE` · `DEPENDENCY_UNAVAILABLE` · `INTERNAL_ERROR`

## Authentication, public

| Method | Path | Returns |
|---|---|---|
| POST | `/api/auth/register` | 201 with tokens and profile |
| POST | `/api/auth/login` | 200 with tokens |
| POST | `/api/auth/refresh` | 200 with a new access token |

Login returns an identical 401 whether the username is unknown or the password
is wrong, and the unknown-user path still performs a BCrypt comparison against
a dummy hash. Returning early would make an unknown username measurably
faster, which is a timing side channel that leaks which accounts exist.

Registration returns a 409 that names no field, for the same reason.

## Games, public

| Method | Path | Notes |
|---|---|---|
| GET | `/api/games` | Browse, search or filter |
| GET | `/api/games/{id}` | Detail |
| GET | `/api/games/slug/{slug}` | Detail by slug, for shareable links |

One endpoint for all three modes, because the Discover screen moves between
them as the user types and toggles facets. Splitting them would make the
client juggle three URLs for what is, to the user, one evolving query.

```
GET /api/games?q=stellar
GET /api/games?genre=Racing&multiplayer=true&maxSessionMinutes=20
GET /api/games?page=2&size=20
```

Search responses use `totalItems: -1`. Computing a real total needs a second
COUNT over the full-text match, doubling the cost of every search to populate
a number the UI only uses for infinite scroll.

## Leaderboards, public

| Method | Path |
|---|---|
| GET | `/api/games/{gameId}/leaderboard` |
| GET | `/api/leaderboards/global` |
| GET | `/api/leaderboards/regional/{region}` |

Public so a leaderboard is shareable. The caller own standing is resolved only
when a token happens to be present, which is why the principal is read from
`Authentication` rather than injected with `@CurrentUser`: the latter throws
on an anonymous request, and anonymous is legitimate here.

`viewerEntry` is resolved even when it falls outside the requested page, so
the UI can pin it without a second request.

`servedFrom` is `REDIS` or `POSTGRES`. Surfaced deliberately: during a Redis
outage the data is correct but the path is slower, and making that visible
turns an invisible degradation into something a dashboard can count.

## Matchmaking, authenticated

| Method | Path | Returns |
|---|---|---|
| POST | `/api/matchmaking/join` | **202**, or 409 if already queued |
| DELETE | `/api/matchmaking/leave` | 204, always |
| GET | `/api/matchmaking/tickets/{id}` | 200 |

**202, not 201.** The ticket exists, but the match it is waiting for does not
yet and may never. 201 would imply a finished resource.

Leave returns 204 whether or not a ticket was found. A client cancelling
something already matched, or retrying a cancel, has reached the state it
wanted and should not be shown an error.

`skillRating` is **not** accepted from the client; it is read from the
profile. A client-supplied rating would let anyone queue as a beginner and
farm real beginners. `latencyMs` *is* client-supplied, because the server
cannot measure it. It is clamped, and a dishonest value only degrades the
match quality of the player who sent it.

## Cloud saves, authenticated

| Method | Path | Notes |
|---|---|---|
| GET | `/api/cloud-saves` | Metadata only, no payloads |
| GET | `/api/cloud-saves/{gameId}?slot=0` | Includes the payload |
| POST | `/api/cloud-saves` | 200, or **409 carrying merge data** |

The listing omits payloads so the Cloud Saves screen does not transfer several
megabytes to render a list of slots.

`expectedVersion` is **required, with no default**. Making it optional would
let a client omit it and silently get last-write-wins, which is exactly the
data loss this design exists to prevent. Zero means create, and fail if one
exists.

The 409 is part of the contract, not an error condition. A client that treats
it as a generic failure will lose player progress. See
[cloud-saves.md](cloud-saves.md).

## Sessions, authenticated

| Method | Path | Returns |
|---|---|---|
| POST | `/api/sessions` | 201. Resumes an open session for the same game |
| POST | `/api/sessions/{id}/complete` | 200 |
| GET | `/api/sessions` | 200 |

Completing a session is the highest-fan-out write in the product. It stays
fast because the fan-out happens asynchronously through the outbox, so
statistics, achievements and leaderboards may lag the response by a moment.

The `perfect` flag is accepted and then **ignored**: the server re-derives it
from outcome and score, so a modified client cannot award itself an
achievement.

## Players, authenticated

| Method | Path |
|---|---|
| GET | `/api/users/me` |
| GET | `/api/users/{userId}` (public profile) |
| GET | `/api/users/me/achievements?gameId=` |
| GET | `/api/users/me/recommendations?limit=10` |

`/me` and `/{userId}` are separate endpoints rather than one with an optional
id. Collapsing them is where authorisation bugs come from, because the safe
and unsafe cases end up sharing a code path and one conditional.

With a `gameId`, achievements include **locked** ones too. A list showing only
what you already have is a trophy cabinet, not a goal list, and the goals are
the part that drives play.

## AI, authenticated and rate limited separately

| Method | Path |
|---|---|
| POST | `/api/ai/search` |
| POST | `/api/ai/assistant` |

Authenticated when browsing is not, because every call can cost money at a
third-party provider. Anonymous access would make this a free proxy to a paid
API, and the per-principal rate limit would have nothing to key on.

Both responses carry `degraded: true` when the answer came from the
deterministic fallback rather than a model. The assistant also carries
`grounded`.

## Operational

`/actuator/health/liveness` · `/actuator/health/readiness` ·
`/actuator/prometheus`

Readiness includes Postgres and Redis; liveness deliberately does not.
Restarting a process because Redis is down fixes nothing and turns a degraded
service into an unavailable one.

## Paging

```json
{ "items": [], "page": 0, "size": 20, "totalItems": 150,
  "totalPages": 8, "hasNext": true }
```

Deliberately **not** Spring Data `Page`. That type serialises a large,
unstable structure that is an implementation detail of the server persistence
layer, and its JSON shape has changed between Spring versions. Pinning our own
means a Spring upgrade cannot break every mobile client in the field.

## Rate limiting

300 requests per minute per authenticated principal; 20 per minute for AI.
Fixed window in Redis via an atomic Lua script, because `INCR` and the
conditional `EXPIRE` must be atomic. Done as two round trips, a crash between
them leaves a counter with no TTL that never resets and locks the caller out
permanently.

Unauthenticated endpoints are **not** limited. A real gap, listed in the
README.
