#include "remote_control.h"
#include <string.h>

volatile RC_State_t g_rc;
static uint8_t rx[20], rx_count, have_seq, last_seq, expired;
static uint16_t age_ms;

#ifndef RC_HOST_TEST
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
    rx_count=0; have_seq=0; last_seq=0; expired=1; age_ms=0;
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
    uint32_t key=lock();
    if (age_ms < 300) age_ms++;
    if (age_ms == 300 && !expired) { expired=1; neutral(); }
    unlock(key);
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
    uint32_t key=lock();
    if (expired && (flags&1u)) { unlock(key); return; } /* Recovery needs a disabled frame. */
    if (!expired && have_seq && (delta==0 || delta>=128)) { unlock(key); return; }
    have_seq=1; last_seq=seq; expired=0; age_ms=0;
    g_rc.sequence=seq; g_rc.link_valid=1; g_rc.valid_frames++;
    g_rc.stopped=(uint8_t)((flags&2u)!=0);
    g_rc.enabled=(uint8_t)((flags&1u)!=0 && !g_rc.stopped);
    for (i=0;i<4;i++) g_rc.axes[i]=g_rc.enabled ? signed_le16(p+4+2*i) : 0;
    for (i=0;i<2;i++) g_rc.sliders[i]=g_rc.enabled ? le16(p+12+2*i) : 0;
    unlock(key);
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
#ifdef RC_H723
#define RC_VECTOR_COUNT (16u + (unsigned)TIM24_IRQn + 1u)
static uint32_t vectors[RC_VECTOR_COUNT] __ALIGNED(1024);
#else
#define RC_VECTOR_COUNT (16u + (unsigned)USBWakeUp_IRQn + 1u)
static uint32_t vectors[RC_VECTOR_COUNT] __ALIGNED(256);
#endif
static uint32_t initialized_baud;
static void uart_irq(void);
static void timer_irq(void);

