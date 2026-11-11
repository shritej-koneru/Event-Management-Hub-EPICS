# Merge progress log

Working branch: `work/combined` in this folder (cut from `integration` @ `9cf1bc4`; `master` still holds the original skeleton).
Originals `../integration` and `../offline backend` are untouched. Offline mesh source of truth: `offline-backend` @ `7daad04`.

## Decisions
- Host app: integration (approved 2026-11-09). Offline engine merges into it. Work happens in this folder.
- ai-service fate: LEFT OUT for now (approved 2026-11-09)
- Venue registry scope: both building and campus, via one canonical registry (approved 2026-11-09)
- Phase 1 approved. Phase 2 design written: docs/PHASE2_CONTRACTS.md (revised 2026-11-09 with decisions D1-D7; awaiting final approval, nothing implemented)
- Open blockers: B1-B4 venue/portal/parentId verification (cross-scope routing disabled), B5 mesh clientId format (Phase 3 measures)

## Environment (per-shell, nothing committed)
- `JAVA_HOME=C:\Program Files\Android\Android Studio\jbr` (JDK 17; default JDK 25 breaks Gradle 8.x)
- `ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk` (platforms android-34, android-36.1)
- Server tests need `JWT_SECRET` (32+ chars) set.

## Phase 0 - done (read-only audit verification)
## Phase 3 - meshlib imported + B5 measured (awaiting review)
- `meshlib/` = `offline-backend` @ 7daad04 `Application/meshlib`, 60 files, byte-identical (tree-hash compared). No edits.
- `settings.gradle.kts`: `include(":meshlib")`. No Gradle/JDK/AGP changes were needed (Groovy `java-library` subproject, Java 17, builds under the Kotlin-DSL root).
- `:app` does NOT depend on `:meshlib` yet (Phase 4). `merged/Application/` (stale copy) left untouched, not in the build.
- Verified: `:meshlib:test` 150/150 (22 classes); `:app:testDebugUnitTest` 110/110, run together with `--rerun-tasks`.
- Measurements: `docs/measurements/MeshIdMeasure.java` + `mesh-id-measurements.txt`; summary in `docs/PHASE2_CONTRACTS.md` section 4.
- clientId format: NOT frozen. Placeholder from Phase 2 is shown to be invalid.
- Router dedup (NEW RISK, pre-existing in offline-backend): `Router` keys its seen-set on the 3-hex wire msgId alone (4,096
  possible keys) with an LRU cap of 4,096 that therefore never evicts; `Router.reset()` has no caller in meshlib main or the app.
  With the real Router, distinct messages from different senders were delivered 99% (N=100), 79% (2,000), 63% (4,096),
  43% (8,192), 20% (20,000), 8% (50,000). Not changed in this phase. Real-device traffic volume is unmeasured, and whether the
  Router is rebuilt on node restart was not verified.

## Router dedup (R3) implemented as a separate meshlib bugfix (awaiting review; JVM-tested only)
- `meshlib/.../Router.java`: key = src+id+kind+dst+payload, sliding 120 s expiry (refresh on every sighting), 600 s max age, LRU cap kept.
  ANNOUNCE is keyed WITHOUT src so internet-ingest copies still collapse (an existing test fails otherwise). `onLocalSend` now synchronized.
- Tests: `RouterIdentityLossTest` (9), new `RouterDedupExpiryTest` (13). `:meshlib:test` 172/0 failures; `:app:testDebugUnitTest` 110/0.
- Twin source ids: still unresolved (protocol issue R6). R4 tracked; R5/R6 not implemented. No BLE/device validation yet.
- C3 sync identity: direction only; nothing frozen. Phase 4/5 not started. Details: `docs/ROUTER_DEDUP_AND_MESSAGE_IDENTITY.md` sections 8-10.
- Device validation: plan, procedures and blank results template in [`docs/R3_DEVICE_VALIDATION_PLAN.md`](docs/R3_DEVICE_VALIDATION_PLAN.md).
  STATUS: plan written with no phone attached. Documentation-only change.
- First device run (1 wired phone, Samsung SM-M156B, Android 16): single-phone smoke check only. R3 debug APK (SHA-256 `420e1b60...`) installed,
  started, ran ~5 min with no crash/stall; one local ingest event produced `sent 1` on the sender. Results: [`docs/device-results/2026-11-09_single-phone_R3.md`](docs/device-results/2026-11-09_single-phone_R3.md).
  T1-T6 (soak, mixed fleet, A-B-C chain, two-ingester fanout, frame loss, twin id) are NOT RUN / BLOCKED pending more phones. **R3 is NOT BLE-validated.**
  Plan corrected: ingest needs an explicit `-n` receiver and a space-free payload; `--user 0` and `MSYS_NO_PATHCONV=1` notes added.

## Phase 1 - baseline verified, no code changes needed
| Target | Command | Result |
|---|---|---|
| server | `npm ci && npm test` | 17 files, 408/408 pass |
| platform app | `gradlew :app:testDebugUnitTest` | BUILD SUCCESSFUL, 110 tests, 0 fail |
| offline meshlib | `:meshlib:test` (scratch export of 7daad04) | 150/150 pass |
| offline app | `:app:testDebugUnitTest :app:assembleDebug` | BUILD SUCCESSFUL, 9 tests, 0 fail |

Not run: emulator/device, BLE, release builds, `verify:migrations`, `npm audit`, frontend build.
