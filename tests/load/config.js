// Shared configuration for every k6 scenario.
//
// Centralised so a threshold change applies everywhere at once, and so the
// base URL is overridable without editing scripts.

export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// Credentials for the load-test accounts created by seed-users.js.
// Deliberately weak and deliberately public: these exist only against a local
// throwaway database and are never valid anywhere else.
export const LOAD_USER_PREFIX = 'loadtest_user_';
export const LOAD_USER_PASSWORD = 'load-test-password-not-a-secret';
export const LOAD_USER_COUNT = 50;

// Seeded game ids from V2__seed_catalogue.sql. Hardcoded rather than
// discovered at runtime, so a scenario measures the endpoint under test and
// not a lookup that precedes it.
export const GAME_IDS = [
  'a0000000-0000-4000-8000-000000000001', // Stellar Drift, multiplayer
  'a0000000-0000-4000-8000-000000000003', // Ironroot Siege, multiplayer
  'a0000000-0000-4000-8000-000000000005', // Circuit Breakers, multiplayer
  'a0000000-0000-4000-8000-000000000002', // Tide and Tessera, single player
  'a0000000-0000-4000-8000-000000000004', // Paper Lantern, single player
];

export const MULTIPLAYER_GAME_IDS = [
  'a0000000-0000-4000-8000-000000000001',
  'a0000000-0000-4000-8000-000000000003',
  'a0000000-0000-4000-8000-000000000005',
];

// Service-level objectives, expressed so k6 exits non-zero when one is missed.
// That is what makes these a gate rather than a report.
//
// Stated as targets, not predictions. Nothing here is derived from a measured
// run, because no run has happened.
export const THRESHOLDS = {
  cachedRead: {
    http_req_duration: ['p(95)<200', 'p(99)<500'],
    http_req_failed: ['rate<0.01'],
  },
  uncachedRead: {
    http_req_duration: ['p(95)<400', 'p(99)<800'],
    http_req_failed: ['rate<0.01'],
  },
  write: {
    http_req_duration: ['p(95)<500', 'p(99)<1000'],
    http_req_failed: ['rate<0.01'],
  },
};

/** Picks a stable user index for this VU, so a VU reuses one account. */
export function userIndexForVu() {
  return (__VU - 1) % LOAD_USER_COUNT;
}

export function usernameFor(index) {
  return `${LOAD_USER_PREFIX}${index}`;
}

export function randomFrom(array) {
  return array[Math.floor(Math.random() * array.length)];
}

/**
 * Logs in and returns an Authorization header.
 *
 * Called once per VU in setup or init rather than per iteration. Logging in
 * every iteration would make BCrypt at cost 12 the thing being measured, not
 * the endpoint under test.
 */
export function authHeaderFor(http, index) {
  const response = http.post(
    `${BASE_URL}/api/auth/login`,
    JSON.stringify({
      username: usernameFor(index),
      password: LOAD_USER_PASSWORD,
    }),
    { headers: { 'Content-Type': 'application/json' } },
  );

  if (response.status !== 200) {
    throw new Error(
      `login failed for ${usernameFor(index)}: ${response.status} ${response.body}. ` +
      'Run seed-users.js first.',
    );
  }

  return {
    'Content-Type': 'application/json',
    Authorization: `Bearer ${response.json('accessToken')}`,
  };
}
