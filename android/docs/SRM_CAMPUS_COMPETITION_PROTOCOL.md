# SRM校内赛通信协议 / SRM Campus Competition Protocol

状态：已确定，可用于 App 与单片机联调  
协议名称：SRM校内赛  
英文名称：SRM Campus Competition  
线协议版本：4

传输方式：BLE GATT FFE1（默认）或 Bluetooth 3.0 SPP（实验性）

> 版本 4 的 `CONTROL` 与 `PRO_CONTROL` 统一使用四轴 10-bit 打包格式，不兼容版本 3。
> `HELLO` 是可选的节点信息握手，不是控制帧前置条件。接收端必须直接根据 TYPE `0x0`
> 或 `0x7` 判断并解码普通控制或专业手柄控制。

## 1. 设计目标

- 控制帧目标频率可由 App 在 1–100 Hz 范围内设置，默认 50 Hz；实际频率受传输链路与从机串口带宽限制。
- JDY-31 / BLE FFE1 实测即使将串口波特率提高至 115200 bps，从机实际接收频率仍低于 70 Hz；App 仍按 1–100 Hz 设置值调度控制帧，不会静默限频。链路忙时发送队列仅保留最新控制状态，异常断联后可按设置自动重连。
- 设置页可暂停向已连接从机下发 CONTROL/PRO_CONTROL，默认开启。暂停后 DEBUG 仍可发送，但 MCU 会在 600 ms 后进入安全状态。
- 每个控制帧包含双摇杆、ABXY、6 个开关和十字键的完整状态。
- 手柄中继模式转发双摇杆、双线性扳机、17 个标准按键/方向键；四轴统一使用有符号 10-bit，扳机使用 `uint8_t`。
- 支持拆包、粘包、噪声后的重新同步和 CRC 校验。
- App 默认按照 RM_BLE 的设备链路使用 BLE GATT `FFE1` 特征；设置中可切换实验性 Bluetooth 3.0 SPP。
- 从机在通信中断时自动进入安全状态。
- MCU 实现不使用动态内存。

## 2. 9600 bps 带宽预算

UART 使用 `9600, 8N1`。每个字节包含 1 个起始位、8 个数据位和 1 个停止位，因此：

```text
可用字节率 = 9600 / 10 = 960 bytes/s
CONTROL 帧长度 = 13 bytes
PRO_CONTROL 帧长度 = 16 bytes
CONTROL 理论上限 = 960 / 13 = 73.8 frames/s
PRO_CONTROL 理论上限 = 960 / 16 = 60 frames/s
默认控制频率 = 50 frames/s（App 可设置 1–100 Hz）
CONTROL 占用 = 13 * 50 = 650 bytes/s = 67.7%
App -> MCU 剩余带宽 = 310 bytes/s

PRO_CONTROL @ 50 Hz = 16 * 50 = 800 bytes/s = 83.3%
PRO_CONTROL 剩余带宽 = 160 bytes/s
```

App 按设置中的目标频率发送完整 `CONTROL` 帧，默认周期为 20 ms（50 Hz）。不要在
从机端要求每个 `CONTROL` 或 `PRO_CONTROL` 帧返回 ACK。控制过程中应限制 App 下发 DEBUG 的频率；
最大 DEBUG 帧会在 9600 bps 串口上产生可见排队延迟。

GATT/FFE1 传输时，两类控制帧优先使用 `Write Without Response`。如果链路暂时低于
目标频率，无论目标频率如何，发送端都只保留尚未写出的最新控制快照，不能按
FIFO 堆积历史控制帧；HELLO、DEBUG 等低频消息仍使用可靠队列。这样短时拥塞表现为
丢弃过期快照，而不是不断增加遥控延迟。

CONTROL 将四个有符号 10-bit 轴连续打包为 5 字节，再加 2 字节 Controls 和 6 字节
通用开销，组成 13 字节完整帧。该格式保留完整的同步头、序号、长度和 CRC。

PRO_CONTROL 复用相同的 5 字节四轴字段，再加 2 个 `uint8_t` 扳机和 24 位按键位图，
正好占 10 字节 Payload，完整帧 16 字节。它不复用 CONTROL 的 16 位控件域，
避免丢失肩键、摇杆按压、Start/Select/Mode 或斜向 D-pad 组合。

## 3. 物理与蓝牙传输

