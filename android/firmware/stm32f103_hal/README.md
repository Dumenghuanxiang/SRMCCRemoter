# STM32F103 HAL 适配例程与教学

本目录把“SRM校内赛”协议 v4 接入 STM32F103。示例面向 STM32CubeMX / STM32CubeIDE、
STM32F103C8T6（Blue Pill）和 STM32F1 HAL，默认使用 `USART1 + RX DMA Circular` 接收
JDY-31 的连续串口字节流。协议层不使用动态内存，也不把任何输入定义成前进、转向或
其他具体动作；实际语义只在板级回调中决定。

## 1. 文件职责

| 文件 | 作用 |
|---|---|
| `../srm_protocol.c/.h` | v4 帧同步、CRC-8/ATM、CONTROL/PRO_CONTROL 解码 |
| `../srm_mcu_example.c/.h` | 消息分发、ACK/ERROR、600 ms 失联保护 |
| `srm_stm32f103_port.c/.h` | STM32F103 HAL、DMA、UART 回包与板级回调 |
| `examples/srm_board_example.c` | 四轴 PWM 与 PC13 LED 教学映射 |

## 2. 硬件接线

以 USART1 默认引脚为例：

| STM32F103 | JDY-31 串口侧 | 说明 |
|---|---|---|
| PA9 / USART1_TX | RXD | 交叉连接 |
| PA10 / USART1_RX | TXD | 交叉连接 |
| GND | GND | 必须共地 |
| 供电 | VCC | 按所用模块/底板规格供电 |

STM32F103 与 JDY-31 的 UART 逻辑应为 3.3 V。不要因为某些转接底板可接 5 V，就默认
裸模块的 IO 能承受 5 V。上电前先查所购模块原理图。

## 3. CubeMX 配置

1. 新建 STM32F103C8Tx 工程，`SYS -> Debug` 选择 `Serial Wire`。
2. 启用 `USART1 -> Asynchronous`。
3. 设置与 JDY-31 完全一致的串口参数：
   - Baud Rate：先用 `9600` 验证；双方都修改成功后可用 `115200`。
   - Word Length：8 Bits。
   - Parity：None。
   - Stop Bits：1。
   - Hardware Flow Control：None。
4. 在 USART1 的 DMA Settings 添加 `USART1_RX`：
   - Direction：Peripheral To Memory。
   - Mode：`Circular`，不能选 Normal。
   - Peripheral Increment：Disable。
   - Memory Increment：Enable。
   - Data Width：Byte / Byte。
   - Priority：High 或 Very High。
5. 开启 `USART1 global interrupt`，用于接收溢出等错误恢复。
6. 生成代码。CubeMX 通常会自动生成 `DMA1_Channel5`（USART1_RX）。

本适配层轮询 DMA 写指针，不在 DMA 中断里解析协议，因此会关闭 DMA Half Transfer 和
Transfer Complete 中断。UART 错误中断仍应保留。

## 4. 把文件加入 CubeIDE

将以下文件加入工程的 `Core/Src` 与 `Core/Inc`，或保持原目录并加入编译路径：

```text
firmware/srm_protocol.c
firmware/srm_protocol.h
firmware/srm_mcu_example.c
firmware/srm_mcu_example.h
firmware/stm32f103_hal/srm_stm32f103_port.c
firmware/stm32f103_hal/srm_stm32f103_port.h
```

头文件搜索路径至少包含 `firmware` 和 `firmware/stm32f103_hal`。

## 5. main.c 接入

在 `main.c` 的 USER CODE Includes 区加入：

```c
#include "srm_stm32f103_port.h"
```

所有 CubeMX 外设初始化完成后启动协议。JDY-31 使用 App 默认 BLE FFE1 时传
`SRM_TRANSPORT_BLE_FFE1`；只有确认使用实验性经典蓝牙 SPP 时才传
`SRM_TRANSPORT_SPP`。

```c
MX_GPIO_Init();
MX_DMA_Init();
MX_USART1_UART_Init();

if (SRM_STM32_Init(&huart1, SRM_TRANSPORT_BLE_FFE1) != HAL_OK) {
    Error_Handler();
}
```

主循环每轮调用一次，不要在它前面放长时间 `HAL_Delay()`：

```c
while (1) {
    SRM_STM32_Poll();

    /* 其他任务必须非阻塞，或拆成短时间片。 */
}
```

在 `stm32f1xx_it.c` 中保留 CubeMX 生成的 USART1 IRQ：

```c
void USART1_IRQHandler(void) {
    HAL_UART_IRQHandler(&huart1);
}
```

在任意 USER CODE 区加入错误回调。如果工程已有该回调，只合并函数体，不要重复定义：

```c
void HAL_UART_ErrorCallback(UART_HandleTypeDef *huart) {
    SRM_STM32_OnUartError(huart);
}
```

错误回调只设置标志，DMA 停止和重启在下一次 `SRM_STM32_Poll()` 中进行，避免在中断里
执行复杂逻辑。

