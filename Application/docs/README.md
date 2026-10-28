# BitChat Mesh — Documentation Handbook

> **Chapter index.** This folder documents the BitChat-style BLE mesh +
> indoor-navigation Android project completely: architecture, wire protocol,
> every engine component, the Android app, and the test strategy. Each
> document is a chapter; they cross-reference each other like a book.

## The stack in one paragraph

Two Gradle modules. **[`meshlib/`](../meshlib)** is a pure-Java engine: a
BLE-advertisement flood-routing mesh (devices = nodes, users = relay hops)
with auto-fragmentation of long texts, NACK-based frame recovery, venue
adverts, RSSI positioning and Dijkstra routing over a venue graph. It knows
nothing about Android and runs identically in JUnit tests, a simulated radio,
or on a phone. **[`app/`](../app)** is the Android shell: BLE advertising +
scanning transport, permissions, UI (console, peers, send, navigation,
settings) and an AI assistant layer.

## Chapters

| # | Document | What it covers |
|---|----------|----------------|
| 1 | [Overview & architecture](01-overview.md) | What the system is, layer diagram, design philosophy, module map |
| 2 | [Mesh engine](02-mesh-engine.md) | `MeshNode`, flood `Router`, peer table, transports, logging, tuning constants |
| 3 | [Wire protocol](03-protocol.md) | Byte-level advert formats, ids and tokens, message kinds, limits |
| 4 | [Fragmentation & recovery](04-fragmentation.md) | Auto-splitting long texts, reassembly, NACK re-send (Meshtastic-style) |
| 5 | [Positioning & navigation](05-positioning-navigation.md) | Beacons + venue adverts as anchors, ranging, weighted centroid, routes |
| 6 | [Android app](06-android-app.md) | `MeshManager`, BLE transport, UI fragments, AI layer, test hub |
| 7 | [Testing & verification](07-testing.md) | 150 unit tests, emulator matrix, hardware matrix, how to run everything |
| 8 | [API reference](08-reference.md) | Public classes, methods, callbacks and constants of `meshlib` |
| — | [Demo script](DEMO_SCRIPT.md) | Pre-existing scripted live demo walk-through |

## Quick facts

| Fact | Value |
|------|-------|
| Language | Java 8-compatible, no external engine dependencies |
| Modules | `:meshlib` (engine), `:app` (Android UI) |
| Radio | BLE 31-byte advertisement manufacturer data (no connection needed) |
| Payload / advert | 14 chars (see [`PacketCodec`](08-reference.md#packetcodec)) |
| Longest text | 324 chars, auto-fragmented into ≤36 frames |
| Flood routing | Managed flood, TTL 8, per-id dedup window |
| Delivery | ACK (12 s timeout) / group-token ACK for fragments / NACK re-send |
| Positioning | Beacon RSSI + mesh venue adverts → weighted centroid on a venue graph |
| Tests | 150 unit tests (green) + emulator matrix + 3-phone hardware matrix |

## How to read this book

- **New to the codebase?** Read chapters [1](01-overview.md) →
  [2](02-mesh-engine.md) → [3](03-protocol.md) in order; they build on each
  other.
- **Integrating the engine elsewhere?** Chapter [8](08-reference.md) is the
  API surface; chapter [2](02-mesh-engine.md) explains the runtime behaviour.
- **Debugging lost messages?** Chapter [4](04-fragmentation.md) (fragment
  loss + NACK) and chapter [3](03-protocol.md) (dedup ids) hold the answers.
- **Working on maps/positioning?** Chapter [5](05-positioning-navigation.md)
  covers the whole pipeline from RSSI to route steps.

Every claim is anchored to source: paths are relative links into the
repository, e.g. [`Router.java`](../meshlib/src/main/java/com/epics/bitchat/mesh/Router.java).

## At a glance: one message's journey

```
 SendFragment (UI)            MeshNode                    Router            BLE radio
 ─────────────────► send() ──► fragment if > 14 chars ──► flood + dedup ──► advertise
                                                                    │
 receiver: scan → interpret → dedup/deliver → FragmentAssembler ────┘
           → complete text → group ACK → sender shows "Delivered"
```

Details: [chapter 3](03-protocol.md) for the bytes,
[chapter 4](04-fragmentation.md) for the splitting/reassembly,
[chapter 2](02-mesh-engine.md) for the hop-by-hop rules.
