# GameHub Android client

Kotlin, Jetpack Compose, Hilt, Retrofit, Room.

## Status: NOT BUILT

**This module has never been compiled.** No Android SDK is installed on the
machine it was written on, so there is no `local.properties`, no Gradle
wrapper for this module, and no build output.

Treat every file here as unverified. The backend is compiled and tested; this
is not, and saying otherwise would be the one claim in this repository that
was not checked.

## Building it

```bash
# 1. Install the Android SDK (Android Studio, or command-line tools)
# 2. Point Gradle at it
echo "sdk.dir=/path/to/Android/sdk" > android/local.properties

# 3. Generate the wrapper and build
cd android
gradle wrapper --gradle-version 8.12
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

The debug build targets `http://10.0.2.2:8080`, the emulator alias for the
host machine. `localhost` inside an emulator resolves to the emulator itself.

## Architecture

```
UI (Compose)  ->  ViewModel  ->  Repository  ->  Retrofit  (network)
                                            \->  Room      (cache)
```

Unidirectional. The UI observes a `StateFlow`, emits events upward, and holds
no business logic.

### The decision that shapes the rest

**Room is the source of truth for the UI, not the network response.**

The screen observes a Room `Flow`. A refresh writes to Room, the Flow
re-emits, and the screen updates with no coordination in the ViewModel. That
inversion is what makes an offline launch show content instead of a spinner,
and a failed refresh keep content on screen with a banner rather than
replacing it with an error page.

### What is cached, and what deliberately is not

| Data | Cached | Why |
|---|---|---|
| Game catalogue | Yes | Public, slow-changing, needed offline |
| Profile | Yes | Renders the Profile screen instantly |
| Leaderboards | **No** | A stale rank next to a fresh board reads as a bug |
| Matchmaking tickets | **No** | A stale ticket is actively wrong |
| Cloud saves | **No** | Would invite writing from a version the device no longer holds, manufacturing the conflict the server guards against |
| Achievements | **No** | Change as a direct result of something the player just did |

## State modelling

`UiState` is a sealed interface, not a data class with `isLoading`, `data` and
`error` fields. Independent fields let the compiler permit "loading and
errored with data", which is meaningless and which every composable then has
to decide how to render.

`UiState.Success` carries `isStale`, so cached data and an offline banner are
one state rather than a contradiction.

## Honesty in the UI

Two API flags are surfaced rather than hidden:

- **`degraded`** on AI responses — shows "Interpreted without AI" when the
  deterministic fallback answered. Hiding it would present keyword matching as
  if a model had understood the request.
- **`servedFrom`** on leaderboards — available for a "showing cached
  standings" indicator during a Redis outage.

## Details worth knowing

**Token refresh is an OkHttp `Authenticator`, not an interceptor.** OkHttp
calls authenticators only after a 401 and **serialises** them. In an
interceptor, ten concurrent 401s would trigger ten refreshes racing to
overwrite the token store. It gives up after one attempt (`priorResponse`),
because a second 401 means the refresh token is itself invalid and retrying is
an infinite loop.

**Cleartext HTTP is scoped to one host.** `network_security_config.xml` permits
it for `10.0.2.2` only. A blanket `usesCleartextTraffic="true"` would permit
plaintext to any host in every build, including release.

**HTTP body logging is debug-only.** In release it would write bearer tokens
and cloud-save payloads to logcat.

**`allowBackup="false"`**, so the token store is not copied to Google Drive.

**Tokens are stored unencrypted in app-private storage.** A deliberate trade,
stated rather than hidden: on a non-rooted device that is already inaccessible
to other apps, and an attacker with root has the Keystore key too. What
actually limits damage is the 15-minute access token lifetime. A product
handling payment would use `EncryptedSharedPreferences`.

## Tests

`MatchmakingViewModelTest` — 7 tests, JVM only, no emulator. They cover the
behaviours that are easy to get wrong:

- A duplicate join surfaces as "already queued", **not** an error.
- A transient poll failure does not drop the player from the queue.
- An expired ticket is distinct from a failure, because the useful action
  differs.
- Leaving returns to idle even when the request fails, since leaving is
  idempotent.

**Status: NOT EXECUTED**, along with everything else in this module.

## What is not built

- Home, Profile, Game detail, Leaderboard, Achievements, Cloud Saves,
  Assistant and Settings screens. Routes and ViewModels exist for some;
  the composables are placeholders.
- Cloud-save conflict resolution UI. The repository models the conflict as a
  first-class `SaveOutcome.Conflict` carrying `serverVersion`; no screen
  presents the choice yet.
- Instrumentation tests.
- Real latency measurement for matchmaking. A constant is sent, with a comment
  explaining why an honest default beats zero.
- App icons and launcher assets.
