# Scan Compatibility Implementation Plan

> Use inline executing-plans and requesting-code-review.

**Goal:** 按调试器双路发现，解除扫描权限策略差异，仍仅 BLE 连接。
**Architecture:** RemoteViewModel 管理双路扫描生命周期，DeviceRow 合并来源；Manifest/Activity 处理定位权限与说明。
**Tech Stack:** Android Bluetooth、Kotlin、Compose、Android instrumentation、GitHub CI。

- [ ] 先提交 scannerRequestsLocationLikeBtMcu，确认旧应用权限测试失败。
- [ ] 改权限和双路扫描，按地址保留名字并合并来源，展示每路结果/错误。
- [ ] 验证生命周期和接收器释放，补设备合并与 Manifest/定位测试。
- [ ] 运行完整 CI 并独立审阅，修复实质问题。
- [ ] 本机签名 APK 1.4.2，发布并记录验证边界。
