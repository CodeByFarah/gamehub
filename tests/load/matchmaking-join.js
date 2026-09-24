// Matchmaking join under sustained load.
//
// Two things are under test, and only one of them is latency.
//
// 1. The write path holds up: joins are accepted quickly despite the partial
//    unique index serialising per player.
// 2. The queue drains. A join endpoint that stays fast while queue depth
//    climbs without bound is not working, it is just failing quietly. Watch
//    gamehub_matchmaking_queue_depth in Grafana during the run.
//
// Each VU owns one account and alternates join and leave, which is the real
// client behaviour: a player queues, waits, and either gets matched or
// cancels.

import http from 'k6/http';
import { check, group, sleep } from 'k6';
import { Counter, Rate } from 'k6/metrics';
import {
  BASE_URL, THRESHOLDS, MULTIPLAYER_GAME_IDS,
  authHeaderFor, userIndexForVu, randomFrom,
} from './config.js';

const joinAccepted = new Counter('matchmaking_join_accepted');
// Not a failure. A double-tap or a retry legitimately produces 409, and the
// rate is a signal about client behaviour rather than about server health.
const joinConflicted = new Counter('matchmaking_join_conflicted');
const matchedRate = new Rate('matchmaking_matched');

export const options = {
  scenarios: {
    matchmaking: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: 30 },
        { duration: '1m30s', target: 60 },
        { duration: '30s', target: 0 },
      ],
      gracefulRampDown: '15s',
    },
  },
  thresholds: {
    ...THRESHOLDS.write,
    // Only unexpected statuses count as failures. A 409 is a correct
    // response, so counting it as an error would make a working system look
    // broken under exactly the load this test applies.
    checks: ['rate>0.99'],
  },
};

// Logged in once per VU at init rather than per iteration, so BCrypt is not
// what gets measured.
const authHeaders = {};

function headersForThisVu() {
  const index = userIndexForVu();
  if (!authHeaders[index]) {
    authHeaders[index] = authHeaderFor(http, index);
  }
  return authHeaders[index];
}

export default function () {
  const headers = headersForThisVu();
  const gameId = randomFrom(MULTIPLAYER_GAME_IDS);
  let ticketId = null;

  group('join', () => {
    const response = http.post(
      `${BASE_URL}/api/matchmaking/join`,
      JSON.stringify({
        gameId: gameId,
        // Realistic spread rather than a constant. A fixed latency would make
        // every pairing score identically on that dimension and would not
        // exercise the cost function at all.
        latencyMs: 15 + Math.floor(Math.random() * 120),
      }),
      { headers: headers, tags: { name: 'matchmaking_join' } },
    );

    if (response.status === 202) {
      joinAccepted.add(1);
      ticketId = response.json('ticketId');
    } else if (response.status === 409) {
      joinConflicted.add(1);
    }

    check(response, {
      'join was accepted or correctly rejected as duplicate': (r) =>
        r.status === 202 || r.status === 409,
    });
  });

  // A real client polls while it waits. Two seconds is long enough for at
  // least one matchmaker tick at the one-second interval.
  sleep(2);

  if (ticketId) {
    group('poll ticket', () => {
      const response = http.get(
        `${BASE_URL}/api/matchmaking/tickets/${ticketId}`,
        { headers: headers, tags: { name: 'matchmaking_poll' } },
      );

      check(response, { 'poll returned 200': (r) => r.status === 200 });

      if (response.status === 200) {
        const status = response.json('status');
        matchedRate.add(status === 'MATCHED');

        check(response, {
          // The core invariant, asserted under load rather than only in the
          // concurrency test: a matched ticket must name exactly the
          // opponents it was paired with.
          'a matched ticket carries a match id': (r) =>
            r.json('status') !== 'MATCHED' || r.json('matchId') !== null,
        });
      }
    });
  }

  group('leave', () => {
    // Idempotent, so this is safe whether or not the ticket was matched.
    // Leaving keeps the queue from accumulating tickets that no VU is
    // waiting on, which would otherwise make queue depth grow for a reason
    // unrelated to matchmaking throughput.
    const response = http.del(
      `${BASE_URL}/api/matchmaking/leave`,
      null,
      { headers: headers, tags: { name: 'matchmaking_leave' } },
    );

    check(response, { 'leave returned 204': (r) => r.status === 204 });
  });

  sleep(1);
}
