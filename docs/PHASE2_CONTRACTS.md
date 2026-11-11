# Phase 2 - Shared contracts (DESIGN ONLY, not implemented)

Scope: ai-service is OUT. Registry covers building AND campus venues. Facts below come from reading
`merged/` (integration @ 9cf1bc4) and `offline backend` @ 7daad04 on 2026-11-09. Items marked
**[OPEN]** need a human decision; nothing here invents a mapping without saying so.

## 0. Decisions (approved as a proposal, 2026-11-09) and blockers

| # | Decision | Effect on this design |
|---|---|---|
| D1 | Cafeteria (offline) and `cafeteria` (platform) are NOT treated as the same place; Library likewise. Separate records until the campus map is verified. | No venue is merged. No portal exists. |
| D2 | No other venue names are merged. Cross-scope routing is DISABLED unless a portal is confirmed. | Routing is per-scope only (section 2). |
| D3 | `parentId` stays unset (null) until verified campus-to-building pairs are provided. | Column may exist; no value is written. |
| D4 | The 30 s floor-change penalty affects route CHOICE in both engines; must be documented and tested. | See section 2 "Floor penalty". |
| D5 | A signed-in phone may upload relayed mesh messages, with an opt-out and clear attribution. | See section 4. |
| D6 | `V:<name>` BLE adverts unchanged; short codes deferred. | `code` column not populated in v1. |
| D7 | The mesh `clientId` format is NOT frozen. Phase 3 first measures source-id derivation and collision risk. | Section 4 format is a placeholder. |

### Blockers (unresolved, must be cleared before the named work starts)

| ID | Blocker | Blocks | Needed from you |
|---|---|---|---|
| B1 | Cafeteria / `cafeteria` identity unverified | any portal, any cross-scope route | verified campus map check |
| B2 | Library / `library` identity unverified | same | same |
| B3 | Main Gate vs `entrance`, Hall 1/2 vs `main-hall`/`auditorium` unmapped (guesses rejected) | any further portal | verified pairs, or "no counterpart" |
| B4 | No verified campus-to-building pairs | `parentId` values; "building inside campus venue" UX | verified pairs |
| B5 | Mesh srcId derivation and wire-msgId collision rate unmeasured | freezing `clientId` format; sync endpoint's unique index | Phase 3 measurement (no input needed) |

While B1-B4 are open: a route request whose endpoints are in different scopes returns `NO_PATH` with
reason `CROSS_SCOPE_UNSUPPORTED`; the UI shows "no verified connection between campus and building maps".
Recommendations, events and chat are unaffected because they key on venue id, not on routes.

## 1. Facts found (what exists today)

| Area | Platform (server + Compose app) | Offline (meshlib + Views app) |
|---|---|---|
| Venue identity | 14 kebab ids (`main-hall`, `room-204`...) + display names, rows in `venues` table, **runtime-extensible by managers** (`/manage`), `kind`, `description`, lat/lng, `map_x/map_y` 0..1 | 10 display names used AS node ids (`"Hall 2"`), x/y in 0..100, floor 0/1, beacon flag, 3 transition nodes (`stairwell_1`, `elevator_1`, `ramp_1`) |
| Where venue appears | `events.venue_id` FK, `announcements.room` FK, `chat_messages.room` FK, socket rooms `announce:<id>` | node ids; BLE venue advert payload `V:<venue name>` (e.g. `V:Lecture Block A` = 17 chars > 14-char frame, so it fragments); `DemoEventCatalog` events; PositionEstimator beacons |
| Graph | undirected, weights = metres, no floors, no accessibility flag, Dijkstra, in-memory registry rebuilt from DB | undirected, weights = rounded Euclid on 0..100 grid, floors, `accessible` flag (stairs=false), Dijkstra + A*, 30 s/floor-change penalty **only in ETA, not path choice** (O-7) |
| Map coords | fraction of 4:3 canvas, projected to campus lat/lng bounds (VR Siddhartha) | abstract grid, no lat/lng |
| Chat | Socket.IO, `chat_messages(id bigint, author, user_id, room->venues, text<=500, at)`, `client_id` + UNIQUE(user_id, client_id) idempotency (migration 006) | flood mesh, wire frame 27 B = 13 B header + 14-char payload, fragmentation up to 36 frames (~full text), msg id on wire = 3 bytes, src = 3 chars; `MessageLog` in memory only |
| Recommendations | server keyword scoring over DB events; app `OfflineEngine` mirror | `RecommendationEngine` + `DemoEventCatalog` (8 demo events, own ids `evt-001..008`) |
| Persistence | server Postgres; app `OfflineStore` JSON file + `Outbox` (bookmarks, interests, support msgs use clientId) | SharedPreferences: settings + venue-advert cache |
| Auth | JWT, roles (student/organizer/admin) | none; mesh identity = device id |

