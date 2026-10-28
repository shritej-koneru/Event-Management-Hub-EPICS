# Manual Test Guide — 3-Phone Real BLE Verification

Everything below was verified against the actual code (button labels, timeouts, payload limits) —
no guesswork. Follow top to bottom; total time ≈ 25–30 min.

## What is already done (by me, over wireless ADB)

| Phone | Model | ADB serial | Status |
|---|---|---|---|
| **Phone A** | iQOO I2302 (Android 16) | `adb-10BE3M2JXD0004U-FZq0A1._adb-tls-connect._tcp` | APK installed, perms granted, BT on |
| **Phone B** | vivo V2502i (Android 16) | `adb-10BF4H1AGX000N2-SFVqKm._adb-tls-connect._tcp` | APK installed, perms granted, BT on |
| **Phone C** | Samsung SM-M156B (Android 16) | `adb-RZCX827X9DP-4Mam2j._adb-tls-connect._tcp` | APK installed, perms granted, BT on |

Latest APK contains the full 16-bug fix pass: ACK matching (delivery status is now truthful),
live traffic-console feed, venue-directory seeding (Navigate tab survives restarts), 14-char
payloads (wire v3), BLE scan re-arm watchdog with error surfacing, relay-off fix, quick-send
destination fix, and a throttled venue cache. Recommendations/Assistant test screens were removed
(offline-first pivot).

**2026-10-05 hardware session additions:** the transport now **self-heals** — a transient radio
failure (scan throttling, adapter hiccup) no longer kills it permanently; recovery is attempted
every 10 s and logged (`BLE transport recovered`). A send that cannot leave the radio is now
logged (`send dropped — transport not usable …`) instead of silently vanishing. The pair screen's
input no longer caps at 6 chars (stale pre-v3 limit) — type up to 14.

---

## STEP 0 — One-time setup on each phone (~3 min per phone)

1. Launch **BitChat Mesh**.
2. Bottom nav → **Settings** (rightmost tab).
3. Scroll down and set **Node name**:
   - Phone A → `Phone A`, Phone B → `Phone B`, Phone C → `Phone C`
   - (Set names on all 3 *before* they discover each other, so peer labels are readable.)
4. Under **Transport**, tick **Real BLE advertisements**.
5. Leave both switches ON: **Relay other users' messages** and **Auto-announce my venue**.
6. Tap **Save and restart mesh** (button at the bottom).
7. Sanity check: the Console tab status line should NOT say "simulator". If Bluetooth is off,
   enable it (Settings → Bluetooth) and reopen the app — the app auto-switches to BLE.

✅ **Checkpoint:** Send tab (bottom nav) → Destination spinner should eventually list the other
phones as peers (labels like `Phone B`). Peer discovery takes ~5–15 s. Refresh by leaving/re-entering
the tab.

---

## TEST 3 — BLE pair, direct message (2 phones: A + B)

**Physical:** A and B next to each other (< 2 m). C away/Bluetooth-off to keep the transcript clean.

On **Phone A**:
1. Settings → scroll down → **Open test hub** → row **3 · BLE pair — direct message**.
2. The message field is pre-filled with `ping`. Payloads now carry up to **14 chars** — try
   `meet at Hall 2` (the 6-char truncation bug is fixed; oversize input is rejected with a
   visible console row, never silently cut).
3. Destination spinner → pick **Phone B**. Tap **Refresh** first if Phone B isn't listed.
4. Tap **Real test**.
5. Within a second or two the status line must show:
   `Delivered to Phone B` ✅
   If after **12 seconds** it says the peer is out of range, see Troubleshooting.

On **Phone B** (the receiver) simultaneously:
- In the same pair screen the **transcript** must show an RX line:
  `RX  Phone A  (broadcast)  "ping"  DELIVERED` ✅ — and the transcript now refreshes when you
  leave and re-enter the screen (receiver-side fix).
- (Bonus: repeat in the other direction — send from B to A.)

**Optional C variant:** repeat with A→C while C is nearby (still direct, 0 hops).

