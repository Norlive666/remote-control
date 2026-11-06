#!/usr/bin/env python3
"""一键启动 rc-tune 桥接器：准备依赖、找到 ELF、启动 HTTP 服务。

默认连接你已经在跑的 OpenOCD（GDB server，默认 127.0.0.1:3333），不抢占探针；
也可以用 --backend pyocd 自己打开探针。首次运行会在本目录建 `.venv` 并装依赖，
之后直接复用。双击用 `start.bat`（Windows）或 `./start.sh`（Linux），
命令行用 `python3 start.py --allow-write`。

    python3 start.py                     # 自动找 ELF，交互询问是否允许写入
    python3 start.py --elf build/x.elf --allow-write
    python3 start.py --ocd-port 3334     # OpenOCD 的 gdb_port 不是 3333 时
    python3 start.py --backend pyocd     # 没跑 OpenOCD，让桥接器自己开探针
    python3 start.py --no-venv           # 复用当前解释器里已装的依赖
"""
from __future__ import annotations

import argparse
import atexit
import os
import re
import shutil
import socket
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent
VENV = ROOT / ".venv"
TUNE = ROOT / "tune.py"
MIN_PYTHON = (3, 9)
# 按优先级找固件：先当前目录，再常见的构建输出目录。
# 递归查找时跳过的目录：都是依赖/缓存，走进去只会拖慢启动。
ELF_SKIP_DIRS = frozenset({".git", ".venv", "venv", "node_modules", "__pycache__", ".cache"})
ELF_MAX_DEPTH = 6
# (pip 包名, 导入模块名)：pip 装的是 pyelftools，模块却叫 elftools。
CORE_DEPS = (("pyelftools", "elftools"),)
PYOCD_DEPS = (("pyocd", "pyocd"),)
BACKENDS = ("openocd", "pyocd")


class StartError(Exception):
    """可预期的启动错误，直接打印给用户看。"""


# ---------------------------------------------------------------- 纯逻辑


def find_elf(start: Path) -> Path:
    """在 start 目录下递归找一个唯一的 .elf；找不到或有歧义都抛 StartError。"""
    base = start.resolve()
    found = set()
    for root, dirs, files in os.walk(base):
        depth = len(Path(root).relative_to(base).parts)
        dirs[:] = [] if depth >= ELF_MAX_DEPTH else [d for d in dirs if d not in ELF_SKIP_DIRS]
        for name in files:
            if name.endswith(".elf"):
                found.add(Path(root, name).resolve())
    found = sorted(found)
    if len(found) == 1:
        return found[0]
    if found:
        lines = "\n".join(f"  {path}" for path in found)
        raise StartError(f"找到多个 .elf，请用 --elf 指定一个：\n{lines}")
    raise StartError("没找到 .elf 固件。请用 --elf 指定，或先在固件工程里编译出带调试信息的 ELF。")


def venv_python() -> Path:
    return VENV / ("Scripts/python.exe" if os.name == "nt" else "bin/python")


def running_in_venv() -> bool:
    python = venv_python()
    try:
        return python.exists() and Path(sys.executable).resolve() == python.resolve()
    except OSError:
        return False


def required_deps(backend: str) -> tuple[tuple[str, str], ...]:
    """openocd 后端只需要 pyelftools，省掉 pyocd 那 15 MB。"""
    return (PYOCD_DEPS if backend == "pyocd" else ()) + CORE_DEPS


def dep_probe(backend: str = "openocd") -> str:
    return "import " + ", ".join(module for _, module in required_deps(backend))


def package_names(backend: str = "openocd") -> list[str]:
    return [package for package, _ in required_deps(backend)]


def missing_deps(python: Path, backend: str = "openocd") -> bool:
    probe = [str(python), "-c", dep_probe(backend)]
    return subprocess.call(probe, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL) != 0


