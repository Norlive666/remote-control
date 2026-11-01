#!/usr/bin/env bash
set -euo pipefail
destination=${1:?Provide a dependency directory}
mkdir -p "$destination"
fetch_component() {
    local repo=$1 commit=$2 path=$3
    git init -q "$path"
    git -C "$path" remote add origin "https://github.com/STMicroelectronics/$repo.git"
    git -C "$path" fetch -q --depth 1 origin "$commit"
    git -C "$path" checkout -q --detach FETCH_HEAD
}
cube="$destination/H7"
git clone -q --depth 1 --filter=blob:none --sparse --branch v1.13.0 "https://github.com/STMicroelectronics/STM32CubeH7.git" "$cube"
git -C "$cube" sparse-checkout set Drivers/CMSIS/Core/Include
fetch_component stm32h7xx-hal-driver a1996eed9172b59887bafaaa0ea1816ea14d48b5 "$cube/Drivers/STM32H7xx_HAL_Driver"
fetch_component cmsis-device-h7 8f922cdc7cc6de2344e75ddd657889f4ff761790 "$cube/Drivers/CMSIS/Device/ST/STM32H7xx"
