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


def _fake_processes(monkeypatch, stdout: str) -> None:
    monkeypatch.setattr("start._command_lines", lambda: stdout.splitlines())


def test_detects_gdb_port_from_running_openocd(monkeypatch):
    from start import detect_openocd_port

    _fake_processes(monkeypatch, '4242 openocd -f interface/stlink.cfg -c "gdb_port 50001" -f target/stm32h7x.cfg\n')

    assert detect_openocd_port() == 50001


def test_ignores_dynamic_zero_port(monkeypatch):
    from start import detect_openocd_port

    _fake_processes(monkeypatch, '4242 openocd -c "gdb_port 0"\n')

    assert detect_openocd_port() is None


def test_detects_gdb_port_written_as_two_words(monkeypatch):
    from start import detect_openocd_port

    _fake_processes(monkeypatch, '4242 openocd -f interface/stlink.cfg -c "gdb port 50002" -f target/stm32h7x.cfg\n')

    assert detect_openocd_port() == 50002


@pytest.mark.parametrize("option", ["tcl_port", "tcl port"])
@pytest.mark.parametrize("explicit", [False, True])
def test_tcl_start_uses_tcl_port_without_opening_gdb(monkeypatch, tmp_path, option, explicit):
    import start

    elf = tmp_path / "firmware.elf"
    elf.touch()
    monkeypatch.setattr(start, "openocd_processes",
                        lambda: [f'4242 openocd -c "gdb_port 2000" -c "{option} 2001"'])
    monkeypatch.setattr(start, "port_open", lambda *args: pytest.fail("Tcl must not open GDB"))
    forwarded = []

    def run_tune(argv):
        forwarded.extend(argv)
        return 0

    monkeypatch.setattr("tune.main", run_tune)
    argv = ["--backend", "openocd-tcl", "--elf", str(elf), "--no-venv", "--read-only"]
    if explicit:
        argv += ["--ocd-port", "2002"]

    assert start.main(argv) == 0
    assert forwarded[forwarded.index("--backend") + 1] == "openocd-tcl"
    assert forwarded[forwarded.index("--ocd-port") + 1] == ("2002" if explicit else "2001")


def test_tcl_detection_requires_an_explicit_port_with_multiple_instances(monkeypatch):
    import start

    monkeypatch.setattr(start, "openocd_processes",
                        lambda: ["4242 openocd -c tcl_port 2001", "4243 openocd"])

    with pytest.raises(StartError, match="--ocd-port"):
        start.detect_openocd_port("tcl")


def test_tcl_start_does_not_launch_another_openocd(monkeypatch):
    import start

    monkeypatch.setattr(start, "spawn_openocd", lambda *args: pytest.fail("Tcl is attach-only"))
    args = start.build_parser().parse_args(["--backend", "openocd-tcl", "--start-ocd", "--no-venv"])

    with pytest.raises(StartError, match="--start-ocd"):
        start.run(args)


def test_openocd_processes_match_only_the_executable():
    """start.py 自己的命令行里也有 openocd 这个词，不能被当成一个实例。"""
    from start import _is_openocd

    assert _is_openocd('/usr/bin/openocd -f interface/stlink.cfg')
    assert _is_openocd('"C:\\Tools\\openocd.exe" -f interface/stlink.cfg')
    assert not _is_openocd('python3 tools/rc-tune/start.py --backend openocd')
    assert not _is_openocd('')


def test_openocd_args_include_configs_and_port(monkeypatch, tmp_path):
    from start import openocd_args

    monkeypatch.chdir(tmp_path)
    (tmp_path / ".vscode").mkdir()
    (tmp_path / ".vscode" / "h7-debug-unlock.cfg").write_text("")

    args = openocd_args(3333, [".vscode/h7-debug-unlock.cfg"])

    assert args[:2] == ["-s", str(tmp_path)]
    assert args.count("-f") == 3
    assert "interface/stlink.cfg" in args
    assert "target/stm32h7x.cfg" in args
    assert ".vscode/h7-debug-unlock.cfg" in args
    assert args[-1] == "gdb_port 3333"


def test_openocd_args_skip_a_missing_local_config(monkeypatch, tmp_path, capsys):
    from start import openocd_args

    monkeypatch.chdir(tmp_path)

    args = openocd_args(3333, [".vscode/not-here.cfg"])

    assert ".vscode/not-here.cfg" not in args
    assert args.count("-f") == 2  # 只剩 interface + target
    assert "跳过不存在的 OpenOCD 配置" in capsys.readouterr().out


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


def test_broken_venv_is_rebuilt(monkeypatch, tmp_path, capsys):
    import start

    venv = tmp_path / ".venv"
    (venv / "bin").mkdir(parents=True)
    broken = venv / "bin" / "python"
    broken.write_text("#!/bin/sh\nexit 1\n")
    broken.chmod(0o755)

    monkeypatch.setattr(start, "VENV", venv)
    monkeypatch.setattr(start, "missing_deps", lambda python, backend="openocd": False)

    python = start.ensure_venv(None, "openocd")

    assert start.venv_works(python)
    assert "删掉重建" in capsys.readouterr().out


