#!/usr/bin/env bash
set -euo pipefail
: "${STM32_H7_CUBE_DIR:?Set STM32_H7_CUBE_DIR to STM32Cube_FW_H7 root}"
repo_dir=$(cd "$(dirname "$0")/.." && pwd)
tmp_dir=$(mktemp -d)
trap 'rm -rf "$tmp_dir"' EXIT
cat > "$tmp_dir/stm32h7xx_hal_conf.h" <<'CONF'
#ifndef STM32H7XX_HAL_CONF_H
#define STM32H7XX_HAL_CONF_H
#define HAL_MODULE_ENABLED
#define HAL_RCC_MODULE_ENABLED
#define HSE_VALUE 25000000U
#define HSI_VALUE 64000000U
#define CSI_VALUE 4000000U
#define LSE_VALUE 32768U
#define LSI_VALUE 32000U
#define VDD_VALUE 3300U
#define TICK_INT_PRIORITY 0x0FU
#define USE_RTOS 0U
#define PREFETCH_ENABLE 0U
#define ART_ACCLERATOR_ENABLE 1U
#include "stm32h7xx_hal_rcc.h"
#endif
CONF
name=h723; chip=STM32H723xx; cpu=cortex-m7; cube=$STM32_H7_CUBE_DIR; series=STM32H7xx
arm-none-eabi-gcc -std=c11 -Wall -Wextra -Werror -mcpu="$cpu" -mthumb -D"$chip" \
  -I"$tmp_dir" -I"$cube/Drivers/${series}_HAL_Driver/Inc" \
  -I"$cube/Drivers/CMSIS/Device/ST/$series/Include" \
  -I"$cube/Drivers/CMSIS/Core/Include" \
  -c "$repo_dir/stm32/$name/remote_control.c" -o "$tmp_dir/$name.o"
arm-none-eabi-gcc -std=c11 -Wall -Wextra -Werror -mcpu="$cpu" -mthumb -D"$chip" \
  -I"$tmp_dir" -I"$cube/Drivers/${series}_HAL_Driver/Inc" \
  -I"$cube/Drivers/CMSIS/Device/ST/$series/Include" \
  -I"$cube/Drivers/CMSIS/Core/Include" \
  -c "$repo_dir/tests/c/test_generated_handlers.c" -o "$tmp_dir/$name-generated.o"
arm-none-eabi-gcc -mcpu="$cpu" -mthumb -nostdlib -Wl,-e,main \
  "$tmp_dir/$name.o" "$tmp_dir/$name-generated.o" -lgcc -o "$tmp_dir/$name.elf"
echo "$name: no UART/TIM HAL dependency; generated IRQ coexistence link passed"
