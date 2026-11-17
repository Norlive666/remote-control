# rc-tune 调试链路说明

手机 App 用**和蓝牙完全相同的 20 字节控制帧**，经局域网 HTTP 交给电脑上的 Python 桥接器；
VS Code 的 rc-tune 扩展启动桥接器，通过当前 OpenOCD 会话的 Tcl 通道直接读写 STM32 RAM 里的 `g_rc`。
Live Watch 继续使用 GDB，两者共用同一个 OpenOCD 和探针。

不经过串口，不需要在固件里加通信代码，App 侧的摇杆/滑块/使能/急停逻辑完全复用。

---

## 1. 全景

```
 手机 App（Android）              电脑                                 STM32H7 固件
┌───────────────────┐  HTTP   ┌────────────────┐    Tcl    ┌──────────┐  SWD   ┌──────────┐
│ RemoteViewModel   │ 20 Hz   │ tune.py        │           │ OpenOCD  │        │ g_rc     │
│ Bt630Session.Link │────────▶│  ├ DWARF 解析   │──────────▶│ tcl_port │───────▶│ (RAM)    │
│  └ TuneLink       │◀────────│  ├ HTTP 服务    │◀──────────│          │◀───────│ 28 字节   │
│ ControlProtocol   │  JSON   │  └ 看门狗       │           └──────────┘        └──────────┘
└───────────────────┘         └───────▲────────┘
                                      │ 启动/参数
                              rc-tune 扩展（当前 F5 会话）
```

三种连接段：

| 连接段 | 协议 | 默认 | 说明 |
| --- | --- | --- | --- |
| App → 桥接器 | HTTP/1.1 | 8765 | 局域网直连，JSON + 二进制帧 |
| 桥接器 → OpenOCD | Tcl RPC | 由 F5 会话提供 | 使用 `read_memory` 和 `write_memory` |
| OpenOCD → 芯片 | SWD | 探针 | ST-Link / DAPLink 都行，由 interface cfg 决定 |

---

## 2. 固件侧

### 2.1 接入 STM32H723 固件

把 [remote_control.c](../stm32/h723/remote_control.c) 和 [remote_control.h](../stm32/h723/remote_control.h)
加入 H723 HAL 工程。驱动提供一份 `g_rc` 定义和 `RC_GetState()`。原来使用独立 `rc_state.h`
的工程，切换到驱动头文件时同时移除原 `g_rc` 定义和旧的状态读取函数。

如果只通过 SWD 写入，启动时在控制任务运行前调用一次 `RC_Reset()`，让初始状态为停止并保留 `g_rc`。
这条路径只使用驱动的状态接口。若工程已通过 `RC_Init(baud)` 启用 BT630，可直接复用该驱动的
`g_rc`。蓝牙和 SWD 写同一个状态，测试 SWD 时请断开手机的 BLE 连接。

在业务代码中读取状态。

```c
#include "remote_control.h"

RC_State_t rc;
RC_GetState(&rc);
if (rc.link_valid && rc.enabled && !rc.stopped) {
    /* rc.axes[0..3] / rc.sliders[0..3] */
}
```

### 2.2 确认 ELF 保留了 `g_rc`

编译保留 DWARF 调试信息的 ELF，并让 rc-tune 使用与实际烧录固件对应的版本。
`RC_Reset()` 或 `RC_Init(baud)` 会引用 `g_rc`。自行定义状态的工程也需要实际引用它，
否则链接器的 `--gc-sections` 可能将其回收。

```bash
arm-none-eabi-nm -S --defined-only build/Debug/Gimbal.elf
```

在输出中查找 `g_rc`，以本次构建显示的地址为准。

### 2.3 H723 的 RAM 和 D-Cache

桥接器是**调试器经 SWD 直接改 RAM**，这条路径不经过 CPU 缓存。
H723 的 `g_rc` 可放在探针可访问的 DTCM，例如 Vela_Gimbal 把 `.bss` 放在 DTCM。
如果把它放在启用 D-Cache 的可缓存 SRAM，需为该状态使用非缓存区或处理缓存一致性。
探针回读一致仅表示 RAM 读回了预期值，业务仍需验证 CPU 实际读取的状态。

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

