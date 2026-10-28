# Bug report — Event Management Hub (BLE mesh)

Tested against `MANUAL_TEST_GUIDE.md` on 3 real phones over wireless debugging:

| Phone | Model | Node id | Serial |
|---|---|---|---|
| A | iQOO I2302 | `4FADKU` | `adb-10BE3M2JXD0004U-FZq0A1._adb-tls-connect._tcp` |
| B | vivo V2502i | `G47J4B` | `adb-10BF4H1AGX000N2-SFVqKm._adb-tls-connect._tcp` |
| C | Samsung SM-M156B | `ZU4G78` | `adb-RZCX827X9DP-4Mam2j (2)._adb-tls-connect._tcp` |

Evidence channel was `uiautomator dump` + `logcat`. A temporary wire-trace
instrumentation was added to `BleAdvertiserTransport` to prove the transport
layer, then **removed** — the codebase is unchanged and compiles.

---

## 1. Test results

| Test | Verdict | Notes |
|---|---|---|
| STEP 0 setup (3 phones) | **PASS** | names, ids, `transport_mode=ble`, relay ON, auto-announce ON all persisted in `shared_prefs/bitchat_mesh.xml` |
| TEST 1 routing (sim) | **PASS** | `(on-device) Dijkstra → standard → 1.2 ms → 128 m → 91 s (5 steps)` with real turn-by-turn steps |
| TEST 1 routing (real) | expected fail | `SocketTimeoutException: failed to connect to /10.0.2.2 (port 8000) after 4000ms` — ai-service not running. See B11 |
| TEST 2 positioning (sim) | **PASS** | AUTO-FLICKER runs, RSSI updates live, `Estimated position: Hall 1` |
| TEST 2 positioning (real) | **PASS** | `LIVE BLE PEERS … Phone -39 … Phone -50`, then `no estimate` / `no anchors in range` — correct, peers advertise as phones not beacons |
| TEST 5 BLE pair | **FAIL (reporting)** | the message *was* delivered; only the confirmation is broken — see B1/B3 |
| TEST 6 hop/relay | **PARTIAL** | relaying works (`relayed 9 · dups 8 · max hops 5`) but true multi-hop cannot be staged with all 3 phones in one room — see B13 |
| TEST 7 mesh venue nav | **FAIL** | venue name truncated `Hall 1` → `Hall` (B6); Navigate tab shows "No venues heard" despite a populated directory (B5) |
| TEST 8 offline cache | **PARTIAL** | cache works (`restored 1 cached venue adverts for offline navigation`) but the UI never shows it (B5) |
| TEST 3 recommendations (sim) | **PASS** | 5 ranked items with `interest/popularity/recency` breakdown |
| TEST 3 offline | **FAIL** | `served from cache: (cache empty)`, `cache: 0 events, 0 recommendations` — see B12 |
| TEST 4 assistant (sim) | **PASS** | `intent=registration` → real answer body; `intent=navigate matched_faq=none` |
| TEST 4 assistant (real) | expected fail | same connection refused to `10.0.2.2:8000` |

### What demonstrably works

- **Peer discovery over real BLE is solid.** All 3 phones discovered the other two at −39 to −84 dBm.
- **The packet codec is correct on the wire.** 25-byte CHAT and 27-byte BEACON packets were transmitted and the peer interpreted them as `MESSAGE` — the packet-size limit is *not* being hit.
- **Flood-relay routing works.** Phone B: `relayed 9 · delivered 12 · dups 8 · ttl-drops 0 · no-relay drops 0 · max hops 5`. Venue adverts propagated to both B and C.
- **Router loop prevention works** — duplicates are counted and dropped (`DROPPED_DUP`).
- **Venue directory persistence works** — `restored 1 cached venue adverts for offline navigation` after a force-stop.
- **All four engine screens work in simulated mode** (routing, positioning, recommendations, assistant).
- **Every server-dependent test fails gracefully** with a readable message; nothing crashed in any test.

---

## 2. Bugs

