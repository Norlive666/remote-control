# Remote Control Implementation Plan

Goal: deliver a downloadable Android remote and two self-initializing STM32 drivers.
Architecture: one fixed protocol shared by Classic SPP and BLE transports; UI state
is sampled by a bounded sender; MCU parser validates before publishing controls.
Tech: Kotlin, Compose, Nordic BLE Library, Android SPP, STM32 HAL, C, GitHub Actions.

## Global constraints
- Follow the approved design in ../specs/2026-10-10-remote-control-design.md.
- Use 20-byte v1 frames and CCITT-FALSE across bytes2..17.
- HC-05: default9600/20Hz; BT630:115200/50Hz. TIM4 tick1ms; watchdog300ms.
- No driving after background/disconnect; no queued historical controls.
- Tests and compiled artifacts are not proof of physical hardware performance.

### Task 1: STM32 protocol and drivers
- [x] Add host behavior tests for split/coalesced/invalid packets, CRC, sequence wrap,
  stale frames, failsafe, flags, coherent state and boundary ranges; run red first.
- [x] Implement portable parser and standalone HAL packages for H723/F103.
- [x] Compile host tests with gcc warnings as errors and both target packages with
  arm-none-eabi-gcc and real matching ST HAL/CMSIS headers.
- [x] Document resource ownership, hookup, one-init usage and IRQ conflicts.

### Task 2: Android controller
- [x] Write protocol and safety-state behavior tests, observe red then implement.
- [x] Implement selected mist style, independent sticks, sliders, editable actions.
- [x] Implement HC-05 bonded-device SPP and BT630 scanning/GATT via Nordic library.
- [x] Implement permission flow, connection cancellation, bounded current-state
  sender, stop latch, lifecycle neutralization, and persisted settings.
- [x] Run unit tests and build signed installable APK; inspect UI when possible.

### Task 3: delivery and review
- [x] Add pinned/reproducible build setup and CI with tests, target compile and APK.
- [x] Review protocol interoperability, transport lifecycle and hardware init.
- [x] Fix findings, rerun affected tests; release APK, driver zip and checksums.
- [x] Push source, verify CI and release URLs; report unperformed hardware tests.
