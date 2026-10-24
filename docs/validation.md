# v1.4.2 — 蓝牙扫描兼容

- 按 bt_mcu commit10dddc6 对齐扫描：默认 BLE 扫描与系统发现并行，按地址合并来源/保留名字；系统结果只列LE/DUAL，仍仅BLEGATT连接。
- 移除 neverForLocation；所有支持版本申请COARSE/FINE，Android12+另申请附近设备；定位开关关闭时提示。部分授权后重试精确定位必须保持COARSE/FINE成对，已补回归测试。
- 20秒兜底，系统结束后BLE继续；单路失败保留另一条；连接/后台/退出搜索窗口停止并注销接收器。设备行显示来源，扫描诊断显示原始结果条数、可选数及错误。
- 原权限测试先在旧版失败（38260133139）。[最终验证38264625892](https://github.com/Norlive666/remote-control/actions/runs/38264625892)，源码 `47a9fed742201712711ea444af616290b725b12a`：36单元、25Android15横屏界面测试全部0失败/0错误/0跳过，STM32任务通过。覆盖权限、Manifest、部分授权重试、来源合并、后台释放接收器和迟到BLE回调。独立复核提出的精确定位重试问题已修正并复审通过。
- versionCode9/versionName1.4.2；Google官方BuildTools35包校验SHA1 `2cfaa0bbb2336e9ec18ed3ecea84fa2e2af607bc`，工具持久缓存于 tools/.cache/android-15（不提交）；仅本机原密钥签名，v2/v3通过；证书SHA256 `27a45a0d6cddcf3952d44cd114034833a245a8029af3fd355b6c1a9eb7563935`。
- [已发布APK](https://github.com/Norlive666/remote-control/releases/tag/v1.4.2)，SHA256 `54b392b97c7090381b8f0e319806ab5deaaada0aad68bfeb9aadfbe0cf64d26d`，GitHub资产校验一致。
- 未在用户手机与真实BT630上确认广播可见性或通信；这些测试不等于用户实机漏扫已经解决。四向、布局和协议保持原行为。

# v1.4.1 — 四向摇杆

- 八向替换为四向：比较原始坐标绝对值选横/纵，另一轴始终零，45° 相等时优先纵向；保留径向死区、连续力度和 ±1～±1000 范围。外圈不会因逐轴截断而误选方向。
- EIGHT_WAY 旧设置自动读取为 FOUR_WAY，保留范围、死区和布局；模式按钮、说明和引导线更新。STM32 和 20 字节协议不变。
- [最终验证 38148761027](https://github.com/Norlive666/remote-control/actions/runs/38148761027)，源码 `db209043ecfdeefd05aa94aba4e2a5d4b58010be`：36 单元、20 Android15 横屏界面测试全部 0 失败/0 错误，STM32 两套协议、25 初始化模拟和编译链接通过。独立代码复核通过。
- 回归先在原八向实现失败（38147500096）：输出 707/707 与四向期望不符。首次新实现界面验证的四向与迁移场景通过；误改的自由模式测试坐标导致一项失败，恢复原测试坐标后完整通过，App 内容未改变。
- versionCode8/versionName1.4.1，本机原密钥签名 v2/v3 通过；证书 SHA256 `27a45a0d6cddcf3952d44cd114034833a245a8029af3fd355b6c1a9eb7563935`。发布 APK SHA256 `8a0b6307267e8c997a0395bf59a9c606358ab2557267692cdee20fbc7f177c71` 与 GitHub 资产一致。
- 未在真实手机和开发板验证手感与端到端通信。

# STM32 驱动 v2.0.0 — 两文件接入

- USART1/TIM4 使用寄存器配置和私有中断入口；不再依赖 HAL UART/TIM 初始化及生成的 uart.c/tim.c，不再导出同名 IRQ。
- 初次初始化发现外设时钟或目标 IRQ 已启用时返回 HAL_BUSY，不修改原外设/向量/g_rc；F103 保留重映射冲突也返回忙并恢复 AFIO 时钟门。相同 baud 重复初始化保留数据。
- 使用固定版本 ST 官方 CMSIS/设备头文件及 HAL：F103/H723 两份源码在 HAL 配置未开启 UART/TIM/GPIO/DMA/CORTEX 模块时均通过 -Wall -Wextra -Werror 编译，且与强 USART1_IRQHandler/TIM4_IRQHandler 一起链接成功。链接桩只提供 RCC 时钟查询和 C 库 memmove，无 UART/TIM HAL 符号。
- 25 个初始化/中断主机模拟场景通过，覆盖两个目标的占用、参数/时钟错误、9600/115200、PRIMASK 保留、向量入口保留、H7 cache-clean 分支、APB/TIMPRE、幂等调用、实际驱动 ISR 收包和 300 tick 断连归零。使用官方设备寄存器结构；CMSIS、时钟查询和寄存器内存由测试替身提供，不模拟硬件自动位清除或真实缓存。两套原协议测试通过。
- 新增回归先发现 F103 在 AFIO 时钟关闭但存在保留重映射时误返回 HAL_OK；修复后该场景通过。原驱动在最少 HAL 模块配置下无法编译，新驱动通过。
- F103 既有 HC-05/OLED 工程的临时副本替换这两个文件，移除 HAL UART/TIM 模块及源文件后完整构建、链接通过。FLASH 14400 B、RAM 3312 B；符号表存在私有 uart_irq/timer_irq 和对齐 RAM vectors，无 HAL_UART/HAL_TIM 符号。保留原 OLED_Data.c 的既有初始化器警告，未修改实际用户工程或烧录。
- [GitHub STM32 验证任务 38138926767](https://github.com/Norlive666/remote-control/actions/runs/38138926767) 已通过，对应源码提交 `ec96237ddfb7c5f8f72f355151e45120d058d830`；本条只记录 STM32 任务结果。驱动 ZIP 发布在 [stm32-v2.0.0](https://github.com/Norlive666/remote-control/releases/tag/stm32-v2.0.0)，GitHub 资产 SHA256 与本机一致：`3aa502ed92cf647c903256fe7fe6213923086825d18f84d50c8bfad5e43abe1e`。
- 独立代码审阅未发现未处理的重要问题。Android App 和 20 字节协议未修改，APK 继续使用 v1.4.0。
- 尚未验证真实 F103/H723 的 UART 接收、TIM4 时序、向量迁移、缓存和无线链路；以上编译、链接及模拟测试不能替代开发板运行。

# v1.4.0 — 八向摇杆、缩放与下移

- 八向方向每45度吸附，径向死区与连续力度；±1000范围下轴向满量程1000、斜向约707/707。越过满量程半径仍保持原角度，松手归零。
- 组件等比缩放30%–250%；两滑块可缩小，蓝色摇杆在整个操控画布独立移动/缩放，白色卡片默认高度80%留出下移空间。升级一次性换算旧蓝色位置及显示尺寸，其他设置保留。
- [最终验证 38107341311](https://github.com/Norlive666/remote-control/actions/runs/38107341311)，提交 `53acd840087dcda938f557da7957a60f58feed24`：35单元测试、19项Android15横屏界面测试均为0失败/0错误；两套STM32主机测试和目标编译通过。界面覆盖八向触摸/边缘方向/归零、滑块縮小、缩放后触摸、两卡片下移、蓝色区域画布底部位置、旧设置转换与重载。独立复核确认角度及迁移尺寸问题已修正。
- 回归测试曾在旧行为失败：缺八向/.3缩放（38106025846）、边缘角度误吸附（38106245256）、左卡片无法下移（38106646279）。
- 本机原密钥签名：versionCode7，v2/v3通过；证书SHA256 `27a45a0d6cddcf3952d44cd114034833a245a8029af3fd355b6c1a9eb7563935`；APK SHA256 `978bca6af93ab8455cd33da29e491f29e3d653052d4b03cb272076c4d57ba996`。
- 新版未在真实手机、蓝牙模块与开发板通信验证；模拟器触摸/源码编译不能替代实物手感与通信测试。

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


## v1.5.0 实体手柄与按键映射（2026-10-20）

运行源码 `5efecf8390dfc412eaa092820a82daaa2d535029` 已通过 [Actions 39387169669](https://github.com/Norlive666/remote-control/actions/runs/39387169669)，Android、STM32、UI 三个任务全部成功。

- 44项JUnit单元测试、31项Android 15横屏界面测试，失败/错误/跳过均为0。
- 覆盖默认与自定义映射、档位协调、配置持久化与恢复、按下设值松开保持、重复事件、60%/40%阈值、数字先到的扳机事件、原生Activity事件路由、失焦与移除清零，以及既有触屏/布局行为。
- 界面测试曾复现元数据缺失时选错备用轴、无手柄弹窗误清零，修正后全量通过。
- STM32两套协议解析、25项初始化模拟测试、两套ARM编译链接通过，本次未改变STM32驱动和20字节协议。
- unsigned release APK下载后仅在本机签名，v2/v3签名和对齐校验通过；证书与旧版相同。
- APK为versionName=1.5.0、versionCode=10，10824718字节。
- SHA-256：`f4bf7b4e1d68a1d8b83d5455865cc85253d580e9e83837bac6089bcbe62f7401`；已核对GitHub Release资产摘要一致。

此轮未连接X5 Lite或真实安卓手机。模拟事件验证不能代替实物USB识别、具体摇杆轴/扳机上报、蓝牙链路与STM32实际接收验证。


## v1.5.1 手柄设备选择修复（2026-10-20）

源码`6b64724a1222219a0cd64bdc0ab4871fc649f780`通过[Actions 39443383884](https://github.com/Norlive666/remote-control/actions/runs/39443383884)，三个任务均成功。

- 44项单元测试、32项Android 15横屏界面测试，失败、错误和跳过均为0；STM32回归任务通过。
- 在本机用真实GamepadInput和最小Android依赖模拟复现首项uinput-xiaomi锁定后拒绝另一ID真实摇杆的路径，修复前失败、修复后通过；零死区首次1%输入另行先失败后通过。
- 新增Android回归覆盖：错误旧设备ID占用→真实输入接管→旧设备空闲零值不抢选→当前设备回零，以及首次接管遵循零死区配置。
- 本机签名APK versionName=1.5.1、versionCode=11，10824718字节，v2/v3签名与对齐校验通过，证书保持一致。
- SHA-256：`4b95be1712a67a5292f5fc17c7ec36c0ee4afe05b83745041efb09f1bc6d8dfb`，已核对GitHub公开资产摘要。

此轮未连接用户小米手机或X5 Lite。自动测试确认代码拦截缺陷已修复，不代表已经证明用户实机无响应仅由此缺陷导致。新版的设备名、事件ID/输入源与原始轴值用于进一步实测定位；未按uinput-xiaomi名称屏蔽输入。


## STM32驱动v2.1.0：H723 USART3/PD8/PD9/TIM5（2026-10-22）

H723改为USART3 PD8 TX、PD9 RX（AF7），USART3内核时钟读取RCC_PERIPHCLK_USART3，TIM5约1ms；占用检查、私有中断和RAM向量同步迁移。初始化API、波特率、协议和全局结构体不变；F103驱动未修改。

已用本机工程的官方ST依赖执行两套协议解析测试、28项初始化模拟测试及两目标ARM编译/与生成强IRQ入口共存链接，全部通过。测试先确认旧H723错误读取USART1内核时钟而失败，再迁移驱动；覆盖USART3/TIM5时钟或IRQ占用拒绝，以及原USART1/TIM4启用时新驱动仍正常且旧外设、GPIOA、旧IRQ不变。未烧录或验证真实UART波形、收包及1ms周期。


## STM32驱动v2.1.1与v2.2.0：时钟修复、空闲串口接入与反相（2026-10-24）

v2.1.1修复H723 UART内核时钟查询：本机官方H7 HAL的HAL_RCCEx_GetPeriphCLKFreq不支持USART查询并返回0。新实现根据USART234578实际内核时钟选择读取PCLK1、PLL2Q、PLL3Q、HSI、CSI或LSE，不修改共享时钟选择。此前模拟器给该通用查询返回有效频率，未暴露真实HAL限制；已修正该替身。

v2.2.0允许CubeMX已初始化但未启动收发的UART接入，包括目标NVIC已开启的情况。启用DMA请求、收发/错误等中断或发送器尚未发送完成时，Init返回HAL_BUSY并保留原状态；定时器占用及F103重映射保护不变。头文件提供RC_DEFAULT_BAUD和RC_RX_INVERT，F103拒绝非零接收反相配置。

- 两套协议行为测试、68项初始化行为测试、两项非法反相编译拒绝检查通过。
- 两目标使用官方HAL/CMSIS头文件、无UART/TIM HAL模块的ARM编译及与生成强IRQ共存链接通过。
- H723原工程保留MX_DMA_Init和MX_USART3_UART_Init，随后调用RC_Init；头文件配置115200与接收反相。工程编译通过，主程序反汇编确认初始化顺序；29项其他已保存文件摘要不变。当前仅运行接收快照，没有启动电机或D-Bus接收业务。
- 用户提供的原理图表明CN14第3脚经Q5 S8050反相后进入USART3_RX；该板需开启接收反相，直接PD9则不反相。用户加载新版后报告HAL_OK、valid_frames持续增长。这是该H723板卡、BT630和手机链路的用户实测反馈；F103已初始化串口路径本轮只完成自动测试，未进行F103实物复测，也未验证长期运行或底盘/云台控制。

新版本不修改20字节协议或Android应用，不自动接管已有活动接收业务，不做D-Bus到业务rc_ctrl的数据映射。