App 每次只启用一种传输方式。设置中的“实验性 Bluetooth 3.0 SPP”关闭时使用 BLE FFE1，开启时使用经典蓝牙 SPP。两种方式承载完全相同的“SRM校内赛”（SRM Campus Competition）协议连续字节流，MCU 解析器不需要区分蓝牙类型。

### 3.1 BLE FFE1 串口

- App 使用 BLE Scan 发现设备，并通过 `connectGatt()` 建立连接。
- 服务发现完成后遍历全部 GATT 服务，寻找特征 UUID
  `0000FFE1-0000-1000-8000-00805F9B34FB`。
- 若 FFE1 支持 Notify/Indicate，App 通过 CCCD
  `00002902-0000-1000-8000-00805F9B34FB` 开启从机上行通知。
- App 将长数据按 20 字节 ATT Payload 分片写入 FFE1；从机接收端必须把通知/写入片段视为连续字节流。
- 蓝牙模块到 MCU 的 UART 默认设置为 `9600 baud, 8 data bits, no parity, 1 stop bit`；若改为 `115200`，模块与 MCU 必须同时修改。
- 不启用软件流控，不能假设一次 BLE 回调等于一个完整的“SRM校内赛”协议帧。

### 3.2 Bluetooth 3.0 SPP（实验性）

- App 使用经典蓝牙 Discovery，并同时显示系统中已经配对的设备。
- 未配对设备会先要求输入配对码；默认提示值为 `1234`。App 尝试向 Android 配对流程提交该 PIN，部分厂商系统仍会显示系统配对窗口，用户需在系统窗口中再次确认。
- 配对成功后使用标准 Serial Port Profile UUID：
  `00001101-0000-1000-8000-00805F9B34FB`。
- App 先尝试安全 RFCOMM Socket；失败后再尝试非安全 RFCOMM Socket，并在调试窗口保留两次尝试的具体错误。
- RFCOMM `connect()`、读取和写入均在后台线程执行。建立连接前必须停止设备发现，避免 Discovery 降低连接成功率和吞吐量。
- SPP 提供连续双向字节流，不存在 ATT 20 字节分片限制；接收端仍必须按流式方式寻找 `A5 5A`、读取长度并校验 CRC。
- 配对成功不等于 RFCOMM 已连接。只有 App 显示绿色“已连接”且调试窗口记录 `[SPP] 已连接` 后才能认为串口链路可用。

### 3.3 App 连接与重连语义

- “启动时自动重连”只在 App 启动时尝试上次保存的设备，默认关闭。
- “意外断联后自动重连”只在本次会话曾连接成功、且断开并非用户主动操作时生效，默认关闭；重试间隔依次为 1、2、4、8、15 秒，之后保持 15 秒。
- 用户主动断开、切换 BLE/SPP 或开始手动扫描时会取消待执行的自动重连。
- 每次连接拥有独立生命周期；旧 GATT/RFCOMM 回调必须被忽略，不能影响新连接。
- 以上是当前 App 行为，不属于线协议要求；其他上位机可以采用不同策略。

## 4. SRM校内赛协议帧格式 / SRM Campus Competition Frame Format

所有多字节整数使用小端序。固定头共 5 字节，CRC 1 字节。

| 偏移 | 长度 | 字段 | 说明 |
|---:|---:|---|---|
| 0 | 1 | SYNC1 | 固定 `0xA5` |
| 1 | 1 | SYNC2 | 固定 `0x5A` |
| 2 | 1 | VTYPE | 高 4 位版本固定为 `4`，低 4 位消息类型 |
| 3 | 1 | SEQ | `0..255` 循环递增 |
| 4 | 1 | LEN | Payload 长度，`0..64` |
| 5 | LEN | PAYLOAD | 类型对应的数据 |
| 5+LEN | 1 | CRC8 | CRC-8/ATM |

总帧长度为 `LEN + 6`。版本不是 4 或长度非法时，应丢弃当前候选帧并重新寻找 `A5 5A`。

### 4.1 CRC-8/ATM

- 多项式：`0x07`
- 初值：`0x00`
- RefIn / RefOut：false
- XorOut：`0x00`
- 校验范围：从 `VTYPE` 到 Payload 最后一个字节，不包含两个同步字节和 CRC 本身。
- 标准检查向量：ASCII `123456789` 的 CRC 为 `0xF4`。

### 4.2 完整示例

v4 全中立 CONTROL，SEQ=`0x00`：

```text
A5 5A 40 00 07 00 00 00 00 00 00 00 3F
```