### B1 — Delivery ACK can never match, so every send reports "out of range" (CRITICAL)

`MeshNode.java:79-83`

```java
if (message.getKind() == MeshKind.ACK) {
    deliveryTracker.ack(PacketCodec.ackToken(message), System.currentTimeMillis());
    fireDeliveryStatus(PacketCodec.ackToken(message), message.getSrcId(), true);
    return;
}
```

`ackToken(message)` derives a token from the **ACK packet's own** msgId. The
token the sender is waiting for is carried in the ACK's **payload** — that is
what `sendAck` puts there (`MeshNode.java:297-302`):

```java
MeshMessage ack = MeshMessage.create(nodeId, message.getSrcId(), MeshKind.ACK,
        PacketCodec.ackToken(message));
```

`DeliveryTracker.ack()` is an exact `Map.remove` (`DeliveryTracker.java:57-59`),
so it never matches. Every send therefore survives 12 s and is expired by
`sweep()`, producing `fireDeliveryStatus(..., false)` → **"Phone is out of range"
even when the peer received the message and ACKed it**.

Proof from the session: Phone B's hop stats read `delivered 3 · sent 3 · max hops 1`
while Phone A's TEST 5 status read `Phone is out of range`.

**Fix**

```java
if (message.getKind() == MeshKind.ACK) {
    String token = message.getPayload();   // the token echoed back by the peer
    if (deliveryTracker.ack(token, System.currentTimeMillis())) {
        fireDeliveryStatus(token, message.getSrcId(), true);
    }
    return;
}
```

`PacketCodec.ackToken` itself is fine — `wireMsgId()` (`PacketCodec.java:171-178`)
reproduces exactly what `decode()` reconstructs, so sender and receiver agree on
the token. Only the extraction site is wrong.

---

### B2 — "Delivered" is reported without verifying the match

`MeshNode.java:80` discards the boolean returned by `deliveryTracker.ack(...)`,
so `fireDeliveryStatus(..., true)` fires for *any* ACK — including a stale or
duplicate one, and even when the token matched nothing in the pending map.

**Fix:** gate the status on the return value (same edit as B1).

---

### B3 — The traffic console is permanently blank on every device (CRITICAL)

`MeshNode` never fires `onLogEntry`. It has `fireDelivered`, `firePeerDiscovered`,
`firePeerLost`, `firePositionUpdated`, `fireVenueDirectoryChanged`,
`fireDeliveryStatus` (`MeshNode.java:399-433`) but **no `fireLog`** — and it never
registers itself as a `MessageLog.Listener` (`MessageLog.addListener`,
`MessageLog.java:50`, is never called from main source). Therefore:

- `MeshManager.onLogEntry` (`MeshManager.java:477`) is dead code
- `MeshViewModel.onLogEntry` → `refreshLogs()` (`MeshViewModel.java:201-204`) never runs after construction
- the `logs` LiveData keeps its initial `Collections.emptyList()` forever
- `ConsoleFragment`'s RecyclerView (`ConsoleFragment.java:59`) never receives a row

Observed: the Console tab showed zero rows on all 3 phones immediately after a
real send, on a freshly restarted app.

**Fix** — in the `MeshNode` constructor, next to `transport.setListener(this)`:

```java
log.addListener(new MessageLog.Listener() {
    @Override public void onNewEntry(MeshLogEntry entry) {
        for (MeshEventListener l : listeners) { l.onLogEntry(entry); }
    }
});
```

and hold a reference so it can be removed in `stop()` (otherwise the log keeps
the node alive). Cheaper alternative if you do not want the new plumbing: call
`refreshLogs()` from the existing 2 s `peerRefresher` in `MeshViewModel` (line 60-66).

---

### B4 — The receiving phone's pair-test transcript never updates

`BlePairTestActivity` reads the log directly (`renderTranscript()`,
`BlePairTestActivity.java:188`), but it is only re-invoked from `onCreate`
(line 68) and from the `getLogs().observe` callback (line 82). Because of B3 that
LiveData never emits, so on the *receiving* phone the transcript is rendered once
while empty and never again — TEST 5's acceptance criterion ("Phone B's
transcript must show RX DELIVERED") can never be met.

