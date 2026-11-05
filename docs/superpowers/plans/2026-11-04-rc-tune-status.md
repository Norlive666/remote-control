# rc-tune 进度存档（2026-11-04 23:20，2026-11-05 更新）

目标：手机 App 沿用现有摇杆/滑块/使能逻辑，把 20 字节控制帧经局域网发给电脑桥接器，
由桥接器经 DAPLink/SWD 写进 STM32 固件的 `g_rc`（`RC_State_t`）。

## 已完成并验证

| 部分 | 状态 | 证据 |
|---|---|---|
| 桥接器 `tools/rc-tune/tune.py` | 完成 | `32 passed`（DWARF 布局、CRC/帧校验、读改写保留计数器、看门狗、HTTP 401/403/422、RSP 客户端、启动脚本） |
| App「调试链路」 | 完成 | `128 tests completed, 0 failed`；`:app:assembleDebug` 成功 |
| 一键启动 `start.py` + `start.bat` + `start.sh` | 完成，Linux 端已跑通 | `--help` 正常；建 venv、装依赖、重执行、定位 ELF、走到 SRAM 校验并拒绝 `0x00403010` |
| 文档 | 完成 | `tools/rc-tune/README.md`（含一键启动、参数表、安全边界）；旧浏览器方案文档顶部已标注被取代 |

版本：App `1.10.0` / code `16`；APK 在 `android/app/build/outputs/apk/debug/app-debug.apk`。

## 提交

`feat(android): drive g_rc over LAN through rc-tune bridge`（2026-11-05），
21 个文件、+1984 行。工作区仅剩 `2026-11-04-remote-control-global-struct-handoff.md`
未跟踪——那是用户提供的参考文档，未纳入提交。

## 已修（2026-11-05）

`start.py` 的依赖表改成 `(pip 包名, 导入模块名)` 对：`dep_probe()` 拼
`import pyocd, elftools`，`package_names()` 供 pip 用。此前把包名 `pyelftools`
当模块名，导致每次启动都白跑一遍 pip；现在启动 0.4 秒直达 ELF 解析。

## 恢复命令

```bash
# Python（工作区内的 venv，已装好依赖）
tools/.cache/venv/bin/python -m pytest tools/rc-tune/tests -q

# Android 构建（工具链都在 tools/.cache/，已被 gitignore）
export JAVA_HOME=$PWD/tools/.cache/android-build/jdk
export ANDROID_HOME=$PWD/tools/.cache/android-build/sdk
export ANDROID_USER_HOME=$PWD/tools/.cache/android-build/andhome
export GRADLE_USER_HOME=$PWD/tools/.cache/gradle
export JAVA_TOOL_OPTIONS="-Djava.net.preferIPv4Stack=true -Duser.home=$PWD/tools/.cache/home"
cd android && ../tools/.cache/gradle-dist/gradle-8.11.1/bin/gradle --no-daemon --console=plain \
  :app:testDebugUnitTest :app:assembleDebug
```

`-Duser.home=…` 是必须的：Robolectric 要写 `~/.robolectric-download-lock`，原 `$HOME` 只读。

## 2026-11-05：改用 OpenOCD 后端 + VS Code 一键启动

用户的调试流程本来就跑 OpenOCD（Cortex-Debug 等），pyOCD 会跟它抢 DAPLink 独占，
所以默认后端改成「连已有 OpenOCD」，依赖从 pyocd+pyelftools 降到只剩 pyelftools。

- `tune.py`：新增 `RspClient` / `OpenOcdProbe`，直接说 GDB Remote Serial Protocol，
  只用 `m addr,len` / `M addr,len:hex` 两个包、按字节寻址；`--backend {openocd,pyocd}`
  （默认 openocd）、`--ocd-host`（默认 127.0.0.1）、`--ocd-port`（默认 3333），
  `--target` 退为 pyOCD 专用。
- `start.py`：`ensure_openocd()` 先探端口，连不上就明说「先启动 OpenOCD，或用 --backend pyocd」；
  依赖按后端安装；ELF 查找改为递归（跳过 `.venv`/`node_modules`/`.cache` 等）。
- `.vscode/tasks.json`：四个任务（先只读 / 允许写入 / 指定 ELF / pyOCD），**不占用
  `Ctrl+Shift+B`**，从「Tasks: Run Task」启动。
- `tools/rc-tune/bind_key.py`：把两个「运行任务」快捷键合并进用户级 `keybindings.json`
  （默认 `ctrl+alt+r` / `ctrl+alt+shift+r`），写入前备份、保留 JSONC 注释、已存在则不动。
- 测试 `32 passed`；RSP 用本地假 GDB server 验证封包、>1024 分片、坏校验、连接被拒文案。

端口共用：3333 被调试会话占着时，让 OpenOCD `gdb_port 3334`、桥接器 `--ocd-port 3334`。
探针无关：ST-Link / DAPLink 都只是 OpenOCD 的 `interface`，桥接器只认地址，不用改代码。

## 未验证 / 已知限制

- **Windows 实机**：`start.bat`（`py -3` 回退、`chcp 65001`、`if errorlevel 1 pause`）与防火墙提示都没跑过。
- **真实硬件**：没有 DAPLink/开发板，`PyOcdProbe` / `OpenOcdProbe` 路径与 `g_rc` 实际写入未实测；D-Cache 场景靠 App 回读提示兜底。
- **真实 OpenOCD**：只用本地假 GDB server 验证过协议；连真机时若报「应答校验失败」，多半是 `--ocd-port` 指到了 telnet 端口。
- **VS Code 任务**：`.vscode/tasks.json` 只做了 JSON 校验与命令行模拟，没在真实 VS Code 里点过。
- **PyPI 直连很慢**：本机下 14.8 MB 的 pyocd 轮子 7 分钟没下完；阿里云镜像秒级。已提供 `--index-url` 并在报错里提示。
- 安全边界不变：默认只读（需 `--allow-write` 或交互确认）、断流 300ms 写安全值、蓝牙帧优先于调试帧。