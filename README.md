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

#### Outdoor map (OpenStreetMap)

Indoor navigation needs no extra setup, and neither does Outdoor mode. The
outdoor layer shows real OpenStreetMap tiles through
[osmdroid](https://github.com/osmdroid/osmdroid)
(`org.osmdroid:osmdroid-android` 6.1.20, Apache-2.0). **No Google Maps API
key, Google Cloud project, billing account or credit card is required.**

- **Home view:** the map opens centred on **VR Siddhartha Engineering
  College** (Kanuru, Vijayawada — real campus coordinates
  `16.485277, 80.692780`) at zoom 16.5, with a labelled college marker.
  The user's own location (when permission is granted) is a separate green
  "You are here" marker and is never substituted for the college.
- **Search:** typing a college alias (e.g. "VR", "Siddhartha", "Kanuru")
  offers the college as a destination; selecting it switches to Outdoor and
  recentres on campus. Directions still hand off to an external maps app —
  the app does not invent walking routes or distances for the college.
- **Tile provider:** OpenStreetMap's default "Mapnik" raster tiles
  (`tile.openstreetmap.org`), fetched on demand and cached on disk by
  osmdroid.
- **Attribution:** the map view shows the built-in
  "© OpenStreetMap contributors" notice; keep it visible in screenshots and
  releases.
- **Tile-usage policy:** the public OSM tile servers are fine for development
  and light use, but they are **not** licensed for unlimited production
  traffic. Do not bulk-download tiles. For a public/production deployment,
  point `OutdoorMap.kt` at a commercial tile provider (MapTiler,
  Thunderforest, …) or a self-hosted tile server — osmdroid accepts any XYZ
  tile URL.
- **Play Services:** not required; the map also runs on bare AOSP emulator
  images.

Outdoor turn-by-turn is still handed to the user's external maps app
(Google Maps, or any browser that handles the URL) via a deep link — the app
does not run routing itself, and the backend holds no outdoor coordinates
for indoor venues.

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
| `integration` | `985211c` | **Current work** — backend + Android + contract + docs |
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
| Outdoor maps | OpenStreetMap via osmdroid (no API key) | Free OSM raster tiles; attribution built in; no billing or Play Services |
