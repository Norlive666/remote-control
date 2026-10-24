#ifndef REMOTE_CONTROL_H
#define REMOTE_CONTROL_H
#include <stdint.h>
#ifndef RC_HOST_TEST
#include "stm32f1xx_hal.h"
#else
typedef int HAL_StatusTypeDef;
#endif
#define RC_F103 1

/* Project settings: edit here or override for every driver compilation. */
#ifndef RC_DEFAULT_BAUD
#define RC_DEFAULT_BAUD 115200U
#endif
#ifndef RC_RX_INVERT
#define RC_RX_INVERT 0
#endif
#if RC_RX_INVERT != 0
#error "STM32F103 has no hardware RX inversion; use normal UART or external inversion"
#endif

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
/* Call after all MX init calls, before starting other UART business.
 * Owns USART1 PA9/PA10 and TIM4; idle generated UART init is allowed.
 * HAL_BUSY: UART DMA/IRQ/transmit active, timer occupied or pins remapped.
 * No state changed on HAL_BUSY. Do not use this UART concurrently after Init.
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
