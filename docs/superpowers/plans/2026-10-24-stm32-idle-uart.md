# STM32 idle UART integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Allow one-call receiver initialization with either unconfigured UART or idle CubeMX-initialized UART, preserving active peripherals.

**Architecture:** Keep the existing two-file register driver and private IRQ vectors. Replace clock-only UART ownership checks with activity checks; add header configuration for baud and H723 RX inversion. Keep timer protection and wire protocol unchanged.

**Tech Stack:** STM32H723/F103 CMSIS, RCC HAL, C11 host register tests, arm-none-eabi-gcc, STM32Cube HAL project.

## Global Constraints
- No Android changes, D-Bus control mapping, motor activation, or unrelated source changes.
- Active DMA/interrupt UART and occupied timer must remain untouched on HAL_BUSY.
- H723 USART3 PD8/PD9 TIM5; F103 USART1 PA9/PA10 TIM4.
- Preserve local project edits and all existing recovery backups. Upload only source/docs/tests and driver ZIP.

### Task 1: Behavior regression and driver changes
Files: stm32/{h723,f103}/remote_control.{c,h}, tests/c/test_init.c, tests/c/run-hardware.sh.
- [x] Add reuse-uart/reuse-uart-irq fixtures with enabled clock, UE/TE/RE, TC set, generated IRQ enabled, old baud/parity/stop bits. Expect HAL_OK, correct new format, protocol frame acceptance and timeout; run against old driver and observe failure.
- [x] Add activity fixtures for RXNEIE, IDLEIE, TXEIE/TCIE, PEIE, DMAR/DMAT, CR3 error/CTS interrupts, H7 extra IRQ sources, unfinished TX. Expect HAL_BUSY with register/vector/state preservation.
- [x] Add separately compiled H723 inversion and default-baud cases; observe old behavior fail before implementation.
- [x] Implement private uart_occupied(), permitting only idle configured ports; retain existing timer and remap protection.
- [x] Add RC_DEFAULT_BAUD and RC_RX_INVERT header settings; RC_Init uses default baud, H723 configures CR2 inversion with UE disabled, F103 rejects inversion.
- [x] Run tests/c/run.sh, tests/c/run-hardware.sh and tools/compile-stm32.sh with official local H7/F1 dependencies.

### Task 2: Existing project integration and physical verification
Files: /home/norlive/project/h723_test/User/bsp/remote_control.{c,h}, Core/Src/main.c.
- [x] Back up exact current main/driver/header and hashes under the existing recovery directory; refuse overwriting unexpected edits.
- [x] Copy new driver; local header selects 115200 and inverted RX. In main after SystemClock_Config invoke MX_DMA_Init and MX_USART3_UART_Init before the existing RC Init, retaining all other test logic and user formatting.
- [x] Build original project and regenerate HEX/BIN. Confirm other files unchanged and no motor/business initialization is enabled.
- [x] Verify board program after a controlled flash or user loading: g_rc_init_status HAL_OK, CR2 RXINV, idle configured UART accepted, valid_frames grows after phone disabled frame. Do not claim physical pass from compile alone.

### Task 3: Review, documentation and upload
Files: README.md, docs/stm32.md, stm32 headers, GitHub stm32-v2.2.0 release.
- [x] Explain unconfigured/configured-idle/active cases, one-line use, two header options, ordering/exclusive UART rule, timer limits, H723 D-Bus polarity and F103 limitation.
- [x] Request read-only review of exact diff and act on correctness findings.
- [ ] After automated checks and board evidence, commit/push precise paths and publish ZIP with both families, license and protocol/docs. Verify uploaded hash and remote commit.