Corrections to the older audit: server chat/support already have `clientId` idempotency (the audit's I-S8
is partly done) and the app already has an outbox for bookmarks/interests (I-A1 done). **Still absent:**
`updatedAt`/`?since=`, a batch endpoint, and any notion of mesh-originated messages.

Real conflicts: (1) two venue vocabularies and two coordinate systems; (2) the Mesh advert carries venue
**names**, so renaming a venue changes wire text; (3) mesh wire ids are not globally unique, so they cannot
be the server's idempotency key by themselves; (4) path-cost semantics differ (floor penalty).
Only `Cafeteria` and `Library` are the same word in both vocabularies; whether they are the same *place*
is unresolved (blockers B1-B2, section 0).

## 2. Canonical venue registry (proposal)

One registry, owned by the server `venues` table (already exists, already extensible). Android and
meshlib get a *generated, versioned snapshot* (JSON) instead of hard-coded lists.

```
Venue {
  id        string  stable, immutable, kebab-case slug     // PK. Never reused. Never renamed.
  name      string  display name (editable, not a key)
  scope     "campus" | "building"
  parentId  id|null building venue -> the campus venue that contains it   // nullable, never written until B4 clears
  floor     int     default 0
  x,y       double  0..1 canvas (existing map_x/map_y) -- single coordinate system
  lat,lng   double? existing, derived from x/y when null
  kind, description                                        // existing 008 columns
  aliases   string[]  legacy ids/names that resolve to this venue (see 3)
  beacon    bool      has a BLE beacon (meshlib positioning)
  code      string?   <=4 chars, optional short wire code for BLE adverts
  kindOfNode "venue" | "transition"   // stairs/elevator/ramp are nodes, not selectable venues
}
Edge { from, to, distanceM, accessible(bool, default true), floorChange(bool, derived) }
Registry { version(int, monotonic), venues[], edges[] }
```

Design choices (recommended):
- **Slug ids are the key; names are never keys.** Fixes the "names are the #1 contract" fragility.
- **Two graph layers in one registry**, distinguished by `scope`. Routing runs inside a layer only.
  A cross-scope route needs a confirmed **portal venue**; **none is confirmed (D1/D2, B1-B4)**, so
  cross-scope routing is disabled (`NO_PATH` / `CROSS_SCOPE_UNSUPPORTED`). A portal is added later by
  an explicit registry edge flagged `portal=true`, never inferred from matching names.
- **Edges gain `accessible`** (default true) so the server can mirror the step-free model; the server's
  existing edges stay accessible.
- **Floors**: add `floor` on venues (building rows default 0).
- **Floor penalty (D4).** Both engines charge 30 s per floor change as part of the edge cost used to
  pick the path, not only in the ETA. Cost model: `cost(edge) = distanceM / 1.4 m/s + 30 s * floorChange`
  (the walking speed constant must be read from `Route.WALK_SPEED_MPS` and mirrored, not re-guessed).
  Impact: Dijkstra/A* in meshlib switch from metres to seconds as the weight; A* heuristic must stay
  admissible (Euclid distance / speed, penalty omitted). Server graph has no floors yet, so server output is
  unchanged until floored venues exist; the server gets the same cost function so behaviour cannot diverge.
  Required tests (written in the implementation phase, listed here as acceptance criteria):
  (a) a route where a longer ground path beats a shorter path with a floor change; (b) the reverse when
  the detour exceeds 30 s; (c) step-free routing still excludes stairs; (d) identical results from meshlib
  and server for the same fixture graph; (e) ETA still equals the sum of path costs.
  Documentation: update `Route`/`Dijkstra` Javadoc, server `README`, and API contract note.
  Risk: existing device-tuned routes may change; call it out in release notes.
- **Resolution rule** (`resolve(input)`): exact id, then exact alias, then exact display name; case-
  sensitive, no fuzzy guessing; unknown -> error. Same rule server and device.
- Registry `version` is sent in `/ping`/`/navigation/map`; clients cache the snapshot and refuse to mix
  versions with the server silently (mismatch => refetch, offline => use cached).
- Wire: keep `V:<name>` adverts working during migration; optionally add `V:<code>` (<=4 chars -> single
  frame) later. Do NOT touch hop/RSSI constants (contract 4) or the scan filter (contract 6).

### 2a. Mapping table (nothing here is final)

