/* Link fixture: generated strong IRQs must coexist; only RCC HAL is supplied. */
#include <stddef.h>
#ifdef STM32H723xx
#include "stm32h7xx_hal.h"
#else
#include "stm32f1xx_hal.h"
#endif
#ifdef STM32H723xx
void USART3_IRQHandler(void) {}
void TIM5_IRQHandler(void) {}
#endif
void USART1_IRQHandler(void) {}
void TIM4_IRQHandler(void) {}
uint32_t HAL_RCC_GetPCLK1Freq(void) { return 36000000; }
uint32_t HAL_RCC_GetPCLK2Freq(void) { return 72000000; }
void HAL_RCC_GetClockConfig(RCC_ClkInitTypeDef *cfg, uint32_t *latency) {
    cfg->APB1CLKDivider=RCC_HCLK_DIV2; *latency=0;
}
#ifdef STM32H723xx
uint32_t HAL_RCCEx_GetPeriphCLKFreq(uint64_t peripheral) { (void)peripheral; return 64000000; }
#endif
void *memmove(void *dst, const void *src, size_t n) {
    unsigned char *d=dst; const unsigned char *s=src;
    if (d<s) { for (size_t i=0;i<n;i++) d[i]=s[i]; }
    else { while (n) { n--; d[n]=s[n]; } }
    return dst;
}
int main(void) { return 0; }
