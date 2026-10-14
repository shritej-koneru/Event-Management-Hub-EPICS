# How the Implemented Features Work — Beginner Guide

This document explains the Event Management Hub in plain English. It assumes
you know basic programming but not necessarily Node.js, PostgreSQL, Socket.IO,
or Jetpack Compose. Every feature described here **exists in the code** —
nothing is aspirational.

---

## 0. The big picture

```
┌─────────────────────┐        HTTP (REST)         ┌──────────────────────┐        SQL        ┌────────────┐
│   Android app       │ ──────────────────────────► │   Node.js backend    │ ────────────────► │ PostgreSQL │
│   (Jetpack Compose) │ ◄────────────────────────── │   (Express + socket.io)│ ◄──────────────── │            │
└─────────────────────┘        JSON responses       └──────────────────────┘                    └────────────┘
         │                               │                    │
         │  Socket.IO (live chat &       │  Socket.IO         │
         │  announcements)               │  (same server)     │
         └───────────────────────────────┴────────────────────┘
```

- The **Android app** shows the UI. It talks to the backend over HTTP for most
  things, and keeps one long-lived Socket.IO connection for live chat and
  announcements.
- The **backend** is a Node.js server. It validates every request, runs SQL
  against PostgreSQL, and pushes live updates through Socket.IO.
- **PostgreSQL** stores everything: users, events, bookmarks, announcements,
  and now chat messages.

In local development the backend starts its **own** PostgreSQL (embedded) so
you don't need to install anything.

---

## 1. Authentication (JWT)

**What it does:** Lets a user register, log in, and stay logged in.

**Why it's needed:** Bookmarks, recommendations, and personalized search must
know *who* is asking.

**How it works, step by step:**

1. User fills the registration form → app sends `POST /api/auth/register` with
   name, email, password.
2. Backend hashes the password with bcrypt, inserts a row in `users`, creates a
   JWT (a signed digital token) containing `{ uid, email, name }`, and returns it.
3. App stores the token in Android DataStore and attaches it to every future
   request as `Authorization: Bearer <token>`.
4. On each protected request, `src/middleware/auth.js` verifies the token's
   signature. If valid, the user id is available to the handler. If not, the
   request gets `401 AUTH_INVALID_TOKEN`.

**Key files:**
- `server/src/auth/jwt.js` — sign/verify
- `server/src/middleware/auth.js` — Express middleware
- `app/src/main/java/.../data/api/ApiClient.kt` — attaches the header

---

## 2. Events & the details aggregate

**What it does:** Lists events with filters; returns *everything* about one
event in a single call.

**Why it's needed:** The Event Details screen needs the event, its venue, seat
statistics, charts, announcements, and walking directions. Six separate HTTP
calls would be slow and fragile; one aggregate call is fast and atomic.

**How it works:**

1. `GET /api/events?category=Tech&date=2026-10-20` runs a filtered SQL query.
2. `GET /api/events/evt_001/details?from=entrance` runs one controller that
   gathers:
   - the event row,
   - its venue (with neighbour list),
   - computed statistics (seats remaining, occupancy %, days until event),
   - chart data (category breakdown, registration over time),
   - recent announcements for that event,
   - a navigation path from the chosen start node.
3. All of that is returned as one JSON object. The Android screen just renders it.

**Key files:**
- `server/src/services/eventService.js`
- `server/src/controllers/eventController.js`
- `app/src/main/java/.../data/model/Models.kt` (`EventDetailsResponse`)

---

## 3. Deterministic AI search (with prefix matching)

**What it does:** Takes a free-text query like `"android dev work"` and returns
ranked events with a score (0–1) and a human-readable reason.

**Why "AI" but not an LLM:** The scoring is **deterministic** — same query, same
result, every time. It is fully testable and runs offline. It is *not* a neural
network; it is a weighted field scorer.

**How it works, step by step:**

1. Query is tokenized into lowercase terms (`["android", "dev", "work"]`).
2. For each event, each term is checked against:
   - **title** (weight 4)
   - **tags** (3)
   - **category** (3)
   - **description** (2)
   - **category synonyms** (2.5) — e.g. "tech" ↔ "technology"
   - **host** (1.5)
3. **Prefix matching:** a term matches a word if it equals the word **or** is a
   leading fragment of it (and the term is ≥ 3 characters). So `"work"` matches
   `"workshop"`. This was a real bug fix — without it, `"android dev work"`
   returned nothing.
4. Popularity (registered/capacity) is added only as a tiny tie-breaker; it can
   never make an unrelated event appear.
