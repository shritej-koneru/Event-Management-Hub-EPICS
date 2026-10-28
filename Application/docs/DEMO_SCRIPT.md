# Three-Phone Viva Demo Script — Mesh Relay, Venue Adverts & Beacon-less Navigation

**Total time:** ~6 minutes. **Hardware:** 3 Android phones (A, B, C) with the app installed.
**What it proves, in order:**

1. **Relay proof** — a user's phone forwards other users' data ("users as nodes"), and stops doing so when they opt out.
2. **Venue announcement** — "I am at Hall 2" floods the mesh and reaches a phone that cannot hear the announcer directly.
3. **Mesh navigation end to end** — the far phone fixes its position from that relayed advert alone. No hardware beacons anywhere.

Every expected string below is exactly what the UI shows.

---

## 0. Setup (before examiners arrive) — 10 min

```bash
# from the Application/ directory (bundled JDK 21 — system JDK 25 breaks Gradle 8.13)
JAVA_HOME="../Android Studio Quil 2/android-studio/jbr" ./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk   # repeat per phone
```

On **each** phone:

1. Grant **Bluetooth** and **Location** permissions when asked (BLE scanning requires location on Android 12+).
2. Settings ▸ set **Node name** to `Phone A` / `Phone B` / `Phone C`.
3. Settings ▸ Transport = **"Real BLE advertisements"** ▸ **Save and restart mesh**.

**Critical physical arrangement:** place **A and C far enough apart that they cannot hear each other** (different rooms, or ~25+ m with walls), with **B in between**.

> **Verify non-adjacency (the demo fails without it):** Phone A's *Peers* tab must list **B but not C**; Phone C's must list **B but not A**. If A sees C, move them further apart or turn your body to absorb the path (human bodies attenuate 2.4 GHz nicely).

Sanity check: each phone's *Console* tab shows TX/RX traffic, and the foreground service notification is up.

---

## Act 1 — Relay proof: "this user acted as a node" (~2 min)

**On Phone B:** Tests tab ▸ **"Hop / relay — users as nodes"**.
Confirm the **relay switch is ON** (toggling it toasts *"Relay ON — this phone forwards others' messages"*).
Point at **HOP STATS**: `relayed / delivered / sent / drops / max hops` — all the accounting is per-phone and live.

**On Phone A:** same screen ▸ tap **"Real flood (2+ phones)"**.
It prints *"REAL flood (2+ phones) sent. Watching stats for 6 s…"* and floods a `hop…` broadcast with **TTL 8**.

**After 6 seconds, read the three screens:**

| Phone | What appears | What it proves |
|---|---|---|
| **B** | `After 6 s: +1 relayed by this phone, …` followed by **"→ this user acted as a node for other users' data."** | The middle user forwarded A's packet. **This is the headline line.** |
| **C** | `+1 broadcast deliveries heard`, and HOP STATS now shows **"Phone A: heard at ≤ 1 hops"** | C received data that physically travelled A → B → C. |
| **A** | flood confirmed sent; sender counts `sent`, not `relayed` | clean accounting per role |

**Negative control (do this — examiners love it):**
On Phone B, flip the relay switch **OFF** (*"Relay OFF — floods stop at this phone"*). Phone A: **Real flood** again.
- Phone C: **nothing arrives** — no new delivery, no new peer-hop line.
- Phone B's *Console* tab shows the packet logged with status **`DROPPED_NO_RELAY`**.
Flip B back **ON**. The contrast is the proof: relaying is a real, visible, user-controlled role — not an accident of the flood.

---

## Act 2 — Venue announcement over the mesh (~1.5 min)

**On Phone A:** Settings ▸ **"Announce my venue over the mesh (navigation beacon)"** ▸ type `Hall 2` ▸ **Announce**.
(The same box exists on the Hop test screen.) A's phone broadcasts `V:Hall 2` as a BEACON message, TTL 8, and **re-announces every 15 s** so late joiners learn it.

> **Auto-announce note:** Settings ▸ *"Auto-announce my venue"* is **on by default** — any phone in range of a hardware beacon starts broadcasting its measured venue automatically, so the mesh fills with navigation beacons with no manual entry. Manual announcements still win (they pin the venue until cleared), and relayed hearsay is **never** rebroadcast — only physically measured positions are, which is what keeps the flood from echoing one venue across the whole mesh. For deterministic demo timing, the script pins venues manually as below; auto-announce is the fallback that seeds everything else.

