"""Build a platform-specific VSIX from a CPython install_only tarball and pyelftools wheel."""

import argparse
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile
from zipfile import ZipFile

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("target", choices=("linux-x64", "win32-x64"))
    parser.add_argument("python_archive", type=Path)
    parser.add_argument("pyelftools_wheel", type=Path)
    parser.add_argument("vsix", type=Path)
    args = parser.parse_args()
    destination = args.vsix.resolve()
    destination.parent.mkdir(parents=True, exist_ok=True)

    with tempfile.TemporaryDirectory(prefix="rc-tune-vsix-") as scratch:
        stage = Path(scratch)
        bridge = stage / "bridge"
        bridge.mkdir()
        for file in ("extension.js", "package.json", "README.md"):
            shutil.copy2(HERE / file, stage / file)
        shutil.copy2(REPO / "LICENSE", stage / "LICENSE")
        for file in ("run.py", "tune.py"):
            shutil.copy2(HERE / file, bridge / file)

        with tarfile.open(args.python_archive) as archive:
            archive.extractall(bridge, filter="data")
        runtime = bridge / "python"
        executable = runtime / ("bin/python3.12" if args.target == "linux-x64" else "python.exe")
        if not executable.is_file():
            parser.error("Python archive does not contain the selected target's Python 3.12 executable")
        # The bridge does not use curses. Terminfo includes paths that differ
        # only in case, which VSIX archives cannot represent.
        terminfo = runtime / "share" / "terminfo"
        if args.target == "linux-x64" and terminfo.is_dir():
            shutil.rmtree(terminfo)
        for cache in runtime.rglob("__pycache__"):
            if cache.is_dir():
                shutil.rmtree(cache)
        # The bridge executes the versioned binary. Omit aliases so the VSIX
        # contains regular files and works with Windows and Unix ZIP extractors.
        for alias in runtime.rglob("*"):
            if alias.is_symlink():
                alias.unlink()
        with ZipFile(args.pyelftools_wheel) as wheel:
            if "elftools/elf/elffile.py" not in wheel.namelist():
                parser.error("expected a pyelftools wheel")
            wheel.extractall(bridge / "vendor")

        vsce = shutil.which("vsce")
        if vsce:
            command = [vsce]
        else:
            npx = shutil.which("npx")
            if npx is None:
                parser.error("VSCE or npx is required to package the extension")
            command = [npx, "--yes", "--package", "@vscode/vsce@3.9.1", "vsce"]
        subprocess.run([
            *command, "package",
            "--no-dependencies", "--target", args.target, "--out", str(destination),
        ], cwd=stage, check=True)


if __name__ == "__main__":
    main()
