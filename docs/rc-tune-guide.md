# rc-tune 调试链路说明

手机 App 用**和蓝牙完全相同的 20 字节控制帧**，经局域网 HTTP 交给电脑上的 Python 桥接器；
桥接器通过 OpenOCD 的 GDB 调试通道（GDB RSP）直接读写 STM32 RAM 里的 `g_rc` 结构体。

不经过串口，不需要在固件里加通信代码，App 侧的摇杆/滑块/使能/急停逻辑完全复用。

---

## 1. 全景

```
 手机 App（Android）              电脑                                 STM32H7 固件
┌───────────────────┐  HTTP   ┌────────────────┐  GDB RSP  ┌──────────┐  SWD   ┌──────────┐
│ RemoteViewModel   │ 20 Hz   │ tune.py        │  m/M 包   │ OpenOCD  │        │ g_rc     │
│ Bt630Session.Link │────────▶│  ├ DWARF 解析   │──────────▶│ gdb_port │───────▶│ (RAM)    │
│  └ TuneLink       │◀────────│  ├ HTTP 服务    │◀──────────│ 3333 …   │◀───────│ 28 字节   │
│ ControlProtocol   │  JSON   │  └ 看门狗       │           └──────────┘        └──────────┘
└───────────────────┘         └───────▲────────┘
                                      │ 启动/参数
                              start.py（一键入口）
```

三种连接段：

| 连接段 | 协议 | 默认 | 说明 |
| --- | --- | --- | --- |
| App → 桥接器 | HTTP/1.1 | 8765 | 局域网直连，JSON + 二进制帧 |
| 桥接器 → OpenOCD | GDB RSP | `gdb_port` | 只发 `m`（读）/ `M`（写）两个包 |
| OpenOCD → 芯片 | SWD | 探针 | ST-Link / DAPLink 都行，由 interface cfg 决定 |

---

## 2. 固件侧

### 2.1 只需要一个结构体

固件里不需要任何通信驱动、串口中断或定时器，只需要一个全局变量：

```c
/* Core/Src/main.c */
#include "rc_state.h"

/* used    : 即使暂时没有代码读它，也强制编译器保留；
 * aligned : 32 字节对齐，读之前才能整行刷 D-Cache。 */
__attribute__((used, aligned(32))) volatile RC_State_t g_rc = {0};
```

`User/APP/rc_state.h` 提供类型、`extern` 声明和一个读取封装：

```c
RC_State_t rc;
RC_GetState(&rc);              /* 内部先 SCB_InvalidateDCache_by_Addr，再拷贝 */
if (rc.enabled && !rc.stopped) {
    /* rc.axes[0..3] / rc.sliders[0..3] */
}
```

### 2.2 为什么 `used` 还不够

链接器开了 `--gc-sections`（CubeMX 默认），**没人引用的变量会被整段删掉**，
哪怕它标了 `__attribute__((used))`。所以入口里必须真的读一次：

```c
(void)g_rc.sequence;   /* volatile 读是副作用，编译器删不掉 → 链接器必须保留 g_rc */
```

验证：

```bash
nm build/Debug/Gimbal.elf | grep -w g_rc
# 200040c0 B g_rc        ← 有这一行，桥接器才找得到
```

### 2.3 为什么必须刷 D-Cache

桥接器是**调试器经 SWD 直接改 RAM**，这条路径不经过 CPU 缓存。
STM32H7 的 D-Cache 如果开着，CPU 可能一直读到旧值。
`RC_GetState()` 在读之前把 `g_rc` 所在缓存行整行失效（32 字节对齐就是为了这一步安全）。

### 2.4 内存布局（与桥接器、App 三方一致）