/* GetPeriphCLKFreq does not support USART in this HAL; preserve the shared mux. */
static uint32_t uart_kernel_clock(void) {
    switch (__HAL_RCC_GET_USART234578_SOURCE()) {
    case RCC_USART234578CLKSOURCE_PCLK1:
        return HAL_RCC_GetPCLK1Freq();
    case RCC_USART234578CLKSOURCE_PLL2: {
        PLL2_ClocksTypeDef clocks;
        if (!(RCC->CR & RCC_CR_PLL2RDY) || !(RCC->PLLCFGR & RCC_PLLCFGR_DIVQ2EN)) return 0;
        HAL_RCCEx_GetPLL2ClockFreq(&clocks);
        return clocks.PLL2_Q_Frequency;
    }
    case RCC_USART234578CLKSOURCE_PLL3: {
        PLL3_ClocksTypeDef clocks;
        if (!(RCC->CR & RCC_CR_PLL3RDY) || !(RCC->PLLCFGR & RCC_PLLCFGR_DIVQ3EN)) return 0;
        HAL_RCCEx_GetPLL3ClockFreq(&clocks);
        return clocks.PLL3_Q_Frequency;
    }
    case RCC_USART234578CLKSOURCE_HSI:
        return (RCC->CR & RCC_CR_HSIRDY) ? HSI_VALUE >> ((RCC->CR & RCC_CR_HSIDIV) >> RCC_CR_HSIDIV_Pos) : 0;
    case RCC_USART234578CLKSOURCE_CSI:
        return (RCC->CR & RCC_CR_CSIRDY) ? CSI_VALUE : 0;
    case RCC_USART234578CLKSOURCE_LSE:
        return (RCC->BDCR & RCC_BDCR_LSERDY) ? LSE_VALUE : 0;
    default:
        return 0;
    }
}
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
static int occupied(void) {
    if (RCC->APB1LENR & RCC_APB1LENR_USART3EN) return 1;
#ifdef RC_H723
    if (RCC->APB1LENR & RCC_APB1LENR_TIM5EN) return 1;
#else
    if (RCC->APB1ENR & RCC_APB1ENR_TIM4EN) return 1;
    /* A remapped USART3 cannot use PA9/PA10 without changing shared AFIO state. */
    if ((RCC->APB2ENR & RCC_APB2ENR_AFIOEN) && (AFIO->MAPR & AFIO_MAPR_USART1_REMAP)) return 1;
#endif
    return NVIC_GetEnableIRQ(USART3_IRQn) || NVIC_GetEnableIRQ(TIM5_IRQn);
}
static void install_vectors(void) {
    const uint32_t *original=(const uint32_t *)(uintptr_t)SCB->VTOR;
    unsigned i;
    for (i=0;i<RC_VECTOR_COUNT;i++) vectors[i]=original[i];
    vectors[16u+USART3_IRQn]=(uint32_t)(uintptr_t)uart_irq;
    vectors[16u+TIM5_IRQn]=(uint32_t)(uintptr_t)timer_irq;
#ifdef RC_H723
    if (SCB->CCR & SCB_CCR_DC_Msk) SCB_CleanDCache_by_Addr(vectors,(int32_t)sizeof(vectors));
#endif
    __DSB();
    SCB->VTOR=(uint32_t)(uintptr_t)vectors;
    __DSB(); __ISB();
}
HAL_StatusTypeDef RC_InitBaud(uint32_t baud) {
    uint32_t clock, uart_clock, divisor, period, brr, key;
    HAL_StatusTypeDef result;
#ifdef RC_F103
    uint32_t afio_enabled;
#endif
    if (baud==0) return HAL_ERROR;
    key=lock();
    if (initialized_baud) {
        result=baud==initialized_baud ? HAL_OK : HAL_BUSY;
        unlock(key); return result;
    }
    if (occupied()) { unlock(key); return HAL_BUSY; }
#ifdef RC_H723
    uart_clock=uart_kernel_clock();
#else
    uart_clock=HAL_RCC_GetPCLK2Freq();
#endif
    clock=timer_clock();
    brr=(uint32_t)(((uint64_t)uart_clock+baud/2u)/baud);
    divisor=(clock+999999u)/1000000u;
    if (brr<16u || brr>65535u || divisor==0 || divisor>65536u) { unlock(key); return HAL_ERROR; }
    period=(clock+divisor*500u)/(divisor*1000u);
    if (period==0 || period>65536u) { unlock(key); return HAL_ERROR; }

#ifdef RC_F103
    /* Inspect the retained remap with AFIO clocked; never rewrite MAPR/SWJ_CFG. */
    afio_enabled=RCC->APB2ENR & RCC_APB2ENR_AFIOEN;
    __HAL_RCC_AFIO_CLK_ENABLE();
    if (AFIO->MAPR & AFIO_MAPR_USART1_REMAP) {
        if (!afio_enabled) CLEAR_BIT(RCC->APB2ENR,RCC_APB2ENR_AFIOEN);
        unlock(key); return HAL_BUSY;
    }
#endif
    __HAL_RCC_GPIOD_CLK_ENABLE(); __HAL_RCC_USART3_CLK_ENABLE(); __HAL_RCC_TIM5_CLK_ENABLE();
#ifdef RC_H723
    MODIFY_REG(GPIOD->OTYPER,(1u<<8)|(1u<<9),0);
    MODIFY_REG(GPIOD->OSPEEDR,15u<<16,0);
    MODIFY_REG(GPIOD->PUPDR,15u<<16,4u<<16); /* RX pull-up, TX no pull. */
    MODIFY_REG(GPIOD->AFR[1],0xffu,0x77u); /* AF7 on PD8/PD9. */
    MODIFY_REG(GPIOD->MODER,15u<<16,10u<<16);
#else
    MODIFY_REG(GPIOA->CRH,0xff0u,0x8b0u); /* PA9 AF push-pull; PA10 input pull-up. */
    GPIOA->BSRR=1u<<10;
#endif
    USART3->CR1=0; USART3->CR2=0; USART3->CR3=0;
#ifdef RC_H723
    USART3->PRESC=0;
    USART3->ICR=USART_ICR_ORECF|USART_ICR_FECF|USART_ICR_NECF|USART_ICR_PECF;
    USART3->RQR=USART_RQR_RXFRQ;
#else
    (void)USART3->SR; (void)USART3->DR;
#endif
    USART3->BRR=brr;
    TIM5->CR1=0; TIM5->CR2=0; TIM5->SMCR=0; TIM5->DIER=0; TIM5->CCER=0;
    TIM5->PSC=divisor-1u; TIM5->ARR=period-1u;
    TIM5->EGR=TIM_EGR_UG; TIM5->SR=0; TIM5->CNT=0;
    RC_Reset();
    install_vectors();
    NVIC_ClearPendingIRQ(USART3_IRQn); NVIC_ClearPendingIRQ(TIM5_IRQn);
    NVIC_SetPriority(USART3_IRQn,NVIC_EncodePriority(NVIC_GetPriorityGrouping(),5,0));
    NVIC_SetPriority(TIM5_IRQn,NVIC_EncodePriority(NVIC_GetPriorityGrouping(),5,0));
#ifdef RC_H723
    USART3->CR1=USART_CR1_UE|USART_CR1_TE|USART_CR1_RE|USART_CR1_RXNEIE_RXFNEIE;
#else
    USART3->CR1=USART_CR1_UE|USART_CR1_TE|USART_CR1_RE|USART_CR1_RXNEIE;
#endif
    TIM5->DIER=TIM_DIER_UIE; TIM5->CR1=TIM_CR1_CEN;
    initialized_baud=baud;
    NVIC_EnableIRQ(USART3_IRQn); NVIC_EnableIRQ(TIM5_IRQn);
    unlock(key);
    return HAL_OK;
}
HAL_StatusTypeDef RC_Init(void) { return RC_InitBaud(9600); }
HAL_StatusTypeDef RC_InitBT630(void) { return RC_InitBaud(115200); }
static void uart_irq(void) {
#ifdef RC_H723
    uint32_t isr=USART3->ISR;
    if (isr & USART_ISR_RXNE_RXFNE) {
        uint8_t data=(uint8_t)USART3->RDR;
        if (!(isr & (USART_ISR_ORE|USART_ISR_FE|USART_ISR_NE|USART_ISR_PE))) RC_ProcessByte(data);
    }
    if (isr & (USART_ISR_ORE|USART_ISR_FE|USART_ISR_NE|USART_ISR_PE)) USART3->ICR=USART_ICR_ORECF|USART_ICR_FECF|USART_ICR_NECF|USART_ICR_PECF;
#else
    uint32_t sr=USART3->SR;
    if (sr & (USART_SR_RXNE|USART_SR_ORE|USART_SR_FE|USART_SR_NE|USART_SR_PE)) {
        uint8_t data=(uint8_t)USART3->DR;
        if ((sr & USART_SR_RXNE) && !(sr & (USART_SR_ORE|USART_SR_FE|USART_SR_NE|USART_SR_PE))) RC_ProcessByte(data);
    }
#endif
}
static void timer_irq(void) {
    if ((TIM5->SR & TIM_SR_UIF) && (TIM5->DIER & TIM_DIER_UIE)) {
        TIM5->SR=~(uint32_t)TIM_SR_UIF;
        RC_Tick1ms();
    }
}
#endif
