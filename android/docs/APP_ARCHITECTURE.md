# Android App 架构

本文说明 SRM Remoter 1.3.0 的代码职责、调用链和并发约束，供后续维护和排错使用。

## 模块职责

| 类 | 职责 |
|---|---|
| `SRMRemoterApplication` | 进程启动时应用 Material You 动态颜色 |
| `MainActivity` | 页面、设置、扫描、连接状态机、调度器、自动重连和调试日志 |
| `GamepadView` | 绘制手柄、处理多点触摸、触觉/按压反馈并输出中性控制事件 |
| `SrmProtocol` | 保存完整控制状态，编码 v4 10-bit 控制帧并解码帧流和 CRC-8 |
| `GamepadRelay` | 检测系统手柄、处理热插拔并映射 KeyEvent/MotionEvent |
| `ProControlState` | 量化双摇杆/双扳机并定义 PRO_CONTROL 按键位图 |
| `BluetoothFfe1Client` | BLE GATT FFE1 服务发现、通知、写事务和链路生命周期 |
| `BluetoothSppClient` | Bluetooth RFCOMM 连接、独立读写线程和最新控制帧槽位 |
| `BluetoothDeviceListAdapter` | 扫描列表的设备信息呈现 |
| `ControlRate` | 将设置限制到 1–100 Hz 并换算纳秒周期 |
| `StatusLightView` | 绘制带发光效果的连接状态灯 |

界面使用传统 View + Material Components，主布局在 `res/layout/activity_main.xml`；设置、
关于和调试发送由 `MainActivity` 动态构建为 Material 对话框。

## 控制调用链

```text
MotionEvent
  -> GamepadView.updateStick/updateDpad/updateControl
  -> CommandListener.onCommand("JL,..." / "BTN,..." / "SW,...")
  -> MainActivity.dispatchFrame
  -> SrmProtocol.applyControl（更新一份完整控制快照）
  -> ScheduledExecutorService（1–100 Hz）
  -> SrmProtocol.encodeControlState（13 字节 CONTROL，四轴打包为 5 字节）
  -> BluetoothFfe1Client.sendControl 或 BluetoothSppClient.sendControl
  -> FFE1 characteristic / RFCOMM OutputStream
```

触摸事件只更新内存状态，不直接为每个移动事件排队发送，因此手指快速拖动不会堵塞主线程
或堆积历史位置。周期任务发送的每一帧都是完整快照。未连接时不启动周期发送，
`dispatchFrame` 会节流本地回环预览，避免连续摇杆事件淹没 UI 日志。

DEBUG 的路径不同：`showDebugSender` 调用 `SrmProtocol.encodeDebug`，再由
`dispatchWireFrame` 作为可靠、不可覆盖的事务进入当前传输队列。它不受“下发 CONTROL”
开关影响。

实体手柄路径由 Activity 的 `dispatchGenericMotionEvent` / `dispatchKeyEvent` 进入
`GamepadRelay`。调度器只采样最新快照并直接发送 16 字节 PRO_CONTROL。HELLO 仅是可选
节点信息握手，不参与控制类型选择；接收端按 TYPE 区分 CONTROL 与 PRO_CONTROL。

## BLE GATT 写事务

`BluetoothFfe1Client` 搜索 UUID `0000FFE1-0000-1000-8000-00805F9B34FB`，要求其支持
WRITE 或 WRITE_NO_RESPONSE；若存在通知/指示和 CCCD，则先配置接收通知再报告连接成功。

- HELLO、DEBUG 等事务帧进入 FIFO `writeQueue`，必要时按 20 字节 ATT 载荷切片。
- CONTROL/PRO_CONTROL 不进入 FIFO，而是写入单个 `latestControlFrame`；新状态覆盖旧状态。
- 有 WRITE_NO_RESPONSE 时 CONTROL 优先使用它，内部以 12 ms 完成槽推进下一次写入。
- Android 返回 busy 时最多以 30 ms 间隔重试 5 次；其他拒绝会结束当前连接并上报错误。
- CONTROL 流启停时分别请求 HIGH / BALANCED connection priority。

