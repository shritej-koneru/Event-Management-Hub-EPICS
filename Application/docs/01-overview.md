# 1 · Overview & Architecture

> Previous: [Index](README.md) · Next: [Mesh engine](02-mesh-engine.md)

## 1.1 What this project is

A **BitChat-style communication and navigation system for crowded venues**
(stadiums, campuses, expos) where mobile data is congested or unavailable.
Phones talk **directly to nearby phones over Bluetooth LE advertisements** —
no cell tower, no Wi-Fi AP, no server — and every phone silently relays
traffic for every other phone, so the crowd itself becomes the network.
On top of that mesh rides an **indoor navigation layer**: beacons and
venue announcements anchor each phone onto a shared venue graph, and the
app computes accessible-friendly routes with Dijkstra/A*.

The project consists of:

| Module | Path | Role |
|--------|------|------|
| `:meshlib` | [`meshlib/`](../meshlib/src/main/java/com/epics/bitchat) | Pure-Java mesh engine: codec, flood router, fragmentation, recovery, positioning, navigation, AI recommendation engine. Android-free; 150 JUnit tests run on the JVM. |
| `:app` | [`app/`](../app/src/main/java/com/epics/bitchat) | Android shell: BLE transport implementation, permissions, ViewModels + fragments, test activities. |

## 1.2 Layer diagram

```
┌─────────────────────────── app (Android) ───────────────────────────┐
│  UI: Console · Peers · Send · Navigation · Settings  + test hub     │
│  MeshViewModel (LiveData)      AI assistant (demo catalog)          │
│  MeshManager — engine lifecycle, persistence, simulator fallback    │
│  BleAdvertiserTransport — BLE advertise/scan, watchdog wiring       │
└───────────────────────────────┬─────────────────────────────────────┘
                                │ MeshTransport interface
┌───────────────────────────────▼─────── meshlib (pure Java) ────────┐
│  MeshNode — app-facing facade, ACK/NACK, auto-announce, ingest     │
│  Router — managed flood relay, dedup, hop/TTL rules                │
│  FragmentAssembler — reassembly + NACK rounds                      │
│  PacketCodec — 31-byte wire codec (v3) + BeaconParser (identity)   │
│  PositionEstimator · VenueDirectory · Dijkstra/A*/RouteBuilder     │
│  SimulatedTransport — deterministic in-process radio for tests     │
└─────────────────────────────────────────────────────────────────────┘
```

The only bridge between modules is the [`MeshTransport`](../meshlib/src/main/java/com/epics/bitchat/mesh/MeshTransport.java)
interface (chapter [2.5](02-mesh-engine.md#25-transports)). That is why the
same engine code is verified by JUnit tests, an emulator matrix, and real
phones (chapter [7](07-testing.md)).

## 1.3 Design philosophy

1. **The advert is the packet.** BLE advertisements carry a complete,
   self-contained 31-byte frame (chapter [3](03-protocol.md)). No connection,
   pairing or GATT is involved — any phone in radio range receives the flood.
2. **Users are nodes.** Every installation relays by default (opt-out in
   Settings). Multi-hop reach comes from the crowd, with a hop counter and
   TTL stopping runaway floods (chapter [2.3](02-mesh-engine.md#23-the-flood-router)).
3. **Nothing is silently dropped.** Oversize texts are visibly rejected;
   incomplete fragment groups NACK first, then expire with a console row;
   relay opt-outs and TTL deaths are logged, not eaten (chapters [4](04-fragmentation.md),
   [2](02-mesh-engine.md)).
4. **Measured beats heard.** A physically measured beacon RSSI outranks a
   relayed venue advert on a tie, and auto-announce never re-broadcasts
   second-hand positions (chapter [5.4](05-positioning-navigation.md#54-auto-announce-and-the-echo-chamber)).
5. **Engine purity.** `meshlib` has zero Android imports; every behaviour is
   testable on the JVM with a deterministic simulated radio (chapter [7.2](07-testing.md#72-unit-tests)).

## 1.4 The cross-team contract

The venue graph node ids deliberately match the **event schema of the
backend team's event service** (see [`GraphNode`](../meshlib/src/main/java/com/epics/bitchat/nav/GraphNode.java)):
`GET /events` venue names resolve 1:1 to graph nodes via
[`Graph.findNodeByName`](../meshlib/src/main/java/com/epics/bitchat/nav/Graph.java)
(exact first, then case-insensitive). Venue adverts that travel over the
mesh carry display names — the estimator resolves them back to node ids
(chapter [5.2](05-positioning-navigation.md#52-anchor-sources)).

## 1.5 Where each feature lives

| Feature | Engine class | Chapter |
|---------|--------------|---------|
| Flood relay, dedup, TTL | [`Router`](../meshlib/src/main/java/com/epics/bitchat/mesh/Router.java) | [2.3](02-mesh-engine.md#23-the-flood-router) |
| Fragmentation + NACK | [`FragmentAssembler`](../meshlib/src/main/java/com/epics/bitchat/mesh/FragmentAssembler.java) | [4](04-fragmentation.md) |
| Wire codec | [`PacketCodec`](../meshlib/src/main/java/com/epics/bitchat/mesh/PacketCodec.java) | [3](03-protocol.md) |
| Delivery ACK tracking | [`DeliveryTracker`](../meshlib/src/main/java/com/epics/bitchat/mesh/DeliveryTracker.java) | [2.6](02-mesh-engine.md#26-acknowledgements-and-nacks) |
| Venue knowledge | [`VenueDirectory`](../meshlib/src/main/java/com/epics/bitchat/mesh/VenueDirectory.java) | [5.3](05-positioning-navigation.md#53-venue-directory) |
| Position estimate | [`PositionEstimator`](../meshlib/src/main/java/com/epics/bitchat/mesh/PositionEstimator.java) | [5](05-positioning-navigation.md) |
| Route computation | [`Dijkstra`](../meshlib/src/main/java/com/epics/bitchat/nav/Dijkstra.java), [`AStar`](../meshlib/src/main/java/com/epics/bitchat/nav/AStar.java), [`RouteBuilder`](../meshlib/src/main/java/com/epics/bitchat/nav/RouteBuilder.java) | [5.6](05-positioning-navigation.md#56-routes) |
| MIUI scan-stall watchdog | [`ScanWatchdog`](../meshlib/src/main/java/com/epics/bitchat/mesh/ScanWatchdog.java) | [6.3](06-android-app.md#63-scanwatchdog-miui-fix) |
| Internet backhaul ingest | [`MeshNode.ingestFromInternet`](../meshlib/src/main/java/com/epics/bitchat/mesh/MeshNode.java) | [2.7](02-mesh-engine.md#27-internet-ingest) |

---
Next: [2 · Mesh engine](02-mesh-engine.md) — the runtime heart of the system.
