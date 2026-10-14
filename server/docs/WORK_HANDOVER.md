# Work Handover — Event Management Hub Backend

*Written for a teammate who has never seen this project. Read top to bottom.*

---

## 1. Executive summary

**Event Management Hub** helps students find campus events, navigate to rooms,
and chat/live-follow announcements. This repository is the **backend**: a Node.js
API + Socket.IO server backed by PostgreSQL. A separate repository holds the
Android app and the shared API contract.

Your job as the next developer: keep the server running, understand the five
main user journeys, and extend features without breaking the Android client.

## 2. What I implemented (Person 2)

| Area | What exists |
| --- | --- |
| HTTP API | Auth, events, bookmarks, AI search, recommendations, navigation, chat REST |
| Database | 4 migrations, seeds, embedded PostgreSQL for zero-install dev |
| Auth | JWT register/login, bearer middleware, optional auth for public reads |
| Search | Deterministic field scoring + prefix match (not a chatbot/LLM) |
| Real-time | Socket.IO rooms, event announcement relay, interactive chat |
| Chat persistence | `chat_messages` table; save-before-broadcast; `GET /api/chat/messages` |
| Quality | 285 Vitest tests, migration cold-build verifier, error codes |

## 3. Important source files

| File | Read this to understand… |
| --- | --- |
| `src/server.js` | How the process boots |
| `src/app.js` | Middleware order and `/ping` |
| `src/routes/index.js` | Which routers mount where |
| `src/middleware/validators.js` | Every Zod schema |
| `src/models/*.js` | All SQL |
| `src/socket/index.js` | Everything real-time |
| `src/models/chatMessageModel.js` | Chat insert + history |
| `src/db/migrate.js` | How migrations apply |
| `tests/helpers/testContext.js` | How tests talk to the app |

## 4. Main workflows (step by step)

### A. Student opens the app

1. App calls `GET /ping` → server alive + venues.
2. If a token was stored, `GET /api/auth/me` restores the profile.

### B. Browse and search events

1. `GET /api/events` with optional filters.
2. Search box → `POST /api/ai/search` `{ query }` → ranked events with scores.

### C. Get directions

1. Pick event → `GET /api/events/:id/route?from=entrance`.
2. Server resolves venue on the graph and returns steps.

### D. Bookmark / personalize

1. `POST /api/auth/me/bookmarks/:eventId`.
2. `GET /api/ai/recommendations` uses interests + bookmarks + recent searches.

### E. Chat and announcements

1. Socket connect (token optional) → `join` venue rooms.
2. History: `GET /api/chat/messages`.
3. Send: emit `chat:message` `{ text }` → server saves → broadcasts → ack.
4. Event create/update → announcement row → `event` emit to venue room.

## 5. APIs and database

- REST base: `/api/...` — see `docs/API_REFERENCE.md` for every endpoint.
- Errors always `{ error, code }` siblings.
- Tables: venues, events, users, bookmarks, search_history, announcements,
  chat_messages. Details: `docs/DATABASE_GUIDE.md`.

## 6. Authentication and real-time

- JWT only (no sessions/cookies). Socket may pass the same token in `auth.token`.
- Chat author = JWT name or `Guest`. Invalid socket token is rejected loudly.
- Chat messages are in PostgreSQL **before** they are broadcast.

## 7. Run my work from scratch

```bash
npm install && npm run setup && npm start
curl http://localhost:3000/ping
```

Full guide: `docs/SETUP_AND_TESTING.md`.

## 8. How to test each major feature

| Feature | Command / action |
| --- | --- |
| Everything | `npm test` (stop the server first) |
| Migrations only | `npm run verify:migrations` |
| Login | `POST /api/auth/login` with demo user |
| Search | `POST /api/ai/search` with `"android work"` |
| Path | `POST /api/navigation/path` Entrance → Room 204 |
| Chat | App Chat tab, or emit `chat:message` in a socket client |
| History after restart | Send a message, restart server, `GET /api/chat/messages` |

## 9. Known bugs, limits, dependencies

- **No private conversations** — chat is one public thread per optional room tag.
- **Assistant is rule-based**, not an AI model.
- **Single-process Socket.IO** — no Redis adapter yet.
- **Embedded PG is for dev/test only** — production needs `DATABASE_URL`.
- Android client lives in a **sibling folder** (`../Epics`), not in this repo.
- Contract file is outside this repo (`../Epics/contract/API-CONTRACT.md`).

## 10. What you should do next

1. Run `npm test` on your machine and note the pass count.
2. Walk workflow E (chat) end-to-end with the Android app.
3. Read `src/socket/index.js` and `004_chat_messages.sql` fully.
4. If you add a table: migration file + `dropAll` list + tests + `db:schema`.
5. If you add an endpoint: Zod schema + route + test + contract note.

## 11. Short message you can send to the next person

> Hi — the Event Management Hub backend is in `server/`. Run `npm install`,
> `npm run setup`, `npm start`, then `npm test`. Start with `docs/WORK_HANDOVER.md`
> and `docs/API_REFERENCE.md`. Chat is persisted (migration 004) and history is
> at `GET /api/chat/messages`. Ask if the Android emulator cannot reach :3000 —
> usually `adb reverse` or `10.0.2.2`. Demo login is in the README.
