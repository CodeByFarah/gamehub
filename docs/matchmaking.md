# Matchmaking

## The problem

Given a continuously changing queue of players, pair them so that matches are
fair, fast to find, and nobody waits forever. Those three goals are in direct
tension: the fairest possible match is the one you find after waiting an hour.

## Inputs

Each queued player contributes a ticket:

| Field | Source | Notes |
|---|---|---|
| `skillRating` | Server, from the profile | **Never** taken from the request. A client-supplied rating would let anyone queue as a beginner and farm real beginners |
| `region` | Server, from the profile | Deployment region, not geography |
| `latencyMs` | Client-measured | The server cannot measure this, so it is accepted and clamped. A dishonest value only degrades the match quality of the player who sent it |
| `enqueuedAt` | Server | Clamped at read time, so clock skew cannot produce a negative wait |

## The cost function

Lower is better; zero is perfect.

```
cost(a,b) = skillWeight    · skillTerm
          + latencyWeight  · latencyTerm
          + regionWeight   · regionTerm
          − patienceWeight · patienceBonus
```

Every term is normalised into `[0,1]` **before** weighting. This is the part
that makes the weights mean what they say. Mixing a raw Elo gap (hundreds), a
raw latency (tens) and a region flag (0 or 1) in one sum lets whichever
quantity has the largest units silently dominate.

| Term | Definition | Why |
|---|---|---|
| `skillTerm` | `min(1, |Δrating| / 400)` | 400 is the Elo convention: roughly 10:1 expected odds, the point at which a match stops being a game. Clamped, because past that the match is already a write-off and letting the term grow to 5.0 would make latency and region irrelevant |
| `latencyTerm` | `min(1, max(a,b) / 150ms)` | The **worse** of the two, not the mean. A 20ms player paired with a 300ms player does not experience a 160ms match; both experience the 300ms one |
| `regionTerm` | Affinity table: 0 same, 0.35 neighbour, 1.0 distant | Modelled as data so the cost function stays pure arithmetic |
| `patienceBonus` | `min(1, maxWait / 45s)` | **Subtracted**, and driven by the longer-waiting ticket |

Cost may go negative for a very patient pair. That is intentional and
harmless: only the *ordering* of costs and the comparison against the
acceptance threshold matter.

### Symmetry

`cost(a,b) == cost(b,a)`, by construction — every term is an absolute
difference, a max, or a symmetric table lookup. This is not cosmetic. The
engine evaluates each unordered pair once, so an asymmetric cost would make
results depend on iteration order, and therefore on Redis key ordering. Two
instances would disagree about the same queue.

Asserted as a property over 120 random tickets in `MatchCandidateScorerTest`.

## Liveness: why nobody waits forever

Two independent mechanisms, and both are needed:

**1. The skill window widens with wait.**

```
window(t) = 100 + 25·t  Elo, capped at 1200
```

Linear, not exponential. This window bounds a `ZRANGEBYSCORE`: doubling it
doubles the candidates scanned. Exponential growth would turn a queue that is
merely slow into one that is also expensive, at exactly the moment the system
is already under pressure.

**2. The acceptance threshold relaxes with wait.**

```
threshold(t) = 0.25 + 0.65 · min(1, t/45s)
```

Without this, a player in a thin population could wait forever for a perfect
opponent who never arrives. Tolerance rises, so every ticket eventually
accepts an imperfect match rather than none.

The first decides *who picks first*; the second guarantees *there is
eventually something acceptable to pick*. Either alone leaves a hole.

## The algorithm

1. Sort tickets by wait **descending** — the starvation guard.
2. For each still-unmatched ticket, scan candidates inside its skill window.
3. Push every acceptable pairing into a priority queue keyed by cost.
4. Drain cheapest-first, committing a pairing only if **both** tickets are
   still unclaimed.

Step 4 is what makes this *globally* greedy rather than merely locally greedy:
pairings are committed in order of quality across the whole bucket, not in the
order tickets happened to be visited.

### Complexity

Let N = tickets in the bucket, K = average candidates inside a skill window.

| Stage | Cost |
|---|---|
| Sort | O(N log N) |
| Candidate generation and scoring | O(N·K), each score O(1) |
| Priority queue inserts | O(N·K·log(N·K)) |
| Drain, with O(1) claim checks | O(N·K) |

**Overall: O(N·K·log(N·K)) time, O(N·K) space.**

K is bounded by `maxSkillWindowElo` and by `candidateLimit`, so this is
near-linear in N for realistic queues. The degenerate case is every player in
one narrow rating band, where K → N and the pass becomes O(N² log N);
`candidateLimit = 50` is the hard stop that keeps that from taking the tick
down. `GreedyMatchmakingEngineTest` covers it with 1000 identical tickets.

### Why greedy and not optimal

Minimum-weight perfect matching is solvable exactly by Blossom in O(N³). That
is the right algorithm for a different problem:

- The input is a **snapshot**. By the time an optimal solution is computed,
  tickets have arrived and left — the precision is spent on a queue that no
  longer exists.
- The tick runs on a one-second budget. At N = 10,000, an O(N³) pass is ~10¹²
  operations. It would not finish.
- Players judge **their own** match, not the global optimum. Greedy gives each
  ticket the best partner still available, which is the property that is
  actually visible to a player.

