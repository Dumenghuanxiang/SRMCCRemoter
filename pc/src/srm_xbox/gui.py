"""Qt desktop application for the SRM Xbox bridge."""

from __future__ import annotations

import asyncio
from collections import deque
from dataclasses import dataclass
import json
from pathlib import Path
import sys
import threading
import time
from typing import Any

from bleak.backends.device import BLEDevice
from PySide6.QtCore import QThread, QTimer, Qt, Signal
from PySide6.QtGui import QColor, QIcon, QPainter, QPixmap
from PySide6.QtWidgets import (
    QApplication,
    QCheckBox,
    QComboBox,
    QFormLayout,
    QFrame,
    QGridLayout,
    QGroupBox,
    QHBoxLayout,
    QLabel,
    QMainWindow,
    QMessageBox,
    QPlainTextEdit,
    QProgressBar,
    QPushButton,
    QRadioButton,
    QSizePolicy,
    QSpinBox,
    QVBoxLayout,
    QWidget,
)

from .protocol import (
    Frame,
    PRO_BUTTON_A,
    PRO_BUTTON_B,
    PRO_BUTTON_DPAD_DOWN,
    PRO_BUTTON_DPAD_LEFT,
    PRO_BUTTON_DPAD_RIGHT,
    PRO_BUTTON_DPAD_UP,
    PRO_BUTTON_L1,
    PRO_BUTTON_L2,
    PRO_BUTTON_R1,
    PRO_BUTTON_R2,
    PRO_BUTTON_SELECT,
    PRO_BUTTON_START,
    PRO_BUTTON_THUMB_L,
    PRO_BUTTON_THUMB_R,
    PRO_BUTTON_X,
    PRO_BUTTON_Y,
    ProControlState,
    StreamDecoder,
    TYPE_ERROR,
    TYPE_LOG,
    TYPE_PRO_CONTROL,
    crc8_atm,
    encode_pro_control,
    encode_hello,
)
from .transport import (
    BleFfe1Transport,
    SerialTransport,
    discover_ble,
    resolve_ble_device,
    serial_ports,
)
from .xinput import ControllerDisconnected, XInputController, map_to_srm


@dataclass(frozen=True, slots=True)
class BridgeConfig:
    transport: str
    target: BLEDevice | str
    controller: int
    rate: int
    deadzone: int
    trigger_threshold: int
    baudrate: int
    auto_reconnect: bool


class Sequence:
    def __init__(self) -> None:
        self.value = 0

    def take(self) -> int:
        value = self.value
        self.value = (value + 1) & 0xFF
        return value


def describe_frame(frame: Frame) -> str:
    names = {1: "HELLO", 3: "ACK", 4: "ERROR", 5: "LOG", 6: "STATUS"}
    name = names.get(frame.type, f"TYPE_{frame.type}")
    if frame.type == TYPE_LOG and frame.payload:
        text = frame.payload[1:].decode("utf-8", errors="replace").rstrip()
        detail = f"level={frame.payload[0]} {text}"
    elif frame.type == TYPE_ERROR and len(frame.payload) >= 2:
        text = frame.payload[2:].decode("utf-8", errors="replace").rstrip()
        detail = f"request={frame.payload[0]} code={frame.payload[1]} {text}".rstrip()
    else:
        detail = frame.payload.hex(" ").upper()
    return f"RX {name} #{frame.sequence}: {detail}"


class BleScanThread(QThread):
    found = Signal(object)
    failed = Signal(str)

    def run(self) -> None:
        try:
            prepare_ble_thread()
            self.found.emit(asyncio.run(discover_ble(5.0)))
        except Exception as error:
            self.failed.emit(str(error))