5. The raw score is squashed to 0–1 with `x / (x + 6)`.
6. Only events with textual evidence above a floor (`MIN_TEXT_RAW = 0.5`) are
   returned.

**Key files:**
- `server/src/ai/scoring.js` — the scorer
- `server/src/ai/tokenizer.js` — tokenizing + synonym map
- `server/tests/ai.test.js` — pins the behaviour

---

## 4. Bookmarks & recommendations

**What it does:** Lets a user save events; suggests events based on interests,
bookmarks, and recent searches.

**How it works:**

1. `POST /api/auth/me/bookmarks/evt_001` inserts `(user_id, event_id)` into the
   `bookmarks` table (primary key prevents duplicates).
2. `GET /api/ai/recommendations` loads the user's interests, bookmarked event
   ids, and last 50 search queries.
3. Each event gets a personalization boost:
   - +1.5 if its category/tags/title match a saved interest
   - +1.5 if it is in the bookmark list
   - +1.0 if it matches a recent search term
4. The boost is added to the textual score, then normalized to 0–1. An event
   with no textual match is never rescued by the boost alone.

**Key files:**
- `server/src/models/bookmarkModel.js`
- `server/src/services/recommendationService.js`
- `server/src/ai/scoring.js` (`personalizationSignal`)

---

## 5. Campus navigation

**What it does:** Given a start and end venue, returns step-by-step directions.

**How it works:**

1. `src/navigation/venues.js` defines 14 campus nodes (Entrance, Library,
   Room 204, Main Hall, …) and their neighbours.
2. `src/navigation/graph.js` runs a shortest-path algorithm (BFS with edge
   weights) between the two nodes.
3. The result is a list of `{ node, instruction, distanceM }` steps.
4. Both bare ids (`"room-204"`) and display names (`"Room 204"`) are accepted.

**Key files:**
- `server/src/navigation/graph.js`
- `server/src/navigation/venues.js`
- `server/tests/navigation.test.js`

---

## 6. Socket.IO — live announcements

**What it does:** When an organizer creates or updates an event, every connected
client sees a banner instantly.

**How it works, step by step:**

1. `POST /api/events` (authenticated) creates the event.
2. The controller calls `broadcastEventUpdate(io, event)`.
3. That function writes an `announcements` row with
   `INSERT ... ON CONFLICT (room, message) DO NOTHING` — so relaying the same
   event twice is a no-op.
4. Only if a row was actually created does it emit `event` to the Socket.IO room
   `announce:<venueId>` on both namespaces (`/` and `/announce`).
5. The Android `ChatViewModel` listens for `event` and shows a banner +
   a system message in the chat thread.

**Key files:**
- `server/src/socket/index.js` (`broadcastEventUpdate`)
- `server/src/models/announcementModel.js`

---

## 7. Socket.IO — interactive chat (with persistence)

This is the feature that needed the biggest fix.

### The problem

Messages used to live only in a Jetpack Compose `remember` variable inside
`ChatScreen`. Two things went wrong:

1. **Tab switch** → the composable was disposed → the list was gone.
2. **Backend never stored messages** → reconnect or app restart = empty thread.

### The fix (backend)

1. **New table** `chat_messages` (migration `004`):

   ```sql
   id      bigint identity primary key
   author  text not null
   user_id text references users(id)   -- NULL for Guest
   room    text references venues(id)  -- NULL for global thread
   text    text not null check (length between 1 and 500)
   bot     boolean default false
   at      timestamptz default now()
   ```

2. **Socket handler** (`chat:message`):
   - Validate text (required, ≤ 500 chars).
   - Resolve author from JWT or "Guest".
   - **INSERT the row.** If the insert fails → ack `CHAT_PERSIST_FAILED` and
     **do not broadcast**.
   - Broadcast the stored row to everyone.
   - Ack `{ ok: true, message }`.
   - ~500 ms later, insert and emit an assistant auto-reply (`bot: true`).

3. **History endpoint** `GET /api/chat/messages`:
   - No params → latest 50 across all rooms, oldest-first.
   - `?room=room-204` → only that venue.
   - `?before=<ISO timestamp>` → older page (cursor).
   - `?limit=1..100` → page size.

### The fix (Android)

1. **`ChatViewModel`** (activity-scoped):
   - Lives in the Activity's `ViewModelStore`, so it survives tab switches.
   - Holds: message list, draft text, connected flag, the Socket.IO object.
   - `start()` (called once from `ChatScreen`):
     1. Loads history via `HubRepository.chatMessages()`.
     2. Connects the socket.
   - On socket connect: joins every venue room, then loads history again.
   - `loadHistory()` **merges** the page — it never replaces the list.