**Watch the VENUE DIRECTORY panel** on the Hop screen of the other phones:

| Phone | Shows | Meaning |
|---|---|---|
| B | `Hall 2 via <A's device id> · 0 hops` | heard A directly |
| **C** | `Hall 2 via <A's device id> · 1 hops` | **venue knowledge crossed B's relay** — C has never heard A's radio directly |

Note for the viva: the directory dedups by device, keeps the *shortest* hop count seen, and entries expire after **2 minutes** unless re-announced (the 15 s cycle keeps them alive).

---

## Act 3 — Mesh navigation end to end, no beacons (~1.5 min)

**On Phone C:** Tests ▸ **Positioning** ▸ tap **"Real test (BLE peers)"** (or *Watch live* for 1 Hz updates).

The table shows both anchor sources:

```
LIVE BLE PEERS (from the running mesh stack)
device            rssi     beacon
Phone B             -67      phone

MESH VENUE ADVERTS (positioning anchors)
venue               hops       from
Hall 2                 1     <A's id>

Estimate: Hall 2 via mesh venue advert (1 hops)
```

**The money line:** `Real estimate: Hall 2 via mesh venue advert (1 hops)` —
C has fixed its position at Hall 2 using **only an advert announced by a phone it cannot hear, relayed by a third user**. No beacon hardware exists in this demo.

How it works under the hood (one sentence each, if asked):
- Each relay hop costs **7 dB** of synthetic signal (direct advert = −55 dBm), so mesh anchors compete fairly with real beacon RSSI on one scoreboard; **strongest anchor wins**, and a *measured* signal beats a *synthetic* one on an exact tie.
- More than ~7 hops falls below the −100 dBm trust floor and is rejected.
- Anchors expire with their directory entry — no stale ghost positions.

**Close the loop on the Navigation tab (Phone C):** the venue picker side shows **"Mesh venues: Hall 2 (1 hops)"**, and a computed route to Hall 2 adds *"someone is at Hall 2 (1 hops away)"*.

**Optional flourish (30 s):** Phone A walks to another room, changes the announcement to `Library`. C's directory and estimate follow: `Library via mesh venue advert (1 hops)`. The "map" is made of people and it moves with them.

**If a hardware beacon is available:** place one near C (advertising a node id in the `BeaconParser` format). Its estimate line flips to `… via beacon RSSI -xx` and wins ties — both anchor types fuse in the same estimator, no code change.

---

## Fallback plan (if the room's BLE misbehaves)

Multi-phone relay needs real BLE — the simulator is an in-process island, so **sim mode on 3 phones does not talk across phones**. If the radios won't cooperate:

1. Run the **"Simulated flood"** button on one phone to show the stats/drop accounting live.
2. Show the evidence on file: `RelayAndVenueTest` (relay on/off, stats, DROPPED_NO_RELAY, venue directory multi-hop) and `MeshPositionTest` (advert → position end to end) — 21 tests that pin this exact behavior in CI.
3. One phone in **Simulator** transport still demos venue announce → directory → positioning (the synthetic beacons announce "Hall 1/Hall 2/Cafeteria").

---

## Q&A ammunition

- **Why flooding instead of routing tables?** Stateless — zero setup, survives churn (phones walking away mid-flood), loop prevention via seen-message ids, TTL bounds the blast radius. For a venue-sized mesh, flooding is the correct simplicity/robustness trade.
- **Why do broadcasts get both delivered *and* relayed?** That's the design decision that makes venue adverts work: every hop consumes the venue knowledge locally *and* forwards it, so the directory builds everywhere the flood reaches.
- **Why 7 dB per hop?** A tunable model constant (`PositionEstimator.HOP_ATTENUATION_DB`) rather than physics — it makes hop counts map onto the same −55…−100 dBm scale beacons use, so one "strongest anchor wins" rule covers both.
- **What are the honest limits?** Adverts carry only the venue name (payload budget); no ACKs on adverts (best-effort by design); 2-minute directory TTL; phone adverts are coarse anchors (room-level), not meter-level.
- **What happens if two people announce different venues?** Both live in the directory; the estimator picks whichever anchor is "loudest" (fewest hops). Multiple people at one venue = one anchor, shortest hops wins.