class BridgeThread(QThread):
    status_changed = Signal(str, str)
    log_received = Signal(str)

    def __init__(self, config: BridgeConfig) -> None:
        super().__init__()
        self.config = config
        self._stop = False
        self._state_lock = threading.Lock()
        self._latest_state = ProControlState()

    @property
    def latest_state(self) -> ProControlState:
        with self._state_lock:
            return self._latest_state

    def _publish_state(self, state: ProControlState) -> None:
        with self._state_lock:
            self._latest_state = state

    def stop(self) -> None:
        self._stop = True

    def run(self) -> None:
        try:
            prepare_ble_thread()
            asyncio.run(self._run_bridge())
        except Exception as error:
            self.log_received.emit(f"后台异常: {error}")

    async def _make_transport(self, receive):
        if self.config.transport == "ble":
            target = self.config.target
            device = target if isinstance(target, BLEDevice) else await resolve_ble_device(target)
            return BleFfe1Transport(device, receive)
        return SerialTransport(str(self.config.target), self.config.baudrate, receive)

    async def _run_bridge(self) -> None:
        controller = XInputController(self.config.controller)
        sequence = Sequence()
        decoder = StreamDecoder()

        def receive(data: bytes) -> None:
            for frame in decoder.feed(data):
                self.log_received.emit(describe_frame(frame))

        while not self._stop:
            transport = None
            try:
                transport = await self._make_transport(receive)
                self.status_changed.emit("connecting", f"正在连接 {transport.description}")
                await transport.connect()
                self.status_changed.emit("connected", transport.description)
                self.log_received.emit(f"已连接 {transport.description}")
                await transport.send(
                    encode_hello(sequence.take(), ble=self.config.transport == "ble")
                )
                await self._stream(transport, controller, sequence)
            except Exception as error:
                if not self._stop:
                    self.status_changed.emit("error", str(error))
                    self.log_received.emit(f"链路错误: {error}")
            finally:
                if transport is not None:
                    await self._send_neutral(transport, sequence)
                    await transport.close()

            if self._stop or not self.config.auto_reconnect:
                break
            self.status_changed.emit("connecting", "2 秒后重新连接")
            for _ in range(20):
                if self._stop:
                    break
                await asyncio.sleep(0.1)

    async def _stream(self, transport, controller: XInputController, sequence: Sequence) -> None:
        period = 1.0 / self.config.rate
        loop = asyncio.get_running_loop()
        deadline = loop.time()
        latest_state = ProControlState()
        self._publish_state(latest_state)

        async def sample_controller() -> None:
            nonlocal latest_state
            controller_missing = False
            while not self._stop:
                try:
                    latest_state = map_to_srm(
                        controller.read(),
                        deadzone=self.config.deadzone,
                        trigger_threshold=self.config.trigger_threshold,
                    )
                    if controller_missing:
                        self.log_received.emit("XInput 手柄已重新连接")
                        controller_missing = False
                except ControllerDisconnected:
                    latest_state = ProControlState()
                    if not controller_missing:
                        self.log_received.emit("XInput 手柄断开，正在发送中立状态")
                        controller_missing = True
                self._publish_state(latest_state)
                # Keep input sampling responsive even when a BLE write waits
                # for an acknowledgement. The sender below consumes the
                # newest snapshot at the configured wire rate.
                await asyncio.sleep(0.01)

        sampler = asyncio.create_task(sample_controller())
        try:
            while not self._stop:
                if sampler.done():
                    sampler.result()
                await transport.send(encode_pro_control(latest_state, sequence.take()))
                now = loop.time()
                deadline += period
                if deadline < now - period:
                    deadline = now
                await asyncio.sleep(max(0.0, deadline - loop.time()))
        finally:
            sampler.cancel()
            await asyncio.gather(sampler, return_exceptions=True)

    async def _send_neutral(self, transport, sequence: Sequence) -> None:
        neutral = ProControlState()
        self._publish_state(neutral)
        for _ in range(3):
            try:
                await transport.send(encode_pro_control(neutral, sequence.take()))
                await asyncio.sleep(0.02)
            except Exception:
                return


