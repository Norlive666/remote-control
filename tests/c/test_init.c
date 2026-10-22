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
uint32_t HAL_RCC_GetPCLK1Freq(void) { return pclk1; }
uint32_t HAL_RCC_GetPCLK2Freq(void) { return pclk2; }
void HAL_RCC_GetClockConfig(RCC_ClkInitTypeDef *cfg, uint32_t *latency) {
    cfg->APB1CLKDivider=divider; *latency=0;
}
uint32_t HAL_RCCEx_GetPeriphCLKFreq(uint64_t peripheral) {
#ifdef RC_H723
    assert(peripheral==RCC_PERIPHCLK_USART3);
#else
    (void)peripheral;
#endif
    return uart_clock;
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
        assert(RC_Init()==HAL_BUSY); unchanged(original);
        assert(TEST_UART_ENR==TEST_UART_EN);
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
        uart_clock=pclk2=0;
        assert(RC_Init()==HAL_ERROR); unchanged(original);
        assert(RCC->APB2ENR==0);
    } else {
        uint32_t expected_primask=strcmp(argv[1],"unmasked") ? 1u : 0u;
        test_primask=expected_primask;
        test_enabled[5]=1; test_priority[5]=12;
        uint32_t expected_psc=71;
        if (!strcmp(argv[1],"apb1")) { divider=RCC_HCLK_DIV1; expected_psc=35; }
#ifdef RC_H723
        if (!strcmp(argv[1],"timpre4")) { RCC->CFGR=RCC_CFGR_TIMPRE; divider=RCC_HCLK_DIV4; pclk1=60000000; expected_psc=239; }
        if (!strcmp(argv[1],"timpre8")) { RCC->CFGR=RCC_CFGR_TIMPRE; divider=RCC_HCLK_DIV8; pclk1=30000000; expected_psc=119; }
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
        int cache=!strcmp(argv[1],"cache");
        if (cache) SCB->CCR=SCB_CCR_DC_Msk;
        TEST_GPIO->ODR=0xffff;
#ifdef RC_H723
        TEST_GPIO->MODER=TEST_GPIO->PUPDR=TEST_GPIO->AFR[1]=0xffffffff;
#else
        TEST_GPIO->CRH=0xffffffff;
#endif
        assert((bt630?RC_InitBT630():RC_Init())==HAL_OK);
        assert(test_primask==expected_primask && g_rc.valid_frames==0 && !g_rc.link_valid);
        assert(SCB->VTOR!=original);
        uint32_t *installed=(uint32_t *)(uintptr_t)SCB->VTOR;
#ifdef RC_H723
        assert((SCB->VTOR%1024)==0);
        assert(TEST_UART->BRR==(bt630?556:6667));
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
        assert((TEST_GPIO->CRH & 0xff0)==0x8b0);
        assert(TEST_GPIO->ODR==0xffff);
        unsigned count=59;
#endif
        for (unsigned i=0;i<count;i++)
            if (i!=16+TEST_UART_IRQn && i!=16+TEST_TIMER_IRQn) assert(installed[i]==vectors[i]);
        assert(test_enabled[5]==1 && test_priority[5]==12);
        assert(TEST_TIMER->PSC==expected_psc && TEST_TIMER->ARR==999 && (TEST_TIMER->CR1 & TIM_CR1_CEN));
        assert(test_enabled[TEST_UART_IRQn] && test_enabled[TEST_TIMER_IRQn]);
        g_rc.valid_frames=77;
        assert(RC_InitBaud(bt630?115200:9600)==HAL_OK && g_rc.valid_frames==77);
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
