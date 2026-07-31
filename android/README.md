# SRM Remoter / SRM 遥控器

SRM Remoter 是面向“SRM校内赛”的 Android 横屏蓝牙遥控器。主界面采用双摇杆、
十字键、ABXY 和六开关布局；未连接从机时仍可通过本地回环验证控制输入和协议编码。

## 当前基线

- App 版本：1.3.0（`versionCode=5`，正式版）。
- Android：最低 API 31（Android 12），目标 API 36。
- 线协议：“SRM校内赛”v4，四轴统一 10-bit，13 字节 CONTROL + 16 字节 PRO_CONTROL。
- 手柄中继：检测 Android 系统游戏手柄，转发双摇杆、双扳机与标准按键完整状态。
- 控制调度：1–100 Hz 可调，默认 50 Hz；不对 100 Hz 设置静默限频。
- 默认传输：BLE GATT FFE1。
- 实验传输：Bluetooth 3.0 SPP，可输入配对码并使用标准 RFCOMM SPP UUID。
- 连接策略：启动时自动重连和意外断联自动重连分别配置，默认均关闭。
- 主题：Material Design 3、Material You 动态配色、窗口模糊与预测性返回。

JDY-31 / BLE FFE1 实测即使将模块 UART 提高到 115200 bps，从机 CONTROL 接收频率
仍低于 70 Hz。App 会按设置值调度，但链路忙时只保留最新控制状态，避免旧帧排队形成
持续延迟。9600 8N1 下 CONTROL 的理论线速上限约为 73.8 Hz，建议默认使用 50 Hz。

## 目录

```text
app/        Android App、单元测试和资源
docs/       线协议、开发复盘和测试说明
firmware/   通用 C99 协议层、MCU 例程和 STM32F103 HAL 适配
tools/      PC 串口采样分析工具
```

## 构建 App

构建环境需要 Android SDK 36.1 和 JDK 21 或更新版本；目标 API 仍为 36，Gradle 9.5.0
由 Wrapper 提供。
使用 Android Studio 打开仓库，或在 Windows PowerShell 中执行：

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

Debug APK 输出到：

```text
app/build/outputs/apk/debug/app-debug.apk
```

Release 构建默认生成未签名 APK。发布者应在仓库外自行配置并保管签名材料。

完整的 Android、C99、Python 测试和实机发布矩阵见
[测试与发布](docs/TESTING_AND_RELEASE.md)。

## 连接与控制

1. 主界面控件始终可操作；红、绿、黄状态灯分别表示未连接、已连接和连接异常。
2. 在设置中选择 BLE FFE1 或实验性 SPP，然后扫描附近设备并点击连接。
3. “向已连接从机下发控制帧”默认开启；关闭后 DEBUG 仍可发送，但 MCU 应在 600 ms 后进入安全状态。
4. DEBUG 默认自动追加 LF。仓库固件例程接受 `PING`、`PING\n` 和 `PING\r\n`。
5. 实验性 SPP 若导致系统蓝牙状态异常，应完全关闭 App，并在系统设置中先断开设备、再取消配对，然后恢复默认 BLE FFE1 模式。

## 下位机

完整帧格式见 [SRM校内赛通信协议](docs/SRM_CAMPUS_COMPETITION_PROTOCOL.md)。STM32F103
用户从 [HAL 适配教学](firmware/stm32f103_hal/README.md) 开始；通用 C99 文件职责和三组
桌面测试命令见 [固件说明](firmware/README.md)。

App 只发送中性的输入状态。摇杆、按键、开关和十字键如何映射到电机、舵机或其他执行
机构，必须由下位机项目定义。

## 诊断工具

```powershell
python -m pip install -r tools/requirements.txt
python -m unittest discover -s tools -p "test_*.py"
python tools/srm_serial_analyzer.py --list
python tools/srm_serial_analyzer.py --port COM10 --realtime
```

分析器可统计协议有效率、CRC 错误、序号丢失、实际频率、周期抖动和最长控制间隔。

## 文档

- [用户指南](docs/USER_GUIDE.md)
- [Android App 架构](docs/APP_ARCHITECTURE.md)
- [SRM校内赛通信协议](docs/SRM_CAMPUS_COMPETITION_PROTOCOL.md)
- [1.1 开发复盘](docs/DEVELOPMENT_RETROSPECTIVE_1.1.md)
- [测试与发布](docs/TESTING_AND_RELEASE.md)
- [通用固件说明](firmware/README.md)
- [STM32F103 HAL 适配教学](firmware/stm32f103_hal/README.md)

## 许可证

本项目采用 [GNU General Public License v3.0](../LICENSE) 开源。分发本项目或其修改版本时，
请遵守 GPL-3.0 的源码公开和许可证保留要求。
