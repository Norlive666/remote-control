# Task 1 report — native BT630 link and session

## Implemented

- `Bt630Session` has cancellable five-attempt lifecycle, bounded backoff, stable-ready budget reset, disabled control baseline, latest-frame send, and immediate non-Ready state on loss before backoff. A reentrant `stop()` from a Ready callback cannot send a frame.
- `Bt630GattClient` uses foreground LE `connectGatt(..., false, ..., TRANSPORT_LE)`, discovers FFF0, validates FFF2 write properties, subscribes to FFF1 through CCCD when present, and publishes readiness only after subscription confirmation. It selects response writes when supported, serializes sends, checks operation return values and callbacks, has 10-second open and 1-second write deadlines, and closes invalid operations. Generation plus GATT identity reject stale callbacks. API 26–32 use the older value/write overloads.
- Native source attribution and Mulan PSL v2 notices were committed in earlier Task 1 checkpoints.

## Verification

- TDD RED: 13 session assertion failures were recorded at `/tmp/bt630-session-behavior-red.log`; native CI RED at `/tmp/bt630-native-red-ci.log` reported 14 native failures with the skeleton. The reentrant-stop and immediate-loss tests each failed on the prior session implementation before their fixes.
- GREEN: `bash /tmp/bt630-session-test/run.sh` passed all 15 session tests on 2026-10-30.
- Kotlin compiler against Android 35 SDK and coroutines jar compiled both production sources successfully. Only deprecation warnings for required pre-API-33 GATT overloads remain.
- `git diff --check` passed.
- Native Robolectric GREEN and full Android unit suite pending CI. Local Gradle cannot start in the sandbox (`Could not determine a usable wildcard IP for this machine`); external dependency resolution also has TLS failures. Physical BT630 behavior remains unverified.

## Boundary

Task 1 does not wire ViewModel/UI or change STM32 firmware. Source flow is adapted from bt_mcu commit `10dddc6c52c43b3bd8e20f377237d8eb7eb68fe4` under Mulan PSL v2.

## Review fix wave

- The coordinator's CI run 41077312729 passed the original Task 1 checkpoint: Android full unit task and APK build, plus STM32 job. Exact XML counts were being collected separately.
- Review findings addressed: legacy two-argument notification callback, exception-safe GATT disconnect/close and session stop, serialized send/close platform boundary, and loss check after open before Ready/sending. Focused tests cover each behavior.
- The expanded pure JVM session suite passes 17/17 locally; production Kotlin sources compile against Android 35. Native review-fix tests and full Android suite await CI after this fix commit.