| Offline name | Platform id | Basis | Status |
|---|---|---|---|
| Cafeteria | (none) | identical word only | **NOT MERGED (D1, B1)**: separate campus row; platform `cafeteria` stays a building row |
| Library | (none) | identical word only | **NOT MERGED (D1, B2)**: same treatment |
| Main Gate | (none) | semantic guess rejected | **NOT MERGED (D2, B3)** |
| Hall 1 / Hall 2 | (none) | semantic guess rejected | **NOT MERGED (D2, B3)** |
| Lecture Block A/B, Admin Block, Parking, Sports Complex | none | no counterpart | would be NEW registry rows, `scope=campus` |
| `room-101..205`, `lab-a/b`, `lobby`, `registration`, `tech-lounge` | none on campus side | no counterpart | `scope=building` rows; `parentId` null (D3, B4) |
| `stairwell_1`, `elevator_1`, `ramp_1` | none | helper nodes | `kindOfNode=transition`, hidden from pickers |

Decision (D1/D2): NO venues are merged. Every venue is carried over as its own row. Because the campus
Cafeteria/Library and the building `cafeteria`/`library` would collide on display name, the registry's
`name` uniqueness is scoped per `scope` (UNIQUE(scope, name)), and every UI list that mixes scopes shows
the scope next to the name. The current `venues.name UNIQUE` constraint is replaced by that composite one
in migration 010. Campus ids need distinct slugs, proposed `campus-cafeteria`, `campus-library`,
`campus-main-gate`, ... (**slugs to be confirmed when the registry is implemented**).
`parentId` is added as a nullable column but never written (D3, B4).

### 2b. Migration shape (for Phase 3+, additive only)
Migration `010_registry.sql`: add `scope`, `parent_id`, `floor`, `beacon`, `code`, `kind_of_node`,
`aliases` (table `venue_aliases(alias PK, venue_id FK)`), `venue_edges.accessible`, and a
`registry_version` row. Existing 14 rows get `scope='building'`. Existing FKs (events, announcements,
chat) are untouched, so existing data is preserved. Seed adds the 10 campus rows as new ids.

## 3. Routing / recommendations / chat / persistence / API: ownership

| Concern | Owner (one implementation) | Others become |
|---|---|---|
| Venue registry | server `venues` table | generated snapshot for app + meshlib |
| Online routing | server (`POST /navigation/path`) + app `OfflineEngine` fallback | unchanged |
| Offline routing / positioning | `:meshlib` (Dijkstra/A*, PositionEstimator) fed from the registry snapshot | `DemoGraphFactory` becomes the **fallback only** until snapshot loading is proven |
| Recommendations | server scoring online; `OfflineEngine` offline | meshlib `RecommendationEngine`/`DemoEventCatalog` deprecated, kept until parity tests pass (demo events `evt-00x` are NOT server events) |
| Chat | server for online; mesh for offline | linked by sync (section 4) |
| Local persistence | app `OfflineStore` + `Outbox` (extend it) | mesh prefs stay inside the mesh module |
| Auth | server JWT | mesh stays anonymous; sync authenticates at the gateway |

API additions (all additive, contract 1.7.0): registry fields on `GET /navigation/map` and
`/navigation/venues`; `GET /navigation/registry?since=<version>` (full snapshot, 304 if unchanged).

## 4. Offline-to-online sync design

Only mesh **chat** is synced in the first version. Positioning and routing stay on-device.
Bookmarks/interests already have an outbox; support messages already use clientId.

**Identity (NOT FROZEN, D7/B5).** Requirement only: two gateways uploading the same heard message must
derive the SAME `clientId`, and distinct messages must not collide. The mesh wire id (3 bytes) + src
(3 chars) may not be unique enough. The shape below is a **placeholder for discussion, not a contract**:
`m1:<srcDeviceId>:<msgIdHex>:<createdAtEpochSec>` (within the existing 64-char limit).
Phase 3 must first measure: (1) how the 3-char srcId is derived per install and whether two installs can
share one; (2) wire msgId collision probability within a 24 h window and under fragmentation (each
fragment has its own frame id); (3) whether reassembled text can be hashed instead (e.g. content hash +
src + coarse time bucket) as a collision-resistant alternative. The `/api/chat/sync` unique index and
migration 011 are NOT written until that result is reviewed and the format approved.

**Phase 3 measurements (B5; tool + raw output in `docs/measurements/`; clientId STILL NOT FROZEN).**
- Device id: 3 chars x 32-char alphabet = 32,768 values, SecureRandom, persisted, regenerated on
  app-data clear/reinstall. P(any two installs share an id): 10 -> 0.14%, 50 -> 3.7%, 100 -> 14%,
  200 -> 46%, 500 -> 98%. So `srcId` alone is not a unique author key at classroom/campus scale.
