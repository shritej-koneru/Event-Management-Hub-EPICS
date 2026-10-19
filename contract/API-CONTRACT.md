# Shared API Contract — Event Management Hub

> **Owner: Person 2 (Backend).** Codified Day 1. Frontend (Person 1) builds any UI/map
> against these shapes and names. If a field changes here, update this file FIRST,
> then bump `contractVersion` in the `/ping` response.

- **Contract version: `1.2.0`** (`/ping` -> `contractVersion`; previously `1.1.0`).
  - **1.2.0 adds, and breaks nothing:** `GET /navigation/map` and
    `GET /navigation/venues/:venueId/map` (campus canvas layout + stored graph
    edges, both additive).
  - **1.1.0 added, and broke nothing:** `GET /events/:id/details`,
    `GET /chat/announcements?eventId=`, and an optional `eventId` on announcements.
  - Every pre-existing endpoint kept its exact response shape, so no client has to
    change. `GET /events/:id` still returns `{ event }` and nothing else.
- Base URL (backend): `http://10.0.2.2:3000/api` from Android emulator (`10.0.2.2` = host loopback).
- Base URL (real device): `http://<your-LAN-IP>:3000/api`
- All JSON.

**Auth model.** An endpoint is either **public** (no credential needed) or
**authenticated** (valid JWT required; missing/invalid -> `401`). A JWT is always
*accepted* when present on any endpoint and used for personalisation when
relevant; it is never required merely to read data.

| Public | Authenticated |
| --- | --- |
| `GET /ping`, `GET /navigation/venues`, `GET /navigation/map`, `GET /navigation/venues/:venueId/map`, `GET /navigation/path`, `GET /events`, `GET /events/:id`, `GET /events/:id/route`, `GET /events/:id/details`, `GET /chat/announcements`, `POST /auth/login`, `POST /auth/register` | `POST /events`, `GET /auth/me`, `POST /auth/me/bookmarks/:eventId`, `DELETE /auth/me/bookmarks/:eventId` |

> **Correction from 1.0.0.** The old note read "all auth endpoints except
> `/ping`, `/auth/login`, `/auth/register`, `GET /events`, `GET /events/:id`,
> `GET /navigation/venues` are optional-auth". That was ambiguous and implied
> writes like `POST /events` needed no credential. `POST /events` mutates shared
> data and has required a JWT since the authentication work. The table above is
> authoritative.

---

## 1. Canonical venue names (navigation graph nodes)

Routing is keyed EXACTLY on these strings. DO NOT introduce new spellings (spaces, casing,
accents) in event data or API responses. **This is the #1 integration risk.**

```
entrance        Entrance
lobby           Lobby
registration    Registration Desk
auditorium      Auditorium
main-hall       Main Hall
room-101        Room 101
room-102        Room 102
room-204        Room 204
room-205        Room 205
lab-a           Lab A
lab-b           Lab B
cafeteria       Cafeteria
library         Library
tech-lounge     Tech Lounge
```

Every `Event.venue` MUST equal one of the display values above (e.g. `"Room 204"`, `"Auditorium"`).

---

## 2. Event object — canonical shape

```json
{
  "id": "evt_001",
  "title": "Android Dev Workshop",
  "category": "Tech",
  "venue": "Room 204",
  "venueId": "room-204",
  "date": "2026-10-15",
  "time": "10:00",
  "durationMin": 90,
  "capacity": 50,
  "registered": 32,
  "host": "Dr. Mehra",
  "description": "Hands-on Jetpack Compose session.",
  "tags": ["android", "compose", "workshop"],
  "icon": "🎫"
}
```

### Register body / response

```json
{
  "id": "evt_001"
}
```

### `GET /events` response

```json
{ "events": [ { ...event... }, ... ] }
```

Unpaginated by default. Add `?page=1&pageSize=20` to receive a `pagination` block.
`pageSize` defaults to 20 and is capped at 100. `pagination` is additive: the `events`
key is always present and always a full array of Event objects.

```json
{
  "events": [ { ...event... }, ... ],
  "pagination": { "page": 1, "pageSize": 20, "total": 42, "totalPages": 3, "hasMore": true }
}
```

Supported filters (all case-insensitive, combinable):

| Query param | Matches against |
| --- | --- |
| `category` | `Event.category` |
| `venue` | canonical id (`room-204`) **or** display name (`Room 204`) |
| `date` | `Event.date`, exactly `YYYY-MM-DD` |
| `q` | substring of title, description, host, category, venue name or any tag |

Unsupported query parameters are rejected with `400 VALIDATION_ERROR` rather than
being silently ignored.

