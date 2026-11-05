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


def _fake_pgrep(monkeypatch, stdout: str) -> None:
    class Done:
        pass

    done = Done()
    done.stdout = stdout
    monkeypatch.setattr("start.subprocess.run", lambda *a, **k: done)


@pytest.mark.skipif(__import__("shutil").which("pgrep") is None, reason="需要 pgrep")
def test_detects_gdb_port_from_running_openocd(monkeypatch):
    from start import detect_openocd_port

    _fake_pgrep(monkeypatch, '4242 openocd -f interface/stlink.cfg -c "gdb_port 50001" -f target/stm32h7x.cfg\n')

    assert detect_openocd_port() == 50001


@pytest.mark.skipif(__import__("shutil").which("pgrep") is None, reason="需要 pgrep")
def test_ignores_dynamic_zero_port(monkeypatch):
    from start import detect_openocd_port

    _fake_pgrep(monkeypatch, '4242 openocd -c "gdb_port 0"\n')

    assert detect_openocd_port() is None


def test_openocd_args_include_configs_and_port(monkeypatch, tmp_path):
    from start import openocd_args

    monkeypatch.chdir(tmp_path)

    args = openocd_args(3333, [".vscode/h7-debug-unlock.cfg"])

    assert args[:2] == ["-s", str(tmp_path)]
    assert args.count("-f") == 3
    assert "interface/stlink.cfg" in args
    assert "target/stm32h7x.cfg" in args
    assert ".vscode/h7-debug-unlock.cfg" in args
    assert args[-1] == "gdb_port 3333"


def test_port_open_sees_listening_socket():
    import socket

    from start import port_open

    server = socket.socket()
    server.bind(("127.0.0.1", 0))
    server.listen(1)
    live = server.getsockname()[1]

    try:
        assert port_open("127.0.0.1", live) is True
    finally:
        server.close()

    probe = socket.socket()
    probe.bind(("127.0.0.1", 0))
    dead = probe.getsockname()[1]
    probe.close()

    assert port_open("127.0.0.1", dead) is False


def test_missing_openocd_binary_is_explained(monkeypatch):
    from start import StartError, spawn_openocd

    monkeypatch.setattr("start.shutil.which", lambda name: None)

    with pytest.raises(StartError, match="openocd"):
        spawn_openocd(3333, [])