def detect_openocd_port() -> int | None:
    """从正在运行的 openocd 命令行里捡 gdb_port。

    VS Code 的调试扩展会给 OpenOCD 随机分配端口，命令行里通常能看到
    `-c "gdb_port 50001"`；读出来就不用每次手填 --ocd-port。
    """
    pgrep = shutil.which("pgrep")
    if pgrep is None:
        return None
    try:
        result = subprocess.run(
            [pgrep, "-af", "openocd"], capture_output=True, text=True, timeout=2, check=False
        )
    except (OSError, subprocess.SubprocessError):
        return None
    ports = [int(m) for m in re.findall(r"gdb_port\s+(\d+)", result.stdout)]
    ports = [port for port in ports if port > 0]
    return ports[0] if ports else None


OCD_CONFIGS = ("interface/stlink.cfg", "target/stm32h7x.cfg")


def _rsp_packet(payload: bytes) -> bytes:
    return b"$" + payload + b"#" + f"{sum(payload) & 0xFF:02x}".encode()


def port_open(host: str, port: int, timeout: float = 0.3) -> bool:
    """连一下 gdb_port；连上就先问好再 detach，免得 OpenOCD 留着半个会话把真连接挡掉。"""
    try:
        with socket.create_connection((host, port), timeout=timeout) as sock:
            sock.settimeout(timeout)
            try:
                sock.sendall(_rsp_packet(b"qSupported:multiprocess+"))
                sock.recv(4096)
                sock.sendall(_rsp_packet(b"D"))
                sock.recv(64)
            except OSError:
                pass
            return True
    except OSError:
        return False


def openocd_args(port: int, extra_configs: list[str]) -> list[str]:
    """组一条 `openocd -f …`：先按当前目录找配置，再带上用户追加的 cfg。"""
    args = ["-s", str(Path.cwd())]
    for config in (*OCD_CONFIGS, *extra_configs):
        args += ["-f", config]
    args += ["-c", f"gdb_port {port}"]
    return args


def spawn_openocd(port: int, extra_configs: list[str]) -> subprocess.Popen:
    """没有现成的 OpenOCD 时自己起一个；输出留在同一个终端里，方便看探针报错。"""
    exe = shutil.which("openocd")
    if exe is None:
        raise StartError("PATH 里找不到 openocd；先装上，或者去掉 --start-ocd、自己启动 OpenOCD。")
    argv = [exe, *openocd_args(port, extra_configs)]
    print(f"启动 OpenOCD：{' '.join(argv)}", flush=True)
    return subprocess.Popen(argv)


def wait_for_port(proc: subprocess.Popen, host: str, port: int, timeout: float = 10.0) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if port_open(host, port):
            return
        if proc.poll() is not None:
            raise StartError(
                f"OpenOCD 启动失败（退出码 {proc.returncode}），看上面它自己的报错；"
                "多半是探针被别的程序占着，或配置文件路径不对。"
            )
        time.sleep(0.1)
    stop_openocd(proc)
    raise StartError(f"OpenOCD 10 秒内没在 {host}:{port} 开 gdb server，检查探针是否插好、是否被占用。")


def stop_openocd(proc: subprocess.Popen) -> None:
    if proc.poll() is not None:
        return
    proc.terminate()
    try:
        proc.wait(timeout=5)
    except subprocess.TimeoutExpired:
        proc.kill()


def ensure_openocd(host: str, port: int, timeout: float = 0.5) -> None:
    """先探一下 OpenOCD 的 GDB server，免得装完依赖才发现连不上。"""
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return
    except OSError as exc:
        raise StartError(
            f"连不上 OpenOCD（{host}:{port}）：{exc}\n"
            "请先按你平时的任务启动 OpenOCD。端口不确定就看一眼 `pgrep -a openocd`，"
            "里面通常有 `-c gdb_port 50001` 这样的字样；也可以用 --ocd-port 指定，"
            "或者干脆用 --ocd-port 0 让脚本自己探测。\n"
            "没有 OpenOCD，或者想让桥接器自己占用探针，就用 --backend pyocd。"
        ) from exc


# ---------------------------------------------------------------- 环境准备


