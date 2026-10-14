# Project Overview — Event Management Hub

## Problem statement

Campus events are scattered across posters, chats, and word of mouth. Students miss
workshops, cannot find rooms, and organizers have no single place to publish or
track events. **Event Management Hub** is a mobile app plus backend that:

1. Stores campus events in one database.
2. Lets students discover events with filters and AI-style search.
3. Shows personalized recommendations from bookmarks and interests.
4. Navigates users between venues on a fixed campus graph.
5. Relays live announcements and interactive chat over Socket.IO.

## Architecture

```
┌─────────────────────┐   HTTP + Socket.IO   ┌──────────────────────┐   SQL   ┌────────────┐
│  Android app        │ ───────────────────► │  Node.js backend     │ ──────► │ PostgreSQL │
│  (Jetpack Compose)  │ ◄─────────────────── │  (Express + socket.io)│        │ (embedded  │
└─────────────────────┘                      └──────────────────────┘        │  or real)  │
                                                                             └────────────┘
```

- **Frontend:** Kotlin + Jetpack Compose (`../Epics/android`). Tabs: Home, Events,
  Map, Chat, Profile. Offline cache for events/announcements when the network fails.
- **Backend (this repo):** Express REST API + Socket.IO. JWT auth, deterministic
  AI search (not an LLM), navigation graph, BitChat announcements + persisted chat.
- **Database:** PostgreSQL. Local dev uses the bundled `embedded-postgres` cluster
  on port 5433; production uses `DATABASE_URL`.
- **Contract:** `../Epics/contract/API-CONTRACT.md` is the shared API contract
  (version `1.1.0`). The backend implements it; new endpoints are additive.

## Technology choices

| Layer | Choice | Why |
| --- | --- | --- |
| Runtime | Node.js ≥ 20.19 | Async I/O for HTTP + sockets; matches team stack |
| HTTP | Express 4 | Small, well-known middleware chain |
| Validation | Zod | Schema-first query/body checks with stable error codes |
| Real-time | Socket.IO | Fallback transports, rooms, acks — needed for BitChat |
| DB access | `pg` pool | Plain SQL; no ORM to hide migrations |
| DB (dev) | `embedded-postgres` | Zero-install real Postgres for students |
| Tests | Vitest + supertest + socket.io-client | Same test runner; real HTTP and real sockets |
| Mobile | Jetpack Compose + Retrofit + socket.io-client | Modern Android UI; matches course tooling |

## Roles in the project

- **Person 1 (Android):** Compose UI, offline engine, navigation screens.
- **Person 2 (backend — this repository):** server, database, auth, search,
  recommendations, Socket.IO relay, chat persistence, tests, handover docs.

## Module map (backend)

| Module | Responsibility |
| --- | --- |
| `src/server.js` | Boot: ensure DB → migrate → seed → HTTP + Socket.IO |
| `src/app.js` | Express app, CORS, rate limit, `/ping`, error handlers |
| `src/routes/*` | URL → controller wiring |
| `src/controllers/*` | Request validation outcome → response |
| `src/services/*` | Multi-step use cases (search, details, profile) |
| `src/models/*` | SQL for one table/aggregate |
| `src/socket/index.js` | Namespaces, join/leave, chat, announcement relay |
| `src/auth/*` | JWT sign/verify, bearer middleware |
| `src/db/*` | Pool, migrations, seeds, embedded PG |
| `tests/*` | Vitest suites against a real `emh_test` database |

## How the pieces interact (happy path)

1. App boots → `GET /ping` to check the backend.
2. User logs in → `POST /api/auth/login` → JWT stored in Android DataStore.
3. Home loads events → `GET /api/events` (optional JWT for bookmarks).
4. AI search box → `POST /api/ai/search` with a free-text query.
5. Map routes → `POST /api/navigation/path` with venue ids or display names.
6. Chat opens → Socket.IO connect (optional token) → join venue rooms →
   load history via `GET /api/chat/messages` → send `chat:message`.

See `docs/PROJECT_FLOW.md` for diagrams and `docs/WORK_HANDOVER.md` for a
beginner-oriented walkthrough.
