# Event Management Hub — Server

Central Node.js/Express backend for the Event Management Hub Android app. It is the
single source of truth for events, venues, navigation, JWT authentication, AI search,
personalized recommendations and BitChat **announcements + persisted chat**.

Implements [`contract/API-CONTRACT.md`](../contract/API-CONTRACT.md)
(additive history endpoint: `GET /api/chat/messages`).

## Documentation (start here)

Root-level handover docs (monorepo view):

| Doc | Audience |
| --- | --- |
| [../docs/PROJECT_HANDOVER.md](../docs/PROJECT_HANDOVER.md) | Next developer — start here |
| [../docs/MY_WORK_SUMMARY.md](../docs/MY_WORK_SUMMARY.md) | Complete summary of implemented work |
| [../docs/IMPLEMENTATION_EXPLANATION.md](../docs/IMPLEMENTATION_EXPLANATION.md) | Beginner-friendly "how it works" |

Backend-focused docs:

| Doc | Audience |
| --- | --- |
| [docs/WORK_HANDOVER.md](docs/WORK_HANDOVER.md) | **Main handover — read first** |
| [docs/PROJECT_OVERVIEW.md](docs/PROJECT_OVERVIEW.md) | Architecture & stack |
| [docs/BACKEND_IMPLEMENTATION.md](docs/BACKEND_IMPLEMENTATION.md) | Feature-by-feature backend guide |
| [docs/DATABASE_GUIDE.md](docs/DATABASE_GUIDE.md) | Schema, migrations, seeds |
| [docs/API_REFERENCE.md](docs/API_REFERENCE.md) | Every REST + Socket.IO endpoint |
| [docs/REALTIME_WORKFLOWS.md](docs/REALTIME_WORKFLOWS.md) | Socket.IO + chat persistence |
| [docs/SETUP_AND_TESTING.md](docs/SETUP_AND_TESTING.md) | Install, run, test, emulator |
| [docs/PROJECT_FLOW.md](docs/PROJECT_FLOW.md) | Mermaid diagrams |
| [docs/CHANGELOG.md](docs/CHANGELOG.md) | Real git history + this handover |

---

## Quick start

```bash
npm install     # installs deps and the bundled PostgreSQL binaries
npm run setup   # generates .env with a random JWT_SECRET (safe, idempotent)
npm start       # migrates, seeds and listens on http://localhost:3000
```

Point the Android emulator at `http://10.0.2.2:3000` or run
`adb reverse tcp:3000 tcp:3000` when the app uses `http://127.0.0.1:3000/`.

Verify:

```bash
curl http://localhost:3000/ping
# {"status":"ok","ok":true,"service":"emh-server","contractVersion":"1.1.0",
#  "contract":"contract/API-CONTRACT.md","database":"connected",
#  "venues":[{"id":"entrance","name":"Entrance"}, ...]}
```

Demo account: `alice@uni.edu` / `secret123`

Run tests (stop `npm start` first — both use embedded PG port 5433):

```bash
npm test                 # 295 tests
npm run verify:migrations
```


---

## Requirements

- Node.js **>= 20.19**
- No PostgreSQL install, no admin rights, no Docker

For local development the server starts a **real PostgreSQL cluster** itself using the
`embedded-postgres` devDependency. Application code, migrations and queries are
identical to production — only the connection string differs.

In production set `DATABASE_URL` (mandatory, `embedded-postgres` is never imported).

---

## Configuration (`.env`)

| Variable | Default | Notes |
| --- | --- | --- |
| `PORT` | `3000` | HTTP + Socket.IO port |
| `NODE_ENV` | `development` | `production` requires `DATABASE_URL` |
| `API_BASE_URL` | `http://10.0.2.2:3000` | Informational; what the client should use |
| `DATABASE_URL` | *(empty)* | Empty ⇒ use bundled PostgreSQL |
| `USE_EMBEDDED_PG` | `true` | Dev/test only |
| `EMBEDDED_PG_PORT` | `5433` | Avoids clashing with a system PostgreSQL on 5432 |
| `EMBEDDED_PG_DIR` | `<server>/data/pg` | Cluster data directory |
| `JWT_SECRET` | *(required)* | No default in source. `npm run setup` generates one |
| `JWT_EXPIRES_IN` | `7d` | |
| `BCRYPT_ROUNDS` | `10` | |

`.env` is Git-ignored. Never commit it; `.env.example` holds placeholders only.

