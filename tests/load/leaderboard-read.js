// Leaderboard reads.
//
// The scenario the Redis sorted set exists for. What matters is not the
// absolute latency but its flatness: ZREVRANK is O(log N), so a deep page
// should cost roughly what a shallow one does. A curve that climbs with page
// depth means reads have fallen back to Postgres, where rank is O(better
// players).
//
// The response carries servedFrom, so the split between REDIS and POSTGRES is
// measurable directly rather than inferred from latency.

import http from 'k6/http';
import { check, group } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { BASE_URL, THRESHOLDS, GAME_IDS, randomFrom } from './config.js';

const servedFromRedis = new Counter('leaderboard_served_from_redis');
const servedFromPostgres = new Counter('leaderboard_served_from_postgres');

// Latency split by page depth. If Redis is doing its job these three stay
// within noise of each other.
const shallowPage = new Trend('leaderboard_page_shallow', true);
const middlePage = new Trend('leaderboard_page_middle', true);
const deepPage = new Trend('leaderboard_page_deep', true);

export const options = {
  scenarios: {
    // Constant arrival rate rather than constant VUs. Leaderboards are read
    // in bursts when players finish a game, and arrival-rate load keeps
    // pressure constant even as latency changes, which is what reveals a
    // degradation instead of masking it behind a queue of waiting VUs.
    leaderboard: {
      executor: 'constant-arrival-rate',
      rate: 200,
      timeUnit: '1s',
      duration: '2m',
      preAllocatedVUs: 50,
      maxVUs: 200,
    },
  },
  thresholds: {
    ...THRESHOLDS.cachedRead,
    leaderboard_page_shallow: ['p(95)<150'],
    // Only slightly looser than shallow, and that is the point of the test.
    // A large gap here is the failure being looked for.
    leaderboard_page_deep: ['p(95)<250'],
    checks: ['rate>0.99'],
  },
};

export default function () {
  group('global leaderboard, shallow', () => {
    const response = http.get(
      `${BASE_URL}/api/leaderboards/global?page=0&size=20`,
      { tags: { name: 'leaderboard_global_shallow' } },
    );

    shallowPage.add(response.timings.duration);
    recordSource(response);

    check(response, {
      'returned 200': (r) => r.status === 200,
      'ranks are one-based and ascending': (r) => {
        const entries = r.json('entries');
        if (entries.length === 0) {
          return true;
        }
        return entries[0].rank === 1
          && entries.every((entry, i) => i === 0 || entry.rank === entries[i - 1].rank + 1);
      },
    });
  });

  group('global leaderboard, middle', () => {
    const response = http.get(
      `${BASE_URL}/api/leaderboards/global?page=10&size=20`,
      { tags: { name: 'leaderboard_global_middle' } },
    );
    middlePage.add(response.timings.duration);
    recordSource(response);
    check(response, { 'returned 200': (r) => r.status === 200 });
  });

  group('global leaderboard, deep', () => {
    // Page 50 at size 20 is rank 1000. Against a ZSET this is a skip-list
    // seek; against Postgres it is an OFFSET that walks a thousand rows.
    const response = http.get(
      `${BASE_URL}/api/leaderboards/global?page=50&size=20`,
      { tags: { name: 'leaderboard_global_deep' } },
    );
    deepPage.add(response.timings.duration);
    recordSource(response);
    check(response, { 'returned 200': (r) => r.status === 200 });
  });

  group('per-game leaderboard', () => {
    const gameId = randomFrom(GAME_IDS);
    const response = http.get(
      `${BASE_URL}/api/games/${gameId}/leaderboard?page=0&size=20`,
      { tags: { name: 'leaderboard_game' } },
    );

    recordSource(response);
    check(response, { 'returned 200': (r) => r.status === 200 });
  });
}

/**
 * Counts which layer answered.
 *
 * The endpoint reports this deliberately, so a Redis outage shows up as a
 * countable shift rather than as an unexplained latency change.
 */
function recordSource(response) {
  if (response.status !== 200) {
    return;
  }
  if (response.json('servedFrom') === 'REDIS') {
    servedFromRedis.add(1);
  } else {
    servedFromPostgres.add(1);
  }
}
