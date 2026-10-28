# PLAN.md — BitChat Mesh: BLE Mesh + Navigation (Java Android)

## 1. Goal

Build a Java Android app that implements a lightweight **BitChat-style BLE mesh**
(short messages flooded over BLE advertisements, peer-to-peer, no server) and
shows a **live traffic console** — every message sent/received with sender,
receiver, payload, hops, RSSI — plus **indoor navigation** (graph map +
turn-by-turn steps + compass direction arrow) using beacon-based positioning
and an in-app Dijkstra/A* solver.

## 2. Architecture & repo layout

```
Event-Management-Hub-EPICS/
└── android/BitChatMesh/
    ├── PLAN.md
    ├── README.md
    ├── settings.gradle
    ├── build.gradle            (root)
    ├── gradle.properties
    ├── gradle/wrapper/…
    ├── local.properties         (sdk.dir → %LOCALAPPDATA%\Android\Sdk)   [gitignored]
    ├── .gitignore
    ├── meshlib/                 ← pure Java engine, no UI, reusable in hybrid APK
    │   └── src/main/java/com/epics/bitchat/mesh/…
    │   └── src/test/java/…      ← JUnit unit tests
    └── app/                     ← console/demo UI (single activity)
        └── src/main/java/com/epics/bitchat/…
        └── src/main/res/…
```

- **`:meshlib`** = the reusable mesh + positioning + routing engine. Later
  plugged into the Capacitor hybrid APK as a native plugin (merge option 2).
- **`:app`** = dev/demo interface with the 5 tabs.
- Build: **JDK 21** (bundled `Android Studio Quil 2/android-studio/jbr`),
  **minSdk 26**, **targetSdk 34**, Java 17 source/target, Gradle wrapper 8.13.

## 3. Phase 1 — Scaffolding

- Root `build.gradle` + `settings.gradle` (includes `:meshlib`, `:app`),
  `gradle.properties` (`android.useAndroidX=true`).
