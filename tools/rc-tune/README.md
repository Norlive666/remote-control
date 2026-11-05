# rc-tune：把 App 的操作直接写进 STM32 的 `g_rc`

手机 App 里的「调试链路」把与蓝牙**完全相同**的 20 字节控制帧经局域网发给电脑，
电脑再用 DAPLink 经 SWD 把这帧的效果写进固件的全局变量 `g_rc`（`RC_State_t`）。

- 摇杆、死区、滑块、按钮通道、使能/急停全部沿用 App 现有逻辑，手感与蓝牙模式一致；
- 只替换传输层：`Bt630Session` 的 `Link` 实现从 GATT 换成 HTTP（`TuneLink.kt`）；
- 电脑端不 halt、不 reset、不擦写 Flash，只做 4 字节对齐的读改写。

```
手机 App ──HTTP 20Hz──▶ tune.py ──SWD──▶ DAPLink ──▶ STM32  g_rc
```

## 一键启动

Windows 双击 `start.bat`，Linux/macOS 执行 `./start.sh`，等价于：

```bash
python3 tools/rc-tune/start.py --allow-write
```

首次运行会自动在本目录建 `.venv` 并装好 pyocd/pyelftools（约 30 MB，只装一次），
然后在当前目录的 `./`、`build/`、`out/`、`Debug/`、`Release/` 里找唯一的 `.elf`，
确认是否允许写入，最后直接开服务。

| 参数 | 作用 |
|---|---|
| `--elf build/robot.elf` | 手动指定固件 |
| `--allow-write` / `--read-only` | 跳过 / 强制关闭写入询问 |
| `--port 8765` | 换端口 |
| `--probe <UID>` | 多个 DAPLink 时指定 |
| `--no-venv` | 不建虚拟环境，复用当前解释器里已装的依赖 |
| `--index-url <url>` | pip 镜像（PyPI 慢时用，例如 `https://pypi.tuna.tsinghua.edu.cn/simple`） |

不加 `--allow-write` 时会交互问一次；非交互环境（管道、CI）默认只读。

## 手动启动（依赖已装好的情况）

```bash
pip install pyocd pyelftools
python tools/rc-tune/tune.py list-probes          # 确认能看到 DAPLink
python tools/rc-tune/tune.py serve \
    --elf build/robot.elf --symbol g_rc --target stm32h723vg --allow-write
```

启动后会打印：

```
g_rc  0x200001A0  28 字节  DTCM（不经过 D-Cache）
成员  axes[0..3] sliders[0..3] enabled stopped sequence link_valid valid_frames crc_errors
令牌  3f9a1c
监听  http://192.168.1.23:8765   （手机与电脑需要在同一 WiFi）
```

常用参数：`--host`（默认 `0.0.0.0`）、`--port`（默认 `8765`）、`--probe <UID>`（多探针时指定）、
`--no-token`（关闭令牌校验）、`--watchdog-ms`（断流后多久写安全值，默认 300）。

## App 侧

1. 「面板设置」→「调试链路（局域网 → SWD）」；
2. 填电脑 IP（上面打印的 `192.168.x.x`）、端口 `8765`、令牌；
3. 点「连接调试链路」，关闭设置页，按主界面的使能按钮；
4. 之后摇杆/滑块/按钮的行为与蓝牙模式一致，`g_rc` 会被实时改写。

断线自动重连沿用 `Bt630Session` 的 1/1/2/4/5 秒退避；`status` 与设置页会显示当前状态。

## 安全边界

- **默认只读**：不加 `--allow-write` 时 `POST /api/frame` 返回 403，App 直接报
  「桥接器处于只读模式」并不进入重试。
- **断流兜底**：超过 `--watchdog-ms` 没收到帧，桥接器把 `axes/sliders` 清零、
  `enabled=0`、`stopped=1`、`link_valid=0` 写一次。App 退出时也会补一帧急停。
- **蓝牙优先**：驱动收到真实蓝牙帧时 `publish()` 会整帧覆盖 `g_rc`，所以调试链路
  只在蓝牙不发送时有效；两者同时发帧时以蓝牙为准。
- **300ms 空窗**：`RC_Tick1ms()` 在最后一次真实帧之后 300ms 会 `neutral()` 一次
  （`expired` 只置位一次）。若刚从蓝牙切到调试链路，可能有一次小于 20ms 的归零，
  随后被调试帧覆盖，属正常现象。
- **D-Cache**：`g_rc` 在 DTCM（`0x2000_xxxx`）时无影响；若在 AXI SRAM 且固件开了
  D-Cache，SWD 读回可能是旧值，App 会显示「写入未生效」。此时先确认链接脚本，再考虑
  临时把该区域设成 non-cacheable，**不要**为了调试工具关掉全局缓存。
- 写入 `g_rc` 等于直接驱动执行机构：**先架空机器人**，急停随时可用。

## 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/health` | 符号、地址、大小、区域、`write_enabled`、成员表 |
| GET | `/api/state` | 读回当前 `g_rc` 各成员 |
| POST | `/api/frame` | 请求体是 20 字节帧；返回 `verified`、`readback`、`frames`、`ms` |

令牌放在 `X-Token` 头。错误码：401 令牌错误、403 只读、422 帧不合法（帧头/CRC/急停互斥/
未使能却非零）、503 探针或内存访问失败。

## 测试

```bash
python -m pytest tools/rc-tune/tests -q
```

覆盖 DWARF 布局解析（现场用 gcc 编译夹具）、帧解码规则、读改写是否保留相邻计数器、
看门狗、HTTP 鉴权与只读拒绝。Android 侧有 `TuneLinkTest`（MockWebServer）。

## 已知限制

- 只写 `axes`、`sliders`、`enabled`、`stopped`、`sequence`、`link_valid`，
  不动 `valid_frames` / `crc_errors`；
- 不支持位域、多维数组和运行时位置（location list）的变量；
- 帧按到达顺序直接生效，不做序号过滤（App 侧本来就是递增序号）；
- 需要目标芯片的 pack 支持（`--target stm32h723vg` 由 pyOCD 内置）。