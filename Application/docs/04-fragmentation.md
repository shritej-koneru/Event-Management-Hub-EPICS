# 4 · Fragmentation & Recovery

> Previous: [Wire protocol](03-protocol.md) · Next: [Positioning & navigation](05-positioning-navigation.md)

A 14-char advert cannot carry a sentence, so long texts are **split
automatically, flooded frame-by-frame, and rebuilt on every receiver** —
mirroring BitChat's whitepaper approach (§4.4) with a Meshtastic-style NACK
layer for lost frames.

## 4.1 Send path — auto-splitting

Callers never fragment manually: [`MeshNode.send`](../meshlib/src/main/java/com/epics/bitchat/mesh/MeshNode.java)
routes any text over 14 chars into `sendFragmentsWithGroup`:

1. [`PacketCodec.fragment(text, groupId)`](../meshlib/src/main/java/com/epics/bitchat/mesh/PacketCodec.java)
   sanitizes to printable ASCII, splits greedily **at spaces** (cutting
   *before* the space so no chunk overflows; hard cuts as fallback) into ≤9-char
   parts — `FRAGMENT_TEXT_CHARS = MAX_PAYLOAD − FRAMING_OVERHEAD = 9`.
2. More than `MAX_FRAGMENTS = 36` parts → the text cannot be carried; the
   send fails visibly (`DROPPED_TOO_LONG` row; the UI shows the 324-char cap).
