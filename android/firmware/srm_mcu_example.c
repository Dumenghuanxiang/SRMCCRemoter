#include "srm_mcu_example.h"

#include <string.h>

/* 解析器和控制状态均使用静态内存，适合没有堆或不允许动态分配的 MCU。 */
static srm_parser_t parser;
static uint32_t last_control_ms;
static uint8_t reply_sequence;
static uint8_t link_caps;
static uint8_t control_active;
static uint8_t pro_control_active;

/* 将所有摇杆、按键、开关恢复到中立状态，并立即写入执行机构。 */
static void apply_safe_state(void) {
    static const srm_control_state_t safe_state = {0, 0, 0, 0, 0u, 0u, 0u};
    static const srm_pro_control_state_t safe_pro_state = {0, 0, 0, 0, 0u, 0u, 0u};
    if (pro_control_active != 0u) srm_platform_apply_pro_control(&safe_pro_state);
    else srm_platform_apply_control(&safe_state);
    control_active = 0u;
}

/* 构建一帧并从蓝牙模块 UART 发回 App。 */
static void send_frame(uint8_t type, const uint8_t *payload, uint8_t payload_length) {
    uint8_t wire[SRM_MAX_FRAME];
    size_t wire_length = srm_build_frame(
            wire, sizeof(wire), type, reply_sequence++, payload, payload_length);
    if (wire_length > 0u) srm_platform_uart_write(wire, wire_length);
}

/* ACK 的第一个字节是被确认帧的 SEQ，第二个字节 0 表示成功。 */
static void send_ack(uint8_t request_sequence) {
    uint8_t payload[2] = {request_sequence, 0u};
    send_frame(SRM_TYPE_ACK, payload, (uint8_t)sizeof(payload));
}

/* ERROR 至少包含原请求 SEQ 和错误码；例程省略可选的 UTF-8 错误详情。 */
static void send_error(uint8_t request_sequence, uint8_t error_code) {
    uint8_t payload[2] = {request_sequence, error_code};
    send_frame(SRM_TYPE_ERROR, payload, (uint8_t)sizeof(payload));
}

/* 收到 App HELLO 后，回复下位机角色、链路能力和本例程实现版本。 */
static void send_mcu_hello(void) {
    uint8_t payload[4] = {2u, (uint8_t)(link_caps | 0x04u), 2u, 0u};
    send_frame(SRM_TYPE_HELLO, payload, (uint8_t)sizeof(payload));
}

/*
 * CRC 已通过后按消息类型分发。CONTROL 不回复 ACK，以免 9600 bps 下挤占
 * 高频控制流；HELLO 和 DEBUG 按协议回复 ACK 或 ERROR。
 */
static void handle_frame(const srm_frame_t *frame, uint32_t now_ms) {
    if (frame->type == SRM_TYPE_CONTROL) {
        srm_control_state_t next_state;

        /* 只有长度、保留位和字段范围都合法时，才原子更新执行机构和超时计时。 */
        if (srm_decode_control(frame, &next_state)) {
            srm_platform_apply_control(&next_state);
            last_control_ms = now_ms;
            control_active = 1u;
            pro_control_active = 0u;
        }
        return;
    }

    if (frame->type == SRM_TYPE_PRO_CONTROL) {
        srm_pro_control_state_t next_state;
        if (srm_decode_pro_control(frame, &next_state)) {
            srm_platform_apply_pro_control(&next_state);
            last_control_ms = now_ms;
            control_active = 1u;
            pro_control_active = 1u;
        }
        return;
    }

    if (frame->type == SRM_TYPE_HELLO) {
        /* HELLO 固定 4 字节；长度错误返回协议错误码 2。 */
        if (frame->length != 4u) {
            send_error(frame->sequence, 2u);
            return;
        }
        send_mcu_hello();
        send_ack(frame->sequence);
        return;
    }

    if (frame->type == SRM_TYPE_DEBUG) {
        uint8_t error_code = srm_platform_handle_debug(frame->payload, frame->length);
        if (error_code == 0u) send_ack(frame->sequence);
        else send_error(frame->sequence, error_code);
        return;
    }

    /* App 不应向 MCU 发送其他类型；用错误码 1 告知“不支持的消息类型”。 */
    send_error(frame->sequence, 1u);
}

void srm_mcu_init(uint32_t now_ms, uint8_t transport_caps) {
    srm_parser_init(&parser);
    last_control_ms = now_ms;
    reply_sequence = 0u;
    link_caps = (uint8_t)(transport_caps & 0x03u);
    control_active = 0u;
    pro_control_active = 0u;

    /* 上电时先应用安全状态，防止执行机构沿用复位前的输出。 */
    apply_safe_state();
}

void srm_mcu_rx_byte(uint8_t byte, uint32_t now_ms) {
    srm_frame_t frame;
    srm_parse_result_t result = srm_parser_push(&parser, byte, &frame);

    /*
     * NONE 表示还没收完；负值表示版本、长度或 CRC 错误。
     * 这些情况都不更新控制超时，也不回包，继续等待解析器重新找到 A5 5A。
     */
    if (result == SRM_PARSE_FRAME) handle_frame(&frame, now_ms);
}

void srm_mcu_periodic(uint32_t now_ms) {
    /* 无符号减法即使毫秒计数器回绕也能正确计算不超过约 49 天的时间差。 */
    if (control_active != 0u
            && (uint32_t)(now_ms - last_control_ms) > SRM_CONTROL_TIMEOUT_MS) {
        apply_safe_state();
    }
}

/*
 * 典型主循环接法（伪代码）：
 *
 * int main(void) {
 *     board_init_uart_9600_8n1();
 *     // App 默认使用 BLE FFE1（0x02）；实验性 SPP 模式改传 0x01。
 *     srm_mcu_init(board_millis(), 0x02u);
 *     for (;;) {
 *         uint8_t byte;
 *         while (board_uart_read_byte(&byte)) {
 *             srm_mcu_rx_byte(byte, board_millis());
 *         }
 *         srm_mcu_periodic(board_millis());
 *     }
 * }
 *
 * 中断中只把 UART 字节放入环形缓冲区；解析、调试命令和发送回包放在主循环，
 * 可避免耗时操作阻塞串口接收中断。
 */