| 偏移 | 成员 | 类型 | 含义 |
| --- | --- | --- | --- |
| 0 | axes[0] | int16 | 通道 1（左右） |
| 2 | axes[1] | int16 | 通道 2（前后） |
| 4 | axes[2] | int16 | 通道 3 |
| 6 | axes[3] | int16 | 通道 4 |
| 8 | sliders[0] | uint16 | 滑块 1 |
| 10 | sliders[1] | uint16 | 滑块 2 |
| 12 | sliders[2] | uint16 | 滑块 3 |
| 14 | sliders[3] | uint16 | 滑块 4 |
| 16 | enabled | uint8 | 使能（1 = 有控制权） |
| 17 | stopped | uint8 | 急停（1 = 停止） |
| 18 | sequence | uint8 | 帧序号，可用来观察丢帧 |
| 19 | link_valid | uint8 | 链路有效标记 |
| 20 | valid_frames | uint32 | 固件侧统计（桥接器不改它） |
| 24 | crc_errors | uint32 | 固件侧统计（桥接器不改它） |

共 **28 字节**。桥接器只写前 20 字节里的控制字段，`valid_frames`/`crc_errors` 原样保留。

---

## 3. 桥接器（`tools/rc-tune/`）

### 3.1 文件

| 文件 | 作用 |
| --- | --- |
| `start.py` / `start.sh` / `start.bat` | 一键入口：虚拟环境、找 ELF、探测/启动 OpenOCD、转发参数 |
| `tune.py` | 核心：DWARF 解析 + 调试通道 + HTTP 服务 + 看门狗 |
| `tests/` | 单元测试（假 GDB server、假 DWARF、假时钟） |
| `README.md` | 快速上手 |

### 3.2 `tune.py` 做三件事

1. **解析 ELF**：用 `pyelftools` 读 DWARF，找到全局变量（默认 `g_rc`）的地址和成员布局。
   兼容 DWARF 5 的 `DW_AT_specification`（定义 DIE 不带名字、只有声明 DIE 带名字的编译方式）。
2. **读写目标**：`m <addr>,<len>` 读、`M <addr>,<len>:<hex>` 写，按 4 字节对齐读改写，
   只动帧里带的字段，保留统计计数器。
3. **提供 HTTP 服务**：接 App 的帧，回读校验，断流写安全值。

### 3.3 HTTP 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/health` | 返回 `symbol`、`address`、`size`、`region`、`write_enabled`、`frames`、`members` |
| GET | `/api/state` | 回读当前 `g_rc` 全部成员 |
| POST | `/api/frame` | Body 为 20 字节二进制帧，`Content-Type: application/octet-stream` |

响应状态码：

| 码 | 含义 |
| --- | --- |
| 200 | `{"ok":true,"verified":true/false,"frames":N,"readback":{...},"ms":x}` |
| 400 | 帧长不是 20 字节 |
| 401 | 令牌错误（仅 `--token` 开启时） |
| 403 | 只读模式（没加 `--allow-write`） |
| 422 | 帧内容不合法：帧头、版本、CRC、急停与使能同时置位、未使能却带非零轴/滑块 |
| 503 | 调试通道读写失败（探针掉线、端口被抢等） |

`verified=false` 表示写进去了但回读不一致，通常是 D-Cache 问题。

### 3.4 看门狗

默认断流 300 ms（`--watchdog-ms`）写一次安全值：

```
axes = 0, sliders = 0, enabled = 0, stopped = 1, link_valid = 0
```

日志形如 `控制流中断，已写入安全值（输出 0 / stopped=1）：共 120 帧，断流 0.31s`。
只要 App 每 20 ms 正常发帧就不会触发；持续触发说明帧率太低或网络/调试通道太慢。

### 3.5 `start.py` 的自动处理

- **虚拟环境**：首次运行自动建 `tools/rc-tune/.venv` 并装 `pyelftools`；
  解释器失效（比如系统 Python 升级导致 `libpython3.14.so` 找不到）会自动删掉重建。
- **找 ELF**：不带 `--elf` 时在当前目录递归找唯一的 `.elf`；找到多个会报错，用 `--elf` 指定。
- **找 OpenOCD**：`pgrep` 读已有 OpenOCD 的 `gdb_port`（支持 `gdb_port 3333` 和 `gdb port 3333` 两种写法）。
- **起 OpenOCD**：加 `--start-ocd` 且没有实例时自己拉起，退出时自动关闭；
  探针接口从 `.vscode/launch.json`（其次 `tasks.json`）识别，`stlink.cfg` 起不来会回退 `cmsis-dap.cfg`。