3. Each part becomes a frame (chapter [4.2](#42-frame-format)) queued in
   `pendingFragments`.
4. The **group id** is tracked once in the `DeliveryTracker` — the whole
   stream completes with a single token.
5. [`flushFragments`](../meshlib/src/main/java/com/epics/bitchat/mesh/MeshNode.java)
   sends frames `FRAGMENT_INTERVAL_MS = 400` apart. The gap matters: a
   phone's back-to-back adverts overflow receiver scan buffers, so spacing
   is *loss prevention*. A test seam (`setFragmentIntervalForTest(0)`) makes
   flushing synchronous for deterministic tests.

## 4.2 Frame format

```
<group:3><index:1><total:1><text≤9>
```

- **group** — 3 chars from the 32-symbol alphabet, minted by FNV-1a over
  `srcId|text|epochMs` ([`fragmentGroupId`](../meshlib/src/main/java/com/epics/bitchat/mesh/PacketCodec.java)):
  the epoch salt makes every user action fresh; the src|text prefix keeps
  the id stable across the rare two-phone ingest race.
- **index / total** — 1-based, letter-then-digit (`A`…`Z`, then `0`…`9` up to 36).
- **Detection** — [`isFragment`](../meshlib/src/main/java/com/epics/bitchat/mesh/PacketCodec.java)
  decodes chars at offsets 3 and 4 as frame elements. (An earlier bug read
  offsets 1/2 and misfired; regression-tested.)
- Each frame is its own flood message with its own id, so relay dedup works
  per-frame and receivers buffer out-of-order arrivals.

## 4.3 Reassembly — FragmentAssembler

[`FragmentAssembler.offer`](../meshlib/src/main/java/com/epics/bitchat/mesh/FragmentAssembler.java)
is keyed by `sender/groupId`:

- Frames buffer into a `TreeMap<Integer, String>` — order-correct reassembly
  regardless of arrival order; duplicate frames `putIfAbsent` harmlessly.
- **Completion fires exactly once** (`onTextAssembled(sender, groupId, kind, text)`).
  The completed group stays as a **tombstone**: the flood's own echoes
  re-deliver the frames later, and without the tombstone the text would
  rebuild and fire twice. The TTL sweep evicts it once echoes die.
- Kind-aware: only `CHAT` completion triggers the group-token ACK back to
  the sender (chapter [2.6](02-mesh-engine.md#26-acknowledgements-and-nacks));
  `ANNOUNCE` (server events) never ACKs.
- `MAX_GROUPS = 64` buffer cap; oversized floods absorb frames rather than
  mis-assembling.

`MeshNode` pushes partial frames nowhere — the app sink only ever sees the
complete text, so no partial text and no per-frame ACK noise ever surfaces.

## 4.4 NACK recovery (Meshtastic-style)

Frame loss over a real room's BLE spectrum is *normal*. Instead of silently
dropping incomplete groups, the receiver asks for exactly what is missing —
the pattern Meshtastic formalizes (explicit ACK/NAK with retransmission):

**Receiver** ([`FragmentAssembler.nackSweep`](../meshlib/src/main/java/com/epics/bitchat/mesh/FragmentAssembler.java),
called on a 2 s maintenance tick):
- waits `NACK_AFTER_MS = 2 000` grace (frames are still 400 ms apart in flight),
- then fires `onGroupIncomplete(sender, groupId, missingIndices, received, total)`
  — at most `MAX_NACK_ROUNDS = 3` rounds, `NACK_RETRY_MS = 6 000` apart,
  so a dead sender is never NACK-stormed;
- `MeshNode` encodes the missing indices into a 36-bit mask → 12-char payload
  `<group:3>N<mask:8>` ([`makeNackPayload`](../meshlib/src/main/java/com/epics/bitchat/mesh/PacketCodec.java)),
  sent **unicast** to the original sender (`MeshKind.NACK`). The mask
  alphabet is lowercase so a NACK can never decode as a fragment frame.

**Sender**: recently sent groups are cached 60 s (16 groups max). On NACK,
[`resendMissingFrames`](../meshlib/src/main/java/com/epics/bitchat/mesh/MeshNode.java)
re-floods exactly the requested frames **with fresh message ids** — relays
that already deduped the originals would otherwise swallow the recovery
copies before they could reach whoever asked. Receivers that already
finished absorb the copies into their tombstones silently.

**If all rounds fail** the group expires at `GROUP_TTL_MS = 30 000` with a
visible console row: `announcement incomplete: X/Y packets - text dropped`
(`onGroupExpired`) — the last resort is loud, never silent.

## 4.5 Ingest and deterministic frame ids

Fragmented [`ingestFromInternet`](../meshlib/src/main/java/com/epics/bitchat/mesh/MeshNode.java)
payloads derive the group id *and* every frame id from the server event id
(`wireMsgIdFor(groupId + "#" + index)`). Two phones that both ingest the
same event therefore flood **identical frames**, and the dedup collapses the
second copy mesh-wide — the whole point of deterministic ids. User sends use
random per-frame ids instead (two users sending the same words are two
distinct floods; the epoch-salted group id keeps them separate).

## 4.6 Constants

| Constant | Value | Where |
|----------|-------|-------|
| `FRAMING_OVERHEAD` | 5 | [PacketCodec.java](../meshlib/src/main/java/com/epics/bitchat/mesh/PacketCodec.java) |
| `FRAGMENT_TEXT_CHARS` | 9 | [PacketCodec.java](../meshlib/src/main/java/com/epics/bitchat/mesh/PacketCodec.java) |
| `MAX_FRAGMENTS` / `MAX_FRAGMENTED_TEXT` | 36 / 324 | [PacketCodec.java](../meshlib/src/main/java/com/epics/bitchat/mesh/PacketCodec.java) |
| `FRAGMENT_INTERVAL_MS` | 400 | [MeshNode.java](../meshlib/src/main/java/com/epics/bitchat/mesh/MeshNode.java) |
| `GROUP_TTL_MS` | 30 000 | [FragmentAssembler.java](../meshlib/src/main/java/com/epics/bitchat/mesh/FragmentAssembler.java) |
| `NACK_AFTER_MS` / `NACK_RETRY_MS` / `MAX_NACK_ROUNDS` | 2 000 / 6 000 / 3 | [FragmentAssembler.java](../meshlib/src/main/java/com/epics/bitchat/mesh/FragmentAssembler.java) |
| `FRAGMENT_CACHE_TTL_MS` | 60 000 | [MeshNode.java](../meshlib/src/main/java/com/epics/bitchat/mesh/MeshNode.java) |
| `NACK_MASK_CHARS` / `NACK_MASK_BITS` | 8 / 36 | [PacketCodec.java](../meshlib/src/main/java/com/epics/bitchat/mesh/PacketCodec.java) |

**Tests**: [`MessageFragmentationTest`](../meshlib/src/test/java/com/epics/bitchat/mesh/MessageFragmentationTest.java) (10),
[`FragmentAssemblerTest`](../meshlib/src/test/java/com/epics/bitchat/mesh/FragmentAssemblerTest.java) (4),
[`FragmentRecoveryTest`](../meshlib/src/test/java/com/epics/bitchat/mesh/FragmentRecoveryTest.java) (4) —
walkthrough in chapter [7.2](07-testing.md#72-unit-tests).

---
Previous: [Wire protocol](03-protocol.md) · Next: [Positioning & navigation](05-positioning-navigation.md)