class MainWindow(QMainWindow):
    def __init__(self) -> None:
        super().__init__()
        self.worker: BridgeThread | None = None
        self.scan_worker: BleScanThread | None = None
        self.ble_devices: dict[str, BLEDevice] = {}
        self.config_widgets: list[QWidget] = []
        self._displayed_state: ProControlState | None = None
        self._pending_logs: deque[str] = deque()
        self.close_after_stop = False
        self._setup_window()
        self._build_ui()
        self._refresh_controllers()
        self._transport_changed()
        self._state_timer = QTimer(self)
        self._state_timer.setTimerType(Qt.TimerType.PreciseTimer)
        self._state_timer.setInterval(20)
        self._state_timer.timeout.connect(self._refresh_state)
        self._state_timer.start()
        self._log_timer = QTimer(self)
        self._log_timer.setInterval(100)
        self._log_timer.timeout.connect(self._flush_logs)
        self._log_timer.start()

    def _setup_window(self) -> None:
        self.setWindowTitle("SRM Xbox Bridge")
        self.resize(980, 720)
        self.setMinimumSize(850, 640)
        pixmap = QPixmap(64, 64)
        pixmap.fill(QColor("#1667b2"))
        painter = QPainter(pixmap)
        painter.setBrush(QColor("#f4f6f8"))
        painter.setPen(Qt.PenStyle.NoPen)
        painter.drawRoundedRect(12, 16, 40, 32, 8, 8)
        painter.setBrush(QColor("#16835b"))
        painter.drawEllipse(26, 26, 12, 12)
        painter.end()
        self.setWindowIcon(QIcon(pixmap))

    def _build_ui(self) -> None:
        container = QWidget()
        container.setObjectName("root")
        self.setCentralWidget(container)
        main = QVBoxLayout(container)
        main.setContentsMargins(24, 18, 24, 18)
        main.setSpacing(12)

        header = QHBoxLayout()
        title_box = QVBoxLayout()
        title = QLabel("SRM Xbox Bridge")
        title.setObjectName("title")
        subtitle = QLabel("Xbox XInput → SRM 校内赛协议 v4 PRO_CONTROL → BLE FFE1 / 串口")
        subtitle.setObjectName("muted")
        title_box.addWidget(title)
        title_box.addWidget(subtitle)
        header.addLayout(title_box)
        header.addStretch()
        status_box = QVBoxLayout()
        self.status_label = QLabel("未连接")
        self.status_label.setObjectName("status")
        self.status_label.setAlignment(Qt.AlignmentFlag.AlignRight)
        self.status_detail = QLabel("请选择链路和目标设备")
        self.status_detail.setObjectName("muted")
        self.status_detail.setAlignment(Qt.AlignmentFlag.AlignRight)
        status_box.addWidget(self.status_label)
        status_box.addWidget(self.status_detail)
        header.addLayout(status_box)
        main.addLayout(header)

        connection = QGroupBox("连接")
        connection_layout = QVBoxLayout(connection)
        target_row = QHBoxLayout()
        self.ble_radio = QRadioButton("BLE FFE1")
        self.serial_radio = QRadioButton("串口")
        self.ble_radio.setChecked(True)
        self.ble_radio.toggled.connect(self._transport_changed)
        self.serial_radio.toggled.connect(self._transport_changed)
        target_row.addWidget(self.ble_radio)
        target_row.addWidget(self.serial_radio)
        target_row.addSpacing(14)
        target_row.addWidget(QLabel("目标"))
        self.target_combo = QComboBox()
        self.target_combo.setEditable(True)
        self.target_combo.setSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Fixed)
        target_row.addWidget(self.target_combo, 1)
        self.scan_button = QPushButton("扫描")
        self.scan_button.clicked.connect(self._scan)
        target_row.addWidget(self.scan_button)
        connection_layout.addLayout(target_row)

        option_row = QHBoxLayout()
        self.controller_combo = QComboBox()
        self.controller_combo.addItems(["0", "1", "2", "3"])
        option_row.addWidget(QLabel("手柄"))
        option_row.addWidget(self.controller_combo)
        self.rate_spin = self._spin(1, 100, 50, " Hz")
        option_row.addSpacing(10)
        option_row.addWidget(QLabel("发送频率"))
        option_row.addWidget(self.rate_spin)
        self.deadzone_spin = self._spin(0, 32766, 4096)
        option_row.addSpacing(10)
        option_row.addWidget(QLabel("摇杆死区"))
        option_row.addWidget(self.deadzone_spin)
        self.trigger_spin = self._spin(1, 255, 30)
        option_row.addSpacing(10)
        option_row.addWidget(QLabel("扳机阈值"))
        option_row.addWidget(self.trigger_spin)
        self.baud_combo = QComboBox()
        self.baud_combo.setEditable(True)
        self.baud_combo.addItems(["9600", "19200", "38400", "57600", "115200"])
        option_row.addSpacing(10)
        option_row.addWidget(QLabel("波特率"))
        option_row.addWidget(self.baud_combo)
        self.reconnect_check = QCheckBox("异常自动重连")
        self.reconnect_check.setChecked(True)
        option_row.addWidget(self.reconnect_check)
        option_row.addStretch()
        self.start_button = QPushButton("开始遥控")
        self.start_button.setObjectName("primary")
        self.start_button.clicked.connect(self._toggle_bridge)
        option_row.addWidget(self.start_button)
        connection_layout.addLayout(option_row)
        main.addWidget(connection)
        self.config_widgets = [
            self.ble_radio, self.serial_radio, self.target_combo, self.scan_button,
            self.controller_combo, self.rate_spin, self.deadzone_spin, self.trigger_spin,
            self.baud_combo, self.reconnect_check,
        ]

        telemetry = QGroupBox("实时输入")
        telemetry_layout = QVBoxLayout(telemetry)
        axis_grid = QGridLayout()
        self.axis_values: list[QLabel] = []
        self.axis_bars: list[QProgressBar] = []
        for index, name in enumerate(("LX", "LY", "RX", "RY")):
            row, side = divmod(index, 2)
            base = side * 3
            axis_grid.addWidget(QLabel(name), row, base)
            bar = QProgressBar()
            bar.setRange(-512, 511)
            bar.setValue(0)
            bar.setTextVisible(False)
            axis_grid.addWidget(bar, row, base + 1)
            value = QLabel("0")
            value.setObjectName("axisValue")
            value.setAlignment(Qt.AlignmentFlag.AlignRight)
            value.setFixedWidth(70)
            axis_grid.addWidget(value, row, base + 2)
            self.axis_bars.append(bar)
            self.axis_values.append(value)
        axis_grid.setColumnStretch(1, 1)
        axis_grid.setColumnStretch(4, 1)
        telemetry_layout.addLayout(axis_grid)

        digital = QHBoxLayout()
        digital.addWidget(QLabel("按键"))
        self.button_labels = [self._digital(name) for name in ("A", "B", "X", "Y")]
        for label in self.button_labels:
            digital.addWidget(label)
        digital.addSpacing(10)
        digital.addWidget(QLabel("扩展"))
        self.switch_labels = [self._digital(name) for name in
                              ("L1", "R1", "L2", "R2", "LS", "RS", "Start", "Back")]
        for label in self.switch_labels:
            digital.addWidget(label)
        digital.addSpacing(12)
        digital.addWidget(QLabel("十字键"))
        self.dpad_label = QLabel("中立")
        self.dpad_label.setObjectName("axisValue")
        self.dpad_label.setFixedWidth(54)
        self.dpad_label.setAlignment(Qt.AlignmentFlag.AlignCenter)
        digital.addWidget(self.dpad_label)
        digital.addWidget(QLabel("LT/RT"))
        self.trigger_labels = []
        for _ in range(2):
            label = QLabel("0")
            label.setObjectName("axisValue")
            label.setFixedWidth(35)
            label.setAlignment(Qt.AlignmentFlag.AlignRight)
            digital.addWidget(label)
            self.trigger_labels.append(label)
        digital.addStretch()
        telemetry_layout.addLayout(digital)
        main.addWidget(telemetry)

        log_group = QGroupBox("通信日志")
        log_layout = QVBoxLayout(log_group)
        self.log_view = QPlainTextEdit()
        self.log_view.setReadOnly(True)
        self.log_view.setMaximumBlockCount(500)
        log_layout.addWidget(self.log_view, 1)
        log_footer = QHBoxLayout()
        protocol_label = QLabel("SRM 校内赛协议 v4 · FFE1 · 16-byte PRO_CONTROL")
        protocol_label.setObjectName("muted")
        log_footer.addWidget(protocol_label)
        log_footer.addStretch()
        clear_button = QPushButton("清空日志")
        clear_button.clicked.connect(self.log_view.clear)
        log_footer.addWidget(clear_button)
        log_layout.addLayout(log_footer)
        main.addWidget(log_group, 1)
        self.setStyleSheet(STYLE_SHEET)

    @staticmethod
    def _spin(minimum: int, maximum: int, value: int, suffix: str = "") -> QSpinBox:
        spin = QSpinBox()
        spin.setRange(minimum, maximum)
        spin.setValue(value)
        spin.setSuffix(suffix)
        return spin

    @staticmethod
    def _digital(text: str) -> QLabel:
        label = QLabel(text)
        label.setProperty("active", False)
        label.setAlignment(Qt.AlignmentFlag.AlignCenter)
        label.setFixedSize(50 if len(text) > 2 else 40, 28)
        return label

    def _refresh_controllers(self) -> None:
        connected = []
        for index in range(4):
            try:
                XInputController(index).read()
                connected.append(index)
            except ControllerDisconnected:
                pass
        if connected:
            self.controller_combo.setCurrentText(str(connected[0]))
            self._log(f"检测到 XInput 手柄: {', '.join(map(str, connected))}")
        else:
            self._log("当前未检测到 XInput 手柄；连接后可直接开始")

    def _transport_changed(self) -> None:
        serial = self.serial_radio.isChecked()
        self.baud_combo.setEnabled(serial and self.worker is None)
        self.target_combo.clear()
        if serial:
            self._load_serial_targets()

    def _load_serial_targets(self) -> None:
        values = [f"{port} | {description}" for port, description in serial_ports()]
        self.target_combo.addItems(values)

    def _scan(self) -> None:
        if self.worker is not None or self.scan_worker is not None:
            return
        self._refresh_controllers()
        if self.serial_radio.isChecked():
            self.target_combo.clear()
            self._load_serial_targets()
            return
        self.scan_button.setEnabled(False)
        self.scan_button.setText("扫描中...")
        self._set_status("connecting", "正在扫描附近 BLE 设备")
        self.scan_worker = BleScanThread(self)
        self.scan_worker.found.connect(self._scan_finished)
        self.scan_worker.failed.connect(self._scan_failed)
        self.scan_worker.finished.connect(self._scan_thread_finished)
        self.scan_worker.start()

    def _scan_thread_finished(self) -> None:
        self.scan_worker = None
        self.scan_button.setEnabled(True)
        self.scan_button.setText("扫描")

    def _scan_finished(self, devices: list[BLEDevice]) -> None:
        self.ble_devices.clear()
        self.target_combo.clear()
        for device in devices:
            display = f"{device.name or '(未命名)'} | {device.address}"
            self.ble_devices[display] = device
            self.target_combo.addItem(display)
        preferred = next(
            (index for index in range(self.target_combo.count())
             if "RM_BLE" in self.target_combo.itemText(index).upper()),
            0,
        )
        if self.target_combo.count():
            self.target_combo.setCurrentIndex(preferred)
        self._set_status("idle", f"扫描完成，发现 {len(devices)} 个 BLE 设备")
        self._log(f"BLE 扫描完成，共 {len(devices)} 个设备")

    def _scan_failed(self, error: str) -> None:
        self._set_status("error", error)
        self._log(f"BLE 扫描失败: {error}")

    def _toggle_bridge(self) -> None:
        if self.worker is not None:
            self.start_button.setEnabled(False)
            self.start_button.setText("正在停止...")
            self.worker.stop()
            return
        try:
            config = self._read_config()
        except ValueError as error:
            QMessageBox.critical(self, "参数错误", str(error))
            return
        self._set_config_enabled(False)
        self.start_button.setText("停止遥控")
        self.worker = BridgeThread(config)
        self.worker.status_changed.connect(self._set_status)
        self.worker.log_received.connect(self._log)
        self.worker.finished.connect(self._bridge_finished)
        self.worker.start()

    def _read_config(self) -> BridgeConfig:
        target_text = self.target_combo.currentText().strip()
        if not target_text:
            raise ValueError("请选择或填写目标设备")
        transport = "serial" if self.serial_radio.isChecked() else "ble"
        if transport == "ble":
            target: BLEDevice | str = self.ble_devices.get(target_text, target_text)
        else:
            target = target_text.split(" | ", 1)[0].strip()
        try:
            baudrate = int(self.baud_combo.currentText())
        except ValueError as error:
            raise ValueError("波特率必须是整数") from error
        if not 300 <= baudrate <= 2_000_000:
            raise ValueError("波特率必须在 300..2000000 之间")
        return BridgeConfig(
            transport=transport,
            target=target,
            controller=int(self.controller_combo.currentText()),
            rate=self.rate_spin.value(),
            deadzone=self.deadzone_spin.value(),
            trigger_threshold=self.trigger_spin.value(),
            baudrate=baudrate,
            auto_reconnect=self.reconnect_check.isChecked(),
        )

    def _set_config_enabled(self, enabled: bool) -> None:
        for widget in self.config_widgets:
            widget.setEnabled(enabled)
        self.baud_combo.setEnabled(enabled and self.serial_radio.isChecked())

    def _bridge_finished(self) -> None:
        if self.worker is not None:
            self.worker.deleteLater()
        self.worker = None
        if self.close_after_stop:
            self.close()
            return
        self._set_config_enabled(True)
        self.start_button.setEnabled(True)
        self.start_button.setText("开始遥控")
        self._update_state(ProControlState())
        self._set_status("idle", "已停止并发送中立状态")

    def _refresh_state(self) -> None:
        worker = self.worker
        if worker is not None:
            self._update_state(worker.latest_state)

    def _update_state(self, state: ProControlState) -> None:
        previous = self._displayed_state
        self._displayed_state = state
        axes = (state.left_x, state.left_y, state.right_x, state.right_y)
        old_axes = () if previous is None else (
            previous.left_x, previous.left_y, previous.right_x, previous.right_y
        )
        for index, (label, bar, value) in enumerate(zip(self.axis_values, self.axis_bars, axes)):
            if previous is None or value != old_axes[index]:
                label.setText(str(value))
                bar.setValue(value)

        if previous is None or state.buttons != previous.buttons:
            for bit, label in zip(
                (PRO_BUTTON_A, PRO_BUTTON_B, PRO_BUTTON_X, PRO_BUTTON_Y), self.button_labels
            ):
                self._set_active(label, bool(state.buttons & bit))
            for bit, label in zip(
                (PRO_BUTTON_L1, PRO_BUTTON_R1, PRO_BUTTON_L2, PRO_BUTTON_R2,
                 PRO_BUTTON_THUMB_L, PRO_BUTTON_THUMB_R, PRO_BUTTON_START, PRO_BUTTON_SELECT),
                self.switch_labels,
            ):
                self._set_active(label, bool(state.buttons & bit))
            dpad = "".join(
                name for bit, name in ((PRO_BUTTON_DPAD_UP, "上"),
                                       (PRO_BUTTON_DPAD_DOWN, "下"),
                                       (PRO_BUTTON_DPAD_LEFT, "左"),
                                       (PRO_BUTTON_DPAD_RIGHT, "右"))
                if state.buttons & bit
            ) or "中立"
            self.dpad_label.setText(dpad)
        if previous is None or state.left_trigger != previous.left_trigger:
            self.trigger_labels[0].setText(str(state.left_trigger))
        if previous is None or state.right_trigger != previous.right_trigger:
            self.trigger_labels[1].setText(str(state.right_trigger))

    @staticmethod
    def _set_active(label: QLabel, active: bool) -> None:
        if label.property("active") == active:
            return
        label.setProperty("active", active)
        label.style().unpolish(label)
        label.style().polish(label)

    def _set_status(self, state: str, detail: str) -> None:
        labels = {"connected": "已连接", "connecting": "连接中", "error": "异常", "idle": "未连接"}
        colors = {"connected": "#16835b", "error": "#bd3131", "connecting": "#1667b2"}
        self.status_label.setText(labels.get(state, state))
        self.status_label.setStyleSheet(f"color: {colors.get(state, '#5e6b73')}; font-weight: 700")
        self.status_detail.setText(detail)

    def _log(self, message: str) -> None:
        if len(self._pending_logs) >= 256:
            self._pending_logs.popleft()
        self._pending_logs.append(message)

    def _flush_logs(self) -> None:
        if not self._pending_logs:
            return
        lines = []
        timestamp = time.strftime("%H:%M:%S")
        for _ in range(min(64, len(self._pending_logs))):
            lines.append(f"[{timestamp}] {self._pending_logs.popleft()}")
        self.log_view.appendPlainText("\n".join(lines))

    def closeEvent(self, event) -> None:  # noqa: N802 - Qt API name
        if self.worker is not None and self.worker.isRunning():
            self.close_after_stop = True
            self.worker.stop()
            self.start_button.setEnabled(False)
            self.status_detail.setText("正在发送中立状态并断开...")
            event.ignore()
            QTimer.singleShot(8000, self._force_close)
            return
        event.accept()

    def _force_close(self) -> None:
        if self.close_after_stop:
            QApplication.quit()


