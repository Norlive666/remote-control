#!/usr/bin/env bash
set -euo pipefail
: "${STM32_H7_CUBE_DIR:?Provide official H7 dependency root}"
: "${STM32_F1_CUBE_DIR:?Provide official F1 dependency root}"
repo_dir=$(cd "$(dirname "$0")/../.." && pwd)
tmp_dir=$(mktemp -d)
trap 'rm -rf "$tmp_dir"' EXIT
for spec in "h723 STM32H723xx $STM32_H7_CUBE_DIR STM32H7xx" "f103 STM32F103xB $STM32_F1_CUBE_DIR STM32F1xx"; do
    read -r name chip cube series <<< "$spec"
    cc -std=c11 -Wall -Wextra -Werror -fno-pie -no-pie -D"$chip" \
       -I"$repo_dir/tests/c/hardware" -I"$repo_dir/stm32/$name" \
       -I"$cube/Drivers/CMSIS/Device/ST/$series/Include" \
       "$repo_dir/tests/c/test_init.c" "$repo_dir/stm32/$name/remote_control.c" -o "$tmp_dir/$name"
    for case in busy-uart busy-timer busy-irq busy-uart-irq invalid-baud invalid-clock invalid-uart-clock normal bt630 cache unmasked apb1; do
        "$tmp_dir/$name" "$case"
    done
    if [[ "$name" == f103 ]]; then "$tmp_dir/$name" busy-remap;
    else
        for case in old-peripherals timpre4 timpre8 hsi-div4 csi pll2 pll3 lse pll2-off pll2-no-q; do
            "$tmp_dir/$name" "$case"
        done
    fi
done
