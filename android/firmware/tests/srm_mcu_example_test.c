#include "srm_mcu_example.h"

#include <assert.h>
#include <stdio.h>
#include <string.h>

/* 以下变量模拟真实硬件，用来观察例程向执行机构和 UART 输出了什么。 */
static srm_control_state_t applied_state;
static srm_pro_control_state_t applied_pro_state;
static uint8_t transmitted[SRM_MAX_FRAME];
static size_t transmitted_length;
static unsigned int apply_count;
static unsigned int pro_apply_count;

void srm_platform_uart_write(const uint8_t *data, size_t length) {
    assert(length <= sizeof(transmitted));
    memcpy(transmitted, data, length);
    transmitted_length = length;
}

void srm_platform_apply_control(const srm_control_state_t *state) {
    applied_state = *state;
    apply_count++;
}

void srm_platform_apply_pro_control(const srm_pro_control_state_t *state) {
    applied_pro_state = *state;
    pro_apply_count++;
}

uint8_t srm_platform_handle_debug(const uint8_t *utf8, uint8_t length) {
    static const uint8_t expected[] = "PING";
    if (length > 0u && utf8[length - 1u] == '\n') length--;
    if (length > 0u && utf8[length - 1u] == '\r') length--;
    return length == sizeof(expected) - 1u
            && memcmp(utf8, expected, sizeof(expected) - 1u) == 0 ? 0u : 3u;
}

/* 模拟 UART 逐字节到达，和真实主循环调用方式一致。 */
static void feed_wire(const uint8_t *wire, size_t length, uint32_t now_ms) {
    size_t index;
    for (index = 0u; index < length; index++) srm_mcu_rx_byte(wire[index], now_ms);
}

static int states_equal(const srm_control_state_t *left,
                        const srm_control_state_t *right) {
    return left->left_x == right->left_x && left->left_y == right->left_y
            && left->right_x == right->right_x && left->right_y == right->right_y
            && left->buttons == right->buttons && left->switches == right->switches
            && left->dpad == right->dpad;
}

int main(void) {
    static const srm_control_state_t command = {-500, 500, -193, 365,
                                                 0x05u, 0x21u, 4u};
    static const srm_control_state_t safe = {0, 0, 0, 0, 0u, 0u, 0u};
    static const srm_pro_control_state_t pro_command = {
        -512, 256, -129, 511, 80u, 255u, SRM_PRO_BUTTON_A | SRM_PRO_BUTTON_R1
    };
    static const uint8_t debug_payload[] = "PING\n";
    srm_parser_t reply_parser;
    srm_frame_t reply;
    srm_parse_result_t parse_result = SRM_PARSE_NONE;
    uint8_t wire[SRM_MAX_FRAME];
    size_t wire_length;
    size_t index;

    /* 初始化必须立即输出一次安全状态。 */
    srm_mcu_init(100u, 0x01u);
    assert(apply_count == 1u);
    assert(states_equal(&applied_state, &safe));

    /* 合法 CONTROL 帧应完整替换当前控制状态。 */
    wire_length = srm_build_control(wire, sizeof(wire), 7u, &command);
    assert(wire_length == 13u);
    feed_wire(wire, wire_length, 100u);
    assert(apply_count == 2u);
    assert(states_equal(&applied_state, &command));

    /* 恰好 600 ms 时仍有效，超过 600 ms 后只执行一次安全复位。 */
    srm_mcu_periodic(700u);
    assert(apply_count == 2u);
    srm_mcu_periodic(701u);
    assert(apply_count == 3u);
    assert(states_equal(&applied_state, &safe));
    srm_mcu_periodic(900u);
    assert(apply_count == 3u);

    /* 专业手柄状态无需 HELLO，按帧类型走独立回调并使用同一失联保护。 */
    wire_length = srm_build_pro_control(wire, sizeof(wire), 8u, &pro_command);
    feed_wire(wire, wire_length, 1000u);
    assert(pro_apply_count == 1u);
    assert(applied_pro_state.right_trigger == 255u
            && applied_pro_state.buttons == pro_command.buttons);
    srm_mcu_periodic(1601u);
    assert(pro_apply_count == 2u);
    assert(applied_pro_state.left_x == 0 && applied_pro_state.right_trigger == 0u
            && applied_pro_state.buttons == 0u);

    /* App 默认追加 LF；例程处理成功后应回 ACK，并带回请求序号 0x44。 */
    wire_length = srm_build_frame(wire, sizeof(wire), SRM_TYPE_DEBUG, 0x44u,
                                  debug_payload, (uint8_t)(sizeof(debug_payload) - 1u));
    feed_wire(wire, wire_length, 900u);
    assert(transmitted_length > 0u);

    srm_parser_init(&reply_parser);
    for (index = 0u; index < transmitted_length; index++) {
        parse_result = srm_parser_push(&reply_parser, transmitted[index], &reply);
    }
    assert(parse_result == SRM_PARSE_FRAME);
    assert(reply.type == SRM_TYPE_ACK);
    assert(reply.length == 2u);
    assert(reply.payload[0] == 0x44u);
    assert(reply.payload[1] == 0u);

    puts("srm_mcu_example_test: PASS");
    return 0;
}
