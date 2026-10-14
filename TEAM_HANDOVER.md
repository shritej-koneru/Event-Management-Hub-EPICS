# Team Handover — Event Management Hub

**From:** satvi (Backend & Integration)
**To:** Next developer
**Date:** 2026-10-14
**Repo:** https://github.com/shritej-koneru/Event-Management-Hub-EPICS
**Branch:** `integration` (commit `045b138`)

---

## 1. What this project is

Campus event platform: **Android app** (Jetpack Compose) + **Node.js backend**
(Express + Socket.IO) + **PostgreSQL**. Students browse events, get directions,
bookmark favourites, and chat live — with an offline-first mobile client.

```
Event-Management-Hub-EPICS/     (integration branch)
├── app/                         Android client
├── server/                      Backend (Node/Express/Socket.IO)
├── contract/API-CONTRACT.md     Shared API contract v1.1.0
└── docs/                        Handover docs (this file + 3 more)
```

---

## 2. What I built

### Backend (`server/`)

| Feature | What it does | Key files |
| --- | --- | --- |
| **JWT auth** | Register, login, profile, interests, bookmarks | `src/auth/jwt.js`, `src/middleware/auth.js` |
| **Events** | List/filter/paginate; create; details aggregate (event+venue+stats+charts+announcements+nav in one call) | `src/services/eventService.js` |
| **Deterministic search** | Weighted field scorer (title 4, tags 3, category 3, …) with prefix matching — `"work"` matches `"workshop"` | `src/ai/scoring.js` |
| **Recommendations** | Ranks by interests + bookmarks + recent searches | `src/services/recommendationService.js` |
| **Navigation** | 14-node campus graph, shortest path, accepts ids or display names | `src/navigation/graph.js` |
| **Announcements** | Socket.IO relay to `announce:<venueId>`, DB-deduped | `src/socket/index.js` |
| **Chat persistence** | Messages stored in PostgreSQL **before** broadcast; history API | `src/db/migrations/004_chat_messages.sql`, `src/models/chatMessageModel.js` |
| **Embedded PG** | Real PostgreSQL cluster for dev/test — no Docker/install needed | `src/db/embedded.js` |
| **Migration verifier** | Cold-builds a scratch DB from SQL files alone | `scripts/verify-migrations.mjs` |

### Android (`app/`)

| Feature | What it does | Key files |
| --- | --- | --- |
| **Offline cache** | Shows cached events/search when backend down, flags `isStale` so UI shows "Offline" banner honestly | `data/HubRepository.kt`, `data/OfflineStore.kt` |
| **Chat history fix** | Messages survive tab switches (activity-scoped ViewModel) | `ui/screens/ChatViewModel.kt` |
| **Chat merge logic** | Pure Kotlin merge: never wipes on empty history, dedupes by id, renders own messages as "You" | `ui/screens/ChatHistoryMerger.kt` |
| **Chat UI** | Compose screen wired to ViewModel, loading/empty states | `ui/screens/ChatScreen.kt` |
| **Gradle fix** | `dos:readonly` reset before clean (Windows build fix) | `app/build.gradle.kts` |

---

## 3. The chat-history fix (main bug I solved)

**Problem:** Chat messages vanished every time the user switched tabs (Home ↔
Events ↔ Map). Two causes:

1. Backend never stored messages — live-only broadcast.
2. Android held the list in a Compose `remember` block, which is disposed on
   every tab switch.

**Fix:**

1. **Database** — migration `004_chat_messages.sql`:
   ```sql
   chat_messages(id bigint PK, author text, user_id text FK, room text FK,
                 text text CHECK(1..500), bot boolean, at timestamptz)
   ```
   Indexes on `(room, at DESC)` and `(at DESC)` for history pages.

2. **Backend socket handler** (`chat:message`):
   - Validate text (≤ 500 chars) → nack `BAD_CHAT_MESSAGE` if bad
   - Resolve author from JWT or "Guest"
   - **INSERT the row** → if INSERT fails, nack `CHAT_PERSIST_FAILED` and
     **do not broadcast**
   - Broadcast the stored row to everyone
   - Ack `{ ok: true, message }`
   - ~500 ms later: persist + emit assistant auto-reply (`bot: true`)

3. **History API** — `GET /api/chat/messages?room=&before=&limit=`
   - No params → latest 50 across all rooms, oldest-first
   - `before` is an exclusive ISO cursor for "load more"
   - Unknown room → `400 UNKNOWN_VENUE` (not empty list)

4. **Android `ChatViewModel`** (activity-scoped, survives tab switches):
   - `start()` → load history → connect socket
   - On socket connect → join all venue rooms → load history again
   - `loadHistory()` **merges** via `ChatHistoryMerger` — never replaces list
   - `sendChat()` emits `chat:message`, records acked id in `ownChatIds`
   - `onCleared()` disconnects socket

5. **`ChatHistoryMerger`** (pure Kotlin, no Android types — unit-testable):
   - `mergeIncoming`: if incoming empty → return existing unchanged
   - Dedupes by `id` via `seenIds` set
   - `displayAuthor`: own ids → "You", blank → "Guest"

6. **`ChatScreen`**: gets ViewModel scoped to hosting Activity, observes
   `messages`/`connected`/`isLoadingHistory` StateFlows, Send enabled only
   when connected + draft non-blank.

---

## 4. Search prefix fix

**Problem:** `POST /api/ai/search` with `"android dev work"` returned nothing
for an event titled "Android Development Workshop" — only whole-word matches
were checked.

