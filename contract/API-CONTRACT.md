# Shared API Contract — Event Management Hub

> **Owner: Person 2 (Backend).** Codified Day 1. Frontend (Person 1) builds any UI/map
> against these shapes and names. If a field changes here, update this file FIRST,
> then bump `contractVersion` in the `/ping` response.

- **Contract version: `1.5.0`** (`/ping` -> `contractVersion`; previously `1.4.0`).
  - **1.5.0 adds (additive for Android):** `bounds` on `GET /navigation/map` and per-venue
    `lat`, `lng`, `kind`, `description`, `geoPlaced` on `/navigation/map` and `/navigation/venues`;
    `POST`/`PUT /manage/venues` accept real `lat`/`lng` (`400 OUTSIDE_CAMPUS`, `connectToNearest`,
    response `edge`); manager announcements may repeat (the `409 DUPLICATE_ANNOUNCEMENT` path is
    removed); admin-only `GET|POST /manage/users` and `PUT /manage/users/:id`; migration 008.
    Existing fields are unchanged.
  - **1.4.0 adds (additive for Android):** the event-manager API `/manage/*` (section 6b:
    overview, managed events, venue + edge CRUD, announcements); the venue/edge graph is now
    DB-backed and editable at runtime (`GET /navigation/*`, `/ping.venues` reflect edits);
    socket event `map:updated`; manual announcements delivered through the existing `event`
    socket event (global ones reach every client); migration 007 (`announcements.sent_by`,
    nullable `announcements.room` = global). **Wording change:** event auto-announcement titles are now
    `New event: <title> at <venue>` / `Updated: <title>` / `Cancelled: <title>` (shape unchanged).
  - **1.3.0 adds (additive for Android):** `role` on the `user` object; `POST /events`
    now needs an `organizer`/`admin` role (`403 FORBIDDEN`) and rejects past dates
    (`400 PAST_DATE`) / duplicates (`409 DUPLICATE_EVENT`); `PUT` and `DELETE /events/:id`
    (creator or admin); chat posting needs a JWT, is room-scoped, throttled and accepts
    an optional `clientId`; an invalid bearer token on an optional-auth route is `401`
    instead of silently anonymous; `/ping` no longer returns `databaseName` to remote callers.
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
relevant; it is never required merely to read data. **Exception (1.3.0):** if an
`Authorization` header IS sent but the token is invalid or expired, the server answers
`401 INVALID_TOKEN` instead of treating the caller as anonymous; omit the header (or
re-authenticate) to continue. JWTs are HS256 only.

| Public | Authenticated |
| --- | --- |
| `GET /ping`, `POST /navigation/path`, `POST /ai/search` (optional auth), `GET /navigation/venues`, `GET /navigation/map`, `GET /navigation/venues/:venueId/map`, `GET /events`, `GET /events/:id`, `GET /events/:id/route`, `GET /events/:id/details`, `GET /chat/announcements`, `POST /auth/login`, `POST /auth/register` | `POST /events` (organizer/admin), `PUT /events/:id` and `DELETE /events/:id` (creator or admin), `GET /auth/me`, `POST /auth/me/bookmarks/:eventId`, `DELETE /auth/me/bookmarks/:eventId` |

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

**Requires a JWT** (`Authorization: Bearer <jwt>`) **and a `role` of `organizer` or
`admin`** (`403 FORBIDDEN` for `student`, the default role of self-registered users).
It is not in the optional-auth exemption list in §1. The creator is recorded and used for
`PUT`/`DELETE`. Errors: `400 PAST_DATE` (date before today, UTC), `409 DUPLICATE_EVENT`
(same title case-insensitive + date + venue), `400 UNKNOWN_VENUE`.

### `PUT /events/:id` and `DELETE /events/:id` (1.3.0)

JWT + `organizer`/`admin` role, and the caller must be the event's creator or an `admin`
(`403 FORBIDDEN`; `404 EVENT_NOT_FOUND`). Seeded events have no creator (admin only).
`PUT` accepts any non-empty subset of the create body and returns `200 { "event": {...} }`;
`DELETE` returns `200 { "deleted": true, "eventId": "evt_..." }`. Both broadcast `event` to the
venue room (`reason: "updated"` / `"deleted"` in the payload; a delete is not stored as an announcement).

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
{ "token": "<jwt>", "user": { "id": "usr_001", "name": "Alice", "email": "alice@uni.edu", "role": "student" } }
```

### `POST /auth/login`
```json
// request
{ "email": "alice@uni.edu", "password": "secret123" }
// 200 response
{ "token": "<jwt>", "user": { "id": "usr_001", "name": "Alice", "email": "alice@uni.edu", "role": "student" } }
```

`role` is `student` (default) | `organizer` | `admin`; it is also returned by `GET /auth/me`.

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

### `GET /ai/recommendations` (user-aware; mounted with optionalAuth, but anonymous callers get `401 AUTH_REQUIRED`)
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
{ "venues": [ { "id": "room-204", "name": "Room 204", "neighbors": ["lab-a", "main-hall"],
                "lat": 16.4851, "lng": 80.6927, "kind": "", "description": "", "geoPlaced": false } ] }
```