## 6. 实现板级控制

新建一个 `.c` 文件并实现强符号。传入的是已经通过同步头、版本、长度、CRC、保留位和
字段范围校验的完整快照：

```c
void SRM_BoardApplyControl(const srm_control_state_t *state) {
    /* 四轴：state->left_x/left_y/right_x/right_y，范围 -512..511 */
    /* 按键：state->buttons，bit0=A、bit1=B、bit2=X、bit3=Y */
    /* 开关：state->switches，bit0..bit5=S1..S6 */
    /* 十字键：state->dpad，0中立、1上、2下、3左、4右 */
}

void SRM_BoardApplyProControl(const srm_pro_control_state_t *state) {
    /* 摇杆：-512..511；left_trigger/right_trigger：0..255 */
    /* buttons：SRM_PRO_BUTTON_* 位图，允许 D-pad 斜向组合 */
}
```

600 ms 没有收到合法控制帧时，协议层会再次调用最近控制类型的回调，并传入全零安全状态。不要在
板级代码中绕过这一调用。若继电器或机械结构需要不同的安全输出，应在这里把“全零输入”
映射为该硬件真正安全的状态。

`examples/srm_board_example.c` 演示了：

- 四个 `int16_t` 容器中的 10-bit 轴映射到 TIM2 CH1..CH4 的 1000..2000 us PWM；
- A 键控制 Blue Pill PC13 LED；
- `PING`、`LED=1`、`LED=0` 调试命令。

这是教学映射，不应不经检查直接接入电机或功率负载。

使用该 PWM 示例时，在 CubeMX 中把 TIM2 的计数频率配置为 1 MHz、Period 配置为
`19999`，并启用 CH1..CH4 的 PWM Generation。`MX_TIM2_Init()` 后还必须启动四个通道：

```c
HAL_TIM_PWM_Start(&htim2, TIM_CHANNEL_1);
HAL_TIM_PWM_Start(&htim2, TIM_CHANNEL_2);
HAL_TIM_PWM_Start(&htim2, TIM_CHANNEL_3);
HAL_TIM_PWM_Start(&htim2, TIM_CHANNEL_4);
```

例如 TIM2 时钟为 72 MHz 时，Prescaler 设置为 `71` 可得到 1 MHz 计数频率。实际定时器
时钟取决于 RCC 和 APB1 分频，必须按 CubeMX 的 Clock Configuration 计算，不能只照抄数值。

## 7. DMA 设计与实时性

UART、BLE 和 SPP 都是连续字节流。一次 DMA 更新可能只有半帧，也可能包含多帧，因此
`SRM_STM32_Poll()` 会把每个新字节依次送入流式解析器。

256 字节缓存可覆盖的最坏时间：

```text
9600 bps   -> 约 267 ms
115200 bps -> 约 22 ms
```

建议主循环每 `1–5 ms` 至少调用一次。若超过整个缓存容量都没有轮询，DMA 可能覆盖尚未
处理的数据；此时增加缓存只能缓解问题，正确做法仍是移除阻塞式延时、printf 和长时间
关中断代码。

JDY-31 / BLE FFE1 实测即使 UART 提高到 115200 bps，控制帧频率仍低于 70 Hz，因此
提高 App 调度频率不代表 MCU 会收到同样频率。App 不会静默降低设置值；推荐使用仓库
中的 `tools/srm_serial_analyzer.py` 测量实际帧率和丢帧。

## 8. 调试指令教学

App 的“调试发送”会发送 `TYPE=DEBUG`，Payload 是 UTF-8 原始字节，不含 `\0`。自动换行
默认开启，因此输入 `PING` 时实际 Payload 通常是 `PING\n`。处理函数必须使用 `length`，
不能直接调用假设 NUL 结尾的 `strcmp()`；命令式处理可只去掉末尾一个 LF 和可选 CR：

```c
uint8_t SRM_BoardHandleDebug(const uint8_t *data, uint8_t length) {
    static const uint8_t command[] = "PING";
    if (data == NULL) return 1u;
    if (length > 0u && data[length - 1u] == '\n') length--;
    if (length > 0u && data[length - 1u] == '\r') length--;
    if (length == sizeof(command) - 1u
            && memcmp(data, command, sizeof(command) - 1u) == 0) {
        return 0u; /* App 收到 ACK */
    }
    return 1u;     /* App 收到 ERROR: unsupported */
}
```

调试处理不能长时间阻塞。9600 bps 下也要限制 MCU 上行日志，否则日志会与控制链路争用
带宽。

## 9. 首次联调步骤