状态 `LX=-512, LY=511, RX=-193, RY=365, B+Y 按下, S1+S6 开启, 左方向`，SEQ=`0x2A`：

```text
A5 5A 40 2A 07 00 FE F7 73 5B 1A 0E 98
```

App 的可选 BLE HELLO，SEQ=`0x00`：

```text
A5 5A 41 00 04 01 06 02 00 54
```

v4 PRO_CONTROL 黄金向量，SEQ=`0x2C`：

```text
A5 5A 47 2C 0A 00 FE F7 2F 40 1F FF 69 35 01 54
```

## 5. 消息类型

| TYPE | 名称 | 方向 | Payload |
|---:|---|---|---|
| `0x0` | CONTROL | App -> MCU | 7 字节普通遥控完整状态 |
| `0x1` | HELLO | 双向 | 角色、能力、实现版本 |
| `0x2` | DEBUG | App -> MCU | 原始 UTF-8，最多 64 字节 |
| `0x3` | ACK | MCU -> App | 请求 SEQ、状态 |
| `0x4` | ERROR | MCU -> App | 请求 SEQ、错误码、可选详情 |
| `0x5` | LOG | MCU -> App | 日志级别、UTF-8 文本 |
| `0x6` | STATUS | MCU -> App | 电池、电气状态和信号信息 |
| `0x7` | PRO_CONTROL | App -> MCU | 10 字节专业游戏手柄完整状态 |

未知类型必须通过 CRC 和长度检查。接收端可忽略，或对管理方向请求返回“不支持”错误；
未知类型绝不能刷新控制超时或改变执行机构。

## 6. CONTROL 完整状态

Payload 固定 7 字节：

| Payload 偏移 | 类型 | 名称 | 取值 |
|---:|---|---|---|
| 0..4 | packed 40-bit LE | Axes | `LX, LY, RX, RY` 四个有符号 10-bit 轴 |
| 5..6 | `uint16_t LE` | Controls | 按键、开关和十字键压缩位域 |

四轴范围统一为 `-512..511`，使用 10-bit 二进制补码。令 `U(x) = x & 0x3FF`，则：

```text
P = U(LX) | (U(LY) << 10) | (U(RX) << 20) | (U(RY) << 30)
Payload[0..4] = P 的低 40 bit，小端序
```

因此字节边界固定为：Payload[0] 是 LX bit0..7；Payload[1] 是 LX bit8..9 加 LY bit0..5；
Payload[2] 是 LY bit6..9 加 RX bit0..3；Payload[3] 是 RX bit4..9 加 RY bit0..1；
Payload[4] 是 RY bit2..9。所有实现必须按位打包，不得把每轴扩展为 16 位后发送。

Controls 位分配：

| bit | 字段 | 说明 |
|---:|---|---|
| 0..3 | Buttons | bit0=A, bit1=B, bit2=X, bit3=Y |
| 4..9 | Switches | S1..S6 |
| 10..12 | D-pad | 0=中立, 1=上, 2=下, 3=左, 4=右 |
| 13..15 | Reserved | 必须为 0 |

规则：

- 每个轴解包为有符号 `-512..511`；这 1024 个码值全部有效。
- Controls 的 bit13..bit15 必须为 0。
- D-pad 大于 4 时整帧控制状态无效。
- CONTROL 是完整快照，不是增量事件。校验通过后可以一次性替换当前状态。
- CONTROL 不返回 ACK，也不要求先交换 HELLO。

## 7. PRO_CONTROL 专业游戏手柄状态（v4）

Payload 固定 10 字节，完整帧固定 16 字节：

| Payload 偏移 | 类型 | 名称 | 取值 |
|---:|---|---|---|
| 0..4 | packed 40-bit LE | Axes | 与 CONTROL 完全相同的四轴 10-bit 字段 |
| 5 | `uint8_t` | Left Trigger | `0..255`，释放到完全按下 |
| 6 | `uint8_t` | Right Trigger | `0..255`，释放到完全按下 |
| 7..9 | 24-bit LE bitfield | Buttons | 标准按键完整位图，低字节在前 |

Buttons 位分配：

| bit | 字段 | bit | 字段 |
|---:|---|---:|---|
| 0 | A（南） | 1 | B（东） |
| 2 | X（西） | 3 | Y（北） |
| 4 | L1 | 5 | R1 |
| 6 | L2 数字按键 | 7 | R2 数字按键 |
| 8 | Left Stick Press | 9 | Right Stick Press |
| 10 | Start | 11 | Select/Back |
| 12 | Mode/Home | 13 | D-pad Up |
| 14 | D-pad Down | 15 | D-pad Left |
| 16 | D-pad Right | 17..23 | Reserved，必须为 0 |

