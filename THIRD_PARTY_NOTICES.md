# Third-party notices

## bb8-app

https://github.com/Alexei-Simons/bb8-app

The Compose joystick gesture structure was adapted from DriveScreen.kt. BB-8
protocol, assets, branding and BLE implementation are not included. Original
copyright and MIT license are reproduced in `licenses/bb8-app.txt` and bundled
with the APK as an asset.

## STM32 HAL / CMSIS

The standalone drivers depend on the user's matching STM32Cube HAL/CMSIS project.
These upstream packages are downloaded for compile checks, not distributed as
part of the two-file driver. Follow the license files of the selected ST/Arm
packages when redistributing a complete firmware project.

## AndroidX / Jetpack Compose and Kotlin

https://github.com/androidx/androidx
https://github.com/JetBrains/kotlin
https://github.com/Kotlin/kotlinx.coroutines

AndroidX / Compose components and Kotlin runtime/coroutines are used as Android
application dependencies under Apache License 2.0. The full license is reproduced
in `licenses/apache-2.0.txt` and bundled with the APK as an asset. Upstream sources
remain at the links above; this project does not modify those libraries.

## bt_mcu

https://gitee.com/xie-rongji/bt_mcu

Native BLE connection, service discovery, notification and write flow adapted
from `app/src/main/java/xnj/lazydog/btcontroller/Bluetooth/BlueToothMaster.java`,
commit `10dddc6c52c43b3bd8e20f377237d8eb7eb68fe4` (xie-rongji / 谢荣基).
The adaptation uses explicit foreground connections, fixed BT630 UUIDs,
serialized callback-checked operations, cancellation and bounded timeouts;
it does not include the upstream SPP transport or debugger packet protocol.
Mulan Permissive Software License v2, reproduced unmodified in
`licenses/bt-mcu.txt` and bundled with the APK as
`assets/licenses/bt-mcu.txt`.
