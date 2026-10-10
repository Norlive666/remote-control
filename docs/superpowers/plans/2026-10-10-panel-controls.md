# 分档滑块与模块大小实施计划

> **For agentic workers:** 使用 executing-plans 按已确认设计执行。

**Goal:** 保留滑块外观，提供档位配置、内部摇杆移动及模块缩放，交付 v1.3.0。

**Architecture:** SliderConfig 负责归一化输入到档位数值；LayoutEditSession 扩展缩放草稿。ViewModel 保存偏好，Compose 在既有卡片和底部编辑工具栏接入。

**Tech Stack:** Kotlin、Compose、JUnit、Android 模拟器。

## Global Constraints
- 分档默认 0、1、2，档数 2～8，实际值 0～1000。
- 缩放 50%～150%，旧布局兼容，取消不落盘。
- 协议、STM32、蓝牙频率不改变，密钥只留本机。

### Task 1: 数据行为
- [ ] 新增 SliderConfigTest：输入 0/249/250/749/750/1000 对应 0/0/1/1/2/2；自定义 [0,10,100] 的中档输出 10；连续模式 420 输出 420；线上的实际值为 10。
- [ ] PanelLayoutTest 增加 resize("leftPad",0.6f) 草稿不改保存，cancel 恢复、save 持久、defaults 仅清草稿。
- [ ] 运行测试确认新功能缺失，新增 SliderConfig.kt 并扩展 LayoutEditSession(saved, savedScales) 的 scales/resize，测试通过。

### Task 2: 界面接入
- [ ] RemoteViewModel 加入 sliderConfigs 与 setSliderConfig；slider 先映射，enable 对分档值校正，读写 layout.scales。
- [ ] EditablePanel 的 SliderControl 使用 steps 和白色短刻度，显示实际值；连续模式原外观及 50% 按钮保留。
- [ ] PositionedItem 读取倍率、限制容器尺寸、点选/拖动；摇杆内部增加 leftPad/rightPad，父卡片内容和标签固定。
- [ ] 编辑工具栏提供模块选择和大小滑条，设置页提供类型、档数、档值与应用。
- [ ] 模拟器测试独立内部拖动、尺寸持久化、取消与边界，分档输入和设置持久化；回归双指、模式、布局。

### Task 3: 交付
- [ ] 代码审查；Kotlin、C 测试、APK 构建与模拟器通过，核对截图。
- [ ] versionCode 4 / versionName 1.3.0，更新说明和验证记录。
- [ ] 上传源码，下载匹配 CI 产物，本机原密钥签名并验证，发布 APK 与 SHA256。
