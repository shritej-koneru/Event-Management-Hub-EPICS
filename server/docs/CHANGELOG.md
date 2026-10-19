# Changelog

Derived from **actual git history** in this repository plus the working tree at
the time of handover. Nothing here is invented.

## Unreleased (feat/indoor-maps branch, 2026-10-20)

### Added

- **Indoor map API (contract 1.2.0, additive):**
  - Migration `005_venue_map.sql` — `venues.map_x/map_y` (fractional canvas
    coordinates, CHECKed to [0,1]) + `venue_edges` table (one row per
    undirected edge, FK to venues, `CHECK (from_id < to_id)`)
  - `MAP_POSITIONS` in `src/navigation/venues.js` — canonical layout, the
    values the Android map used to hard-code as `MapPositions`
  - Seed mirrors positions + `GRAPH` into the DB (`upsertVenues` now carries
    coordinates, new `upsertVenueEdges`), with `assertMapIntegrity()` gating boot
  - `GET /api/navigation/map` — whole-campus layout (venues with x/y +
    undirected edges) served from the database
  - `GET /api/navigation/venues/:venueId/map` — one venue with positioned
    neighbours; accepts id or URL-encoded display name; `400 UNKNOWN_VENUE`
    for anything unknown (never a guess)
  - `tests/navigation.test.js`: 10 new tests (map shapes, canonical layout,
    DB-vs-JS graph consistency, name/near-miss/unknown handling)
  - `verify-migrations` extended for the fifth migration + map/edge checks;
    `schema.sql` regenerated via `npm run db:schema`

### Fixed

- **Occupancy parse crash:** the server sends `occupancyPercent` with one
  decimal (e.g. 91.7) but `EventStatistics.occupancyPercent` was an `Int`, so
  `GET /events/:id/details` failed to decode and the app showed a false
  "offline" banner. Now a `Double` everywhere, including the offline engine,
  which rounds the same way the server does; the detail screen formats 40.0 as
  "40%".
- **Android build break from b6317eb:** `values-night/themes.xml` still
  referenced the removed MaterialComponents theme and the deleted
  purple/teal/black colors, so `processDebugResources` always failed on the
  integration branch. Removed (night falls back to the day theme, matching the
  working local Android repo).
- **Events screen copy:** the empty "Registered" filter said "tap Register"
  but the button is "Save" — now says "tap Save".
- **Chat room joins** were built from the hard-coded offline venue list; they
  now come from `GET /api/navigation/venues` (offline list remains the
  unreachable-server fallback).

### Changed (Android, map wiring)

- `MapScreen` draws from `GET /api/navigation/map` (`campusMap()` in the
  repository, `offlineCampusMap()` fallback) — the hard-coded `MapPositions`
  map is gone
- New models `MapVenue` / `MapEdge` / `CampusMapResponse` /
  `VenueMapNeighbor` / `VenueMapResponse`; `HubApi.campusMap()` /
  `venueMap()`; `OfflineData.offlineMapPositions` mirrors the backend layout
  for the offline experience
- Tests: `OfflineMapTest` (4) pins offline-vs-backend layout parity;
  `:app:testDebugUnitTest` → 38 passed

### Verified (this branch)

- Backend `npm test` → **295 passed** (12 files, embedded PostgreSQL)
- `npm run verify:migrations` → **ALL CHECKS PASSED** (5 migrations)
- Android `:app:testDebugUnitTest` → **38 passed**
- Live smoke on a running server: `/ping` → contractVersion `1.2.0`;
  `/api/navigation/map` → 14 venues / 19 edges; venue map by id and by
  display name OK; unknown and near-miss → `400 UNKNOWN_VENUE`

## Previous unreleased notes (landed in integration as b6317eb / e8fe922)

### Added

- Interactive chat persistence:
  - Migration `004_chat_messages.sql` (`chat_messages` table + indexes)
  - `src/models/chatMessageModel.js` (`insertChatMessage`, `listRecentChatMessages`)
  - Socket `chat:message` now **inserts before broadcast**; nack `CHAT_PERSIST_FAILED` on write failure
  - Assistant auto-reply rows are persisted (`bot: true`)
  - REST `GET /api/chat/messages` with `room` / `before` / `limit`
  - `tests/chat.test.js` (16 tests)
  - `dropAll` + `verify-migrations` updated for the fourth migration
- Assistant auto-reply (`buildAutoReply`, `ASSISTANT_NAME`) on `chat:message`
- Interactive `chat:message` handler itself (was announcements-only before this working tree)

### Fixed

- Deterministic search: prefix matching with `MIN_PREFIX_LEN = 3` so queries
  like `"android dev work"` match `"workshop"` (`src/ai/scoring.js`, `tests/ai.test.js`)

### Changed

- `.gitignore` tweaks; README wording

## Committed history

| Commit | Summary |
| --- | --- |
| `9ea8a8a` | test: cover the single-string join form the Android client uses |
| `6803581` | test: add probe-event cleanup script and correct test count |
| `981c665` | fix: require an explicit scratch target for live verifiers |
| `dc091bd` | test: add scratch-database migration rebuild verifier |
| `e119c58` | test: add malformed-JSON error mapping regression suite |
| `36bf74b` | Initial commit: Person 2 Event Management Hub backend |

## Verification results (this handover)

| Check | Result |
| --- | --- |
| `npm test` | **285 passed** (12 files), 0 failed |
| `npm run verify:migrations` | **ALL CHECKS PASSED** (4 migrations, `chat_messages` present) |
| `npm run db:schema` | Regenerated `src/db/schema/schema.sql` |

Android companion repo (separate): `ChatHistoryMergeTest` 8 tests + `OfflineEngineTest` 25 tests, all passing after the ViewModel history fix.
