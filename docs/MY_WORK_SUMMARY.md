# My Work Summary — Event Management Hub

**Contributor:** satvi (Person 2 — Backend & Integration)
**Repository:** https://github.com/shritej-koneru/Event-Management-Hub-EPICS
**Integration branch:** `integration`
**Integration commit:** `b6317eb` (2026-10-14)
**Local source commits:** backend `c42308e`, Android `2265ff2`

This document lists every feature I implemented, verified, or fixed. It is
based on the actual code in commit `b6317eb` and test runs executed against
that code. Nothing here is aspirational.

---

## 1. What I was responsible for

| Area | Scope |
| --- | --- |
| Backend (Node.js/Express) | Full server: auth, events, search, recommendations, navigation, Socket.IO, chat persistence |
| Database (PostgreSQL) | 4 migrations, seeds, schema dump, embedded-PG dev cluster |
| Real-time (Socket.IO) | Room join/leave, announcement relay, interactive chat with DB persistence |
| REST APIs | All `/api/*` endpoints behind the shared contract |
| Deterministic AI search | Field scoring engine with prefix matching and explainability |
| Testing | 295 backend Vitest tests (incl. 16 chat-persistence tests) |
| Documentation | 9 handover docs + README + API contract updates |
| Android integration (shared) | Chat history persistence UI (`ChatViewModel`, `ChatHistoryMerger`, `ChatScreen`) |

---

## 2. Features implemented (backend)

### 2.1 JWT authentication

- **What:** Register, login, profile, interests, bookmarks — all behind Bearer JWT.
- **Why:** The app needs a source of truth for who a user is and what they saved.
- **How:** `POST /api/auth/register|login` issue a JWT with `{ uid, email, name }`.
  `src/middleware/auth.js` verifies it on every protected route.
- **Files:** `src/auth/jwt.js`, `src/middleware/auth.js`, `src/controllers/authController.js`,
  `src/routes/authRoutes.js`, `tests/auth.test.js`.

### 2.2 Event catalogue & details aggregate

- **What:** List/filter/paginate events; create events (authenticated); one
  `GET /api/events/:id/details` call returns event + venue + statistics + charts
  + announcements + navigation.
- **Why:** The Android Event Details screen would otherwise need 5–6 separate
  calls; the aggregate keeps it to one.
- **Files:** `src/models/eventModel.js`, `src/services/eventService.js`,
  `src/controllers/eventController.js`, `tests/eventDetails.test.js`.

### 2.3 Bookmarks & recommendations

- **What:** Server-side bookmark store (PK `user_id, event_id`). `GET
  /api/ai/recommendations` ranks events using interests + bookmarks + recent
  searches.
- **Why:** Personalization must be server-truth so it survives device changes.
- **Files:** `src/models/bookmarkModel.js`, `src/services/recommendationService.js`,
  `src/services/profileService.js`.

### 2.4 Deterministic multi-field AI search (with prefix fix)

- **What:** `POST /api/ai/search` scores every event on title, tags, category,
  description, host, synonyms, and a small popularity tie-breaker. Returns
  0..1 scores with a human-readable `reason`.
- **Why:** Needed a fast, explainable search that does not depend on an external
  LLM. Fully offline-computable and testable.
- **Prefix fix:** `MIN_PREFIX_LEN = 3` in `src/ai/scoring.js` makes `"work"`
  match the token `"workshop"` via `word.startsWith(term)`. Without it, search
  only matched whole words, so `"android dev work"` returned nothing.
- **Files:** `src/ai/scoring.js`, `src/ai/tokenizer.js`, `src/services/aiSearchService.js`,
  `tests/ai.test.js`.

### 2.5 Campus navigation

- **What:** A fixed venue graph (`src/navigation/venues.js`, `graph.js`) with
  shortest-path between any two nodes. Accepts bare ids or display names.
- **Why:** The Map tab needs turn-by-turn style steps between campus buildings.
- **Files:** `src/navigation/*`, `src/controllers/navigationController.js`,
  `tests/navigation.test.js`.

### 2.6 Socket.IO announcement relay

