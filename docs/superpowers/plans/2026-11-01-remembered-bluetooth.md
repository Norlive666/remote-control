# Remembered Bluetooth Quick Connection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Remember the last successful BT630 and offer cancellable persistent quick connection with short/long gestures.

**Architecture:** Extend the existing Bt630Session retry loop and existing RemoteViewModel SharedPreferences/state. Route the same main button through short-click direct connect/disconnect/cancel and long-click device selection, preserving foreground-service lifecycle.

**Tech Stack:** Kotlin, Android26–35, Compose, coroutines, Robolectric/JUnit, Android UI instrumentation, GitHub Actions.

## Global Constraints

- Approved spec: ../specs/2026-11-01-remembered-bluetooth-design.md. Only App changes; driver/protocol and unrelated projects untouched.
- Ready is the persistence boundary. Last-success name/address survive disconnect/restart; failed selections never replace them.
- Transient failures retry until cancel with delays1/2/4/5/5 seconds and10-second open timeout; permanent/permission failures terminate. Real reconnect disables controls as before.
- Long press never performs short action. First use opens list. Explicit list selection may switch devices, safely cancelling old session first.
- Default background-off stops pending work on background; active background ownership retains existing semantics. Permission callbacks cannot replay cancelled or replaced requests.
- App1.9.0/code15; only original local signing key, never secrets upload.

### Task1: End-to-end remembered connection

**Files:** Modify Bt630Session.kt, RemoteViewModel.kt, MainActivity.kt, EditablePanel.kt, Bt630ConnectionDialog.kt under android/app/src/main/java/io/github/norlive/remotecontrol; matching test files under src/test and src/androidTest. No new framework or dependency needed.

**Interfaces:** Preserve existing connect(DeviceRow),disconnect(),foreground(Boolean). Add last-device state and connectLastDevice() in ViewModel; persist only inside Ready callback. MainActivity handles permission intent, ControllerScreen handles dialog, BluetoothHub uses combinedClickable with separate callbacks.

- [x] Add regressions first: change fifth-failure tests to assert sixth attempt at17000ms and fifth/subsequent interval5000ms; check stop prevents further opens and permanent failures terminate. Add successful GATT/ready fixture for memory persistence, failed target not overwriting and new ViewModel reload. Add UI short-click default/remembered/cancel/disconnect and long-press-only list behavior; update obsolete five-attempt expectations.
- [x] Run regressions against old implementation and record real assertion failures, fixing any fixture errors before coding. Use CI if local Android SDK is unavailable. Example session assertion: start; advanceTimeBy(17000);runCurrent();assertEquals(6,links.size);session.stop().
- [x] Implement minimum retry loop: remove attempt>=5 terminal condition, use backoff1/2/4/5; preserve permanent errors, generation guard and close-before-retry. Persist name/address in Ready and reconstruct BluetoothDevice from saved address only on explicit short click. Do not auto-connect at launch.
- [x] Update button with combinedClickable(onClick=shortAction,onLongClick=openList), preserve tag connection-open, role Button and accessible long-click label. Short action disconnects connected/connecting, otherwise direct-connects remembered target or opens initial list. Keep permission intent distinct for scan vs connect and invalidate stale requests on cancel/stop. Dialog explicit cancel/selection safely replaces work, close-only preserves existing main connection.
- [x] Run full tests/build, self-review lifecycle and permission boundaries, commit only scoped files. Review spec compliance and code quality using a fresh reviewer; address material findings with regressions.

### Task2: Package and verify publication (root)

- [x] Bump code15/name1.9.0; update README/validation/release notes with gestures, memory, retries and background behavior. Keep driver3.0.0 links unchanged.
- [x] Complete all unit/API35 UI/STM32 checks; inspect actual screenshot. Sign release APK locally with existing key and verify version, certificate, zip alignment and SHA256.
- [ ] Push final source and publish v1.9.0 APK/checksum. Re-download public assets and compare bytes/checksum. Record exact tests, source revision, artifact hash and physical-device limitation. No firmware release.
