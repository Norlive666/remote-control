#!/usr/bin/env bash
set -euo pipefail
: "${STM32_H7_CUBE_DIR:?Set STM32_H7_CUBE_DIR to STM32Cube_FW_H7 root}"
: "${STM32_F1_CUBE_DIR:?Set STM32_F1_CUBE_DIR to STM32Cube_FW_F1 root}"
repo_dir=$(cd "$(dirname "$0")/.." && pwd)
tmp_dir=$(mktemp -d)
trap 'rm -rf "$tmp_dir"' EXIT
cat > "$tmp_dir/stm32h7xx_hal_conf.h" <<'CONF'
#ifndef STM32H7XX_HAL_CONF_H
#define STM32H7XX_HAL_CONF_H
#define HAL_MODULE_ENABLED
#define HAL_RCC_MODULE_ENABLED
#define HAL_GPIO_MODULE_ENABLED
#define HAL_DMA_MODULE_ENABLED
#define HAL_UART_MODULE_ENABLED
#define HAL_TIM_MODULE_ENABLED
#define HAL_CORTEX_MODULE_ENABLED
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
#include "stm32h7xx_hal_gpio.h"
#include "stm32h7xx_hal_dma.h"
#include "stm32h7xx_hal_uart.h"
#include "stm32h7xx_hal_tim.h"
#include "stm32h7xx_hal_cortex.h"
#endif
CONF
cat > "$tmp_dir/stm32f1xx_hal_conf.h" <<'CONF'
#ifndef STM32F1XX_HAL_CONF_H
#define STM32F1XX_HAL_CONF_H
#define HAL_MODULE_ENABLED
#define HAL_RCC_MODULE_ENABLED
#define HAL_GPIO_MODULE_ENABLED
#define HAL_DMA_MODULE_ENABLED
#define HAL_UART_MODULE_ENABLED
#define HAL_TIM_MODULE_ENABLED
#define HAL_CORTEX_MODULE_ENABLED
#define HSE_VALUE 8000000U
#define HSI_VALUE 8000000U
#define LSE_VALUE 32768U
#define LSI_VALUE 40000U
#define TICK_INT_PRIORITY 0x0FU
#define USE_RTOS 0U
#define PREFETCH_ENABLE 1U
#include "stm32f1xx_hal_rcc.h"
#include "stm32f1xx_hal_gpio.h"
#include "stm32f1xx_hal_dma.h"
#include "stm32f1xx_hal_uart.h"
#include "stm32f1xx_hal_tim.h"
#include "stm32f1xx_hal_cortex.h"
#endif
CONF
for spec in "h723 STM32H723xx cortex-m7 $STM32_H7_CUBE_DIR STM32H7xx" "f103 STM32F103xB cortex-m3 $STM32_F1_CUBE_DIR STM32F1xx"; do
    read -r name chip cpu cube series <<< "$spec"
    arm-none-eabi-gcc -std=c11 -Wall -Wextra -Werror -mcpu="$cpu" -mthumb -D"$chip" \
      -I"$tmp_dir" -I"$cube/Drivers/${series}_HAL_Driver/Inc" \
      -I"$cube/Drivers/CMSIS/Device/ST/$series/Include" \
      -I"$cube/Drivers/CMSIS/Core/Include" \
      -c "$repo_dir/stm32/$name/remote_control.c" -o "$tmp_dir/$name.o"
    echo "$name: target compile passed"
done
