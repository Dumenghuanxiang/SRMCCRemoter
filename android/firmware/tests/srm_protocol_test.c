#include "srm_protocol.h"

#include <assert.h>
#include <stdio.h>
#include <string.h>

int main(void) {
    static const uint8_t check[] = "123456789";
    static const uint8_t expected_wire[] = {
        0xA5u, 0x5Au, 0x40u, 0x2Au, 0x07u,
        0x00u, 0xFEu, 0xF7u, 0x73u, 0x5Bu, 0x1Au, 0x0Eu, 0x98u
    };
    static const uint8_t expected_pro_wire[] = {
        0xA5u, 0x5Au, 0x47u, 0x2Cu, 0x0Au,
        0x00u, 0xFEu, 0xF7u, 0x2Fu, 0x40u, 0x1Fu, 0xFFu,
        0x69u, 0x35u, 0x01u, 0x54u
    };
    static const srm_pro_control_state_t pro_sent = {
        -512, 511, -257, 256, 31u, 255u, 0x013569ul
    };
    srm_control_state_t sent = {-512, 511, -193, 365, 0x0Au, 0x21u, 3u};
    srm_control_state_t invalid;
    srm_control_state_t received;
    srm_pro_control_state_t pro_received;
    srm_parser_t parser;
    srm_frame_t frame;
    uint8_t wire[SRM_MAX_FRAME];
    size_t length;
    size_t index;
    srm_parse_result_t result = SRM_PARSE_NONE;

    /* 第一步：用 CRC-8/ATM 标准检查向量确认 CRC 实现没有移植错误。 */
    assert(srm_crc8_atm(check, 9u) == 0xF4u);

    /* 第二步：把一组包含正负摇杆值、按键、开关和十字键的状态编码成帧。 */
    length = srm_build_control(wire, sizeof(wire), 0x2Au, &sent);
    assert(length == 13u);
    assert(memcmp(wire, expected_wire, sizeof(expected_wire)) == 0);

    invalid = sent;
    invalid.left_x = -513;
    assert(srm_build_control(wire, sizeof(wire), 0x2Au, &invalid) == 0u);
    length = srm_build_control(wire, sizeof(wire), 0x2Au, &sent);

    /* 第三步：先送入一个噪声字节，再逐字节送入完整帧，验证自动同步和拆包解析。 */
    srm_parser_init(&parser);
    assert(srm_parser_push(&parser, 0x11u, &frame) == SRM_PARSE_NONE);
    for (index = 0u; index < length; index++) result = srm_parser_push(&parser, wire[index], &frame);
    /* 第四步：检查序号，并确认解码后的 7 字节控制状态与发送前完全一致。 */
    assert(result == SRM_PARSE_FRAME);
    assert(frame.sequence == 0x2Au);
    assert(srm_decode_control(&frame, &received));
    assert(frame.version == SRM_PROTOCOL_VERSION);
    assert(sent.left_x == received.left_x && sent.left_y == received.left_y);
    assert(sent.right_x == received.right_x && sent.right_y == received.right_y);
    assert(sent.buttons == received.buttons && sent.switches == received.switches);
    assert(sent.dpad == received.dpad);

    /* v4 不再接受 v3 VTYPE，即使载荷和 CRC 本身完整。 */
    wire[2] = (uint8_t)((3u << 4) | SRM_TYPE_CONTROL);
    wire[length - 1u] = srm_crc8_atm(&wire[2], length - 3u);
    srm_parser_init(&parser);
    assert(srm_parser_push(&parser, wire[0], &frame) == SRM_PARSE_NONE);
    assert(srm_parser_push(&parser, wire[1], &frame) == SRM_PARSE_NONE);
    assert(srm_parser_push(&parser, wire[2], &frame) == SRM_PARSE_BAD_VERSION);

    /* PRO_CONTROL 为 16 字节，并完整保留 uint8 双扳机和 17 个按键位。 */
    length = srm_build_pro_control(wire, sizeof(wire), 0x2Cu, &pro_sent);
    assert(length == 16u && wire[2] == 0x47u && wire[4] == 10u);
    assert(memcmp(wire, expected_pro_wire, sizeof(expected_pro_wire)) == 0);
    srm_parser_init(&parser);
    for (index = 0u; index < length; index++) result = srm_parser_push(&parser, wire[index], &frame);
    assert(result == SRM_PARSE_FRAME && srm_decode_pro_control(&frame, &pro_received));
    assert(pro_received.left_x == pro_sent.left_x && pro_received.left_y == pro_sent.left_y);
    assert(pro_received.right_x == pro_sent.right_x && pro_received.right_y == pro_sent.right_y);
    assert(pro_received.left_trigger == 31u && pro_received.right_trigger == 255u);
    assert(pro_received.buttons == pro_sent.buttons);

    {
        uint8_t invalid_pro_payload[10] = {0u};
        invalid_pro_payload[9] = 0x02u;
        length = srm_build_frame(wire, sizeof(wire), SRM_TYPE_PRO_CONTROL, 0x2Eu,
                                 invalid_pro_payload, (uint8_t)sizeof(invalid_pro_payload));
        srm_parser_init(&parser);
        for (index = 0u; index < length; index++) result = srm_parser_push(&parser, wire[index], &frame);
        assert(result == SRM_PARSE_FRAME && !srm_decode_pro_control(&frame, &pro_received));
    }

    /* 第五步：人为翻转 Payload 中一位，确认损坏的数据会被 CRC 拒绝。 */
    length = srm_build_control(wire, sizeof(wire), 0x2Au, &sent);
    wire[7] ^= 0x01u;
    srm_parser_init(&parser);
    for (index = 0u; index < length; index++) result = srm_parser_push(&parser, wire[index], &frame);
    assert(result == SRM_PARSE_BAD_CRC);

    /* Controls bit13..bit15 是保留位，即使 CRC 正确也必须拒绝。 */
    {
        uint8_t invalid_payload[7] = {0u};
        invalid_payload[6] = 0x20u;
        length = srm_build_frame(wire, sizeof(wire), SRM_TYPE_CONTROL, 0x2Bu,
                                 invalid_payload, (uint8_t)sizeof(invalid_payload));
        srm_parser_init(&parser);
        for (index = 0u; index < length; index++) {
            result = srm_parser_push(&parser, wire[index], &frame);
        }
        assert(result == SRM_PARSE_FRAME);
        assert(!srm_decode_control(&frame, &received));
    }
    puts("srm_protocol_test: PASS");
    return 0;
}