**Fix** (`src/ai/scoring.js`):
```js
const MIN_PREFIX_LEN = 3;
function hits(words, term) {
  return words.some(w => w === term ||
    (term.length >= MIN_PREFIX_LEN && w.startsWith(term)));
}
```
So `"work"` (≥3 chars) matches `"workshop"`, but `"ca"` won't match random words.

---

## 5. How to run & test

### Backend

```bash
cd server
npm install
npm run setup      # generates .env with random JWT_SECRET
npm start          # migrate + seed + listen on :3000
```

Verify: `curl http://localhost:3000/ping`

**Tests** (stop `npm start` first — both use embedded PG port 5433):

```bash
npm test                    # 285 tests
npm run verify:migrations   # cold-build scratch DB from SQL files alone
```

### Android

1. Start backend.
2. Emulator: `10.0.2.2:3000` (default) **or** `adb reverse tcp:3000 tcp:3000`
   if app points at `127.0.0.1`.
3. Open `app/` in Android Studio → Run.
4. Unit tests: `.\gradlew.bat :app:testDebugUnitTest` (33 tests).

---

## 6. Verified test results (2026-10-14)

| Suite | Command | Result |
| --- | --- | --- |
| Backend | `npm test` | **285 passed** (12 files), 0 failed |
| Migrations | `npm run verify:migrations` | **ALL CHECKS PASSED** (4 migrations) |
| Android | `gradlew.bat :app:testDebugUnitTest` | **33 passed**, BUILD SUCCESSFUL |

Chat-specific:
- `tests/chat.test.js` — 16 tests (persist-before-broadcast, room scoping,
  500-char limit, assistant persistence, `before` cursor, unknown-room 400,
  no secrets on rows)
- `ChatHistoryMergeTest.kt` — 8 tests (empty refresh doesn't wipe, dedupe,
  "You" rendering, admit-once)

---

## 7. API surface (quick reference)

All under `/api` except `GET /ping`. Full details: `server/docs/API_REFERENCE.md`.

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
| WS | `/socket.io` (ns `/` and `/announce`) | token optional |

Socket events: `join`, `leave`, `events:for`, `event`, `chat:message`.

---

## 8. Git branches

| Branch | Commit | Notes |
| --- | --- | --- |
| `integration` | `045b138` | **Current work** — everything |
| `server-backend` | `4650157` | Earlier backend (pre-chat) — unchanged |
| `master` | `26d9095` | Original Android skeleton — unchanged |

`integration` was built on top of `server-backend`, so earlier history is in
its ancestry. To merge: open a PR from `integration`.

---

## 9. What's done vs. what's not

### Done & verified
- JWT auth, events + details aggregate, bookmarks, recommendations
- Deterministic search with prefix matching
- Campus navigation
- Socket.IO announcements (DB-deduped)
- Interactive chat with DB persistence, history API, assistant
- Embedded PostgreSQL, migration runner + verifier
- Android offline cache with honest stale flagging
- Chat history retained across tab switches

### Not done / known gaps
- Push notifications, payments, QR tickets, waitlists — **not implemented**
- Private DMs — chat is one public thread by design
- Horizontal scaling of Socket.IO (single process)
- **Known bug:** `EventStatistics.occupancyPercent` is `Int` in `Models.kt`
  but backend sends `91.7` (double) → can cause false Offline banner.
  Fix: change to `Double` and `.toDouble()` at `OfflineEngine.kt:275`.
- On-device E2E chat test was manual (emulator), not in CI

---

## 10. Common tasks for you

| Task | Where to look |
| --- | --- |
| Add REST endpoint | `src/routes/*` + Zod schema in `src/middleware/validators.js` + test |
| Add DB column | New migration in `src/db/migrations/` + `dropAll` list in `migrate.js` + `npm run db:schema` |
| Change chat behaviour | `src/socket/index.js` + `chatMessageModel.js` + `tests/chat.test.js` |
| Change Android chat UI | `ChatScreen.kt` + `ChatViewModel.kt` + `ChatHistoryMerger.kt` |
| Update contract | `contract/API-CONTRACT.md` (additive only) |
| Debug socket | Logcat tag `BitChatSocket`; check `adb reverse` or `10.0.2.2` |

---

## 11. More detailed docs (in repo)

| File | What's inside |
| --- | --- |
| `docs/MY_WORK_SUMMARY.md` | Full feature list with file references |
| `docs/PROJECT_HANDOVER.md` | Repo structure, setup, architecture diagrams |
| `docs/IMPLEMENTATION_EXPLANATION.md` | Beginner-friendly "how each feature works" |
| `server/docs/WORK_HANDOVER.md` | Backend-focused 11-section handover |
| `server/docs/API_REFERENCE.md` | Every endpoint with examples |
| `server/docs/SETUP_AND_TESTING.md` | Install, run, test, emulator troubleshooting |

---

## 12. Quick message you can paste

> Hi — the Event Management Hub monorepo is on the `integration` branch
> (commit `045b138`). Backend in `server/`, Android in `app/`, contract in
> `contract/`. Run `cd server && npm install && npm run setup && npm start`,
> then `npm test` (285 tests). Android: open `app/` in Android Studio, unit
> tests via `gradlew.bat :app:testDebugUnitTest` (33 tests). Start with
> `docs/PROJECT_HANDOVER.md` and `server/docs/API_REFERENCE.md`. Chat is now
> persisted (migration `004`) and history is at `GET /api/chat/messages`; the
> Android `ChatViewModel` is activity-scoped so messages survive tab switches.
> If the emulator can't reach :3000, run `adb reverse tcp:3000 tcp:3000` or use
> `10.0.2.2`. Demo login is in `server/README.md`.