def install_deps(python: Path, index_url: str | None, backend: str) -> None:
    packages = package_names(backend)
    print(f"首次运行：安装 {' / '.join(packages)}（只需一次，网络慢可能要几分钟）…")
    install = [str(python), "-m", "pip", "install", "--disable-pip-version-check", "--progress-bar", "off", *packages]
    if index_url:
        install += ["-i", index_url]
    if subprocess.call(install) != 0:
        raise StartError(
            "依赖安装失败。请检查网络后重试，或换镜像：\n"
            "  python start.py --index-url https://pypi.tuna.tsinghua.edu.cn/simple\n"
            f"也可以手动执行：\n  {python} -m pip install {' '.join(packages)}"
        )


def ensure_venv(index_url: str | None, backend: str) -> Path:
    python = venv_python()
    if not python.exists():
        print(f"首次运行：在 {VENV} 创建虚拟环境…")
        import venv as venv_module

        try:
            venv_module.EnvBuilder(with_pip=True).create(VENV)
        except Exception as exc:  # noqa: BLE001 - 各种平台错误都转成一句人话
            raise StartError(
                f"创建虚拟环境失败：{exc}\n"
                "Debian/Ubuntu 需要先安装 python3-venv；或加 --no-venv 复用当前解释器。"
            ) from exc
    if missing_deps(python, backend):
        install_deps(python, index_url, backend)
        if missing_deps(python, backend):
            raise StartError(f"依赖装完了却导入不了：{dep_probe(backend)}")
    return python


def relaunch(python: Path) -> int:
    """用虚拟环境里的解释器重跑本脚本，后续逻辑就都在 venv 里了。"""
    return subprocess.call([str(python), str(Path(__file__).resolve()), *sys.argv[1:]])


def ask_write_permission() -> bool:
    """交互式确认是否允许写入；非交互环境（CI、管道）一律只读。"""
    if not sys.stdin.isatty():
        return False
    print("\n写入 g_rc 会直接驱动执行机构（电机/舵机/继电器），请先让机器人架空。")
    try:
        answer = input("允许写入？[y/N] ").strip().lower()
    except (EOFError, KeyboardInterrupt):
        print()
        return False
    return answer in ("y", "yes", "是")


# ---------------------------------------------------------------- 主流程


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="start.py",
        description="准备依赖并启动 rc-tune 桥接器（电脑端）。",
        epilog="手机 App 里填本机打印的 IP 和端口即可（令牌默认关闭）。",
    )
    parser.add_argument("--elf", help="固件 ELF（不填则自动在当前目录递归找唯一的 .elf）")
    parser.add_argument("--symbol", default="g_rc", help="结构体变量名，默认 g_rc")
    parser.add_argument("--backend", choices=BACKENDS, default="openocd",
                        help="openocd：连已运行的 OpenOCD（默认，不抢探针）；pyocd：自己打开探针")
    parser.add_argument("--ocd-host", default="127.0.0.1", help="OpenOCD 的 gdb_host，默认 127.0.0.1")
    parser.add_argument(
        "--ocd-port",
        type=int,
        default=0,
        help="OpenOCD 的 gdb_port；0 表示自动探测运行中的 openocd，探测不到再用 3333",
    )
    parser.add_argument("--start-ocd", action="store_true",
                        help="端口上没人时自己起一个 OpenOCD（退出时关闭），用于真正的一键启动")
    parser.add_argument("--ocd-config", action="append", default=[], metavar="CFG",
                        help="额外加载的 OpenOCD 配置，可给多次，如 .vscode/h7-debug-unlock.cfg")
    parser.add_argument("--target", default="stm32h723vg", help="pyOCD 目标名，仅 --backend pyocd 时使用")
    parser.add_argument("--probe", default=None, help="多探针时用 UID 指定 DAPLink（仅 pyocd）")
    parser.add_argument("--host", default="0.0.0.0", help="监听地址，默认 0.0.0.0")
    parser.add_argument("--port", type=int, default=8765, help="监听端口，默认 8765")
    parser.add_argument("--watchdog-ms", type=int, default=300, help="断流多久写安全值，默认 300")
    parser.add_argument("--token", default=None, help="可选令牌：默认不校验，给了就要求 App 带同一个值")
    parser.add_argument("--no-token", action="store_true", help=argparse.SUPPRESS)
    parser.add_argument("--allow-write", action="store_true", help="允许写入 g_rc")
    parser.add_argument("--read-only", action="store_true", help="强制只读，不再询问")
    parser.add_argument("--no-venv", action="store_true", help="不建虚拟环境，直接用当前解释器")
    parser.add_argument("--index-url", default=None, help="pip 镜像地址（网络慢时用）")
    return parser


