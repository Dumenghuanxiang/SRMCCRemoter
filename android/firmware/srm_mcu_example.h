#ifndef SRM_MCU_EXAMPLE_H
#define SRM_MCU_EXAMPLE_H

#include <stddef.h>
#include <stdint.h>

#include "srm_protocol.h"

#ifdef __cplusplus
extern "C" {
#endif

/* 超过该时间没有合法 CONTROL/PRO_CONTROL 帧，下位机自动释放全部控制量。 */
#define SRM_CONTROL_TIMEOUT_MS 600u

/*
 * 初始化协议例程。
 * now_ms：当前单调递增的毫秒计时值。
 * transport_caps：HELLO 的链路能力位，SPP 传 0x01，BLE FFE1 传 0x02。
 */
void srm_mcu_init(uint32_t now_ms, uint8_t transport_caps);

/* UART 每收到一个字节调用一次。建议在主循环中从串口环形缓冲区取出后调用。 */
void srm_mcu_rx_byte(uint8_t byte, uint32_t now_ms);

/* 主循环周期调用，用于执行 600 ms 失联保护。 */
void srm_mcu_periodic(uint32_t now_ms);

/*
 * 以下三个函数由具体单片机工程实现，本例程只负责调用：
 * 1. 将完整字节数组写入蓝牙模块 UART；
 * 2. 一次性更新执行机构，避免各通道更新时间不同；
 * 3. 处理 App 发来的 UTF-8 调试命令，成功返回 0，失败返回非零错误码。
 */
void srm_platform_uart_write(const uint8_t *data, size_t length);
void srm_platform_apply_control(const srm_control_state_t *state);
void srm_platform_apply_pro_control(const srm_pro_control_state_t *state);
uint8_t srm_platform_handle_debug(const uint8_t *utf8, uint8_t length);

#ifdef __cplusplus
}
#endif

#endif