- `:meshlib` = Java library module. `:app` = Android application module.
- `AndroidManifest.xml`: `BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`,
  `BLUETOOTH_CONNECT`, `ACCESS_FINE_LOCATION`, `FOREGROUND_SERVICE`,
  `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `uses-feature android.hardware.bluetooth_le`.
- Deps: `androidx.appcompat`, `com.google.android.material:material`,
  `androidx.recyclerview`, `androidx.lifecycle:lifecycle-viewmodel`, `junit` (test).
- Wrapper generated via the cached Gradle 8.13 distribution; `local.properties`
  points at the local SDK.

## 4. Phase 2 — Mesh domain (`:meshlib`, pure Java)

Classes:
- `Peer` — `nodeId`, `displayName`, `deviceAddress`, `lastSeenEpochMs`, `rssi`, `isBeacon`.
- `MeshMessage` — `msgId` (UUID), `srcId`, `dstId`, `kind` (`CHAT`, `PING`, `ACK`,
  `BEACON`), `payload`, `ttl`, `hops`, `seq`.
- `MeshLogEntry` — `timestamp`, `direction` (`TX`/`RX`), `from`, `to`, `payload`,
  `kind`, `hops`, `rssi`, `relayBy`, `status` (`IN_FLIGHT`/`DELIVERED`/`DROPPED_TTL`/`DROPPED_DUP`).
  ← the "from whom → to whom" record.
- `MeshNetwork` — `Map<nodeId, Peer>` neighbor table, RSSI smoothing,
  add/update/expire peers.
- `MessageLog` — thread-safe ring buffer of `MeshLogEntry`, listener callbacks to UI.
- `MeshNode` — the local mesh stack: `start()`, `stop()`, `send(...)`, owns
  transport + router, exposes `MeshEventListener` (`onLogEntry`,
  `onPeerDiscovered`, `onPeerLost`, `onRssiUpdate`).

## 5. Phase 3 — Transports & routing

- `ITransport` interface: `send(MeshMessage)`, `start()`, `stop()`; callback
  `onPacketReceived(addr, payload, rssi)`.
- `SimulatedTransport` — runs N `SimulatedMeshNode`s in-process (each on its own
  thread/queue). Configurable: node count, packet loss %, latency ms, topology
  (ring/star/mesh). Auto-generates demo traffic so the console is alive.
  Simulates RSSI from inter-node distance.
- `Router` — flood-relay: on RX, if `dstId == self` → deliver to app; else if
  `ttl > 0` → decrement TTL, increment hops, re-broadcast; drop if msgId seen
  (dedup set) or TTL expired. Emits `MeshLogEntry` per hop (RX + relay).
- JUnit tests: TTL decrement & expiry drop, dedup prevents loops, relay fan-out
  reaches multi-hop dst, packet loss tolerance.

## 6. Phase 3b — Positioning (`:meshlib`)

- `RssiFilter` — moving average + simple Kalman smoothing.
- `BeaconParser` — decode beacon advertisement carrying **nodeId** (+ floor).
- `PositionEstimator` — from strongest beacons' nodeIds + smoothed RSSI →
  estimated graph node (nearest/weighted); emits `onPositionUpdated(nodeId)`.

## 7. Phase 3c — Navigation (`:meshlib`, in-app Java solver)

- `Graph`, `GraphNode` (id, name, x, y, floor, tags e.g. accessible),
  `GraphEdge` (from, to, weightMeters, accessible).
- `Dijkstra` — exact shortest path (priority queue).
- `AStar` — heuristic (Euclidean) shortest path, faster on large graphs.
- `Route` = ordered `List<GraphNode>` + `RouteStep` list (`text`,
  `distanceMeters`, `bearingDeg`, `floorChange`).
- Sample campus graph built in `DemoGraphFactory` (venue names matching Person 2's
  event schema — the #1 cross-team contract). Floor-1 venues (Hall 2, Library)
  are reachable only through explicit transition nodes (stairwell/elevator/ramp)
  so multi-floor routing and rendering are honest; stairs edges are flagged
  `accessible=false`, and `Dijkstra/AStar.findShortestPath(..., accessible=true)`
  guarantee step-free routes. `Route.getTotalSeconds()` adds a 30 s penalty per
  floor change. Mirrors `ai-service/app/graph.py` server-side.
- JUnit: known-graph shortest-path assertions for both solvers, equivalence
  check Dijkstra vs A*, pairwise connectivity (standard + accessible).

## 8. Phase 4 — UI (`:app`, single activity + `ViewModel`)

Material `BottomNavigationView` + 5 fragments:
- **Console** — `RecyclerView` of `MeshLogEntry`, TX blue / RX green, relay hops
  grey; shows `#id "payload" from → to · n hops · -XX dBm · status`.
  **Floating ＋ Send button** (opens Send dialog) + quick-send chips
  (`hi`, `meet at Hall 2`, `ping`).
- **Peers** — discovered nodes/beacons, RSSI, last seen, beacon badge.
- **Send** — destination picker (from Peers + fixed IDs in simulator),
  compose box, **Send button**.
- **Navigation** — custom `GraphView` (Canvas) drawing nodes as dots + edges as
  lines, your position dot, highlighted route; **step list** `RecyclerView` with
  current-step highlighting; **compass direction arrow** (overlay, uses
  `SensorManager` `TYPE_ROTATION_VECTOR`, bearing to next waypoint).
  Simulator-safe orientation.
- **Settings** — node name, simulator node count, packet loss %, transport
  toggle (Simulator / BLE), reset log. Defaults to BLE on phones with the
  radio enabled. **Tests** button opens the full-screen Test Hub activity
  (`TestHubActivity`): one row per test screen, each labelled SIM/REAL and
  with the device count its real test needs (1 device / 2 phones / 3 phones
  / server). Every test screen has a Simulated test button that passes on a
  single device; Real tests use live BLE phones or the ai-service.