> **UTF-8:** the bundled cluster is initialised with `--encoding=UTF8`. On Windows
> `initdb` would otherwise default to the system ANSI code page and reject the
> 4-byte emoji in `events.icon`. If you ever see an encoding error, delete `data/pg`
> and re-run.

---

## npm scripts

| Script | Purpose |
| --- | --- |
| `npm run setup` | Create `.env` with a random `JWT_SECRET` |
| `npm start` | Migrate + seed + start (production-style) |
| `npm run dev` | Same with `--watch` |
| `npm run db:migrate` | Apply pending migrations |
| `npm run db:seed` | Idempotent seed (safe to re-run) |
| `npm run db:reset` | Drop all tables, migrate, seed |
| `npm run db:schema` | Regenerate `src/db/schema/schema.sql` |
| `npm test` | Full suite against a real `emh_test` database |
| `npm run verify:migrations` | Rebuild a scratch database from the migration files alone |
| `npm run verify:rest` | Live REST smoke check — **requires `VERIFY_BASE_URL`** |
| `npm run verify:socket` | Live Socket.IO relay check — **requires `VERIFY_BASE_URL`** |
| `npm run verify:events` | Live Event Details check — **requires `VERIFY_BASE_URL`** |
| `npm run verify` | `npm test` + `verify:migrations` (the unattended, safe subset) |

### Live verification needs an explicit scratch target

`verify:rest`, `verify:socket` and `verify:events` all **write** to the target:
they register a verifier account and create events. They therefore have no
default URL and accept no positional argument, and they refuse to start unless the
server reports a scratch database name on `GET /ping`:

```powershell
# terminal 1 — throwaway server on a scratch database, any free port
$env:EMBEDDED_PG_DATABASE = 'emh_scratch'
$env:PORT = 3100
npm start

# terminal 2
$env:VERIFY_BASE_URL = 'http://127.0.0.1:3100'
npm run verify:rest
npm run verify:socket
npm run verify:events
```

A name that is not self-evidently disposable (`emh_scratch`, `*_test`, `*_check`,
`*_verify`, `*_tmp`) is rejected, as is `emh` itself. If a target cannot report
its database name — for example a server started before this check existed — the
verifiers refuse rather than guess. `VERIFY_ALLOW_ANY_DB=1` overrides the name
check, and should only be used where the name genuinely cannot be read.

---

## Architecture

```
src/
  server.js                 process entry: db -> http -> socket.io -> shutdown
  app.js                    express app, /ping, error handler
  config/                   env parsing and validation
  db/
    embedded.js             bundled PostgreSQL lifecycle + database bootstrap
    pool.js                 pg Pool singleton
    migrate.js              migration runner (001_init.sql)
    reset.js                drop -> migrate -> seed
    migrations/             authoritative SQL
    schema/schema.sql       generated reference snapshot
    seeds/                  14 venues, 12 events, demo user
  models/                   parameterised SQL, one per table
  routes/                   thin HTTP routing
  controllers/              request validation + response shaping
  middleware/               auth, validation, ApiError
  services/                 AI search, recommendations, navigation, events
  ai/                       deterministic tokenizer + scoring
  navigation/               venue list + graph + Dijkstra
  socket/                   Socket.IO namespaces and rooms
tests/                      vitest suites (real PostgreSQL)
scripts/                    setup, schema dump, live REST + socket verification
```

Data flow: `route → validate (zod) → controller → service → model → PostgreSQL`.
Errors are thrown as `ApiError` and serialised centrally to `{ error, code }`, where
`error` is the human-readable message **string** and `code` its sibling — never nested.

---

## API

All business endpoints are under `/api`; `/ping` is at the root.

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| GET | `/ping` | – | Health, contract version + venue catalogue (no counts) |
| GET | `/api/events` | – | List. Filters: `category`, `date`, `venue`, `q`. Optional `page`/`pageSize` |
| GET | `/api/events/:id` | – | Single event, exactly `{ event }` |
| GET | `/api/events/:id/details` | – | Event Details: `{ event, venue, statistics, charts, announcements, navigation }` |
| GET | `/api/events/:id/route` | – | Route from `?from=` (default `entrance`) to the event's venue |
| POST | `/api/events` | **Bearer** | Create event |
| POST | `/api/auth/register` | – | Create account, returns JWT (rate limited) |
| POST | `/api/auth/login` | – | Returns JWT (rate limited) |
| GET | `/api/auth/me` | Bearer | Current profile + bookmarks |
| PUT | `/api/auth/me/interests` | Bearer | Replace interests |
| GET | `/api/auth/me/bookmarks` | Bearer | List bookmarked event ids |
| POST | `/api/auth/me/bookmarks/:eventId` | Bearer | Add a bookmark (idempotent, 200) |
| DELETE | `/api/auth/me/bookmarks/:eventId` | Bearer | Remove a bookmark (idempotent, 200) |
| POST | `/api/ai/search` | optional | Ranked AI search + reasons |
| GET | `/api/ai/recommendations` | **Bearer** | Personalized recommendations |
| GET | `/api/navigation/venues` | – | Venue list + neighbours |
| POST | `/api/navigation/path` | – | Route between two venues |
| GET | `/api/chat/announcements` | – | Recent announcements. Optional `?eventId=` scopes to one **event** (404 if unknown) |
| POST | `/api/chat/join` | – | Join a venue room, body `{ "venueId": "room-204" }` |