规则：

- 四轴必须使用第 6 节规定的同一 5 字节位序和 `-512..511` 语义。
- 扳机是无符号线性量；不得按有符号数解释 `128..255`。
- D-pad 使用四个独立 bit，允许斜向组合；同时报告 Hat Axis 和 KeyEvent 的手柄应合并去重。
- L2/R2 数字位表示 Android 实际收到的按钮事件，线性深度始终以对应 Trigger 字段为准。
- PRO_CONTROL 是完整快照、不返回 ACK，也不要求先交换 HELLO。

## 8. 可选管理握手与调试消息

### HELLO (`0x1`)

固定 4 字节：`ROLE, CAPS, IMPL_MAJOR, IMPL_MINOR`。

- ROLE：1=App，2=MCU。
- CAPS bit0：当前链路是 SPP 时置 1，否则置 0。
- CAPS bit1：当前链路是 BLE FFE1 时置 1，否则置 0。
- CAPS bit2：节点支持 v4 和 PRO_CONTROL。
- CAPS bit3..bit7：保留，必须为 0。
- App 的 BLE HELLO 为 `01 06 02 00`，SPP HELLO 为 `01 05 02 00`。
- MCU 可回复自己的 HELLO，例如 BLE 为 `02 06 02 00`，并另发 ACK。
- HELLO 完全可选。发送端可以不发，接收端也可以只实现控制帧和 600 ms 失联保护。
- CONTROL 与 PRO_CONTROL 都不得等待 HELLO/ACK；接收端只按 TYPE `0x0` / `0x7` 分发。
- 如果实现 HELLO，请求需要 ACK；HELLO 失败或丢失不影响任一控制类型。

### DEBUG (`0x2`)

Payload 是原始 UTF-8 字节，不包含字符串结束符。App 限制为 64 字节，并默认在文本末尾没有 LF 时追加 `\n`；可在设置中关闭自动换行。换行字节属于 Payload，也计入 64 字节限制。命令式 MCU 处理器建议同时接受无换行、LF 和 CRLF，但不能笼统删除 Payload 内部的其他空白。DEBUG 需要从机返回 ACK 或 ERROR。

### ACK (`0x3`)

固定 2 字节：`REQUEST_SEQ, STATUS`。STATUS 为 0 表示成功。

### ERROR (`0x4`)

最少 2 字节：`REQUEST_SEQ, ERROR_CODE`，其后可跟 UTF-8 简短说明。

建议错误码：

| 错误码 | 含义 |
|---:|---|
| 1 | 不支持的消息类型 |
| 2 | Payload 长度错误 |
| 3 | 参数范围错误 |
| 4 | 当前状态不允许执行 |
| 5 | 内部执行失败 |

### LOG (`0x5`)

首字节为级别，剩余最多 63 字节为 UTF-8：0=Debug，1=Info，2=Warning，3=Error。

在 9600 bps 下，MCU 日志应限速。运动过程中建议不超过 10 条/秒或 200 bytes/s。

### STATUS (`0x6`)

建议固定 4 字节：`BATTERY_MV_LE16, FLAGS, RSSI_INT8`。FLAGS 由具体硬件项目定义。

## 9. 失联保护

- MCU 记录最后一个有效 CONTROL 或 PRO_CONTROL 帧时间。
- 超过 600 ms 未收到有效控制帧：按最近使用的控制类型应用对应全零状态。
- CRC 错误、范围错误或未知版本不能刷新超时计时器。
- 恢复收到任一有效控制帧后可立即恢复控制，无需重新握手。
- 若某些开关控制不可瞬时断开的负载，应在产品层单独定义安全状态，不要删除协议超时机制。

## 10. MCU 接入步骤

参考实现：

- `firmware/srm_protocol.h`
- `firmware/srm_protocol.c`
- `firmware/srm_mcu_example.h`
- `firmware/srm_mcu_example.c`（带逐步中文注释的完整下位机接入例程）
- `firmware/tests/srm_protocol_test.c`
- `firmware/tests/srm_mcu_example_test.c`
- `firmware/stm32f103_hal/srm_stm32f103_port.h`
- `firmware/stm32f103_hal/srm_stm32f103_port.c`
- `firmware/stm32f103_hal/README.md`（STM32F103 CubeMX、DMA、接线与联调教学）
- `firmware/stm32f103_hal/examples/srm_board_example.c`（PWM、LED 和 DEBUG 映射示例）

