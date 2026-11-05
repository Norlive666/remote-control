# 手机浏览器读写 g_rc（最简方案）

> **已被 v2 取代（2026-11-04 22:30）：** 实际实现改为 App 内的「调试链路」——复用摇杆/滑块/使能等现有操作逻辑，
> 把同样的 20 字节帧经局域网交给电脑桥接器写 `g_rc`。实现见 `tools/rc-tune/tune.py`、`tools/rc-tune/README.md`、
> `android/app/src/main/java/io/github/norlive/remotecontrol/TuneLink.kt` 与 `DebugLinkSettings.kt`。
> 本文档的浏览器页面部分不再执行，桥接器设计（DWARF、读改写、看门狗、只读默认）仍然适用。

**目标：** 手机浏览器打开电脑上的页面，实时查看并修改 STM32 上的 `g_rc`（`RC_State_t`）。**不改 App、不改驱动、不改协议、不新增 App 权限。**

**参考文档：** `docs/superpowers/plans/2026-11-04-remote-control-global-struct-handoff.md`（本文是它的简化替代，不是它的执行记录）。

---

## 1. 与参考方案的差异

| 参考方案 | 本方案 | 原因 |
| --- | --- | --- |
| 新增 App 页面 + OkHttp + INTERNET 权限 + 重签 APK | 手机浏览器打开网页 | 功能只在调参时用，不值得为它改 App、重打包、教用户装新 APK |
| WebSocket 10 Hz 推送 | HTTP 轮询 2 Hz | 调参看的是几个小时才变一次的参数；轮询的断线/过期语义天然简单 |
| FastAPI/Uvicorn + 独立事件循环 + 串行 worker + 写请求去重 | `http.server` + 一把 `Lock` | 单用户、2 Hz，并发复杂度为零；少 500 行和 4 个依赖 |
| 数组成员不支持 | 数组展开成 `axes[0]`…`sliders[3]` | `g_rc` 一半的成员是数组，不支持等于不能用 |
| 必须是 ARM ELF、按目标架构拒绝 | 只解析 DWARF，写入前按 RAM 地址校验 | 少一条无用门槛，测试夹具可以直接用本机 GCC 编译 |

保留参考方案里真正必要的两条：**默认只读，`--allow-write` 才可写**；**局域网令牌**（否则同一 WiFi 上任何人都能改机器人状态）。

---

## 2. 使用方式

```bash
# 电脑上一次性准备
python3 -m venv .venv && . .venv/bin/activate
pip install "pyocd>=0.36" "pyelftools>=0.31"

# 看探针
python tools/rc-tune/tune.py list-probes

# 启动（默认 127.0.0.1 且只读；给手机用要 --host 且 --allow-write）
python tools/rc-tune/tune.py serve \
  --elf build/robot.elf --symbol g_rc --target stm32h723vg \
  --host 0.0.0.0 --allow-write
```

终端输出：

```text
ELF   build/robot.elf  sha256=3f9a…c21
符号  g_rc  @ 0x200001a0  28 B  (DTCM, 不经过 D-Cache)
探针  ST-Link/V2-1 CMSIS-DAP 0x0A1B2C3D
只读  ☐ 可写  令牌 4821
手机  打开 http://192.168.1.23:8765/?t=4821
```

手机浏览器打开那个 URL 即可。`?t=` 只用于首次拿令牌，页面存在 `sessionStorage`，刷新不用重输。

退出：`Ctrl-C`。服务不会 halt、reset、erase、flash 目标，也不改变 CPU 运行状态。

---

## 3. 目标结构体 `g_rc`

定义在 `stm32/h723/remote_control.h`：

```c
typedef struct {
    int16_t  axes[4];       /* -1000..1000 */
    uint16_t sliders[4];    /* 0..1000 */
    uint8_t  enabled;
    uint8_t  stopped;
    uint8_t  sequence;
    uint8_t  link_valid;
    uint32_t valid_frames;
    uint32_t crc_errors;
} RC_State_t;
extern volatile RC_State_t g_rc;
```