1. 暂时不要连接电机，只连接 UART 和共地。
2. JDY-31 与 USART1 都设为 9600 8N1。
3. 下载固件，确认 `SRM_STM32_Init()` 返回 `HAL_OK`。
4. App 不连接蓝牙时先验证本地回环；随后扫描并连接 JDY-31。
5. 保持 App 默认“调试指令自动换行”开启并发送 `PING`。收到 ACK 说明带 LF 的下行、解析、CRC 和上行都已工作。
6. 在调试器观察 `current_state`，逐个操作摇杆、ABXY、十字键和 S1..S6。
7. 停止操作后保持连接，状态仍应持续刷新；断开蓝牙，600 ms 后应进入全零安全状态。
8. 最后才实现 `SRM_BoardApplyControl()` 并连接低功率测试负载。
9. 需要提高波特率时，先修改 JDY-31，再修改 CubeMX USART1，确保两端同时生效。

## 10. 常见问题

### 完全收不到字节

- 检查 TX/RX 是否交叉、是否共地；
- 检查 `huart1.hdmarx` 不为空，DMA Mode 是否为 Circular；
- 检查 JDY-31 与 STM32 的波特率、数据位、校验位和停止位是否一致；
- 观察 `SRM_STM32_CopyStats()` 的 `rx_bytes` 是否增长。

### rx_bytes 增长但状态不更新

- 确认帧的 VTYPE 版本为 4，并按 TYPE `0x0` / `0x7` 选择 CONTROL/PRO_CONTROL；
- 检查是否误改 `srm_protocol.c` 的同步字节或 CRC；
- 使用协议文档中的 13 字节中立 CONTROL 做串口注入测试；
- 确认没有把一次 DMA 接收错误地当成一帧。

### 已连接但始终没有 CONTROL

- 检查 App 设置顶部“向已连接从机下发控制帧”是否开启；该开关默认开启。
- 该开关关闭时，HELLO 和 DEBUG 仍可发送，但周期 CONTROL 会停止，600 ms 后下位机应进入安全状态。
- 检查 App 调试窗口是否出现“控制帧调度已停止”，并确认目标频率不是问题排查时误设的值。

### 偶发失控或延迟越来越大

- 主循环中删除长时间 `HAL_Delay()` 和阻塞式 printf；
- CONTROL 不应返回 ACK；
- App 和从机都不能用 FIFO 堆积旧 CONTROL 状态；
- 将目标频率降到 50–60 Hz，检查 UART 错误和 DMA 重启计数。

### 修改到 115200 后完全失联

JDY-31 参数修改与 STM32 CubeMX 配置必须一致。先用 USB-UART 单独确认模块当前波特率，
不要只根据 AT 命令返回值猜测。若实验性 SPP 导致系统蓝牙状态异常，应完全关闭 App，
在系统设置中断开并取消配对设备，再恢复 App 默认 BLE FFE1 模式。

## 11. 发布前检查表

- [ ] USART RX DMA 为 Circular，主循环轮询周期不超过 5 ms；
- [ ] 固件只接受协议 v4，v3 VTYPE 不得刷新控制状态；
- [ ] 两种控制帧的四轴均按 5 字节打包，并解码到 `-512..511`；
- [ ] CONTROL 通过 CRC 和范围校验后才整体应用；
- [ ] 600 ms 失联时真实执行机构进入安全状态；
- [ ] CONTROL/PRO_CONTROL 不回 ACK，DEBUG/HELLO 正确回 ACK 或 ERROR；
- [ ] 调试命令按长度比较，不把 Payload 当作 NUL 结尾字符串；
- [ ] 已用断开蓝牙、串口噪声和错误波特率测试恢复能力；
- [ ] 功率负载测试前已验证急停和上电默认状态。

## 12. 无 DMA 时的逐字节中断备选

资源紧张或暂时不想配置 DMA 时，可以直接使用通用 MCU 层。不要同时编译
`srm_stm32f103_port.c`，而是在自己的平台文件中实现四个 `srm_platform_*` 函数：

```c
static uint8_t uart_rx_byte;

void protocol_it_init(void) {
    srm_mcu_init(HAL_GetTick(), SRM_TRANSPORT_BLE_FFE1);
    HAL_UART_Receive_IT(&huart1, &uart_rx_byte, 1u);
}

void HAL_UART_RxCpltCallback(UART_HandleTypeDef *huart) {
    if (huart == &huart1) {
        srm_mcu_rx_byte(uart_rx_byte, HAL_GetTick());
        HAL_UART_Receive_IT(&huart1, &uart_rx_byte, 1u);
    }
}

void HAL_UART_ErrorCallback(UART_HandleTypeDef *huart) {
    if (huart == &huart1) {
        HAL_UART_Receive_IT(&huart1, &uart_rx_byte, 1u);
    }
}

/* main while(1) 中持续调用。 */
void protocol_it_poll(void) {
    srm_mcu_periodic(HAL_GetTick());
}
```

逐字节中断方案在 115200 bps 下会显著增加中断次数，主循环实时性也更容易受影响，正式
控制工程优先使用本目录的 DMA Circular 适配。中断回调里只喂入一个字节并立即重新挂接
接收，不能执行 printf、PWM 计算或其他耗时操作。
