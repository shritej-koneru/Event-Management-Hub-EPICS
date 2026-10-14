# Project Handover — Event Management Hub

**For:** the next developer picking up this repository
**Repository:** https://github.com/shritej-koneru/Event-Management-Hub-EPICS
**Branch to start from:** `integration` (commit `b6317eb`)
**Date of handover:** 2026-10-14

---

## 1. What this project is

**Event Management Hub** is a campus event platform made of two parts:

| Part | Location in this repo | Stack |
| --- | --- | --- |
| Backend | `server/` | Node.js ≥ 20.19, Express 4, Socket.IO, PostgreSQL (embedded for dev) |
| Android app | `app/` (repo root) | Kotlin, Jetpack Compose, Retrofit, socket.io-client |
| Shared contract | `contract/API-CONTRACT.md` | Version `1.1.0` — the single source of truth for HTTP + socket shapes |

The backend is the **single source of truth** for events, venues, JWT auth,
search, recommendations, navigation, and chat. The Android app caches aggressively
and flags stale data honestly.

---

## 2. Repository structure

```
Event-Management-Hub-EPICS/          <- integration branch
├── app/                              <- Android (Jetpack Compose)
│   └── src/main/java/.../
│       ├── data/                     <- HubRepository, OfflineStore, ApiClient
│       ├── data/api/                 <- Retrofit HubApi
│       ├── data/model/               <- Serializable wire models
│       └── ui/screens/               <- Compose screens + ChatViewModel
├── server/                           <- Backend (Node/Express)
│   ├── src/
│   │   ├── socket/index.js           <- Socket.IO: rooms, announcements, chat
│   │   ├── models/chatMessageModel.js<- Chat insert + history SQL
│   │   ├── db/migrations/004_chat_messages.sql
│   │   ├── ai/scoring.js             <- Deterministic search + prefix match
│   │   └── ...
│   ├── tests/                        <- Vitest (285 tests)
│   └── docs/                         <- 9 handover docs (start with WORK_HANDOVER.md)
├── contract/API-CONTRACT.md          <- Shared API contract
└── docs/                             <- This handover + work summary
```

---

## 3. How to run everything from scratch

### Backend

```bash
cd server
npm install
npm run setup      # generates .env with a random JWT_SECRET
npm start          # migrate + seed + listen on :3000
```

Verify: `curl http://localhost:3000/ping`

A demo user is seeded automatically for local development (see `server/README.md`
for the demo login).

### Tests (stop `npm start` first — both use embedded PG port 5433)

```bash
npm test                    # 285 tests
npm run verify:migrations   # cold-build scratch DB from SQL files alone
```

### Android

1. Start the backend.
2. Emulator: either `10.0.2.2:3000` (default fallback) **or**
   `adb reverse tcp:3000 tcp:3000` if `API_BASE_URL` is `http://127.0.0.1:3000/`.
3. Open `app/` in Android Studio → Run `app`.
4. Unit tests: `.\gradlew.bat :app:testDebugUnitTest` (33 tests).

---

## 4. The five main user journeys

1. **Login / register** → JWT stored in DataStore; `GET /api/auth/me` restores profile.
2. **Browse & search events** → `GET /api/events` (filters) or `POST /api/ai/search`.
3. **Get directions** → `GET /api/events/:id/route?from=entrance` or `POST /api/navigation/path`.
4. **Bookmark & personalize** → `POST/DELETE /api/auth/me/bookmarks/:id`; `GET /api/ai/recommendations`.
5. **Chat & announcements** → Socket.IO connect → join rooms → `GET /api/chat/messages`
   for history → emit `chat:message` to talk.

---

## 5. Real-time architecture (chat + announcements)

```
Android ChatViewModel ──socket.io──► server/src/socket/index.js
        │                                   │
        │  emit "chat:message" { text }     │
        │ ─────────────────────────────────►│
        │                                   ├─ INSERT INTO chat_messages
        │                                   ├─ namespace.emit("chat:message", row)
        │◄──────────────────────────────────┤
        │  ack { ok, message }              │
        │◄──────────────────────────────────┤
        │                                   └─ ~500ms later: bot reply INSERT + emit
        │
        │  GET /api/chat/messages (on start / reconnect)
        │ ─────────────────────────────────► HubRepository.chatMessages()
        │◄────────────────────────────────── { messages: [...] }
        │                                   merged via ChatHistoryMerger
```

Key invariants:

- **Persist before broadcast.** If the DB write fails, the client gets
  `CHAT_PERSIST_FAILED` and no broadcast happens.
- **Merge, never wipe.** `ChatHistoryMerger.mergeIncoming` returns the existing
  list unchanged when the incoming page is empty.
- **Dedupe by id.** History and live socket echoes share the same `id`; the
  merger's `seenIds` set prevents double-rendering.

---

## 6. Database quick reference

| Table | Purpose |
| --- | --- |
| `venues` | 14 campus graph nodes |
| `events` | Catalogue; FK to venues |
| `users` | `id text` PK, email, bcrypt hash, interests jsonb |
| `bookmarks` | PK `(user_id, event_id)` |
| `search_history` | Recent queries; pruned to 50 per user |
| `announcements` | Event-relay log; `UNIQUE (room, message)` |
| `chat_messages` | Interactive chat (migration `004`) |