### `GET /events/:id` response

```json
{ "event": { ...event... } }
```

### `GET /events/:id/route`

Wayfinding to the venue the event is held at. `from` accepts a node id or a display
name and defaults to `entrance`. Returns the same `nodes` / `steps` /
`totalDistanceM` shape as `POST /navigation/path`.

```json
{
  "eventId": "evt_001",
  "venueId": "room-204",
  "venue": "Room 204",
  "nodes": [ { "id": "entrance", "name": "Entrance" }, ... ],
  "steps": [ ... ],
  "totalDistanceM": 42
}
```

### `GET /events/:id/details` (1.1.0)

One call that fills an entire Event Details screen: the event, its venue, real
statistics, chart-ready series, **that event's** announcements, and the route to
the venue.

`?from=<node>` behaves exactly as on `GET /events/:id/route` and defaults to
`entrance`. Accepts a JWT when supplied (public endpoint) — with one, the
`isBookmarked` flag reflects the caller.

```json
{
  "event": { ...event... },
  "venue": {
    "id": "room-204",
    "name": "Room 204",
    "navigationNode": "room-204",
    "neighbors": ["room-102", "room-205"]
  },
  "statistics": {
    "capacity": 40,
    "registered": 12,
    "seatsRemaining": 28,
    "occupancyPercent": 30,
    "durationMin": 120,
    "announcementCount": 2,
    "bookmarkCount": 1,
    "isBookmarked": true,
    "categoryEventCount": 4,
    "totalEvents": 12,
    "isPast": false,
    "daysUntilEvent": 10
  },
  "charts": [
    {
      "id": "occupancy",
      "title": "Capacity",
      "type": "donut",
      "unit": "people",
      "total": 40,
      "data": [ { "label": "Registered", "value": 12 },
                { "label": "Available", "value": 28 } ]
    },
    { "id": "categoryMix", "type": "bar",  "unit": "events", "...": "..." },
    { "id": "announcementTrend", "type": "line", "unit": "announcements", "...": "..." }
  ],
  "announcements": [ { "id": 3, "room": "room-204", "message": "...", "eventId": "evt_001", "at": "..." } ],
  "navigation": {
    "from": "entrance",
    "nodes": [ { "id": "entrance", "name": "Entrance" }, ... ],
    "steps": [ ... ],
    "totalDistanceM": 42
  }
}
```

**What the numbers mean — nothing here is synthesised.**

| Field | Source |
| --- | --- |
| `capacity`, `registered`, `durationMin` | the event row's own columns |
| `seatsRemaining`, `occupancyPercent` | arithmetic on `capacity` / `registered` |
| `announcementCount` | `COUNT` of announcements with this `eventId` |
| `bookmarkCount` | `COUNT` of users who bookmarked this event |
| `categoryEventCount`, `totalEvents` | `COUNT` over `events` |
| `isBookmarked` | the caller's own bookmark row; always `false` when anonymous |
| `isPast`, `daysUntilEvent` | derived from `event.date` only |

> **`registered` is an event-level aggregate stored on the server. It is NOT
> evidence that any particular person registered** — registration lives on the
> Android device and the server deliberately has no registrations table.
> `bookmarkCount` is likewise an aggregate and is never presented as "you saved
> this"; that is `isBookmarked`.

**Charts may be absent.** `charts` is a list, not a fixed set, and an empty one is
valid. A series is omitted rather than padded with invented points:

| Chart | `type` | Included when |
| --- | --- | --- |
| `occupancy` | `donut` | `capacity > 0` (a zero-capacity donut would divide by zero client-side) |
| `categoryMix` | `bar` | `totalEvents > 0` |
| `announcementTrend` | `line` | at least **2** announcements on distinct days; a single point is not a trend |

Each chart's `data` sums to its own `total`. Data points are `{ label, value }`.

**Errors.** `404 EVENT_NOT_FOUND` for an unknown event; `400 UNKNOWN_VENUE` for a
`from` that is not a graph node; `400 VALIDATION_ERROR` for an empty `from`.

### `POST /events`

**Requires a JWT** (`Authorization: Bearer <jwt>`). This is the only mutating event
endpoint, and it is not in the optional-auth exemption list in §1.

```json
// 201 response
{ "event": { ...event... } }
```

---

## 3. Auth

### `POST /auth/register`
```json
// request
{ "name": "Alice", "email": "alice@uni.edu", "password": "secret123" }
// 201 response
{ "token": "<jwt>", "user": { "id": "usr_001", "name": "Alice", "email": "alice@uni.edu" } }
```

