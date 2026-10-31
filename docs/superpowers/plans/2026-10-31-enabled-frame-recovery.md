# Enabled-frame recovery implementation plan

**Goal:** Both distributed STM32 drivers accept a valid enabled frame immediately after Init/Reset or a receive timeout, as requested by the user. Scope is the GitHub repository only; do not edit Vela_Gimbal.

**Architecture:** Remove only the expired-and-enabled rejection in `publish()`. Retain existing sequence-baseline logic: the first valid frame establishes the baseline after reset/timeout; while live, duplicates and backward sequences remain rejected. CRC, range, disabled/stop payload validation, 300-tick neutral timeout, baud and inversion defaults remain unchanged. App connection/retry/manual-enable behavior is unchanged.

**Tech:** C11, H723 USART3/TIM5, F103 USART1/TIM4, existing host and hardware simulation suites, ARM GCC and GitHub Actions.

- [x] Update `tests/c/test_remote_control.c` for enabled startup/recovery, invalid recovery packets, restored live sequence checks and metadata. Run against both old drivers and record assertion failures before implementation.
- [x] Remove the single rejection line from both `stm32/*/remote_control.c`; document enabled baseline acceptance at `RC_Init` declarations in both headers. Run `tests/c/run.sh` with strict compiler flags.
- [x] Update README, protocol, STM32 integration and BT630 guide to distinguish MCU auto-recovery from App reconnect requiring manual enable. Publish new driver version stm32-v2.3.0, preserving 115200 and board-specific inversion defaults.
- [x] Obtain scoped code review, push source, verify STM32 CI initialization/private interrupt tests and ARM compilation/linking. Create ZIP with both .c/.h pairs and current protocol/integration/porting docs. Verify ZIP contents against source and publish release/checksum; verify downloaded asset hash. Record exact evidence in docs/validation.md. No APK changes or flashing.

## Verified result

- Both old drivers failed the new startup assertion; with startup isolated, both failed the new timeout-recovery assertion. After the two single-line deletions, `tests/c/run.sh` passes both targets.
- Independent scoped review found no material issue and reran both host tests successfully.
- Runtime source `e572ad13b3f74eccf7509c27d68e595604f85ea2`; STM32 job in [41159521528](https://github.com/Norlive666/remote-control/actions/runs/41159521528) passed: both protocol suites, 70 initialization/private interrupt cases, invalid inversion rejection and both ARM compile/generated-IRQ coexistence links.
- [stm32-v2.3.0](https://github.com/Norlive666/remote-control/releases/tag/stm32-v2.3.0) ZIP includes two driver pairs plus current protocol, integration, BT630 guide and MIT license. All eight entries checked byte-for-byte against the source commit. Published asset downloaded and SHA256 checked: `397a4c9dd2b3f268522330937cdd7079b4cc22844f7bf1601d13816c2d33b4df`.
- No Android code, Vela_Gimbal files or device firmware modified/flashed; physical recovery behavior remains to be tested by the user.