Greedy here is not a shortcut around the hard algorithm. It is the better fit
for a queue that mutates faster than an exact solver can run.

## Concurrency: the race that matters

### Problem

A player double-taps Find Match, or a flaky connection makes the client retry
a request that actually succeeded. Two joins arrive at once, and behind a load
balancer they land on different instances.

### Race condition

The obvious implementation reads *does this player already have a waiting
ticket?* and inserts if not. Between the read and the insert, the other
request does the same. Both see nothing, both insert.

The consequence is not cosmetic. The matchmaker can pair each ticket
separately, putting one player into two games at once. Both opponents then
believe they have a match against someone who can only play one of them.

### Why an application check cannot fix it

No amount of care in Java closes this:

- The two requests are in different transactions, possibly different JVMs.
- `synchronized` guards one instance and does nothing across three.
- At READ COMMITTED, neither transaction can see the other's uncommitted
  insert — so both reads are **correct**. The bug is not a missing check, it
  is the absence of a serialisation point.

### Solution

```sql
CREATE UNIQUE INDEX uq_mmq_one_active_ticket_per_user
    ON matchmaking_queue (user_id) WHERE status = 'WAITING';
```

The database serialises the two inserts and rejects the loser. **Partial**, so
it constrains only active tickets and a player can queue again after a
previous ticket was matched or cancelled — a plain unique index on `user_id`
would let a player queue exactly once, ever.

`MatchmakingService` catches the resulting `DataIntegrityViolationException`
and translates it into the same 409 the fast-path check returns. That
translation *is* the mechanism, not defensive decoration. The pre-check exists
only so the ordinary duplicate avoids a write.

### Why this works

Uniqueness is enforced at the point of write, by the one component both
requests share. It holds regardless of instance count, isolation level or
request ordering.

### Tradeoffs

The loser surfaces as a constraint violation rather than a clean branch, so
the service must translate it. An untranslated violation would return 500 for
an ordinary double-tap — `MatchmakingConcurrencyTest` asserts that it does
not.

## The second race: committing a stale proposal

The engine works from an in-memory snapshot. Between the snapshot and the
commit, a player may have cancelled, or another instance may have matched them
into a different game.

```sql
UPDATE matchmaking_queue
   SET status = 'MATCHED', match_id = :matchId, matched_at = now()
 WHERE id IN (:ticketIds) AND status = 'WAITING';
```

The caller compares the returned count against the number of tickets it meant
to claim. Anything less means the proposal is stale, and the whole transaction
rolls back — so no half-formed match is ever visible and the surviving ticket
is untouched for the next tick.

Two instances proposing the same pair is therefore safe: both run this, one
claims both tickets, the other claims zero and rolls back. The
`gamehub_matchmaking_stale_proposals` counter makes the collision rate
visible; a sustained high ratio to matches means the per-bucket lease is not
deduplicating effectively.

## Why the Redis lease is an optimisation, not the safety mechanism

Each `(game, region)` bucket is leased before a tick. If the lease failed open
and three instances ran the same bucket, they would read the same pool and
propose overlapping pairings — and the atomic claim above would reject the
duplicates. The outcome stays correct; only wasted work changes.

That property is what makes a plain Redis lease acceptable here. Redis locks
are not safe under arbitrary failure: a paused holder can overrun its TTL, and
a failover can lose the key. Systems that depend on a lock for correctness
need fencing tokens or a consensus service. GameHub does not, because nothing
depends on the lease for correctness.

Leases are **per bucket**, not global. One global lease would serialise all
matchmaking onto one instance and make tick duration the sum of every bucket.

## Failure cases

| Case | Behaviour |
|---|---|
| Fewer than two tickets | Empty result. Callers treat "no proposals" as normal |
| Nothing within any threshold | Empty result; thresholds relax next tick |
| Stale snapshot | Atomic claim rejects it, transaction rolls back |
| Clock skew | `waitSeconds` clamps at zero, so priority ordering cannot invert |
| Thin population | Ticket eventually hits its 5-minute TTL and is expired, logged at INFO — the signal that a bucket has too few players |
| Redis unavailable | Lease acquisition fails closed, tick is skipped. Players wait one more second |
| Postgres unavailable | Tick logs and returns. No partial state |

## Observability

| Metric | Question it answers |
|---|---|
| `gamehub_matchmaking_queue_depth` | Are players accumulating? |
| `gamehub_matchmaking_matches_total` | Is the queue draining? |
| `gamehub_matchmaking_stale_proposals_total` | Are instances colliding? |
| `gamehub_matchmaking_tick_seconds` p95 | Is a tick still finishing inside its 1s interval? |
| `matches.match_quality` | Is match quality degrading after a policy change? |

Match quality is persisted per match specifically so that question is
answerable from data rather than anecdote.

## Tuning

Every weight and threshold is in `application.yml` under
`gamehub.matchmaking`, bound to `MatchmakingProperties` and validated at
startup. A nonsensical weight stops the instance from booting rather than
surfacing hours later as players quietly getting unfair matches.

## What is deliberately not built

- **Team matchmaking.** The engine produces pairs. Balancing two teams of five
  is a genuinely different algorithm, not a parameter change.
- **Predicted wait times.** The API returns the patience horizon as a hint.
  A real estimate needs an arrival-rate model per bucket.
- **Skill rating updates.** Ratings are read, never written. An Elo update
  after each match is a separate concern.