### 3.6 参数

| 参数 | 默认 | 说明 |
| --- | --- | --- |
| `--elf <路径>` | 自动发现 | 固件 ELF |
| `--symbol <名字>` | `g_rc` | 要调的结构体变量 |
| `--backend openocd\|pyocd` | openocd | 调试后端 |
| `--ocd-host` / `--ocd-port` | 127.0.0.1 / 自动 | OpenOCD 的 gdb 地址与端口（0 = 从进程命令行探测） |
| `--start-ocd` | 关 | 没有实例时自己启动 OpenOCD |
| `--ocd-config <cfg>` | — | 额外的 `-f` 配置文件，可重复 |
| `--interface <名字>` | 自动 | 探针接口，如 `stlink` / `cmsis-dap` |
| `--host` / `--port` | 0.0.0.0 / 8765 | HTTP 监听地址与端口 |
| `--allow-write` | 关 | 允许写 `g_rc`（默认只读） |
| `--read-only` | — | 强制只读，不再询问 |
| `--watchdog-ms` | 300 | 断流多久写安全值 |
| `--token <字符串>` | 不校验 | 开启后 App 需带同一个 `X-Token` |
| `--index-url <镜像>` | 官方 PyPI | pip 镜像（国内网络慢时用） |

---

## 4. 手机 App

### 4.1 传输层可插拔

App 的蓝牙会话 `Bt630Session` 把「发送 20 字节帧」抽象成 `Link` 接口；
`TuneLink` 实现该接口，把同一份帧 POST 给桥接器：

```kotlin
interface Link {
    suspend fun open()
    suspend fun send(frame: ByteArray)
    fun close()
}
```

所以摇杆、死区、滑块、按钮、使能、急停全部沿用现有逻辑，**没有第二套控制代码**。

### 4.2 连接流程

1. `open()` → `GET /api/health`，检查 `write_enabled`；只读模式直接报「桥接器处于只读模式」。
2. 读取 `symbol`/`address`，在状态栏显示 `g_rc @0x200040C0 · 链路正常`。
3. 就绪后每 20 ms 发一帧（`RemoteViewModel` 的既有节拍）。
4. 每 500 ms 把回执转成状态文字：`已写入 N 帧 · 回读一致` 或 `写入未生效：…`。
5. 断开时 `close()` 补发一帧急停；失败后按 1/1/2/4/5 秒退避重连。

### 4.3 设置页

「面板设置 → 调试链路（局域网 → SWD）」：主机 IP、端口（8765）、令牌（默认留空）、备注。

---

## 5. 20 字节帧格式

与蓝牙协议完全一致：

| 偏移 | 长度 | 内容 |
| --- | --- | --- |
| 0–1 | 2 | 帧头 `A5 5A` |
| 2 | 1 | 版本（1 = 无滑块，2 = 带滑块） |
| 3 | 1 | 序号（0–255 循环） |
| 4–11 | 8 | `axes[4]`，int16 小端 |
| 12–16 | 5 | 4 个 10 位滑块，小端打包 |
| 17 | 1 | flags：bit0 = 使能，bit1 = 急停 |
| 18–19 | 2 | CRC16/CCITT-FALSE（多项式 0x1021，初值 0xFFFF），覆盖字节 2–17 |

规则：急停与使能不能同时置位；未使能时轴和滑块必须为 0。

---

## 6. 连接全流程（逐步）

1. **接线**：ST-Link/DAPLink 接板子 SWD，USB 插电脑。
2. **OpenOCD**：由 `start.py --start-ocd` 自动拉起，或使用你已有的 OpenOCD。
3. **桥接器**：`start.sh --allow-write`（或 VS Code 任务「rc-tune: 启动（允许写入）」）。
   终端会打印结构体地址、监听地址、令牌状态。
4. **手机**：同一 WiFi，设置页填电脑 IP + 8765，返回面板按使能。
5. **验证**：App 显示「已写入 N 帧 · 回读一致」；桥接器终端每帧回执。
6. **退出**：直接停任务即可；App 会补发急停，桥接器断流也会写安全值。

