# Four-way Joystick Implementation Plan

> **For agentic workers:** Use inline executing-plans and requesting-code-review before delivery.

**Goal:** 四向替换八向，45° 优先纵向，保留力度/死区/范围和旧布局。
**Architecture:** JoystickConfig 纯函数按原始坐标选主轴，RemoteViewModel 读取旧模式别名，UI 去除斜线。
**Tech Stack:** Kotlin、Jetpack Compose、JUnit、Android CI，本机签名。

- [x] 修改 JoystickConfigTest，让原八向映射接受四向期望；CI 验证四个行为测试先失败。
- [x] JoystickConfig EIGHT_WAY 替换为 FOUR_WAY，输出按 abs(x)>abs(y) 选 X，否则选 Y；保留 hypot 力度。
- [x] 读取 EIGHT_WAY 为 FOUR_WAY；更新界面模式和说明，去掉八向斜线。
- [x] UI 测试四向拖动、松手、重载、模式循环；增加旧八向偏好迁移并保留范围/死区测试。
- [x] 版本 1.4.1/code8，更新 README，运行单元/界面/C 回归、独立审阅。
- [x] 下载通过测试的未签名 APK，本机原密钥签名，上传 GitHub Release 并核验校验和。
