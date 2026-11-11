# R3 Router fix - BLE / device validation plan and results template

**STATUS: multi-device tests T1-T6 are NOT RUN (blocked: need more phones). The only run so far is a single-phone smoke check
(install, start, local counters, one local ingest, sender side): [`device-results/2026-11-09_single-phone_R3.md`](device-results/2026-11-09_single-phone_R3.md).
It is NOT BLE validation of R3. The results template below is intentionally blank.**
Written against: `merged/meshlib/.../Router.java` (R3) and offline-backend @ 7daad04 (old behaviour).
Related: `ROUTER_DEDUP_AND_MESSAGE_IDENTITY.md` (design, section 8), `Application/MANUAL_TEST_GUIDE.md` (existing 3-phone guide).
Documentation-only change: no application code, build file or test was modified to write this.

Why this exists: the R3 fix is proven only by JVM tests (`RouterIdentityLossTest`, `RouterDedupExpiryTest`). JVM tests say
nothing about real BLE (loss, duplicate scan callbacks, OEM scan throttling, airtime). Do not call R3 validated until
section 6 is filled in from real runs.

---

## 0. Decide before you start (to avoid reading results into the thresholds)
- Every pass/fail threshold below is a **proposal**. Confirm or change them first, write the final values in the run header,
  then run. Do not adjust thresholds after seeing numbers.
- Run order bias: BLE conditions drift. For T1 run old and R3 in alternating blocks if time allows (old, R3, old), or at
  minimum repeat the first build at the end as a control.

## 1. Prerequisites (nothing is assumed available)

| Need | Used by | Status on the machine this plan was written on | If missing |
|---|---|---|---|
| 3+ Android phones, Android 8+ (guide used iQOO I2302, vivo V2502i, Samsung SM-M156B, all Android 16) | all tests | `adb devices` showed **no device attached** | Tests cannot run. T3/T4 need 3; T2 needs 2 builds on 2 phones (3 for relay); T1 needs 3, 5+ strongly preferred (see 5.1 pacing) |
| A 4th (and 5th) phone | T3/T4 optional extras, T1 pace | unknown | T1 runs slower with fewer phones |
| `adb` (platform-tools) on the PC | all | adb 1.0.41 present at `%LOCALAPPDATA%\Android\Sdk\platform-tools` | install platform-tools |
| Wireless debugging or USB per phone | all | unknown | USB works; use a hub; chargers for long soaks |
| JDK 17 + Android SDK platform 34 | building the two APKs | present; **recipe below built both APKs on 2026-11-09** (not installed anywhere) | install, or get prebuilt APKs from a teammate and record their SHA-256 |
| **Debug builds** (not release) | T4 (adb ingest), T6 (`run-as`), exported test activities | n/a | release builds keep `IngestReceiver` non-exported and are not `run-as`-able, so T4/T6 are **blocked** on release |
| Phones with screens kept on, charged/plugged in, battery optimisation off for the app | all | n/a | existing guide: screen lock suspends unfiltered BLE scans on these OEM builds, which would be mistaken for the bug |
| A metal tin / shielding bag, or a way to place a phone at marginal range | T5 | n/a | T5 can only be done opportunistically (see T5) |
| Spreadsheet/notebook, stopwatch, PC clock | all | n/a | use the paper template in section 6 |
| BLE sniffer (e.g. nRF52 + Wireshark, Ellisys) | true airtime | **not assumed** | airtime is only a proxy (section 2); a sniffer is optional |
| Server access / FCM | T4 | **not needed**: the offline app has no server bridge, ingest is injected with `adb shell am broadcast` | T4 therefore does NOT test a real server push |

## 2. Facts from the code that shape the procedures (read, not measured)

