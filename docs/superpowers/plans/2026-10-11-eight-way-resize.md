# Eight-way joystick and resizing Implementation Plan

**Goal:** 八向连续力度输出与30%–250%组件等比缩放。
**Architecture:** 保留现有Compose组件和持久设置；JoystickConfig处理八向数学，PanelLayout处理比例边界，EditablePanel负责内容等比渲染。
**Tech Stack:** Kotlin、Compose、现有Nordic BLE。

## Task 1: Eight-way output

- [x] JoystickConfigTest增加八方向、分界角度、径向死区、连续力度和自定义范围测试；旧模式fallback只用于测试旧代码。
- [x] 通过CI查看旧版断言失败。
- [x] JoystickMode末尾加入EIGHT_WAY，旧模式序号及保存的名字不变；map使用atan2按PI/4吸附，hypot限1000，径向死区后映射maxValue，再按cos/sin投影并roundToInt。
- [x] MainActivity显示八向斜线；设置说明包括八向径向死区；ControllerUiTest验证模式循环、保存、手势和松手归零。

## Task 2: Resizing

- [x] PanelLayoutTest验证.3和2.5可保存，越界值限幅，取消保留已保存数据。
- [x] LayoutEditSession.resize、RemoteViewModel设置加载、EditablePanel大小滑条统一.3..2.5。
- [x] PositionedItem保留现有位置计算；比例最小值为基础尺寸*.3，最大值受画布限制；内容按原始尺寸测量并通过placeWithLayer等比缩放，外框用实际尺寸，避免内容裁剪。
- [x] StickControl蓝色区域去掉80dp下限，使用基础边长*.3至可用边长，仍在卡片内部独立移动。
- [x] ControllerUiTest验证两滑块都能缩小、扩大、保存重载，蓝色摇杆扩展大小及取消恢复。

## Task 3: Verification and release

- [x] 检查差异与独立代码复核，运行全部单元、Android横屏界面、C主机及目标编译检查。
- [x] 提升到v1.4.0/versionCode7，原本机密钥签名；核对版本、证书、上传文件哈希；更新截图、说明、验证记录并发布APK。

## Task 4: 用户追加的下移自由度

- [x] 两白色卡片向下拖动、两蓝色区域移动到画布底部并保存重载的回归测试。
- [x] 将蓝色区域提升为整个画布上的独立组件；白色卡片默认高度为主区域80%。
- [x] 旧坐标通过初始旧布局的实测bounds一次性换算为画布坐标并持久化。首次无旧位置无需换算；转换完成后再进入编辑。
- [x] 更新原有卡片内部边界断言为画布边界；执行全部测试、代码复核并签名发布。
