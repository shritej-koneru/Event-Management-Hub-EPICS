# Backend Implementation Guide

This document describes **what is actually in the code** in this repository.
Planned-but-missing features are called out explicitly.

## Folder structure

```
server/
├── package.json
├── vitest.config.js
├── scripts/           # setup, smoke, migration verifier, schema dump
├── src/
│   ├── server.js      # entry: migrate + seed + listen
│   ├── app.js         # Express app factory
│   ├── config/        # env parsing (no secrets in source)
│   ├── db/
│   │   ├── pool.js            # pg pool + type parsers
│   │   ├── migrate.js         # migration runner + dropAll
│   │   ├── migrations/        # 001…004 SQL files
│   │   ├── seeds/             # venues, events, demo user, announcements
│   │   ├── bootstrap.js       # create database if missing
│   │   ├── embedded.js        # bundled PostgreSQL for dev/test
│   │   └── schema/schema.sql  # generated dump (npm run db:schema)
│   ├── auth/          # JWT + bearer/optionalAuth middleware
│   ├── middleware/    # validate (Zod), rateLimit, asyncHandler
│   ├── models/        # SQL per table (events, users, chat, …)
│   ├── services/      # search, recommendations, event details, profile
│   ├── navigation/    # campus graph + pathfinding
│   ├── socket/        # Socket.IO namespaces and handlers
│   ├── controllers/   # HTTP handlers
│   ├── routes/        # routers mounted under /api
│   └── utils/         # ApiError, asyncHandler
└── tests/             # Vitest (*.test.js) + globalSetup + helpers
```

## Server startup

`npm start` runs `src/server.js`:

1. `ensureDatabase()` — create the logical DB if missing (embedded or `DATABASE_URL`).
2. `migrate()` — apply any pending `src/db/migrations/*.sql` (one transaction each).
3. `seed()` — idempotent seed (events, venues, demo user, sample announcements).
4. `validateGraph()` — refuse to boot if the navigation graph is inconsistent.
5. `createApp()` + `attachSocketServer(httpServer)` — listen on `PORT` (default 3000).

`npm run dev` is the same with `node --watch`.

## Configuration

Read from environment via `src/config/index.js`. See `docs/DATABASE_GUIDE.md`
for DB variables and `README.md` for the full table. Secrets (especially
`JWT_SECRET`) never appear in source; `.env` is gitignored.

## API surface (implemented)

Mounted under `/api` except `GET /ping`. Full request/response examples:
`docs/API_REFERENCE.md`.

| Method | Path | Auth |
| --- | --- | --- |
| GET | `/ping` | none |
| POST | `/api/auth/register` | rate limited |
| POST | `/api/auth/login` | rate limited |
| GET | `/api/auth/me` | Bearer |
| PUT | `/api/auth/me/interests` | Bearer |
| GET | `/api/auth/me/bookmarks` | Bearer |
| POST | `/api/auth/me/bookmarks/:eventId` | Bearer |
| DELETE | `/api/auth/me/bookmarks/:eventId` | Bearer |
| GET | `/api/events` | optional (+ filters, pagination) |
| POST | `/api/events` | Bearer |
| GET | `/api/events/:id` | optional |
| GET | `/api/events/:id/details` | optional |
| GET | `/api/events/:id/route` | optional |
| POST | `/api/ai/search` | optional |
| GET | `/api/ai/recommendations` | Bearer |
| GET | `/api/navigation/venues` | none |
| POST | `/api/navigation/path` | none |
| GET | `/api/chat/announcements` | optional |
| GET | `/api/chat/messages` | optional |
| POST | `/api/chat/join` | none |
| WS | `/socket.io` (ns `/` and `/announce`) | token optional |

### Error handling

Every failure uses `ApiError` → JSON `{ "error": "<message>", "code": "<CODE>" }`
with a sibling `code` (never nested). Zod failures become `VALIDATION_ERROR`.
Malformed JSON becomes `INVALID_REQUEST`. SQL unique violations map to `409 DUPLICATE`.

### Validation

`src/middleware/validate.js` + `src/middleware/validators.js` (Zod). Query schemas
are `.strict()` so unknown parameters return 400 instead of being ignored.

## Authentication

- Register/login issue a JWT with claims `{ uid, email, name }` only.
- `Authorization: Bearer <token>` on protected routes (`src/middleware/auth.js`).
- Socket handshake `auth.token` is verified in `resolveHandshakeAuth`; invalid
  tokens get `connect_error` with `AUTH_INVALID_TOKEN` (no silent downgrade).
- Missing token on sockets = anonymous (`Guest` chat authorship).

## Database

Four migrations (`001`…`004`). Tables: `venues`, `events`, `users`, `bookmarks`,
`search_history`, `announcements`, `chat_messages`, plus the `migrations` ledger.
See `docs/DATABASE_GUIDE.md`.

## Features (status)

### Fully implemented

- Event CRUD path used by the app: list/filter/paginate, get by id, create (auth).
- Event details aggregate (statistics, charts, announcements, navigation).
- Bookmarks + personalized recommendations.
- Deterministic multi-field AI search (title/description/host/category/tags/synonyms)
  with prefix matching (`MIN_PREFIX_LEN = 3`). **Not an LLM.**
- Campus navigation graph + shortest path.
- JWT auth with demo user seeding.
- Socket.IO: join/leave venue rooms, `events:for`, announcement relay with DB
  uniqueness, interactive `chat:message` **persisted** to `chat_messages`,
  assistant auto-reply (also persisted), `GET /api/chat/messages` history.
- Rate limiting on auth routes; security headers; CORS allowlist.
- Vitest suite (295 tests) + migration rebuild verifier.

### Partial / intentional limits

- Chat is a **public namespace-wide thread** (optional `room` tag). There are no
  private DMs or per-user inboxes — by design for the campus announcement use case.
- Assistant auto-reply is rule-based (may query the events table), not a model.
- Announcements are written only by the event relay (`POST /events` / update),
  not by a user-facing compose box.

### Not implemented (do not assume)

- Push notifications (FCM), payments, QR tickets, waitlists, admin approval flows.
- Server-side registration history beyond `events.registered` count.
- Horizontal scaling of Socket.IO (single process).

## Important files

| File | Purpose |
| --- | --- |
| `src/socket/index.js` | Chat + announcement real-time behaviour |
| `src/models/chatMessageModel.js` | Chat insert + history SQL |
| `src/models/announcementModel.js` | Announcement insert (ON CONFLICT) + feeds |
| `src/services/searchService.js` + `src/ai/scoring.js` | Deterministic search |
| `src/navigation/graph.js` | Venue graph + pathfinding |
| `src/db/migrate.js` | Migration runner |
| `tests/socket.test.js` | Live socket suite |
| `tests/chat.test.js` | Chat persistence + history API |