Notes:

- `GET /api/events` is **unpaginated by default**. Send `page` (and optionally
  `pageSize`, default 20, max 100) to get a page plus a `pagination` block:
  `{ page, pageSize, total, totalPages, hasMore }`. The `events` key is
  unchanged, so the Android `EventListResponse(events)` parses either shape.
- `category`, `venue` and `q` are matched case-insensitively. `venue` accepts a
  canonical id (`room-204`) or display name (`Room 204`).
- The list query is strict: unknown parameters return `VALIDATION_ERROR` (400)
  rather than being silently ignored.
- `POST /api/events` **requires** a JWT: it mutates shared data. Reads
  (`GET /events`, `GET /events/:id`, `GET /events/:id/details`,
  `GET /events/:id/route`, `GET /navigation/*`, `GET /chat/announcements`) are
  public, and a token is still honoured when supplied. The Android client does
  not create events.
- Creating an event requires the canonical `venue` display name; an unmappable
  value is rejected with `UNKNOWN_VENUE` (400) rather than stored unroutable.
- There is no `POST /api/chat/announcements` — announcements are written only by
  the event relay when an event is created, each tagged with its `event_id`.

### Event Details

`GET /api/events/:id/details` returns everything a details screen needs in one
call, so the client does not have to fan out:

```json
{
  "event": { "id": "evt_001", "...": "unchanged canonical Event" },
  "venue": { "id": "room-204", "name": "Room 204",
             "navigationNode": "room-204", "neighbors": ["room-102", "room-205"] },
  "statistics": { "capacity": 40, "registered": 12, "seatsRemaining": 28,
                  "occupancyPercent": 30, "durationMin": 120,
                  "announcementCount": 2, "bookmarkCount": 1, "isBookmarked": true,
                  "categoryEventCount": 4, "totalEvents": 12,
                  "isPast": false, "daysUntilEvent": 10 },
  "charts": [ { "id": "occupancy", "type": "donut", "unit": "people",
                "total": 40, "data": [ { "label": "Registered", "value": 12 } ] } ],
  "announcements": [ { "id": 3, "room": "room-204", "eventId": "evt_001",
                       "message": "...", "at": "..." } ],
  "navigation": { "from": "entrance", "nodes": [], "steps": [], "totalDistanceM": 42 }
}
```

- `GET /api/events/:id` is **unchanged** and still returns exactly `{ event }`, so
  the existing Android `EventResponse` is unaffected.
- **Nothing is synthesised.** Every figure comes from a stored column or a
  `COUNT` over real rows. `registered` is the event's own aggregate counter, not
  a claim that any individual registered — there is no registrations table, and
  registration state lives on the Android device. Likewise `bookmarkCount` is an
  aggregate; `isBookmarked` is the caller's own state.
- **Charts are optional.** `charts` may be empty or omit individual entries:
  `occupancy` is skipped when `capacity` is 0, and `announcementTrend` needs at
  least two announcements on distinct days. A single point is not a trend.
- `venue.navigationNode` is the id to pass as `to` to the navigation API.
- `?from=<node>` (default `entrance`) mirrors `GET /api/events/:id/route`.
- Errors: `404 EVENT_NOT_FOUND` (unknown event), `400 UNKNOWN_VENUE` (bad `from`).

### Event-scoped announcements

`GET /api/chat/announcements?eventId=evt_001` returns only messages recorded for
that event. This is required for correctness, not convenience: `evt_002` (Music
Night) and `evt_006` (Drama) are both at `auditorium`, so a venue-only feed would
mix them. An event with no announcements returns `200` with an empty array; an
**unknown** event id returns `404 EVENT_NOT_FOUND` so a typo is not mistaken for
"nothing to show". Announcements stored without an event association omit the
`eventId` field entirely.

