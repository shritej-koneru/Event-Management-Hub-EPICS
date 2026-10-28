# 7 · Testing & Verification

> Previous: [Android app](06-android-app.md) · Next: [API reference](08-reference.md)

Three verification layers, cheapest first: pure-JVM unit tests (every
engine behaviour), an emulator matrix (real Android BLE stack over a
virtual radio), and physical phones (the truth). Everything below was run
to green in this session.

## 7.1 How to run

```bash
# Unit tests + APK (use a JDK 17/21 — the Android Studio JBR; system JDK 25 fails)
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" \
  ./gradlew :meshlib:test :app:assembleDebug --console=plain

# Per-suite counts from the JUnit XML
awk -F'"' '/<testsuite /{t+=$4; f+=$8; e+=$10}
  END{printf "TOTAL tests=%d failures=%d errors=%d\n", t, f, e}' \
  meshlib/build/test-results/test/TEST-*.xml
```

Latest result: **150 tests, 0 skipped, 0 failures, 0 errors**; debug APK
builds clean (~6.1 MB).

## 7.2 Unit tests

22 suites in [`meshlib/src/test`](../meshlib/src/test/java/com/epics/bitchat)
(`mesh/`, `nav/`, `ai/`).
The key harnesses:

- **Mini-mesh** (used by [`MessageFragmentationTest`](../meshlib/src/test/java/com/epics/bitchat/mesh/MessageFragmentationTest.java)
  and [`FragmentRecoveryTest`](../meshlib/src/test/java/com/epics/bitchat/mesh/FragmentRecoveryTest.java)):
  several `MeshNode`s on `StubTransport`s, a link map, and a `step()` that
  delivers every outbox to neighbours — real router/assembler code, fully
  deterministic radio.
- **[`SimulatedTransportTest`](../meshlib/src/test/java/com/epics/bitchat/mesh/SimulatedTransportTest.java)**:
  the engine's own simulated radio (range/loss/RSSI model, chapter [2.5](02-mesh-engine.md#25-transports)).

| Suite | Tests | Covers |
|-------|-------|--------|
| `PacketCodecTest` | 12 | codec roundtrip, framing, ids, tokens |
| `MeshPositionTest` | 13 | venue-advert anchors vs beacons, provenance, expiry |
| `AutoAnnounceTest` | 10 | the three announce gates (chapter [5.4](05-positioning-navigation.md#54-auto-announce-and-the-echo-chamber)) |
| `InternetIngestTest` | 9 | deterministic ids, dedup, fragmented ingest |
| `DijkstraTest` | 10 | shortest paths on the campus graph |
| `MessageFragmentationTest` | 10 | split/reassemble end-to-end, dedup, order |
| `RelayAndVenueTest` | 10 | relay on/off, venue directory |
| `AiEngineTest` | 7 | recommendation ranking |
| `RouterTest` | 7 | dedup, TTL, unicast |
| `RebroadcastWindowTest` | 6 | ingest window cadence |
| `BeaconParserTest` / `BlePacketInterpreterTest` | 6 / 8 | identity adverts, hit classification |
| `ScanWatchdogTest` | 5 | raw/mesh stall, re-arm, FULL_RECOVERY escalation |
| `DeliveryTrackerTest` | 5 | ACK match, timeout |
| `SimulatedTransportTest` | 5 | simulated radio |
| `BroadcastFanoutTest` | 4 | one-to-many fanout |
| `FragmentAssemblerTest` | 4 | tombstones, expiry visibility |
| `FragmentRecoveryTest` | 4 | **NACK roundtrip, retry cap, mask codec** |
| `RssiFilterTest` | 3 | smoothing |
| `PositionEstimatorTest` / `PositionEstimatorDistanceTest` | 2 / 6 | strongest-anchor, **ranging curve, weighted centroid** |
| `RouteBuilderTest` | 4 | turn-by-turn steps |

## 7.3 Emulator matrix

Two headless API 34 emulators joined by the emulator's **BLE netsim virtual
radio** (emulator ≥ 36.5; this build is 36.6.11 — plain launch, no radio
flags needed). Drive with adb:

```bash
emulator -avd <AVD1> -port 5554 -no-window -no-audio -no-boot-anim -no-snapshot-save &
emulator -avd <AVD2> -port 5556 -no-window -no-audio -no-boot-anim -no-snapshot-save &
adb -s emulator-5554 shell cmd bluetooth_manager enable      # both devices
adb -s emulator-5554 install -r -g app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 shell am start -n com.epics.bitchat/.MainActivity
```

Latest matrix — **ALL PASS**:

| # | Test | Evidence |
|---|------|----------|
| T1 | BLE-mode start, no crash | `BitChatMesh: starting mesh in BLE mode` both sides |
| T2 | Mutual peer discovery | `peer discovered: TZJ (EMU1)` ↔ `UN6 (EMU3)` |
| T3 | Short message + ACK | sender status `Delivered to EMU1 (TZJ)` |
| T4 | Fragmentation e2e (191 chars) | same status — fires only on group-token ACK |
| T5 | >324-char reject | `Too long — the mesh carries at most 324 characters` |
| T6 | Routing hub + test activities | screen renders, zero `AndroidRuntime` fatals |

Known emulator caveats: the netsim radio does not drop adverts, so NACK
recovery and watchdog escalations cannot be exercised here (chapter [7.4](#74-hardware-matrix));
some system images do not export RecyclerView text to uiautomator — verify
via logcat/ACK chains rather than UI dumps.

## 7.4 Hardware matrix

Three physical phones (A = Samsung SM-M476B `EUB`, B = Xiaomi 2201116SI
`MG2`, C = Samsung SM-M156B `EVR`) over wireless ADB. **T1–T5 ALL PASS** in
the earlier session (fresh installs, permissions, relay on/off, dedup,
ingest, oversize-reject with console-row evidence). B also produced the
original **MIUI scan-stall bug** that motivated [`ScanWatchdog`](../meshlib/src/main/java/com/epics/bitchat/mesh/ScanWatchdog.java)
(deaf after ~6 min, cured only by app restart).

**Outstanding on hardware** (emulator cannot reach these):
- live NACK recovery with a physically-induced lost frame,
- ScanWatchdog FULL_RECOVERY observed on a stalling MIUI stack,
- regression of the full matrix on the newest APK (phones currently off ADB —
  re-enable wireless debugging and provide fresh `IP:port` pairs).

## 7.5 Bug-fix ledger (this session)

| Bug | Fix | Guarded by |
|-----|-----|------------|
| Per-frame ACK storm | one group-token ACK per stream | `completeChatGroupIsAckedOnceWithGroupToken` |
| Silent loss of incomplete groups | visible expiry row after TTL | `FragmentAssemblerTest` |
| MIUI silent scan death | `ScanWatchdog` escalation | `ScanWatchdogTest` |
| `isFragment` wrong offsets (1/2) | decode offsets 3/4 | `PacketCodecTest` framing cases |
| Silent too-long rejection | UI cap message | emulator T5 |
| Ambiguous delivery status | `Delivered to NAME (ID)` | emulator T3/T4 |
| Lost frames unrecoverable | NACK re-send (chapter [4.4](04-fragmentation.md#44-nack-recovery-meshtastic-style)) | `FragmentRecoveryTest` |
| Raw-RSSI position noise | smoothed ranging + weighted centroid | `PositionEstimatorDistanceTest` |

---
Previous: [Android app](06-android-app.md) · Next: [API reference](08-reference.md)
