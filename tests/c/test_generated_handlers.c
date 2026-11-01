/* Link fixture: generated strong IRQs must coexist; only RCC HAL is supplied. */
#include <stddef.h>
#include "stm32h7xx_hal.h"
void USART3_IRQHandler(void) {}
void TIM5_IRQHandler(void) {}
void USART1_IRQHandler(void) {}
void TIM4_IRQHandler(void) {}
uint32_t HAL_RCC_GetPCLK1Freq(void) { return 36000000; }
uint32_t HAL_RCC_GetPCLK2Freq(void) { return 72000000; }
void HAL_RCC_GetClockConfig(RCC_ClkInitTypeDef *cfg, uint32_t *latency) {
    cfg->APB1CLKDivider=RCC_HCLK_DIV2; *latency=0;
}
void HAL_RCCEx_GetPLL2ClockFreq(PLL2_ClocksTypeDef *clocks) { clocks->PLL2_Q_Frequency=64000000; }
void HAL_RCCEx_GetPLL3ClockFreq(PLL3_ClocksTypeDef *clocks) { clocks->PLL3_Q_Frequency=64000000; }
void *memmove(void *dst, const void *src, size_t n) {
    unsigned char *d=dst; const unsigned char *s=src;
    if (d<s) { for (size_t i=0;i<n;i++) d[i]=s[i]; }
    else { while (n) { n--; d[n]=s[n]; } }
    return dst;
}
int main(void) { return 0; }
