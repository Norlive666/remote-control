# v1.3.2 — 删除经典蓝牙

- 删除 Android HC-05 经典 SPP 连接与界面选项，默认 BLE 串口 FFE0/FFE1、20 Hz；保留 BT630 FFF0/FFF2、50 Hz。
- 旧 HC05 或空设置回落到 BLE 串口；其他设置与 STM32 协议不变。
- 回归测试先在旧代码失败：预期 BLE_UART，实际 HC05（运行 38104475361）。
- [正式验证运行 38104890988](https://github.com/Norlive666/remote-control/actions/runs/38104890988)，构建提交 `1be51a25c4798597803c5e431a8489ff3fa809f6`：30 单元测试、14 Android 15 横屏界面测试全部通过；两套 MCU 主机测试与目标编译通过。独立代码复核无重要问题。
- 本机签名 APK：versionCode 6；SHA256 `395d2e35bc0abe8ddaae908a2ed5c3644bcaa22bc7a569e65203b28fd00e2518`；原证书 `27a45a0d6cddcf3952d44cd114034833a245a8029af3fd355b6c1a9eb7563935`，v2/v3 验证通过。密钥仍仅在本机。
- 用户报告 v1.3.1 BLE 串口可连接；v1.3.2 未在真实手机与模块验证，STM32 实际收包仍需用户测试。

# v1.3.1 验证记录

增加BLE串口模式：FFE0服务、FFE1收发、20Hz；保留HC-05经典SPP20Hz与BT630 FFF0/FFF2 50Hz。BLE模式覆盖Android8–11定位权限，保存模式时兼容旧设置。HC05模式遇到仅LE设备提示切换。

参考用户提供 [bt_mcu的BlueToothMaster](https://gitee.com/xie-rongji/bt_mcu/blob/master/app/src/main/java/xnj/lazydog/btcontroller/Bluetooth/BlueToothMaster.java) 默认BLE配置（服务FFE0、收发FFE1）。实际经典分支仍用安全SPP，与旧版相同；未将此前连接失败断定为安全/非安全差异，相关试验已撤回。本次独立审查无重要遗留问题。

- Android单元测试30项通过；Android15横屏模拟器交互13项通过，新增BLE模式选择、20Hz周期、配置重载与三模式切换，原摇杆/滑块/布局12项回归通过。
- Android debug/release APK、两套STM32主机行为测试与目标源编译通过。实际界面截图核对，面板布局保持。
- 最终运行源码 `5ba92cee0e60ecaa5f717cd9041894ca980075ce` 的 [GitHub Actions 38103590544](https://github.com/Norlive666/remote-control/actions/runs/38103590544) 全部成功；APK来自该次未签名release产物，在本机以原密钥签名，v2/v3与对齐校验通过。versionCode5/versionName1.3.1。
- APK SHA256：`62474c6144f5f9643a6879fbb568491ae2c5c8618499fed723e0462fe58f89ba`。
- 签名证书SHA256：`27a45a0d6cddcf3952d44cd114034833a245a8029af3fd355b6c1a9eb7563935`，与旧版一致；密钥未上传。

用户模块被判断可能走BLE，但尚未拿到其实际服务列表，未连接真实手机/蓝牙模块/开发板，本次未证明实物连接问题已修复。FFE0/FFE1模式仅覆盖参考程序默认配置，不覆盖任意自定义UUID。模块UART波特率仍需与STM32一致。

---

# v1.3.0 验证记录

- 蓝色摇杆区域在白色卡片内独立移动；模块选择与大小滑条支持 50%～150% 请求倍率，受最低可操作尺寸及容器边界限制。外部卡片统一缩放保持宽高比，内部操作区域保持正方形；位置与大小共享保存、取消与恢复默认，并在重新打开后保留。
- 滑块保留原轨道与旋钮，增加白色刻度及档位吸附。默认实际值 0、1、2，可分别设为连续或 2～8 档，值为 0～1000 且不重复；应用后回到第一档，重新使能恢复有效档值。线上的字段与 STM32 接收驱动不改变。
- 独立代码审查提出的比例、重复档位及大屏实际操作区域尺寸问题均已修正并复核。

- Android 单元测试 30 项通过（协议 7、布局 11、摇杆 6、滑块 6）；Android 15 横屏模拟器交互测试 12 项通过，覆盖蓝色区域移动不移动白卡、大小保存/重新加载/取消/容器边界、档位实际值与设置持久化、双指/归零/单轴/死区/输出范围及原布局回归。
- Android debug/release 构建、两套 STM32 主机测试及目标源文件编译通过；实际截图已核对。
- versionCode 4 / versionName 1.3.0，在本机以原密钥签名；v2/v3 验签及对齐通过。

运行代码 `e1af8bb04a17cbcc125b95cc0b96a1a44624954f` 的 [GitHub Actions 38058889356](https://github.com/Norlive666/remote-control/actions/runs/38058889356) 全部成功。APK 来自该次构建；后续文档与截图提交不改变运行代码。

APK SHA-256：`f6c0979550f62c15897d9c5908d0ffd6b0b8381e2621093dbbab5af2c753cd34`。

证书 SHA-256：`27a45a0d6cddcf3952d44cd114034833a245a8029af3fd355b6c1a9eb7563935`，与旧版一致。密钥与密码只保留本机。

尚未连接真实手机、HC-05、FSC-BT630 或开发板；无线距离、实际延迟和板上通信仍需实测。

---

# v1.2.0 验证记录

- 删除底部连接、复位、急停按钮及 HC-05 白色状态横条，编辑布局时保留操作工具栏。
- 两个摇杆分别保存死区（0～30%，默认 5%）、最大绝对值（1～1000）与自由／仅横向／仅纵向模式。死区外线性映射，方向限制、松手、取消和配置变更归零。
- Android 单元测试 22 项通过（协议 7、布局 9、摇杆计算 6）。
- Android 15 横屏模拟器交互测试 10 项通过，覆盖按钮和状态条删除、设置应用与重新加载、双指独立操作、模式切换、旧触摸取消与新触摸恢复、死区、范围、布局草稿/保存/取消/恢复及边缘限位。设置输入测试包含点击输入框并滚动操作；这是模拟器操作序列验证。
- Android debug/release APK 编译、两套 STM32 主机行为测试与目标源文件编译通过；协议和 STM32 源文件没有改变。
- [实际截图](controller.png)已核对。独立代码审查没有留下重要问题。
- APK versionCode 3 / versionName 1.2.0，在本机使用原密钥签名，v2/v3 签名和对齐验证通过；签名密钥与密码未上传。

最终运行代码 `6055aea5869b34e5877a12f691257eb112b7caa4` 的 [GitHub Actions 38055858231](https://github.com/Norlive666/remote-control/actions/runs/38055858231) 全部成功。APK 来自此构建的未签名 release 产物，后续文档和截图提交不改变运行代码。

APK SHA-256：`cdf560fc3bfec20e9e6002ad6b390abcc59411e907419c0fd3880ec4da1c1c72`。

证书 SHA-256：`27a45a0d6cddcf3952d44cd114034833a245a8029af3fd355b6c1a9eb7563935`，与旧版一致，支持覆盖升级。

尚未连接真实手机、HC-05、FSC-BT630 或开发板；无线距离、实际延迟和板上通信仍需实测。

---

# v1.1.0 验证记录

- 采用用户提供 HTML 的卡片布局，并保留八个独立可拖动组件。
- Android 单元测试 16 项通过：原协议 7 项、位置/边界/草稿保存取消恢复默认 9 项。
- Android 15 横屏模拟器交互测试 8 项全部通过：双摇杆多点触摸与独立归中、急停锁定、顶部/中央布局、拖动草稿与取消、保存重新加载/界面重建/恢复默认、编辑时控制量禁用、使能卡片边缘限位、六字功能键完整字形显示。
- Android debug / release 编译、两套 STM32 主机测试和目标源文件编译通过；本次没有修改协议或 STM32 源文件。
- 实际运行截图见 [controller.png](controller.png)。代码审查未留下重要问题。
- APK versionCode 2 / versionName 1.1.0，在本机用原密钥对齐、签名及校验，v2/v3 签名通过。证书与 v1.0.0 一致，密钥和密码只保留本机。

运行代码 `7172367` 的 [GitHub Actions 38050449928](https://github.com/Norlive666/remote-control/actions/runs/38050449928) 三个任务全部成功。正式 APK 来自该次构建的未签名 release 产物。后续文档和截图提交不改变运行代码。

签名证书 SHA-256：`27a45a0d6cddcf3952d44cd114034833a245a8029af3fd355b6c1a9eb7563935`。

尚未连接真实手机、HC-05、FSC-BT630 或开发板；无线距离、实际延迟和板上运行仍需实测。沿用下列 v1.0.0 协议与接入边界。

---

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
