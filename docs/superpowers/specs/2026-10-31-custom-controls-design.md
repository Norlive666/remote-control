# Configurable controls and four slider channels

User approved adding widgets through settings, retaining the two default sliders and extending output to four slider channels. All widgets can carry configurable title and one-line small note, target channel1..4 and relevant values0..1000. Existing blue/white panel style remains.

## Confirmed interactions

- Ordinary momentary button: configured value while held, zero on release/cancel.
- Short/long button: release before500ms produces short-value pulse100ms then zero. Holding500ms produces long value untilrelease then zero. Short and long are mutually exclusive.
- Click-cycle control: click cycles configured2..8 values, holding selected value afterrelease. Default0,1,2. No sliding required.
- Slider: existing stepped/continuous behavior; settings can add widgets for channels3/4. Default two existing sliders remain in place. New widgets configurable/editable/deletable; addedbuttons default1 or short1/long2. Note blank bydefault.
- Each widget has stable ID and participates in existing layout movement/scaling30%-250%, save/cancel/default position/size and persistence. Defaults restore layout draft rather than silently removing configured widgets. Existing saved widgets/layout/gamepad settings survive update.
- Writes sharing a channel use the latest input: an older button release, pulse timer or cancellation must not clear a newer widget/gamepad/slider value. Background/disconnect/edit/settings cancellation stops pending presses and zeroes controls under existing enable rules. Gamepad default mappings and release-holds unchanged; targets expand tofour.

## Protocol and driver

Continue20-byte frames, fixed BLE UUIDs. Version2 byte2, seq3, axes little-endian int16 at4..11, four10-bit slider values packed least-significant-bit first into12..16, enable/stop flags17 (bits0/1 only), CRC16 CCITT-FALSE over2..17 at18..19. Range0..1000 preserved; values1001..1023 rejected. New driver RC_State_t.sliders[4], accepts legacy version1 frames and zeroes missing channels3/4. Enabled-frame recovery and300ms timeout retained. Old firmware cannot parse v2: upgrade driver when installing new App. No MTU increase or fragmentation required.

## Delivery

App v1.7.0/code13, original appId and local signing key. STM32 driver stm32-v3.0.0 reflects expanded state structure/protocol support. Tests cover models/timing/ownership/persistence/UI and both C decoders/legacy sequences/timeout/CRC; full CI, actual screenshot inspection and independent reviews required. Publish signed APK and driver ZIP/checksums with migration docs. Signing keys staylocal; Vela_Gimbal unchanged. Tests do not substitute phone/BT630/board verification.
