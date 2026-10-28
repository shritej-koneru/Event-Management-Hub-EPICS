# 3 · Wire Protocol

> Previous: [Mesh engine](02-mesh-engine.md) · Next: [Fragmentation & recovery](04-fragmentation.md)

Two packet families share the 31-byte BLE advertisement manufacturer-data
record, distinguished by their first byte (version):

| Byte 0 | Family | Decoder |
|--------|--------|---------|
| `0x01` | **Identity advert** | [`BeaconParser`](../meshlib/src/main/java/com/epics/bitchat/mesh/BeaconParser.java) |
| `0x03` | **Mesh message** | [`PacketCodec`](../meshlib/src/main/java/com/epics/bitchat/mesh/PacketCodec.java) |

[`BlePacketInterpreter`](../meshlib/src/main/java/com/epics/bitchat/mesh/BlePacketInterpreter.java)
routes each scan payload to the right decoder and classifies the hit
(`IDENTITY`, `MESSAGE`, `SELF`, `NOT_INTERESTING`).

## 3.1 Identity advert (v1)

```
byte 0      version 0x01
byte 1      type: 0x01 phone · 0x02 beacon
byte 2..7   node id, 6 printable-ASCII chars ('\0' padded)
byte 8..    display name (ASCII)
```

Phones advertise this once per second; receivers upsert the peer table from
it (chapter [2.2](02-mesh-engine.md#22-peer-table--meshnetwork--rssifilter)).
`type = 0x02` marks a hardware (or beacon-mode phone) whose name is a graph
node id — the anchor source for positioning (chapter [5.2](05-positioning-navigation.md#52-anchor-sources)).

## 3.2 Mesh message (v3)

```
byte 0       version = 0x03
byte 1       kind              (table below)
byte 2       packed: high nibble ttl (1..15) · low nibble hops (0..15)
byte 3       payload length n
byte 4-6     message id   (3 raw ASCII bytes — dedup identity)
byte 7-9     src id       (3 ASCII chars, NUL padded)
byte 10-12   dst id       (3 ASCII chars, NUL padded; empty = broadcast)
byte 13..    payload      (max 14 ASCII chars)
```

**Budget math** — the header is `4 + 3 + 3 + 3 = 13` bytes
([`HEADER_SIZE`](../meshlib/src/main/java/com/epics/bitchat/mesh/PacketCodec.java)),
leaving `MAX_PAYLOAD = 27 − 13 = 14` chars for payload in a 31-byte advert
(`PACKET_SIZE = 27`). IDs compress to 3 chars over a 32-symbol alphabet
(no `0/O/1/I`) → 32 768 distinct devices — the compression that made v3
viable (v2 wasted 21 header bytes and capped payloads at 6 chars).

**Kinds** ([`MeshKind`](../meshlib/src/main/java/com/epics/bitchat/mesh/MeshKind.java)):

| Byte | Kind | Purpose | ACKed? |
|------|------|---------|--------|
| 0x00 | `CHAT` | user text | yes, per-message |
| 0x01 | `PING` | liveness | — |
| 0x02 | `ACK` | delivery confirmation (token in payload) | — |
| 0x03 | `BEACON` | venue advert `V:<venue>` or beacon position | never |
| 0x04 | `ANNOUNCE` | internet-ingested server event | never |
| 0x05 | `TICKET` | credential pass to one user | — |
| 0x06 | `NACK` | re-send request for missing fragment frames | — |

`encode()` **throws** on oversize payloads — truncation is never silent;
senders pre-check with [`fitsOnWire`](../meshlib/src/main/java/com/epics/bitchat/mesh/PacketCodec.java)
(venue names that cannot fit produce a visible `DROPPED_TOO_LONG` row).

## 3.3 IDs, tokens, acks

- **Message id**: first 3 chars of the created id (or event-derived — see
  ingest). Sole dedup key inside the router's seen-window (chapter [2.3](02-mesh-engine.md#23-the-flood-router)).
- **ACK token**: [`ackToken(msg)`](../meshlib/src/main/java/com/epics/bitchat/mesh/PacketCodec.java)
  = the same 3 chars, echoed in an ACK payload so the sender can match it
  ([`DeliveryTracker`](../meshlib/src/main/java/com/epics/bitchat/mesh/DeliveryTracker.java)).
- **Node id on wire**: first 3 chars of the 6-char sanitized id; identity
  adverts carry all 6.

## 3.4 Fragment frame (payload layout)

When a payload is a fragment frame it has the shape:

```
<group:3><index:1><total:1><text>
```

Index/total use a 1-based letter-then-digit encoding (`A`=1 … `Z`=26, `0`=27
… `9`=36). Detection is by decoding offsets 3/4
([`isFragment`](../meshlib/src/main/java/com/epics/bitchat/mesh/PacketCodec.java))
— full treatment in chapter [4.2](04-fragmentation.md#42-frame-format).

**NACK payload** (must never parse as a frame): `<group:3>N<mask:8>` with a
lowercase base-26 bitmask — `isFragment` cannot match it because offset 4
(`N`) and the lowercase mask decode as invalid frame elements (chapter [4.4](04-fragmentation.md#44-nack-recovery-meshtastic-style)).

## 3.5 Limits at a glance

| Limit | Value | Consequence |
|-------|-------|-------------|
| `MAX_PAYLOAD` | 14 chars | plain message ceiling |
| `FRAMING_OVERHEAD` | 5 | frame prefix cost |
| `FRAGMENT_TEXT_CHARS` | 9 | text per frame |
| `MAX_FRAGMENTS` | 36 | frames per group |
| `MAX_FRAGMENTED_TEXT` | 324 | longest auto-carried text (bigger → visible reject) |
| ttl / hops nibbles | 15 / 15 | flood depth bound |
| `AnnouncementDirectory.MAX_TEXT` | 6 | demo catalogue rows per entry |

---
Previous: [Mesh engine](02-mesh-engine.md) · Next: [Fragmentation & recovery](04-fragmentation.md)
