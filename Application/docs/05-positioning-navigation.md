# 5 · Positioning & Navigation

> Previous: [Fragmentation & recovery](04-fragmentation.md) · Next: [Android app](06-android-app.md)

The second half of the product: where am I, and how do I get somewhere.
Everything is anchored on a **venue graph** — no GPS, no maps API, works
entirely indoors offline.

## 5.1 The venue graph

[`Graph`](../meshlib/src/main/java/com/epics/bitchat/nav/Graph.java) holds
[`GraphNode`s](../meshlib/src/main/java/com/epics/bitchat/nav/GraphNode.java)
(id, display name, x/y, floor, `isBeacon`, tags like `accessible`) and
bidirectional weighted [`GraphEdge`s](../meshlib/src/main/java/com/epics/bitchat/nav/GraphEdge.java)
(meters, `isAccessible`).
[`DemoGraphFactory.buildCampusGraph`](../meshlib/src/main/java/com/epics/bitchat/nav/DemoGraphFactory.java)
seeds a demo campus (Main Gate, lecture blocks, cafeteria, stairs/elevator
*transition nodes*…); production venues come from the cross-team event
schema (chapter [1.4](01-overview.md#14-the-cross-team-contract)).

## 5.2 Anchor sources

[`PositionEstimator`](../meshlib/src/main/java/com/epics/bitchat/mesh/PositionEstimator.java)
fuses two kinds of input onto one RSSI-like scale:

1. **Hardware beacons / beacon-mode phones** — identity adverts with
   `type = 0x02` whose name is a graph node id (chapter [3.1](03-protocol.md#31-identity-advert-v1)).
   Their RSSI is *physically measured*, refreshed ~1 Hz, fresh for 10 s.
2. **Mesh venue adverts** — ordinary phones broadcasting `V:<venue>`
   (`MeshKind.BEACON`, chapter [3.2](03-protocol.md#32-mesh-message-v3)).
   Knowledge floods multi-hop; each relay leg costs a synthetic
   `HOP_ATTENUATION_DB = 7` from `DIRECT_ADVERT_RSSI = −55`, and beyond the
   `SIGNAL_FLOOR = −100` the advert is ignored as too far to trust.

Tie-breaking rule: **measured beats heard** — on an exact signal tie the
beacon anchor wins. Venue anchors are garbage-collected against the
directory (below) so stale adverts cannot pin a stale position.

## 5.3 Venue directory

[`VenueDirectory`](../meshlib/src/main/java/com/epics/bitchat/mesh/VenueDirectory.java)
aggregates every heard advert (venue → announcer, hops, freshness). On each
position refresh `MeshNode.syncVenueAnchors` re-applies live entries to the
estimator and drops expired ones. This is also what the Peers screen lists
as "who says they are where".

## 5.4 Auto-announce and the echo chamber

Every phone can become a *navigation beacon* for others
([`setAutoAnnounceEnabled`](../meshlib/src/main/java/com/epics/bitchat/mesh/MeshNode.java)),
subject to three gates evaluated every `AUTO_ANNOUNCE_PERIOD_MS = 5 000`:

1. **Manual pin wins** — a user-chosen venue (`setAutoAnnounceOverride`)
   outranks everything; the user is the highest authority.
2. **Announce only measured positions** — auto-announce reads
   `strongestBeaconAnchor()` (beacon-only view). Re-broadcasting *heard*
   adverts would reset hop counts on every leg and the whole mesh would
   converge on one venue — an echo chamber. The original flood already
   propagates venue knowledge with proper hop decay; hearsay must die here.
3. **Transition nodes are not venues** — stairs/elevator/ramp positions are
   never announced (no navigation meaning).

## 5.5 Estimating the position

Two estimate modes coexist by design:

- **`estimate()` — strongest anchor** (nearest-beacon heuristic): the
  classic path; used by auto-announce's beacon-only gate and by
  `describeEstimate()` ("Hall 1 via beacon RSSI −55" vs "Hall 2 via mesh
  advert (2 hops)").
- **`estimatePosition()` — weighted centroid** (the refinement, modelled on
  standard BLE indoor-positioning projects): every fresh anchor converts its
  smoothed RSSI to **meters** with the AltBeacon-library path-loss curve
  (`distanceMeters(rssi)`, `TX_POWER_AT_1M_DB = −59`, A = 0.89976,
  B = 7.7095, C = 0.111, `ratio^10` below 1 m), pulls the centroid with
  inverse-square weight, and the centroid snaps to the nearest graph node.
  Returns a [`Position`](../meshlib/src/main/java/com/epics/bitchat/mesh/PositionEstimator.java):
  `nodeId`, `confidence` (best-anchor signal quality × anchor agreement —
  1 anchor = half weight, 3+ = full), `anchorsUsed`, `bestDistanceMeters`.
  `describeWeightedEstimate()` renders it: *"Hall 1 (≈1.1 m, 2 anchors, 67%)"*.

`MeshNode.updatePosition` uses the weighted result for the app-facing
position and classifies provenance (`AnchorSource.BEACON / MESH / NONE`),
firing `onPositionUpdated` only on change (chapter [2.1](02-mesh-engine.md#21-meshnode--the-facade)).

## 5.6 Routes

- [`Dijkstra`](../meshlib/src/main/java/com/epics/bitchat/nav/Dijkstra.java)
  and [`AStar`](../meshlib/src/main/java/com/epics/bitchat/nav/AStar.java)
  compute shortest paths over edge weights (meters).
- [`RouteBuilder`](../meshlib/src/main/java/com/epics/bitchat/nav/RouteBuilder.java)
  turns a node path into human [`RouteStep`s](../meshlib/src/main/java/com/epics/bitchat/nav/RouteStep.java)
  — turn direction, distance, floor changes — preferring accessible edges
  when the user asks for step-free routing.
- The Android layer draws the graph ([`GraphView`](../app/src/main/java/com/epics/bitchat/ui/GraphView.java)),
  shows heading ([`CompassView`](../app/src/main/java/com/epics/bitchat/ui/CompassView.java))
  and renders steps ([`StepAdapter`](../app/src/main/java/com/epics/bitchat/ui/StepAdapter.java)) —
  chapter [6.4](06-android-app.md#64-ui-layer).

## 5.7 Positioning constants

| Constant | Value | Meaning |
|----------|-------|---------|
| `HOP_ATTENUATION_DB` | 7 | synthetic RSSI cost per relay leg |
| `DIRECT_ADVERT_RSSI` | −55 | synthetic RSSI of a directly-heard advert |
| `SIGNAL_FLOOR` | −100 | beyond this an advert anchor is rejected |
| `TX_POWER_AT_1M_DB` | −59 | 1 m reference of the ranging model |
| anchor freshness | 10 s | beacon RSSI max age |

**Tests**: [`PositionEstimatorTest`](../meshlib/src/test/java/com/epics/bitchat/mesh/PositionEstimatorTest.java),
[`PositionEstimatorDistanceTest`](../meshlib/src/test/java/com/epics/bitchat/mesh/PositionEstimatorDistanceTest.java),
[`MeshPositionTest`](../meshlib/src/test/java/com/epics/bitchat/mesh/MeshPositionTest.java),
[`AutoAnnounceTest`](../meshlib/src/test/java/com/epics/bitchat/mesh/AutoAnnounceTest.java),
[`RssiFilterTest`](../meshlib/src/test/java/com/epics/bitchat/mesh/RssiFilterTest.java),
[`DijkstraTest`](../meshlib/src/test/java/com/epics/bitchat/nav/DijkstraTest.java),
[`RouteBuilderTest`](../meshlib/src/test/java/com/epics/bitchat/nav/RouteBuilderTest.java) —
see chapter [7](07-testing.md).

---
Previous: [Fragmentation & recovery](04-fragmentation.md) · Next: [Android app](06-android-app.md)
