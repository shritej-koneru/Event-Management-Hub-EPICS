# API Reference

Base URL (local): `http://localhost:3000`  
Android emulator → host: `http://10.0.2.2:3000` (or `adb reverse tcp:3000 tcp:3000` with `127.0.0.1`).

All errors: `{ "error": "<human message>", "code": "<CODE>" }` with sibling keys.

Contract version reported by `/ping`: `1.1.0`.

---

## GET /ping

Health + venue catalogue. No auth.

```bash
curl -s http://localhost:3000/ping
```

```json
{ "status": "ok", "ok": true, "service": "emh-server", "contractVersion": "1.1.0",
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

Bearer required. `PUT` body: `{ "interests": ["Tech", ...] }` (max 20).

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

Bearer required. Body matches contract Event create schema (`title`, `category`,
`venue` display name, `date`, `time`, `capacity`, …). Venue must be a graph node.

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

Bearer required → ranked events from interests + bookmarks + search history.

---

## GET /api/navigation/venues

No auth → all graph nodes with neighbours.

## POST /api/navigation/path

Body: `{ "from": "Entrance", "to": "Room 204" }` → nodes, steps, totalDistanceM.

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

Body: `{ "venueId": "room-204" }` → `{ "ok": true, "room": "announce:room-204" }`

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

### chat:message

Client: `socket.emit("chat:message", { text, room? }, ack)`

Server **persists then broadcasts**. Payload:

```json
{ "id": 42, "author": "Guest", "text": "hi", "room": null, "bot": false, "ts": "..." }
```

Ack success: `{ "ok": true, "message": { ... } }`  
Ack failures: `BAD_CHAT_MESSAGE` (empty / >500 chars), `CHAT_PERSIST_FAILED` (DB write failed — not broadcast).

Assistant auto-replies after ~500 ms with `bot: true` (also persisted).

Handshake auth: `auth: { token: "<jwt>" }` optional. Invalid token → `connect_error` `AUTH_INVALID_TOKEN`.