- `MeshTransportService` (foreground, `specialUse` type) keeps the mesh stack
  alive while navigating tabs.
- **Offline cache** — `AiCacheStore` (SharedPreferences JSON) stores the event
  catalog and the last successful /recommend response; a failed real call
  serves the cached ranking (`fromCache` flagged). `MeshManager` persists the
  venue directory on every change and restores it (full fresh TTL) at mesh
  start, so mesh venue navigation keeps working offline after a restart.

## 9. Phase 5 — Verification (emulator)

- `./gradlew :meshlib:test` and `:app:assembleDebug` via bundled JDK.
- Install on emulator (`platform-tools/adb`): run app in **Simulator mode** →
  verify live TX/RX console, Send button, peer list, multi-hop route rendering,
  Dijkstra/A* route + steps + arrow.

## 10. Phase 6 — Real BLE transport (device-to-device)

Status: **implemented** — ready for the three-phone hop test. Install the same
`app-debug.apk` on 3 physical phones (or Android Studio → Run on each).

How it works:
- `BleAdvertiserTransport`: `BluetoothLeAdvertiser` (manufacturer-specific
  data, id `0xFFFF`) + `BluetoothLeScanner`. The scan is **unfiltered** — the
  adverts carry manufacturer data, which a UUID-based scan filter could never
  match — and everything that is not a `0xFFFF` manufacturer-data record is
  dropped in `onScanResult`. So **only devices running this app are ever
  discovered**; beacon-type adverts from indoor nodes are ignored.
- Every device continuously advertises a **unique wire id + display name**
  identity packet (`BeaconParser`). The wire id is a random 6-char id
  persisted per install (`MeshManager.getDeviceId()`), independent of the
  display name, so two phones both named "Phone A" still route separately.
- Outbound messages are advertised with ~16 retransmits over ~2.4 s
  (reliability against scan gaps), then the identity advert is restored.
  Received packets are relayed the same way, so flood routing hops between
  phones. TTL + per-message dedup prevent loops (`Router`). Duplicate
  re-transmissions are logged as `DROPPED_DUP` **once** per message id.
- Transport is **BLE by default** when the device has an enabled Bluetooth
  radio (`MeshManager.getDefaultMode()`); otherwise it falls back to the
  simulator. All radio calls are guarded so a missing runtime permission
  cannot crash the app — the console logs why BLE is unavailable, and
  `MainActivity` restarts the mesh once permissions are granted. The mesh
  also restarts automatically when Bluetooth is toggled (`MeshManager`
  adapter-state receiver).

Users as nodes (relay participation + hop stats):
- Every phone is a flood-relay hop by default; `Router.setRelayEnabled(false)`
  lets a user opt out (messages addressed to them are still delivered — only
  forwarding stops, logged as `DROPPED_NO_RELAY`). Toggle lives in Settings
  and the Tests ▸ Hop screen; persisted per install.
- `RelayStats` counts relayed / delivered / sent / dup / TTL / no-relay drops
  and the max hops seen, per node and per peer (`Router.getStats()`), and is
  surfaced live in the Hop test screen.

Mesh navigation (phones as navigation beacons):
- A phone can announce its venue (`MeshNode.announceVenue("Hall 2")`): a
  `MeshKind.BEACON` broadcast ("V:Hall 2", TTL 8) floods like any message, so
  **venue knowledge is relayed multi-hop through other users** — navigation
  data reaches devices far outside direct BLE range.
- Receivers observe adverts into `VenueDirectory` (dedup, shortest-hops,
  2 min expiry); `MeshNode` re-announces every 15 s. The Navigation tab lists
  heard venues with their hop counts; the Hop test screen shows the full
  directory. Broadcasts are consumed locally AND flooded onward (one hop
  accounting entry per listener per message).
- Positioning fuses both anchor types in `PositionEstimator` (each relay hop
  costs 7 dB of synthetic RSSI; strongest anchor wins; measured beacons beat
  synthetic adverts on ties) — see `MeshPositionTest`.