典型布局（**以 ELF 的 DWARF 为准，不硬编码**）：

| 成员 | 类型 | 偏移 | 大小 | 写入 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `axes[0..3]` | int16 | 0 | 2×4 | ✅ | 左X/左Y/右X/右Y |
| `sliders[0..3]` | uint16 | 8 | 2×4 | ✅ | |
| `enabled` | bool | 16 | 1 | ✅ | 需同时 `link_valid=1`、`stopped=0` |
| `stopped` | bool | 17 | 1 | ✅ | |
| `sequence` | uint8 | 18 | 1 | 🔒 | 驱动序号基线，手改会打乱过滤 |
| `link_valid` | bool | 19 | 1 | ✅ | |
| `valid_frames` | uint32 | 20 | 4 | 🔒 | 计数器 |
| `crc_errors` | uint32 | 24 | 4 | 🔒 | 计数器 |

🔒 的成员在页面里只显示、不给输入框，避免无意义的误操作。

### 写进去到底有没有用

这是本方案最需要提前说清的一件事：

1. **蓝牙正在发帧**：`publish()` 每 20 ms 覆盖整个结构体 → SWD 写入基本立刻失效。
2. **蓝牙断开、且已超过 300 ms**：`RC_Tick1ms()` 里 `expired` 已置 1，之后不再反复清零 → 写入的值**会保持**。此时把 `link_valid=1, enabled=1, stopped=0` 和四个轴写进去，业务代码的 `link_valid && enabled && !stopped` 就会成立。
3. 也就是说这顺带成了一条 **SWD 有线遥控 / 无手机注入** 的调试通道。**只在机器人架空、手边有急停时用**，页面顶部要常驻这条警告。
4. 恢复：写 `enabled=0, stopped=1`；等一帧真实蓝牙帧；或复位 MCU。

### H7 D-Cache

`g_rc` 是 `volatile`，但 volatile 不保证调试器与 CPU 之间的缓存一致：

- 落在 **DTCM（0x2000_0000 起）**：不经过 D-Cache，没有这个问题。
- 落在 **AXI SRAM（0x2400_0000 起）** 且固件调用了 `SCB_EnableDCache()`：CPU 写可能还在 cache 里，SWD 读到旧值；反过来 SWD 写的值也可能被 cache 遮住。

处理顺序：**先实测**（摇杆动一下，页面数值跟着变就说明没问题）。真的不一致，再给 10 行最小固件补丁（把 `g_rc` 所在 32 字节对齐区间配成 MPU non-cacheable）。不要为了这个功能全局关缓存。

---

## 4. 架构

```text
手机浏览器  ──HTTP(轮询 2 Hz / 单次写入)──►  电脑 tune.py
                                             │ pyelftools：ELF+DWARF → 成员表
                                             │ pyOCD：attach，按 4 字节对齐 RMW
                                             ▼
                                      USB DAPLink → SWD → STM32 RAM
```

- **不经过蓝牙**，所以调参时手机可以连蓝牙遥控，也可以不连。
- 电脑端只有一条硬件访问通道，所有请求在一把锁内串行。
- 页面与数据同源，不需要 CORS。

---

## 5. 文件清单

```text
tools/rc-tune/
  tune.py            # CLI + DWARF 解析 + pyOCD + HTTP，约 450 行
  page.html          # 手机页面，原生 JS，无构建，约 200 行
  requirements.txt   # pyocd / pyelftools 固定版本
  README.md          # 用法、缓存说明、故障排查
  tests/
    test_layout.py   # 本机 gcc 编译夹具，校验偏移/数组展开/typedef 解包
    test_server.py   # FakeProbe，校验只写单成员、只读拦截、令牌、越界
```

`tune.py` 内部按 `layout / probe / server / cli` 四段分节，不拆包、不建插件框架。整个工具放进 `tools/`，不触碰 `android/` 和 `stm32/`。

---

## 6. 接口

