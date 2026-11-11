# Router dedup defect and stable message identity - analysis and design

Status (updated after review):
- **Router fix R3: IMPLEMENTED** in `meshlib/.../Router.java` (JVM-tested only, NOT validated on devices). See section 8.
- **C3 (sync identity): approved as a DIRECTION only.** Nothing about the window length, clientId format, schema,
  migration or endpoint is frozen. See section 9 for what must be documented before Phase 5.
- **R4 tracked** for reconsideration after R3 device results. **R5/R6 (wire widening) are separate future protocol
  decisions**, not implemented.
- Sections 1-7 below are the pre-implementation analysis and are kept as written; where the implementation
  differs (the ANNOUNCE exception) section 8 says so.
Source of the mesh code: offline-backend @ 7daad04 (imported into `meshlib/`; `Router.java` is now modified there).

## 1. Regression test

`meshlib/src/test/java/com/epics/bitchat/mesh/RouterIdentityLossTest.java` (9 tests, no production change).

| Test | Kind | Result today |
|---|---|---|
| `knownDefect_wireIdSpaceIsOnly4096` | characterization | pass (200k ids -> <=4096 distinct) |
| `knownDefect_deliveriesAreCappedAtTheIdSpace` | characterization | pass (20,000 distinct msgs -> <=4096 delivered, <50%) |
| `knownDefect_deliveryStopsCompletelyOnceTheSetIsSaturated` | characterization | pass (after 60k, 500 more distinct msgs -> 0 delivered) |
| `knownDefect_sameWireIdFromDifferentSendersIsDroppedAsDuplicate` | characterization | pass (sender and payload ignored) |
| `knownDefect_twoDevicesWithTheSameSourceIdCannotHearEachOther` | characterization | pass (twin src classified SELF) |
| `recoveryRebuildingTheRouterClearsTheSeenSet` | characterization | pass |
| `echoOfTheSameMessageStillDedups` | guard (must stay true after any fix) | pass |
| `ownSendIsStoredUnderTheFullUuid...` | characterization | pass |
| `desired_distinctMessagesAreAllDelivered` | desired behaviour | skipped by default; with `-Dmeshlib.expectDedupFix=true` it FAILS: `expected:<20000> but was:<4061>` |

Suite: `:meshlib:test` = 159 tests, 0 failures, 1 skipped (was 150). The `knownDefect_*` tests are
meant to be inverted on purpose when a fix lands.

## 2. How Router identity interacts with echoes, relays and fragments (read from source)

- **Key.** `Router.onReceive` (Router.java:79-91) uses `message.getMsgId()` alone; LRU cap 4096; hits do
  not expire. Wire id = first 3 chars of a UUID string => hex => 4,096 values. With only 4,096 possible
  keys the LRU can never evict, so once all are seen every new message is dropped until the Router is rebuilt.
- **Restart.** `MeshManager` builds a new `MeshNode` (and so a new `Router`) on every mesh start
  (MeshManager.java:168, 189), so saturation lasts for one mesh session, not forever. How long a session
  takes to saturate depends on real traffic, which is unmeasured on devices (section 6).
- **Own echo.** `onLocalSend` stores the FULL 36-char UUID; the echo returns with the 3-char wire id, so
  the Router itself would not recognise it. It never gets that far on BLE: `BlePacketInterpreter` drops
  any packet whose `srcId` equals the local id (BlePacketInterpreter.java:73-76) and the simulator does
  the same (SimulatedTransport.java:284). Side effect (tested): two installs with the same 3-char source
  id are deaf to each other. P(any two share an id) is 14% at 100 installs and 46% at 200.
- **Relay.** `relayed()` keeps `msgId`, changes ttl/hops. So (src, id, kind, dst, payload) is identical
  on every copy; hops/ttl must stay OUT of any dedup key.
- **Fragments.** Each frame is its own `MeshMessage` with its own random 3-hex id (9 frames -> 9 ids),
  so every frame is exposed to the false drop. But `MeshNode.onPacketReceived` offers each frame to
  `FragmentAssembler` BEFORE `router.onReceive` (MeshNode.java:731-733), so text reassembly does not
  depend on the Router: a falsely-dropped frame still counts toward the group. What is lost is the
  frame's delivery log entry and, importantly, its onward relay from that phone (reach shrinks).
  The assembler keys groups by `sender + "/" + groupId` (15-bit group id, FNV over src|text|epochMs) and
  keeps a completed group as a tombstone until the 30 s group TTL sweep.
