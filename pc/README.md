# SRM Xbox Bridge

这是一个 Windows PC 中继程序：直接通过系统 XInput 读取 Xbox 手柄，把完整状态编码成
“SRM校内赛”v4 `PRO_CONTROL` 帧，再经 BLE GATT FFE1 或串口发送给从机。

```text
Xbox 手柄 -> Windows XInput -> SRM v4 PRO_CONTROL -> BLE FFE1 / COM -> 蓝牙模块或 MCU
```

实现规格：Python 3.10+、原生 XInput、Bleak、PySerial。程序不依赖 pygame、SDL 或手柄
模拟驱动。

## 图形版 EXE

已经打包的图形程序位于：

```text
dist\SRMXbox.exe
```

直接双击即可运行，无需安装 Python。窗口内可以扫描 BLE 设备、选择串口和 XInput 手柄、
调整频率/死区/扳机阈值，并实时查看双摇杆、ABXY、肩键、扳机、摇杆按压、十字键和通信日志。

需要重新构建时，在 PowerShell 中运行：

```powershell
.\build_exe.ps1
```

构建脚本会安装 `requirements-build.txt` 中的 Qt for Python 和 PyInstaller，生成单文件、
无控制台窗口的 `dist\SRMXbox.exe`，并自动执行下述成品自检。自检失败时构建脚本返回失败。

当前 spec 只保留本程序实际使用的 Qt Core/Gui/Widgets、Windows 平台插件和 Windows
外观插件，并移除了软件 OpenGL、QML/Quick、PDF、虚拟键盘、未使用图像插件和翻译包；
因此成品约 27 MB，而不是完整 Qt 收集时的约 49 MB。没有启用 UPX：PyInstaller 单文件
本身已经压缩，UPX 还可能增加杀毒软件误报。以后如果 GUI 加入 QML 或运行时加载图片，
需要同步调整 `SRMXbox.spec` 的 Qt 白名单。

构建后自检（不会显示窗口）可验证成品内的 Qt、协议、XInput、串口和 Windows BLE 后端：

```powershell
.\dist\SRMXbox.exe --smoke-test .\smoke-report.json
```

## 1. 环境准备

源码运行要求 Windows 10/11、Python 3.10 或更新版本、可用的蓝牙适配器；已打包的 EXE
不需要安装 Python。EXE 目前面向 64 位 Windows 10/11，不能直接当作 Linux、macOS 或
ARM Windows 程序使用；目标机器仍需要对应的蓝牙/串口驱动和 XInput 手柄。先将 Xbox 手柄通过
USB、Xbox Wireless Adapter 或系统蓝牙连接到 PC；XInput 控制器编号通常是 `0`。

```powershell
py -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -e .
```

列出 0–3 号 XInput 槽位、附近 BLE 设备和本机串口：

```powershell
srm-xbox list
srm-xbox list --transport ble --scan-timeout 8
srm-xbox list --transport serial
```

## 2. 通过 BLE FFE1 遥控

先关闭手机 App 与从机的连接。BLE 外设一般不能同时连接手机和 PC。`--device` 可填写
设备地址、完整名称，或能唯一匹配设备名称的一段文本：

```powershell
srm-xbox run --transport ble --device RM_BLE
```

程序会扫描并解析目标设备，连接后遍历 GATT 服务寻找
`0000FFE1-0000-1000-8000-00805F9B34FB`。如果 FFE1 支持 Write Without Response 就优先
使用；只支持 Write 时会自动回退。FFE1 支持 Notify/Indicate 时也会开启通知并解析从机的
HELLO、ACK、ERROR、LOG 和 STATUS 帧。

## 3. 通过串口遥控

串口承载完全相同的连续字节流，默认参数与协议文档一致，为 9600 baud、8N1：

```powershell
srm-xbox run --transport serial --port COM10
srm-xbox run --transport serial --port COM10 --baudrate 115200
```

第二条命令只在蓝牙模块和 MCU UART 已同时改成 115200 时使用。串口模式同样读取并解析
从机上行数据。

## 4. 默认映射

| Xbox 输入 | SRM 字段 | 备注 |
|---|---|---|
| 左摇杆 X/Y | LX/LY | 左负右正，下负上正 |
| 右摇杆 X/Y | RX/RY | 左负右正，下负上正 |
| A/B/X/Y | ABXY bit 0..3 | 与协议同名 |
| LB / RB | PRO bit4 / bit5 | L1 / R1 |
| LT / RT | PRO bit6 / bit7 + Trigger 字段 | 数字位阈值默认 30，模拟值保留 0..255 |
| 左右摇杆按压 | PRO bit8 / bit9 | LS / RS |
| Menu / View | PRO bit10 / bit11 | Start / Select |
| 十字键 | PRO bit13..bit16 | 四个独立位，支持斜向组合 |

默认摇杆死区是 4096，死区外重新缩放并量化到 v4 的 `-512..511` 范围；扳机保留
XInput 的 `0..255` 原始值。可按手柄状态调整：

```powershell
srm-xbox run --transport ble --device RM_BLE --deadzone 6000 --trigger-threshold 40
```

## 5. 发送与安全行为

- 默认按 50 Hz 发送 16 字节完整 PRO_CONTROL 快照，可用 `--rate 1..100` 调整。
- 每个周期现读手柄并现发一帧，不使用控制帧 FIFO，因此链路变慢时不会补发历史状态。
- 手柄中途断开时持续发送全中立状态，重新连上后自动恢复。
- 按 `Ctrl+C` 时尽力连续发送三帧中立状态，然后断开链路。
- 链路异常默认每 2 秒重连；使用 `--reconnect-delay 0` 可在首次异常后退出。
- MCU 端仍必须保留协议规定的 600 ms 失联保护，PC 的中立帧不能替代从机安全逻辑。

JDY-31 的 FFE1 实测吞吐通常低于 70 Hz。建议先使用默认 50 Hz；9600 baud 下不要要求
从机逐帧回复 PRO_CONTROL ACK。

## 6. 验证

测试包含 Android/固件文档中的 CONTROL/PRO_CONTROL 完整协议向量、CRC-8/ATM 检查向量、
拆包、坏 CRC、扩展按键、双扳机和摇杆方向：

```powershell
python -m unittest discover -s tests -v
```

首次实机联调建议先走串口并配合安卓工程中的 `tools/srm_serial_analyzer.py` 检查实际包频、
CRC 和序号，再切换到 BLE FFE1。