STYLE_SHEET = """
QWidget#root { background: #f4f6f8; color: #182026; font-family: "Microsoft YaHei UI"; font-size: 13px; }
QLabel#title { font-size: 25px; font-weight: 700; color: #182026; }
QLabel#muted { color: #5e6b73; font-size: 12px; }
QLabel#status { font-size: 14px; font-weight: 700; }
QLabel#axisValue { font-family: Consolas; font-size: 15px; font-weight: 700; }
QGroupBox { background: #ffffff; border: 1px solid #d8dfe4; border-radius: 6px; margin-top: 10px; padding-top: 10px; font-weight: 600; }
QGroupBox::title { subcontrol-origin: margin; left: 10px; padding: 0 4px; color: #394750; }
QPushButton { min-height: 29px; padding: 0 13px; border: 1px solid #b9c3ca; border-radius: 4px; background: #ffffff; }
QPushButton:hover { background: #eef3f6; border-color: #8797a1; }
QPushButton:disabled { color: #9ba6ad; background: #edf0f2; }
QPushButton#primary { color: white; background: #1667b2; border-color: #1667b2; font-weight: 700; min-width: 88px; }
QPushButton#primary:hover { background: #0e579c; }
QComboBox, QSpinBox { min-height: 28px; border: 1px solid #b9c3ca; border-radius: 4px; background: white; padding: 0 6px; }
QComboBox:focus, QSpinBox:focus { border-color: #1667b2; }
QProgressBar { height: 11px; border: 1px solid #d2d9de; border-radius: 3px; background: #edf1f3; }
QProgressBar::chunk { background: #16835b; border-radius: 2px; }
QLabel[active="false"] { color: #5e6b73; background: #e4e9ed; border-radius: 4px; font-weight: 700; }
QLabel[active="true"] { color: white; background: #1667b2; border-radius: 4px; font-weight: 700; }
QPlainTextEdit { background: #ffffff; border: 0; font-family: Consolas; font-size: 12px; selection-background-color: #9bc4e9; }
"""


