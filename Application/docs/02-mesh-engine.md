# 2 · The Mesh Engine

> Previous: [Overview](01-overview.md) · Next: [Wire protocol](03-protocol.md)

The engine's center of gravity is [`MeshNode`](../meshlib/src/main/java/com/epics/bitchat/mesh/MeshNode.java):
everything the app wants to do goes through it, and everything the radio
hears comes back through it.

## 2.1 MeshNode — the facade

**Responsibilities** (one class, seven jobs):

1. Owns the transport, [`Router`](#23-the-flood-router), peer table
   ([`MeshNetwork`](../meshlib/src/main/java/com/epics/bitchat/mesh/MeshNetwork.java))
   and traffic log ([`MessageLog`](../meshlib/src/main/java/com/epics/bitchat/mesh/MessageLog.java)).
2. Sends: [`send(dst, kind, payload)`](../meshlib/src/main/java/com/epics/bitchat/mesh/MeshNode.java)
   auto-fragments oversized texts (chapter [4](04-fragmentation.md)),
   [`broadcast(payload, ttl)`](../meshlib/src/main/java/com/epics/bitchat/mesh/MeshNode.java)
   floods with a chosen TTL.
3. Receives: [`onPacketReceived`](../meshlib/src/main/java/com/epics/bitchat/mesh/MeshNode.java)
   offers every payload to the [`FragmentAssembler`](../meshlib/src/main/java/com/epics/bitchat/mesh/FragmentAssembler.java)
   first, then hands the packet to the `Router` for dedup/deliver/relay.
4. Delivery bookkeeping: sends ACKs for CHAT, matches incoming ACK tokens in
   the [`DeliveryTracker`](#26-acknowledgements-and-nacks), answers NACKs by
   re-sending cached frames (chapter [4.4](04-fragmentation.md#44-nack-recovery-meshtastic-style)).
5. Venue life-cycle: [`announceVenue`](../meshlib/src/main/java/com/epics/bitchat/mesh/MeshNode.java),
   the 15 s re-advertise tick, and auto-announce
   (chapter [5.4](05-positioning-navigation.md#54-auto-announce-and-the-echo-chamber)).
6. Internet backhaul: [`ingestFromInternet`](#27-internet-ingest) floods
   server events into the neighbourhood with dedupable ids.
7. Maintenance single-thread: peer refresh (2 s), delivery sweeps (2 s),
   NACK sweeps (2 s), venue re-advertise (15 s), auto-announce (5 s),
   rebroadcast window service (500 ms) — all scheduled in
   [`start()`](../meshlib/src/main/java/com/epics/bitchat/mesh/MeshNode.java).

**Listener contract** — [`MeshEventListener`](../meshlib/src/main/java/com/epics/bitchat/mesh/MeshEventListener.java)
callbacks (all on mesh worker threads; the app must hop to main):

| Callback | Fires when |
|----------|-----------|
| `onLogEntry` | every console-visible traffic row |
| `onMessageDelivered` | a *complete* message arrived (frames never surface) |
| `onPeerDiscovered` / `onPeerLost` | peer table changes |
| `onPositionUpdated` | position estimate changed (node id or `null` = lost) |
| `onVenueDirectoryChanged` | new/refreshed venue advert |
| `onDeliveryStatus(token, dst, delivered)` | ACK matched or 12 s timeout |

## 2.2 Peer table — MeshNetwork & RssiFilter

[`MeshNetwork`](../meshlib/src/main/java/com/epics/bitchat/mesh/MeshNetwork.java)
keeps one [`Peer`](../meshlib/src/main/java/com/epics/bitchat/mesh/Peer.java)
(`nodeId`, display name, address, `isBeacon`, RSSI, last-seen) per device.
[`upsert`](../meshlib/src/main/java/com/epics/bitchat/mesh/MeshNetwork.java)
refreshes and reports newness so `onPeerDiscovered` fires once;
[`expire`](../meshlib/src/main/java/com/epics/bitchat/mesh/MeshNetwork.java)
drops stale peers each refresh tick.

Raw RSSI is never trusted directly — BLE multipath makes single samples jump
by ±10 dB. [`RssiFilter`](../meshlib/src/main/java/com/epics/bitchat/mesh/RssiFilter.java)
layers a 5-sample moving average over exponential smoothing (α = 0.3);
positioning consumes only smoothed values (chapter [5](05-positioning-navigation.md)).

## 2.3 The flood Router

[`Router.onReceive`](../meshlib/src/main/java/com/epics/bitchat/mesh/Router.java)
implements the whole relay discipline:

1. **Dedup by message id.** The first copy of an id is processed; every later
   copy is a logged `DROPPED_DUP`. Our own sends are pre-marked seen, so a
   neighbour's relay echoing back cannot loop ([`onLocalSend`](../meshlib/src/main/java/com/epics/bitchat/mesh/Router.java)).
2. **Deliver** broadcast packets locally via the sink; **unicast** only when
   `dstId` is ours (and the flood stops there).
3. **Relay** broadcast packets onward — *decrement TTL, increment hops* —
   until TTL 0 (logged `DROPPED_TTL`). The wire caps both at 15
   (chapter [3.2](03-protocol.md#32-mesh-message-v3)).
4. **Respect the relay opt-out**: with relay disabled a packet is consumed
   locally but the flood ends here, logged `DROPPED_NO_RELAY` — counted, not
   silent (issue #9's fix).
5. **Stats** ([`RelayStats`](../meshlib/src/main/java/com/epics/bitchat/mesh/RelayStats.java)):
   sent/delivered/relayed/dropped counters plus a hops histogram — the
   Hop-test screen reads these.

`DEFAULT_TTL = 8` — deep enough for a packed hall, short enough to die
before leaking building-wide.

## 2.4 Traffic log

[`MessageLog`](../meshlib/src/main/java/com/epics/bitchat/mesh/MessageLog.java)
is a bounded in-memory ring of [`MeshLogEntry`](../meshlib/src/main/java/com/epics/bitchat/mesh/MeshLogEntry.java)
rows (direction, from/to, hops, RSSI, status, msg id). Every router decision
and send path writes a row; `MeshNode` fans each one out as `onLogEntry` —
this is literally what the Console tab renders (chapter [6.4](06-android-app.md#64-ui-layer)).

## 2.5 Transports

The engine speaks to radios through one interface, [`MeshTransport`](../meshlib/src/main/java/com/epics/bitchat/mesh/MeshTransport.java):
`start/stop/isRunning`, `send(message)`, `getPeers()`, `setListener`.
Two implementations:

| Transport | Used | Behaviour |
|-----------|------|-----------|
| [`SimulatedTransport`](../meshlib/src/main/java/com/epics/bitchat/mesh/SimulatedTransport.java) | tests, demos, BT-off fallback | Deterministic in-process radio: 30 m range, RSSI −41 dBm at 1 m with exponent 3, configurable packet loss, ACK synthesis for directed CHAT. |
| [`BleAdvertiserTransport`](../app/src/main/java/com/epics/bitchat/mesh/BleAdvertiserTransport.java) | the app | Real BLE advertise + scan (chapter [6.2](06-android-app.md#62-ble-transport)). |

`MeshManager` (chapter [6.1](06-android-app.md#61-meshmanager)) picks one at
runtime and switches SIM→BLE when Bluetooth turns on mid-session.

## 2.6 Acknowledgements and NACKs

- **Directed CHAT** → the receiver sends `MeshKind.ACK` whose payload is the
  3-char [`ackToken`](../meshlib/src/main/java/com/epics/bitchat/mesh/PacketCodec.java)
  of the original (issue #16: the token travels in the *payload*; the ACK's
  own wire id is random). The sender's [`DeliveryTracker`](../meshlib/src/main/java/com/epics/bitchat/mesh/DeliveryTracker.java)
  matches it, fires `onDeliveryStatus(…, true)`, or times out at
  `DEFAULT_TIMEOUT_MS = 12_000` (status "... is out of range").
- **Fragment streams** → *one* group-token ACK for the reassembled text, not
  one ACK per frame (chapter [4.3](04-fragmentation.md#43-reassembly--fragmentassembler)).
- **Missing frames** → `MeshKind.NACK` with a bitmask; the sender re-floods
  exactly the missing frames (chapter [4.4](04-fragmentation.md#44-nack-recovery-meshtastic-style)).

## 2.7 Internet ingest

[`ingestFromInternet(externalId, payload, ttl)`](../meshlib/src/main/java/com/epics/bitchat/mesh/MeshNode.java)
bridges the online world: a server push (alert, venue change) is flooded
over BLE so nearby offline phones receive it. The trick is determinism:
every phone that receives the *same* server event derives the *same* 3-char
wire id via FNV-1a (`wireMsgIdFor`), so duplicate ingests collapse in the
router's dedup instead of double-flooding. The copy is re-advertised on a
fixed cadence for [`RebroadcastWindow.DEFAULT_WINDOW_MS = 5_000`](../meshlib/src/main/java/com/epics/bitchat/mesh/RebroadcastWindow.java)
(covers phones entering range late), then the window closes.
[`cancelInternetRebroadcast`](../meshlib/src/main/java/com/epics/bitchat/mesh/MeshNode.java)
ends the window early. Fragmented ingests use event-derived per-frame ids so
*every frame* of the same event dedups identically (chapter [4.5](04-fragmentation.md#45-ingest-and-deterministic-frame-ids)).

## 2.8 Tuning constants (engine)

| Constant | Value | Where | Meaning |
|----------|-------|-------|---------|
| `Router.DEFAULT_TTL` | 8 | [Router.java](../meshlib/src/main/java/com/epics/bitchat/mesh/Router.java) | flood reach in hops |
| `DeliveryTracker.DEFAULT_TIMEOUT_MS` | 12 000 | [DeliveryTracker.java](../meshlib/src/main/java/com/epics/bitchat/mesh/DeliveryTracker.java) | ACK wait |
| `RebroadcastWindow.DEFAULT_WINDOW_MS` | 5 000 | [RebroadcastWindow.java](../meshlib/src/main/java/com/epics/bitchat/mesh/RebroadcastWindow.java) | ingest re-advertise window |
| `MeshNode.FRAGMENT_INTERVAL_MS` | 400 | [MeshNode.java](../meshlib/src/main/java/com/epics/bitchat/mesh/MeshNode.java) | gap between frames |
| `MeshNode.VENUE_ADVERT_PERIOD_MS` | 15 000 | [MeshNode.java](../meshlib/src/main/java/com/epics/bitchat/mesh/MeshNode.java) | venue re-advertise |
| `MeshNode.AUTO_ANNOUNCE_PERIOD_MS` | 5 000 | [MeshNode.java](../meshlib/src/main/java/com/epics/bitchat/mesh/MeshNode.java) | auto-announce tick |
| Fragment/NACK timings | — | chapter [4.6](04-fragmentation.md#46-constants) | TTL, NACK cadence |

---
Previous: [Overview](01-overview.md) · Next: [Wire protocol](03-protocol.md)
