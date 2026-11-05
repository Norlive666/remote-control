"""start.py 的纯逻辑部分：ELF 定位。"""

import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from start import StartError, find_elf


def test_finds_single_elf_under_build(tmp_path):
    (tmp_path / "build").mkdir()
    target = tmp_path / "build" / "robot.elf"
    target.write_bytes(b"\x7fELF")

    assert find_elf(tmp_path) == target.resolve()


def test_finds_elf_in_working_directory(tmp_path):
    target = tmp_path / "firmware.elf"
    target.write_bytes(b"\x7fELF")

    assert find_elf(tmp_path) == target.resolve()


def test_ambiguous_elfs_ask_for_explicit_path(tmp_path):
    (tmp_path / "build").mkdir()
    for name in ("a.elf", "b.elf"):
        (tmp_path / "build" / name).write_bytes(b"\x7fELF")

    with pytest.raises(StartError) as info:
        find_elf(tmp_path)

    message = str(info.value)
    assert "a.elf" in message and "b.elf" in message
    assert "--elf" in message


def test_missing_elf_explains_how_to_build(tmp_path):
    with pytest.raises(StartError) as info:
        find_elf(tmp_path)

    assert "--elf" in str(info.value)

def test_dependency_probe_imports_module_names():
    """pip 包名 pyelftools 的模块名是 elftools，探针必须用模块名。"""
    from start import dep_probe

    assert dep_probe() == "import elftools"
    assert dep_probe("openocd") == "import elftools"
    assert dep_probe("pyocd") == "import pyocd, elftools"


def test_dependency_install_uses_package_names():
    from start import package_names

    assert package_names() == ["pyelftools"]
    assert package_names("pyocd") == ["pyocd", "pyelftools"]


def test_openocd_backend_does_not_need_pyocd():
    """默认走 OpenOCD 时不该逼用户下载 pyocd 那 15 MB。"""
    from start import required_deps

    assert [module for _, module in required_deps("openocd")] == ["elftools"]
    assert [module for _, module in required_deps("pyocd")] == ["pyocd", "elftools"]


def test_missing_openocd_explains_how_to_fix():
    import socket

    from start import StartError, ensure_openocd

    sock = socket.socket()
    sock.bind(("127.0.0.1", 0))
    port = sock.getsockname()[1]
    sock.close()

    with pytest.raises(StartError) as info:
        ensure_openocd("127.0.0.1", port)

    message = str(info.value)
    assert str(port) in message and "--ocd-port" in message


def test_ignores_elfs_in_dependency_dirs(tmp_path):
    """.venv/node_modules 里常有 ELF，不能当成固件。"""
    (tmp_path / "build").mkdir()
    (tmp_path / "build" / "robot.elf").write_bytes(b"\x7fELF")
    for skipped in (".venv/lib", "node_modules/pkg", "__pycache__"):
        (tmp_path / skipped).mkdir(parents=True)
        (tmp_path / skipped / "fake.elf").write_bytes(b"\x7fELF")

    assert find_elf(tmp_path).name == "robot.elf"