def main() -> None:
    app = QApplication.instance() or QApplication([])
    app.setApplicationName("SRM Xbox Bridge")
    app.setOrganizationName("SRM")
    window = MainWindow()
    window.show()
    raise SystemExit(app.exec())


def prepare_ble_thread() -> None:
    """Ensure the current Windows worker thread can receive WinRT callbacks."""
    if sys.platform == "win32":
        from bleak.backends.winrt.util import uninitialize_sta

        uninitialize_sta()


def smoke_test_ble() -> list[BLEDevice]:
    result: list[list[BLEDevice]] = []
    errors: list[BaseException] = []

    def scan() -> None:
        try:
            prepare_ble_thread()
            result.append(asyncio.run(discover_ble(1.5)))
        except BaseException as error:
            errors.append(error)

    thread = threading.Thread(target=scan, name="srm-smoke-ble")
    thread.start()
    thread.join(10.0)
    if thread.is_alive():
        raise TimeoutError("BLE smoke scan timed out")
    if errors:
        raise errors[0]
    return result[0]


def smoke_test_main() -> None:
    """Exercise bundled GUI and Windows backends without showing a window."""
    report: dict[str, Any] = {"ok": False, "checks": {}}
    try:
        app = QApplication.instance() or QApplication([])
        window = MainWindow()
        report["checks"]["qt_window"] = window.windowTitle() == "SRM Xbox Bridge"
        report["checks"]["protocol"] = (
            crc8_atm(b"123456789") == 0xF4
            and encode_pro_control(ProControlState(), 0).hex()
            == "a55a47000a00000000000000000000d8"
        )
        xinput_slots = []
        for index in range(4):
            try:
                XInputController(index).read()
                xinput_slots.append(index)
            except ControllerDisconnected:
                pass
        report["checks"]["xinput_loaded"] = True
        report["xinput_connected"] = xinput_slots
        report["serial_ports"] = [port for port, _description in serial_ports()]
        report["checks"]["serial_backend"] = True
        devices = smoke_test_ble()
        report["ble_device_count"] = len(devices)
        report["checks"]["ble_backend"] = True
        report["ok"] = all(report["checks"].values())
        window.close()
        app.processEvents()
    except Exception as error:
        report["error"] = f"{type(error).__name__}: {error}"
    output = Path(sys.argv[sys.argv.index("--smoke-test") + 1])
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    raise SystemExit(0 if report["ok"] else 1)


if __name__ == "__main__":
    main()
