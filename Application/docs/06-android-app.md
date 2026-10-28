# 6 · Android App

> Previous: [Positioning & navigation](05-positioning-navigation.md) · Next: [Testing & verification](07-testing.md)

The `:app` module wraps the engine in a phone-ready shell: real BLE radio,
permissions, persisted state, five-tab UI, and a test-hub for live demos.

## 6.1 MeshManager

[`MeshManager`](../app/src/main/java/com/epics/bitchat/mesh/MeshManager.java)
is the app's engine owner:

- builds the node id from the device identity (6-char sanitized, chapter [3.3](03-protocol.md#33-ids-tokens-acks)),
- picks the transport at startup: **BLE** when Bluetooth is on and mesh
  permissions are granted, otherwise the engine's [`SimulatedTransport`](../meshlib/src/main/java/com/epics/bitchat/mesh/SimulatedTransport.java)
  (a "Bluetooth turned on; switching from simulator to BLE" broadcast
  receiver upgrades mid-session),
- persists state across restarts (venue directory cache, known devices,
  settings) and restores cached venue adverts for offline navigation,
- forwards engine callbacks into [`MeshViewModel`](../app/src/main/java/com/epics/bitchat/ui/MeshViewModel.java)
  LiveData.

**Key functions** (full listing in [MeshManager.java](../app/src/main/java/com/epics/bitchat/mesh/MeshManager.java)):

| Group | Functions |
|-------|-----------|
| Lifecycle | `get(Context)` (singleton), `start()` / `start(forcedMode)` (transport choice, cache restore, engine start), `restart()` / `restartWithMode(mode)`, `stop()`, `isRunning()` |
| Identity & naming | `getDeviceId()` (stable 3-char wire id from `generateDeviceId()`), `getNodeName()` / `resolveNodeName()` (hardware model unless renamed in Settings; legacy "Phone A"-style defaults are migrated), `defaultNodeName()`, `isLegacyDefaultName(name)` |
| Transport choice | `getDefaultMode()` (BLE when a radio is on, else the simulator), `isBluetoothEnabled()`, `hasBlePermissions()` (includes location on API ≤ 30), `blePermissionHint()`, `registerBtStateReceiver()` — the `STATE_ON` receiver upgrades simulator → BLE via `restartWithMode` |
| Engine access | `getMeshNode()`, `getGraph()`, `getReachableNodeIds()`, `getNodeCount()`, `getLossPct()`, `getMode()`, `addListener` / `removeListener` |
| Peers & addressing | `displayNameFor(nodeId)`, `idsFromPeers(peers)`, `loadKnownDevices()` / `persistKnownDevices()` / `getKnownDevices()` (newest first, kept while out of range), `isReachable(nodeId)` |
| Settings | `saveSettings(nodeName, nodeCount, loss, mode)`, `getServerBaseUrl()` / `saveServerBaseUrl(url)` (ai-service base; `""` = server features off), `hasNetwork()` (gates server calls so an offline demo never waits on the 4 s connect timeout), `isRelayEnabled` / `setRelayEnabled`, `isAutoAnnounceEnabled` / `setAutoAnnounceEnabled` |
| Venue persistence | `getVenueDirectory()`, `setVenueAdvertised(venue)` (manual announce pinned as the auto-announce override), `persistVenueDirectory(force)` / `writeVenueDirectoryToPrefs()` / `restoreCachedVenueDirectory()` — seeds a fresh node with cached adverts at full TTL (2 min per entry, refreshed by the 15 s advert cycle) |
| Internet backhaul | `ingestFromInternet(externalId, payload)` (floods a server-originated message with a dedupable id; `""` clears the venue), `wireMsgIdForExternal(externalId)`, `hasSeenExternal(externalId)` (dedup check) |

It also implements [`MeshEventListener`](../meshlib/src/main/java/com/epics/bitchat/mesh/MeshEventListener.java)
itself — `onLogEntry`, `onPeerDiscovered`, `onPeerLost`, `onMessageDelivered`,
`onDeliveryStatus`, `onPositionUpdated`, `onVenueDirectoryChanged` — which is
the bridge that turns engine events into the ViewModel's LiveData.

## 6.2 BLE transport

[`BleAdvertiserTransport`](../app/src/main/java/com/epics/bitchat/mesh/BleAdvertiserTransport.java)
implements [`MeshTransport`](../meshlib/src/main/java/com/epics/bitchat/mesh/MeshTransport.java)
with raw advertisement packets — no GATT connection, no pairing:

- **Advertise**: identity advert (1 Hz) plus the current message packet in
  the manufacturer-data record; unfiltered advertising because UUID-based
  filters hide manufacturer data on some stacks.
- **Scan**: every callback feeds the interpreter chain (chapter [3](03-protocol.md)):
  identity → peer table, message → `MeshNode.onPacketReceived`; own adverts
  come back as `SELF` and are ignored.
- **Permissions posture**: never requests silently; the UI drives the
  prompts (Bluetooth/scan + location on API ≤ 30, nearby-devices on 31+).
- **Recovery**: `attemptRecovery()` tears down and rebuilds the scanner and
  advertiser objects — the equivalent of an app restart without the restart.

## 6.3 ScanWatchdog (MIUI fix)

Some OEM stacks (observed on MIUI) **silently stop delivering scan results**
while reporting the scan as active — the mesh goes deaf until the app is
restarted. [`ScanWatchdog`](../meshlib/src/main/java/com/epics/bitchat/mesh/ScanWatchdog.java)
is a pure state machine (unit-tested in the engine) that detects both failure
signatures and escalates:

| Signal | Meaning | After |
|--------|---------|-------|
| `onScanCallback` | any scan result arrived | — |
| `onMeshPacket` | a result carried *our* protocol | — |
| **raw stall** | no callback of any kind | `RAW_STALL_MS = 60 000` |
| **mesh stall** | callbacks flow but no mesh packets *while we had mesh traffic within 600 s* | `MESH_STALL_MS = 45 000` |

`evaluate(now)` returns `NONE / REARM_SCAN / FULL_RECOVERY`: a re-arm
restarts scanning (20 s probe window, `maxConsecutiveRearms = 2` before
escalation); **FULL_RECOVERY** rebuilds the radio objects via
`attemptRecovery()` — the app-restart cure, automated. The transport feeds
the watchdog from both the periodic ticks and every scan callback, and
resets escalation counters when traffic is healthy.

## 6.4 UI layer

Five tabs ([`MainActivity`](../app/src/main/java/com/epics/bitchat/MainActivity.java)
+ `bottom_navigation`), all driven by [`MeshViewModel`](../app/src/main/java/com/epics/bitchat/ui/MeshViewModel.java)
LiveData:

| Tab / Fragment | Shows |
|----------------|-------|
| [`ConsoleFragment`](../app/src/main/java/com/epics/bitchat/ui/ConsoleFragment.java) | live traffic rows ([`LogAdapter`](../app/src/main/java/com/epics/bitchat/ui/LogAdapter.java), TX blue / RX green / drops grey), position line, quick-send chips, FAB send dialog |
| [`PeersFragment`](../app/src/main/java/com/epics/bitchat/ui/PeersFragment.java) | reachable devices ([`PeerAdapter`](../app/src/main/java/com/epics/bitchat/ui/PeerAdapter.java): BEACON vs NODE, RSSI) |
| [`SendFragment`](../app/src/main/java/com/epics/bitchat/ui/SendFragment.java) | destination spinner + text; keyboard Send action; status line — *"Waiting for delivery…"* → *"Delivered to NAME (ID)"* / *"… is out of range"* / *"Too long — the mesh carries at most 324 characters"* |
| [`NavigationFragment`](../app/src/main/java/com/epics/bitchat/ui/NavigationFragment.java) | map ([`GraphView`](../app/src/main/java/com/epics/bitchat/ui/GraphView.java)), heading ([`CompassView`](../app/src/main/java/com/epics/bitchat/ui/CompassView.java)), step list ([`StepAdapter`](../app/src/main/java/com/epics/bitchat/ui/StepAdapter.java)) |
| [`SettingsFragment`](../app/src/main/java/com/epics/bitchat/ui/SettingsFragment.java) | display name, relay on/off, auto-announce, venue pin, transport override |

### MeshViewModel — the single source of truth

Every tab observes its LiveData; the ViewModel owns the engine callbacks and
republishes **immutable snapshots** (`MeshLogEntry`, `Peer`, `TrailPoint`):

| Area | LiveData / functions |
|------|----------------------|
| Traffic | `getLogs(): LiveData<List<MeshLogEntry>>` |
| Peers | `getPeers(): LiveData<List<Peer>>` |
| Position | `getPosition()`, `getPositionSource()` ("beacon" / "mesh" / "none"), `pushPositionSource(node)` |
| Server position | `refreshServerPosition()` — POSTs the current venue-advert anchors to `POST /position` (the server's weighted-centroid twin of `PositionEstimator`, chapter [5.5](05-positioning-navigation.md#55-estimating-the-position)) — plus `getServerPosition()`, `appendServerTrailSample(x, y)` (near-identical fixes collapse into one dot, list capped oldest-first), `getServerTrail()`, `clearServerTrail()` |
| Venues | `getVenueDirectory(): LiveData<List<VenueDirectory.Entry>>`, `refreshVenues()` (constructor + cache restore — `onVenueDirectoryChanged` only fires for *new* adverts, hence the explicit re-push) |
| Sending | `send(dstId, kind, payload)`, `getReachableIds()`, `getKnownDestinations()` (in-range targets first), `getLastDestinationId()` / `setLastDestinationId(id)` (Send tab) |
| Status & toggles | `getEventMessage()`, `getDeliveryStatus()`, `isRelayEnabled` / `setRelayEnabled`, `isAutoAnnounceEnabled` / `setAutoAnnounceEnabled`, `setAdvertisedVenue(venue)` |
| Misc | `displayNameFor(nodeId)`, `getGraph()`, `getMeshManager()`, `onCleared()` |

Fragment-level logic worth naming: [`NavigationFragment`](../app/src/main/java/com/epics/bitchat/ui/NavigationFragment.java)
`computeRoute()` runs Dijkstra or A\* for the picked start/destination (the
accessible toggle flows through — chapter [5.6](05-positioning-navigation.md#56-routes)),
`advanceStep()` walks the step list as the user moves, and `refreshCompass()`
+ `onSensorChanged` drive the heading;
[`GraphView`](../app/src/main/java/com/epics/bitchat/ui/GraphView.java) is fed
by `setGraph` / `setRoute` / `setCurrentNode` / `setCurrentStepIndex` /
`setServerTrail` / `setPositionSource`. Console's quick-send chips call
`ConsoleFragment.quickSend(...)`; `SettingsFragment.saveSettings(...)` writes
through `MeshManager.saveSettings(...)`, so name, node count, loss model and
transport override survive restarts.

## 6.5 AI layer

A small on-device assistant demonstrates cross-team integration:
[`AiServiceClient`](../app/src/main/java/com/epics/bitchat/ai/AiServiceClient.java)
talks to the backend `ai-service` (emulator base URL
`http://10.0.2.2:8000`), [`RecommendationEngine`](../meshlib/src/main/java/com/epics/bitchat/ai/RecommendationEngine.java)
ranks events against the user's position (the venue graph supplies the
"near me" half — chapter [5](05-positioning-navigation.md)), and
[`DemoEventCatalog`](../meshlib/src/main/java/com/epics/bitchat/ai/DemoEventCatalog.java)
keeps the demo self-sufficient offline.

All calls are blocking `HttpURLConnection` on a background thread (callbacks
arrive there; the caller hops to main). `normalizeBaseUrl(raw)` accepts
`10.0.2.2:8000`, `http://10.0.2.2:8000` or empty (= server features off), and
`hasNetwork()` gates every call (chapter [6.1](#61-meshmanager)):

| Client function | Endpoint | Notes |
|-----------------|----------|-------|
| `getRoute(baseUrl, from, to, accessible, …)` | `GET /route` | `parsePath(...)` renders the JSON id array `[Hall 1, elevator_1, Library]` for display and map lookup |
| `getRecommendations(baseUrl, interests, limit, …)` | `GET /recommend` | interests as a comma-separated list |
| `postPosition(baseUrl, anchors, floor, …)` | `POST /position` | each anchor is `{"beacon": "Hall 1", "rssi": -60}` (measured beacon) or `{"beacon": "Hall 1", "hops": 2}` (mesh venue advert) — the server's weighted-centroid twin of `PositionEstimator` |
| `askAssistant(baseUrl, question, accessible, …)` | `POST /assistant` | grounded FAQ + nav answers — the server twin of [`Assistant`](08-reference.md) (chapter [8](08-reference.md)) |
| `get(url, onSuccess, onError)` / `postJson(...)` | — | raw HTTP primitives (`read`, `enc`, `trim` helpers) |

The Python service itself (`ai-service/app/main.py`, FastAPI) additionally
serves `GET /`, `GET /graph`, `GET /graph/nodes` and `GET /nearest`. Every
endpoint runs the same Dijkstra/A\* and recommendation math as the on-device
mirrors (chapter [8](08-reference.md#ai--recommendation--assistant)), so server
and phone answers match.

## 6.6 Test hub

Dedicated live-demo activities (listed in [`TestHubActivity`](../app/src/main/java/com/epics/bitchat/ui/TestHubActivity.java)):
[`PositioningTestActivity`](../app/src/main/java/com/epics/bitchat/ui/test/PositioningTestActivity.java)
(live RSSI table → estimate), [`RoutingTestActivity`](../app/src/main/java/com/epics/bitchat/ui/test/RoutingTestActivity.java)
(Dijkstra/A*, accessible toggle, server-route mode),
[`HopTestActivity`](../app/src/main/java/com/epics/bitchat/ui/test/HopTestActivity.java)
(hop/relay stats) and [`BlePairTestActivity`](../app/src/main/java/com/epics/bitchat/ui/test/BlePairTestActivity.java) —
the same activities the emulator matrix drives (chapter [7.3](07-testing.md#73-emulator-matrix));
a scripted walk-through for demos lives in [DEMO_SCRIPT.md](DEMO_SCRIPT.md).

## 6.7 Build notes

- `applicationId com.epics.bitchat`; debug APK ≈ 6.1 MB.
- Build: `./gradlew :app:assembleDebug` with a JDK 17/21 toolchain (the
  Android Studio JBR; a bare system JDK 25 fails R8).
- [AndroidManifest](../app/src/main/AndroidManifest.xml) declares BLE
  advertise/scan, nearby-devices, and foreground service for background
  meshing; runtime permissions are requested by the UI flow.

---
Previous: [Positioning & navigation](05-positioning-navigation.md) · Next: [Testing & verification](07-testing.md)
