#include "srm_stm32f103_port.h"

#include <assert.h>
#include <stdio.h>
#include <string.h>

static uint32_t fake_tick;
static uint8_t *dma_target;
static uint16_t dma_capacity;
static unsigned int dma_start_count;
static unsigned int dma_stop_count;
static uint8_t transmitted[SRM_MAX_FRAME];
static uint16_t transmitted_length;

uint32_t HAL_GetTick(void) {
    return fake_tick;
}

HAL_StatusTypeDef HAL_UART_Receive_DMA(UART_HandleTypeDef *uart, uint8_t *data,
                                      uint16_t length) {
    dma_target = data;
    dma_capacity = length;
    uart->hdmarx->counter = length;
    dma_start_count++;
    return HAL_OK;
}

HAL_StatusTypeDef HAL_UART_DMAStop(UART_HandleTypeDef *uart) {
    (void)uart;
    dma_stop_count++;
    return HAL_OK;
}

HAL_StatusTypeDef HAL_UART_Transmit(UART_HandleTypeDef *uart, uint8_t *data,
                                    uint16_t length, uint32_t timeout) {
    (void)uart;
    (void)timeout;
    assert(length <= sizeof(transmitted));
    memcpy(transmitted, data, length);
    transmitted_length = length;
    return HAL_OK;
}

static void dma_feed(DMA_HandleTypeDef *dma, const uint8_t *data, size_t length,
                     size_t *write_position) {
    size_t index;
    assert(length < dma_capacity);
    for (index = 0u; index < length; index++) {
        dma_target[*write_position] = data[index];
        *write_position = (*write_position + 1u) % dma_capacity;
    }
    dma->counter = (uint32_t)(dma_capacity - *write_position);
}

static int state_equal(const srm_control_state_t *left,
                       const srm_control_state_t *right) {
    return left->left_x == right->left_x && left->left_y == right->left_y
            && left->right_x == right->right_x && left->right_y == right->right_y
            && left->buttons == right->buttons && left->switches == right->switches
            && left->dpad == right->dpad;
}

static void expect_debug_ack(DMA_HandleTypeDef *dma, const uint8_t *payload,
                             uint8_t payload_length, size_t *write_position,
                             uint8_t request_sequence) {
    srm_parser_t reply_parser;
    srm_frame_t reply;
    srm_parse_result_t parse_result = SRM_PARSE_NONE;
    uint8_t wire[SRM_MAX_FRAME];
    size_t wire_length;
    size_t index;

    transmitted_length = 0u;
    wire_length = srm_build_frame(wire, sizeof(wire), SRM_TYPE_DEBUG,
                                  request_sequence, payload, payload_length);
    dma_feed(dma, wire, wire_length, write_position);
    SRM_STM32_Poll();
    assert(transmitted_length > 0u);

    srm_parser_init(&reply_parser);
    for (index = 0u; index < transmitted_length; index++) {
        parse_result = srm_parser_push(&reply_parser, transmitted[index], &reply);
    }
    assert(parse_result == SRM_PARSE_FRAME);
    assert(reply.type == SRM_TYPE_ACK && reply.length == 2u);
    assert(reply.payload[0] == request_sequence && reply.payload[1] == 0u);
}

int main(void) {
    DMA_HandleTypeDef dma = {{DMA_CIRCULAR}, 0u};
    UART_HandleTypeDef uart = {&dma};
    srm_control_state_t expected = {-500, 500, -193, 365,
                                    0x09u, 0x21u, 4u};
    srm_control_state_t actual;
    srm_pro_control_state_t expected_pro = {-512, 511, -257, 256, 90u, 255u,
                                             SRM_PRO_BUTTON_A | SRM_PRO_BUTTON_START};
    srm_pro_control_state_t actual_pro;
    srm_stm32_stats_t adapter_stats;
    uint8_t wire[SRM_MAX_FRAME];
    static const uint8_t ping_plain[] = {'P', 'I', 'N', 'G'};
    static const uint8_t ping[] = {'P', 'I', 'N', 'G', '\n'};
    static const uint8_t ping_crlf[] = {'P', 'I', 'N', 'G', '\r', '\n'};
    size_t wire_length;
    size_t write_position = 0u;

    fake_tick = 100u;
    assert(SRM_STM32_Init(&uart, SRM_TRANSPORT_BLE_FFE1) == HAL_OK);
    assert(dma_start_count == 1u);
    assert(SRM_STM32_CopyControlState(&actual));
    assert(actual.left_x == 0 && actual.left_y == 0 && actual.buttons == 0u);

    wire_length = srm_build_control(wire, sizeof(wire), 7u, &expected);
    dma_feed(&dma, wire, wire_length, &write_position);
    SRM_STM32_Poll();
    assert(SRM_STM32_CopyControlState(&actual));
    assert(state_equal(&actual, &expected));

    wire_length = srm_build_pro_control(wire, sizeof(wire), 8u, &expected_pro);
    dma_feed(&dma, wire, wire_length, &write_position);
    SRM_STM32_Poll();
    assert(SRM_STM32_CopyProControlState(&actual_pro));
    assert(actual_pro.left_x == expected_pro.left_x
            && actual_pro.right_trigger == expected_pro.right_trigger
            && actual_pro.buttons == expected_pro.buttons);

    /* 超过最后一帧 600 ms 后必须自动应用全零安全状态。 */
    fake_tick = 701u;
    SRM_STM32_Poll();
    assert(SRM_STM32_CopyProControlState(&actual_pro));
    assert(actual_pro.left_x == 0 && actual_pro.left_y == 0
            && actual_pro.right_trigger == 0u && actual_pro.buttons == 0u);

    /* 默认处理器同时兼容关闭自动换行、默认 LF 和手动 CRLF。 */
    expect_debug_ack(&dma, ping_plain, (uint8_t)sizeof(ping_plain),
                     &write_position, 0x43u);
    expect_debug_ack(&dma, ping, (uint8_t)sizeof(ping), &write_position, 0x44u);
    expect_debug_ack(&dma, ping_crlf, (uint8_t)sizeof(ping_crlf),
                     &write_position, 0x45u);

    /* UART 错误回调只置位，下一轮 Poll 才安全地重启 DMA。 */
    SRM_STM32_OnUartError(&uart);
    SRM_STM32_Poll();
    assert(dma_stop_count == 1u && dma_start_count == 2u);
    SRM_STM32_CopyStats(&adapter_stats);
    assert(adapter_stats.uart_errors == 1u && adapter_stats.dma_restarts == 1u);
    assert(adapter_stats.rx_bytes == 29u
            + sizeof(ping_plain) + sizeof(ping) + sizeof(ping_crlf) + 18u);
    assert(adapter_stats.tx_frames == 3u && adapter_stats.tx_failures == 0u);

    puts("srm_stm32f103_port_test: PASS");
    return 0;
}