def run(args: argparse.Namespace) -> int:
    if not args.no_venv and not running_in_venv():
        return relaunch(ensure_venv(args.index_url, args.backend))
    if not args.no_venv and missing_deps(Path(sys.executable), args.backend):
        # 换了后端（比如从 openocd 切到 pyocd）时补装缺的包。
        install_deps(Path(sys.executable), args.index_url, args.backend)

    elf = Path(args.elf).expanduser().resolve() if args.elf else find_elf(Path.cwd())
    if not elf.is_file():
        raise StartError(f"ELF 不存在：{elf}")

    spawned = False
    if args.backend == "openocd":
        args.ocd_port = args.ocd_port or detect_openocd_port() or 3333
        if port_open(args.ocd_host, args.ocd_port):
            print(f"探测到运行中的 OpenOCD，gdb_port = {args.ocd_port}（不对就用 --ocd-port 指定）")
        elif args.start_ocd:
            proc = spawn_openocd(args.ocd_port, args.ocd_config)
            atexit.register(stop_openocd, proc)
            wait_for_port(proc, args.ocd_host, args.ocd_port)
            spawned = True
        else:
            ensure_openocd(args.ocd_host, args.ocd_port)

    write = args.allow_write or (not args.read_only and ask_write_permission())
    print(f"ELF     {elf}")
    print(f"模式    {'可写' if write else '只读（加 --allow-write 才能写入）'}")
    if args.backend == "openocd":
        how = "脚本启动，退出时关闭" if spawned else "连已有实例，不抢探针"
        print(f"链路    OpenOCD {args.ocd_host}:{args.ocd_port}（{how}）")
    else:
        print(f"链路    pyOCD {args.target}")
    if os.name == "nt":
        print("提示    Windows 首次运行可能弹防火墙提示，请允许「专用网络」，否则手机连不上。")

    argv = ["serve", "--elf", str(elf), "--symbol", args.symbol,
            "--backend", args.backend, "--ocd-host", args.ocd_host, "--ocd-port", str(args.ocd_port),
            "--target", args.target,
            "--host", args.host, "--port", str(args.port), "--watchdog-ms", str(args.watchdog_ms)]
    if args.probe:
        argv += ["--probe", args.probe]
    if args.token:
        argv += ["--token", args.token]
    if write:
        argv += ["--allow-write"]
    sys.path.insert(0, str(ROOT))
    import tune  # 依赖都齐了才导入（pyocd/pyelftools 在 tune 里是延迟导入）

    return tune.main(argv)


def main(argv: list[str] | None = None) -> int:
    if sys.version_info < MIN_PYTHON:
        print(f"需要 Python {MIN_PYTHON[0]}.{MIN_PYTHON[1]} 或更高版本。", file=sys.stderr)
        return 2
    if hasattr(sys.stdout, "reconfigure"):  # Windows 旧控制台默认 GBK，中文会炸
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")

    args = build_parser().parse_args(argv)  # -h 在这里就退出，不会触发任何副作用
    try:
        return run(args)
    except StartError as exc:
        print(f"\n错误：{exc}\n", file=sys.stderr)
        return 2
    except KeyboardInterrupt:
        print("\n已停止。")
        return 130


if __name__ == "__main__":
    raise SystemExit(main())