def test_venv_from_an_older_python_is_rebuilt(monkeypatch, tmp_path, capsys):
    import start

    venv = tmp_path / ".venv"
    (venv / "bin").mkdir(parents=True)
    python = venv / "bin" / "python"
    python.write_text("#!/bin/sh\necho 3 15\n")
    python.chmod(0o755)
    (venv / "pyvenv.cfg").write_text("home = /usr/bin\nversion = 3.14.9\n")

    monkeypatch.setattr(start, "VENV", venv)
    monkeypatch.setattr(start, "missing_deps", lambda python, backend="openocd": False)

    rebuilt = start.ensure_venv(None, "openocd")

    assert start.venv_python_version(rebuilt) is not None
    assert start.venv_recorded_version() != (3, 14)
    assert "Python 3.14" in capsys.readouterr().out


def test_detects_the_probe_interface_from_launch_json(tmp_path):
    from start import detect_interface

    (tmp_path / ".vscode").mkdir()
    (tmp_path / ".vscode" / "launch.json").write_text(
        '{"configurations": [{"configFiles": ["interface/cmsis-dap.cfg", "target/stm32h7x.cfg"]}]}'
    )

    assert detect_interface(tmp_path) == "interface/cmsis-dap.cfg"


def test_interface_flag_accepts_a_bare_name(tmp_path):
    from start import resolve_interface

    assert resolve_interface("cmsis-dap", tmp_path) == "interface/cmsis-dap.cfg"
    assert resolve_interface("interface/jlink.cfg", tmp_path) == "interface/jlink.cfg"
    assert resolve_interface(None, tmp_path) == "interface/stlink.cfg"


def test_openocd_args_use_the_given_interface(monkeypatch, tmp_path):
    from start import openocd_args

    monkeypatch.chdir(tmp_path)

    args = openocd_args(3333, [], "interface/cmsis-dap.cfg")

    assert "interface/cmsis-dap.cfg" in args
    assert "interface/stlink.cfg" not in args


def test_project_root_falls_back_to_the_parent_of_tools(monkeypatch, tmp_path):
    """双击 start.bat 时 cwd 在 tools/rc-tune，ELF 和 .vscode 却在工程根。"""
    import start

    root = tmp_path / "proj"
    (root / ".vscode").mkdir(parents=True)
    (root / "tools" / "rc-tune").mkdir(parents=True)
    monkeypatch.setattr(start, "PROJECT_ROOT", root)
    monkeypatch.chdir(root / "tools" / "rc-tune")

    assert start.project_root() == root


def test_project_root_keeps_cwd_when_it_is_a_project(monkeypatch, tmp_path):
    import start

    monkeypatch.setattr(start, "PROJECT_ROOT", tmp_path / "elsewhere")
    project = tmp_path / "proj"
    (project / ".vscode").mkdir(parents=True)
    monkeypatch.chdir(project)

    assert start.project_root() == project


def test_openocd_args_find_a_local_config_under_the_project_root(tmp_path):
    from start import openocd_args

    root = tmp_path / "proj"
    (root / ".vscode").mkdir(parents=True)
    cfg = root / ".vscode" / "h7-debug-unlock.cfg"
    cfg.write_text("")

    args = openocd_args(3333, [".vscode/h7-debug-unlock.cfg"], root=root)

    assert ".vscode/h7-debug-unlock.cfg" in args


def test_venv_that_cannot_execute_is_kept(monkeypatch, tmp_path):
    """执行被拒（EPERM/EACCES）不是环境损坏，不能把 .venv 删掉重装。"""
    import start

    venv = tmp_path / ".venv"
    (venv / "bin").mkdir(parents=True)
    python = venv / "bin" / "python"
    python.write_text("#!/bin/sh\nexit 0\n")
    python.chmod(0o644)
    monkeypatch.setattr(start, "VENV", venv)

    with pytest.raises(StartError, match="无法执行"):
        start.ensure_venv(None, "openocd")

    assert python.exists()


def test_openocd_path_can_come_from_the_flag(monkeypatch, tmp_path):
    import start

    fake = tmp_path / "openocd.exe"
    fake.write_text("")
    seen = {}

    class Proc:
        pass

    def fake_popen(argv):
        seen["argv"] = argv
        return Proc()

    monkeypatch.setattr("start.subprocess.Popen", fake_popen)

    start.spawn_openocd(3333, [], exe=str(fake))

    assert seen["argv"][0] == str(fake)


def test_openocd_path_can_come_from_the_environment(monkeypatch, tmp_path):
    import start

    fake = tmp_path / "openocd"
    fake.write_text("")
    seen = {}

    def fake_popen(argv):
        seen["argv"] = argv
        return object()

    monkeypatch.setenv("RC_TUNE_OPENOCD", str(fake))
    monkeypatch.setattr("start.subprocess.Popen", fake_popen)

    start.spawn_openocd(3333, [])

    assert seen["argv"][0] == str(fake)


def test_missing_openocd_mentions_the_flag(monkeypatch):
    import start

    monkeypatch.delenv("RC_TUNE_OPENOCD", raising=False)
    monkeypatch.setattr("start.shutil.which", lambda name: None)

    with pytest.raises(StartError, match="--openocd"):
        start.resolve_openocd()
