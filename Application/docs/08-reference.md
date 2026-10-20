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
identity `sanitizeNodeId`; internals `kindByte`/`kindFrom` (MeshKind ⇄ wire byte),
`fragmentIndex` / `fragmentCount` / `fragmentText` (frame-element readers),
`fragChar`/`fragVal` (A–Z, 0–9 token alphabet), `sanitizeText`,
`wireMsgId` (FNV-1a → 3-char id).

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
| [`Dijkstra`](../meshlib/src/main/java/com/epics/bitchat/nav/Dijkstra.java) / [`AStar`](../meshlib/src/main/java/com/epics/bitchat/nav/AStar.java) | `findShortestPath(graph, fromId, toId[, accessible])` → `List<GraphNode>` (empty if unreachable); Dijkstra settles by `dist`, A* by `f = g + hypot(dx, dy)`; both share `reconstruct(prev, toId)` — step-by-step in [5.6](05-positioning-navigation.md#56-routes) |
| [`RouteBuilder`](../meshlib/src/main/java/com/epics/bitchat/nav/RouteBuilder.java) | `build(graph, path[, accessible])` → `Route`; per leg `edgeWeight(from, to)`, `bearingDeg(a, b)` (0–360, 0 = north), `stepText(...)` (8 compass sectors) |
| [`Route`](../meshlib/src/main/java/com/epics/bitchat/nav/Route.java) | `getPath`, `getSteps`, `getTotalMeters`, `getTotalSeconds` (`meters / 1.4 + 30 s per floor change`), `isAccessible`, `getStart` / `getDestination`; `WALK_SPEED_MPS = 1.4`, `FLOOR_CHANGE_PENALTY_S = 30` |
| [`RouteStep`](../meshlib/src/main/java/com/epics/bitchat/nav/RouteStep.java) | `getFromId`, `getToId`, `getText`, `getDistanceMeters`, `getBearingDeg`, `isFloorChange` |
| [`DemoGraphFactory`](../meshlib/src/main/java/com/epics/bitchat/nav/DemoGraphFactory.java) | `buildCampusGraph()`, `isTransitionNode(id)` |

## ai — recommendation & assistant

Two dependency-free engines in [`meshlib/.../ai`](../meshlib/src/main/java/com/epics/bitchat/ai).
Both are **Java mirrors of the Python `ai-service`** (`recommendations.py`,
`assistant.py`) so the on-device test UI and the server score identically —
chapter [6.5](06-android-app.md#65-ai-layer).

### RecommendationEngine

Static utility (`private` constructor) that ranks events for one user.

| Member | Signature / notes |
|--------|-------------------|
| `recommend` | `static List<Result> recommend(List<Event> events, String[] interests, int limit, long nowMs)` — scores every event, sorts **descending by score**, tie-breaks by `eventId`, returns `min(limit, size)` |
| score | `0.6·interest + 0.25·popularity + 0.15·(recency − 1)` |
| `Event.popularity()` | `min(1, registered / capacity)`; `0` when `capacity ≤ 0` |
| `jaccard(wanted, tags, category)` | interest = `\|common\| / \|union\|` over lower-cased strings, where `offered = tags + category` and `wanted` is the user's normalized interest set |
| category boost | if the user's interests contain the event's category → `min(1, interest × CATEGORY_BOOST)` |
| `recencyBoost(event, nowMs)` | `1 + e^(−Δdays/2)` with `Δdays = (start − now)/86 400 000`; an event already started returns `2.0` (maximum) |
| `normalize(raw)` | trims + lower-cases the interest array into a `Set<String>`; empty interests ⇒ pure popularity/recency ranking |
| constants | `W_INTEREST = 0.6`, `W_POPULARITY = 0.25`, `W_RECENCY = 0.15`, `CATEGORY_BOOST = 1.25` |
| types | `Event{eventId, title, category, tags, startEpochMs, capacity, registered, venue}` · `Result{event, score, interest, popularity, recency}` (breakdown kept for the UI) |

*Worked example* — interest match 0.33, 60 % full, starting in 2 days, no
category match: `0.6·0.33 + 0.25·0.60 + 0.15·(1.368 − 1) ≈ 0.40`. If the
category matched, `interest` would be ×1.25 **before** weighting.

### Assistant

Grounded FAQ + navigation answers: `answer(query, accessibilityMode)` →
`Answer{text, intent, matchedFaq}`, evaluated strictly in this order:

1. **`classify(text)`** → intent — `navigate` (any of `where `, `how do i get`,
   `directions`, `route`, `navigate`, `way to`), then `registration`
   (`register`, `check in`, `check-in`), then `schedule` (`when `, `what time`,
   `start`), else `unknown`.
2. **`bestFaq(text)`** — scores each `FaqEntry`: **+2.0 per keyword hit**
   (substring match) plus **3 × (shared tokens ÷ question tokens)**; the entry
   is returned only when its score reaches `≥ 2.0`. A FAQ match short-circuits
   everything below.
3. **`mentionedVenues(text)`** — every graph node whose name occurs in the
   query, sorted by first mention.
4. **`navAnswer(...)`** (intent `navigate` **and** ≥ 2 venues) — the mention
   *before* `" from "` is the destination, the one after it is the origin
   (`"Library from Hall 1"`); then
   `Dijkstra.findShortestPath(…, accessibilityMode)` → `RouteBuilder.build(…)`.
   The answer reports metres, minutes, whether a floor change is involved, and
   the first step. If the accessible search fails but the standard one
   succeeds: *"There is no step-free route … the standard route uses stairs"*.
5. Exactly one venue → *"\<name> is on floor N …"*; nothing matched → the
   fallback prompt.
6. **`defaultFaq()`** — six organizer entries (registration desk, wheelchair
   accessibility, food, parking, lost & found, check-in), shared verbatim with
   `assistant.py`.

### DemoEventCatalog

Offline dataset behind `RecommendationEngine`; `days(...)` / `hours(...)`
helpers build relative `startEpochMs` values so the demo schedule never goes
stale.

The app-side HTTP bridge to the Python service is
[`AiServiceClient`](../app/src/main/java/com/epics/bitchat/ai/AiServiceClient.java)
(function table in chapter [6.5](06-android-app.md#65-ai-layer)).

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
