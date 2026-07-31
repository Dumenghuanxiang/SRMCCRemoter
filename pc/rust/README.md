# SRM Xbox Bridge Rust

这是 Windows Xbox 手柄中继器的 Rust 正式版。程序直接读取 XInput，通过 WinRT 连接
BLE GATT FFE1，从而发送“SRM 校内赛”v4 `PRO_CONTROL` 帧。

```text
Xbox 手柄 -> XInput -> SRM v4 PRO_CONTROL -> BLE GATT FFE1 -> 从机
```

## 功能

- 探测 4 个 XInput 槽位，只显示真实在线手柄
- BLE 扫描、RSSI 排序、FFE1 连接、写入和通知接收
- SRM v4 `HELLO`、CRC-8/ATM、流解码、`PRO_CONTROL` 和中立安全帧
- 1-100 Hz 发送频率、摇杆死区、扳机阈值和异常自动重连
- 与显示器刷新率同步的 Win32 原生界面
- 独立 CLI 工具：`--smoke-test`、`--probe`、`--stream`
- 26 项单元测试和打包自检

## 构建要求

- Windows 10/11 x64
- Rust GNU 工具链 `stable-x86_64-pc-windows-gnu`
- MinGW-w64 的 `gcc` 与 `windres` 位于 `PATH`
- 可选：UPX 位于 `PATH`，用于压缩正式二进制

在本目录运行：

```powershell
.\build.ps1
```

脚本运行全部测试、构建 GUI 与 CLI、自检 CLI，并在 UPX 可用时以 LZMA 压缩产物。

```text
target/release/srm-xbox.exe
target/release/srm-xbox-tools.exe
```

也可以直接使用 Cargo：

```powershell
cargo test --all-features --locked
cargo build --release --locked
cargo build --release --features cli-tools --locked
```

## CLI 工具

```powershell
.\target\release\srm-xbox-tools.exe --smoke-test .\smoke-report.json
.\target\release\srm-xbox-tools.exe --probe AA:BB:CC:DD:EE:FF .\probe-report.json
.\target\release\srm-xbox-tools.exe --stream AA:BB:CC:DD:EE:FF 8 .\stream-report.json
```

## 已知限制

- 当前正式版仅支持 BLE FFE1，不包含串口传输
- 多手柄场景使用系统枚举到的首个在线 XInput 手柄
- UPX 压缩可能触发部分安全软件误报；可以不安装 UPX，发布未压缩版本

## 许可证

本工程作为 SRM Remoter 的组成部分，以 GPL-3.0-only 发布。
