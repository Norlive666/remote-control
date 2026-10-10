# Layout editor Implementation Plan

> For agentic workers: use subagent-driven-development for the isolated model task and review; primary owns Android UI integration.

Goal: free positioning of two sticks, two sliders, enable control and three function keys, with save/cancel/default and a signed upgrade APK.
Architecture: normalized top-left positions represent a fraction of the space remaining after subtracting each widget's size. A pure Kotlin edit session separates saved positions from a draft. ViewModel persists committed positions and disables control while editing; Compose renders independent widgets and consumes drag gestures in edit mode.
Tech Stack: existing Kotlin / Compose / SharedPreferences; no new dependencies.

## Global constraints
- Approved spec: ../specs/2026-10-10-layout-editor-design.md.
- Only position editing; fixed settings/toolbar/title/status; no resize or additional controls.
- Editing turns off enable and zeros axes, blocks control changes and enable; save/cancel never re-enable.
- Bluetooth may continue sending disabled frames; protocol and STM32 are unchanged.
- Saved overrides have stable IDs: leftStick, rightStick, leftSlider, rightSlider, enable, key0, key1, key2.
- Use the user-provided Clean Robot Remote Controller.html as the responsive default; keep each item inside its available canvas.
- v1.1.0 / versionCode 2, same local signing key, source and release uploaded as previously authorized.

### Task 1: pure position and edit model
Files: PanelLayout.kt and PanelLayoutTest.kt in existing main/test package. No other edits.
Interfaces: PanelPosition(x:Float,y:Float); PanelOffset(x:Float,y:Float); panelOffset(position,canvasWidth,canvasHeight,itemWidth,itemHeight):PanelOffset; movePanelItem(position,dx,dy,canvasWidth,canvasHeight,itemWidth,itemHeight):PanelPosition. LayoutEditSession(saved:Map<String,PanelPosition> = emptyMap()) exposes saved, draft, editing, positions (current map), begin(), move(id,position), save(), cancel(), restoreDefaults().
- [ ] Write behavior tests with literal expected pixel/normalized values, bounds and zero remaining space; stub initially returns defaults so tests fail in CI before implementing.
- [ ] Write tests for draft isolation, cancel, save, restoring default only in draft, ignored movement outside editing; implement minimal model after red evidence.
- [ ] Commit owned files; task review for spec and code quality.

### Task 2: ViewModel and Compose integration
Files: RemoteViewModel.kt, MainActivity.kt, new EditablePanel.kt, ControllerUiTest.kt and app/build.gradle.kts.
- [ ] Before UI implementation add a UI test opening settings and clicking 编辑布局; verify absent entry fails on old production UI via CI.
- [ ] Load/save eight stable IDs through SharedPreferences. Begin edit calls session.enable(false), synchronizes controls, and begins layout draft; all axes/slider/enable/reset inputs gated during edit. Save writes committed overrides, cancel discards, restore affects draft only.
- [ ] Replace coupled Channel columns with independently positioned slider/stick/enable/key widgets. Editing uses outer drag handling with disabled children, outline and label. Title/status/settings/footer stay fixed; edit toolbar always available. Use effective normalized position and panelOffset/movePanelItem from Task 1.
- [ ] Test: dragging moves widget, axes remain zero, cancel restores, save persists across Activity recreation, reset default, screen edge clamp. Preserve independent multi-touch and stop tests.
- [ ] Increment version and run unit/compile/emulator checks; inspect exported screenshot.

### Task 3: review and signed delivery
- [ ] Review only changed layout model, persistence, gesture handling and edit safety; fix confirmed findings and repeat affected verification.
- [ ] Local sign latest unsigned release APK with existing key; verify signature, versionCode 2, alignment and certificate continuity.
- [ ] Document editing and verified boundaries, upload source; release v1.1.0 APK/checksums. Verify remote asset digests match local files.

### 用户追加：HTML 布局调整
- [ ] 更新 UI 验收：顶部功能入口、两个滑块在摇杆上方，中央蓝牙使能卡片位于左右摇杆之间；长名称通过 TextLayoutResult 确认完整显示，避免系统字体像素换算假设。
- [ ] EditablePanel 改三列卡片布局，保留八个稳定 ID 和草稿拖动；MainActivity 的颜色/摇杆与按钮绘制匹配 HTML。
- [ ] 所有交互测试和构建通过后检查实际截图，使用本机密钥签名发布。