该设计保证可靠事务不会被 CONTROL 插队，同时不会因目标频率高于物理链路吞吐而积累旧
控制帧。它保证低延迟优先，不承诺实际接收率等于目标调度率。

## SPP 读写

`BluetoothSppClient` 在后台线程建立 RFCOMM socket，优先尝试安全连接，失败后尝试不安全
连接。连接成功后启动独立读线程和写线程：普通事务进入阻塞队列，CONTROL 仍使用一个
`AtomicReference<byte[]>` 最新槽位。读线程把任意长度字节块送回主线程上的协议流解码器。

SPP 配对由 `MainActivity` 监听系统配对广播、尝试提交用户输入的 PIN，并等待 bond 状态完成；
厂商系统仍可能要求用户在系统窗口确认。

## 线程模型

| 执行环境 | 主要工作 |
|---|---|
| Android 主线程 | 触摸、界面、扫描结果合并、连接回调、协议接收展示 |
| `control-scheduler` | 按固定周期读取控制快照并提交 CONTROL |
| `gatt-writer` | GATT busy 重试和无响应写的槽位推进 |
| SPP connect/read/write 线程 | 阻塞 RFCOMM 建连和流 I/O |

蓝牙客户端通过 `connectionGeneration` 标识当前连接世代。旧 GATT 回调、延迟重试或已关闭
socket 的结果只有世代仍匹配时才能更新 UI，避免“快速扫描后立即点击”产生的生命周期竞态。
`onDestroy` 会停止调度器、关闭两种传输并注销广播。

## 连接与自动重连

连接 UI 有 `DISCONNECTED`、`CONNECTING`、`CONNECTED`、`ERROR` 四态。只有完成 FFE1
通知配置或 RFCOMM 建连才进入 CONNECTED，并发送 HELLO、启动 CONTROL 心跳和触发成功反馈。

两个自动重连选项互不替代：启动重连只在 Activity 创建后尝试保存设备；意外断联重连只
针对曾经成功建立且非主动断开的会话，按 1/2/4/8/15 秒退避。切换 BLE/SPP 会断开并删除
保存地址，防止用错误传输重连同一个 MAC。

## 日志和接收

BLE 通知或 SPP 字节流进入 `SrmProtocol.StreamDecoder`。解码器在粘包、拆包和噪声后重新
同步，只把校验通过的帧交给 UI。`MainActivity` 最多保存 500 条带时间戳记录，并以 50 ms
合并刷新界面；主界面显示最近两条，完整窗口提供筛选、复制和自动滚动。

## Material 主题、模糊和返回

`SRMRemoterApplication` 在创建时应用 Material 动态颜色；`MainActivity` 监听壁纸变化并重建
Activity，使新壁纸色重新解析。动态颜色不可用时回退到 `values/themes.xml` 和
`values-night/themes.xml` 的静态配色。

二级对话框使用系统 cross-window blur。进入时从零模糊/零 dim 动画到目标值；普通退出反向
动画。预测性返回开始后，模糊与遮罩随手势进度减弱；取消手势则恢复，提交手势才关闭窗口。
设备关闭系统窗口模糊时仍保留 dim 动画，因此功能不会依赖厂商一定支持模糊。

## 持久化与边界

`SharedPreferences` 保存上次设备地址、传输模式、两个重连开关、DEBUG 换行、屏幕常亮、
CONTROL 下发和频率。当前 Manifest 允许 Android 系统备份，且备份 XML 未排除这些偏好；
修改数据策略时应同步更新两个备份规则文件和用户指南。

输入到车辆动作的语义不属于 App。新增控制位或修改帧结构时，必须同时更新：
`SrmProtocol`、协议文档、通用 C99 固件、STM32 例程、Android/C/Python 测试和串口分析器。