### `POST /auth/login`
```json
// request
{ "email": "alice@uni.edu", "password": "secret123" }
// 200 response
{ "token": "<jwt>", "user": { "id": "usr_001", "name": "Alice", "email": "alice@uni.edu" } }
```

Send as `Authorization: Bearer <jwt>` on authenticated calls.

### Bookmarks (requires JWT)

Bookmarks are the server-side personalization signal. The Android app tracks
*registrations* locally in `OfflineStore`, but bookmarks are persisted server-side so
they survive a reinstall and can drive recommendations.

| Method | Path | Response |
| --- | --- | --- |
| GET | `/auth/me/bookmarks` | `{ "bookmarks": ["evt_001", ...] }` |
| POST | `/auth/me/bookmarks/:eventId` | `{ "bookmarked": true, "eventId": "...", "bookmarks": [...] }` |
| DELETE | `/auth/me/bookmarks/:eventId` | `{ "bookmarked": false, "eventId": "...", "bookmarks": [...] }` |

Both `POST` and `DELETE` return **200 and are idempotent** — retrying a bookmark or
un-bookmark never fails. A `:eventId` that does not exist returns `404
EVENT_NOT_FOUND`. `GET /auth/me` continues to include the user's `bookmarks`.

### Rate limiting

`POST /auth/register` and `POST /auth/login` are rate limited per IP. Exceeding the
budget returns:

```json
{ "error": "Too many authentication attempts. Try again later.", "code": "RATE_LIMITED" }
```

with HTTP `429` and a `Retry-After` header. Successful requests do not count against
the budget, so a legitimate user is never locked out by logging in repeatedly.

---

## 4. AI — `/ai`

### `POST /ai/search` (user-aware)
```json
// request  (Bearer token optional; when present results are re-ranked by user interests/history)
{ "query": "android workshop", "limit": 10 }
// response
{
  "query": "android workshop",
  "results": [
    { "event": { ...event... }, "score": 0.66, "reason": "Matches your interest in: Tech" }
  ]
}
```

**`results` may be empty.** An event is only eligible when it has real textual evidence
for the query (title, tags, category, description, host, or venue text), or — for a
signed-in user — a concrete personalization signal. Popularity is a tie-breaker only and
never admits an event on its own, so a query that matches nothing returns
`{ "query": "...", "results": [] }` rather than the whole catalogue.

`score` is in `[0,1]` and uses a saturating curve, so strong matches remain
distinguishable and never all pin to `1`.

### `GET /ai/recommendations` (user-aware, requires JWT)
```json
// response
{
  "recommendations": [
    { "event": { ...event... }, "score": 0.34, "reason": "You bookmarked this event" }
  ]
}
```

Personalization signals used (when JWT present): user `interests`, `bookmarks` and
recent `search_history`. Popularity is a small tie-breaker, so brand new and
low-registration events that match an interest are still recommended.

`reason` only ever describes data the server actually holds. It **never** claims the
user registered for an event, because registrations are stored on-device in
`OfflineStore` and the server has no registration history. Events with no
personalization signal are returned with `reason: "Suggested for you"`.

---

## 5. Navigation — `/navigation`

### `GET /navigation/venues`
```json
{ "venues": [ { "id": "room-204", "name": "Room 204", "neighbors": ["lab-a", "main-hall"] } ] }
```

### `GET /navigation/map` (1.2.0)
Whole-campus canvas layout served from the database (`venues.map_x/map_y`,
`venue_edges`): every venue with a fractional position (0..1, x right, y
down — the same convention Compose Canvas uses) plus the undirected walk
graph. The Android map screen draws this instead of a hard-coded layout, so
moving a venue is a data change, not a client release. Public (no auth).
```json
{
  "venues": [
    { "id": "entrance", "name": "Entrance", "x": 0.15, "y": 0.88, "neighbors": ["lobby"] },
    { "id": "room-204", "name": "Room 204", "x": 0.72, "y": 0.62, "neighbors": ["room-205", "lab-a", "tech-lounge"] }
  ],
  "edges": [
    { "from": "entrance", "to": "lobby", "distanceM": 40 },
    { "from": "room-204", "to": "room-205", "distanceM": 8 }
  ]
}
```
Every venue from `GET /navigation/venues` appears exactly once; every edge
appears once (`from < to`) with the same distance the path API uses.

