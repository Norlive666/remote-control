# 摇杆设置实现计划

> **For agentic workers:** 使用 executing-plans 按步骤实施已获用户批准的设计。

**Goal:** 删除冗余按钮，提供独立死区、方向模式与数值范围，并交付兼容升级 APK。

**Architecture:** 新增纯 Kotlin JoystickConfig 输出映射；RemoteViewModel 保存设置并处理清零；现有 Compose 卡片接入参数与模式按钮。

**Tech Stack:** Kotlin、Compose、SharedPreferences、JUnit、Android 模拟器。

## Global Constraints
- 范围 1～1000；死区 0～30%；初值 1000、5%。
- 协议、STM32 驱动及蓝牙发送频率不变。
- 签名凭据只留本机。

### Task 1: 输入映射
- [ ] 新建 JoystickConfigTest.kt：5% 死区下输入 (525,40) 输出 (500,0)；横向 (1000,1000) 输出 (1000,0)；纵向输出 (0,1000)；设置最大值 255 对应端点 ±255。
- [ ] 运行测试，确认因缺少 JoystickConfig 而失败。
- [ ] 新建 JoystickConfig.kt：JoystickMode 枚举、配置与 map(x:Int,y:Int):Pair<Int,Int>，独立线性死区映射与限幅。
- [ ] 全部 Kotlin 单元测试通过。

### Task 2: 接入界面与保存
- [ ] RemoteViewModel.kt 添加 stickConfigs、setStickConfig(index:Int,config:JoystickConfig)、cycleStickMode(index:Int)，配置更改清零该摇杆；axes 调用 map。
- [ ] 删除 FunctionKey、keys、setKey 和三个按钮位置 ID；EditablePanel.kt 删除按钮与测宽，摇杆底部添加方向切换。
- [ ] MainActivity.kt Joystick 接收 config，用 config 作为 pointerInput 键取消旧手势，绘制按 maxValue 缩放；SettingsDialog 添加两个摇杆死区滑块与范围输入及应用按钮。
- [ ] ControllerUiTest.kt 保留布局/双指测试，替换删除按钮测试为模式、死区、范围与保存测试。

### Task 3: 验证交付
- [ ] 更新使用说明，versionCode 3 / versionName 1.2.0。
- [ ] 运行 Kotlin、C 测试和 diff 检查；独立代码审查并修复重要问题。
- [ ] 推送源码，GitHub Actions 验证构建与模拟器测试，检查截图。
- [ ] 下载未签名 APK，本机签名并验证相同证书、版本与 SHA256，发布 v1.2.0 APK 和校验文件。