**Fix:** B3's fix makes the observer fire. Also add `renderTranscript()` to
`onResume()` so re-opening the screen always shows current state.

---

### B5 — The venue-directory LiveData is never seeded (CRITICAL for offline-first)

The `MeshViewModel` constructor (`MeshViewModel.java:68-76`) calls `refreshLogs()`
and `refreshPeers()` but **never pushes an initial venue directory**.
`venueDirectory` starts as `Collections.emptyList()` and is only ever written by
`onVenueDirectoryChanged()` (line 239-242), which `MeshNode` fires **only for
newly observed venue adverts** (`MeshNode.java:71-76`, guarded by
`venueDirectory.observe(...)` returning `true`).

Consequence: after any Activity re-creation — rotation, process restart, or just
returning from a test screen — the Navigate tab shows
`No venues heard over the mesh yet` even though the directory is populated, and
stays wrong until some phone advertises a venue it has never advertised before.

Observed directly: Phone B's Navigate tab read `No venues heard over the mesh yet`
while its Hop screen simultaneously listed `Hall via 4FADKU · 0 hops`. After a
clean force-stop, logcat said `restored 1 cached venue adverts for offline
navigation` and the Navigate tab *still* said "No venues heard".

**Fix**

```java
private void refreshVenues() {
    MeshNode node = meshManager.getMeshNode();
    if (node != null) {
        venueDirectory.postValue(node.getVenueDirectory().entries());
    }
}
```

Call it from the constructor and from the existing `peerRefresher` runnable.

---

### B6 — Every message is silently truncated to 6 characters (CRITICAL)

`PacketCodec.java:34, 102-103`

```java
public static final int MAX_PAYLOAD = 6;
...
int n = Math.min(payload.length, MAX_PAYLOAD);
```

The cap is silent: no error, no log, no UI warning, and the sender's UI still
shows the full text it "sent".

Observed:

- chat `"meet at Hall 2"` → 27-byte wire = 21-byte header + 6 → the peer receives `"meet a"`
- venue advert `"V:Hall 1"` → Phone B's venue directory recorded **`Hall`**, not `Hall 1`
  (Hop screen: `Hall via 4FADKU · 0 hops`)

Real venue names and real messages are therefore unusable. This is the single
biggest functional defect for the product.

**Fix** — in preference order for a BLE-only design:

1. **Fragment across adverts.** The header wastes 21 of the 27 usable bytes. Compress it: 6-byte src + 6-byte dst → 3+3, pack `hops`+`ttl` into one byte, and shrink the 4-byte msgId to 3. That buys ~10 bytes → `MAX_PAYLOAD` ~16, plus room for the (B7) safety margin.
2. **Rendezvous + GATT handoff** — advertise a small packet (`src`, `dst`, `msgId`, `rssi`) and push the full body over a GATT characteristic. The `PacketCodec` javadoc (`PacketCodec.java:21-24`) already names this as the planned extension. It is required regardless for ticket sharing and any payload over 27 bytes.
3. **Minimum stop-gap:** make `MeshNode.send` / `broadcast` refuse over-long payloads and surface it (`return false` + a log entry `TX failed: payload 12 > max 6`) so the app never lies to the user.

---

### B7 — `PacketCodec.PACKET_SIZE` is wrong, and there is zero size headroom

`PacketCodec.java:35`

```java
public static final int PACKET_SIZE = 3 + 4 + NODE_ID_LEN * 2 + 1 + MAX_PAYLOAD;  // = 26
```

The real header is **21 bytes** (`version`, `kind`, `hops`, `ttl`, `msgId[4]`,
`src[6]`, `dst[6]`, `n`) and the real maximum packet is **27** bytes — which the
class's own javadoc states on line 21. The `3` should be `4`. Confirmed on the
wire: 27-byte packets were observed. Any future size guard written against
`PACKET_SIZE` would be off by one.

