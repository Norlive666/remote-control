#include "remote_control.h"
#include <string.h>

volatile RC_State_t g_rc;
static uint8_t rx[20], rx_count, have_seq, last_seq, expired;
static uint16_t age_ms;

#ifndef RC_HOST_TEST
static UART_HandleTypeDef uart;
static TIM_HandleTypeDef timer;
static uint32_t lock(void) { uint32_t key=__get_PRIMASK(); __disable_irq(); return key; }
static void unlock(uint32_t key) { __set_PRIMASK(key); }
#else
static uint32_t lock(void) { return 0; }
static void unlock(uint32_t key) { (void)key; }
#endif

static void neutral(void) {
    unsigned i;
    for (i=0;i<4;i++) g_rc.axes[i]=0;
    for (i=0;i<2;i++) g_rc.sliders[i]=0;
    g_rc.enabled=0;
    g_rc.stopped=1;
    g_rc.link_valid=0;
}
void RC_Reset(void) {
    uint32_t key=lock();
    rx_count=0; have_seq=0; last_seq=0; expired=0; age_ms=0;
    neutral(); g_rc.sequence=0; g_rc.valid_frames=0; g_rc.crc_errors=0;
    unlock(key);
}
void RC_GetState(RC_State_t *out) {
    uint32_t key=lock();
    unsigned i;
    for (i=0;i<4;i++) out->axes[i]=g_rc.axes[i];
    for (i=0;i<2;i++) out->sliders[i]=g_rc.sliders[i];
    out->enabled=g_rc.enabled; out->stopped=g_rc.stopped; out->sequence=g_rc.sequence;
    out->link_valid=g_rc.link_valid; out->valid_frames=g_rc.valid_frames; out->crc_errors=g_rc.crc_errors;
    unlock(key);
}
void RC_Tick1ms(void) {
    if (age_ms < 300) age_ms++;
    if (age_ms == 300 && !expired) { expired=1; neutral(); }
}
static uint16_t le16(const uint8_t *p) { return (uint16_t)((uint16_t)p[0] | ((uint16_t)p[1]<<8)); }
static int16_t signed_le16(const uint8_t *p) {
    uint16_t raw=le16(p);
    return (int16_t)(raw<=32767u ? (int32_t)raw : (int32_t)raw-65536);
}
static uint16_t crc16(const uint8_t *p) {
    uint16_t crc=0xffff;
    unsigned i, bit;
    for (i=2;i<18;i++) {
        crc ^= (uint16_t)p[i]<<8;
        for (bit=0;bit<8;bit++) crc=(uint16_t)((crc&0x8000u) ? (crc<<1)^0x1021u : (uint32_t)crc<<1);
    }
    return crc;
}
static int valid(const uint8_t *p) {
    unsigned i;
    int inactive;
    if (p[0]!=0xa5 || p[1]!=0x5a || p[2]!=1 || (p[16]&~3u)!=0 || p[17]!=0) return 0;
    inactive=((p[16]&1u)==0 || (p[16]&2u)!=0);
    for (i=0;i<4;i++) { int16_t v=signed_le16(p+4+2*i); if (v < -1000 || v > 1000 || (inactive && v!=0)) return 0; }
    for (i=0;i<2;i++) { uint16_t v=le16(p+12+2*i); if (v>1000 || (inactive && v!=0)) return 0; }
    return 1;
}
static void publish(const uint8_t *p) {
    uint8_t seq=p[3], flags=p[16], delta=(uint8_t)(seq-last_seq);
    unsigned i;
    if (expired && (flags&1u)) return; /* Recovery must begin with an explicit disabled frame. */
    if (!expired && have_seq && (delta==0 || delta>=128)) return;
    have_seq=1; last_seq=seq; expired=0; age_ms=0;
    g_rc.sequence=seq; g_rc.link_valid=1; g_rc.valid_frames++;
    g_rc.stopped=(uint8_t)((flags&2u)!=0);
    g_rc.enabled=(uint8_t)((flags&1u)!=0 && !g_rc.stopped);
    for (i=0;i<4;i++) g_rc.axes[i]=g_rc.enabled ? signed_le16(p+4+2*i) : 0;
    for (i=0;i<2;i++) g_rc.sliders[i]=g_rc.enabled ? le16(p+12+2*i) : 0;
}
void RC_ProcessByte(uint8_t byte) {
    unsigned i;
    if (rx_count==0) { if (byte==0xa5) { rx[0]=byte; rx_count=1; } return; }
    if (rx_count==1) { if (byte==0x5a) {rx[1]=byte; rx_count=2;} else if (byte!=0xa5) rx_count=0; return; }
    rx[rx_count++]=byte;
    if (rx_count<20) return;
    if (le16(rx+18)!=crc16(rx)) g_rc.crc_errors++;
    else if (valid(rx)) { publish(rx); rx_count=0; return; }
    for (i=1;i<19;i++) if (rx[i]==0xa5 && rx[i+1]==0x5a) break;
    if (i<19) { rx_count=(uint8_t)(20-i); memmove(rx,rx+i,rx_count); }
    else if (rx[19]==0xa5) { rx[0]=0xa5; rx_count=1; }
    else rx_count=0;
}

