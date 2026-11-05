# API Reference

Base URL (local): `http://localhost:3000`  
Android emulator → host: `http://10.0.2.2:3000` (or `adb reverse tcp:3000 tcp:3000` with `127.0.0.1`).

All errors: `{ "error": "<human message>", "code": "<CODE>" }` with sibling keys.

Contract version reported by `/ping`: `1.3.0`.

---

## GET /ping

Health + venue catalogue. No auth. `databaseName` is no longer returned to remote
clients: it appears only for loopback callers outside production (used by the verify scripts). `databaseName` is no longer returned to remote
clients: it appears only for loopback callers outside production (used by the verify scripts).

```bash
curl -s http://localhost:3000/ping
```

```json
{ "status": "ok", "ok": true, "service": "emh-server", "contractVersion": "1.3.0",
  "database": "connected", "venues": [ { "id": "entrance", "name": "Entrance" } ] }
```

---

## POST /api/auth/register

Body: `{ "name", "email", "password", "interests"? }`  
→ `201` `{ token, user }` · `409 EMAIL_TAKEN` · `400 VALIDATION_ERROR`

```bash
curl -s -X POST http://localhost:3000/api/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"name":"Test","email":"test@example.com","password":"secret123"}'
```

## POST /api/auth/login

Body: `{ "email", "password" }` → `200` `{ token, user }` · `401 INVALID_CREDENTIALS`

```bash
curl -s -X POST http://localhost:3000/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"alice@uni.edu","password":"secret123"}'
```

Demo user (dev seed only): `alice@uni.edu` / `secret123`.

## GET /api/auth/me · PUT /api/auth/me/interests

Bearer required. The returned `user` now includes `role` (`student` | `organizer` | `admin`; default
`student`; additive). `PUT` body: `{ "interests": ["Tech", ...] }` (max 20).

## Bookmarks

| Method | Path | Auth |
| --- | --- | --- |
| GET | `/api/auth/me/bookmarks` | Bearer → `{ "bookmarks": ["evt_001"] }` |
| POST | `/api/auth/me/bookmarks/:eventId` | Bearer |
| DELETE | `/api/auth/me/bookmarks/:eventId` | Bearer |

---

## GET /api/events

Optional auth (adds bookmark flags when Bearer present).

| Query | Notes |
| --- | --- |
| `category` | case-insensitive |
| `date` | `YYYY-MM-DD` |
| `venue` | id (`room-204`) or display name (`Room 204`) |
| `q` | free-text |
| `page`, `pageSize` | opt-in pagination; returns `pagination` block |

```bash
curl -s 'http://localhost:3000/api/events?category=Tech&pageSize=5'
```

## POST /api/events

Bearer + role `organizer` or `admin` required (`403 FORBIDDEN` for a `student`). Body matches contract
Event create schema (`title`, `category`, `venue` display name, `date`, `time`, `capacity`, …). Venue must
be a graph node. `400 PAST_DATE` if `date` is before today (UTC); `409 DUPLICATE_EVENT` if an event with
the same title (case-insensitive), date and venue exists. The creator is stored as `created_by`.

## PUT /api/events/:id · DELETE /api/events/:id

Bearer + role `organizer`/`admin`, and the caller must be the event creator or an `admin` (`403 FORBIDDEN`
otherwise; `404 EVENT_NOT_FOUND`). Seeded events have no creator, so only admins can change them.
`PUT` takes any subset of the create fields (at least one, else `400`) → `200 { event }` and broadcasts
`event` with `reason: "updated"`. `DELETE` → `200 { deleted: true, eventId }` and broadcasts `event` with
`reason: "deleted"` (relayed live, not persisted as an announcement).

## Auth on optional-auth routes

A request with **no** `Authorization` header is anonymous. A header that IS present but invalid/expired
now returns `401 INVALID_TOKEN` (previously silently anonymous). JWTs are signed and verified as HS256 only.

## GET /api/events/:id · /details · /route

- `/:id` → `{ "event": { ... } }`
- `/:id/details?from=` → event + venue + statistics + charts + announcements + navigation
- `/:id/route?from=` → same shape as `POST /api/navigation/path`

---

## POST /api/ai/search

Body: `{ "query": "android workshop", "limit": 10 }`  
Deterministic multi-field scoring (not an LLM). Prefix terms ≥ 3 chars match word starts.

```bash
curl -s -X POST http://localhost:3000/api/ai/search \
  -H 'Content-Type: application/json' \
  -d '{"query":"android dev work","limit":5}'
```

## GET /api/ai/recommendations

Mounted with `optionalAuth`, but the controller still answers `401 AUTH_REQUIRED` when there is no
valid Bearer, so a login is effectively required. Ranks events from interests + bookmarks + search history.

---

## GET /api/navigation/venues

