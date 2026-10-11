#ifndef REMOTE_CONTROL_H
#define REMOTE_CONTROL_H
#include <stdint.h>
#ifndef RC_HOST_TEST
#include "stm32h7xx_hal.h"
#else
typedef int HAL_StatusTypeDef;
#endif
#define RC_H723 1

typedef struct {
    int16_t axes[4];
    uint16_t sliders[2];
    uint8_t enabled;
    uint8_t stopped;
    uint8_t sequence;
    uint8_t link_valid;
    uint32_t valid_frames;
    uint32_t crc_errors;
} RC_State_t;
extern volatile RC_State_t g_rc;
/* Call after HAL_Init/SystemClock_Config. Owns USART1 PA9/PA10 and TIM4.
 * HAL_BUSY: peripheral clock/IRQ already enabled; no state is changed.
 * Same-baud repeated Init is a no-op; different baud returns HAL_BUSY.
 * UART/TIM HAL modules and generated peripheral/IRQ files are not required. */
HAL_StatusTypeDef RC_Init(void);
HAL_StatusTypeDef RC_InitBT630(void);
HAL_StatusTypeDef RC_InitBaud(uint32_t baud);
void RC_GetState(RC_State_t *out);
void RC_Reset(void);
void RC_ProcessByte(uint8_t byte);
void RC_Tick1ms(void);
#endif