- **Non-fragment messages** (<=14 chars: short chat, `V:Hall 1` beacons) are delivered only through the
  Router sink, so they are the ones actually lost to a false drop.
- **ACK tokens** (`ackToken`, 3 chars) live in the same 12-bit space; I did not analyse `DeliveryTracker`
  collisions. Listed as a follow-up, not covered here.

## 3. Router fix options (receiver-local unless stated)

Prototype in `docs/measurements/DedupPolicySim.java` (output: `dedup-policy-sim.txt`). It simulates one
hour, one receiver hearing everything, a beacon every 10 s per phone, chat ~1/min from a 10-phrase set,
and every message heard 3 times (original + 2 relay echoes). Wire ids are 12-bit, source ids come from
the real 32^3 space. Not a device measurement; it models the key policy only.

False drops of NEW messages (message loss), % of originals:

| Phones (msgs/s) | P0 current | P1 id only + 120 s expiry | P2 (src,id) + expiry | P3 (src,id,kind,dst,payload) + expiry |
|---|---|---|---|---|
| 10 (1.2) | 37.5% | 3.5% | 0.34% | 0.19% |
| 30 (3.5) | 69.0% | 10.1% | 0.45% | 0.27% |
| 100 (11.7) | 90.3% | 28.7% | 0.33% | 0.22% |
| 300 (35.0) | 96.8% | 63.6% | 0.33% | 0.24% |

False NEW (an echo delivered twice) was 0% in every policy once hits refresh the entry. My first run
of the prototype showed a non-zero value because a hit did NOT refresh its timestamp, and echoes of a
late duplicate then crossed the expiry; the fix needs sliding expiry (refresh on every sighting).
Remaining P3 drops are mostly a phone's periodic identical beacon repeating the same 12-bit id inside
the window (harmless: the next beacon follows). Identical chat text repeated with the same id is
~1/4096 per repeat pair.

| Option | Change | Wire change | Fixes | Residual risk | Old phones |
|---|---|---|---|---|---|
| R0 do nothing | - | none | nothing | loss grows with traffic; 4,096 messages per session ends delivery | unchanged |
| R1 id-only + expiry | Router entries expire | none | saturation | still collapses with crowd size (29% at 100 phones) | unaffected |
| R2 key (src,id) + expiry | | none | saturation and crowd scaling | same-sender id repeats; twin sources | unaffected |
| **R3 key (src,id,kind,dst,payload) + sliding 120 s expiry, keep cap** | Router only | none | saturation, crowd scaling, same-sender repeats of different text | ~0.2% periodic duplicates; twin sources still deaf (separate issue) | unaffected; each phone fixes itself |
| R4 R3 + sender counter nonce (random start, +1 per message) | MeshMessage.create | none (still 3 hex chars) | removes same-sender id reuse for 4,096 msgs | changes sender behaviour, needs device test; wraps | opaque id to old receivers |
| R5 wider id (new wire version) | PacketCodec | **yes** | the root cause | old phones decode only v3 and would ignore v4 (decode returns null on unknown version) so a mixed fleet needs dual transmit and more airtime in a 27-byte advert | breaks / doubles traffic |
| R6 widen src id | MeshManager + wire | **yes** | twin deafness | same compatibility problem; regeneration of stored ids | breaks |

**Recommendation: R3.** It changes only `Router` (key, expiry, refresh-on-hit), touches no wire bytes,
and is compatible with every existing phone in both directions: a new phone simply stops dropping
messages an old phone's Router would have kept. Old phones keep their own old loss. R4 can follow if
device tests show residual same-sender collisions matter. R5/R6 are the long-term fix for source and
message identity and should be a separate protocol-version decision.
Loop safety to be checked on devices: with fewer false drops, new phones relay more floods; TTL/hops
still bound it and the copies of one flood share the same key, so loops are still caught.
Implementation notes if approved: expiry sweep must not run per message on the transport thread in O(n);
flip the `knownDefect_*` tests; add tests for refresh-on-hit and for 'same id, different payload'.

## 4. Stable message identity for sync (clientId) - candidates

Requirements: (1) every gateway that hears one message derives the same identity; (2) repeated identical
messages stay distinct; (3) tolerate source-id collisions; (4) work for fragmented messages;
(5) work for messages sent by phones that are not upgraded; (6) a retried upload is idempotent;
(7) tolerate a gateway uploading hours later (offline) and clock skew; (8) no wire change.

