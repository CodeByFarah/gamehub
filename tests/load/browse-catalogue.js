// Catalogue browse and search.
//
// The busiest read in the product and the one the Redis cache exists for.
// A hit rate that collapses under load means Postgres takes the full read
// volume, so this scenario is really a test of the cache, not of the endpoint.
//
// Unauthenticated, because browsing is public. That also keeps token handling
// out of the measurement.

import http from 'k6/http';
import { check, group } from 'k6';
import { Trend } from 'k6/metrics';
import { BASE_URL, THRESHOLDS, randomFrom } from './config.js';

// Separated from the aggregate http_req_duration, because cached and uncached
// paths have genuinely different targets and averaging them together would
// hide a regression in either.
const browseLatency = new Trend('browse_latency', true);
const searchLatency = new Trend('search_latency', true);

export const options = {
  scenarios: {
    // Ramping rather than a fixed rate. A cold cache behaves completely
    // differently from a warm one, and a flat load would measure whichever
    // state happened to dominate the run.
    browse: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: 20 },   // warm the cache
        { duration: '1m', target: 50 },    // steady state
        { duration: '30s', target: 100 },  // spike
        { duration: '30s', target: 0 },    // drain
      ],
      gracefulRampDown: '10s',
    },
  },
  thresholds: {
    ...THRESHOLDS.cachedRead,
    // Paging deeper than the cached first page is expected to be slower, so
    // it gets its own, looser target rather than dragging the main one down.
    browse_latency: ['p(95)<200'],
    search_latency: ['p(95)<400'],
    checks: ['rate>0.99'],
  },
};

const SEARCH_TERMS = [
  'racing', 'puzzle', 'strategy', 'multiplayer', 'relaxing',
  'stellar', 'siege', 'cards', 'roguelike',
  // A deliberate misspelling, to exercise the trigram fallback path rather
  // than only the full-text index.
  'stelar drift',
];

export default function () {
  group('browse first page', () => {
    const response = http.get(`${BASE_URL}/api/games?page=0&size=20`, {
      tags: { name: 'browse_page_0' },
    });

    browseLatency.add(response.timings.duration);

    check(response, {
      'browse returned 200': (r) => r.status === 200,
      'browse returned items': (r) => r.json('items').length > 0,
    });
  });

  group('browse deeper page', () => {
    // Page 1 is usually a cache miss on a small catalogue, so this is the
    // uncached Postgres path.
    const response = http.get(`${BASE_URL}/api/games?page=1&size=20`, {
      tags: { name: 'browse_page_1' },
    });

    browseLatency.add(response.timings.duration);
    check(response, { 'deep page returned 200': (r) => r.status === 200 });
  });

  group('search', () => {
    const term = randomFrom(SEARCH_TERMS);
    const response = http.get(
      `${BASE_URL}/api/games?q=${encodeURIComponent(term)}&size=20`,
      { tags: { name: 'search' } },
    );

    searchLatency.add(response.timings.duration);

    check(response, {
      'search returned 200': (r) => r.status === 200,
      // Not asserting on result count: a fuzzy term legitimately returns
      // nothing, and asserting otherwise would make the test fail for a
      // correct system.
      'search returned a body': (r) => r.json('items') !== undefined,
    });
  });

  group('filter', () => {
    const response = http.get(
      `${BASE_URL}/api/games?multiplayer=true&maxSessionMinutes=30&size=20`,
      { tags: { name: 'filter' } },
    );

    check(response, {
      'filter returned 200': (r) => r.status === 200,
      'filter respected the constraint': (r) =>
        r.json('items').every((game) => game.supportsMultiplayer === true),
    });
  });
}