鉴权：请求头 `X-Token: <令牌>`；缺失或错误返回 401，且不触碰探针。

### `GET /api/schema`

```json
{
  "symbol": "g_rc", "address": 536871328, "size": 28,
  "elf_sha256": "3f9a…", "write_enabled": true,
  "members": [
    {"id": "axes[0]", "type": "int16", "size": 2, "offset": 0,
     "writable": true, "min": -1000, "max": 1000}
  ]
}
```

`min/max` 取类型范围；`axes` 另给 `-1000..1000` 的业务提示，但不强制。

### `GET /api/values`

一次读整段 28 字节再解码，返回 `{"ts": …, "values": {"axes[0]": "0", …}}`；读失败返回 `{"ok": false, "code": "probe_failed", "message": "…"}`。

### `POST /api/write`

```json
{"member": "axes[0]", "value": "-250"}
```

- 十进制字符串；`bool` 只收 `true/false`；整数拒绝小数、拒绝越界；float 拒绝 NaN/Inf。
- **4 字节对齐读改写**：读出包含该成员的 4 字节字，只替换目标字节，再写回。相邻字段和 padding 不变，也避开非对齐 AP 访问。
- 写后立刻回读同一成员：相等 `verified`，不等 `mismatch`（附 `observed`），读失败 `unknown`。
- `unknown` 在页面上**不能显示成成功**。
- 不做自动重试、不做回滚、不做写请求去重（没有自动重发，所以不需要）。

---

## 7. 页面

```text
┌──────────────────────────────────────────┐
│ g_rc @0x200001A0 · 28B · 可写 · 已连接    │
│ 上次读取 0.4s 前            [刷新频率 2Hz] │
├──────────────────────────────────────────┤
│ 成员        类型     当前值    新值   写入 │
│ axes[0]     int16        0   [    ]  [写] │
│ axes[1]     int16      123   [    ]  [写] │
│ …                                          │
├──────────────────────────────────────────┤
│ ⚠ 蓝牙正在发帧时写入会在 20ms 内被覆盖；   │
│   注入控制值前请架空机器人。               │
└──────────────────────────────────────────┘
```

要点：

- 单列 `LazyColumn` 等价物（普通 `table` + 滚动），不嵌套双层滚动。
- 轮询 500 ms；输入框聚焦或写入进行中时暂停刷新，**草稿不被实时值覆盖**。
- 超过 2 s 没读到 → 置灰并显示「数据过期」，禁用写入。
- 写失败的成员行单独标红，显示 `code/message`，不弹全局 toast。

---

## 8. 分任务

### Task 0：确认三个事实（30 分钟，必须做）

- [ ] ELF 里有 `g_rc` 的 DWARF：`readelf --debug-dump=info build/robot.elf | grep -A5 g_rc` 有输出。
- [ ] `g_rc` 的实际地址与所在 RAM 区（DTCM / AXI SRAM），决定是否要关心缓存。
- [ ] 固件是否开启 D-Cache：搜 `SCB_EnableDCache`、`MPU_Config`。
- [ ] `pyocd list-targets` 里有精确型号（`stm32h723vg`），不用 `cortex_m` 猜内存图。

**产出：** 写进 `tools/rc-tune/README.md` 的「本机实测环境」一节；没确认的部分标未验证。

### Task 1：DWARF 解析 + 夹具测试（半天）

- [ ] 先写夹具：`struct TuneFixture { volatile float kp; int32_t limit; const uint16_t tag; int16_t arr[3]; }; volatile TuneFixture g_fixture;`，用**本机 gcc** `-g -O0` 编译（不要求 ARM 工具链）。
- [ ] 测试：偏移/宽度与 `offsetof` 一致、数组展开为 3 项、`const` 标只读、typedef/volatile 链解包、符号不存在时报中文错误。
- [ ] 实现：遍历 CU → `DW_TAG_variable` 的 `DW_AT_location`（`DW_OP_addr`）取地址 → `DW_AT_type` 递归（typedef/const/volatile/array/struct）→ 成员表。
- [ ] 地址必须落在 `0x2000_0000..0x3000_0000`，否则拒绝并提示。

