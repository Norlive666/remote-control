#!/usr/bin/env bash
set -euo pipefail
: "${STM32_H7_CUBE_DIR:?Provide official H7 dependency root}"
repo_dir=$(cd "$(dirname "$0")/../.." && pwd)
tmp_dir=$(mktemp -d)
trap 'rm -rf "$tmp_dir"' EXIT
cube=$STM32_H7_CUBE_DIR
cc -std=c11 -Wall -Wextra -Werror -fno-pie -no-pie -DSTM32H723xx \
   -I"$repo_dir/tests/c/hardware" -I"$repo_dir/stm32/h723" \
   -I"$cube/Drivers/CMSIS/Device/ST/STM32H7xx/Include" \
   "$repo_dir/tests/c/test_init.c" "$repo_dir/stm32/h723/remote_control.c" -o "$tmp_dir/h723"
for case in busy-uart busy-dma-rx busy-dma-tx busy-rx-ie busy-tc-ie busy-parity-ie busy-txe-ie busy-lin-ie busy-error-ie busy-cts-ie busy-tx-hardware busy-timer busy-irq busy-uart-irq invalid-baud invalid-clock invalid-uart-clock normal bt630 cache unmasked apb1 reuse-uart reuse-uart-irq default-baud old-peripherals timpre4 timpre8 hsi-div4 csi pll2 pll3 lse pll2-off pll2-no-q busy-rto-ie busy-fifo-ie; do
    "$tmp_dir/h723" "$case"
done
cc -std=c11 -Wall -Wextra -Werror -fno-pie -no-pie -DSTM32H723xx -DRC_DEFAULT_BAUD=9600U \
   -I"$repo_dir/tests/c/hardware" -I"$repo_dir/stm32/h723" \
   -I"$cube/Drivers/CMSIS/Device/ST/STM32H7xx/Include" \
   "$repo_dir/tests/c/test_init.c" "$repo_dir/stm32/h723/remote_control.c" -o "$tmp_dir/h723-baud"
"$tmp_dir/h723-baud" default-baud
cc -std=c11 -Wall -Wextra -Werror -fno-pie -no-pie -DSTM32H723xx -DRC_RX_INVERT=0 \
   -I"$repo_dir/tests/c/hardware" -I"$repo_dir/stm32/h723" \
   -I"$cube/Drivers/CMSIS/Device/ST/STM32H7xx/Include" \
   "$repo_dir/tests/c/test_init.c" "$repo_dir/stm32/h723/remote_control.c" -o "$tmp_dir/h723-normal-uart"
for case in normal bt630 reuse-uart reuse-uart-irq busy-dma-rx; do "$tmp_dir/h723-normal-uart" "$case"; done
if cc -std=c11 -DSTM32H723xx -DRC_RX_INVERT=2 \
   -I"$repo_dir/tests/c/hardware" -I"$repo_dir/stm32/h723" \
   -I"$cube/Drivers/CMSIS/Device/ST/STM32H7xx/Include" \
   -c "$repo_dir/stm32/h723/remote_control.c" -o "$tmp_dir/invalid.o" 2>"$tmp_dir/invalid.log"; then
    echo "h723: invalid inversion unexpectedly accepted" >&2; exit 1
fi
echo "h723: invalid inversion rejected"