- **What:** When an event is created/updated, the server writes an
  `announcements` row (deduped by `UNIQUE (room, message)`) and emits `event`
  to `announce:<venueId>` on both namespaces `/` and `/announce`.
- **Why:** Live "new event" banners on every connected client without polling.
- **Files:** `src/socket/index.js` (`broadcastEventUpdate`), `src/models/announcementModel.js`,
  `tests/socket.test.js`.

### 2.7 Interactive chat with persistence (major fix)

- **What:** Socket.IO `chat:message` is now **persisted to PostgreSQL before
  broadcast**. A new `GET /api/chat/messages` endpoint returns history for
  reconnect/cold start. Assistant auto-replies are also persisted (`bot: true`).
- **Why (root cause):** Chat messages used to live only in a Jetpack Compose
  `remember` block inside `ChatScreen`. Every tab switch disposed the
  composition, dropped the list, and the backend never stored them — so history
  vanished on navigation or reconnect.
- **Files:** see §3 for the full chat-history fix breakdown.

### 2.8 Embedded PostgreSQL for zero-install dev

- **What:** `src/db/embedded.js` starts a real PostgreSQL 18 cluster from the
  `embedded-postgres` devDependency on port 5433 when `DATABASE_URL` is empty.
- **Why:** Students and CI need a real database with no Docker/admin rights.
- **Files:** `src/db/embedded.js`, `src/db/bootstrap.js`, `scripts/setup.mjs`.

### 2.9 Migration runner + cold-build verifier

- **What:** `src/db/migrate.js` applies `001`–`004` in one transaction each.
  `scripts/verify-migrations.mjs` builds a scratch database from the SQL files
  alone (no app code) and asserts table shape.
- **Why:** Guarantees a fresh install works, not just the developer's machine.
- **Files:** `src/db/migrations/001_init.sql` … `004_chat_messages.sql`,
  `scripts/verify-migrations.mjs`.

---

## 3. The chat-history fix in detail

This was the highest-impact fix in the project.

### 3.1 Database — migration `004_chat_messages.sql`

```sql
CREATE TABLE IF NOT EXISTS chat_messages (
  id      bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  author  text        NOT NULL,
  user_id text        REFERENCES users (id) ON UPDATE CASCADE ON DELETE SET NULL,
  room    text        REFERENCES venues (id) ON UPDATE CASCADE ON DELETE SET NULL,
  text    text        NOT NULL,
  bot     boolean     NOT NULL DEFAULT false,
  at      timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT chat_messages_text_length CHECK (char_length(text) BETWEEN 1 AND 500)
);
CREATE INDEX idx_chat_messages_room_at ON chat_messages (room, at DESC);
CREATE INDEX idx_chat_messages_at      ON chat_messages (at DESC);
```

- `room` stores a **bare** venue id (`"room-204"`) or `NULL` for the global
  namespace-wide thread the Android client joins by default.
- No JWT, password, or secret is ever stored on a row.
- The two indexes cover the two query shapes: "latest N for a room" and
  "latest N globally", both paginated by `at DESC`.

### 3.2 Model — `chatMessageModel.js`

| Function | Purpose |
| --- | --- |
| `insertChatMessage` | Single INSERT + RETURNING; called **before** any broadcast |
| `listRecentChatMessages` | Chronological page; optional `room` filter, optional `before` cursor (exclusive), `limit` capped at 100 |
| `countChatMessages` | Row count (used by tests) |

The wire format is `{ id, author, text, room, bot, ts }` — identical for the
REST response and the Socket.IO broadcast payload.

### 3.3 Socket handler — persist-before-broadcast

In `src/socket/index.js`, the `chat:message` handler now:

1. Validates `text` (required, ≤ 500 chars) → nack `BAD_CHAT_MESSAGE` if bad.
2. Resolves `author` from the JWT user or `"Guest"`.
3. Normalizes optional `room` to a real graph venue id or `null`.
4. **INSERTs the row.** If the INSERT fails → nack `CHAT_PERSIST_FAILED` and
   **does not broadcast**.
