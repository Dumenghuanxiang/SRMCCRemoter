# SRM Remoter 1.3.0 更新说明

本版本以 SRM Remoter 1.1 为比较基线，是包含正式签名的稳定版本。

## 主要更新

- 新增游戏手柄中继模式。在设置中开启后，App 会检测 Android 系统识别的
  Gamepad/Joystick，并转发双摇杆、双扳机、ABXY、肩键、摇杆按压、Start、Select、
  Mode/Home 和四向 D-pad。手柄断开或退出模式时会发送全零安全状态并恢复屏幕输入。
- 升级“SRM校内赛”线协议到 v4，新增 `PRO_CONTROL`。四轴统一采用有符号 10-bit 精度，
  以 5 字节打包；普通 `CONTROL` 为 13 字节，`PRO_CONTROL` 为 16 字节，扳机使用
  `uint8`。
- `HELLO` 改为可选握手。普通控制帧和专业手柄帧均可直接发送，接收端通过帧 TYPE
  `0x0` / `0x7` 自动选择解析器。
- 重做设置界面：手柄中继开关移入设置页，设置列表可使用完整纵向空间，确认按钮恢复到
  右下角的独立圆角样式。
- 顶部状态新增输入来源，显示为“屏幕输入”或“手柄输入”；状态灯改用更明亮的颜色和
  圆形径向渐变，不再出现方形光晕裁切。
- 加入 Baseline Profile 和 Startup Profile，并把首屏后的蓝牙、偏好设置和监听器初始化
  延后，减少冷启动主线程工作。
- 同步更新 Android、便携 C99 协议库、STM32F103 HAL 适配、PC 串口分析器、测试向量和
  移植文档。

## 重要兼容性说明

- v4 不兼容 v3。从机必须先升级到本版本附带的 v4 协议实现，再使用 1.3.0 App。
- v4 接收端不应依赖 `HELLO` 才接收控制帧；必须根据 TYPE 直接区分 `CONTROL` 和
  `PRO_CONTROL`。
- 最低系统版本仍为 Android 12（API 31）。Bluetooth 3.0 SPP 仍属于实验功能。
- 若设备当前安装的是 Debug 签名包，需要先卸载，再安装正式签名 APK；相同正式证书的
  后续版本可以直接覆盖升级。

## 正式产物

- 文件：`SRMRemoter-1.3.0.apk`
- `versionCode`：5
- `versionName`：1.3.0
- APK 大小及 SHA-256：见 GitHub Release 附件说明。
- 签名证书：`CN=SRMRemoter Release, O=SRMRemoter, C=CN`
- 证书 SHA-256：`696EA4C2C7EAC11B6327B802F42F77C465FA308385ADB1F8E019FDF6917AEA0C`
- APK Signature Scheme v2 验证通过，Release APK 内置 API 31+ Baseline Profile。

## 验证结果

- 小米 23116PN5BC（shennong，Android 16 / API 36）真机启动门槛通过；最终签名产物的
  首次启动、逐次冷启动和汇总数据见 GitHub Release。
- Android：JUnit、lint、Debug APK、R8 Release APK 构建通过。
- Python 串口分析器：11 项测试通过。
- C99：协议、通用 MCU 和 STM32F103 HAL 三组测试均以
  `-std=c99 -Wall -Wextra -Werror` 通过。

## 已知限制

- JDY-31 / BLE FFE1 实测控制帧接收上限仍低于 70 Hz；建议从默认 50 Hz 开始测试。
- 手柄中继会选择系统枚举到的第一个 Gamepad/Joystick；多手柄场景暂不提供手动选择。
- Bluetooth 3.0 SPP 的配对和稳定性受 Android 厂商蓝牙栈及从机固件影响。
