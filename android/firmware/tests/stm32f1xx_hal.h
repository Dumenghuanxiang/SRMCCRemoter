#ifndef STM32F1XX_HAL_H
#define STM32F1XX_HAL_H

#include <stdint.h>

typedef enum {
    HAL_OK = 0x00u,
    HAL_ERROR = 0x01u,
    HAL_BUSY = 0x02u,
    HAL_TIMEOUT = 0x03u
} HAL_StatusTypeDef;

typedef struct {
    uint32_t Mode;
} DMA_InitTypeDef;

typedef struct {
    DMA_InitTypeDef Init;
    uint32_t counter;
} DMA_HandleTypeDef;

typedef struct {
    DMA_HandleTypeDef *hdmarx;
} UART_HandleTypeDef;

#define DMA_CIRCULAR 1u
#define DMA_IT_HT 1u
#define DMA_IT_TC 2u

#define __HAL_DMA_DISABLE_IT(handle, interrupt) ((void)(handle), (void)(interrupt))
#define __HAL_DMA_GET_COUNTER(handle) ((handle)->counter)

#if defined(__GNUC__)
#define __weak __attribute__((weak))
#else
#define __weak
#endif

uint32_t HAL_GetTick(void);
HAL_StatusTypeDef HAL_UART_Receive_DMA(UART_HandleTypeDef *uart, uint8_t *data,
                                      uint16_t length);
HAL_StatusTypeDef HAL_UART_DMAStop(UART_HandleTypeDef *uart);
HAL_StatusTypeDef HAL_UART_Transmit(UART_HandleTypeDef *uart, uint8_t *data,
                                    uint16_t length, uint32_t timeout);

#endif