No auth → all graph nodes with neighbours.

## GET /api/navigation/map (1.2.0)

No auth → whole-campus canvas layout read from the seeded database
(`venues.map_x/map_y`, `venue_edges`): fractional positions (0..1) plus the
undirected walk graph. This is what the Android map draws — the client no
longer hard-codes venue coordinates.

```bash
curl -s 'http://localhost:3000/api/navigation/map'
```

## GET /api/navigation/venues/:venueId/map (1.2.0)

No auth → one venue node with its positioned neighbours and edge distances.
`:venueId` accepts a graph node id or a URL-encoded canonical display name;
anything unknown (including a near-miss like `room 204`) is
`400 UNKNOWN_VENUE` — never a guess.

```bash
curl -s 'http://localhost:3000/api/navigation/venues/room-204/map'
curl -s 'http://localhost:3000/api/navigation/venues/Room%20204/map'
```

## POST /api/navigation/path

`POST` (not GET), no auth. `POST` (not GET), no auth. Body: `{ "from": "Entrance", "to": "Room 204" }` → nodes, steps, totalDistanceM.

---

## GET /api/chat/announcements[?eventId=]

REST fallback for event-relay announcements (last 20, newest first).

```bash
curl -s 'http://localhost:3000/api/chat/announcements?eventId=evt_001'
```

## GET /api/chat/messages[?room=&before=&limit=]

Persisted interactive chat history (oldest-first page). Auth optional.

| Query | Meaning |
| --- | --- |
| *(none)* | latest across all rooms |
| `room` | bare venue id or `announce:<id>` |
| `before` | ISO-8601 exclusive cursor |
| `limit` | 1–100 (default 50) |

```bash
curl -s 'http://localhost:3000/api/chat/messages?limit=20'
```

```json
{ "messages": [ { "id": 1, "author": "Guest", "text": "hello", "room": null, "bot": false, "ts": "..." } ] }
```

| Failure | Code |
| --- | --- |
| unknown room | `400 UNKNOWN_VENUE` |
| bad `before` / extra param | `400 VALIDATION_ERROR` |

## POST /api/chat/join

Body: `{ "venueId": "room-204" }` → `{ "ok": true, "room": "announce:room-204" }`.
Stateless echo: validates the venue is a real graph node (`400 UNKNOWN_VENUE`) but does not join anything.
Real membership is established by the Socket.IO `join` event..
Stateless echo: validates the venue is a real graph node (`400 UNKNOWN_VENUE`) but does not join anything.
Real membership is established by the Socket.IO `join` event.

---

## Socket.IO

Path `/socket.io`, namespaces `/` and `/announce`.

| Event | Direction | Notes |
| --- | --- | --- |
| `join` | client → server | string or array of bare venue ids; ack `{ ok, joined }` |
| `leave` | client → server | ack `{ ok, left }` |
| `joined` | server → client | room list after join |
| `events:for` | client → server | venue events; ack `{ ok, events }` |
| `event` | server → room | announcement relay payload |
| `chat:message` | both | see below |
| `chat:error` | server → client | `{ code, message }` for rejected chat posts |

### chat:message

Client: `socket.emit("chat:message", { text, room?, clientId? }, ack)`

Server **persists then broadcasts**. Payload:

```json
{ "id": 42, "author": "Guest", "text": "hi", "room": null, "bot": false, "ts": "..." }
```

**Posting requires a valid handshake token** (`AUTH_REQUIRED` otherwise; listening stays anonymous).
`author` is the signed-in user's name. Delivery is room-scoped: a message with `room` goes to sockets that
joined `announce:<room>` plus the sender; a message without `room` goes to every connected socket
(auto-joined to the global room `chat:global`). Per-socket throttle: 5 messages / 5 s, excess is dropped
with `RATE_LIMITED`. Optional `clientId` (string, ≤64 chars) makes resends idempotent per user.

Ack success: `{ "ok": true, "message": { ... } }`; a resend of a known `clientId` acks
`{ "ok": true, "message": { ... }, "duplicate": true }` and is NOT re-broadcast.
Ack failures: `AUTH_REQUIRED`, `RATE_LIMITED`, `BAD_CHAT_MESSAGE` (empty / >500 chars / bad clientId),
`CHAT_PERSIST_FAILED` (DB write failed — not broadcast). Rejections other than persist failures are also
emitted as `chat:error` `{ code, message }`.

Assistant auto-replies after ~500 ms with `bot: true` (also persisted).

Socket.IO CORS uses the same `CORS_ORIGINS` allowlist as Express (reflect-origin in dev, none in production when unset).
Handshake auth: `auth: { token: "<jwt>" }` optional for listening, required for posting. Invalid token → `connect_error` `AUTH_INVALID_TOKEN`.
