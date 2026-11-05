#!/usr/bin/env python3
"""把 rc-tune 的快捷键写进 VS Code 的用户级 keybindings.json。

VS Code 不允许工作区自带快捷键，所以这一步只能改用户配置（不在仓库里）。
写入前会备份成 keybindings.json.bak，写完再校验一遍，失败就自动还原。
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import sys
from pathlib import Path

TASKS = ("rc-tune: 启动（允许写入）", "rc-tune: 启动（先只读）")
DEFAULT_KEYS = ("ctrl+alt+r", "ctrl+alt+shift+r")
COMMAND = "workbench.action.tasks.runTask"
EDITORS = ("Code", "Code - Insiders", "VSCodium", "Cursor")


class BindError(Exception):
    """可预期的用户错误，直接打印。"""


def candidates() -> list[Path]:
    home = Path.home()
    appdata = os.environ.get("APPDATA")
    if appdata:
        root = Path(appdata)
        return [root / app / "User" / "keybindings.json" for app in EDITORS]
    if sys.platform == "darwin":
        return [home / "Library/Application Support" / app / "User" / "keybindings.json" for app in EDITORS]
    return [home / ".config" / app / "User" / "keybindings.json" for app in EDITORS]


def locate() -> Path:
    for path in candidates():
        if path.parent.is_dir():
            return path
    raise BindError("没找到 VS Code 的用户配置目录；用 --file 指定 keybindings.json 的完整路径")


def entries(keys=DEFAULT_KEYS) -> list[dict]:
    return [
        {"key": key, "command": COMMAND, "args": task}
        for key, task in zip(keys, TASKS)
    ]


def _strip_jsonc(text: str) -> str:
    """去掉 // 与 /* */ 注释、以及数组/对象末尾多余逗号，再交给 json。"""
    out = []
    index = 0
    in_string = False
    escaped = False
    while index < len(text):
        char = text[index]
        if in_string:
            out.append(char)
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                in_string = False
            index += 1
            continue
        if char == '"':
            in_string = True
            out.append(char)
            index += 1
            continue
        if char == "/" and text[index + 1:index + 2] == "/":
            while index < len(text) and text[index] != "\n":
                index += 1
            continue
        if char == "/" and text[index + 1:index + 2] == "*":
            end = text.find("*/", index + 2)
            index = len(text) if end == -1 else end + 2
            continue
        out.append(char)
        index += 1
    return re.sub(r",(\s*[\]}])", r"\1", "".join(out))


def parse(text: str):
    try:
        return json.loads(_strip_jsonc(text))
    except json.JSONDecodeError as exc:
        raise BindError(f"keybindings.json 解析失败：{exc}") from exc


def merge(text: str, new: list[dict]) -> tuple[str, int, list[str]]:
    """把还没出现过的快捷键插到数组末尾，保留原有注释与条目。

    返回 (新文本, 新增数量, 被别的命令占用的键)；占用时不覆盖，交给用户换键。
    """
    data = parse(text)
    if not isinstance(data, list):
        raise BindError("keybindings.json 的顶层不是数组")
    existing = {item.get("key"): item for item in data if isinstance(item, dict) and item.get("key")}
    wanted = []
    conflicts = []
    for item in new:
        current = existing.get(item["key"])
        if current is None:
            wanted.append(item)
        elif current.get("command") != item["command"] or current.get("args") != item["args"]:
            conflicts.append(item["key"])
    if not wanted:
        return text, 0, conflicts

    block = ",\n".join("  " + json.dumps(item, ensure_ascii=False) for item in wanted)
    stripped = text.rstrip()
    close = stripped.rfind("]")
    if close == -1:
        raise BindError("keybindings.json 里找不到结尾的 ]")
    head, tail = stripped[:close].rstrip(), stripped[close:]
    if head.endswith("[") or head.endswith(","):
        separator = "\n"
    else:
        separator = ",\n"
    result = f"{head}{separator}{block}\n{tail}\n"
    parse(result)  # 写回前再确认一次语法没坏
    return result, len(wanted), conflicts


def apply(path: Path, keys=DEFAULT_KEYS, dry_run: bool = False) -> str:
    text = path.read_text(encoding="utf-8") if path.exists() else "[]\n"
    result, added, conflicts = merge(text, entries(keys))
    notes = []
    if conflicts:
        notes.append(f"跳过已被占用的键：{'、'.join(conflicts)}（想换键用 --key f9 --key f10）")
    if added == 0:
        notes.append("没有可写的快捷键。" if notes else f"已经配好了，没动：{path}")
        return "\n".join(notes)
    if dry_run:
        notes.append(result)
        return "\n".join(notes)
    if path.exists():
        shutil.copy2(path, path.parent / f"{path.name}.bak")
    else:
        path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(result, encoding="utf-8")
    notes.append(f"写好 {added} 个快捷键：{path}（原文件备份在 {path.name}.bak）")
    return "\n".join(notes)


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="给 rc-tune 的两个任务装 VS Code 快捷键")
    parser.add_argument("--file", type=Path, default=None, help="手动指定 keybindings.json")
    parser.add_argument("--key", action="append", default=None,
                        help="快捷键，按「允许写入」「先只读」顺序给两次")
    parser.add_argument("--dry-run", action="store_true", help="只打印结果，不写文件")
    args = parser.parse_args(argv)

    keys = tuple(args.key) if args.key else DEFAULT_KEYS
    if len(keys) != 2:
        print("错误：--key 要给两次（先「允许写入」，再「先只读」）")
        return 2
    try:
        target = args.file or locate()
        print(apply(target, keys, args.dry_run))
    except (BindError, OSError) as exc:
        print(f"错误：{exc}")
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())