---

## TEST 4 — Hop/relay, users as nodes (3 phones: A → B → C)

**Physical placement (the critical part):**
- Phone A and Phone C in **different rooms** (or max distance apart — a few walls/10+ m).
- Phone B **in between**, close enough to both (~5 m from each). Only B may be in range of both.

On **all three phones**: Settings → **Open test hub** → row **4 · Hop / relay — users as nodes**.
Leave the Hop screen open on all 3 (stats refresh every 1 s).

### 4a. Verify relay stats
On **Phone A**: tap **Real flood (2+ phones)**.
- **Phone B** stats within ~6 s: `relayed …` count **increases** and after the flood line shows
  `→ this user acted as a node for other users' data.` ✅
- **Phone C**: its stats show broadcast deliveries (`+N broadcast deliveries heard`) ✅
  — proof the flood crossed B.

### 4b. Directed hop test (the headline proof)
On **Phone A** → bottom nav → **Send** tab:
1. Destination spinner → **Phone C**. (If unsure it's C, check the short id in B's/C's peers list.)
2. Message: `hopme` (≤ 14 chars). Tap **Send**.
3. Status must show `Delivered to Phone C` within **12 s** ✅ — that ACK could only have come
   back through B, i.e. real multi-hop delivery.
4. Meanwhile on **Phone B** (Send tab or pair transcript): you should see the relayed message in
   its traffic log (Console tab shows `RELAY`/RX entries), and B's Hop stats show `relayed ≥ 1`
   with `max hops 1`+.
5. On **Phone C**: Console tab shows the incoming message DELIVERED with `hops=1`.

### 4c. Reverse direction
On **Phone C** → Send tab → destination **Phone A**, message `reply1`, Send.
Expect `Delivered to Phone A` ≤ 12 s, and B's relayed count to grow again ✅

**Hop screen fields, for reference:**
- `HOP STATS (this phone)` — summary `relayed N · delivered N · drops … · max hops N`
- Peer lines like `Phone A (A1) -58 dBm · heard at ≤ 1 hops` now show **live RSSI** per peer —
  with radios at −39…−84 dBm, "relayed 0" only means something next to the signal it came with.
- `VENUE DIRECTORY (from mesh adverts)` — each entry shows its age (`just now` / `N min ago`);
  see Test 7.
- (Simulator only) per-node **link-loss toggles** stage walk-outs-of-range and true multi-hop
  paths on one device.

---

## TEST 7 — Mesh venue navigation across phones

Venue names must exist in the campus graph for positioning: use **`Hall 1`**, **`Hall 2`**,
**`Cafeteria`**. The `V:` prefix + name must fit 14 chars — every graph venue name does now.

Keep the same physical layout (A – B – C, A and C far apart).

1. **Phone A**: Hop screen (test hub row 4) → venue input → type `Hall 1` → tap **Announce**.
2. Within ~5–15 s (re-announce cycle):
   - **Phone B** Hop screen, VENUE DIRECTORY: `Hall 1 via <A's id> · 0 hops · just now` ✅
   - **Phone C** Hop screen: `Hall 1 via <A's id> · 1 hops` ✅ ← learned it *through* B, out of
     direct range. This is the money shot.
3. **Phone C** → bottom nav → **Navigation** tab:
   - `Mesh venues: Hall 1 (1 hops, just now)` line ✅
   - C's position marker on the map jumps to Hall 1 and the label says
     `Hall 1 via mesh venue advert (1 hops)` (blue marker = mesh-sourced) ✅
   - Pick `To: Cafeteria` → **Navigate** → route + compass steps appear ✅
4. (Optional) Announce `Cafeteria` from Phone C and check A/B learn it the other way.

**Belt-and-braces:** the Settings screen "Announce my venue" field does the same thing;
either works.

---

## TEST 8 — Offline venue cache survives restart (2 phones)

1. With venue knowledge flowing (Test 7 state, e.g. C knows `Hall 1` from A), on **Phone C**:
   note the Navigation tab still lists `Hall 1`.
2. **Kill the app** on C (recents → swipe away) **and turn Phone A off (or walk A away)** so no
   fresh advert can arrive.
3. Reopen the app on C. The **Navigation tab lists `Hall 1` again immediately** (with a
   `just now` freshness tag) ✅ — the directory LiveData is now seeded from the restored cache,
   not only from newly heard adverts (this was the TEST 8 failure before). Logcat still shows
   `restored N cached venue adverts for offline navigation`.
4. If you want logcat proof later: `adb -s <serial C> logcat -d | findstr "restored"`.

---

## TESTS 1–2 (engine screens) — quick pass

Open each from the Test Hub; the **Simulated test** button works standalone on any one phone:

| Row | Test | Simulated pass criteria |
|---|---|---|
| 1 | Routing engine | Route Hall 1 → Cafeteria: distance, 5 steps, solve time ~ms |
| 2 | Positioning | Pick beacon + RSSI −60 → Update → `Real estimate: … via beacon RSSI` |

The Recommendations and Assistant screens were **removed** (offline-first pivot; the cache bugs
#11/#12 are fixed by deletion, per direction). Routing's **Real** button calls the ai-service
over HTTP (host field, default `10.0.2.2:8000` = emulator alias); without the server it fails
gracefully with a timeout message — expected, not a bug.

---

## Troubleshooting

| Symptom | Likely cause / fix |
|---|---|
| Everything works, then ALL phones stop hearing each other after ~5 min | **Screen lock.** These OEM builds (vivo/Samsung, Android 16) suspend unfiltered BLE scans and throttle advertisers when the screen locks. Keep screens on during tests (Settings → display → screen timeout ≥ 10 min, or plug the phones in). The app now self-heals within ~10 s of the screen coming back; if a send still fails, check logcat for `send dropped — transport not usable` and `BLE transport recovered`. |
| No peers in Send spinner after 30 s | Bluetooth off on the other phone; app in background/killed; radios too far apart. Bring them within 5 m, app foreground, then reopen the tab (or tap Refresh in the pair screen). |
| Pair test says out of range but phones are adjacent | First-ever advert exchange can take a few seconds — tap **Refresh**, resend once. Android also rate-limits advert scanning; keep screens on. |
| Hop test: C never gets A's message | Placement — B must reach *both*. Move B closer to A first, confirm A↔B pair test passes, then B↔C pair test. Then retry A→C. |
| "Delivered" never appears, status times out | The 12 s ACK window passed. Check B is not set to Relay OFF (Settings → "Relay other users' messages" must be ON). |
| Venue never appears in C's directory | Venue string must be typed exactly; check A's Hop screen shows `announcing: Hall 1`. Venue adverts re-flood every ~5 s — wait 2 cycles. |
| Compass/position marker blue vs green | Blue = mesh advert (hearsay), green = measured beacon RSSI. Both are correct. |
| Labels show `Phone (G47J4B)` style | Names were learned before you renamed. Force-stop the app on all phones and relaunch (names persist). |

## Collecting evidence (optional)

From a PC with ADB, per phone:
```
adb -s <serial> logcat -d > phoneX.log        # full log
adb -s <serial> logcat -d | findstr /i "MeshManager BleAdvertiser FATAL AndroidRuntime"
```
Zero `FATAL`/`AndroidRuntime` lines = no crashes. Send me the logs and I'll analyse them.

## Known non-issues
- Real-server test 1 fails with a timeout when ai-service isn't running — expected.
- In **debug** builds the test activities are exported and CI-launchable, e.g.
  `adb shell am start -n com.epics.bitchat/.ui.TestHubActivity`; release builds keep them
  non-exported.
- Payloads carry up to **14 chars**; longer input is rejected with a visible console row
  (`DROPPED_TOO_LONG`), never truncated.
- A dead BLE scan now self-heals: the transport re-arms after a stall (watch for
  `scan stall detected — re-arming BLE scan` in logcat) and surfaces scan/advertise
  failures in the console instead of swallowing them.