### `GET /navigation/venues/:venueId/map` (1.2.0)
One venue node with its positioned neighbours and edge distances.
`:venueId` accepts a graph node id ("room-204") or a canonical display
name ("Room%20204", URL-encoded). Anything unknown — including a near-miss
like "room 204" — is `400 UNKNOWN_VENUE`; nothing is guessed. Public (no auth).
```json
{
  "venue": { "id": "room-204", "name": "Room 204", "x": 0.72, "y": 0.62 },
  "neighbors": [
    { "id": "room-205", "name": "Room 205", "x": 0.80, "y": 0.48, "distanceM": 8 }
  ]
}
// 400 response
{ "error": "Unknown venue: dormitory. Use a navigation graph node id or canonical display name.", "code": "UNKNOWN_VENUE" }
```

### `POST /navigation/path`
```json
// request
{ "from": "entrance", "to": "room-204" }
// 200 response — CONTRACT GUARANTEED (any two venue ids resolve to a path)
{
  "nodes": [
    { "id": "entrance", "name": "Entrance" },
    { "id": "lobby", "name": "Lobby" },
    { "id": "room-204", "name": "Room 204" }
  ],
  "steps": [
    { "node": "entrance", "instruction": "Start at Entrance", "distanceM": 0 },
    { "node": "lobby", "instruction": "Go straight ahead to Lobby", "distanceM": 40 },
    { "node": "room-204", "instruction": "Turn right, arrive at Room 204", "distanceM": 70 }
  ],
  "totalDistanceM": 70
}
// 400 response
{ "error": "Unknown venue: foo" }
```

---

## 6. Socket.io announcements (BitChat)

`GET /chat/announcements` (REST backup, returns last 20):
```json
{ "announcements": [ { "id": 1, "room": "main-hall", "message": "...", "at": "2026-09-23T10:00:00.000Z" } ] }
```

Socket.io namespace `/announce` (auth via `socket.auth.token` JWT):
- Server broadcasts an `Event` + `announcement` object to **room `announce:<venueId>`** when a new event is created
  or an event is updated, so the BitChat map UI can live-sync event bubbles onto venues:
    ```json
    {
      "event": { "id": "evt_002", "venue": "Auditorium", "venueId": "auditorium", ... },
      "announcement": { "title": "New event at Auditorium", "venueId": "auditorium", "eventId": "evt_002" },
      "title": "New event at Auditorium",
      "venue": "Auditorium",
      "venueId": "auditorium",
      "eventId": "evt_002"
    }
    ```
  - Client joins rooms: `socket.emit("join", ["auditorium", "room-204"])`
  - `rooms` is read as `Array.isArray(rooms) ? rooms : [rooms]`, so a single bare room
    name is also valid and is acknowledged the same way. Clients whose emit signature is
    varargs (such as the Java/Kotlin client) must send one room name per call rather than
    a list, since a spread list would put the first room in `rooms` and the second in `ack`.

**Namespaces.** Both `/` (default) and `/announce` are served. The Android client uses
the default namespace, so nothing else changes for it.

**Authentication.** The Android client connects anonymously, so anonymous listeners are
supported. A token may still be supplied via handshake `auth.token`:

| Handshake | Result |
| --- | --- |
| no token | connected as anonymous |
| valid token | connected, `socket.data.userId` set |
| invalid / expired token | rejected with `connect_error`, `data.code = "AUTH_INVALID_TOKEN"` |

An invalid token is rejected rather than silently downgraded to anonymous, so a client
holding a stale token learns to re-authenticate.

**Acknowledgements.** Handlers that reply do so through the acknowledgement callback:
`join`/`leave` ack `{ ok: true, joined: [...] }` / `{ ok: true, left: [...] }`, and
`events:for` acks `{ ok: true, events: [...] }` or `{ ok: false, error: { code, message } }`.

**Interactive chat (`chat:message`).** The feed is not read-only: any connected client can
send a message and the server broadcasts it back to **every** client in the namespace
(sender included), so all UIs render the same thread. Messages are **persisted** to
`chat_messages` before the broadcast; a failed write is never broadcast
(`CHAT_PERSIST_FAILED` ack).

  - Client → server: `socket.emit("chat:message", { text }, ack)` — `room` is optional and,
    when present, must be a bare venue id (e.g. `"room-204"`); it is informational, the
    broadcast still reaches everyone.
  - Server → clients (all, on the same namespace): `chat:message` payload
    ```json
    { "id": 42, "author": "Alice Wang", "text": "hi everyone", "room": null, "bot": false, "ts": "2026-10-07T18:30:00.000Z" }
    ```
    `id` is the database identity (JSON number). Clients should treat it as an opaque
    dedupe key (stringify if needed).
  - `author` is the identity from the handshake: a valid `auth.token` resolves to the user's
    name, otherwise `"Guest"`.
  - ack: `{ ok: true, message: { ... } }`, or `{ ok: false, error: { code: "BAD_CHAT_MESSAGE", message } }`
    when `text` is missing/blank or longer than 500 characters, or
    `{ code: "CHAT_PERSIST_FAILED", message }` when the database write failed.

