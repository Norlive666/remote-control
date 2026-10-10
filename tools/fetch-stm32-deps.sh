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
for family in H7 F1; do
    version=v1.13.0
    if [[ "$family" == F1 ]]; then version=v1.8.7; fi
    cube="$destination/$family"
    git clone -q --depth 1 --filter=blob:none --sparse --branch "$version" "https://github.com/STMicroelectronics/STM32Cube$family.git" "$cube"
    git -C "$cube" sparse-checkout set Drivers/CMSIS/Core/Include
    if [[ "$family" == H7 ]]; then
        fetch_component stm32h7xx-hal-driver a1996eed9172b59887bafaaa0ea1816ea14d48b5 "$cube/Drivers/STM32H7xx_HAL_Driver"
        fetch_component cmsis-device-h7 8f922cdc7cc6de2344e75ddd657889f4ff761790 "$cube/Drivers/CMSIS/Device/ST/STM32H7xx"
    else
        fetch_component stm32f1xx-hal-driver fee494a92b5ad331f92ad21f76c66a5cb83773ee "$cube/Drivers/STM32F1xx_HAL_Driver"
        fetch_component cmsis-device-f1 c8e9a4a4f16b6d2cb2a2083cbe5161025280fb22 "$cube/Drivers/CMSIS/Device/ST/STM32F1xx"
    fi
done
