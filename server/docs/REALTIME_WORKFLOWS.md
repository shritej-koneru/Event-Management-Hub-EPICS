# Real-time Workflows (Socket.IO)

## Connection

- URL: same host/port as REST; Engine.IO path `/socket.io`.
- Namespaces: `/` (Android BitChat) and `/announce` (contract §6). Both share
  the same handlers via `registerNamespace`.
- Auth: optional `auth: { token }`. Valid token → `socket.data.userId`. Missing
  token → anonymous. Invalid token → `connect_error` with `AUTH_INVALID_TOKEN`
  (never a silent downgrade).

## Room join / leave

- Client emits `join` with **one string per room** or an array of bare venue ids
  (`"room-204"`, not `"announce:room-204"` — both are accepted; prefix is stripped).
- Server normalizes to a graph venue id; unknown ids are ignored (not created).
- Room name on the server: `announce:<venueId>`.
- Ack: `{ ok: true, joined: ["announce:room-204", ...] }` plus a `joined` emit.

Android joins **every** seeded venue so map zoom does not affect delivery.

## Announcement delivery (`event`)

When an event is created or updated (`POST /api/events` or update path):

1. `recordAnnouncement` inserts into `announcements` with `ON CONFLICT (room, message) DO NOTHING`.
2. Only if a row was created, the server emits `event` to room `announce:<venueId>`
   on **both** namespaces.
3. Payload includes flattened `title`, `venue`, `venueId`, `eventId` for the Android UI.

REST fallback: `GET /api/chat/announcements[?eventId=]`.

## Interactive chat (`chat:message`)

**Write path (server is the single writer):**

1. Validate `text` (required, ≤ 500 chars).
2. Resolve author from JWT user or `"Guest"`.
3. Optional `room` must be a real graph venue id or null.
4. **INSERT into `chat_messages`** — if this fails, ack `CHAT_PERSIST_FAILED`
   and **do not broadcast**.
5. Broadcast `chat:message` to the whole namespace (sender included).
6. Ack `{ ok: true, message }` with the stored row (`id` is the DB identity).
7. After ~500 ms, persist and emit an assistant auto-reply (`bot: true`) to the sender.

**History path:** `GET /api/chat/messages[?room=&before=&limit=]` returns a
chronological page for reconnect / cold start.

## Frontend responsibilities (Android)

- `ChatViewModel` is **activity-scoped** so the thread and socket survive tab switches.
- On first `start()`: load REST history, then connect socket.
- On every reconnect: reload history (merge only — never wipe).
- Dedupe by message `id` so history + live echo appear once.
- Messages acked as ours render as “You”.

## Reconnection & errors

| Situation | Behaviour |
| --- | --- |
| Socket drops | UI badge → Offline; ViewModel keeps last thread |
| Reconnect | Re-join rooms; `loadHistory()` merges missed rows |
| History HTTP fails | Existing list kept; no empty replacement |
| Chat send while offline | No-op; draft preserved; log warning |
| Invalid token on connect | `connect_error`; user must re-login |
| DB write fails on send | Nack `CHAT_PERSIST_FAILED`; nothing broadcast |

## What is intentionally not real-time

- Event list / search / recommendations: plain REST.
- Bookmarks: REST only (server is source of truth).
