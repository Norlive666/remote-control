# rc-tune for MCU AI Debug

用 [MCU AI Debug](https://github.com/Runzelee/mcu-ai-debug) 的现有 F5 会话启动手机的 SWD 调试链路。扩展通过调试适配器的 `get-arguments` 读取实际 ELF 和 Tcl 端口，使用同一 OpenOCD 会话。

## 使用

1. 在 VS Code 安装 MCU AI Debug 0.1.5。本扩展分别提供 Windows x64 和 Linux x64 的 VSIX，包内包含 Python 3.12 和 ELF 解析依赖。
2. 在扩展侧栏通过「从 VSIX 安装」安装对应平台的文件。
3. 在固件工程按 F5 启动 OpenOCD 调试，选中要连接的会话，让 STM32 继续运行。
4. 点击状态栏的「rc-tune」，或者从命令面板运行「rc-tune: 启动手机调试链路」。弹窗显示电脑 IP 和端口。多网卡时，选择与手机同一局域网的 IP。
5. 在 App 的「调试链路」填入电脑 IP 和端口，令牌留空即可连接。也可随时运行「rc-tune: 查看手机连接参数」重看。

再次点击状态栏可停止桥接。建议在结束 F5 前先停止桥接，让它有机会写入停止状态。F5 会话结束后，扩展会关闭对应桥接进程。探针已断开时，停止状态可能写入失败，具体原因在 VS Code 的 `rc-tune` 输出频道查看。

首版支持本地桌面 VS Code 上的单核 OpenOCD 会话。固件 ELF 需要带 DWARF 调试信息，并包含符合遥控协议布局的 `g_rc`。手机连接电脑的 HTTP 端口 8765。Windows 防火墙如有弹窗，请按需允许专用网络访问。

## 打包

用 Python 3.12 或以上运行 `build_vsix.py`，并准备 Node.js 和 npx。运行时使用 [python-build-standalone](https://github.com/astral-sh/python-build-standalone) 的对应 x64、Python 3.12 `install_only` tar.gz 和 pyelftools 的纯 Python wheel。运行时和 wheel 内的许可证随包分发。

```bash
python3 build_vsix.py linux-x64 /path/to/python-linux.tar.gz /path/to/pyelftools.whl rc-tune-linux-x64.vsix
python3 build_vsix.py win32-x64 /path/to/python-windows.tar.gz /path/to/pyelftools.whl rc-tune-win32-x64.vsix
node --test extension.test.js
```