Note also that 27 bytes is exactly `31 − 4` (legacy advertising PDU minus the
manufacturer-specific-data record overhead). There is **no headroom at all** —
one extra header byte and every packet silently fails to advertise (see B8).

**Fix:** correct the constant, derive it from the real header length
(`HEADER_SIZE = 4 + 4 + NODE_ID_LEN * 2 + 1`, `PACKET_SIZE = HEADER_SIZE + MAX_PAYLOAD`),
and add a unit test asserting `encode(msg).length == PACKET_SIZE` for a
max-length payload.

---

### B8 — Advertise failures are swallowed, so a failed send looks like an out-of-range peer

`BleAdvertiserTransport`, `AdvertiseCallback.onStartFailure` is `// ignore`.
`ADVERTISE_FAILED_DATA_TOO_LARGE` (3) is permanent for that payload — nothing is
ever transmitted — yet the user sees the send sit at `[IN_FLIGHT]` and then time
out as "out of range", exactly like a genuine range failure.

**Fix:** log `errorCode` and `packet.length`, and push a `TX failed: DATA_TOO_LARGE`
entry into the traffic log so the cause is visible. (This is the diagnostic that
proved the packets *were* going out, and it should stay in the product.)

---

### B9 — The BLE scan is started exactly once and never re-armed (CRITICAL for a background mesh)

`BleAdvertiserTransport.startScan()` is called only from `start()` (line 157).
`onScanFailed` is `// ignore transient scan failures` (lines 151-153) with **no
retry, no backoff and no watchdog**. `identityRefresh` (lines 167-177) re-advertises
but never re-arms the scan. Nothing re-arms it on `BluetoothAdapter` state changes
or on the host activity resuming.

Consequences:

- Bluetooth off → on leaves the node permanently deaf while `running` is still `true`, so nothing notices.
- Any `onScanFailed` (e.g. `SCAN_FAILED_APPLICATION_REGISTRATION_FAILED`, or OS scan throttling when the app leaves the foreground on Android 12+) kills the node permanently, with no error shown.
- **Observed:** after ~25 min of running, A and B both received **zero** BLE packets for roughly 4 minutes with both apps alive. A clean restart fixed it immediately. No crash, no log line.

For a product whose premise is unattended background operation this is the
highest-severity robustness defect.

**Fix**

- Retry on failure with backoff: `onScanFailed` → `handler.postDelayed(this::startScan, 2000)`.
- Add a stall watchdog to the existing maintenance loop: if no scan result has arrived for N seconds, `stopScan()` + `startScan()`.
- Register a `BroadcastReceiver` for `BluetoothAdapter.ACTION_STATE_ON` and re-arm; also re-arm from the host activity's `onStart`.
- Surface a "mesh link stalled" state in the UI instead of failing silently.

---

### B10 — Quick-send chips always target an arbitrary peer

`ConsoleFragment.java:86-95`

```java
List<String> targets = viewModel.getReachableIds();
String dst = targets.get(0);
viewModel.send(dst, MeshKind.CHAT, payload);
```

The chips ("hi", "meet at Hall 2", "ping") ignore the destination spinner the
user can see on screen and always send to whichever peer happens to be first in
`getReachableIds()`. Users cannot tell who they are talking to, and the demo
behaviour is non-deterministic.

**Fix:** share the FAB dialog's destination selection with the chips, or default
the chips to broadcast (`dstId = ""`) so the target is unambiguous.

---

### B11 — The server base URL defaults to the emulator loopback alias

Every "REAL TEST (SERVER)" screen and the ai-service config default to
`http://10.0.2.2:8000` (visible as `ai-service base URL (emulator: http://10.0.2.2:8000)`
on TEST 3). `10.0.2.2` only resolves on the Android emulator, so on a real phone
the real test can never pass without editing the URL, and the error
(`failed to connect to /10.0.2.2`) misleads you into diagnosing a network problem
on the phone.

