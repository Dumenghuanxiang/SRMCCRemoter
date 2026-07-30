#ifndef SRM_STM32F103_PORT_H
#define SRM_STM32F103_PORT_H

#include <stddef.h>
#include <stdint.h>

#include "stm32f1xx_hal.h"
#include "srm_protocol.h"

#ifdef __cplusplus
extern "C" {
#endif

/* JDY-31 的链路能力位。当前 App 默认使用 BLE FFE1。 */
#define SRM_TRANSPORT_SPP 0x01u
#define SRM_TRANSPORT_BLE_FFE1 0x02u

/*
 * DMA 环形接收缓存。115200 bps 下 256 字节约能容纳 22 ms 连续数据，
 * 因此主循环必须频繁调用 SRM_STM32_Poll()，不能用长时间阻塞式 delay。
 */
#ifndef SRM_STM32_RX_DMA_SIZE
#define SRM_STM32_RX_DMA_SIZE 256u
#endif
#if SRM_STM32_RX_DMA_SIZE == 0u || SRM_STM32_RX_DMA_SIZE > 65535u
#error "SRM_STM32_RX_DMA_SIZE must be in 1..65535"
#endif

typedef struct {
    uint32_t rx_bytes;
    uint32_t tx_frames;
    uint32_t tx_failures;
    uint32_t uart_errors;
    uint32_t dma_restarts;
} srm_stm32_stats_t;

/*
 * 初始化 STM32 HAL 平台层。
 * bluetooth_uart 必须已经由 CubeMX 初始化，且 RX DMA 必须配置为 Circular。
 * 成功返回 HAL_OK；配置缺失或启动 DMA 失败时返回相应 HAL 状态。
 */
HAL_StatusTypeDef SRM_STM32_Init(UART_HandleTypeDef *bluetooth_uart,
                                uint8_t transport_caps);

/* 主循环每轮调用：取出 DMA 新字节、解析协议，并执行 600 ms 失联保护。 */
void SRM_STM32_Poll(void);

/* 在 HAL_UART_ErrorCallback() 中调用；真正的 DMA 重启延后到主循环执行。 */
void SRM_STM32_OnUartError(UART_HandleTypeDef *uart);

/* 将最近一次应用到板级输出的完整状态复制给调用方。成功返回 1。 */
int SRM_STM32_CopyControlState(srm_control_state_t *output);

/* 复制最近一次 PRO_CONTROL 状态；尚未初始化时返回 0。 */
int SRM_STM32_CopyProControlState(srm_pro_control_state_t *output);

/* 获取只读统计快照，用于串口或调试器诊断。 */
void SRM_STM32_CopyStats(srm_stm32_stats_t *output);

/*
 * 板级扩展点。适配层提供弱实现，工程可在自己的 .c 文件中定义同名强实现：
 * - ApplyControl / ApplyProControl：映射屏幕控制或专业手柄完整状态；
 * - HandleDebug：处理 App 发来的 UTF-8 调试指令，0=成功，非零=协议错误码。
 */
void SRM_BoardApplyControl(const srm_control_state_t *state);
void SRM_BoardApplyProControl(const srm_pro_control_state_t *state);
uint8_t SRM_BoardHandleDebug(const uint8_t *utf8, uint8_t length);

#ifdef __cplusplus
}
#endif

#endif
