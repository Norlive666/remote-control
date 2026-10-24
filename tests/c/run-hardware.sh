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
    for case in busy-uart busy-dma-rx busy-dma-tx busy-rx-ie busy-tc-ie busy-parity-ie busy-txe-ie busy-lin-ie busy-error-ie busy-cts-ie busy-tx-hardware busy-timer busy-irq busy-uart-irq invalid-baud invalid-clock invalid-uart-clock normal bt630 cache unmasked apb1 reuse-uart reuse-uart-irq; do
        "$tmp_dir/$name" "$case"
    done
    if [[ "$name" == f103 ]]; then "$tmp_dir/$name" busy-remap;
    else
        for case in old-peripherals timpre4 timpre8 hsi-div4 csi pll2 pll3 lse pll2-off pll2-no-q busy-rto-ie busy-fifo-ie; do
            "$tmp_dir/$name" "$case"
        done
    fi
    cc -std=c11 -Wall -Wextra -Werror -fno-pie -no-pie -D"$chip" -DRC_DEFAULT_BAUD=115200U \
       -I"$repo_dir/tests/c/hardware" -I"$repo_dir/stm32/$name" \
       -I"$cube/Drivers/CMSIS/Device/ST/$series/Include" \
       "$repo_dir/tests/c/test_init.c" "$repo_dir/stm32/$name/remote_control.c" -o "$tmp_dir/$name-baud"
    "$tmp_dir/$name-baud" default-baud
    if [[ "$name" == h723 ]]; then
        cc -std=c11 -Wall -Wextra -Werror -fno-pie -no-pie -D"$chip" -DRC_RX_INVERT=1 \
           -I"$repo_dir/tests/c/hardware" -I"$repo_dir/stm32/$name" \
           -I"$cube/Drivers/CMSIS/Device/ST/$series/Include" \
           "$repo_dir/tests/c/test_init.c" "$repo_dir/stm32/$name/remote_control.c" -o "$tmp_dir/$name-inverted"
        for case in normal bt630 reuse-uart reuse-uart-irq busy-dma-rx; do "$tmp_dir/$name-inverted" "$case"; done
    fi
    invalid_inversion=1
    if [[ "$name" == h723 ]]; then invalid_inversion=2; fi
    if cc -std=c11 -D"$chip" -DRC_RX_INVERT="$invalid_inversion" \
       -I"$repo_dir/tests/c/hardware" -I"$repo_dir/stm32/$name" \
       -I"$cube/Drivers/CMSIS/Device/ST/$series/Include" \
       -c "$repo_dir/stm32/$name/remote_control.c" -o "$tmp_dir/invalid.o" 2>"$tmp_dir/invalid.log"; then
        echo "$name: invalid inversion unexpectedly accepted" >&2; exit 1
    fi
    echo "$name: invalid inversion rejected"
done
