# BT630 Connection Refactor Implementation Plan

> Execute with subagent-driven-development, TDD and requesting-code-review. The user approved implementation; continue through build, local signing and publication without another workflow-choice prompt.

**Goal:** BT630-only native GATT connection with bounded, cancellable retries and a compact landscape connection window.

**Architecture:** A pure Kotlin session owns lifecycle and latest-frame sending. A native Android GATT client adapts bt_mcu's BLE discovery/write flow. Existing ViewModel bridges controller inputs and Compose state; a separate dialog displays devices and short connection progress.

**Tech Stack:** Kotlin 2.1.20, Android API 26–35, coroutines, Compose, JUnit, coroutine virtual-time tests, Android instrumentation, GitHub Actions.

## Global Constraints

- Source baseline `1957f72`; approved spec `docs/superpowers/specs/2026-10-30-bt630-connection-design.md`.
- Keep STM32 drivers, 20-byte protocol, layout, joystick and gamepad behavior unchanged.
- Fixed FFF0 service, FFF2 write, FFF1 notify; remove classic/FFE0/FFE1 mode and Nordic dependency after integration.
- Five attempts including first, delays 1/2/4/4 seconds; connect 10 seconds, write 1 second. Reset failure budget only after 10 seconds of ready operation.
- Cancel/foreground loss stops retries. Every reconnect requires disabled baseline and manual re-enable.
- Signing material stays local; publish only source, notices, signed APK, checksums and validation notes.

### Task 1: Native BLE client and tested session

**Files:** Create `android/app/src/main/java/io/github/norlive/remotecontrol/Bt630Session.kt`, `Bt630GattClient.kt`; create corresponding tests under `android/app/src/test/java/io/github/norlive/remotecontrol/`; add test dependencies in `android/app/build.gradle.kts`; add `licenses/bt-mcu.txt`, matching APK asset and THIRD_PARTY_NOTICES entry.

**Interfaces:** `Bt630Session.Link` has suspend `open()`, suspend `send(ByteArray)`, `close()`. Session constructor receives CoroutineScope, a factory `(lost: (String) -> Unit) -> Link`, `frame: () -> ByteArray`, `pauseControls: () -> Unit`, `stateChanged: (State) -> Unit`, `sent: (ByteArray) -> Unit`. Public `start()` and `stop()` control the lifecycle. State variants expose Idle, Connecting(attempt, error), Ready, Failed(error). Native client constructor takes application Context, BluetoothDevice, CoroutineScope, lost and received callbacks and implements Link. Fatal failures carry an explicit retryable=false property.

- [x] Write behavior tests using controllable fake links and virtual time: first two opens fail then third succeeds; fifth failure stops with no sixth; unstable ready/write failure exhausts the same budget; 10 seconds ready resets budget; stop during connect/backoff/send cancels and closes; write failure emits pause before reconnect; only latest frame is sampled and reconnect starts disabled.
- [x] Run the tests before implementation and record the missing-behavior failure. Native boundary tests must exercise discoverServices, FFF0/FFF2 validation, CCCD callback ordering, callback error status, stale GATT rejection and close/cancellation.
- [x] Implement only the specified session and native client. Keep current app wiring unchanged until Task 2 so existing build remains valid. Use bt_mcu native GATT flow with documented Android API updates; no legacy socket, manager polling thread or packet protocol.
- [x] Run unit tests and commit exact Task 1 files. Task reviewer checks lifecycle races, backoff/count boundaries and notice inclusion before Task 2.

### Task 2: Integrate BT630-only lifecycle and compact dialog

**Files:** Modify `RemoteViewModel.kt`, `MainActivity.kt`, `EditablePanel.kt`, `ControllerUiTest.kt`, `android/app/build.gradle.kts`; create `Bt630ConnectionDialog.kt`; remove obsolete `BluetoothTransport.kt`.

**Consumes:** Task 1 session/client interfaces above. **Produces:** existing `connected`, `connecting`, `status`, `lastFrame`, `lastReceived`, `sent` remain observable; connection shows numbered attempts and remembers selected DeviceRow for a retry round. A single fixed BT630 send period replaces LinkMode. Retain `refreshDevices`, `connect`, `disconnect`, `foreground` call sites where practical.

- [x] Update UI regression tests first: fixed BT630 after old HC05/BLE_UART preference; no mode chips; help details hidden until requested; device list and cancel button remain visible in landscape; established connection is not silently closed by refresh; background cancellation still stops both scans and ignores late callbacks.
- [x] Wire session state to ViewModel. Native client construction captures selected device and session-specific callbacks. Disable/zero before each start/reconnect. Preserve scan dedup and modern permissions. Delete all old LinkMode consumers and Nordic transport/dependency.
- [x] Replace connection AlertDialog body with independently scrolling list, concise header/status, explicit scan/stop, cancel/disconnect and help actions. Keep connection progress visible after choosing a device. Keep diagnostics out of the default list view.
- [x] Run full unit/instrumentation tests; capture actual connection-window screenshot. Reviewer checks required behavior and unrelated control regressions before release.

### Task 3: Documentation, signed APK and publication

**Files:** Update README, `docs/bt630-porting.md`, `docs/validation.md`, versionName/versionCode in build file; preserve historical records as historical.

- [x] Document BT630-only flow, retry limits, manual-enable recovery, native-client origin, changed errors and actual hardware-test boundary. Version 1.6.0/code12, same application ID and signing key.
- [x] Run `tests/c/run.sh`, full Android unit tests and API35 landscape UI tests, release build and independent whole-change review. Resolve material findings and repeat affected checks.
- [x] Sign unsigned release APK locally using `tools/sign-apk.py`, verify signature and alignment, compute SHA256. No secrets in git or Actions.
- [x] Push source, publish v1.6.0 APK/checksum/notes to GitHub, verify remote commit and downloaded asset metadata/hash. Report remaining real-phone/BT630 stability test explicitly.

## Completed validation and release

- Runtime source: `f220c67334ca749457fa4eacdfbd5f443b5497d5`; all three jobs in [41153885676](https://github.com/Norlive666/remote-control/actions/runs/41153885676) passed. XML reports confirm 81 unit tests and 34 Android15 landscape UI tests, all zero failures/errors/skips.
- Task reviews and whole-change review completed; adapter OFF/ON finding fixed and scoped rereview found no blocking issue. Actual populated dialog screenshot inspected in `docs/bt630-connection.png`.
- Release APK signed locally with the original certificate, v2/v3 and alignment verified. [v1.6.0](https://github.com/Norlive666/remote-control/releases/tag/v1.6.0) published; downloaded APK/checksum verified against local SHA256 `fcfe62a2a2584b5d06210a782b4bccfb91276f87a675bb498938b715f728e2ca`.
- STM32 implementation/protocol unchanged. Physical-phone and BT630 long-duration stability tests remain for the user, as explicitly stated in the release and validation record.
