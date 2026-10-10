# v1.0.0 验证记录

## 已验证

- Android 7 个单元测试通过：CRC 标准向量、小端数据/独立完整帧、限幅、禁用/急停归零、急停锁定、生命周期归零及双摇杆状态独立。
- Android 模拟器上 2 个交互测试通过：双摇杆同时操作、独立归中/取消、急停锁定、复位不解除急停和设置页打开。
- Android debug / release APK 编译通过。发布 APK 在本机由固定 RSA 3072 位密钥签名，APK v2/v3 签名和 zipalign 校验通过；密钥及密码未提交到源码、未上传 Actions Secrets。
- H723/F103 两份 C 行为测试通过：拆包/连包/重同步、校验/范围、序号回绕/重复/旧帧、300 ms 超时、禁用恢复基线、启动保护及计数器。
- 两份驱动使用官方 STM32Cube H7 v1.13.0 / F1 v1.8.7 对应 HAL/CMSIS 进行目标对象编译，通过 `-Wall -Wextra -Werror` 检查。
- 协议完整帧 CRC 另用 Python `binascii.crc_hqx` 独立核对；Android 与 C 测试共享该固定字节样例。

最终源码 `2707982` 已通过 [GitHub Actions 构建 38033832752](https://github.com/Norlive666/remote-control/actions/runs/38033832752)：Android、STM32、UI 三个任务全部成功。
正式 APK 来自该运行的 `app-release-unsigned.apk`，在本机完成对齐、签名和验签。
后续交付记录提交只补充文档与实际截图，不改变运行代码。

接收结构体发布与超时归零使用保存/恢复 PRIMASK 的短临界区。两个目标的优化反汇编另行检查，确认写字段前关闭中断、写完后恢复，避免高优先级读中断取得半更新快照。

界面截图：[controller.png](controller.png)，来自 Android 15 模拟器实际运行画面。

签名证书 SHA-256：
`27a45a0d6cddcf3952d44cd114034833a245a8029af3fd355b6c1a9eb7563935`

## 验证边界

未连接真实手机、HC-05、FSC-BT630 或开发板，未测无线距离、实际端到端延迟、干扰稳定性、UART 波形和中断负载。MCU 检查是两份接收驱动的对象编译，不是用户完整工程的链接与板上运行验证。App 的蓝牙连接/提交计数不是 STM32 收包确认。

首次联调请观察 `RC_GetState()` 的 `link_valid`、`valid_frames`、`crc_errors` 与控制值，按协议验证关闭使能、急停、后台断开以及 300 ms 超时归零。确认这些行为后再将控制值接入业务程序。