| Fact | Source | Consequence for testing |
|---|---|---|
| Counters shown on the Hop test screen: `relayed, delivered, sent, dups, ttl-drops, no-relay drops, max hops` | `RelayStats.summary()`, `HopTestActivity` (row 4 in the Test Hub) | These are the only unbounded counters. Record them |
| **No UI control resets the counters.** A new `MeshNode` (restart mesh) creates a new Router and new stats | `MeshManager` builds `new MeshNode` per start | Zero point = Settings -> **Save and restart mesh**. Record counters right after, as t0 |
| Console tab = last **500** entries only, in memory, no export | `MessageLog` default 500; `ConsoleFragment` has no export | For long runs use counters + screenshots, not the Console. Rows scroll away; screenshot the moment you need one |
| No per-message logging to logcat; logcat has transport events only (tag `BitChatMesh`): `BLE transport started`, `scan stall detected - re-arming BLE scan`, `BLE transport recovered`, `send dropped - transport not usable`, `peer discovered` (Debug), `ingest ...` | `BleAdvertiserTransport`, `IngestReceiver` | logcat shows radio health, not delivery |
| **No real airtime/transmission counter.** `sent` = messages the router originated; `relayed` = messages it forwarded; the transport re-blasts each message at a 150 ms cadence | `RelayStats`, `BLAST_INTERVAL_MS = 150` | Airtime proxy = `sent + relayed` per hour per phone. A sniffer is needed for real airtime |
| Old defect signature is **silent**: a falsely dropped message is returned before any log row and (after the first repeat of that id) with no counter change | old `Router.onReceive` | After saturation a new message from a nearby phone produces **no Console row and no counter movement**. Use a "canary" (below) to detect it |
| Debug build only: `IngestReceiver` exported, test activities exported, app is `run-as`-able | `app/src/debug/AndroidManifest.xml` | T4/T6 need debug APKs |
| Both builds report versionName 1.0 / versionCode 1; there is **no in-app build marker** | `app/build.gradle` | Identify the build by APK content (section 3) and keep a phone->build table |
| Fragment frames are 400 ms apart; NACK first fires ~2 s after a gap, retries every 6 s, max 3 rounds; incomplete groups expire at 30 s | `FRAGMENT_INTERVAL_MS`, `FragmentAssembler` constants | T5 timing windows |
| Console strings: `missing k/N packets - requesting re-send` (receiver, NACK, TX); `resending k missing packet(s) of group <g>` (sender); `announcement incomplete: x/N packets - text dropped` (DROPPED_LOSS) | `MeshNode` lines ~200-230, ~673 | Look for these in T5 |
| App has **no hook to drop a chosen frame**; the simulator link-loss toggle exists only for the simulated transport, not BLE | `HopTestActivity` | T5 loss must be induced physically |
| Device id: SharedPreferences file `bitchat_mesh`, key `device_id`, 3 chars from `ABCDEFGHJKLMNPQRSTUVWXYZ23456789`; a value of any other length is regenerated; the id is read once per process | `MeshManager` | T6 procedure; force-stop before touching the file |

## 3. Building and identifying the two builds

**Old build = offline-backend @ 7daad04, untouched.  R3 build = the same tree with exactly one file replaced:
`meshlib/src/main/java/com/epics/bitchat/mesh/Router.java` from `merged/`.** Only that file differs (verified by blob hash),
so any behavioural difference is the Router. Do **not** use `merged/Application/` (stale copy, missing later fixes).

Recipe (bash; verified to produce both debug APKs on this machine on 2026-11-09, JDK 17 + SDK 34):
```
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"  ANDROID_HOME="$LOCALAPPDATA/Android/Sdk"
FA="<path to>/Final App"; W=<scratch dir, outside the repo>
mkdir -p $W/old $W/r3
git -C "$FA/offline backend" archive 7daad04 Application | tar -x -C $W/old --strip-components=1
git -C "$FA/offline backend" archive 7daad04 Application | tar -x -C $W/r3  --strip-components=1
cp "$FA/merged/meshlib/src/main/java/com/epics/bitchat/mesh/Router.java" \
   $W/r3/meshlib/src/main/java/com/epics/bitchat/mesh/Router.java
(cd $W/old && ./gradlew :app:assembleDebug)   # -> app/build/outputs/apk/debug/app-debug.apk
(cd $W/r3  && ./gradlew :app:assembleDebug)
```
Record for each APK: SHA-256, the Router.java git blob (old `19041f54...`, R3 `2d3ed1a9...` at the time of writing), the
date, and the `merged` working-tree state (the R3 Router is **uncommitted**; if it changes, rebuild and re-record).

Install / switch builds without losing data or the device id (both are debug-signed with the same local key):
`adb -s <serial> install -r <apk>`. `-r` keeps app data (node name, device id, settings). Do not uninstall between builds
unless a test says so: uninstall regenerates the device id.

