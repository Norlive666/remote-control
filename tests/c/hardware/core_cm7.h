#ifndef TEST_CMSIS_CORE_H
#define TEST_CMSIS_CORE_H
#include <stdint.h>
#define __IO volatile
#define __I volatile const
#define __O volatile
#define __STATIC_INLINE static inline
#define __ALIGNED(n) __attribute__((aligned(n)))
typedef struct { uint32_t VTOR, CCR; } SCB_Type;
extern SCB_Type test_scb;
#define SCB (&test_scb)
#define SCB_CCR_DC_Msk (1u << 16)
extern uint32_t test_primask, test_enabled[163], test_priority[163], test_cache_cleans;
static inline uint32_t __get_PRIMASK(void) { return test_primask; }
static inline void __disable_irq(void) { test_primask=1; }
static inline void __set_PRIMASK(uint32_t key) { test_primask=key; }
static inline void __DSB(void) {}
static inline void __ISB(void) {}
static inline uint32_t NVIC_GetEnableIRQ(IRQn_Type irq) { return test_enabled[irq]; }
static inline void NVIC_EnableIRQ(IRQn_Type irq) { test_enabled[irq]=1; }
static inline void NVIC_ClearPendingIRQ(IRQn_Type irq) { (void)irq; }
static inline uint32_t NVIC_GetPriorityGrouping(void) { return 3; }
static inline uint32_t NVIC_EncodePriority(uint32_t group, uint32_t preempt, uint32_t sub) {
    (void)group; (void)sub; return preempt;
}
static inline void NVIC_SetPriority(IRQn_Type irq, uint32_t priority) { test_priority[irq]=priority; }
static inline void SCB_CleanDCache_by_Addr(void *address, int32_t size) {
    (void)address; (void)size; test_cache_cleans++;
}
#endif
