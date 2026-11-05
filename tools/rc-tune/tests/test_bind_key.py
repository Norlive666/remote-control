"""bind_key.py：把快捷键安全地合并进用户的 keybindings.json。"""

import json
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from bind_key import DEFAULT_KEYS, TASKS, BindError, apply, entries, merge, parse


def test_empty_file_gets_both_bindings():
    text, added, conflicts = merge("[]\n", entries())

    assert (added, conflicts) == (2, [])
    assert [item["key"] for item in json.loads(text)] == list(DEFAULT_KEYS)
    assert [item["args"] for item in json.loads(text)] == list(TASKS)
    assert all(item["command"] == "workbench.action.tasks.runTask" for item in json.loads(text))


def test_existing_bindings_and_comments_survive():
    original = """// 我的设置
[
  // 已经有的一条
  { "key": "ctrl+k", "command": "workbench.action.terminal.clear" }
]
"""

    text, added, conflicts = merge(original, entries())

    assert (added, conflicts) == (2, [])
    assert "// 我的设置" in text and "// 已经有的一条" in text
    keys = [item["key"] for item in parse(text)]
    assert keys == ["ctrl+k", *DEFAULT_KEYS]


def test_running_twice_changes_nothing():
    once, _, _ = merge("[]\n", entries())
    twice, added, conflicts = merge(once, entries())

    assert (added, conflicts) == (0, [])
    assert twice == once


def test_custom_keys_keep_task_order():
    text, _, _ = merge("[]\n", entries(("f9", "f10")))

    data = json.loads(text)
    assert [(item["key"], item["args"]) for item in data] == [
        ("f9", TASKS[0]),
        ("f10", TASKS[1]),
    ]


def test_apply_backs_up_and_is_idempotent(tmp_path):
    path = tmp_path / "keybindings.json"
    path.write_text('[{"key": "ctrl+k", "command": "x"}]\n', encoding="utf-8")

    message = apply(path)
    assert "2" in message
    assert (tmp_path / "keybindings.json.bak").exists()
    assert "ctrl+k" in path.read_text(encoding="utf-8")

    again = apply(path)
    assert "已经配好" in again


def test_apply_creates_missing_file(tmp_path):
    path = tmp_path / "User" / "keybindings.json"

    apply(path)

    assert path.exists()
    assert len(json.loads(path.read_text(encoding="utf-8"))) == 2


def test_dry_run_does_not_touch_the_file(tmp_path):
    path = tmp_path / "keybindings.json"
    path.write_text("[]\n", encoding="utf-8")

    output = apply(path, dry_run=True)

    assert "ctrl+alt+r" in output
    assert path.read_text(encoding="utf-8") == "[]\n"


def test_rejects_non_array_top_level():
    with pytest.raises(BindError):
        merge('{"key": "ctrl+k"}\n', entries())


def test_conflicting_key_is_skipped_not_overwritten(tmp_path):
    path = tmp_path / "keybindings.json"
    path.write_text('[{"key": "ctrl+alt+r", "command": "workbench.action.build"}]\n', encoding="utf-8")

    message = apply(path)

    assert "跳过" in message and "ctrl+alt+r" in message
    data = json.loads(path.read_text(encoding="utf-8"))
    assert data[0]["command"] == "workbench.action.build"
    assert [item["key"] for item in data] == ["ctrl+alt+r", "ctrl+alt+shift+r"]