#ifndef RC_HOST_TEST
static uint32_t timer_clock(void) {
    RCC_ClkInitTypeDef cfg;
    uint32_t latency, pclk;
    HAL_RCC_GetClockConfig(&cfg,&latency);
    pclk=HAL_RCC_GetPCLK1Freq();
#ifdef RC_H723
    if ((RCC->CFGR & RCC_CFGR_TIMPRE)==0) return pclk * (cfg.APB1CLKDivider==RCC_HCLK_DIV1 ? 1u : 2u);
    if (cfg.APB1CLKDivider==RCC_HCLK_DIV1) return pclk;
    if (cfg.APB1CLKDivider==RCC_HCLK_DIV2) return pclk*2u;
    return pclk*4u;
#else
    return pclk * (cfg.APB1CLKDivider==RCC_HCLK_DIV1 ? 1u : 2u);
#endif
}
HAL_StatusTypeDef RC_InitBaud(uint32_t baud) {
    GPIO_InitTypeDef gpio={0};
    uint32_t clock, divisor, period;
    if (baud==0) return HAL_ERROR;
    RC_Reset();
    __HAL_RCC_GPIOA_CLK_ENABLE(); __HAL_RCC_USART1_CLK_ENABLE(); __HAL_RCC_TIM4_CLK_ENABLE();
    gpio.Pin=GPIO_PIN_9;
#ifdef RC_H723
    gpio.Mode=GPIO_MODE_AF_PP; gpio.Pull=GPIO_NOPULL; gpio.Speed=GPIO_SPEED_FREQ_LOW; gpio.Alternate=GPIO_AF7_USART1;
#else
    gpio.Mode=GPIO_MODE_AF_PP; gpio.Speed=GPIO_SPEED_FREQ_HIGH;
#endif
    HAL_GPIO_Init(GPIOA,&gpio);
    gpio.Pin=GPIO_PIN_10;
#ifdef RC_H723
    gpio.Mode=GPIO_MODE_AF_PP; gpio.Pull=GPIO_PULLUP;
#else
    gpio.Mode=GPIO_MODE_INPUT; gpio.Pull=GPIO_PULLUP;
#endif
    HAL_GPIO_Init(GPIOA,&gpio);
    uart.Instance=USART1; uart.Init.BaudRate=baud; uart.Init.WordLength=UART_WORDLENGTH_8B;
    uart.Init.StopBits=UART_STOPBITS_1; uart.Init.Parity=UART_PARITY_NONE;
    uart.Init.Mode=UART_MODE_TX_RX; uart.Init.HwFlowCtl=UART_HWCONTROL_NONE;
    uart.Init.OverSampling=UART_OVERSAMPLING_16;
#ifdef RC_H723
    uart.Init.OneBitSampling=UART_ONE_BIT_SAMPLE_DISABLE;
    uart.Init.ClockPrescaler=UART_PRESCALER_DIV1;
    uart.AdvancedInit.AdvFeatureInit=UART_ADVFEATURE_NO_INIT;
#endif
    if (HAL_UART_Init(&uart)!=HAL_OK) return HAL_ERROR;
    clock=timer_clock();
    divisor=(clock+999999u)/1000000u;
    if (divisor==0 || divisor>65536u) return HAL_ERROR;
    period=(clock+divisor*500u)/(divisor*1000u);
    if (period==0 || period>65536u) return HAL_ERROR;
    timer.Instance=TIM4; timer.Init.Prescaler=divisor-1u; timer.Init.Period=period-1u;
    timer.Init.CounterMode=TIM_COUNTERMODE_UP; timer.Init.ClockDivision=TIM_CLOCKDIVISION_DIV1;
#ifdef RC_H723
    timer.Init.AutoReloadPreload=TIM_AUTORELOAD_PRELOAD_DISABLE;
#endif
    if (HAL_TIM_Base_Init(&timer)!=HAL_OK) return HAL_ERROR;
    __HAL_TIM_CLEAR_FLAG(&timer,TIM_FLAG_UPDATE);
    __HAL_TIM_ENABLE_IT(&timer,TIM_IT_UPDATE);
#ifdef RC_H723
    SET_BIT(USART1->CR1,USART_CR1_RXNEIE_RXFNEIE);
#else
    SET_BIT(USART1->CR1,USART_CR1_RXNEIE);
#endif
    HAL_NVIC_SetPriority(USART1_IRQn,5,0); HAL_NVIC_EnableIRQ(USART1_IRQn);
    HAL_NVIC_SetPriority(TIM4_IRQn,5,0); HAL_NVIC_EnableIRQ(TIM4_IRQn);
    return HAL_TIM_Base_Start(&timer);
}
HAL_StatusTypeDef RC_Init(void) { return RC_InitBaud(9600); }
HAL_StatusTypeDef RC_InitBT630(void) { return RC_InitBaud(115200); }
void USART1_IRQHandler(void) {
#ifdef RC_H723
    uint32_t isr=USART1->ISR;
    if (isr & USART_ISR_RXNE_RXFNE) {
        uint8_t data=(uint8_t)USART1->RDR;
        if (!(isr & (USART_ISR_ORE|USART_ISR_FE|USART_ISR_NE|USART_ISR_PE))) RC_ProcessByte(data);
    }
    if (isr & (USART_ISR_ORE|USART_ISR_FE|USART_ISR_NE|USART_ISR_PE)) USART1->ICR=USART_ICR_ORECF|USART_ICR_FECF|USART_ICR_NECF|USART_ICR_PECF;
#else
    uint32_t sr=USART1->SR;
    if (sr & (USART_SR_RXNE|USART_SR_ORE|USART_SR_FE|USART_SR_NE|USART_SR_PE)) {
        uint8_t data=(uint8_t)USART1->DR;
        if ((sr & USART_SR_RXNE) && !(sr & (USART_SR_ORE|USART_SR_FE|USART_SR_NE|USART_SR_PE))) RC_ProcessByte(data);
    }
#endif
}
void TIM4_IRQHandler(void) {
    if (__HAL_TIM_GET_FLAG(&timer,TIM_FLAG_UPDATE) && __HAL_TIM_GET_IT_SOURCE(&timer,TIM_IT_UPDATE)) {
        __HAL_TIM_CLEAR_IT(&timer,TIM_IT_UPDATE);
        RC_Tick1ms();
    }
}
#endif