What a receiver can observe: src (3 chars, can collide), wire id (12-bit nonce) or fragment group id
(15-bit nonce), kind, dst, payload or reassembled text, hops/ttl (varies per copy), receive time (varies
per gateway - `decode()` stamps local time; the sender's time is NOT on the wire), and nothing else.

| | Candidate | (1) | (2) | (3) | (4) | (5) | (6) | (7) | Verdict |
|---|---|---|---|---|---|---|---|---|---|
| C1 | hash(src, text, time bucket) | fails at bucket edges | merges repeats in a bucket | no | ok | ok | ok | skew breaks it | rejected |
| C2 | hash(src, nonce, kind, dst, text), no time | yes | merges any repeat that re-draws the same 12-bit nonce, ever (100 'ok' messages from one phone -> ~1 false merge) | weak | ok | ok | ok | ok | rejected as the only key |
| **C3** | **observation id + server-side merge** (below) | yes | distinct by nonce; same-nonce repeats merged only inside window W | key includes src; provenance marked unverified | ok | ok | exact | skew corrected at upload | **recommended** |
| C4 | sender-assigned random 128-bit id carried in the message | yes | yes | yes | needs payload bytes | only upgraded phones; wire/payload change | ok | ok | needs a wire change; long-term option |
| C5 | upload own messages only (no relays) | yes | yes | yes | yes | needs upgraded originator | ok | ok | conflicts with decision D5; fallback only |

### C3 in detail (proposal, not frozen)

Two separate problems, solved separately:
1. **Retry idempotency (exact).** Each gateway generates `obsId` = random UUID when it first queues an
   observation. Server unique key `(gateway_user_id, obsId)`. A retried upload can never duplicate.
2. **Cross-gateway merge (bounded probability).** Each observation also carries the observable fields:
   `src3`, `nonce` (wire id, or group id for a fragmented message), `kind`, `dst`, `text` (payload, or the
   fully reassembled text - partial groups are never uploaded), and `heardAt` (gateway clock). The server
   computes `wireKey = H(src3 | nonce | kind | dst | text)` and attaches the observation to an existing
   message with the same `wireKey` whose first-heard time is within W of this `heardAt`, otherwise creates
   a new message. W is a sliding window (proposal 10 min), not a bucket, so there is no boundary problem.
3. **Authoritative path for upgraded originators.** A phone uploading its OWN message also sends a new
   per-install random UUID (`authorInstallId`) and its full 36-char msgId. Server unique key
   `(authorInstallId, fullMsgId)`; the wire fields are included too, so relay observations of the same
   message merge into it. This removes source-id collisions and same-nonce repeats for every message
   whose originator is upgraded and online at some point.
4. **Clock skew.** The client sends its `nowAtUpload`; the server converts `heardAt` using
   `server_now - client_now` measured at upload time. Drift while offline is not corrected.
5. **Fragments.** The identity uses the reassembled text and the group id; frame ids are ignored (they are
   random per frame). Gateways that missed a frame upload nothing; another gateway's upload is merged.
6. **Source collisions.** Two installs with the same `src3` are not distinguishable from relays; the row
   stores `src3` as provenance text, never as an account, and merges only if nonce, kind, dst and text also
   match within W.

Residual risks of C3 (quantified where I can): same text + same nonce + same src within W from a
non-upgraded originator merges (~1/4096 per repeat pair; negligible for chat, harmless for beacons which
are not synced); a gateway whose clock is wrong by more than W after offline drift can create a duplicate
row (visible, not silent); text must be byte-identical across gateways (sender sanitises once, receivers
get the sanitised string - not verified end to end on devices).
Compatibility: no wire change; old phones can be originators or relays; the server change is additive.
Unchanged requirement from Phase 2: do not write migration 011 or the sync endpoint until this is approved.

## 5. Compatibility risks (summary)
- R3 is receiver-local and wire-neutral. It changes how often phones relay. Must be device-tested for flood
  storms with a mixed old/new fleet.
- Twin source ids remain a deafness problem under R3 (only R6, a protocol decision, fixes it).
- Sync (C3) depends on text sanitisation being identical everywhere and on fragment reassembly completing.
- Adding `authorInstallId` is a new persisted value on the phone (SharedPreferences).

## 6. Device tests needed before implementing (BLE is only proved on phones)
1. Baseline soak on 3+ phones, current build, with a counter of `RelayStats.droppedDup` and delivered
   counts, to measure real distinct messages/hour and the time to saturation (no data exists yet).
2. After R3: same soak, confirm delivery stays flat past 4,096 messages and that relays/airtime do not blow up.
3. Mixed fleet: one old build and one R3 build, both directions, short chat, fragmented chat, beacons.
4. Flood-loop check with 3+ phones in a line (A-B-C), TTL limits observed, no repeat delivery.
5. Fragmented message across a relay where one frame is deliberately lost, NACK recovery still completes.
6. Twin source id: force the same device id on two phones (prefs), confirm the documented deafness, so the
   decision on R6 is made with evidence.
7. Gateway test for C3 (Phase 5 scope): two phones hearing one message, upload both, one row; offline
   gateway uploads later; clock skew injected.

## 7. Decisions needed from you
1. Approve R3 (Router key + sliding expiry + refresh-on-hit) as the fix to implement, or choose another option.
2. Approve C3 as the direction for message identity (obsId + server merge + authoritative path), with W to be
   confirmed after the soak test.
3. Should R4 (sender counter nonce) be considered later, and should R5/R6 (protocol widening) be scheduled
   as a separate decision?

## 8. R3 as implemented (meshlib bugfix; separate from sync)

Changed file: `meshlib/src/main/java/com/epics/bitchat/mesh/Router.java` (no other production file, no wire change).

- **Key** = source + wire id + kind + destination + payload. Hops/ttl are excluded.
- **Expiry:** sliding 120 s (`DEFAULT_SEEN_TTL_MS`), refreshed on EVERY sighting, including repeats and local sends,
  bounded by an absolute 600 s from first sighting (`DEFAULT_SEEN_MAX_AGE_MS`) and by the existing 4,096-entry LRU cap.
  The 120 s/600 s values are the proposal's numbers, unvalidated on devices. Sweep is amortised O(1) (the map is
  ordered by last sighting).