Migrations run automatically on every boot. `npm run db:reset` drops everything
(destructive — dev only).

---

## 7. API surface (implemented)

All routes are under `/api` except `GET /ping`. Full examples: `server/docs/API_REFERENCE.md`.

| Method | Path | Auth |
| --- | --- | --- |
| POST | `/api/auth/register`, `/api/auth/login` | none (rate-limited) |
| GET/PUT | `/api/auth/me`, `/api/auth/me/interests` | Bearer |
| GET/POST/DELETE | `/api/auth/me/bookmarks[/:eventId]` | Bearer |
| GET/POST | `/api/events`, `/api/events/:id` | optional / Bearer |
| GET | `/api/events/:id/details`, `/api/events/:id/route` | optional |
| POST | `/api/ai/search` | optional |
| GET | `/api/ai/recommendations` | Bearer |
| GET/POST | `/api/navigation/venues`, `/api/navigation/path` | none |
| GET | `/api/chat/announcements[?eventId=]` | optional |
| GET | `/api/chat/messages[?room=&before=&limit=]` | optional |
| POST | `/api/chat/join` | none |
| WS | `/socket.io` (namespaces `/` and `/announce`) | token optional |

---

## 8. Testing strategy

| Layer | Tool | Count |
| --- | --- | --- |
| Backend unit + integration | Vitest + supertest + socket.io-client | 285 |
| Migration cold-build | `scripts/verify-migrations.mjs` (scratch DB) | 4 migrations |
| Android unit | JUnit on JVM | 33 |
| Live smoke (manual) | `scripts/smoke.mjs`, `verify-socket.mjs`, `verify-events.mjs` | requires `VERIFY_BASE_URL` scratch server |

The live verifiers refuse to run against `emh` / `emh_test` — they always need
an explicit scratch target so they cannot corrupt a real database.

---

## 9. What is done vs. what is not

### Done and verified

- JWT auth, events CRUD + details aggregate, bookmarks, recommendations
- Deterministic search with prefix matching and explainable reasons
- Campus navigation graph + pathfinding
- Socket.IO announcement relay with DB dedup
- Interactive chat with DB persistence, history API, assistant auto-reply
- Embedded PostgreSQL, migration runner, cold-build verifier
- Android offline cache with honest `isStale` flagging
- Chat history retained across tab switches (ViewModel + merger)

### Not done / known gaps

- Push notifications (FCM), payments, QR tickets, waitlists — **not implemented**.
- Private DMs / per-user inboxes — chat is one public thread by design.
- Horizontal scaling of Socket.IO (single process).
- `occupancyPercent` `Int` vs `Double` type mismatch (see `MY_WORK_SUMMARY.md` §7).
- Automated on-device E2E for chat (manual emulator verification only).

---

## 10. Common tasks for the next developer

| Task | Where to look |
| --- | --- |
| Add a new REST endpoint | `src/routes/*` + Zod schema in `src/middleware/validators.js` + test |
| Add a DB column | New migration in `src/db/migrations/` + `dropAll` list in `migrate.js` + `npm run db:schema` |
| Change chat behaviour | `src/socket/index.js` + `src/models/chatMessageModel.js` + `tests/chat.test.js` |
| Change Android chat UI | `ChatScreen.kt` + `ChatViewModel.kt` + `ChatHistoryMerger.kt` |
| Update the contract | `contract/API-CONTRACT.md` (additive changes only) |
| Debug socket issues | Logcat tag `BitChatSocket`; check `adb reverse` or `10.0.2.2` |

---

## 11. Git branches on GitHub

| Branch | Commit | Status |
| --- | --- | --- |
| `master` | `26d9095` | Original Android skeleton — **unchanged** |
| `server-backend` | `4650157` | Earlier backend (pre-chat-persistence) — **unchanged** |
| `integration` | `b6317eb` | **Current work** — backend + Android + contract + docs |

The `integration` branch was built **on top of** `server-backend`, so the earlier
backend history is preserved in its ancestry. To make it the default, open a PR:

https://github.com/shritej-koneru/Event-Management-Hub-EPICS/pull/new/integration

---

## 12. Short message you can paste to the next person

> Hi — the Event Management Hub monorepo is on the `integration` branch
> (commit `b6317eb`). Backend lives in `server/`, Android in `app/`, contract in
> `contract/`. Run `cd server && npm install && npm run setup && npm start`,
> then `npm test` (285 tests). Android: open `app/` in Android Studio, unit tests
> via `gradlew.bat :app:testDebugUnitTest` (33 tests). Start with
> `docs/WORK_HANDOVER.md` and `server/docs/API_REFERENCE.md`. Chat is now
> persisted (migration `004`) and history is at `GET /api/chat/messages`; the
> Android `ChatViewModel` is activity-scoped so messages survive tab switches.
> If the emulator cannot reach :3000, run `adb reverse tcp:3000 tcp:3000` or use
> `10.0.2.2`. Demo login is in the README.
