# Changelog

Derived from **actual git history** in this repository plus the working tree at
the time of handover. Nothing here is invented.

## Unreleased (integration, 2026-10-20)

### Changed (Android — outdoor map: Google Maps → OpenStreetMap)

- Outdoor map layer now uses **osmdroid 6.1.20** (OpenStreetMap raster tiles)
  instead of the Google Maps SDK:
  - No Google Maps API key, Google Cloud project, billing account or Play
    Services is required. Removed the `MAPS_API_KEY` BuildConfig field and
    manifestPlaceholders wiring, and the
    `com.google.android.geo.API_KEY` manifest meta-data entry.
  - Removed `play-services-maps` 19.0.0 and `maps-compose` 6.1.2;
    added `org.osmdroid:osmdroid-android` 6.1.20.
  - `ui/screens/OutdoorMap.kt` rewritten: osmdroid `MapView` inside an
    `AndroidView`, default OSM Mapnik tiles, app User-Agent for OSM's
    tile-usage policy, built-in "© OpenStreetMap contributors" attribution,
    screen-lifecycle pause/resume/detach, and a real single-fix
    "You are here" marker (coordinates only from `DeviceLocation` /
    LocationManager — never invented).
  - Shared outdoor zoom/recenter buttons now drive the osmdroid
    `OutdoorMapController` (outdoor zoom was previously inert).
  - Indoor navigation, destination search, Indoor/Outdoor toggle, bottom
    sheet, and the external Google Maps directions deep link (honest outdoor
    hand-off — the app does not route itself) are unchanged.
  - osmdroid upstream last released 6.1.20 (Aug 2024) and is now archived;
    it remains zero-key and stable for this use. MapLibre Native is the
    actively maintained alternative if a future migration is needed.

## Unreleased (fix/qa-verification branch, 2026-10-20)

### Fixed

- **`scripts/verify-events.mjs` stale contract check:** the live verifier
  hard-coded contract `1.1.0` and failed against the 1.2.0 server ("ping
  advertises contract 1.1.0 -> 1.2.0"). The check now compares against
  `config.contractVersion` (single source of truth) so it cannot go stale
  again. Live re-run: **496 passed, 0 failed**.

### Verified (full QA pass, 2026-10-20)

- Backend `npm test` → 295 passed; `verify:migrations` → ALL CHECKS PASSED;
  live scratch server: `verify:rest` ALL LIVE CHECKS PASSED,
  `verify:socket` 21/21, `verify:events` 496/496
- Android 52/52 unit tests, `assembleDebug` BUILD SUCCESSFUL; installed and
  driven on emulator-5554 (login, events, details, navigation, chat history
  survives process death)

## Unreleased (feat/maps-nav-ui branch, 2026-10-20)

### Added (Android — Google Maps-style navigation UI)

- Navigation screen redesign (`ui/screens/MapScreen.kt`):
  - Search bar over the map with backend venue results
    (`GET /api/navigation/map` venue list; filtering in
    `NavigationUiLogic.searchVenues`)
  - Indoor mode: backend-driven canvas with pinch-zoom/pan, zoom buttons,
    labelled start/destination/active-step nodes, route bottom sheet showing
    the real backend distance plus a clearly-labelled walking-time estimate,
    Walking travel-mode chip, and a manual-advance navigation mode (Next/End,
    arrival state) — no fake live turn-by-turn
  - Outdoor mode: Google Maps SDK layer (`ui/screens/OutdoorMap.kt`,
    play-services-maps 19.0.0 + maps-compose 6.1.2) created only when a
    `MAPS_API_KEY` is configured (same three-tier mechanism as
    `API_BASE_URL`: -P, local.properties — never committed); without a key a
    setup card is shown. Venue outdoor directions hand off to the real
    Google Maps app via a deep link (the backend holds no lat/lng for venues;
    none invented)
  - `ui/navigation/NavigationUiLogic.kt` — pure JVM rules (search ranking,
    walking estimate from backend metres, hand-off URL, step progression)
  - `ui/navigation/DeviceLocation.kt` — single-fix LocationManager helper;
    location permission is requested only when Outdoor mode/locate is used,
    updates stop on dispose; nothing persisted
  - Manifest: `ACCESS_COARSE/FINE_LOCATION` (runtime-requested) + Maps
    API-key meta-data placeholder
- `NavigationUiLogicTest` (14 tests) pins the search/ETA/URL/step rules

### Verified (this branch)

- Android `:app:testDebugUnitTest` → **52 passed** (was 38; +14 new)
- Android `:app:assembleDebug` → BUILD SUCCESSFUL
- Backend unchanged in this pass (295 tests / 5 migrations green as of
  `2db435a`)

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