- **Auto-announce** (on by default): the 5 s tick announces the strongest
  *beacon-backed* position as the user's venue — every phone in beacon range
  becomes a navigation beacon with no manual entry. Only measured positions
  are announced (`strongestBeaconAnchor()`); mesh hearsay is never
  rebroadcast (it would hop-reset and echo), transition nodes (stairs etc.)
  are skipped, and a manual announcement pins the venue until cleared
  (`setAutoAnnounceOverride`). See `AutoAnnounceTest`.
- **Viva demo:** the full three-phone walkthrough (relay proof, venue
  announcement, beacon-less navigation end to end) is in `docs/DEMO_SCRIPT.md`.

Send to out-of-range devices (hop-to-find):
- The **Send** tab lists every *known* device (in-range first, out-of-range
  ones tagged `(out of range)`), persisted across restarts in
  `MeshManager.getKnownDevices()`. Out-of-range targets are still selectable:
  the message floods and relays until it reaches the destination or its TTL
  expires.
- The destination replies with an `ACK` (kind `ACK`, payload = the original
  message's 6-char wire token from `PacketCodec.ackToken`), which relays back
  along the same hops. The sender's `DeliveryTracker` marks the send delivered
  on the ACK, or reports `"X is out of range"` after a 12 s timeout — shown in
  a status line under the Send button.
- Loop safety: every message (including ACKs) carries a unique id; the router
  dedups per node and TTL caps the flood, and ACKs are never re-acked.

Three-phone hop test:
1. Settings → give each phone a distinct name (e.g. "Phone A/B/C"), mode BLE.
2. Position A and C **out of range** of each other, with B bridging (A↔B and
   B↔C in range). Verify on the Peers tab: A sees B, C sees B.
3. On A, Send → pick "Phone C (…C) (out of range)" → message. The message must
   be ≤6 chars.
4. Expected consoles: **B** shows `RX A→C` then `RELAYED hops=1 ttl=7`; **C**
   shows `DELIVERED hops=1`; **A** shows the status line "Delivered to Phone
   C" (after the ACK relays back). If C is powered off/out of the mesh, A
   shows "Phone C is out of range" after ~12 s.
5. Repeat in reverse (C→A). If the receiver shows `0 hops`, A and C were still
   in range — separate them further.

Two-phones demo: real messages hop phone→phone, console shows RSSI + hops.
Optional real beacons → verify positioning + navigation live.

## 11. Phase 7 — Handoff & docs

- `README.md` for the module (build/run/demo instructions), demo script for
  class, roadmap checkbox in root `README.md`.
- Later (separate task): Capacitor hybrid merge — `:meshlib` → native plugin
  under the React PWA shell; console stays as dev tool.

## 12. Risks & mitigations

- **Real BLE on emulator unreliable** → simulator first; real BLE requires
  physical phones (noted in README). Emulator smoke tests verify build/crash
  and the sim fallback path.
- **~27 B advertisement payload (≤6 chars)** → messages are intentionally
  short; longer text needs the planned GATT/rendezvous handoff (documented
  constraint, hinted in the Send UI).
- **Simultaneous scan+advertise is best-effort on Android** → outbound
  packets are retransmitted for ~2.4 s to survive scan gaps.
- **Duplicate default node names** → unique per-install wire ids
  (`device_id`), display name is separate.
- **Android 13+ permissions** → runtime requests for location + BLE
  scan/advertise; restart-on-grant in `MainActivity`; foreground service is
  `specialUse` type (no runtime permission needed to start).

## 13. Deliverables per phase

- P1–P3c: compilable `:meshlib` + passing JUnit tests.
- P4: runnable app — Console/Send/Peers/Navigation/Settings.
- P5: emulator demo (chat + nav, simulator mode).
- P6: two-phone real-BLE demo.
- P7: docs + merge-ready `:meshlib`.