- **`hasSeen(wireId)`** (used by `MeshNode.ingestFromInternet`) is preserved through a wire-id reference index;
  it ignores entries whose sliding TTL has expired. An entry kept alive past the max age by constant repeats is
  dropped on the next lookup of its key but can still answer `hasSeen(id)` until then (edge case, documented not fixed).
- **`onLocalSend` is now `synchronized`.** It was not, while `onReceive` was, and the table is an access-ordered
  `LinkedHashMap` (reads reorder it), so the old code could race. No lock-order issue found by reading the code
  (`MeshNode`'s only synchronized methods are `start`/`stop`); not stress-tested.
- **Behaviour preserved:** a repeat is logged once as DROPPED_DUP, later repeats are silent; relayed copies dedup; a
  new `MeshNode` still builds a fresh Router.

### Deviation from the approved R3, and why
The approved key included the source. Implemented exactly that way, an existing test fails:
`BroadcastFanoutTest.twoInternetIngestsOfSameEventFloodOneIdOnly`. Reason: internet-ingested server events
(`MeshKind.ANNOUNCE`) deliberately use one deterministic wire id on every phone, but each phone floods them under
its OWN source id, and the router is meant to collapse those copies (`MeshNode.ingestFromInternet`, InternetIngestTest).
A source-inclusive key would make every receiver deliver and relay an ingested event once per ingesting phone.
**Approved by the project owner** as part of R3. Its purpose is solely to preserve deduplication of
internet-ingested events. Regression coverage: `BroadcastFanoutTest.twoInternetIngestsOfSameEventFloodOneIdOnly`
(pre-existing), `InternetIngestTest`, and `RouterDedupExpiryTest.announceKindIgnoresTheSourceSoInternetIngestCopiesStillCollapse`;
the rationale is also in the `Router` class Javadoc.
**Therefore `ANNOUNCE` messages are keyed WITHOUT the source** (id + kind + dst + payload). `ANNOUNCE` is sent only by
the ingest path (grep of meshlib). Everything else uses the full key. This is a conscious, easily reverted choice;
flagged for review. (Verified by mutation: removing the exception fails that test and
`RouterDedupExpiryTest.announceKindIgnoresTheSourceSoInternetIngestCopiesStillCollapse`.)

### Tests (JVM only)
- `RouterIdentityLossTest` (9): characterization kept for what the fix does NOT change (wire id still 4,096 values,
  twin source ids still deaf, own-echo handling, relayed-copy dedup, fresh Router on restart); former `knownDefect_*`
  now assert the fixed behaviour; `desired_distinctMessagesAreAllDelivered` now runs by default and passes
  (20,000 of 20,000 delivered; it failed with 4,061 before).
- `RouterDedupExpiryTest` (13, injected clock): differing payload/kind/destination are different messages;
  relayed copies with other hops/ttl are the same; ANNOUNCE ignores source; repeat within window is a duplicate;
  expiry after TTL; refresh-on-every-repeat (late echo at 200 s stays a duplicate); max age bounds refreshing;
  DROPPED_DUP counted once; `hasSeen` follows live entries and expiry; LRU cap keeps the id index consistent; reset.
- Mutation checks: no-refresh and source-in-ANNOUNCE each fail their tests.
- Results: `:meshlib:test` 172 tests (150 original + 22 new), 0 failures, 0 skipped; `:app:testDebugUnitTest` 110, 0 failures.
- `docs/measurements/router-after-r3.txt`: the original measurement re-run on the new Router (delivered 99.8% at N=500,
  98.3% at N=4,096, 96.7% at N=50,000 with a deliberately harsh constant-payload/29-source workload that has an expected
  ~3.4% key-clash rate; before: 94%, 63%, 8%).

### What is NOT fixed / NOT proven
- **Twin source ids** (two installs sharing a 3-char id never hear each other; 14% chance somewhere among 100 installs)
  remain an open protocol issue (R6).
- The 12-bit wire id itself is unchanged (R5). Residual false drops: identical src+id+kind+dst+payload inside the
  window, ~1/4096 per repeat pair (mostly a phone's own periodic beacon).
- Fragment-frame relays, ACK-token collisions (`DeliveryTracker`), and the FragmentAssembler's per-sender group
  keys for two ingesting phones (frames reassemble per sender) were not analysed or changed.
- **No BLE/device validation.** Whether flood storms, airtime or relay load change with fewer false drops is unknown.
- **Behaviour change to confirm (noticed while planning device tests):** the memory used by internet-ingest dedup is now
  bounded. `MeshNode.ingestFromInternet` asks `Router.hasSeen(wireId)`; before R3 an id stayed "seen" until the LRU
  evicted it (effectively indefinitely in a quiet mesh), now it is forgotten 120 s after its last sighting (600 s at most).
  A phone that receives the same server event minutes later over its own connection will therefore flood it again and
  receivers may show it a second time. Not changed; to be accepted or rejected after the T4 observation.

### Required BLE/device checks before this is considered validated
(Detailed procedures, pass/fail criteria and a blank results template: `R3_DEVICE_VALIDATION_PLAN.md`. Nothing in it has been run.)
1. Baseline soak (old build) on 3+ phones: count `RelayStats` dropped-dup vs delivered for a long session to find the
   real message rate and time to saturation (no device data exists).
2. Same soak on the R3 build: delivery stays flat past 4,096 messages; relay count and airtime do not blow up.
3. Mixed fleet (one old, one R3 build) in both directions: short chat, fragmented chat (>14 chars), venue beacons.
4. Three phones in a line (A-B-C) with TTL limits: no repeat delivery, no loop.
5. Internet ingest from two phones for the same event: receivers must still show/relay it once (the ANNOUNCE exception).
6. Fragmented message with a deliberately lost frame: NACK recovery still completes.
7. Two phones forced to the same device id: confirm the documented deafness (informs R6).

## 9. Before Phase 5: sync-identity documentation owed (not written yet, nothing frozen)
C3 stays a direction: gateway observation ids for exact retry safety; server-side cross-gateway matching only as a
probabilistic mechanism for older phones. Before any Phase 5 work, document how matching behaves for:
1. delayed (offline) uploads, 2. clock skew between gateways and the server, 3. concurrent uploads of one message by
several gateways (race on the server), 4. repeated identical messages (same text, same or re-drawn id).
Not frozen: merge-window length, clientId format, schema, migration, endpoint.

## 10. Tracked later options
- R4 (sender counter nonce): reconsider after the R3 device results.
- R5 (wider wire id) and R6 (wider source id): separate future protocol decisions because of mixed-version compatibility.
