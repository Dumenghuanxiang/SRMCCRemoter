# 测试与发布

本文给出 SRM Remoter 1.3.0 的可重复验证流程。所有命令从仓库根目录运行。

## 环境

- Windows PowerShell
- Android SDK 36、Android SDK Build Tools 和 platform-tools
- JDK 21 或更新版本用于运行 Gradle；App Java 源/目标兼容级别为 11
- Gradle Wrapper 9.5.0（无需另装 Gradle）
- GCC（用于 C99 桌面测试）
- Python 3 和 `pyserial==3.5`（用于串口分析器）
- STM32CubeIDE / STM32CubeMX 及 STM32F1 HAL（仅硬件固件构建需要）

`local.properties` 中的 SDK 路径属于本机配置，不应提交。

## 自动测试

Android 单元测试、静态检查和 APK 构建：

```powershell
.\gradlew.bat testDebugUnitTest lintDebug lintRelease assembleDebug assembleRelease
```

`assembleRelease` 默认生成未签名 APK；公开发布前应由发布者在仓库外完成签名。

C99 协议、通用 MCU 行为和 STM32 HAL 模拟测试：

```powershell
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

Python 分析器测试：

```powershell
python -m pip install -r tools/requirements.txt
python -m unittest discover -s tools -p "test_*.py"
```

## 启动性能门槛

在目标真机上完成清洁安装和冷启动测试，并在发布记录中注明设备型号、Android 版本、
逐次测量值、最大值和中位数。模拟器结果只用于发现明显回退，不能替代目标真机验收。
Release APK 会合并 `app/src/main/baseline-prof.txt` 和 `startup-prof.txt`，分别为 ART 热点编译
和启动 DEX 布局提供规则；修改首屏类或启动调用链时必须同步维护两份规则并重新实测。

## 实机检查矩阵

发布前至少完成以下手工检查，并保存设备型号、Android 版本、模块固件、波特率和结果。

| 场景 | 预期结果 |
|---|---|
| 首次权限拒绝/允许 | 拒绝时有明确提示；允许后可重新扫描，不闪退 |
| BLE 扫描后立即点击 | 列表稳定，进入连接态，不因扫描回调竞态闪退 |
| BLE FFE1 收发 | 绿灯、振动和成功提示；CONTROL、PING/ACK、从机 LOG 正常 |
| 无 HELLO 的普通控制 | 连接后直接持续接收 TYPE 0x0 CONTROL |
| 无 HELLO 的手柄中继 | 连接后直接持续接收 TYPE 0x7 PRO_CONTROL |
| v3 拒绝 | v3 VTYPE 不刷新控制状态或 600 ms 超时 |
| 手柄中继/热插拔 | 双摇杆、0–255 扳机、全部标准按键正确；断开后立即全零并恢复屏幕控制 |
| 实验 SPP 未配对 | 可输入 PIN，系统确认后建立 RFCOMM；错误写入调试窗口 |
| 本地回环 | 未连接时全部控件可用，帧可见，不启动蓝牙发送 |
| 1/50/100 Hz | UI 流畅，实际频率由分析器测量，高负载时不累积旧 CONTROL |
| 断联重连关闭/开启 | 关闭时不重试；开启时仅意外断联按 1/2/4/8/15 秒退避 |
| 启动重连关闭/开启 | 行为与断联重连开关独立 |
| CONTROL 下发关闭 | DEBUG 仍可达；下位机约 600 ms 后进入安全状态 |
| 前后台与旋转约束 | 回到前台恢复调度；强制横屏；无重复扫描或连接 |
| 对话框返回手势 | 进入/退出模糊平滑；取消预测性返回可恢复窗口 |
| 明/暗色和换壁纸 | Material You 可用设备跟随系统动态色，静态主题回退正常 |

链路频率可使用：

```powershell
python tools/srm_serial_analyzer.py --list
python tools/srm_serial_analyzer.py --port COM10 --baud 9600 --realtime
```

如需形成报告，增加 `--duration 30 --csv sample.csv --json report.json`。测试 100 Hz 时，
必须区分 App 目标调度率和从机实际接收率；JDY-31 / FFE1 已知实测上限低于 70 Hz。

## Release 构建

1. 在 `app/build.gradle.kts` 增加 `versionCode`，按语义更新 `versionName`。
2. 更新 README、关于页、协议/复盘文档中的版本或行为说明。
3. 运行上面的全部自动测试和实机矩阵。
4. 清理后重新构建，避免把旧产物误作本次发布：

```powershell
.\gradlew.bat clean testDebugUnitTest lintDebug lintRelease assembleDebug assembleRelease
```

输出文件：

```text
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release.apk
```

构建日期由 Gradle 按 `Asia/Shanghai` 写入 `BuildConfig.BUILD_DATE`，关于页显示的是打包日期，
不是安装日期。

## 产物验证

```powershell
Get-FileHash app/build/outputs/apk/debug/app-debug.apk -Algorithm SHA256
Get-FileHash app/build/outputs/apk/release/app-release.apk -Algorithm SHA256
```

正式发布时记录 Release APK 的文件大小、SHA-256、`versionCode`、`versionName`、打包日期和
签名证书 SHA-256 指纹。由发布者完成签名后，需在目标设备上验证安装与升级行为。

## 开源与发布检查表

- `git status` 只包含预期改动，自动生成目录未被跟踪。
- 仓库中没有 keystore、签名属性、密码、设备日志、串口采样或个人路径文件。
- Manifest 权限、备份规则和用户指南的数据说明一致。
- 协议文档、App、C99 固件、STM32 适配和分析器使用同一协议版本。
- Debug 和 Release APK 均完成安装启动检查；发布 APK 的签名可被验证。
- 根目录包含完整的 GPL-3.0 `LICENSE`，源码分发包和仓库页面均保留许可证与版权声明。
- 发布说明列出兼容性边界，尤其是实验 SPP 和 JDY-31 / FFE1 频率上限。