**Fix:** pre-fill the host machine's LAN IP per device, or discover it at runtime
(mDNS / `ConnectivityManager`), and label the field with the resolved address.

---

### B12 — The recommendation cache can never be populated without the server

TEST 3's simulated path runs the on-device scorer but never writes to the cache;
the cache is only filled from the "REAL TEST (SERVER)" `/recommend` response.
With no server, `SIMULATE OFFLINE` + `Simulated test` returns
`served from cache: (cache empty)` and the label reads
`cache: 0 events, 0 recommendations (empty)`.

Offline recommendations are therefore not merely untested — they are structurally
unreachable in the server-less deployment that is the target architecture.

**Fix:** have the simulated path persist its results into the same cache (the
local catalogue *is* the authoritative offline source), so `SIMULATE OFFLINE`
exercises a genuine cache read.

---

### B13 — TEST 6 multi-hop cannot be staged with three phones in one room (test-plan gap)

All three phones sit at −39 to −84 dBm of each other, so every packet is a
direct 1-hop delivery. Phone B's first hop-stats reading was `relayed 0`, which
is indistinguishable from "relay is switched off". A and C must be physically
separated (roughly >20 m, or a wall between) with B in between — the assistant
cannot do that remotely, so this test needs a human.

**Fix:** make the hop observable without moving phones — add a "simulate direct
link loss" toggle per peer in the Hop test that suppresses direct reception of a
chosen node's packets. Then a directed A→C send must show `relayed ≥ 1` on B and
`hops = 2` on C, which is assertable in a scripted test.

---

### B14 — Turning off relay also stops the phone receiving messages

`Router.onReceive` (`Router.java:111-118`): when `relayEnabled` is false the
message is dropped with `DROPPED_NO_RELAY` **without being delivered locally** —
even when this phone is the destination. So the setting labelled
"Relay other users' messages (act as a hop)" also breaks receiving, which is
surely not the intent.

**Fix:** deliver locally first, then skip only the onward flood:

```java
if (broadcast || isForMe(message)) { /* deliver */ }
if (!relayEnabled) { return; }   // only the onward flood is skipped
```

---

### B15 — The test suite cannot be automated (blocks CI)

All test activities are `exported="false"` in `AndroidManifest.xml`, so the whole
guide must be driven by hand. This session had to use `uiautomator dump` plus
coordinate taps. Combined with B3 (blank console) and B5 (blank venue list) the
tests are effectively un-verifiable and un-runnable in CI.

**Fix:** add a debug-only entry point (an `--ez autoRun true` intent extra, or a
`debug` build variant that exports the test activities) so a scripted `adb` smoke
test can run STEP 0 / TEST 5 / TEST 6 unattended on emulators.

---

### B16 — Venue cache churn and no staleness indicator

`persistVenueDirectory` (`MeshManager.java:403-419`) rewrites the whole
SharedPreferences JSON on **every** venue change, from the mesh thread. With 50
attendees advertising every 15 s that is a lot of `apply()` churn. The Navigate
tab shows up to 6 venues with no age indicator, so a 2-minute-TTL entry looks
identical to a fresh one.

**Fix:** throttle persistence to at most once every ~30 s, render "last heard
Xs ago", and drop entries past their TTL when rendering.

---

## 3. Suggested order of work

1. **B1 + B2** — one small edit; makes TEST 5 truthful and delivery confirmable.
2. **B3 + B4** — restores all traffic visibility; without it nothing else is diagnosable.
3. **B5** — one small edit; makes offline venue navigation (the core product promise) actually usable.
4. **B6 + B7** — the header compression / GATT handoff. This is the real engineering task and gates messaging, venue names and ticket sharing.
5. **B9** — scan watchdog, before any unattended/field use.
6. **B14, B10, B12, B11** — quick correctness/usability fixes.
7. **B13, B15, B16** — test-plan and hardening work.
8. Per the offline-first product direction, **delete TESTS 3 and 4** (recommendations and assistant) rather than fixing B11/B12 for them.
