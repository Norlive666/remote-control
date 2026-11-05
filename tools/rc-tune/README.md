# rc-tune：把 App 的操作直接写进 STM32 的 `g_rc`

手机 App 里的「调试链路」把与蓝牙**完全相同**的 20 字节控制帧经局域网发给电脑，
电脑再经调试探针（ST-Link、DAPLink 都行）用 SWD 把这帧的效果写进固件的全局变量
`g_rc`（`RC_State_t`）。探针不参与选型：桥接器只说 GDB Remote，地址来自 ELF 的 DWARF，
换探针不用改任何东西。

- 摇杆、死区、滑块、按钮通道、使能/急停全部沿用 App 现有逻辑，手感与蓝牙模式一致；
- 只替换传输层：`Bt630Session` 的 `Link` 实现从 GATT 换成 HTTP（`TuneLink.kt`）；
- 电脑端不 halt、不 reset、不擦写 Flash，只做 4 字节对齐的读改写。

```
手机 App ──HTTP 20Hz──▶ tune.py ──GDB RSP──▶ OpenOCD ──SWD──▶ STM32  g_rc
```

电脑端默认连你已经在跑的 OpenOCD（`gdb_port`，默认 3333），不启动它、也不抢探针；
没有 OpenOCD 时可以用 `--backend pyocd` 让桥接器自己打开探针（pyOCD 同样支持 ST-Link）。

## 一键启动

Windows 双击 `start.bat`，Linux/macOS 执行 `./start.sh`，等价于：

```bash
python3 tools/rc-tune/start.py --allow-write
```

**先按你平时的任务把 OpenOCD 起起来**（桥接器只连它，不改它）。首次运行会自动在
本目录建 `.venv` 并按后端装依赖：OpenOCD 后端只要 `pyelftools`（约 0.2 MB），
`--backend pyocd` 才需要 15 MB 的 pyocd。然后在当前目录下递归找唯一的 `.elf`（跳过 `.git`、`.venv`、
`node_modules` 这类目录），确认是否允许写入，最后直接开服务。

| 参数 | 作用 |
|---|---|
| `--elf build/robot.elf` | 手动指定固件 |
| `--backend openocd` / `pyocd` | 连已有 OpenOCD（默认）或自己开探针 |
| `--ocd-host` / `--ocd-port` | OpenOCD 的 gdb_host/gdb_port；`--ocd-port 0`（默认）会从运行中的 `openocd` 命令行自动探测，探测不到再用 3333 |
| `--allow-write` / `--read-only` | 跳过 / 强制关闭写入询问 |
| `--port 8765` | 换端口 |
| `--probe <UID>` | 多个探针时指定（仅 pyocd） |
| `--no-venv` | 不建虚拟环境，复用当前解释器里已装的依赖 |
| `--index-url <url>` | pip 镜像（PyPI 慢时用，例如 `https://pypi.tuna.tsinghua.edu.cn/simple`） |

不加 `--allow-write` 时会交互问一次；非交互环境（管道、CI）默认只读。

### 在 VS Code 里启动

`.vscode/tasks.json` 里放了四个任务，从「终端 → 运行任务…」或命令面板的
「Tasks: Run Task」里选：

| 任务 | 用途 |
|---|---|
| `rc-tune: 启动（先只读）` | 确认链路通、看一眼 `g_rc` 当前值 |
| `rc-tune: 启动（允许写入）` | 正式驱动 |
| `rc-tune: 启动（指定 ELF）` | 自动找 ELF 不准时，弹框输入路径 |
| `rc-tune: 用 pyOCD 启动` | 没有 OpenOCD，让桥接器自己占探针 |

这些任务**没有**占用 `Ctrl+Shift+B`（那是默认生成任务的快捷键，留给你自己的构建）。

VS Code 不允许工作区自带快捷键，要真正一键启动，把下面两段贴进**用户级**
`keybindings.json`（命令面板 → `Preferences: Open Keyboard Shortcuts (JSON)`；
Windows 在 `%APPDATA%\Code\User\keybindings.json`，Linux 在
`~/.config/Code/User/keybindings.json`）：

```json
[
  { "key": "ctrl+alt+r", "command": "workbench.action.tasks.runTask", "args": "rc-tune: 启动（允许写入）" },
  { "key": "ctrl+alt+shift+r", "command": "workbench.action.tasks.runTask", "args": "rc-tune: 启动（先只读）" }
]
```

