#!/usr/bin/env bash
set -euo pipefail
repo_dir=$(cd "$(dirname "$0")/../.." && pwd)
tmp_dir=$(mktemp -d)
trap 'rm -rf "$tmp_dir"' EXIT
for target in h723 f103; do
    cc -std=c11 -Wall -Wextra -Werror -pedantic -DRC_HOST_TEST \
      -I"$repo_dir/stm32/$target" \
      "$repo_dir/tests/c/test_remote_control.c" \
      "$repo_dir/stm32/$target/remote_control.c" -o "$tmp_dir/$target"
    "$tmp_dir/$target"
done