### `GET /navigation/map` (1.2.0)
Whole-campus canvas layout served from the database (`venues.map_x/map_y`,
`venue_edges`): every venue with a fractional position (0..1, x right, y
down — the same convention Compose Canvas uses) plus the undirected walk
graph. The Android map screen draws this instead of a hard-coded layout, so
moving a venue is a data change, not a client release. Public (no auth).
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
      "announcement": { "title": "New event: Music Night at Auditorium", "venueId": "auditorium", "eventId": "evt_002" },
      "title": "New event: Music Night at Auditorium",
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

  - **1.3.0:** posting requires a valid handshake token (`AUTH_REQUIRED` otherwise); anonymous
    sockets can still listen. Delivery is room-scoped: with `room` the message goes to sockets that
    joined `announce:<room>` plus the sender; without `room` it goes to every connected socket
    (each is auto-joined to `chat:global`). Per-socket throttle 5 msgs / 5 s; excess is dropped with
    `RATE_LIMITED`. Rejections are also emitted as `chat:error { code, message }`.
  - Client → server: `socket.emit("chat:message", { text, room?, clientId? }, ack)` — `room`, when
    present, must be a bare venue id (e.g. `"room-204"`). `clientId` (string, <=64 chars) is an
    optional idempotency key: a resend by the same user returns
    `{ ok: true, message, duplicate: true }` and is not stored or broadcast again.
  - Server → clients (all, on the same namespace): `chat:message` payload
    ```json
    { "id": 42, "author": "Alice Wang", "text": "hi everyone", "room": null, "bot": false, "ts": "2026-10-07T18:30:00.000Z" }
    ```
    `id` is the database identity (JSON number). Clients should treat it as an opaque
    dedupe key (stringify if needed).
  - `author` is the identity from the handshake token (the user's name). `"Guest"` remains only
    for legacy rows.
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

**Stored messages** embed the event id (`New event: Robotics Workshop at Room 204 [evt_001]`) so two
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

## 6b. Event-manager API — `/manage` (1.4.0, extended in 1.5.0)

Console API for organizers and admins. **Every route needs a JWT and role `organizer` or
`admin`** (`401` without a token, `403 FORBIDDEN` for students). Errors keep the usual
`{ error, code, details? }` shape. Admins see everything; an organizer sees/edits only what
they created (`events.created_by`, `announcements.sent_by`). The campus map is shared:
any organizer or admin may edit it (no ownership).

| Method + path | Body / query | Success | Errors |
| --- | --- | --- | --- |
| `GET /manage/overview` | - | `200 { events:{total,upcoming,past}, bookmarks, announcements, venues, edges }` (organizer: own events/announcements; admin: whole system) | - |
| `GET /manage/events?scope=mine\|all` | `scope=all` admin only | `200 { events:[ <Event> + createdBy, bookmarkCount ] }` date asc | `403` (organizer + `all`), `400` bad scope |
| `POST /manage/venues` | Either `{ id, name, x, y, kind?, description? }` or `{ id, name, lat, lng, kind?, description?, connectToNearest? }` (never both pairs) | `201 { id, name, x, y, lat, lng, kind, description, geoPlaced, edge }` - `edge` is `{ from, to, distanceM }` when `connectToNearest` linked it, else `null` | `400 VALIDATION_ERROR`, `400 OUTSIDE_CAMPUS`, `409 DUPLICATE_VENUE` (id or name; name compared case-insensitively) |
| `PUT /manage/venues/:id` | `{ name?, kind?, description?, x?, y? }` or `{ name?, kind?, description?, lat, lng }` (>=1 field; `id` is immutable; both pairs together = `400`) | `200 { id, name, x, y, lat, lng, kind, description, geoPlaced }` | `404 UNKNOWN_VENUE`, `409 DUPLICATE_VENUE`, `400 VALIDATION_ERROR`, `400 OUTSIDE_CAMPUS` |
| `DELETE /manage/venues/:id` | - | `200 { deleted:true, id }` (edges cascade) | `404 UNKNOWN_VENUE`, `409 VENUE_IN_USE` |
| `POST /manage/edges` | `{ from, to, distanceM }` | `201 { from, to, distanceM }` (stored with `from < to`) | `400 UNKNOWN_VENUE`, `400 VALIDATION_ERROR`, `409 DUPLICATE_EDGE` |
| `PUT /manage/edges` | `{ from, to, distanceM }` | `200 { from, to, distanceM }` | `404 UNKNOWN_EDGE`, `400` |
| `DELETE /manage/edges?from=&to=` | - | `200 { deleted:true }` | `404 UNKNOWN_EDGE`, `400` |
| `GET /manage/announcements?scope=mine\|all` | `all` admin only | `200 { announcements:[ { id, room, message, at, eventId, sentBy } ] }` newest first | `403`, `400` |
| `POST /manage/announcements` | `{ message, venueId?, eventId? }` | `201 { announcement:{ id, room, message, at, eventId, sentBy } }` | `400`, `403` (not your event), `404 EVENT_NOT_FOUND`, `429 RATE_LIMITED` |
| `DELETE /manage/announcements/:id` | - | `200 { deleted:true, id }` (own, or admin) | `403`, `404 ANNOUNCEMENT_NOT_FOUND` |
| `GET /manage/users` | - (admin only) | `200 { users:[ { id, name, email, role, createdAt } ] }` sorted by name | `401`, `403` |
| `POST /manage/users` | `{ name, email, password (>=8), role: "organizer"\|"admin"\|"student" }` (admin only) | `201 { id, name, email, role, createdAt }` | `400`, `403`, `409 EMAIL_TAKEN` |
| `PUT /manage/users/:id` | `{ role }` (admin only) | `200 { id, name, email, role, createdAt }` | `400 CANNOT_DEMOTE_SELF` (an admin changing their own role), `403`, `404 USER_NOT_FOUND` |

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