已经有内容的话，把这两个对象加进现有数组，不要整段替换。冲突了就换
`ctrl+alt+r` 这一串，`args` 必须与任务名一模一样。

懒得手改就跑一次脚本，它会自动找 keybindings.json（Windows 的 `%APPDATA%\Code\User\`、
Linux 的 `~/.config/Code/User/`、macOS 的 `Application Support`，也认 Insiders/VSCodium/Cursor）：

```bash
python tools/rc-tune/bind_key.py                       # 写入并备份成 keybindings.json.bak
python tools/rc-tune/bind_key.py --dry-run             # 只看看会写成什么样
python tools/rc-tune/bind_key.py --key f9 --key f10    # 换两个键（顺序：允许写入、先只读）
```

脚本只往数组末尾加条目，原有注释和绑定都保留；键已被别的命令占用时不覆盖，只打印提示
（用 `--key` 换一个）；已经有同样的绑定就整段不动；写回前还会再校验一次 JSONC。

任务在同名终端里常驻，停止按 `Ctrl+C` 或点终端右上角的垃圾桶。

## 和已有的 OpenOCD 共存

桥接器不启动 OpenOCD，只连它的 **GDB server**（`gdb_port`），所以两边可以同时开着：

```bash
openocd -f board/stm32h7x.cfg -c "gdb_port 3333"    # 你平时那条任务
python3 tools/rc-tune/start.py --allow-write        # 另开一个终端
```

如果 3333 已被 Cortex-Debug 之类的调试会话占着，给 OpenOCD 换个端口，桥接器指过去即可：

```bash
openocd -f board/stm32h7x.cfg -c "gdb_port 3334"
python3 tools/rc-tune/start.py --ocd-port 3334 --allow-write
```

只用 GDB Remote 的 `m`/`M` 两个包（按字节读写内存），目标保持运行，不 halt、不 reset。
想退回自己占探针的方式：`--backend pyocd`。

**探针类型无关**：ST-Link（`interface/stlink.cfg`）和 DAPLink（`interface/cmsis-dap.cfg`）
都只是 OpenOCD 的 `interface`，桥接器看不到它们。老 ST-Link 若仍是 `hla_swd` 传输，
个别版本在目标运行时读写 RAM 会报错或很慢，换成 `transport select dapdirect_swd`
（OpenOCD 0.12+，ST-Link V2-1/V3）即可；实在不行走 `--backend pyocd`。

## 手动启动（依赖已装好的情况）

```bash
pip install pyelftools                            # OpenOCD 后端只需要这个
python tools/rc-tune/tune.py serve \
    --elf build/robot.elf --backend openocd --allow-write
```

要把探针交给桥接器独占（不依赖 OpenOCD）：`pip install pyocd`，加
`--backend pyocd --target stm32h723vg`；`python tools/rc-tune/tune.py list-probes` 可以看探针 UID。

启动后会打印：

```
结构体   g_rc @0x200001A0，28 字节，DTCM（不经过 D-Cache）
成员     axes[0], axes[1], … （共 14 个可写成员）
当前值   axes[0]=0, axes[1]=0, …
链路     OpenOCD 127.0.0.1:3333（连已有实例，不抢探针）
写入     只读（加 --allow-write 才能写）
令牌     3f9a1c
地址     http://192.168.1.23:8765   ← App「面板设置 → 调试链路」
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
看门狗、HTTP 鉴权与只读拒绝，以及 RSP 客户端（用本地假 GDB server 验证封包/拆包/校验失败）。
Android 侧有 `TuneLinkTest`（MockWebServer）。

## 已知限制

- 只写 `axes`、`sliders`、`enabled`、`stopped`、`sequence`、`link_valid`，
  不动 `valid_frames` / `crc_errors`；
- 不支持位域、多维数组和运行时位置（location list）的变量；
- 帧按到达顺序直接生效，不做序号过滤（App 侧本来就是递增序号）；
- pyOCD 后端需要内置 pack（`--target stm32h723vg`）；OpenOCD 后端由你的 cfg 决定目标，
  桥接器不关心芯片型号；
- OpenOCD 的 GDB server 同时接入的连接数有限，被调试会话占满时给 OpenOCD 换 `gdb_port`。