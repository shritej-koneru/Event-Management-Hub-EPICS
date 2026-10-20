# 8 · API Reference (`meshlib`)

> Previous: [Testing & verification](07-testing.md) · Back to [index](README.md)

Public surface of the engine, by package. Everything lives under
`com.epics.bitchat.{mesh,nav,ai}` in [`meshlib/src/main/java`](../meshlib/src/main/java/com/epics/bitchat).

## mesh — core

### MeshNode
The facade (chapter [2.1](02-mesh-engine.md#21-meshnode--the-facade)). All callbacks arrive on mesh worker threads.

| Member | Signature / notes |
|--------|-------------------|
| constructor | `MeshNode(String nodeId, String displayName, MeshTransport transport, MessageLog log)` |
| lifecycle | `start()` / `stop()` / `isRunning()` |
| send | `boolean send(String dstId, MeshKind kind, String payload)` — auto-fragments; `boolean broadcast(String payload, int ttl)` |
| ingest | `boolean ingestFromInternet(String externalId, String payload[, int ttl])`; `void cancelInternetRebroadcast(String externalId)`; `static String wireMsgIdFor(String)` |
| venue | `boolean announceVenue(String)`; `getAdvertisedVenue()`; `setAutoAnnounceEnabled(boolean)`; `setAutoAnnounceOverride(String)` |
| relay | `setRelayEnabled(boolean)` / `isRelayEnabled()` / `getRelayStats()` |
| position | `setPositionEstimator(PositionEstimator)`; `getLastPositionNodeId()`; `getLastPositionSource()`; `enum AnchorSource { BEACON, MESH, NONE }` |
| accessors | `getVenueDirectory()`, `getNetwork()`, `getPeers()`, `getLog()`, `getTransport()`, `getNodeId()`, `getDisplayName()` |
| listeners | `addListener` / `removeListener(MeshEventListener)` |
| constants | `FRAGMENT_INTERVAL_MS = 400`, `VENUE_ADVERT_PERIOD_MS = 15_000`, `AUTO_ANNOUNCE_PERIOD_MS = 5_000` |

### Router
Managed flood relay (chapter [2.3](02-mesh-engine.md#23-the-flood-router)):
`onReceive(msg, rssi, from)`, `onLocalSend(msg)`, `hasSeen(id)`,
`setRelayEnabled`, `getStats()`, `reset()`. `DEFAULT_TTL = 8`.

### MeshKind
`CHAT, PING, ACK, NACK, BEACON, ANNOUNCE, TICKET` — wire bytes in chapter [3.2](03-protocol.md#32-mesh-message-v3).

### MeshMessage
Immutable wire message: `msgId, srcId, dstId, kind, payload, ttl, hops, timestamp`;
factories `create(…)` (random id) and `createWithId(…)`; `relayed()` returns
the TTL-decremented, hops-incremented copy.

### PacketCodec
Static wire codec (chapter [3](03-protocol.md)):
`encode` / `decode` / `fitsOnWire`; `ackToken(msg)`;
fragment framing `fragment(text, groupId)`, `makeFragmentFrame`, `isFragment`,
`fragmentGroup/Index/Count/Text`, `fragmentIndexToken`, `fragmentGroupId`;
NACK framing `makeNackPayload(groupId, mask)`, `isNack`, `nackGroupId`,
`nackMissingMask`;
limits `MAX_PAYLOAD = 14`, `FRAMING_OVERHEAD = 5`, `FRAGMENT_TEXT_CHARS = 9`,
`MAX_FRAGMENTS = 36`, `MAX_FRAGMENTED_TEXT = 324`, `HEADER_SIZE = 13`,
`PACKET_SIZE = 27`;
identity `sanitizeNodeId`.

### BeaconParser / BlePacketInterpreter
Identity-advert codec (`BeaconInfo{nodeId, displayName, floor, isBeacon}`,
`TYPE_PHONE/TYPE_BEACON`) and scan-payload classifier
(`Hit{reason: IDENTITY|MESSAGE|SELF|NOT_INTERESTING}`) — chapter [3.1](03-protocol.md#31-identity-advert-v1).

### FragmentAssembler
Reassembly + NACK rounds (chapter [4.3](04-fragmentation.md#43-reassembly--fragmentassembler)–[4.4](04-fragmentation.md#44-nack-recovery-meshtastic-style)).
`offer(sender, kind, payload, nowMs)` → `Frame` or `null`;
`nackSweep(nowMs)`; `pendingGroups()`.
`Callback`: `onTextAssembled(sender, groupId, kind, text)`;
optional `onGroupIncomplete(sender, groupId, missingIndices, received, total)`
and `onGroupExpired(sender, groupId, received, total)`;
`Frame{sender, groupId, index, total, text}` is the offer() return.
Constants: `GROUP_TTL_MS = 30_000`, `NACK_AFTER_MS = 2_000`,
`NACK_RETRY_MS = 6_000`, `MAX_NACK_ROUNDS = 3`.

### DeliveryTracker
Pending-ACK bookkeeping (chapter [2.6](02-mesh-engine.md#26-acknowledgements-and-nacks)):
`track(token, dstId[, nowMs])`, `boolean ack(token[, nowMs])`,
`sweep(nowMs)` → `Expired{token, dstId}` list. `DEFAULT_TIMEOUT_MS = 12_000`.

### MeshNetwork / Peer / RssiFilter
Peer table `upsert`/`expire`/`getAll` (chapter [2.2](02-mesh-engine.md#22-peer-table--meshnetwork--rssifilter));
`Peer(nodeId, displayName, deviceAddress, isBeacon)` with `setRssi`,
`getLastSeenEpochMs`; `RssiFilter.update(sample)`/`get()`/`reset()`
(5-sample window, α = 0.3), `NO_SIGNAL = −128`.

### MessageLog / MeshLogEntry
Bounded traffic log with `addListener` fan-out; entries carry
`Direction{TX,RX}`, `MeshStatus{IN_FLIGHT, DELIVERED, RELAYED,
DROPPED_DUP, DROPPED_TTL, DROPPED_NO_RELAY, DROPPED_TOO_LONG, DROPPED_LOSS}`.

### VenueDirectory / AnnouncementDirectory
Venue advert aggregation (`Entry`, `Live` views, `venuePrefix()` = `V:`,
`isVenueAdvert(payload)`, `venueOf(payload)`, `observe(venue, src, hops)`,
`hopsTo(venue)`) and the demo announcement catalogue (`MAX_TEXT = 6`,
capacity 50, repeat window 45 s).

### ScanWatchdog
Pure scan-health state machine (chapter [6.3](06-android-app.md#63-scanwatchdog-miui-fix)):
`onScanCallback(now)` / `onMeshPacket(now)` / `evaluate(now)` →
`Action{NONE, REARM_SCAN, FULL_RECOVERY}`; `RAW_STALL_MS = 60_000`,
`MESH_STALL_MS = 45_000`, probe 20 s, `PEER_MEMORY_MS = 600_000`.

### MeshTransport / MeshEventListener / MeshStatus
The two integration seams (chapters [2.5](02-mesh-engine.md#25-transports), [2.1](02-mesh-engine.md#21-meshnode--the-facade)):
`MeshTransport{start, stop, isRunning, send, getPeers, setListener}`;
`MeshEventListener` callbacks in chapter [2.1](02-mesh-engine.md#21-meshnode--the-facade).

### SimulatedTransport
Deterministic radio: 30 m range, RSSI = −41 dBm at 1 m, exponent 3,
`packetLossPct` default 0.10, ACK synthesis for directed CHAT.

## mesh — positioning (see chapter 5)

### PositionEstimator
`feed(Iterable<Peer>)`; `observeVenueAdvert(venue, hops[, now])`;
`retainVenueAnchors(Set<String>)`; `clearVenueAnchors()`;
`estimate()` / `describeEstimate()` / `strongestAnchor()` /
`strongestBeaconAnchor()`;
`Position estimatePosition()` / `describeWeightedEstimate()`;
`static double distanceMeters(int rssi[, int txPowerAt1m])`.
Types: `Anchor{nodeId, signal, fromBeacon, distanceMeters()}`,
`Position{nodeId, confidence, anchorsUsed, bestDistanceMeters}`.
Constants: `HOP_ATTENUATION_DB = 7`, `DIRECT_ADVERT_RSSI = −55`,
`TX_POWER_AT_1M_DB = −59`.

## nav — graph & routing

| Class | Key API |
|-------|---------|
| [`Graph`](../meshlib/src/main/java/com/epics/bitchat/nav/Graph.java) | `addNode`, `addEdge`, `getNode`, `findNodeByName`, `getEdges`, `getNeighbors`, `getAllEdges` |
| [`GraphNode`](../meshlib/src/main/java/com/epics/bitchat/nav/GraphNode.java) | id, name, x/y, floor, `isBeacon`, tags, `distanceTo` |
| [`GraphEdge`](../meshlib/src/main/java/com/epics/bitchat/nav/GraphEdge.java) | from, to, `getWeightMeters()`, `isAccessible()` |
| [`Dijkstra`](../meshlib/src/main/java/com/epics/bitchat/nav/Dijkstra.java) / [`AStar`](../meshlib/src/main/java/com/epics/bitchat/nav/AStar.java) | shortest path over the graph |
| [`RouteBuilder`](../meshlib/src/main/java/com/epics/bitchat/nav/RouteBuilder.java) | node path → `List<RouteStep>` (turns, distance, floor, accessible preference) |
| [`DemoGraphFactory`](../meshlib/src/main/java/com/epics/bitchat/nav/DemoGraphFactory.java) | `buildCampusGraph()`, `isTransitionNode(id)` |

## ai — recommendation

[`RecommendationEngine`](../meshlib/src/main/java/com/epics/bitchat/ai/RecommendationEngine.java)
ranks catalogued events against position and time;
[`DemoEventCatalog`](../meshlib/src/main/java/com/epics/bitchat/ai/DemoEventCatalog.java)
supplies the offline dataset; [`Assistant`](../meshlib/src/main/java/com/epics/bitchat/ai/Assistant.java)
composes the answers consumed by the app's assistant screens (chapter [6.5](06-android-app.md#65-ai-layer)).
The app-side HTTP bridge is [`AiServiceClient`](../app/src/main/java/com/epics/bitchat/ai/AiServiceClient.java).

## Integration recipe (30 seconds)

```java
MessageLog log = new MessageLog();
MeshNode node = new MeshNode(myId, myName, myTransport, log);
node.setPositionEstimator(new PositionEstimator(myGraph));
node.addListener(new MeshEventListener() { /* console, deliveries, position */ });
node.start();
node.send(dstId, MeshKind.CHAT, "any length up to 324 chars");
```

Replace `myTransport` with [`SimulatedTransport`](../meshlib/src/main/java/com/epics/bitchat/mesh/SimulatedTransport.java)
to run the whole stack in a JVM test — that is exactly what chapter [7.2](07-testing.md#72-unit-tests) does.

---
Previous: [Testing & verification](07-testing.md) · Back to [index](README.md)
