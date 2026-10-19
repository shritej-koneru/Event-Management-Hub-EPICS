# Event Management Hub — EPICS

A campus event platform: Android app + Node.js backend + PostgreSQL.
Students discover events, get directions, bookmark favourites, and chat live
via Socket.IO — with an offline-first mobile client.

---

## Repository layout

| Directory | What it contains |
| --- | --- |
| [`app/`](app/) | Android client — Kotlin, Jetpack Compose, Retrofit, socket.io-client |
| [`server/`](server/) | Backend — Node.js/Express, Socket.IO, PostgreSQL (embedded for dev) |
| [`contract/`](contract/) | Shared API contract (`API-CONTRACT.md`, version `1.1.0`) |
| [`docs/`](docs/) | Project handover, work summary, and beginner implementation guide |

---

## Quick start

### Backend

```bash
cd server
npm install
npm run setup      # generates .env with a random JWT_SECRET
npm start          # migrate + seed + listen on http://localhost:3000
```

Verify: `curl http://localhost:3000/ping`

### Android

1. Start the backend.
2. Emulator: use `http://10.0.2.2:3000` (default fallback) **or** run
   `adb reverse tcp:3000 tcp:3000` if the app points at `127.0.0.1`.
3. Open `app/` in Android Studio → Run `app`.

#### Outdoor map (Google Maps SDK)

Indoor navigation needs no extra setup. The Outdoor mode uses the Google
Maps SDK and needs an API key:

1. In Google Cloud Console enable **Maps SDK for Android** (billing required
   by Google) and create an API key.
2. Restrict the key to the **Maps SDK for Android** API and to your package
   name + debug/release SHA-1 fingerprints.
3. Add `MAPS_API_KEY=...` to `local.properties` (gitignored) or pass
   `-PMAPS_API_KEY=...` to Gradle. Never commit the key.

Without a key the Outdoor tab shows a setup card; Indoor navigation is
unaffected.

### Tests

```bash
cd server
npm test                    # 295 backend tests
npm run verify:migrations   # cold-build scratch DB from SQL files alone
```

Android unit tests:

```powershell
cd app
.\gradlew.bat :app:testDebugUnitTest   # 52 tests
```

---

## Documentation

| Document | Audience |
| --- | --- |
| [`docs/PROJECT_HANDOVER.md`](docs/PROJECT_HANDOVER.md) | **Next developer — start here** |
| [`docs/MY_WORK_SUMMARY.md`](docs/MY_WORK_SUMMARY.md) | Complete summary of implemented work |
| [`docs/IMPLEMENTATION_EXPLANATION.md`](docs/IMPLEMENTATION_EXPLANATION.md) | Beginner-friendly "how it works" |
| [`server/docs/WORK_HANDOVER.md`](server/docs/WORK_HANDOVER.md) | Backend-focused handover (11 sections) |
| [`server/docs/API_REFERENCE.md`](server/docs/API_REFERENCE.md) | Every REST + Socket.IO endpoint |
| [`server/docs/SETUP_AND_TESTING.md`](server/docs/SETUP_AND_TESTING.md) | Install, run, test, emulator |
| [`contract/API-CONTRACT.md`](contract/API-CONTRACT.md) | Shared API contract |

---

## Git branches

| Branch | Commit | Notes |
| --- | --- | --- |
| `integration` | `b6317eb` | **Current work** — backend + Android + contract + docs |
| `server-backend` | `4650157` | Earlier backend (pre-chat-persistence) — unchanged |
| `master` | `26d9095` | Original Android skeleton — unchanged |

The `integration` branch was built on top of `server-backend`, so the earlier
backend history is preserved in its ancestry.

---

## Tech stack

| Layer | Choice | Why |
| --- | --- | --- |
| Backend runtime | Node.js ≥ 20.19 | Async I/O for HTTP + sockets |
| HTTP | Express 4 | Small, well-known middleware |
| Validation | Zod | Schema-first with stable error codes |
| Real-time | Socket.IO | Fallback transports, rooms, acks |
| Database | PostgreSQL (embedded dev) | Zero-install real SQL for students |
| Tests | Vitest + supertest + socket.io-client | Same runner; real HTTP + sockets |
| Android | Jetpack Compose + Retrofit | Modern UI; matches course tooling |
| Outdoor maps | Google Maps SDK (key-gated) | Real outdoor map; key via local.properties, never committed |
