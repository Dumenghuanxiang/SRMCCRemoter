# SRM校内赛下位机固件

本目录包含可独立移植的 C99 协议核心，以及 STM32F103 HAL 完整适配例程。

## 从哪里开始

1. 先阅读 [`../docs/SRM_CAMPUS_COMPETITION_PROTOCOL.md`](../docs/SRM_CAMPUS_COMPETITION_PROTOCOL.md)，确认帧格式和控制位。
2. STM32F103 用户直接阅读 [`stm32f103_hal/README.md`](stm32f103_hal/README.md)。
3. 将 `srm_protocol.c/.h`、`srm_mcu_example.c/.h` 和 STM32 适配层加入 CubeIDE。
4. 先用 App 默认设置发送 `PING` 验证双向链路，再实现板级输出映射。例程兼容无换行、LF 和 CRLF。

## 目录

```text
firmware/
  srm_protocol.c/.h                 v4 流式协议、CRC、两类 CONTROL 编解码
  srm_mcu_example.c/.h              消息分发、ACK/ERROR、失联保护
  stm32f103_hal/
    srm_stm32f103_port.c/.h          USART + DMA Circular HAL 适配
    README.md                        CubeMX、接线、移植和排错教学
    examples/srm_board_example.c     PWM、LED、DEBUG 教学映射
  tests/
    srm_protocol_test.c              协议向量与错误帧测试
    srm_mcu_example_test.c           通用 MCU 行为测试
    srm_stm32f103_port_test.c        DMA、超时、ACK、错误恢复测试
```

## 设计边界

- App 只发送中性的输入状态，具体动作语义由下位机 `SRM_BoardApplyControl()` 定义。
- CONTROL/PRO_CONTROL 是可覆盖的完整快照，不返回 ACK。
- v4 不接受 v3；CONTROL/PRO_CONTROL 共享四轴 10-bit 打包格式。
- HELLO 是可选握手，不能阻塞任一控制帧；DEBUG 和已实现的 HELLO 才返回 ACK/ERROR。
- App 默认给 DEBUG 文本追加 LF；命令处理必须按显式长度比较，并兼容所需的行结束符。
- 只有完整帧通过版本、长度、CRC 和字段范围校验后才更新输出。
- 600 ms 没有合法 CONTROL/PRO_CONTROL 时自动应用对应全零安全状态。
- 所有实现均使用静态内存，不依赖 malloc。

## 桌面测试

使用 GCC 可直接验证与硬件无关的代码。仓库测试还包含一个最小 STM32 HAL 模拟层，
用于验证 DMA 环形接收和 UART 错误恢复行为。以下命令从仓库根目录开始执行，第一行会
进入 `android/` 目录：

```powershell
Set-Location android
New-Item -ItemType Directory -Force build | Out-Null

gcc -std=c99 -Wall -Wextra -Werror -Ifirmware `
  firmware/srm_protocol.c firmware/tests/srm_protocol_test.c `
  -o build/srm_protocol_test.exe
.\build\srm_protocol_test.exe

gcc -std=c99 -Wall -Wextra -Werror -Ifirmware `
  firmware/srm_protocol.c firmware/srm_mcu_example.c `
  firmware/tests/srm_mcu_example_test.c `
  -o build/srm_mcu_example_test.exe
.\build\srm_mcu_example_test.exe

gcc -std=c99 -Wall -Wextra -Werror `
  -Ifirmware -Ifirmware/stm32f103_hal -Ifirmware/tests `
  firmware/srm_protocol.c firmware/srm_mcu_example.c `
  firmware/stm32f103_hal/srm_stm32f103_port.c `
  firmware/tests/srm_stm32f103_port_test.c `
  -o build/srm_stm32f103_port_test.exe
.\build\srm_stm32f103_port_test.exe
```

目标平台仍应使用 STM32CubeIDE 自己工程中的正式 STM32F1 HAL 源码完成最终构建和烧录。