Identify the build actually on a phone (do this at the start of every run and write it in the table):
```
adb -s <serial> shell pm path com.epics.bitchat          # prints package:/data/app/.../base.apk
adb -s <serial> pull <that path> installed.apk
python -c "import zipfile,sys;z=zipfile.ZipFile('installed.apk');print(any(b'idRefs' in z.read(n) for n in z.namelist() if n.endswith('.dex')))"
```
`True` = R3 build, `False` = old build (the string `idRefs` exists only in the R3 Router; confirmed against both APKs built
for this plan). This is the only reliable marker, since the app shows no build label.

## 4. Common setup and recording kit (applies to every test)

Per phone, once (from the existing guide, STEP 0): Node name `Phone A`/`B`/`C`..., Transport = **Real BLE advertisements**,
Relay other users' messages ON, Auto-announce per test, **Save and restart mesh**. Bluetooth + location + nearby-devices
permissions granted. Screen timeout >= 10 min or `adb shell svc power stayon true` (check the syntax on your Android version)
while plugged in. App in the foreground on the screen you are recording.

Phone/build table (fill in; one per run):

| Label | Model / Android | adb serial | Build (old/R3, from the APK check) | Device id (3 chars) | Node name |
|---|---|---|---|---|---|

How to record, in order of preference:
1. **Hop screen counters** (Settings -> Open test hub -> row 4): read `relayed / delivered / sent / dups / ttl-drops /
   no-relay drops / max hops`, plus the peer lines and the VENUE DIRECTORY. Screenshot at each checkpoint, or automate with a
   UI dump (UNTESTED helper, resource ids not verified, Hop screen must be in the foreground):
   `adb -s <s> shell uiautomator dump /sdcard/ui.xml` then `adb -s <s> pull /sdcard/ui.xml ui_<label>_<HHMM>.xml`; the text
   `HOP STATS (this phone)` and the summary line are in the XML. Screenshot fallback:
   `adb -s <s> exec-out screencap -p > shot_<label>_<HHMM>.png` (run from Git Bash/cmd, **not** PowerShell `>`, which re-encodes).
2. **logcat per phone** for radio health: `adb -s <s> logcat -c` at t0, then `adb -s <s> logcat -v time -s BitChatMesh:* AndroidRuntime:E > <label>.log`
   (Git Bash/cmd). Check at the end for `FATAL`, `scan stall`, `recovered`, `send dropped`.
3. **Console tab** only for short tests (last 500 rows). Screenshot the rows you rely on before they scroll off.
4. Write the **PC wall-clock time** next to every reading; phone clocks are not trusted.

Counter zero point: Settings -> Save and restart mesh, then immediately record the counters as t0 (should read all zeros).
Restarting also builds a fresh Router, so it clears the old defect: never restart mid-soak unless the test says so.

**Canary (used in T1):** a unicast chat of <=14 chars with a unique payload (e.g. `c01`, `c02`...) sent from one phone to
another via the Send tab; success = the sender shows `Delivered to <name>` within 12 s. A message dropped by the old defect
is dropped silently at the receiver, so no ACK comes back. Always send canaries between phones you have just confirmed are
in range (pair test) so a failure means the Router, not distance.

Ground-truth counts: `sent` at the originating phone is how many messages it originated (incl. ACKs/NACKs). Do not infer
"messages heard" from the receiver's own counters alone; compare to the senders' `sent`.

### 4a. Practical notes learned on the first single-phone run (2026-11-09, Samsung SM-M156B, Android 16)
- If the phone has Secure Folder (second user), plain `pm` commands fail with `SecurityException ... user 150`: add `--user 0` (also for `install`, `pm grant`, `pm path`).
- Git Bash rewrites `/sdcard/...` into `C:/Program Files/Git/sdcard/...`: run `export MSYS_NO_PATHCONV=1` and use Windows-style local paths (`E:/...`) for `adb pull` and redirects.
- Phone and PC clocks differed by ~37 s; correlate adb broadcasts with logcat using the phone clock (`adb shell date`).
- `uiautomator dump` + `pull` does return the `HOP STATS` line from the Hop screen on this phone (the helper in section 4 works for that screen).
- `adb shell am start -n com.epics.bitchat/.ui.test.HopTestActivity` opens the Hop screen directly on a debug build; `KEYCODE_BACK` returns to the Console without restarting the mesh.
- On the node that ingests an event, the Console shows `[IN_FLIGHT]` then `[RELAYED]` rows (the 5 s rebroadcast window, ~1 s apart). Those are its own re-sends; the `relayed` counter does not move. Do not count them as relay activity in T3/T4.
- Advert start/stop churn in logcat (`Stop AdvertingSet`) is frequent while sending; it is not by itself an error.

