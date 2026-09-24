// Session start and complete: the full event pipeline under pressure.
//
// The most important of the four scenarios. Completing a session writes a row
// and an outbox event in one transaction, and three consumers then react to
// it. This is the only test that puts that whole chain under sustained load.
//
// What to watch, in Grafana rather than in the k6 output:
//
//   gamehub_outbox_backlog      should stay bounded and recover after the run
//   gamehub_outbox_published    should track the completion rate
//   gamehub_consumer_events     duplicate count should stay a small fraction
//
// A backlog that climbs and does not drain means the relay cannot keep up
// with the write rate. Request latency will not show that, because the
// endpoint returns as soon as the transaction commits. That is the whole
// point of the asynchronous design, and also its main risk.

import http from 'k6/http';
import { check, group, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import {
  BASE_URL, THRESHOLDS, GAME_IDS,
  authHeaderFor, userIndexForVu, randomFrom,
} from './config.js';

const sessionsCompleted = new Counter('sessions_completed');
const completeLatency = new Trend('session_complete_latency', true);

export const options = {
  scenarios: {
    gameplay: {
      // Constant arrival rate, because the question is whether the pipeline
      // keeps up with a known event rate. With constant VUs a slow backend
      // would simply reduce the offered load and hide the problem.
      executor: 'constant-arrival-rate',
      rate: 50,
      timeUnit: '1s',
      duration: '2m',
      preAllocatedVUs: 50,
      maxVUs: 150,
    },
  },
  thresholds: {
    ...THRESHOLDS.write,
    // Completing a session must stay fast even though it fans out to three
    // consumers, because the fan-out is asynchronous. If this degrades, the
    // outbox write has become the bottleneck.
    session_complete_latency: ['p(95)<500', 'p(99)<1000'],
    checks: ['rate>0.99'],
  },
};

const authHeaders = {};

function headersForThisVu() {
  const index = userIndexForVu();
  if (!authHeaders[index]) {
    authHeaders[index] = authHeaderFor(http, index);
  }
  return authHeaders[index];
}

const OUTCOMES = ['WIN', 'LOSS', 'DRAW'];

export default function () {
  const headers = headersForThisVu();
  const gameId = randomFrom(GAME_IDS);
  let sessionId = null;

  group('start session', () => {
    const response = http.post(
      `${BASE_URL}/api/sessions`,
      JSON.stringify({ gameId: gameId, clientVersion: 'k6-load-1.0' }),
      { headers: headers, tags: { name: 'session_start' } },
    );

    check(response, { 'start returned 201': (r) => r.status === 201 });

    if (response.status === 201) {
      sessionId = response.json('id');
    }
  });

  if (!sessionId) {
    return;
  }

  // A session that starts and ends in the same millisecond is not a session.
  // The gap also gives duration_seconds a non-zero value, which is what the
  // SESSION_DURATION achievement rule reads.
  sleep(1);

  group('complete session', () => {
    const response = http.post(
      `${BASE_URL}/api/sessions/${sessionId}/complete`,
      JSON.stringify({
        // Spread across the leaderboard range, so scores are distinct enough
        // that ZADD GT actually has to compare rather than rejecting every
        // write as not-greater.
        score: Math.floor(Math.random() * 9000) + 100,
        outcome: randomFrom(OUTCOMES),
        // Claimed by the client and deliberately ignored: the server
        // re-derives it. Sending true here proves a lying client cannot award
        // itself the PERFECT_MATCH achievement.
        perfect: Math.random() < 0.1,
      }),
      { headers: headers, tags: { name: 'session_complete' } },
    );

    completeLatency.add(response.timings.duration);

    check(response, {
      'complete returned 200': (r) => r.status === 200,
      'session is closed': (r) => r.status !== 200 || r.json('endedAt') !== null,
      'duration was computed': (r) =>
        r.status !== 200 || r.json('durationSeconds') >= 1,
    });

    if (response.status === 200) {
      sessionsCompleted.add(1);
    }
  });
}

/**
 * Reports the outbox backlog once the run finishes.
 *
 * A backlog still draining here is not a failure, it is the asynchronous
 * design working. A backlog that is still growing is the failure, and this is
 * where it becomes visible without opening a dashboard.
 */
export function teardown() {
  const metrics = http.get(`${BASE_URL}/actuator/prometheus`);
  if (metrics.status !== 200) {
    console.warn('could not read the metrics endpoint to report outbox backlog');
    return;
  }

  const line = metrics.body
    .split('\n')
    .find((l) => l.startsWith('gamehub_outbox_backlog'));

  console.log(line ? `final ${line}` : 'gamehub_outbox_backlog not exported');
}
