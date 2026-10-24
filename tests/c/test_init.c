#define _GNU_SOURCE
#include "remote_control.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>
#include <sys/mman.h>

#ifdef RC_H723
#define TEST_UART USART3
#define TEST_TIMER TIM5
#define TEST_GPIO GPIOD
#define TEST_UART_IRQn USART3_IRQn
#define TEST_TIMER_IRQn TIM5_IRQn
#define TEST_UART_ENR RCC->APB1LENR
#define TEST_UART_EN RCC_APB1LENR_USART3EN
#else
#define TEST_UART USART1
#define TEST_TIMER TIM4
#define TEST_GPIO GPIOA
#define TEST_UART_IRQn USART1_IRQn
#define TEST_TIMER_IRQn TIM4_IRQn
#define TEST_UART_ENR RCC->APB2ENR
#define TEST_UART_EN RCC_APB2ENR_USART1EN
#endif

SCB_Type test_scb;
uint32_t test_primask, test_enabled[163], test_priority[163], test_cache_cleans;
static uint32_t vectors[179] __ALIGNED(1024);
static uint32_t pclk1=36000000, pclk2=72000000, uart_clock=64000000, divider=RCC_HCLK_DIV2;
#ifdef RC_H723
void HAL_RCCEx_GetPLL2ClockFreq(PLL2_ClocksTypeDef *clocks) { clocks->PLL2_Q_Frequency=uart_clock; }
void HAL_RCCEx_GetPLL3ClockFreq(PLL3_ClocksTypeDef *clocks) { clocks->PLL3_Q_Frequency=uart_clock; }
#endif
uint32_t HAL_RCC_GetPCLK1Freq(void) { return pclk1; }
uint32_t HAL_RCC_GetPCLK2Freq(void) { return pclk2; }
void HAL_RCC_GetClockConfig(RCC_ClkInitTypeDef *cfg, uint32_t *latency) {
    cfg->APB1CLKDivider=divider; *latency=0;
}
uint32_t HAL_RCCEx_GetPeriphCLKFreq(uint64_t peripheral) {
#ifdef RC_H723
    assert(peripheral==RCC_PERIPHCLK_USART3);
    /* The project HAL does not support querying USART clocks here. */
    return 0;
#else
    (void)peripheral;
    return uart_clock;
#endif
}
/* Strong generated handlers must coexist, but successful Init installs private handlers. */
#ifdef RC_H723
void USART3_IRQHandler(void) { assert(0 && "generated UART3 handler used"); }
void TIM5_IRQHandler(void) { assert(0 && "generated timer5 handler used"); }
#endif
void USART1_IRQHandler(void) { assert(0 && "generated UART handler used"); }
void TIM4_IRQHandler(void) { assert(0 && "generated timer handler used"); }
static void interrupt(IRQn_Type irq) {
    uint32_t *table=(uint32_t *)(uintptr_t)SCB->VTOR;
    ((void (*)(void))(uintptr_t)table[16+irq])();
}
static void byte(uint8_t value) {
#ifdef RC_H723
    TEST_UART->ISR=USART_ISR_RXNE_RXFNE; TEST_UART->RDR=value;
#else
    TEST_UART->SR=USART_SR_RXNE; TEST_UART->DR=value;
#endif
    interrupt(TEST_UART_IRQn);
}
static void unchanged(uint32_t vtor) {
    assert(SCB->VTOR==vtor && g_rc.valid_frames==123 && test_primask==1);
    assert(TEST_GPIO->ODR==0x1234 && TEST_TIMER->PSC==0x1234 && TEST_UART->BRR==0x1234);
}
static void uart_busy(uint32_t original) {
    uint32_t cr1=TEST_UART->CR1, cr2=TEST_UART->CR2, cr3=TEST_UART->CR3;
    uint32_t irq=test_enabled[TEST_UART_IRQn];
    assert(RC_Init()==HAL_BUSY); unchanged(original);
    assert(TEST_UART->CR1==cr1 && TEST_UART->CR2==cr2 && TEST_UART->CR3==cr3);
    assert(test_enabled[TEST_UART_IRQn]==irq && TEST_UART_ENR==TEST_UART_EN);
}
int main(int argc, char **argv) {
    assert(argc==2);
    assert(mmap((void *)0x40000000,0x20000000,PROT_READ|PROT_WRITE,
                MAP_PRIVATE|MAP_ANONYMOUS|MAP_FIXED,-1,0)==(void *)0x40000000);
    for (unsigned i=0;i<179;i++) vectors[i]=0x08000001+4*i;
    SCB->VTOR=(uint32_t)(uintptr_t)vectors;
    uint32_t original=SCB->VTOR;
    test_primask=1; g_rc.valid_frames=123;
    TEST_GPIO->ODR=TEST_TIMER->PSC=TEST_UART->BRR=0x1234;
    if (!strcmp(argv[1],"busy-uart")) {
        TEST_UART_ENR=TEST_UART_EN;
        TEST_UART->CR1=USART_CR1_IDLEIE;
        uart_busy(original);
    } else if (!strcmp(argv[1],"busy-dma-rx") || !strcmp(argv[1],"busy-dma-tx")) {
        TEST_UART_ENR=TEST_UART_EN;
        TEST_UART->CR3=!strcmp(argv[1],"busy-dma-rx") ? USART_CR3_DMAR : USART_CR3_DMAT;
        uart_busy(original);
    } else if (!strcmp(argv[1],"busy-rx-ie")) {
        TEST_UART_ENR=TEST_UART_EN;
#ifdef RC_H723
        TEST_UART->CR1=USART_CR1_RXNEIE_RXFNEIE;
#else
        TEST_UART->CR1=USART_CR1_RXNEIE;
#endif
        uart_busy(original);
    } else if (!strcmp(argv[1],"busy-tc-ie") || !strcmp(argv[1],"busy-parity-ie")) {
        TEST_UART_ENR=TEST_UART_EN;
        TEST_UART->CR1=!strcmp(argv[1],"busy-tc-ie") ? USART_CR1_TCIE : USART_CR1_PEIE;
        uart_busy(original);
    } else if (!strcmp(argv[1],"busy-txe-ie") || !strcmp(argv[1],"busy-lin-ie")) {
        TEST_UART_ENR=TEST_UART_EN;
        if (!strcmp(argv[1],"busy-lin-ie")) TEST_UART->CR2=USART_CR2_LBDIE;
#ifdef RC_H723
        else TEST_UART->CR1=USART_CR1_TXEIE_TXFNFIE;
#else
        else TEST_UART->CR1=USART_CR1_TXEIE;
#endif
        uart_busy(original);
    } else if (!strcmp(argv[1],"busy-error-ie") || !strcmp(argv[1],"busy-cts-ie")) {
        TEST_UART_ENR=TEST_UART_EN;
        TEST_UART->CR3=!strcmp(argv[1],"busy-error-ie") ? USART_CR3_EIE : USART_CR3_CTSIE;
        uart_busy(original);
    } else if (!strcmp(argv[1],"busy-tx-hardware")) {
        TEST_UART_ENR=TEST_UART_EN;
        TEST_UART->CR1=USART_CR1_UE|USART_CR1_TE;
        uart_busy(original);
#ifdef RC_H723
    } else if (!strcmp(argv[1],"busy-rto-ie") || !strcmp(argv[1],"busy-fifo-ie")) {
        TEST_UART_ENR=TEST_UART_EN;
        if (!strcmp(argv[1],"busy-rto-ie")) TEST_UART->CR1=USART_CR1_RTOIE;
        else TEST_UART->CR3=USART_CR3_RXFTIE;
        uart_busy(original);
#endif
    } else if (!strcmp(argv[1],"busy-timer")) {
#ifdef RC_H723
        RCC->APB1LENR=RCC_APB1LENR_TIM5EN;
#else
        RCC->APB1ENR=RCC_APB1ENR_TIM4EN;
#endif
        assert(RC_Init()==HAL_BUSY); unchanged(original);
        assert(RCC->APB2ENR==0);
    } else if (!strcmp(argv[1],"busy-uart-irq")) {
        test_enabled[TEST_UART_IRQn]=1;
        assert(RC_Init()==HAL_BUSY); unchanged(original);
        assert(RCC->APB2ENR==0);
    } else if (!strcmp(argv[1],"busy-irq")) {
        test_enabled[TEST_TIMER_IRQn]=1;
        assert(RC_Init()==HAL_BUSY); unchanged(original);
        assert(RCC->APB2ENR==0);
    #ifdef RC_F103
    } else if (!strcmp(argv[1],"busy-remap")) {
        AFIO->MAPR=AFIO_MAPR_USART1_REMAP | AFIO_MAPR_SWJ_CFG_JTAGDISABLE;
        assert(RC_Init()==HAL_BUSY); unchanged(original);
        assert(RCC->APB2ENR==0);
        assert(AFIO->MAPR==(AFIO_MAPR_USART1_REMAP | AFIO_MAPR_SWJ_CFG_JTAGDISABLE));
#endif
    } else if (!strcmp(argv[1],"invalid-baud")) {
        assert(RC_InitBaud(0)==HAL_ERROR); unchanged(original);
        assert(RC_InitBaud(UINT32_MAX)==HAL_ERROR); unchanged(original);
        assert(RC_InitBaud(1)==HAL_ERROR); unchanged(original);
        assert(RCC->APB2ENR==0);
    } else if (!strcmp(argv[1],"invalid-clock")) {
        pclk1=0;
        assert(RC_Init()==HAL_ERROR); unchanged(original);
        assert(RCC->APB2ENR==0);
    } else if (!strcmp(argv[1],"invalid-uart-clock")) {
#ifdef RC_H723
        RCC->D2CCIP2R=RCC_USART234578CLKSOURCE_HSI; /* Not ready. */
#else
        uart_clock=pclk2=0;
#endif
        assert(RC_Init()==HAL_ERROR); unchanged(original);
        assert(RCC->APB2ENR==0);
#ifdef RC_H723
    } else if (!strcmp(argv[1],"pll2-off") || !strcmp(argv[1],"pll2-no-q")) {
        RCC->D2CCIP2R=RCC_USART234578CLKSOURCE_PLL2;
        RCC->CR=strcmp(argv[1],"pll2-off") ? RCC_CR_PLL2RDY : 0;
        RCC->PLLCFGR=strcmp(argv[1],"pll2-no-q") ? RCC_PLLCFGR_DIVQ2EN : 0;
        assert(RC_Init()==HAL_ERROR); unchanged(original);
        assert(RCC->APB1LENR==0);
#endif
    } else if (!strcmp(argv[1],"default-baud")) {
        assert(RC_Init()==HAL_OK);
#ifdef RC_H723
#if RC_DEFAULT_BAUD == 9600U
        assert(TEST_UART->BRR==3750); /* 36 MHz / 9600. */
#else
        assert(TEST_UART->BRR==313); /* 36 MHz / 115200, rounded. */
#endif
#else
#if RC_DEFAULT_BAUD == 9600U
        assert(TEST_UART->BRR==7500); /* 72 MHz / 9600. */
#else
        assert(TEST_UART->BRR==625); /* 72 MHz / 115200. */
#endif
#endif
    } else {
        uint32_t expected_primask=strcmp(argv[1],"unmasked") ? 1u : 0u;
        test_primask=expected_primask;
        test_enabled[5]=1; test_priority[5]=12;
        uint32_t expected_psc=71;
        if (!strcmp(argv[1],"apb1")) { divider=RCC_HCLK_DIV1; expected_psc=35; }
#ifdef RC_H723
        if (!strcmp(argv[1],"timpre4")) { RCC->CFGR=RCC_CFGR_TIMPRE; divider=RCC_HCLK_DIV4; pclk1=60000000; expected_psc=239; }
        if (!strcmp(argv[1],"timpre8")) { RCC->CFGR=RCC_CFGR_TIMPRE; divider=RCC_HCLK_DIV8; pclk1=30000000; expected_psc=119; }
        uint32_t expected_uart_clock=pclk1;
        if (!strcmp(argv[1],"hsi-div4")) {
            RCC->D2CCIP2R=RCC_USART234578CLKSOURCE_HSI;
            RCC->CR=RCC_CR_HSIRDY | (2u<<RCC_CR_HSIDIV_Pos);
            expected_uart_clock=16000000;
        }
        if (!strcmp(argv[1],"csi")) {
            RCC->D2CCIP2R=RCC_USART234578CLKSOURCE_CSI; RCC->CR=RCC_CR_CSIRDY;
            expected_uart_clock=4000000;
        }
        if (!strcmp(argv[1],"pll2")) {
            RCC->D2CCIP2R=RCC_USART234578CLKSOURCE_PLL2; RCC->CR=RCC_CR_PLL2RDY;
            RCC->PLLCFGR=RCC_PLLCFGR_DIVQ2EN; expected_uart_clock=uart_clock;
        }
        if (!strcmp(argv[1],"pll3")) {
            RCC->D2CCIP2R=RCC_USART234578CLKSOURCE_PLL3; RCC->CR=RCC_CR_PLL3RDY;
            RCC->PLLCFGR=RCC_PLLCFGR_DIVQ3EN; expected_uart_clock=uart_clock;
        }
        if (!strcmp(argv[1],"lse")) {
            RCC->D2CCIP2R=RCC_USART234578CLKSOURCE_LSE; RCC->BDCR=RCC_BDCR_LSERDY;
            expected_uart_clock=32768;
        }
        uint32_t source=RCC->D2CCIP2R;
#endif
#ifdef RC_H723
        if (!strcmp(argv[1],"old-peripherals")) {
            RCC->APB2ENR=RCC_APB2ENR_USART1EN;
            RCC->APB1LENR=RCC_APB1LENR_TIM4EN;
            USART1->BRR=0x1234; TIM4->PSC=0x5678; GPIOA->MODER=0x12345678;
            test_enabled[USART1_IRQn]=test_enabled[TIM4_IRQn]=1;
        }
#endif
        int bt630=!strcmp(argv[1],"bt630");
        uint32_t baud=bt630 ? 115200 : 9600;
#ifdef RC_H723
        if (!strcmp(argv[1],"lse")) baud=1200;
#endif
        int cache=!strcmp(argv[1],"cache");
        if (cache) SCB->CCR=SCB_CCR_DC_Msk;
        if (!strcmp(argv[1],"reuse-uart") || !strcmp(argv[1],"reuse-uart-irq")) {
            TEST_UART_ENR |= TEST_UART_EN;
            TEST_UART->CR1=USART_CR1_UE|USART_CR1_TE|USART_CR1_RE|USART_CR1_PCE;
            TEST_UART->CR2=USART_CR2_STOP_1;
#ifdef RC_H723
            TEST_UART->ISR=USART_ISR_TC;
#else
            TEST_UART->SR=USART_SR_TC;
#endif
            test_enabled[TEST_UART_IRQn]=!strcmp(argv[1],"reuse-uart-irq");
        }
        TEST_GPIO->ODR=0xffff;
#ifdef RC_H723
        TEST_GPIO->MODER=TEST_GPIO->PUPDR=TEST_GPIO->AFR[1]=0xffffffff;
#else
        TEST_GPIO->CRH=0xffffffff;
#endif
        assert((bt630?RC_InitBT630():RC_InitBaud(baud))==HAL_OK);
        assert(test_primask==expected_primask && g_rc.valid_frames==0 && !g_rc.link_valid);
        assert(SCB->VTOR!=original);
        uint32_t *installed=(uint32_t *)(uintptr_t)SCB->VTOR;
#ifdef RC_H723
        assert((SCB->VTOR%1024)==0);
        assert(TEST_UART->BRR==(expected_uart_clock+baud/2u)/baud);
#if defined(RC_RX_INVERT) && RC_RX_INVERT
        assert(TEST_UART->CR2==USART_CR2_RXINV);
#else
        assert(TEST_UART->CR2==0);
#endif
        assert(RCC->D2CCIP2R==source); /* Shared kernel clock selection is preserved. */
        assert((TEST_GPIO->MODER & (15u<<16))==(10u<<16));
        assert((TEST_GPIO->AFR[1] & 0xff)==0x77);
        assert((TEST_GPIO->PUPDR & (15u<<16))==(4u<<16));
        assert(test_cache_cleans==(uint32_t)cache);
        assert(RCC->AHB4ENR & RCC_AHB4ENR_GPIODEN);
        assert((RCC->APB1LENR & (RCC_APB1LENR_USART3EN|RCC_APB1LENR_TIM5EN))==(RCC_APB1LENR_USART3EN|RCC_APB1LENR_TIM5EN));
        if (!strcmp(argv[1],"old-peripherals")) {
            assert(USART1->BRR==0x1234 && TIM4->PSC==0x5678 && GPIOA->MODER==0x12345678);
            assert(test_enabled[USART1_IRQn] && test_enabled[TIM4_IRQn]);
        }
        unsigned count=179;
#else
        assert((SCB->VTOR%256)==0);
        assert(TEST_UART->BRR==(bt630?625:7500));
        assert(TEST_UART->CR2==0);
        assert((TEST_GPIO->CRH & 0xff0)==0x8b0);
        assert(TEST_GPIO->ODR==0xffff);
        unsigned count=59;
#endif
        assert((TEST_UART->CR1 & USART_CR1_PCE)==0 && TEST_UART->CR3==0);
        for (unsigned i=0;i<count;i++)
            if (i!=16+TEST_UART_IRQn && i!=16+TEST_TIMER_IRQn) assert(installed[i]==vectors[i]);
        assert(test_enabled[5]==1 && test_priority[5]==12);
        assert(TEST_TIMER->PSC==expected_psc && TEST_TIMER->ARR==999 && (TEST_TIMER->CR1 & TIM_CR1_CEN));
        assert(test_enabled[TEST_UART_IRQn] && test_enabled[TEST_TIMER_IRQn]);
        g_rc.valid_frames=77;
        assert(RC_InitBaud(baud)==HAL_OK && g_rc.valid_frames==77);
        assert(RC_InitBaud(bt630?9600:115200)==HAL_BUSY && g_rc.valid_frames==77);
        /* Hand-checked CRC frame: disabled baseline then enabled axes. */
        const uint8_t disabled[20]={0xa5,0x5a,1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0x2e,0xc2};
        for (unsigned i=0;i<20;i++) byte(disabled[i]);
        assert(g_rc.link_valid && !g_rc.enabled && g_rc.valid_frames==78);
        const uint8_t enabled[20]={0xa5,0x5a,1,1,0xe8,3,0,0,0,0,0,0,0,0,0,0,1,0,0x23,0x9a};
        for (unsigned i=0;i<20;i++) byte(enabled[i]);
        assert(g_rc.enabled && g_rc.axes[0]==1000 && g_rc.valid_frames==79);
        TEST_TIMER->SR=0; interrupt(TEST_TIMER_IRQn); /* No update flag cannot age the link. */
        for (unsigned i=0;i<299;i++) { TEST_TIMER->SR=TIM_SR_UIF; interrupt(TEST_TIMER_IRQn); }
        assert(g_rc.link_valid);
        TEST_TIMER->SR=TIM_SR_UIF; interrupt(TEST_TIMER_IRQn);
        assert(!g_rc.link_valid && !g_rc.enabled && g_rc.stopped && g_rc.axes[0]==0 && g_rc.valid_frames==79);
    }
    printf("init %s: pass\n",argv[1]);
}