**验证：** `python -m pytest tools/rc-tune/tests/test_layout.py -q`。

### Task 2：探针 + 服务（半天）

- [ ] `FakeProbe`（字节数组 + 调用记录），先写失败测试：只改目标成员的字节、邻居不变、只读成员拒绝、越界拒绝、令牌错误不碰探针、只读模式拒绝写。
- [ ] 接 pyOCD：`ConnectHelper.session_with_chosen_probe(target_override=…, unique_id=…, options={"connect_mode": "attach"})`；不用 halt/reset/flash API，不在路由里做阻塞调用（`Lock` 包住即可）。
- [ ] 4 字节对齐读改写 + 回读判定。
- [ ] CLI、令牌、`--allow-write`、默认 loopback、打印局域网地址。

**验证：** `pytest tools/rc-tune/tests/test_server.py -q`；`list-probes` 在有/无探针时都能给出可读结果。

### Task 3：页面（半天）

- [ ] `page.html` 从 `?t=` 取令牌存 `sessionStorage`；401 时提示重新输入。
- [ ] 按 schema 渲染，只读成员无输入框；`enabled/stopped/link_valid` 上方固定警告。
- [ ] 输入草稿不被轮询覆盖；写入中禁用重复点击。
- [ ] 手机竖屏/横屏、系统字体放大各看一遍。

**验证：** 用 FakeProbe 起服务，手机或桌面浏览器完整走一遍读/写/过期/断线。

### Task 4：实机（1 小时）

- [ ] 只读 attach，页面显示的值与遥控操作一致（摇杆动 → `axes` 变）。
- [ ] 若数值不动：判断缓存问题，按第 3 节给最小补丁，**改固件前先确认**。
- [ ] 断开蓝牙/退出 App，等 1 秒，写 `axes[0]=100, link_valid=1, enabled=1, stopped=0`，观察下游业务是否采用。**机器人架空。**
- [ ] 写 `enabled=0` 恢复；再让一帧真实蓝牙帧到达，确认覆盖。
- [ ] 拔 DAPLink、关服务，页面 2 秒内变「数据过期」且写入禁用。
- [ ] 记录：探针型号、MCU、ELF 提交、`g_rc` 地址与 RAM 区、D-Cache 状态、哪些成员实测生效。

### Task 5：文档与 CI（可选，1 小时）

- [ ] `README.md` 写清「只读默认」「可信局域网」「不 halt/reset/flash」「H7 缓存边界」。
- [ ] `.github/workflows/build.yml` 加一个 `python -m pytest tools/rc-tune/tests` 的 job（无需硬件）。

---

## 9. 验收标准

- [ ] 一条命令起服务，手机浏览器打开就能看到 `g_rc` 的 14 个成员当前值。
- [ ] 改一个成员，只变那一个成员的字节，其他成员和 padding 不变，回读值正确显示。
- [ ] 令牌错误、只读模式、越界值、只读成员都碰不到硬件。
- [ ] 断线 2 秒内显示过期并禁止写入，不自动重连、不重放写入。
- [ ] 全程没有 halt / reset / erase / flash。
- [ ] 原有 Android、STM32 测试和 CI 全绿（本方案不改它们）。

## 10. 不做

AI / DSH / MCP、任意变量浏览、手填地址、RTT、曲线、CSV、Flash 持久化、烧录、公网暴露、手机直连 USB、多客户端、写请求重放、多成员原子更新。

---

## 11. 交给实现者的指令

> 在 `tools/rc-tune/` 下实现本文。先做 Task 0 确认 ELF 有 DWARF 且 `pyocd list-targets` 支持目标；再用本机 gcc 夹具完成解析测试、用 FakeProbe 完成服务测试，最后才接真实 DAPLink。不要改 `android/`、`stm32/` 和协议。没有用户明确同意，不对真实硬件做任何写入。