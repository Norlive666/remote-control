#!/bin/sh
# 一键启动 rc-tune 桥接器（Linux / macOS）
set -e
dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
if command -v python3 >/dev/null 2>&1; then py=python3
elif command -v python >/dev/null 2>&1; then py=python
else echo "找不到 python3，请先安装 Python 3.9+" >&2; exit 2
fi
exec "$py" "$dir/start.py" "$@"