## 3. VS Code 扩展与桥接器

### 3.1 桥接流程

1. 扩展从当前 MCU AI Debug 会话取得 ELF 路径和本地 OpenOCD Tcl 端口。
2. 桥接器用 `pyelftools` 读取 ELF 的 DWARF，找到 `g_rc` 的地址和成员布局。
3. 桥接器在端口 8765 接收 App 的控制帧，经 Tcl 写入控制字段并回读校验。

### 3.2 HTTP 接口

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
| 422 | 帧内容不合法：帧头、版本、CRC、急停与使能同时置位、未使能却带非零轴/滑块 |
| 503 | 调试通道读写失败（探针掉线、端口被抢等） |

`verified=false` 表示写进去了但回读不一致，通常是 D-Cache 问题。

### 3.3 看门狗

默认断流 300 ms（`--watchdog-ms`）写一次安全值：

```
axes = 0, sliders = 0, enabled = 0, stopped = 1, link_valid = 0
```

日志形如 `控制流中断，已写入安全值（输出 0 / stopped=1）：共 120 帧，断流 0.31s`。
只要 App 每 20 ms 正常发帧就不会触发；持续触发说明帧率太低或网络/调试通道太慢。

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

1. `open()` → `GET /api/health`，确认桥接器可写。
2. 读取 `symbol`/`address`，在状态栏显示 `g_rc @0x200040C0 · 链路正常`。
3. 就绪后每 20 ms 发一帧（`RemoteViewModel` 的既有节拍）。
4. 每 500 ms 把回执转成状态文字：`已写入 N 帧 · 回读一致` 或 `写入未生效：…`。
5. 断开时 `close()` 补发一帧急停；失败后按 1/1/2/4/5 秒退避重连。

### 4.3 设置页

在「面板设置 → 调试链路」填写扩展显示的电脑 IP 和端口 8765，令牌留空。

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

## 6. 连接全流程

1. 在桌面 VS Code 安装 [MCU AI Debug](https://github.com/Runzelee/mcu-ai-debug) 和对应平台的 [rc-tune VSIX](https://github.com/Norlive666/remote-control/releases/tag/rc-tune-v0.1.1)。
2. 将 ST-Link 或 DAPLink 接到板子的 SWD，打开固件工程，按 F5 启动本地单核 OpenOCD 会话，让 STM32 继续运行。
3. 点击状态栏的「rc-tune」，或按 `Ctrl+Shift+P` 运行「rc-tune: 启动手机调试链路」。
4. 手机和电脑接入同一局域网。在 App 的「调试链路」输入弹窗中的 IP 和端口，保持使能关闭，点击连接。观察 Live Watch 的 `g_rc.sequence` 是否更新。
5. 结束时先在 App 关闭使能，再点状态栏的「rc-tune」停止桥接，最后停止 F5。下次启动 F5 后可再次点击启用。

## 7. 安全边界

- **断流及停止**：断流 300 ms 或从扩展停止时，桥接器尝试清空控制量并写入 `stopped=1`。探针已经断开时，桥接器无法写入。
- **地址校验**：桥接器只允许写 STM32 SRAM 中的控制字段。
- **局域网访问**：扩展启动后，同网段可直连端口 8765。实机验证前先架空机器人。

## 8. 常见问题

| 现象 | 处理 |
| --- | --- |
| ELF 里找不到 `g_rc` | 确认 F5 使用的是当前固件的带调试信息 ELF，再按第 2 节核对全局变量。 |
| 连不上 OpenOCD Tcl | 确认已启动 MCU AI Debug 的本地 OpenOCD 调试会话，并选中了对应会话。在 VS Code 的 `rc-tune` 输出中查看原因。 |
| 端口 8765 无法监听 | 退出占用端口的旧桥接器，再启动扩展。 |
| 手机连不上 | 确认两端在同一局域网，选择手机可达的电脑 IP，并检查防火墙是否放行 TCP 8765。 |
| 探针回读一致，固件仍读到旧值 | 检查链接地址和 MPU。H723 可使用探针能访问的 DTCM。 |

扩展源码与打包说明见 [vscode/rc-tune](../vscode/rc-tune/README.md)。