- Wire message id: the first 3 chars of a UUID string = hex only = 4,096 values (12 bits), not 24 bits.
  2,000,000 generated ids produced exactly 4,096 distinct values. The sender keeps the full 36-char
  UUID locally; every receiver sees only the 3-char prefix.
- `decode()` stamps the LOCAL receive time as `createdAt`; the original time is not on the wire.
  Two gateways hearing the same message 1.5 s apart got different seconds, so the Phase 2 placeholder
  `m1:<src>:<msgId>:<createdAtSec>` does NOT agree across gateways and fails requirement 1. Do not use it.
- Fragment frames each carry their own random msgId (9 frames -> 9 ids). Only the 3-char fragment group
  id (32,768 values, FNV-1a over src|text|epochMs) is shared by the frames of one message.
- Consequence for the options still to be weighed: any id built from (src, 3-hex msgId) is ambiguous;
  anything using receive time needs a coarse bucket (boundary straddling) or must be dropped; a hash of
  the reassembled text + src + coarse time bucket is the candidate that satisfies "same id from every
  gateway", at the cost of treating an intentional repeat of identical text in one bucket as one message.
  Not decided here.
- Separate pre-existing risk found while measuring: see "Router dedup" in `MERGE_PROGRESS.md`.

**Who uploads (D5).** Any phone with connectivity and a signed-in user ("gateway") may upload.
Messages it heard from others are uploaded as `origin: "mesh"` with `relayedBy = gateway user id` and
`meshSrc` kept as plain text. They are never presented as the gateway user's own authorship; the UI
labels them "Relayed from mesh (device <meshSrc>) via <gateway name>".
Opt-out: Settings toggle "Upload mesh messages I relay", default ON (per your decision), stored on the
device, applied before queuing (an opted-out phone queues nothing for mesh-heard messages; its OWN
messages still sync). Mesh messages are never uploaded unauthenticated. First-use notice states that
overheard nearby messages may be sent to the server. Server rows record `relayed_by` so a user can be
asked to remove their relays and an admin can purge by gateway.

**Server contract (additive).**
`POST /api/chat/sync` body `{ messages: [{ clientId, room (venue id|null), text, createdAt, origin: "app"|"mesh", meshSrc? }] }`
(max 50). Per item result `{ clientId, status: "created"|"duplicate"|"rejected", serverId?, error? }`.
Idempotent via a new `UNIQUE(client_id)` for `origin='mesh'` rows (the existing `(user_id, client_id)` index
only works per user). Columns added by migration `011`: `origin`, `mesh_src`, `client_created_at`.
`at` stays server receive time; the display time uses `client_created_at` clamped to [now-7d, now+5m]
so a wrong phone clock cannot reorder history. Unknown venue id -> that item `rejected`, others go through.
`GET /api/chat/messages?since=<serverId>` for catch-up (needed anyway; currently absent).

**Client.** Extend `Outbox` with a `chat` op list: persisted before send, ordered per room, retry with
exponential backoff (5 s .. 5 min, jitter), ack removes item, `duplicate` counts as success,
`rejected` is dropped to a visible "failed" list (not silently deleted). 401 pauses the queue
(existing behaviour). Dedup on receive: server echo of an item already shown (same clientId) replaces it.

**Conflicts.** Chat is append-only, so there are no merges, only duplicates and ordering.
Events/bookmarks keep server-wins + outbox replay as today. No offline event creation.

## 5. Risks
- Registry migration touches every FK-bearing table's meaning but not their data (high attention, low risk if additive).
- Changing advert payload from names to codes breaks old phones; keep both for a release.
- Floor-penalty-in-path change alters existing routes; needs mirrored tests on both sides.
- Mesh chat on a server that has `chat_messages.text <= 500`; mesh max ~36x frame text, check at build.

## 6. Status of earlier questions
All six answered; see section 0 (D1-D6). Open items are the blockers B1-B5 only. Nothing in this
document is implemented. Implementation phases must not start work that depends on a blocker:
- Phase 3 (import meshlib, measure id derivation): allowed; resolves B5.
- Phase 4 chat/positioning adapter: allowed. Routing adapter limited to per-scope routing.
- Phase 5 sync: blocked until B5 is resolved and the `clientId` format is approved.
- Registry migration 010: allowed for the additive columns and per-scope name uniqueness; no merged
  rows, no `parentId` values, no portal edges.
