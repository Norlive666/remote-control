#ifndef REMOTE_CONTROL_H
#define REMOTE_CONTROL_H
#include <stdint.h>
#ifndef RC_HOST_TEST
#include "stm32f1xx_hal.h"
#else
typedef int HAL_StatusTypeDef;
#endif
#define RC_F103 1

typedef struct {
    int16_t axes[4];
    uint16_t sliders[2];
    uint8_t enabled;
    uint8_t stopped;
    uint8_t sequence;
} RC_State_t;
extern volatile RC_State_t g_rc;
HAL_StatusTypeDef RC_Init(void);
HAL_StatusTypeDef RC_InitBT630(void);
HAL_StatusTypeDef RC_InitBaud(uint32_t baud);
void RC_GetState(RC_State_t *out);
void RC_Reset(void);
void RC_ProcessByte(uint8_t byte);
void RC_Tick1ms(void);
#endif
