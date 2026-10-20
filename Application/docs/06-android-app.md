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

## 6.5 AI layer

A small on-device assistant demonstrates cross-team integration:
[`AiServiceClient`](../app/src/main/java/com/epics/bitchat/ai/AiServiceClient.java)
talks to the backend `ai-service` (emulator base URL
`http://10.0.2.2:8000`), [`RecommendationEngine`](../meshlib/src/main/java/com/epics/bitchat/ai/RecommendationEngine.java)
ranks events against the user's position (the venue graph supplies the
"near me" half — chapter [5](05-positioning-navigation.md)), and
[`DemoEventCatalog`](../meshlib/src/main/java/com/epics/bitchat/ai/DemoEventCatalog.java)
keeps the demo self-sufficient offline.

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