2. **`ChatHistoryMerger`** (pure Kotlin, no Android types):
   - `mergeIncoming(existing, incoming, seenIds, ownIds)`:
     - If `incoming` is empty → return `existing` unchanged.
     - Otherwise append rows whose id is not already in `seenIds`.
   - `displayAuthor`: renders your own ids as "You", blank authors as "Guest".

3. **`ChatScreen`**:
   - Gets the ViewModel scoped to the hosting Activity.
   - Observes `messages`, `connected`, `isLoadingHistory` as StateFlows.
   - Shows a spinner only while history is loading and the list is empty.
   - Send button enabled only when connected and draft is non-blank.

### End-to-end flow (user sends "hello")

```
User taps Send
    │
    ▼
ChatViewModel.sendChat()
    │  emit "chat:message" { text: "hello" }
    ▼
Server socket handler
    │  1. validate text
    │  2. resolve author (JWT or "Guest")
    │  3. INSERT INTO chat_messages ... RETURNING id, author, ...
    │  4. namespace.emit("chat:message", row)   ← broadcast to everyone
    │  5. ack { ok: true, message }
    ▼
Every connected ChatViewModel
    │  appendChatMessage(id, author, text)
    │  (deduped by id via seenChatIds)
    ▼
UI shows the message
```

---

## 8. Offline cache (Android)

**What it does:** Shows events, announcements, and search results even when the
backend is unreachable — and **says so** with an "Offline" banner.

**How it works:**

1. `HubRepository` always tries the live API first.
2. On success, it refreshes `OfflineStore` (a JSON file on device).
3. On network failure, it returns cached data with `isStale = true`.
4. Screens read `isStale` and show a banner — they never pretend cached data is
   live.

**Key files:**
- `app/src/main/java/.../data/HubRepository.kt`
- `app/src/main/java/.../data/OfflineStore.kt`
- `app/src/main/java/.../data/OfflineEngine.kt` (offline search/recommendations/path)

---

## 9. Error handling (backend)

Every error response has the same shape:

```json
{ "error": "human readable message", "code": "EVENT_NOT_FOUND" }
```

- `error` is always a string the UI can show directly.
- `code` is a stable machine-readable sibling (never nested).
- Zod validation failures become `VALIDATION_ERROR`.
- Malformed JSON becomes `INVALID_REQUEST`.
- SQL unique violations become `409 DUPLICATE`.

This makes the Android `ErrorBody` parser trivial and consistent.

---

## 10. Testing (what is actually covered)

| Suite | What it pins |
| --- | --- |
| `tests/chat.test.js` (16) | Persist-before-broadcast, room scoping, 500-char limit, assistant persistence, `before` cursor, unknown-room 400, no secrets on rows |
| `tests/socket.test.js` | Room join/leave, announcement relay, auth on handshake |
| `tests/ai.test.js` | Prefix matching, synonym boost, popularity isolation |
| `tests/auth.test.js` | Register/login/JWT middleware |
| `tests/eventDetails.test.js` | Aggregate payload shape |
| `tests/navigation.test.js` | Pathfinding |
| `tests/schema.test.js` | DB constraints |
| `ChatHistoryMergeTest.kt` (8) | Empty refresh does not wipe; dedupe; "You" rendering |
| `OfflineEngineTest.kt` (25) | Offline search, recommendations, path, details |

**Verified locally on 2026-10-14:**
- Backend: `npm test` → **285 passed** (12 files)
- Migrations: `npm run verify:migrations` → **ALL CHECKS PASSED**
- Android: `gradlew.bat :app:testDebugUnitTest` → **33 passed**, BUILD SUCCESSFUL

---

## 11. Glossary

| Term | Meaning |
| --- | --- |
| **JWT** | JSON Web Token — a signed, stateless identity token |
| **Socket.IO** | A library that keeps a live WebSocket (with fallbacks) between app and server |
| **Namespace** | A separate Socket.IO channel (`/` and `/announce` here) |
| **Room** | A named group inside a namespace (here: `announce:<venueId>`) |
| **Migration** | A numbered SQL file applied in order to create/alter tables |
| **ViewModel** | An Android object that survives screen/config changes |
| **StateFlow** | A reactive stream the UI collects (like LiveData but Kotlin-first) |
| **Persist-before-broadcast** | Write to DB first; only then send to clients |
| **Deterministic search** | Same input → same output, no randomness, no LLM |
