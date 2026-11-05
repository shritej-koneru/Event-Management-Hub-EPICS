# API Reference

Base URL (local): `http://localhost:3000`  
Android emulator → host: `http://10.0.2.2:3000` (or `adb reverse tcp:3000 tcp:3000` with `127.0.0.1`).

All errors: `{ "error": "<human message>", "code": "<CODE>" }` with sibling keys.

Contract version reported by `/ping`: `1.5.0`.

---

## GET /ping

Health + venue catalogue. No auth. `databaseName` is no longer returned to remote
clients: it appears only for loopback callers outside production (used by the verify scripts). `databaseName` is no longer returned to remote
clients: it appears only for loopback callers outside production (used by the verify scripts).

```bash
curl -s http://localhost:3000/ping
```

```json
{ "status": "ok", "ok": true, "service": "emh-server", "contractVersion": "1.5.0",
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

No auth → all graph nodes with neighbours, plus (1.5.0, additive) `lat`, `lng`, `kind`, `description`, `geoPlaced` per venue.

## GET /api/navigation/map (1.2.0)

No auth → whole-campus canvas layout read from the seeded database
(`venues.map_x/map_y`, `venue_edges`): fractional positions (0..1) plus the
undirected walk graph. This is what the Android map draws — the client no
longer hard-codes venue coordinates.

```json
{
  "bounds": { "north": 16.487077, "south": 16.483477, "west": 80.69028, "east": 80.69528,
              "center": { "lat": 16.485277, "lng": 80.69278 } },
  "venues": [
    { "id": "entrance", "name": "Entrance", "x": 0.15, "y": 0.88, "neighbors": ["lobby"],
      "lat": 16.483909, "lng": 80.69103, "kind": "", "description": "", "geoPlaced": false }
  ],
  "edges": [ { "from": "entrance", "to": "lobby", "distanceM": 40 } ]
}
```
`bounds` (1.5.0, additive) is the campus rectangle the canvas maps onto
(VR Siddhartha Engineering College, about 533 m x 400 m):
`x = (lng - west) / (east - west)`, `y = (north - lat) / (north - south)`. Per venue, `lat`/`lng` are
always present (stored when a manager placed it on the real map, otherwise derived from `x`/`y`),
`geoPlaced` says which, and `kind`/`description` are free text (empty by default). The same four
extra venue fields are added to `GET /navigation/venues`.

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

## Event-manager API · `/api/manage/*` (1.4.0, 1.5.0)

Console API for organizers and admins. **Every route needs a JWT and role `organizer` or
`admin`** (`401` without a token, `403 FORBIDDEN` for students). Errors keep the usual
`{ error, code, details? }` shape. Admins see everything; an organizer sees/edits only what
they created (`events.created_by`, `announcements.sent_by`). The campus map is shared:
any organizer or admin may edit it (no ownership).

| Method + path | Body / query | Success | Errors |
| --- | --- | --- | --- |
| `GET /api/manage/overview` | - | `200 { events:{total,upcoming,past}, bookmarks, announcements, venues, edges }` (organizer: own events/announcements; admin: whole system) | - |
| `GET /api/manage/events?scope=mine\|all` | `scope=all` admin only | `200 { events:[ <Event> + createdBy, bookmarkCount ] }` date asc | `403` (organizer + `all`), `400` bad scope |
| `POST /api/manage/venues` | Either `{ id, name, x, y, kind?, description? }` or `{ id, name, lat, lng, kind?, description?, connectToNearest? }` (never both pairs) | `201 { id, name, x, y, lat, lng, kind, description, geoPlaced, edge }` - `edge` is `{ from, to, distanceM }` when `connectToNearest` linked it, else `null` | `400 VALIDATION_ERROR`, `400 OUTSIDE_CAMPUS`, `409 DUPLICATE_VENUE` (id or name; name compared case-insensitively) |
| `PUT /api/manage/venues/:id` | `{ name?, kind?, description?, x?, y? }` or `{ name?, kind?, description?, lat, lng }` (>=1 field; `id` is immutable; both pairs together = `400`) | `200 { id, name, x, y, lat, lng, kind, description, geoPlaced }` | `404 UNKNOWN_VENUE`, `409 DUPLICATE_VENUE`, `400 VALIDATION_ERROR`, `400 OUTSIDE_CAMPUS` |
| `DELETE /api/manage/venues/:id` | - | `200 { deleted:true, id }` (edges cascade) | `404 UNKNOWN_VENUE`, `409 VENUE_IN_USE` |
| `POST /api/manage/edges` | `{ from, to, distanceM }` | `201 { from, to, distanceM }` (stored with `from < to`) | `400 UNKNOWN_VENUE`, `400 VALIDATION_ERROR`, `409 DUPLICATE_EDGE` |
| `PUT /api/manage/edges` | `{ from, to, distanceM }` | `200 { from, to, distanceM }` | `404 UNKNOWN_EDGE`, `400` |
| `DELETE /api/manage/edges?from=&to=` | - | `200 { deleted:true }` | `404 UNKNOWN_EDGE`, `400` |
| `GET /api/manage/announcements?scope=mine\|all` | `all` admin only | `200 { announcements:[ { id, room, message, at, eventId, sentBy } ] }` newest first | `403`, `400` |
| `POST /api/manage/announcements` | `{ message, venueId?, eventId? }` | `201 { announcement:{ id, room, message, at, eventId, sentBy } }` | `400`, `403` (not your event), `404 EVENT_NOT_FOUND`, `429 RATE_LIMITED` |
| `DELETE /api/manage/announcements/:id` | - | `200 { deleted:true, id }` (own, or admin) | `403`, `404 ANNOUNCEMENT_NOT_FOUND` |
| `GET /api/manage/users` | - (admin only) | `200 { users:[ { id, name, email, role, createdAt } ] }` sorted by name | `401`, `403` |
| `POST /api/manage/users` | `{ name, email, password (>=8), role: "organizer"\|"admin"\|"student" }` (admin only) | `201 { id, name, email, role, createdAt }` | `400`, `403`, `409 EMAIL_TAKEN` |
| `PUT /api/manage/users/:id` | `{ role }` (admin only) | `200 { id, name, email, role, createdAt }` | `400 CANNOT_DEMOTE_SELF` (an admin changing their own role), `403`, `404 USER_NOT_FOUND` |

Rules:
- **Venue `id`**: lowercase slug `^[a-z0-9]+(-[a-z0-9]+)*$`, max 40, immutable; `all` is reserved.
  `name` 1..60 chars, unique. `x`,`y` are JSON numbers in `[0,1]` (or send `lat`/`lng`, see below). Venue ids remain a cross-team
  contract (events, Android, mesh): creating/renaming is allowed, ids never change.
- **Edges** are undirected (`from`/`to` order does not matter), `distanceM` an integer `1..1000`,
  `from != to`, both ids must be existing venues. A venue with no edges is allowed; routing to it
  returns `400 NO_PATH`.
- **The database is the graph source of truth.** The static list in `src/navigation/venues.js`
  only seeds EMPTY tables. `/navigation/path`, `/navigation/venues`, `/navigation/map`,
  `/ping.venues`, event-venue validation, `/events/:id/route` and socket room validation all use
  the live graph, rebuilt after every write.
- **Announcement target**: `eventId` -> that event's venue room (and `event_id` stored; non-admins must
  own the event; a `venueId` that disagrees with the event is `400`); else `venueId` -> that venue's
  room; neither -> **global** (`room: "all"`, delivered to every connected client, no room join needed).
  `venueId` accepts an id or canonical display name. Responses return the *bare* room (`"room-205"`,
  `"all"`), never the `announce:` socket prefix. `message` 1..280. Manager announcements may repeat
  the same text to the same room (1.5.0: `DUPLICATE_ANNOUNCEMENT` is gone); only automatic event
  announcements still dedupe (migration 008: partial unique index on `sent_by IS NULL`).
- **Campus geo (1.5.0)**: the 4:3 canvas is a linear projection of the campus `bounds` returned by
  `GET /navigation/map`: `x = (lng - west) / (east - west)`, `y = (north - lat) / (north - south)`.
  Clients must use the served `bounds`, never their own constants. With `lat`/`lng` the server computes
  `x`/`y` and stores the pair (`geoPlaced:true`); a point outside the bounds is `400 OUTSIDE_CAMPUS`.
  `connectToNearest` (default `false`, lat/lng only) creates ONE edge to the nearest existing venue
  (haversine on each venue's effective lat/lng), `distanceM = round(metres)` clamped `1..1000`.
  `PUT` with `lat`/`lng` recomputes `x`/`y` and sets `geoPlaced:true`; `PUT` with `x`/`y` clears the stored
  lat/lng (`geoPlaced:false`; lat/lng are then derived from x/y). Seeded venues always report derived
  lat/lng with `geoPlaced:false`. `kind` is free text (<=40), `description` <=500.
- **Admin accounts (1.5.0)**: `/manage/users*` is `requireRole('admin')` - organizers and students get
  `403 FORBIDDEN`. The new account can log in at once via `POST /auth/login`.
- **Throttle**: max 10 announcements / minute / user -> `429 RATE_LIMITED`.
- Existing event CRUD (`POST /events`, `PUT|DELETE /events/:id`) is unchanged.

**Live announcement broadcast** uses the SAME socket event as event relays, `event`, with the
fields the Android `ChatViewModel` already reads (`title`, `venue`) at the top level:

```json
{
  "event": null,
  "announcement": { "id": 7, "room": "room-205", "message": "Doors open", "at": "2026-11-05T18:00:00.000Z",
                    "eventId": null, "sentBy": "usr_1", "title": "Doors open", "venueId": "room-205" },
  "title": "Doors open", "venue": "Room 205", "venueId": "room-205", "eventId": null,
  "reason": "announcement", "global": false
}
```

- Venue/event announcement -> room `announce:<venueId>` (both namespaces `/` and `/announce`);
  `event` is the full Event object when `eventId` was given.
- Global -> emitted to **all** sockets of both namespaces: `venue: "All venues"`, `venueId: null`,
  `global: true`.

**Map change signal**: after every successful venue/edge write the server emits socket event
`map:updated` with `{ "at": "<ISO timestamp>" }` to all clients of `/` and `/announce`. Clients
re-fetch `GET /navigation/map`.

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

### Event auto-announcement wording (1.4.0)

`event` socket title: `New event: <title> at <venue>` (created), `Updated: <title>`, `Cancelled: <title>`. The stored message keeps the `[<eventId>]` suffix.
