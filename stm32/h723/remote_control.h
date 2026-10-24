#ifndef REMOTE_CONTROL_H
#define REMOTE_CONTROL_H
#include <stdint.h>
#ifndef RC_HOST_TEST
#include "stm32h7xx_hal.h"
#else
typedef int HAL_StatusTypeDef;
#endif
#define RC_H723 1

/* Project settings: edit here or override for every driver compilation. */
#ifndef RC_DEFAULT_BAUD
#define RC_DEFAULT_BAUD 9600U
#endif
#ifndef RC_RX_INVERT
#define RC_RX_INVERT 0 /* 1 for CN14/Q5 D-Bus input; 0 for direct PD9. */
#endif
#if RC_RX_INVERT != 0 && RC_RX_INVERT != 1
#error "RC_RX_INVERT must be 0 or 1"
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
 * Owns USART3 PD8(TX)/PD9(RX) and TIM5; idle generated UART init is allowed.
 * HAL_BUSY: UART DMA/IRQ/transmit active, or timer occupied; no state changed.
 * Do not use this UART concurrently with HAL or another protocol after Init.
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
