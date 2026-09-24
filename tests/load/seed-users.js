// Creates the accounts every other scenario logs in as.
//
// Run once before any load scenario. Idempotent: an account that already
// exists returns 409, which is treated as success rather than as a failure.
//
// Separate from the scenarios on purpose. Registering inside a load test
// would make BCrypt at cost 12 the dominant cost of every iteration, so the
// measurement would be of password hashing rather than of the endpoint.

import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, LOAD_USER_COUNT, LOAD_USER_PASSWORD, usernameFor } from './config.js';

export const options = {
  vus: 1,
  iterations: 1,
  // No thresholds. This is setup, not a measurement.
};

const REGIONS = ['NA_EAST', 'NA_WEST', 'EU_WEST', 'EU_CENTRAL', 'AP_SOUTHEAST'];

export default function () {
  let created = 0;
  let existing = 0;

  for (let i = 0; i < LOAD_USER_COUNT; i++) {
    const username = usernameFor(i);

    const response = http.post(
      `${BASE_URL}/api/auth/register`,
      JSON.stringify({
        username: username,
        email: `${username}@loadtest.invalid`,
        password: LOAD_USER_PASSWORD,
        displayName: `Load Tester ${i}`,
        // Spread across regions so matchmaking has more than one bucket.
        // A single region would make every join land in one queue, which is
        // not the shape the matchmaker is designed for.
        region: REGIONS[i % REGIONS.length],
      }),
      { headers: { 'Content-Type': 'application/json' } },
    );

    if (response.status === 201) {
      created++;
    } else if (response.status === 409) {
      existing++;
    } else {
      console.error(`unexpected status ${response.status} for ${username}: ${response.body}`);
    }

    check(response, {
      'registration succeeded or already existed': (r) =>
        r.status === 201 || r.status === 409,
    });
  }

  console.log(`seed complete: ${created} created, ${existing} already existed`);
}