---

## 5. Tests

Naming: **A, B, C, D, E** = phones; **old / R3** = build. Thresholds are proposals (section 0).

### T1 - Soak: baseline (old) vs R3, 3+ phones   [checks: messages heard, delivered, duplicate drops, relays, airtime proxy, time to slowdown]

**Setup / topology.** 3-5 phones, all within ~2 m of each other (full mesh, no relay needed), screens on, plugged in, same
spot for both runs, nobody walking between them. Run 1: all phones old. Run 2: all phones R3 (switch with `install -r`). Ideally
repeat Run 1 at the end as a control.
Traffic: every phone announces a venue (Hop screen -> venue input -> `Hall 1`/`Hall 2`/`Cafeteria`/..., **Announce**), which re-floods
about every 5 s (existing guide; verify from the `sent` rate). No other sending except canaries. Auto-announce stays as configured.

**Pacing (so you know how long it takes).** Old defect needs ~4,096 distinct messages heard per phone. Measure first, do not
guess: after 10 min compute rate_i = delta(sent_i)/delta(t) per phone; distinct messages one phone can hear = sum of the others' rates.
Time to 4,096 = 4096 / (sum of others' rates). Plan a run length of **1.5x that or 4 h, whichever is shorter** and write the
estimate in the run header. (Under the unverified assumption of one announce per 5 s per phone, 5 phones need roughly 85 min and
3 phones roughly 2.8 h.) Fewer phones => longer soak.

**Actions.**
1. Build/identify/install (section 3). Phone table filled. Restart mesh on all phones; record t0 counters.
2. Pair test A<->B, B<->C, A<->C once to confirm everyone hears everyone (guide TEST 3). Start logcat on all.
3. All phones Announce a venue. Note the PC time.
4. Every 10 min: read the Hop counters on every phone (screenshot or dump), and send **canaries**: A->B, B->C, C->A (3 pings, unique payloads), note Delivered/not.
5. Continue until the stop condition (sum of others' delta-`sent` >= 6,000 per phone, or 4 h, or a clear slowdown plus 30 more min).
6. At the end: counters, logcat, one final canary round, then **restart the mesh on one phone** and canary again (old defect should recover on restart; record).

**Record per phone per checkpoint** (table in 6.1): PC time, `relayed, delivered, sent, dups, ttl-drops, no-relay, max hops`, canary results, notes.
Derived per 10-min window: delivery ratio = delta(delivered_X) / sum over others of delta(sent_i); relay and airtime proxy = delta(sent)+delta(relayed);
dup rate = delta(dups). **Time to slowdown** = first window where delivery ratio < 70% of the median of the first 3 windows OR canary success < 50%
while pair test still passes; also record the cumulative sum of others' `sent` at that moment.

**Expected (from code reading, UNVERIFIED on devices).** Old: delivery ratio falls toward zero once ~4,096 distinct messages
have been heard, with a silent plateau (counters flat, no new RX rows, canaries fail), and recovery after mesh restart. R3: ratio stays
roughly flat; canaries keep passing. Old behaviour may differ on real radios (own sends also occupy the old 4,096-entry table), so the
baseline run is what actually tells you; do not assume it will reproduce the JVM numbers.

**Pass (R3):** across the whole run (a) every 10-min delivery ratio >= 70% of that run's first-3-window median; (b) canary success >= 90% in
every window; (c) no restart needed; (d) `relayed` and `sent` per window not more than ~1.5x the old build's under the same traffic
(no relay blow-up); (e) no `FATAL`, no unexplained `send dropped`. **Fail:** any sustained slowdown as defined above, a relay/airtime
increase beyond (d), or crashes. **Inconclusive:** the old run never slowed down (then the soak was too short or traffic too low: extend, add phones, or use T1B).

**T1B - optional accelerated probe (old-id-space effect via ingest, debug builds).** From one phone inject distinct events at <=1/s:
`adb -s <P> shell am broadcast -n com.epics.bitchat/.mesh.IngestReceiver -a com.epics.bitchat.INGEST -e event ev$i -e payload T$i` in a loop (payload <=14 chars, no spaces), with the other
phones receiving, **Auto-announce off, no venue announced**, so `delivered` counts only these events. Ingest wire ids come from a
32,768-value space, so by arithmetic the OLD Router should drop about k/32768 of new events once its 4,096-entry table fills (~3% around
1,000 events, up to ~12% after 4,096+); R3 keys ANNOUNCE by id+payload so should drop ~0%. This is a prediction from id-space maths, **not
measured**, and BLE loss of a few % can mask the low end. Keep the rate low enough that `sent` shows the radio is not the bottleneck;
run old and R3 under the same conditions. Record events injected (loop counter) vs each receiver's delta(`delivered`).

**Cleanup.** Stop loops/logcat; restart mesh on all phones (fresh Router); clear venue announce; restore the build you want to leave installed;
unplug. **Limitations:** no real airtime counter; Console rows scroll off; BLE conditions drift between runs; counters only restart with the mesh.

### T2 - Mixed fleet: old and R3, both directions   [short chat, fragmented chat, venue beacons]

**Setup.** Minimum 2 phones (P-old, P-R3); 3 for relay (below). Controls first: an **all-old** pair and an **all-R3** pair run the same
trials to get the baseline BLE success rate for this room. Then P-old<->P-R3. Direct range (~1-2 m). Restart mesh on all before each series.
Relay series: chain A-B-C (placement as in the guide: A and C out of range of each other, B between) with (A old, B R3, C old) and
(A R3, B old, C R3).

**Actions (per direction: old->R3 and R3->old; 10 trials each, unique payloads):**
1. Short chat: Send tab, destination = the other phone, payload `ping01`...`ping10` (<=14 chars). Record `Delivered` within 12 s? and receiver's Console row count for that payload.
2. Fragmented chat: 43-char payload, e.g. `The quick brown fox jumps over the lazy dog` (about 5 frames; payload text must differ per trial, e.g. change the first word). Record: complete text received once and equal to the sent text; sender shows `Delivered`; any `announcement incomplete` row.
3. Venue beacon: sender Announce `Hall 1`; record seconds until the receiver's VENUE DIRECTORY shows `Hall 1 via <id>`; repeat with `Cafeteria`; test `Lecture Block A` (>14 chars with `V:` prefix; it fragments) once.
4. Relay series: A->C with the three kinds across B; record `Delivered` at A (ACK came back through B), B's `relayed` delta, C's `hops=1`.
5. Read counters on every phone before/after each series.

**Record:** per direction and kind: successes/10, mean time-to-delivery, duplicates (same payload shown as DELIVERED more than once), counter deltas, control results.

**Pass:** for each direction and kind, success >= 9/10 **and** not worse than the all-old control by more than 1 trial; **0** duplicate DELIVERED rows for one payload; fragmented text identical to what was sent; venue appears <= 30 s; relay series ACK >= 9/10. **Fail:** any direction/kind clearly worse than control, duplicates, corrupted text, or one build unable to hear the other.
**Cleanup:** restore both phones to the build you intend to keep; restart mesh. **Limitations:** BLE loss is real, so the controls are what make 9/10 meaningful; with only 2 phones you cannot test relay.

### T3 - Chain A-B-C: delivery, loops, repeat delivery

**Setup.** Phones A, B, C all **R3** (repeat once all-old for comparison). A and C in different rooms/max distance so only B hears both; confirm first: A->C pair test should **fail** directly, A->B and B->C pass. All screens on, Relay ON everywhere, no one announcing venues, no other traffic. Restart mesh on all; counters t0.

**Actions.** On A, Hop screen -> **Real flood** x20, about 10 s apart (note each tap time; or read A's delta `sent` for ground truth). Then 10 directed A->C chats `hop01..hop10` (Send tab) with ACK. Then reverse C->A x5. After each phase read counters on all three.

**Record:** per phone delta of `delivered, relayed, dups, ttl-drops, max hops`; A's delta `sent` (ground truth N); Console rows for one flood (screenshot A, B, C within the same minute); directed-chat results.

**Pass (loop / repeat delivery checks):** let N = A's delta-`sent` for the flood phase. B: `delivered <= N`, `relayed <= N`; C: `delivered <= N`; A: `delivered` of those floods = 0 (own echoes are filtered by source id); `ttl-drops` = 0 on all (non-zero in a 2-hop chain suggests loops/long paths); `max hops` at C is 1 (>=1, no more than 2); no payload shown DELIVERED twice on any phone; directed ACK >= 9/10 each way. Dup counts are recorded, not pass/fail, but should be of the same order as the old build's (a large jump is a flag). **Fail:** any `delivered` or `relayed` above N, any repeat DELIVERED row, any ttl-drop.
**Cleanup:** none persistent; restart mesh. **Limitations:** BLE receives the same advert many times, so `dups` is not an exact per-message count; use delivered/relayed vs `sent` instead.

### T4 - Internet ingest through two phones: receivers show and relay once   [ANNOUNCE exception]

**Setup.** **Debug builds on all phones** (R3; repeat all-old for comparison). Phones P1, P2 = ingesters; R1, R2 = receivers placed within range of **both** P1 and P2 (and R1 also in range of R2 to see relay effects). Auto-announce off, no venue announced, Relay ON. Restart mesh; counters t0. (No server involved: events are injected locally with adb; this does **not** exercise any real FCM/WebSocket bridge, which this app does not contain.)

**Actions.** Short events (payload <=14 chars) `E01..E10`, ids distinct:
1. Simultaneous: run both commands at the same time (two terminals or `&`):
   `adb -s <P1> shell am broadcast -n com.epics.bitchat/.mesh.IngestReceiver -a com.epics.bitchat.INGEST -e event E01 -e payload NOTICE-01` and the same for `<P2>`.
   **Use `-n` (explicit component) and a payload with no spaces**: on the first single-phone run the implicit form was enqueued but never delivered to the
   receiver, and a space in the payload made `adb shell` treat the rest as a package name.
2. Staggered: P1 first, P2 about 1 s later (P2's logcat should say `ingest E.. (<wireId>) -> ignored: event id already seen (no re-flood)` if it already heard P1's flood).
3. Late re-ingest (behaviour change to confirm): ingest E01 again on P2 about **3 minutes** after step 1. R3 forgets after 120 s sliding (max 600 s), so this should flood again and receivers may show it a second time; the old Router remembered until its table evicted it. Record what happens; this is **not** pass/fail but a documented consequence to review.
4. Long variant (observe only): a >14-char payload, e.g. `Venue change: Hall 2 moved to Hall 1`, from both ingesters. The receiver's reassembler keys groups per sender, so per code reading the text could be assembled once per ingesting phone; record whether it appears once or twice. **Hypothesis only, never verified; independent of R3 (the assembler runs before the Router).**

**Record:** per receiver, delta `delivered`/`relayed`/`dups` per event, Console rows (screenshot) showing how many DELIVERED rows per event; P1/P2 logcat `ingest` lines.

**Pass (steps 1-2, short events):** for every event each receiver's `delivered` rises by exactly **1** (not 2), shows **one** DELIVERED row, and each relaying receiver's `relayed` rises by exactly **1**. **Fail:** any receiver delivers or relays an event twice (would mean the ANNOUNCE source-key exception is not working on real BLE). Steps 3-4: record only.
**Cleanup:** none persistent (no app data written except the venue-advert cache, untouched here); restart mesh. **Limitations:** broadcast is injected locally; two adb commands are not truly simultaneous (a few hundred ms); BLE loss can make a count 0 instead of 1, so repeat and report loss separately from duplicates.

### T5 - Fragment recovery: lose one frame, NACK completes

**Setup.** Sender S and receiver R, both R3 debug builds (repeat with mixed S/R builds), direct range ~1-2 m to start, screens on, logcat on both. Optional relay phone. Restart mesh on all.
**The app cannot drop a chosen frame.** Choose one loss method and record which (all are stochastic and uncontrolled; none verified):
- (a) **Shielding:** R inside a metal tin/shielding bag for a short moment during the stream (frames are 400 ms apart; a ~100-char text is ~12 frames, ~5 s). A 1-3 frame gap is the goal.
- (b) **Marginal range:** put R at a distance/obstruction where some frames are lost; repeat until a partial group appears.
- (c) Avoid toggling Bluetooth/airplane on R: the app falls back to the simulator and rebuilds the node, which discards reassembly state and is **not** a clean test.

**Actions.** From S send a unique ~100-char text (letters/spaces only) to R via Send tab; repeat >=10 times per method, new text each time; watch both Console tabs and screenshot the key rows.
**Record per trial:** loss method; whether R shows `missing k/N packets - requesting re-send` (NACK TX) and when; whether S shows `resending k missing packet(s) of group <g>`; whether R ends with a DELIVERED row whose text equals the sent text, how long it took; any `announcement incomplete: x/N packets - text dropped`; S's Delivered ACK; `FATAL`/stall lines in logcat.
**Pass:** in **>= 3 trials where a NACK actually occurred**, R3 completes the text (identical, shown once) and S gets Delivered in >= 80% of them, with no corrupted/partial text ever shown. Trials with no NACK are "no loss", not passes. Recovery frames use fresh ids so they should not be treated as duplicates by R3; any recovered group that never completes while S is in range is a **fail** worth investigating. **Inconclusive:** no NACK ever observed (loss method too gentle).
**Cleanup:** remove R from shielding; restart mesh. **Limitations:** loss cannot be targeted to exactly one frame; timing of NACK rounds (~2 s first, 6 s retry, 3 rounds, 30 s expiry) means a sender out of range when the NACK is sent will never recover; manual observation of Console rows is required.

### T6 - Twin source id: document the expected deafness (characterization, not an R3 pass/fail)

**Safety first.** This temporarily edits a phone's persisted device id. **Modify only ONE phone**: give phone A the **real id of phone B**, so only A needs restoring. Debug build required (`run-as`). Never leave a phone with a test id; do the restoration even if the test is abandoned, and tick the cleanup box.
**Setup.** Phones A, B, C (C = observer with its own real id). R3 or old (record which). All in direct range. Restart mesh; confirm A<->B, A<->C, B<->C normal first (pair tests) and record each phone's real id (logcat `starting mesh in BLE mode: id=XXX`, or Peers screen).

**Actions.**
1. **Backup A's prefs (binary-safe, Git Bash/cmd only):** `adb -s <A> exec-out run-as com.epics.bitchat cat shared_prefs/bitchat_mesh.xml > A_prefs_backup.xml`. Record its SHA-256 and the original `device_id` (`grep device_id A_prefs_backup.xml`; format `<string name="device_id">ABC</string>`). If this fails (release build, no `run-as`), **stop: T6 is BLOCKED**.
2. `adb -s <A> shell am force-stop com.epics.bitchat` (the id is read once per process).
3. Edit only that value to B's id: `adb -s <A> shell "run-as com.epics.bitchat sed -i 's|<string name=\"device_id\">[^<]*</string>|<string name=\"device_id\">BID</string>|' shared_prefs/bitchat_mesh.xml"` (replace `BID` with B's 3-char id; must be 3 chars from the id alphabet or the app regenerates a new one). If `sed` is missing: edit a local copy and `adb push` it to `/data/local/tmp/`, `chmod 644`, then `run-as com.epics.bitchat cp /data/local/tmp/<file> shared_prefs/bitchat_mesh.xml`.
4. Launch the app on A (do not change settings: it rewrites prefs). Confirm logcat shows `starting mesh in BLE mode: id=<B's id>`.
5. Observe for >= 60 s each, screens on: do A and B appear in each other's Send-tab destination/Peers list? Send `twin01..twin05` A->B and B->A (a unicast to the twin's own id would be addressed to yourself, so also broadcast from the Hop screen's **Real flood** each way). Record what B and A do and what C sees (does C list one peer or two, flickering names? does a C->`<id>` message get ACKs from both?).
**Expected (from code reading + the JVM characterization test, UNVERIFIED on devices):** A and B classify each other's adverts/packets as their own (Reason.SELF) and neither discovers nor hears the other. C is expected to see a single node id for both. If the phones *do* hear each other, the assumption is wrong: record exactly what happened.
**Pass/fail:** none; this documents the open protocol issue (R6). Mark "confirmed" / "not confirmed" and attach evidence.

**Restoration (mandatory).**
1. `adb -s <A> shell am force-stop com.epics.bitchat`
2. `adb -s <A> push A_prefs_backup.xml /data/local/tmp/A_prefs_backup.xml` then `adb -s <A> shell chmod 644 /data/local/tmp/A_prefs_backup.xml`
3. `adb -s <A> shell "run-as com.epics.bitchat cp /data/local/tmp/A_prefs_backup.xml shared_prefs/bitchat_mesh.xml"`
4. Relaunch; verify logcat `id=<A's original id>`; re-pull the prefs file and compare SHA-256 with the backup; delete `/data/local/tmp/A_prefs_backup.xml`.
5. Pair test A<->B and A<->C again: normal discovery restored.
**Fallback if restoration fails:** Settings -> Apps -> BitChat Mesh -> Storage -> Clear data (new random id; **loses** node name, settings, venue cache; redo STEP 0), or uninstall/reinstall. Either way A ends with a fresh random id, never the test id; record the new id.
**Limitations:** needs debug build + `run-as`; editing app-private files is an OEM-dependent workaround, not an app feature; a phone running the app while the file is edited will overwrite it.

---

## 6. Results template (copy into `docs/device-results/<date>_R3.md`; fields intentionally empty)

### 6.0 Run header
| Field | Value |
|---|---|
| Date / tester | |
| Phones (label, model, Android, OEM skin, serial) | |
| Builds (APK SHA-256, Router.java blob, merged working-tree note) | |
| Thresholds confirmed before running (initials/time) | |
| Location, distances, interference notes | |
| Screen/battery settings applied | |
| T1 predicted time-to-4096 and planned run length | |

### 6.1 T1 soak log (one table per build run)
| PC time | Phone | relayed | delivered | sent | dups | ttl-drops | no-relay | max hops | canary ok/sent | window delivery ratio | notes |
|---|---|---|---|---|---|---|---|---|---|---|---|

Slowdown found? (yes/no) | at PC time | cumulative others' sent at that point | recovered after restart? | logcat anomalies |

### 6.2 T2 mixed fleet
| Direction | Kind | Trials | Delivered ok | Mean seconds | Duplicates | Text identical? | Control (same kind) ok | Result |
|---|---|---|---|---|---|---|---|---|

### 6.3 T3 chain
| Phone | delta delivered | delta relayed | delta dups | delta ttl-drops | max hops | N (A delta sent) | Result |
|---|---|---|---|---|---|---|---|

### 6.4 T4 ingest
| Event | Mode (simul/stagger/late/long) | Receiver | delta delivered | delta relayed | DELIVERED rows | P2 logcat line | Result |
|---|---|---|---|---|---|---|---|

### 6.5 T5 fragment recovery
| Trial | Loss method | NACK seen (time) | Resend seen | Final text identical? | Seconds to complete | Incomplete/expired row? | Sender ACK | Result |
|---|---|---|---|---|---|---|---|---|

### 6.6 T6 twin id
| Item | Observed |
|---|---|
| A original id / B id / backup SHA-256 | |
| A and B list each other? | |
| Messages A->B / B->A delivered? | |
| What C sees (one peer / two / ACK count) | |
| Restored: logcat id / SHA-256 match / pair tests normal | |
| Phones left on test id? (must be NO) | |

### 6.7 Summary and sign-off
| Test | Run? | Result (pass/fail/inconclusive/blocked) | Evidence files | Notes |
|---|---|---|---|---|
| T1 soak | NOT RUN | | | |
| T1B ingest probe | NOT RUN | | | |
| T2 mixed fleet | NOT RUN | | | |
| T3 chain | NOT RUN | | | |
| T4 ingest | NOT RUN (two-ingester fanout). Single-phone sender-side step only: see `device-results/2026-11-09_single-phone_R3.md` | | | |
| T5 fragment recovery | NOT RUN | | | |
| T6 twin id | NOT RUN | | | |

R3 device-validated? Only if T1-T5 pass with evidence and T6 is documented. Sign-off: ______________ Date: ________

## 7. Limitations that need manual observation or remain unknown
- No exportable log: Console is the last 500 rows and logcat carries no per-message events. Counter readings and screenshots are the record.
- No real airtime measurement without a BLE sniffer; `sent + relayed` is only a proxy.
- No app control to drop a frame or reset counters; both are done by physical means / restarting the mesh.
- No in-app build label; identify builds from the APK.
- BLE loss and OEM scan throttling vary by phone, room and time; controls (all-old, all-R3) and repeated runs are required.
- T4 uses locally injected broadcasts; the offline app has no server bridge, so a real server push is not covered.
- T4/T6 need debug builds. Release builds are not covered by this plan.
- Behaviour change to confirm and accept or reject: ingest dedup memory is now 120 s sliding / 600 s maximum, previously until table eviction.
- Twin source ids (R6) and the 12-bit wire id (R5) remain open protocol issues; this plan documents them, it does not fix them.
