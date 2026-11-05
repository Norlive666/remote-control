#!/usr/bin/env python3
"""一键启动 rc-tune 桥接器：准备依赖、找到 ELF、启动 HTTP 服务。

Windows 与 Linux 通用。首次运行会在本目录建 `.venv` 并安装 pyocd/pyelftools；
之后直接复用。双击用 `start.bat`（Windows）或 `./start.sh`（Linux），
命令行用 `python3 start.py --allow-write`。

    python3 start.py                     # 自动找 ELF，交互询问是否允许写入
    python3 start.py --elf build/x.elf --allow-write
    python3 start.py --no-venv           # 复用当前解释器里已装的依赖
"""
from __future__ import annotations

import argparse
import os
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
VENV = ROOT / ".venv"
TUNE = ROOT / "tune.py"
MIN_PYTHON = (3, 9)
# 按优先级找固件：先当前目录，再常见的构建输出目录。
ELF_PATTERNS = ("*.elf", "build/*.elf", "build/*/*.elf", "out/*.elf", "Debug/*.elf", "Release/*.elf")
# (pip 包名, 导入模块名)：pip 装的是 pyelftools，模块却叫 elftools。
DEPS = (("pyocd", "pyocd"), ("pyelftools", "elftools"))


class StartError(Exception):
    """可预期的启动错误，直接打印给用户看。"""


# ---------------------------------------------------------------- 纯逻辑


def find_elf(start: Path) -> Path:
    """在 start 目录下找一个唯一的 .elf；找不到或有歧义都抛 StartError。"""
    found = sorted({path.resolve() for pattern in ELF_PATTERNS for path in start.glob(pattern)})
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


def dep_probe() -> str:
    return "import " + ", ".join(module for _, module in DEPS)


def package_names() -> list[str]:
    return [package for package, _ in DEPS]


def missing_deps(python: Path) -> bool:
    return subprocess.call([str(python), "-c", dep_probe()], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL) != 0


# ---------------------------------------------------------------- 环境准备


def ensure_venv(index_url: str | None = None) -> Path:
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
    if missing_deps(python):
        print(f"首次运行：安装 {' / '.join(package_names())}（约 30 MB，只需一次，网络慢可能要几分钟）…")
        install = [str(python), "-m", "pip", "install", "--disable-pip-version-check", "--progress-bar", "off", *package_names()]
        if index_url:
            install += ["-i", index_url]
        if subprocess.call(install) != 0:
            raise StartError(
                "依赖安装失败。请检查网络后重试，或换镜像：\n"
                f"  python start.py --index-url https://pypi.tuna.tsinghua.edu.cn/simple\n"
                f"也可以手动执行：\n  {python} -m pip install {' '.join(package_names())}"
            )
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
        epilog="手机 App 里填本机打印的 IP、端口和令牌即可。",
    )
    parser.add_argument("--elf", help="固件 ELF（不填则自动在 ./、build/ 等目录找唯一的 .elf）")
    parser.add_argument("--symbol", default="g_rc", help="结构体变量名，默认 g_rc")
    parser.add_argument("--target", default="stm32h723vg", help="pyOCD 目标名，默认 stm32h723vg")
    parser.add_argument("--probe", default=None, help="多探针时用 UID 指定 DAPLink")
    parser.add_argument("--host", default="0.0.0.0", help="监听地址，默认 0.0.0.0")
    parser.add_argument("--port", type=int, default=8765, help="监听端口，默认 8765")
    parser.add_argument("--watchdog-ms", type=int, default=300, help="断流多久写安全值，默认 300")
    parser.add_argument("--no-token", action="store_true", help="关闭令牌校验（不推荐）")
    parser.add_argument("--allow-write", action="store_true", help="允许写入 g_rc")
    parser.add_argument("--read-only", action="store_true", help="强制只读，不再询问")
    parser.add_argument("--no-venv", action="store_true", help="不建虚拟环境，直接用当前解释器")
    parser.add_argument("--index-url", default=None, help="pip 镜像地址（网络慢时用）")
    return parser


def run(args: argparse.Namespace) -> int:
    if not args.no_venv and not running_in_venv():
        return relaunch(ensure_venv(args.index_url))

    elf = Path(args.elf).expanduser().resolve() if args.elf else find_elf(Path.cwd())
    if not elf.is_file():
        raise StartError(f"ELF 不存在：{elf}")

    write = args.allow_write or (not args.read_only and ask_write_permission())
    print(f"ELF     {elf}")
    print(f"模式    {'可写' if write else '只读（加 --allow-write 才能写入）'}")
    if os.name == "nt":
        print("提示    Windows 首次运行可能弹防火墙提示，请允许「专用网络」，否则手机连不上。")

    argv = ["serve", "--elf", str(elf), "--symbol", args.symbol, "--target", args.target,
            "--host", args.host, "--port", str(args.port), "--watchdog-ms", str(args.watchdog_ms)]
    if args.probe:
        argv += ["--probe", args.probe]
    if args.no_token:
        argv += ["--no-token"]
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
    sys.exit(main())