Error codes (`{ "error": string, "code": string }`):

| Code | Status |
| --- | --- |
| `VALIDATION_ERROR` | 400 |
| `UNKNOWN_VENUE` | 400 |
| `AUTH_REQUIRED` | 401 |
| `INVALID_CREDENTIALS` | 401 |
| `AUTH_INVALID_TOKEN` | 401 |
| `NO_PATH`, `EVENT_NOT_FOUND`, `NOT_FOUND` | 404 |
| `EMAIL_TAKEN`, `DUPLICATE` | 409 |
| `RATE_LIMITED` | 429 |
| `INTERNAL` | 500 |

There is no `BAD_CREDENTIALS` (it is `INVALID_CREDENTIALS`), no `FORBIDDEN`
(403) and no `INTERNAL_ERROR` (it is `INTERNAL`).

---

## Venues

Routing is keyed **exactly** on these ids and display names.

| id | name | id | name |
| --- | --- | --- | --- |
| `entrance` | Entrance | `room-101` | Room 101 |
| `lobby` | Lobby | `room-102` | Room 102 |
| `registration` | Registration Desk | `room-204` | Room 204 |
| `auditorium` | Auditorium | `room-205` | Room 205 |
| `main-hall` | Main Hall | `lab-a` | Lab A |
| | | `lab-b` | Lab B |
| | | `cafeteria` | Cafeteria |
| | | `library` | Library |
| | | `tech-lounge` | Tech Lounge |

All 14 nodes are reachable from `Entrance` (asserted at startup and in tests).
Unknown venue names return `UNKNOWN_VENUE` rather than a guessed route.

---

## Socket.IO

Both namespaces behave identically:

- `/` (default — what the Android client uses)
- `/announce`

| Direction | Event | Payload |
| --- | --- | --- |
| → server | `join` | `["room-204","auditorium"]` (bare venue ids or `announce:<id>`), ack `{ ok, joined }` |
| ← server | `joined` | canonical `announce:<venueId>` room names |
| → server | `leave` | `["room-204"]`, ack `{ ok, left }` |
| → server | `events:for` | `"room-204"`, ack `{ ok: true, events: [...] }` or `{ ok: false, error: { code, message } }` |
| ← server | `event` | `{ event, announcement, title, venue, venueId, eventId }` |

`title`, `venue`, `venueId` and `eventId` are flattened to the **top level** because
that is what the Android `ChatScreen` reads; `event` and `announcement` are kept for the
contract shape.

Rooms are stored in PostgreSQL as **bare venue ids** (`room-204`) while sockets join
`announce:room-204`. Duplicate announcements are impossible:
`UNIQUE (room, message)` plus `ON CONFLICT DO NOTHING`. The stored message embeds the
event id (`New event at Room 204 [evt_…]`), so two different events at one venue produce
two announcements instead of collapsing onto one.

**Authentication.** The Android client connects anonymously
(`IO.socket(uri, IO.Options().apply { path = "/socket.io" })`), so anonymous listeners
remain supported. A token may be supplied via handshake `auth.token`:

- valid token → connected, `socket.data.userId` set;
- invalid/expired token → rejected with `connect_error` (`AUTH_INVALID_TOKEN`) instead of
  silently downgrading to anonymous;
- no token → anonymous.

---

## AI search & recommendations

Fully deterministic and offline — no external API key, no LLM or embedding service.

- Tokenized scoring over title, description, tags, category, venue and host, with
  synonym expansion (`concert` → *music*, `talk` → *seminar*, …).
- **Relevance gate.** An event is only eligible when it has real textual evidence for
  the query, or — for a signed-in user — a concrete personalization signal. Popularity is
  a tie-breaker only and can never admit an event on its own, so a query matching nothing
  returns `results: []` rather than the whole catalogue.
- Scores use a saturating `x / (x + 6)` curve, so strong matches stay distinguishable
  instead of all pinning at `1`.
- Personalized users get an interest/bookmark/search-history boost and a matching
  `reason`.
- Queries are recorded in `search_history` and influence later results. History is pruned
  to the newest 50 rows per user on every write, so the table cannot grow without bound.
- Every result carries a `score` in `[0,1]` and a human-readable `reason`. Reasons only
  describe data the server actually has — no result ever claims the user registered for an
  event, because registrations live on the device.
- Recommendations rank personalization signals above raw popularity, so new and
  low-registration events that match an interest are still surfaced.

---

## Testing

```bash
npm test          # 269 tests, 11 suites
```