5. Broadcasts the immutable stored message to the whole namespace.
6. Acks `{ ok: true, message }`.
7. After ~500 ms, persists and emits an assistant auto-reply (`bot: true`) to
   the sender.

### 3.4 REST history — `GET /api/chat/messages`

| Query | Meaning |
| --- | --- |
| *(none)* | latest across all rooms |
| `room` | bare venue id or `announce:<id>` |
| `before` | ISO-8601 exclusive cursor |
| `limit` | 1–100 (default 50) |

- Unknown `room` → `400 UNKNOWN_VENUE` (not an empty list).
- Malformed `before` / extra params → `400 VALIDATION_ERROR`.
- Auth is optional (matches anonymous chat).

**Files:** `src/controllers/chatController.js`, `src/routes/chatRoutes.js`,
`src/middleware/validators.js` (`chatMessageQuerySchema`).

### 3.5 Android — `ChatViewModel`

- **What:** An **activity-scoped** `ViewModel` that holds the message list,
  draft, connected flag, and the Socket.IO connection itself.
- **Why:** `ViewModel` survives configuration changes and tab switches (it lives
  in the Activity's `ViewModelStore`). The previous `remember` state did not.
- **How:**
  - `start()` is idempotent: first call loads history via
    `HubRepository.chatMessages()`, then connects the socket.
  - On socket connect it joins every venue room and calls `loadHistory()` again.
  - `loadHistory()` **merges** the page via `ChatHistoryMerger` — it never
    replaces the list with an empty array.
  - `sendChat()` emits `chat:message` and records the acked id in `ownChatIds`
    so the UI can render it as "You".
  - `onCleared()` disconnects the socket.
- **File:** `app/src/main/java/.../ui/screens/ChatViewModel.kt`.

### 3.6 Android — `ChatHistoryMerger`

A pure Kotlin object with no Android types, so unit tests run without a device:

| Function | Behaviour |
| --- | --- |
| `mergeIncoming` | Appends rows whose id is not already in `seenIds`; returns `existing` unchanged when `incoming` is empty |
| `displayAuthor` | Renders `ownIds` as `"You"`, blank authors as `"Guest"` |
| `admit` | Returns true only once per id |

**File:** `app/src/main/java/.../ui/screens/ChatHistoryMerger.kt`.
**Tests:** `ChatHistoryMergeTest.kt` (8 tests).

### 3.7 Android — `ChatScreen`

- Obtains the `ChatViewModel` scoped to the hosting `ComponentActivity`
  (`LocalContext.current as ComponentActivity` + factory).
- Collects `messages`, `connected`, `isLoadingHistory` as `StateFlow`.
- Shows a loading spinner only when history is loading and the list is still
  empty; shows an empty-state hint when there are genuinely no messages.
- The Send button is enabled only when `connected && draft.isNotBlank()`.

**File:** `app/src/main/java/.../ui/screens/ChatScreen.kt`.

---

## 4. Other verified modifications

| Change | File(s) | Notes |
| --- | --- | --- |
| Gradle `dos:readonly` fix | `app/build.gradle.kts` | `taskGraph.whenReady` sets files writable before clean; prevents Windows read-only build failures |
| Contract §6 update | `contract/API-CONTRACT.md` | Documents `GET /api/chat/messages` and the persist-before-broadcast `chat:message` |
| `ChatMessage` / `ChatMessagesResponse` models | `Models.kt` | Wire format matches backend exactly |
| `HubApi.chatMessages` | `HubApi.kt` | Retrofit method with `room`/`before`/`limit` |
| `HubRepository.chatMessages` | `HubRepository.kt` | Never replaces existing thread on error |
| Migration 4-count plumbing | `migrate.js`, `verify-migrations.mjs`, `server.js` | Added `chat_messages` to drop lists, verifier checks, route printout |

---

## 5. Verified test results

### 2026-10-20 — indoor maps / integration pass (`feat/indoor-maps`)

| Suite | Command | Result |
| --- | --- | --- |
| Backend unit + integration | `npm test` | **295 passed** (12 files), 0 failed |
| Migration cold-build | `npm run verify:migrations` | **ALL CHECKS PASSED** (5 migrations, `venue_edges` + map columns verified) |
| Android unit | `.\gradlew.bat :app:testDebugUnitTest` | **38 passed** (OfflineEngineTest 25 + OfflineMapTest 4 + ChatHistoryMergeTest 8 + ExampleUnitTest 1), BUILD SUCCESSFUL |

Map coverage: `GET /api/navigation/map` (14 venues, 19 edges, canonical
layout), `GET /api/navigation/venues/:venueId/map` (id and display name;
unknown/near-miss → `400 UNKNOWN_VENUE`), DB-vs-JS graph consistency, and the
Android offline map mirror (`OfflineMapTest`). Live HTTP smoke confirmed
`/ping` → `contractVersion: 1.2.0`.

### 2026-10-14 — chat history pass (commits `c42308e` / `b6317eb`)

| Suite | Command | Result |
| --- | --- | --- |
| Backend unit + integration | `npm test` | **285 passed** (12 files), 0 failed |
| Migration cold-build | `npm run verify:migrations` | **ALL CHECKS PASSED** (4 migrations, `chat_messages` present, CHECK constraints verified) |
| Android unit | `.\gradlew.bat :app:testDebugUnitTest` | **33 passed** (OfflineEngineTest 25 + ChatHistoryMergeTest 8), BUILD SUCCESSFUL |

Chat-specific tests included in the 285:

- `tests/chat.test.js` — 16 tests covering persist-before-broadcast, room
  scoping, 500-char limit, assistant persistence, `before` cursor pagination,
  unknown-room 400, anonymous access, and a no-secrets-on-rows assertion.

Android chat tests (8):

- Empty history refresh does not wipe thread.
- History page appends in order without duplicate ids.
- Own messages render as "You".
- Blank authors fall back to "Guest"; blank texts skipped.
- Reconnect resync does not duplicate a live echo.
- `admit` returns true only once per id.

---

## 6. Documentation I wrote

| File | Purpose |
| --- | --- |
| `server/docs/WORK_HANDOVER.md` | Main handover (11 sections, beginner-oriented) |
| `server/docs/PROJECT_OVERVIEW.md` | Architecture & stack |
| `server/docs/BACKEND_IMPLEMENTATION.md` | Feature-by-feature backend guide |
| `server/docs/DATABASE_GUIDE.md` | Schema, migrations, seeds |
| `server/docs/API_REFERENCE.md` | Every REST + Socket.IO endpoint |
| `server/docs/REALTIME_WORKFLOWS.md` | Socket.IO + chat persistence flows |
| `server/docs/SETUP_AND_TESTING.md` | Install, run, test, emulator setup |
| `server/docs/PROJECT_FLOW.md` | Mermaid architecture & sequence diagrams |
| `server/docs/CHANGELOG.md` | Real git history + this handover |
| `server/README.md` | Entry point with doc links |

---

## 7. Known limitations (honest list)

- **Chat is one public thread** (optional `room` tag). No private DMs.
- **Assistant is rule-based**, not an LLM — it pattern-matches keywords and may
  query the events table.
- **Single-process Socket.IO** — no Redis adapter for horizontal scaling.
- **Embedded PG is dev/test only** — production must set `DATABASE_URL`.
- **No outdoor/GPS maps** — the campus map is an indoor canvas layout served
  by `GET /api/navigation/map`; there are no real-world coordinates anywhere
  in the project by design.
- **On-device end-to-end verification** of chat across tab switches was done
  manually on an emulator (`emh_test`) but not automated in CI.

---

## 8. What a reviewer should check first

1. `server/src/socket/index.js` — the `chat:message` handler (persist → broadcast).
2. `server/src/db/migrations/004_chat_messages.sql` — table + indexes.
3. `app/src/main/java/.../ChatViewModel.kt` — activity-scoped state + socket.
4. `app/src/main/java/.../ChatHistoryMerger.kt` — pure merge rules + 8 tests.
5. `server/tests/chat.test.js` — 16 backend tests for the write path.
6. `server/src/ai/scoring.js` — prefix matching (`MIN_PREFIX_LEN`).