BLE 模块的 UART 收到字节后，将每个字节送入同一个解析器：

```c
static srm_parser_t parser;
static uint32_t last_control_ms;

void protocol_init(void) {
    srm_parser_init(&parser);
}

void protocol_rx_byte(uint8_t value) {
    srm_frame_t frame;
    srm_parse_result_t result = srm_parser_push(&parser, value, &frame);
    if (result != SRM_PARSE_FRAME) return;

    if (frame.type == SRM_TYPE_CONTROL) {
        srm_control_state_t state;
        if (srm_decode_control(&frame, &state)) {
            apply_control_atomically(&state);
            last_control_ms = platform_millis();
        }
    } else if (frame.type == SRM_TYPE_PRO_CONTROL) {
        srm_pro_control_state_t state;
        if (srm_decode_pro_control(&frame, &state)) {
            apply_pro_control_atomically(&state);
            last_control_ms = platform_millis();
        }
    } else {
        handle_management_frame(&frame);
    }
}

void protocol_periodic(void) {
    if ((uint32_t)(platform_millis() - last_control_ms) > 600u) {
        apply_safe_state();
    }
}
```

解析出的 `frame.payload` 指针只保证在下一次调用 `srm_parser_push()` 前有效。需要异步处理时，先复制 Payload。

## 11. 部署检查表

1. 蓝牙模块与 MCU UART 使用完全一致的波特率和 8N1 参数，首次联调建议 9600。
2. 默认 BLE 模式要求模块提供 FFE1 可写特征及上行通知；实验性 SPP 模式要求 Android 侧能够建立标准 RFCOMM SPP Socket。
3. MCU 启动时初始化解析器和安全输出状态。
4. 控制状态只在 CRC、长度和范围全部正确时应用。
5. 实现 600 ms 失联保护。
6. 不实现 HELLO 时仍能直接按 TYPE 接收 CONTROL 与 PRO_CONTROL。
7. 已实现的 HELLO 和 DEBUG 返回 ACK/ERROR，CONTROL/PRO_CONTROL 不返回 ACK。
8. 日志限速，不能挤占控制接收处理。
9. 使用提供的 C99 测试确认移植时字节符号和 CRC 未变化。
10. 使用 App 默认 DEBUG 设置发送 `PING`，确认带 LF 的 Payload 也能得到 ACK。

## 12. PC 串口采样与测评

仓库提供 `tools/srm_serial_analyzer.py`，可通过 J-Link CDC UART 读取同一连续字节流，
解析 CONTROL/PRO_CONTROL 并分别统计 CRC、非法字段、序号丢失、实际频率、周期抖动和
600 ms 失联间隔。

```powershell
python -m pip install -r tools/requirements.txt
python tools/srm_serial_analyzer.py --list
python tools/srm_serial_analyzer.py --port COM10 --duration 30 `
  --csv control_samples.csv --json serial_report.json
```

实时联调时使用 `--realtime`。该模式逐帧刷新四轴、扳机、按键、开关、方向、滑动窗口包频率、
CRC 错误和序号丢失；收到 DEBUG (`TYPE=0x2`) 时，以 `[DEBUG #序号 len=长度] 内容`
格式另起一行显示，然后恢复当前控制状态行。模式默认持续运行到按下 `Ctrl+C`：

```powershell
python tools/srm_serial_analyzer.py --port COM10 --realtime
```

频率默认按最近 1 秒内收到的有效 CONTROL/PRO_CONTROL 包数计算，而不是使用相邻帧 `dt`。可用
`--rate-window 2` 改成最近 2 秒窗口，以获得更平滑的控制频率：

```powershell
python tools/srm_serial_analyzer.py --port COM10 --realtime --rate-window 2
```

也可以同时指定 `--duration 60`、`--csv` 或 `--json`，进行限时实时观察并保留数据。

若测试时将遥控器保持在一个已知状态，可增加真值参数。例如四轴中立、全部按键释放：

```powershell
python tools/srm_serial_analyzer.py --port COM10 --duration 30 `
  --expected 0,0,0,0,0,0,0 --axis-tolerance 2
```

未提供 `--expected` 时，“准确性”只表示线协议候选帧通过率和序号交付率；这能测量链路
完整性，但不能证明摇杆物理位置精度。物理精度必须在已知输入真值下用 `--expected` 测量。
