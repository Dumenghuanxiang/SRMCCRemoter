# SRM Remoter

SRM Remoter 是一套基于“SRM 校内赛”V4 通信协议的遥控与中继工具，包含 Android
蓝牙遥控器、Rust/Windows Xbox 手柄中继器、通用 C99 协议实现和串口诊断工具。

## 目录

```text
android/  Android 遥控器、协议文档、固件示例和诊断工具
pc/       Windows Xbox 手柄中继器（推荐 Rust 版，保留 Python 兼容版）
```

三套客户端实现均发送兼容的 V4 `CONTROL` / `PRO_CONTROL` 数据帧：Android 版支持
BLE GATT FFE1 和 Bluetooth SPP，Rust PC 版支持 BLE GATT FFE1，Python PC 兼容版支持
BLE GATT FFE1 和串口。协议细节见
[通信协议](android/docs/SRM_CAMPUS_COMPETITION_PROTOCOL.md)。

## 快速开始

- Android 遥控器：[android/README.md](android/README.md)
- PC 手柄中继器：[pc/README.md](pc/README.md)
- Rust PC 版：[pc/rust/README.md](pc/rust/README.md)
- MCU 固件接入：[android/firmware/README.md](android/firmware/README.md)

## 安全说明

仓库不包含签名密钥、凭据、本机配置、设备日志或串口采样。Android Release 构建默认
生成未签名 APK；发布者应在仓库外自行管理签名材料。

## 许可证

本项目采用 [GNU General Public License v3.0](LICENSE) 开源。