### 6.1 OpenOCD 只能带一个 GDB 客户端（重要）

OpenOCD 0.12 及更早版本，同一个 `gdb_port` **同时只接受一个客户端**。
如果 VS Code 的调试会话（Cortex-Debug / mcu-debug）已经连着，桥接器再连会立刻被踢：

```
Error: attempted 'gdb' connection rejected
```

桥接器的提示是「连上了 127.0.0.1:2000，但它立刻关闭了连接」。

解决：**先停调试会话**（Shift+F5，OpenOCD 本身不用关），或者让 OpenOCD 换一个端口：

```bash
openocd ... -c "gdb_port 3334"
tools/rc-tune/start.sh --ocd-port 3334 --allow-write
```

---

## 7. 安全边界

- **默认只读**：不加 `--allow-write` 时任何写请求返回 403，App 连上就会提示。
- **急停优先**：`stopped=1` 时固件应立刻断开输出（App 侧本来就是这么实现的）。
- **断流兜底**：300 ms 没帧就写 `stopped=1` + 输出 0。
- **退出兜底**：App 断开时补发一帧急停。
- **地址校验**：只允许写 STM32 的 SRAM（0x20000000–0x40000000），拒绝其它地址。
- **令牌可选**：默认不校验（局域网信任）；`--token xxx` 打开后，App 必须填同一个值。
- 写入前请**架空机器人**，第一次先跑只读确认链路。

---

## 8. 常见问题

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| `ELF 里找不到全局变量 g_rc` | 变量被 `--gc-sections` 回收，或没带 `-g` | 在入口真引用一次 `g_rc`，`nm <elf> \| grep -w g_rc` 确认；或 `--symbol` 指定名字 |
| `ELF 不存在：…` | 工程里有多个/零个 `.elf` | 用 `--elf build/Debug/xxx.elf` |
| `连上了 … 但它立刻关闭了连接` | `gdb_port` 已被调试会话占用 | 停调试会话，或换 `gdb_port` + `--ocd-port` |
| `Error: open failed` | 探针接口选错（ST-Link 用 cmsis-dap.cfg）或被占用 | `--interface cmsis-dap` 或反过来；检查 `lsusb` |
| `监听 …:8765 失败` | 上一个桥接器没关 | 关掉旧窗口/进程再启动 |
| `libpython3.14.so.1.0: cannot open` | 虚拟环境随系统 Python 升级失效 | 删掉 `tools/rc-tune/.venv` 重跑，脚本也会自动重建 |
| 401 `令牌错误` | 桥接器开了 `--token` | App 里填同样的令牌，或去掉 `--token` |
| 403 `只读模式` | 桥接器没开写 | 加 `--allow-write` |
| 422 `CRC 校验失败` | 帧内容坏了 | 检查 App 版本与协议版本 |
| `写入未生效` / `verified=false` | D-Cache | 固件用 `RC_GetState()` 读 |
| `控制流中断` 频繁出现 | 帧率不足 / 调试通道慢 | 看回执里的 `ms`；确认 20 Hz 正常 |
| 手机连不上 | 不在同一网段 / 防火墙 | 放行 8765，或用 `--host 0.0.0.0` 并检查 IP |

---

## 9. 文件索引

```
remote-control/
├── android/                     手机 App（含 TuneLink、DebugLinkSettings）
├── stm32/h723/                  驱动参考实现（本方案不需要它）
├── tools/rc-tune/
│   ├── start.py                 一键入口
│   ├── tune.py                  桥接器核心
│   ├── tests/                   单元测试
│   └── README.md                快速上手
├── docs/rc-tune-guide.md        本文
└── dist/rc-tune-kit/            可直接拷进固件工程的独立包
```

固件工程侧（如 Vela_Gimbal）只需要：

```
<工程>/
├── User/APP/rc_state.h          结构体定义 + RC_GetState()
├── Core/Src/main.c              g_rc 定义与保留
└── .vscode/tasks.json           一键启动任务（来自 kit）
```