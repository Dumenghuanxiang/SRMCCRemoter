#include "srm_stm32f103_port.h"

#include <string.h>

#include "srm_mcu_example.h"

#define SRM_UART_TX_TIMEOUT_MS 200u

static UART_HandleTypeDef *srm_uart;
static uint8_t rx_dma_buffer[SRM_STM32_RX_DMA_SIZE];
static uint16_t rx_tail;
static volatile uint8_t restart_requested;
static srm_control_state_t current_state;
static srm_pro_control_state_t current_pro_state;
static srm_stm32_stats_t stats;

/* 启动循环 DMA；解析器不依赖 DMA 分块边界，因此无需寻找“完整 DMA 包”。 */
static HAL_StatusTypeDef start_rx_dma(void) {
    HAL_StatusTypeDef status;

    rx_tail = 0u;
    restart_requested = 0u;
    status = HAL_UART_Receive_DMA(srm_uart, rx_dma_buffer, SRM_STM32_RX_DMA_SIZE);
    if (status != HAL_OK) return status;

    /* 本适配层通过 DMA 剩余计数轮询，不在中断里解析数据。 */
    __HAL_DMA_DISABLE_IT(srm_uart->hdmarx, DMA_IT_HT);
    __HAL_DMA_DISABLE_IT(srm_uart->hdmarx, DMA_IT_TC);
    return HAL_OK;
}

HAL_StatusTypeDef SRM_STM32_Init(UART_HandleTypeDef *bluetooth_uart,
                                uint8_t transport_caps) {
    if (bluetooth_uart == NULL || bluetooth_uart->hdmarx == NULL) return HAL_ERROR;
    if (bluetooth_uart->hdmarx->Init.Mode != DMA_CIRCULAR) return HAL_ERROR;

    srm_uart = bluetooth_uart;
    memset(rx_dma_buffer, 0, sizeof(rx_dma_buffer));
    memset(&current_state, 0, sizeof(current_state));
    memset(&current_pro_state, 0, sizeof(current_pro_state));
    memset(&stats, 0, sizeof(stats));
    srm_mcu_init(HAL_GetTick(), transport_caps);
    return start_rx_dma();
}

static void consume_rx_dma(void) {
    uint16_t dma_position;
    uint32_t now_ms;

    /* DMA counter 表示还剩多少字节未写，换算后得到下一写入位置。 */
    dma_position = (uint16_t)(SRM_STM32_RX_DMA_SIZE
            - __HAL_DMA_GET_COUNTER(srm_uart->hdmarx));
    if (dma_position >= SRM_STM32_RX_DMA_SIZE) dma_position = 0u;
    now_ms = HAL_GetTick();

    while (rx_tail != dma_position) {
        srm_mcu_rx_byte(rx_dma_buffer[rx_tail], now_ms);
        rx_tail++;
        if (rx_tail >= SRM_STM32_RX_DMA_SIZE) rx_tail = 0u;
        stats.rx_bytes++;
    }
}

void SRM_STM32_Poll(void) {
    if (srm_uart == NULL) return;

    if (restart_requested != 0u) {
        (void)HAL_UART_DMAStop(srm_uart);
        if (start_rx_dma() == HAL_OK) stats.dma_restarts++;
        else restart_requested = 1u;
    }

    if (restart_requested == 0u) consume_rx_dma();
    srm_mcu_periodic(HAL_GetTick());
}

void SRM_STM32_OnUartError(UART_HandleTypeDef *uart) {
    if (uart == srm_uart) {
        stats.uart_errors++;
        restart_requested = 1u;
    }
}

int SRM_STM32_CopyControlState(srm_control_state_t *output) {
    if (output == NULL || srm_uart == NULL) return 0;
    *output = current_state;
    return 1;
}

int SRM_STM32_CopyProControlState(srm_pro_control_state_t *output) {
    if (output == NULL || srm_uart == NULL) return 0;
    *output = current_pro_state;
    return 1;
}

void SRM_STM32_CopyStats(srm_stm32_stats_t *output) {
    if (output != NULL) *output = stats;
}

/* srm_mcu_example.c 要求的平台发送函数：仅管理帧会走这里，CONTROL 不回 ACK。 */
void srm_platform_uart_write(const uint8_t *data, size_t length) {
    HAL_StatusTypeDef status;
    if (srm_uart == NULL || data == NULL || length == 0u || length > UINT16_MAX) return;

    status = HAL_UART_Transmit(srm_uart, (uint8_t *)data, (uint16_t)length,
                               SRM_UART_TX_TIMEOUT_MS);
    if (status == HAL_OK) stats.tx_frames++;
    else stats.tx_failures++;
}

/* 先保存完整快照，再调用板级映射；安全状态也会经过相同路径。 */
void srm_platform_apply_control(const srm_control_state_t *state) {
    if (state == NULL) return;
    current_state = *state;
    memset(&current_pro_state, 0, sizeof(current_pro_state));
    SRM_BoardApplyControl(state);
}

void srm_platform_apply_pro_control(const srm_pro_control_state_t *state) {
    if (state == NULL) return;
    current_pro_state = *state;
    memset(&current_state, 0, sizeof(current_state));
    SRM_BoardApplyProControl(state);
}

uint8_t srm_platform_handle_debug(const uint8_t *utf8, uint8_t length) {
    return SRM_BoardHandleDebug(utf8, length);
}

/* 默认板级实现不驱动任何负载，防止复制例程后误动作。 */
__weak void SRM_BoardApplyControl(const srm_control_state_t *state) {
    (void)state;
}

__weak void SRM_BoardApplyProControl(const srm_pro_control_state_t *state) {
    (void)state;
}

/* 默认接受 PING 及 App 默认自动追加换行后的 PING\n / PING\r\n。 */
__weak uint8_t SRM_BoardHandleDebug(const uint8_t *utf8, uint8_t length) {
    static const uint8_t ping[] = {'P', 'I', 'N', 'G'};
    if (utf8 == NULL) return 1u;
    if (length > 0u && utf8[length - 1u] == '\n') length--;
    if (length > 0u && utf8[length - 1u] == '\r') length--;
    if (length == sizeof(ping) && memcmp(utf8, ping, sizeof(ping)) == 0) return 0u;
    return 1u;
}