Vitest runs sequentially in one worker against a real `emh_test` database created,
migrated and seeded by `tests/globalSetup.js`. If a cluster is already listening on
`5433` it is reused, never stopped and never re-initialised.

Coverage: `/ping` · event list/detail/404/filtering/pagination/combined filters/strict
query validation/creation auth · event route endpoint · registration, login, bad
credentials, no user enumeration, `/me`, interests, bookmark add/remove idempotency ·
security headers, rate limiting, no DB detail leakage · AI search (ranking, synonyms,
tags, no-match returns empty, non-saturation, personalisation, limits, validation) ·
recommendations (auth, ordering, reasons, zero/low-registration eligibility, no false
registration claims) · navigation (ids, names, same-node, unknown venue, **all 196 venue
pairs**, shortest path, graph invariants) · seed integrity (every event maps to a routable
graph node, demo user interests/bookmarks/history, demo seeding policy, UTF-8 emoji) ·
Socket.IO (namespaces, rooms, token rejection, acknowledgements, broadcast payloads,
duplicate-announcement protection) · schema integrity (`announcements.room` foreign key,
query-matching `search_history` index, retention pruning on write).

Live checks against a scratch server (`npm start` first, then see
[Live verification needs an explicit scratch target](#live-verification-needs-an-explicit-scratch-target)
for the required environment):

```bash
VERIFY_BASE_URL=http://127.0.0.1:3100 npm run verify:rest      # REST checks
VERIFY_BASE_URL=http://127.0.0.1:3100 npm run verify:socket    # 21 Socket.IO checks
VERIFY_BASE_URL=http://127.0.0.1:3100 npm run verify:events    # per-event details checks

npm run verify                                              # tests + migration rebuild
```

---

## Data model

`venues` · `events` · `users` (`interests` JSONB) · `bookmarks` ·
`search_history` · `announcements` · `migrations`

`events.venue_id` has a **foreign key** to `venues.id`, so an event can never
reference a venue outside the navigation graph. `announcements.room` has the same
foreign key, so the BitChat relay can never persist an announcement for a venue that
does not exist.

`announcements.event_id` is a **nullable** foreign key to `events.id` with
`ON DELETE CASCADE`. It scopes a message to the event that produced it rather
than only to the venue, which is what lets two events at one venue
(`auditorium`: Music Night and Drama) keep separate announcement feeds. `NULL`
means a venue-level notice that belongs to no single event. A partial index on
`(event_id, id DESC) WHERE event_id IS NOT NULL` serves the event-scoped read.
The relay keeps embedding the event id in the message text, so the original
`UNIQUE (room, message)` guarantee — the reason the same event cannot be relayed
twice — is untouched.

**Upgrading an existing database.** Migration 003 backfills `event_id` for rows
the relay wrote, by reading the bracketed `[evt_…]` suffix out of the message
text. Seeded notices carry no such suffix, so their association cannot be
inferred and they stay `NULL` until the next boot — at which point `seed()`
re-saves them and fills the column in (without inserting a duplicate, and without
overwriting an association that is already known). Run the server once after
upgrading; no manual SQL is needed.

`search_history` is indexed as `(user_id, lower(query), at DESC)` to match the
`DISTINCT ON (lower(query))` read used for personalization, and is pruned to the newest
50 rows per user on every write — only the 5 most recent distinct queries are ever read.

There is deliberately **no registrations table** — the Android app registers events
locally in `OfflineStore`, so server-side registration state would be unused.

---

## Production

```bash
NODE_ENV=production
DATABASE_URL=postgres://user:pass@host:5432/emh
USE_EMBEDDED_PG=false
JWT_SECRET=<strong random value>
CORS_ORIGINS=https://app.example.edu
npm start
```

Run `npm run db:migrate` once before the first start. Put the app behind TLS so the
JWT travels over HTTPS, and note that Socket.IO clients must then use `wss://`.

### Seeding in production

`seed()` runs on every boot and is idempotent (venues/events/announcements are upserted
or skipped if present). The **demo account is not created in production**: it uses a
password published in this repository. To create it deliberately, opt in explicitly:

```bash
SEED_DEMO_USER=true DEMO_USER_PASSWORD=<secret> npm run db:seed
```

`SEED_DEMO_USER` defaults to enabled in development/test and disabled when
`NODE_ENV=production`. The demo password is never written to the seed log.

### CORS

`CORS_ORIGINS` is a comma-separated allowlist. Left blank, the request origin is
reflected (development default); with `NODE_ENV=production` and no allowlist, browser
access is refused entirely. Native Android clients send no `Origin` header and are
unaffected either way.