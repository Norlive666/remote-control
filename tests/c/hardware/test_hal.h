#ifndef TEST_HAL_H
#define TEST_HAL_H
#ifdef STM32H723xx
#include "stm32h723xx.h"
#define RCC_HCLK_DIV1 0u
#define RCC_HCLK_DIV2 1u
#define RCC_HCLK_DIV4 2u
#define RCC_HCLK_DIV8 3u
#define RCC_PERIPHCLK_USART1 1u
#define RCC_PERIPHCLK_USART3 2u
#define __HAL_RCC_GPIOD_CLK_ENABLE() (RCC->AHB4ENR |= RCC_AHB4ENR_GPIODEN)
#define __HAL_RCC_USART3_CLK_ENABLE() (RCC->APB1LENR |= RCC_APB1LENR_USART3EN)
#define __HAL_RCC_TIM5_CLK_ENABLE() (RCC->APB1LENR |= RCC_APB1LENR_TIM5EN)
#define __HAL_RCC_GPIOA_CLK_ENABLE() (RCC->AHB4ENR |= RCC_AHB4ENR_GPIOAEN)
#else
#include "stm32f103xb.h"
#define RCC_HCLK_DIV1 0u
#define RCC_HCLK_DIV2 1u
#define RCC_HCLK_DIV4 2u
#define RCC_HCLK_DIV8 3u
#define __HAL_RCC_GPIOA_CLK_ENABLE() (RCC->APB2ENR |= RCC_APB2ENR_IOPAEN)
#define __HAL_RCC_AFIO_CLK_ENABLE() (RCC->APB2ENR |= RCC_APB2ENR_AFIOEN)
#endif
#define __HAL_RCC_USART1_CLK_ENABLE() (RCC->APB2ENR |= RCC_APB2ENR_USART1EN)
#define __HAL_RCC_TIM4_CLK_ENABLE() (RCC->APB1ENR |= RCC_APB1ENR_TIM4EN)
#ifdef STM32H723xx
#undef __HAL_RCC_TIM4_CLK_ENABLE
#define __HAL_RCC_TIM4_CLK_ENABLE() (RCC->APB1LENR |= RCC_APB1LENR_TIM4EN)
#endif
#define CLEAR_BIT(reg, bit) ((reg) &= ~(bit))
#define SET_BIT(reg, bit) ((reg) |= (bit))
#define READ_BIT(reg, bit) ((reg) & (bit))
#define MODIFY_REG(reg, clear, set) ((reg) = ((reg) & ~(clear)) | (set))
typedef enum { HAL_OK=0, HAL_ERROR=1, HAL_BUSY=2, HAL_TIMEOUT=3 } HAL_StatusTypeDef;
typedef struct { uint32_t APB1CLKDivider; } RCC_ClkInitTypeDef;
uint32_t HAL_RCC_GetPCLK1Freq(void);
uint32_t HAL_RCC_GetPCLK2Freq(void);
void HAL_RCC_GetClockConfig(RCC_ClkInitTypeDef *cfg, uint32_t *latency);
uint32_t HAL_RCCEx_GetPeriphCLKFreq(uint64_t peripheral);
#endif
