#!/bin/sh
# 一键启动 rc-tune 桥接器（Linux / macOS）
set -e
dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
# 从 tools/rc-tune 里直接运行（比如双击）时，ELF 和 .vscode 都在上两级的工程根。
if [ -f "$dir/../../CMakeLists.txt" ] || [ -d "$dir/../../.vscode" ]; then
    cd "$dir/../.." || exit 2
fi
if command -v python3 >/dev/null 2>&1; then py=python3
elif command -v python >/dev/null 2>&1; then py=python
else echo "找不到 python3，请先安装 Python 3.9+" >&2; exit 2
fi
exec "$py" "$dir/start.py" "$@"