### `GET /chat/messages`

Persisted chat history for reconnect / cold start (**additive**; existing clients
that never call this endpoint are unaffected).

| Query | Meaning |
| --- | --- |
| *(none)* | latest messages across every room, oldest-first |
| `room=<venueId>` | only that bare venue id (`announce:` prefix accepted) |
| `before=<ISO-8601>` | exclusive cursor: rows with `ts` strictly older than `before` |
| `limit=1..100` | page size (default 50) |

```json
{ "messages": [ { "id": 41, "author": "Guest", "text": "hello", "room": null, "bot": false, "ts": "..." } ] }
```

| Case | Result |
| --- | --- |
| empty conversation | `200` with `{ "messages": [] }` |
| unknown `room` | `400` `UNKNOWN_VENUE` |
| malformed `before` / extra params | `400` `VALIDATION_ERROR` |
| auth | optional (matches anonymous chat) |

**Stored messages** embed the event id (`New event at Room 204 [evt_001]`) so two
different events at the same venue are both persisted, while `UNIQUE (room, message)`
still makes relaying the same event twice a no-op.

Since 1.1.0 the event id is *also* stored as a real column
(`announcements.event_id`, `NULL` for venue-level notices), which is what makes
the event-scoped read below possible. For databases created before 1.1.0 the
column is added by migration and filled in on the next server boot; clients see
no difference.

### `GET /chat/announcements`

REST fallback for BitChat. Rows are de-duplicated by `UNIQUE (room, message)`.

```json
{ "announcements": [ { "id": 3, "room": "room-204", "message": "...", "at": "..." } ] }
```

**`?eventId=<id>` (1.1.0)** restricts the feed to announcements recorded for
**that event**:

```json
{ "announcements": [ { "id": 3, "room": "room-204", "message": "...", "eventId": "evt_001", "at": "..." } ],
  "eventId": "evt_001" }
```

This matters because two events can be held at the same venue — `evt_002` (Music
Night) and `evt_006` (Drama) are both at `auditorium`. Without `eventId` one
event's screen would show the other's messages.

| Case | Result |
| --- | --- |
| `eventId` omitted | venue-wide feed, exactly as before (no `eventId` key in the response) |
| `eventId` = event with no announcements | `200` with `{ "announcements": [] }` — an empty list, not an error |
| `eventId` = unknown event | `404` `EVENT_NOT_FOUND` — a typo must not look like "no announcements" |
| `eventId` malformed | `400` `VALIDATION_ERROR` |
| `GET /events/:id/details` | same event-scoped list, embedded as `announcements` |

`eventId` is **absent** from an announcement object when the message is
venue-level rather than event-specific, so clients should treat it as optional.

---

## 7. Error shape (all endpoints)

```json
{ "error": "human readable message", "code": "EVENT_NOT_FOUND" }
```

`error` is always a human-readable **string**; `code` is its sibling, never nested.
These are the codes the server actually returns:

| Code | Status | Meaning |
| --- | --- | --- |
| `VALIDATION_ERROR` | 400 | request body / query / params failed schema validation |
| `UNKNOWN_VENUE` | 400 | venue or `from` node is not a navigation graph node |
| `NO_PATH` | 404 | no route exists between two nodes |
| `AUTH_REQUIRED` | 401 | credential required but absent (e.g. anonymous `POST /events`) |
| `INVALID_CREDENTIALS` | 401 | login email/password did not match |
| `AUTH_INVALID_TOKEN` | 401 | token was supplied but is invalid or expired (also used for socket `connect_error`) |
| `EMAIL_TAKEN` | 409 | registration email already exists |
| `DUPLICATE` | 409 | write collided with an existing row |
| `EVENT_NOT_FOUND` | 404 | event id does not exist (`/events/:id`, `/:id/details`, `/chat/announcements?eventId=`) |
| `NOT_FOUND` | 404 | no route matches the request |
| `RATE_LIMITED` | 429 | too many requests (`Retry-After` header set) |
| `INTERNAL` | 500 | unexpected server fault; details are logged, never returned |

> There is no `BAD_CREDENTIALS` code — it is `INVALID_CREDENTIALS`. There is no
> `FORBIDDEN` (403) response: authorisation failures surface as `AUTH_REQUIRED`
> or `AUTH_INVALID_TOKEN` with 401.