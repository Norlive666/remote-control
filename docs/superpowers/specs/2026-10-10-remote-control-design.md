# Remote Control v1

Approved scope: landscape Android controller in the selected mist square style,
two independently touchable spring-return joysticks and two 0–100 sliders;
editable top-right connection/reset/stop button labels and actions; settings persist.
HC-05 is the default test transport (Classic SPP, bonded devices, 20 Hz).
FSC-BT630 is supported through BLE GATT FFF0 service, FFF2 write, FFF1 notify;
50 Hz. Explicit permission flow, connect/disconnect, status and latest state display.
No motor control, cloud accounts, HID emulation or background driving.

Protocol: 20 bytes. A5 5A; version 01; uint8 sequence; four little-endian int16
axes (-1000..1000); two little-endian uint16 sliders (0..1000); flags at offset16
(bit0 enabled, bit1 stop); reserved zero at17; little-endian CRC at18.
CRC16 CCITT-FALSE (poly1021, initFFFF, no reflection/xor), covers bytes2..17.
Disabled or stopped frames carry zero control values. Stop is latched until explicit
enable; automatic reconnect never enables driving. Latest-only sending: do not queue
historical control frames. Connection generations isolate stale callbacks and jobs.

STM32H723VGT6 and STM32F103C8T6 HAL projects: USART1 PA9/PA10, TIM4 1ms,
300ms without a valid fresh frame forces neutral disabled state. RC_Init() uses
9600 for HC-05; RC_InitBT630() uses115200. A baud-specific init is available.
Each MCU package provides directly usable remote_control.c/.h with global volatile
RC_State_t g_rc and coherent RC_GetState snapshot; no heap, no DMA, no polling
required. Initialization owns GPIO/USART1/TIM4/NVIC, but requires standard project
HAL/startup/system clock initialization. Existing IRQ symbols must not conflict.
No MCU runtime or wireless latency claims without physical hardware verification.

Deliver source to Norlive666/remote-control, CI tests/MCU compile checks/Android
unsigned APK build, local signing with a persistent key, and a GitHub release
containing signed APK and MCU source archive. Signing keys/passwords remain local
only; no signing credentials are stored in GitHub Secrets.
Keep signing credentials outside source control and provide a continuity route for
future updates. Include licenses and concise Chinese connection instructions.
