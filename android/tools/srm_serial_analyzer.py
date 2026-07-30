#!/usr/bin/env python3
"""Read and evaluate SRM Campus Competition protocol data from a serial port."""

from __future__ import annotations

import argparse
import csv
import json
import math
import statistics
import struct
import sys
import time
from collections import deque
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import BinaryIO, Iterable, TextIO

SYNC = b"\xA5\x5A"
VERSION = 4
MAX_PAYLOAD = 64
CONTROL_TYPE = 0
PRO_CONTROL_TYPE = 7
DEBUG_TYPE = 2
CONTROL_PAYLOAD_LENGTH = 7
PRO_CONTROL_PAYLOAD_LENGTH = 10
TARGET_PERIOD_S = 0.020
FAILSAFE_S = 0.600


def crc8_atm(data: bytes | bytearray) -> int:
    crc = 0
    for value in data:
        crc ^= value
        for _ in range(8):
            crc = ((crc << 1) ^ 0x07) & 0xFF if crc & 0x80 else (crc << 1) & 0xFF
    return crc


@dataclass(frozen=True)
class Frame:
    timestamp: float
    version: int
    frame_type: int
    sequence: int
    payload: bytes


@dataclass(frozen=True)
class ControlState:
    left_x: int
    left_y: int
    right_x: int
    right_y: int
    buttons: int
    switches: int
    dpad: int


@dataclass(frozen=True)
class ProControlState:
    left_x: int
    left_y: int
    right_x: int
    right_y: int
    left_trigger: int
    right_trigger: int
    buttons: int


class StreamDecoder:
    """Buffered decoder that can recover after noise, truncation, and bad frames."""

    def __init__(self) -> None:
        self.buffer = bytearray()
        self.total_bytes = 0
        self.noise_bytes = 0
        self.bad_version = 0
        self.bad_length = 0
        self.bad_crc = 0
        self.valid_frames = 0

    def feed(self, data: bytes, timestamp: float | None = None) -> list[Frame]:
        if not data:
            return []
        self.total_bytes += len(data)
        self.buffer.extend(data)
        frames: list[Frame] = []
        received_at = time.monotonic() if timestamp is None else timestamp

        while True:
            sync_index = self.buffer.find(SYNC)
            if sync_index < 0:
                keep = 1 if self.buffer.endswith(SYNC[:1]) else 0
                discarded = len(self.buffer) - keep
                self.noise_bytes += discarded
                if discarded:
                    del self.buffer[:discarded]
                break
            if sync_index:
                self.noise_bytes += sync_index
                del self.buffer[:sync_index]
            if len(self.buffer) < 5:
                break

            vtype = self.buffer[2]
            if vtype >> 4 != VERSION:
                self.bad_version += 1
                del self.buffer[0]
                continue
            payload_length = self.buffer[4]
            if payload_length > MAX_PAYLOAD:
                self.bad_length += 1
                del self.buffer[0]
                continue
            wire_length = payload_length + 6
            if len(self.buffer) < wire_length:
                break

            wire = self.buffer[:wire_length]
            if crc8_atm(wire[2:-1]) != wire[-1]:
                self.bad_crc += 1
                del self.buffer[0]
                continue

            frames.append(Frame(received_at, vtype >> 4, vtype & 0x0F,
                                wire[3], bytes(wire[5:-1])))
            self.valid_frames += 1
            del self.buffer[:wire_length]
        return frames


def decode_control(frame: Frame) -> ControlState | None:
    if (frame.version != VERSION or frame.frame_type != CONTROL_TYPE
            or len(frame.payload) != CONTROL_PAYLOAD_LENGTH):
        return None
    left_x, left_y, right_x, right_y = unpack_axes(frame.payload)
    controls = struct.unpack_from("<H", frame.payload, 5)[0]
    dpad = (controls >> 10) & 0x07
    if controls & 0xE000 or dpad > 4:
        return None
    return ControlState(
        left_x, left_y, right_x, right_y,
        controls & 0x0F, (controls >> 4) & 0x3F, dpad,
    )


def decode_pro_control(frame: Frame) -> ProControlState | None:
    if (frame.version != VERSION or frame.frame_type != PRO_CONTROL_TYPE
            or len(frame.payload) != PRO_CONTROL_PAYLOAD_LENGTH):
        return None
    left_x, left_y, right_x, right_y = unpack_axes(frame.payload)
    left_trigger, right_trigger = frame.payload[5:7]
    buttons = int.from_bytes(frame.payload[7:10], "little")
    if buttons & ~0x01FFFF:
        return None
    return ProControlState(
        left_x, left_y, right_x, right_y, left_trigger, right_trigger, buttons,
    )


def unpack_axes(payload: bytes) -> tuple[int, int, int, int]:
    packed = int.from_bytes(payload[:5], "little")
    axes = []
    for shift in (0, 10, 20, 30):
        value = (packed >> shift) & 0x03FF
        axes.append(value - 0x0400 if value & 0x0200 else value)
    return axes[0], axes[1], axes[2], axes[3]


class Analyzer:
    def __init__(self, decoder: StreamDecoder, expected: ControlState | None, axis_tolerance: int) -> None:
        self.decoder = decoder
        self.expected = expected
        self.axis_tolerance = axis_tolerance
        self.started_at: float | None = None
        self.ended_at: float | None = None
        self.previous_sequence: int | None = None
        self.sequence_lost = 0
        self.sequence_duplicates = 0
        self.sequence_out_of_order = 0
        self.type_counts: dict[int, int] = {}
        self.invalid_controls = 0
        self.invalid_pro_controls = 0
        self.controls: list[tuple[float, int, ControlState]] = []
        self.pro_controls: list[tuple[float, int, ProControlState]] = []
        self.intervals: list[float] = []
        self.pro_intervals: list[float] = []
        self.expected_matches = 0

    def accept(self, frame: Frame) -> ControlState | ProControlState | None:
        if self.started_at is None:
            self.started_at = frame.timestamp
        self.ended_at = frame.timestamp
        self.type_counts[frame.frame_type] = self.type_counts.get(frame.frame_type, 0) + 1
        self._track_sequence(frame.sequence)
        if frame.frame_type == PRO_CONTROL_TYPE:
            state = decode_pro_control(frame)
            if state is None:
                self.invalid_pro_controls += 1
                return None
            if self.pro_controls:
                self.pro_intervals.append(frame.timestamp - self.pro_controls[-1][0])
            self.pro_controls.append((frame.timestamp, frame.sequence, state))
            return state
        if frame.frame_type != CONTROL_TYPE:
            return None
        state = decode_control(frame)
        if state is None:
            self.invalid_controls += 1
            return None
        if self.controls:
            self.intervals.append(frame.timestamp - self.controls[-1][0])
        self.controls.append((frame.timestamp, frame.sequence, state))
        if self.expected is not None and self._matches_expected(state):
            self.expected_matches += 1
        return state

    def _track_sequence(self, sequence: int) -> None:
        if self.previous_sequence is None:
            self.previous_sequence = sequence
            return
        delta = (sequence - self.previous_sequence) & 0xFF
        if delta == 0:
            self.sequence_duplicates += 1
        elif delta < 128:
            self.sequence_lost += delta - 1
            self.previous_sequence = sequence
        else:
            self.sequence_out_of_order += 1

    def _matches_expected(self, state: ControlState) -> bool:
        assert self.expected is not None
        axes = ("left_x", "left_y", "right_x", "right_y")
        return all(abs(getattr(state, name) - getattr(self.expected, name)) <= self.axis_tolerance for name in axes) \
            and state.buttons == self.expected.buttons \
            and state.switches == self.expected.switches \
            and state.dpad == self.expected.dpad

    def report(self, capture_seconds: float) -> dict[str, object]:
        intervals_ms = [value * 1000.0 for value in self.intervals]
        pro_intervals_ms = [value * 1000.0 for value in self.pro_intervals]
        received = self.decoder.valid_frames
        rejected = self.decoder.bad_version + self.decoder.bad_length + self.decoder.bad_crc
        expected_sequence = received + self.sequence_lost
        control_count = len(self.controls)
        control_span = self.controls[-1][0] - self.controls[0][0] if control_count > 1 else 0.0
        report: dict[str, object] = {
            "capture": {
                "seconds": round(capture_seconds, 3),
                "bytes": self.decoder.total_bytes,
                "noise_bytes": self.decoder.noise_bytes,
            },
            "protocol_accuracy": {
                "valid_frames": received,
                "rejected_candidates": rejected,
                "crc_errors": self.decoder.bad_crc,
                "version_errors": self.decoder.bad_version,
                "length_errors": self.decoder.bad_length,
                "candidate_valid_percent": _percent(received, received + rejected),
                "sequence_inferred_lost": self.sequence_lost,
                "sequence_duplicates": self.sequence_duplicates,
                "sequence_out_of_order": self.sequence_out_of_order,
                "sequence_delivery_percent": _percent(received, expected_sequence),
            },
            "control_stability": {
                "valid_controls": control_count,
                "invalid_controls": self.invalid_controls,
                "observed_rate_hz": round((control_count - 1) / control_span, 3) if control_span > 0 else None,
                "period_mean_ms": _round_or_none(statistics.fmean(intervals_ms) if intervals_ms else None),
                "period_stdev_ms": _round_or_none(statistics.pstdev(intervals_ms) if intervals_ms else None),
                "period_p95_ms": _round_or_none(_percentile(intervals_ms, 0.95)),
                "max_gap_ms": _round_or_none(max(intervals_ms) if intervals_ms else None),
                "periods_over_30ms": sum(value > 30.0 for value in intervals_ms),
                "failsafe_gaps_over_600ms": sum(value > FAILSAFE_S * 1000.0 for value in intervals_ms),
                "target_period_ms": TARGET_PERIOD_S * 1000.0,
            },
            "pro_control_stability": self._stability_report(
                self.pro_controls, pro_intervals_ms, self.invalid_pro_controls,
            ),
            "frame_types": {str(key): value for key, value in sorted(self.type_counts.items())},
            "axis_observation": self._axis_observation(),
            "pro_axis_observation": self._pro_axis_observation(),
        }
        if self.expected is not None:
            report["reference_accuracy"] = {
                "expected": asdict(self.expected),
                "axis_tolerance": self.axis_tolerance,
                "matching_controls": self.expected_matches,
                "matching_percent": _percent(self.expected_matches, control_count),
            }
        return report

    @staticmethod
    def _stability_report(
        controls: list[tuple[float, int, ProControlState]],
        intervals_ms: list[float],
        invalid_controls: int,
    ) -> dict[str, object]:
        count = len(controls)
        span = controls[-1][0] - controls[0][0] if count > 1 else 0.0
        return {
            "valid_controls": count,
            "invalid_controls": invalid_controls,
            "observed_rate_hz": round((count - 1) / span, 3) if span > 0 else None,
            "period_mean_ms": _round_or_none(statistics.fmean(intervals_ms) if intervals_ms else None),
            "period_stdev_ms": _round_or_none(statistics.pstdev(intervals_ms) if intervals_ms else None),
            "period_p95_ms": _round_or_none(_percentile(intervals_ms, 0.95)),
            "max_gap_ms": _round_or_none(max(intervals_ms) if intervals_ms else None),
            "periods_over_30ms": sum(value > 30.0 for value in intervals_ms),
            "failsafe_gaps_over_600ms": sum(value > FAILSAFE_S * 1000.0 for value in intervals_ms),
            "target_period_ms": TARGET_PERIOD_S * 1000.0,
        }

    def _axis_observation(self) -> dict[str, object]:
        result: dict[str, object] = {}
        for name in ("left_x", "left_y", "right_x", "right_y"):
            values = [getattr(item[2], name) for item in self.controls]
            result[name] = {
                "min": min(values) if values else None,
                "max": max(values) if values else None,
                "mean": _round_or_none(statistics.fmean(values) if values else None),
                "stdev": _round_or_none(statistics.pstdev(values) if values else None),
            }
        return result

    def _pro_axis_observation(self) -> dict[str, object]:
        result: dict[str, object] = {}
        for name in ("left_x", "left_y", "right_x", "right_y",
                     "left_trigger", "right_trigger"):
            values = [getattr(item[2], name) for item in self.pro_controls]
            result[name] = {
                "min": min(values) if values else None,
                "max": max(values) if values else None,
                "mean": _round_or_none(statistics.fmean(values) if values else None),
                "stdev": _round_or_none(statistics.pstdev(values) if values else None),
            }
        return result


def _percent(numerator: int, denominator: int) -> float | None:
    return round(numerator * 100.0 / denominator, 3) if denominator else None


def _round_or_none(value: float | None) -> float | None:
    return round(value, 3) if value is not None and math.isfinite(value) else None


def _percentile(values: list[float], fraction: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    position = (len(ordered) - 1) * fraction
    low = math.floor(position)
    high = math.ceil(position)
    if low == high:
        return ordered[low]
    return ordered[low] + (ordered[high] - ordered[low]) * (position - low)


def parse_expected(value: str) -> ControlState:
    try:
        numbers = [int(part.strip(), 0) for part in value.split(",")]
    except ValueError as exc:
        raise argparse.ArgumentTypeError("expected state must contain integers") from exc
    if len(numbers) != 7:
        raise argparse.ArgumentTypeError("expected state needs lx,ly,rx,ry,buttons,switches,dpad")
    state = ControlState(*numbers)
    if any(axis < -512 or axis > 511 for axis in numbers[:4]):
        raise argparse.ArgumentTypeError("expected axes must be within -512..511")
    if not 0 <= state.buttons <= 15 or not 0 <= state.switches <= 63 or not 0 <= state.dpad <= 4:
        raise argparse.ArgumentTypeError("expected controls are out of range")
    return state


def format_realtime_state(
    state: ControlState,
    sequence: int,
    window_packets: int | None,
    rate_window_s: float,
    decoder: StreamDecoder,
    sequence_lost: int,
) -> str:
    button_names = "".join(name if state.buttons & (1 << bit) else "-" for bit, name in enumerate("ABXY"))
    switch_names = "".join(str(bit + 1) if state.switches & (1 << bit) else "-" for bit in range(6))
    dpad_names = ("中", "上", "下", "左", "右")
    rate = (
        f"{window_packets:3d}pkt/{rate_window_s:g}s={window_packets / rate_window_s:5.1f}Hz"
        if window_packets is not None else f" --pkt/{rate_window_s:g}s= --.-Hz"
    )
    return (
        f"SEQ={sequence:03d}  LX={state.left_x:6d} LY={state.left_y:6d} "
        f"RX={state.right_x:6d} RY={state.right_y:6d}  "
        f"ABXY={button_names} SW={switch_names} D={dpad_names[state.dpad]}  "
        f"RATE={rate} CRC={decoder.bad_crc:4d} LOST={sequence_lost:4d}"
    )


def format_realtime_pro_state(
    state: ProControlState,
    sequence: int,
    window_packets: int | None,
    rate_window_s: float,
    decoder: StreamDecoder,
    sequence_lost: int,
) -> str:
    names = ("A", "B", "X", "Y", "L1", "R1", "L2", "R2", "LS", "RS",
             "START", "SELECT", "MODE", "UP", "DOWN", "LEFT", "RIGHT")
    pressed = "+".join(name for bit, name in enumerate(names)
                       if state.buttons & (1 << bit)) or "-"
    rate = (
        f"{window_packets:3d}pkt/{rate_window_s:g}s={window_packets / rate_window_s:5.1f}Hz"
        if window_packets is not None else f" --pkt/{rate_window_s:g}s= --.-Hz"
    )
    return (
        f"PRO SEQ={sequence:03d} LX={state.left_x:4d} LY={state.left_y:4d} "
        f"RX={state.right_x:4d} RY={state.right_y:4d} "
        f"LT={state.left_trigger:3d} RT={state.right_trigger:3d} BTN={pressed} "
        f"RATE={rate} CRC={decoder.bad_crc:4d} LOST={sequence_lost:4d}"
    )
def format_debug_frame(frame: Frame) -> str:
    """Render a DEBUG payload without allowing it to alter the terminal layout."""
    text = frame.payload.decode("utf-8", errors="replace")
    escaped: list[str] = []
    for char in text:
        codepoint = ord(char)
        if char == "\n":
            escaped.append("\\n")
        elif char == "\r":
            escaped.append("\\r")
        elif char == "\t":
            escaped.append("\\t")
        elif codepoint < 0x20 or codepoint == 0x7F:
            escaped.append(f"\\x{codepoint:02X}")
        else:
            escaped.append(char)
    content = "".join(escaped) or "<empty>"
    return f"[DEBUG #{frame.sequence:03d} len={len(frame.payload):02d}] {content}"


def open_csv(path: Path | None) -> tuple[TextIO | None, csv.writer | None]:
    if path is None:
        return None, None
    handle = path.open("w", encoding="utf-8", newline="")
    writer = csv.writer(handle)
    writer.writerow(["frame_type", "version", "elapsed_s", "sequence", "left_x", "left_y",
                     "right_x", "right_y", "left_trigger", "right_trigger",
                     "buttons", "switches", "dpad"])
    return handle, writer


def analyze_stream(
    stream: BinaryIO,
    duration: float,
    expected: ControlState | None,
    axis_tolerance: int,
    csv_path: Path | None,
    quiet: bool,
    realtime: bool = False,
    rate_window_s: float = 1.0,
) -> dict[str, object]:
    decoder = StreamDecoder()
    analyzer = Analyzer(decoder, expected, axis_tolerance)
    csv_handle, csv_writer = open_csv(csv_path)
    started = time.monotonic()
    next_status = started + 1.0
    realtime_line = ""
    realtime_control_times: deque[float] = deque()
    try:
        while duration <= 0 or time.monotonic() - started < duration:
            # Timestamp the byte that completes each frame. Reading large chunks would
            # assign one timestamp to several frames and manufacture timing jitter.
            chunk = stream.read(1)
            now = time.monotonic()
            for frame in decoder.feed(chunk, now):
                state = analyzer.accept(frame)
                if realtime and frame.frame_type == DEBUG_TYPE:
                    if realtime_line:
                        print(f"\r{' ' * len(realtime_line)}\r", end="")
                    print(format_debug_frame(frame))
                    if realtime_line:
                        print(realtime_line, end="", flush=True)
                if state is not None and csv_writer is not None:
                    if isinstance(state, ProControlState):
                        csv_writer.writerow(["PRO_CONTROL", frame.version, f"{now - started:.6f}",
                                             frame.sequence, state.left_x, state.left_y,
                                             state.right_x, state.right_y, state.left_trigger,
                                             state.right_trigger, state.buttons, "", ""])
                    else:
                        csv_writer.writerow(["CONTROL", frame.version, f"{now - started:.6f}",
                                             frame.sequence, state.left_x, state.left_y,
                                             state.right_x, state.right_y, "", "",
                                             state.buttons, state.switches, state.dpad])
                if state is not None and realtime:
                    realtime_control_times.append(now)
                    cutoff = now - rate_window_s
                    while realtime_control_times and realtime_control_times[0] <= cutoff:
                        realtime_control_times.popleft()
                    window_packets = (
                        len(realtime_control_times)
                        if now - started >= rate_window_s else None
                    )
                    if isinstance(state, ProControlState):
                        realtime_line = format_realtime_pro_state(
                            state, frame.sequence, window_packets, rate_window_s,
                            decoder, analyzer.sequence_lost,
                        )
                    else:
                        realtime_line = format_realtime_state(
                            state, frame.sequence, window_packets, rate_window_s,
                            decoder, analyzer.sequence_lost,
                        )
                    print(f"\r{realtime_line}", end="", flush=True)
            if not quiet and not realtime and now >= next_status:
                latest = analyzer.controls[-1][2] if analyzer.controls else None
                rate = len(analyzer.controls) / max(now - started, 0.001)
                suffix = f" last={latest}" if latest else ""
                print(f"\rframes={decoder.valid_frames} controls={len(analyzer.controls)} rate={rate:.1f}Hz crc={decoder.bad_crc}{suffix}", end="", flush=True)
                next_status = now + 1.0
    except KeyboardInterrupt:
        pass
    finally:
        if csv_handle is not None:
            csv_handle.close()
    elapsed = time.monotonic() - started
    if not quiet or realtime:
        print()
    return analyzer.report(elapsed)


def serial_ports() -> Iterable[object]:
    try:
        from serial.tools import list_ports
    except ImportError as exc:
        raise SystemExit("缺少 pyserial，请运行: python -m pip install -r tools/requirements.txt") from exc
    return list_ports.comports()


def auto_port() -> str:
    ports = list(serial_ports())
    for port in ports:
        text = f"{port.device} {port.description} {port.hwid}".lower()
        if "jlink" in text or "j-link" in text or "1366:" in text:
            return port.device
    if len(ports) == 1:
        return ports[0].device
    choices = ", ".join(port.device for port in ports) or "none"
    raise SystemExit(f"未能唯一确定 J-Link 串口（检测到: {choices}），请用 --port COMx 指定")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="SRM v4 串口解析与准确性/稳定性测评")
    parser.add_argument("--port", help="串口名；省略时自动选择 J-Link CDC")
    parser.add_argument("--baud", type=int, default=9600, help="波特率，默认 9600")
    parser.add_argument("--duration", type=float, help="采样秒数；0 表示直到 Ctrl+C（普通模式默认 30 秒）")
    parser.add_argument("--csv", type=Path, help="保存有效 CONTROL 样本")
    parser.add_argument("--json", type=Path, help="保存 JSON 测评报告")
    parser.add_argument("--expected", type=parse_expected, metavar="LX,LY,RX,RY,BTN,SW,DPAD", help="可选真值，用于计算状态匹配准确率")
    parser.add_argument("--axis-tolerance", type=int, default=2, help="10-bit 真值轴容差，默认 2")
    parser.add_argument("--rate-window", type=float, default=1.0, metavar="SECONDS", help="实时频率的滑动计数窗口，默认 1 秒")
    parser.add_argument("--list", action="store_true", help="列出串口后退出")
    display = parser.add_mutually_exclusive_group()
    display.add_argument("--realtime", action="store_true", help="逐帧刷新完整遥控状态，默认运行到 Ctrl+C")
    display.add_argument("--quiet", action="store_true", help="采样期间不显示状态")
    return parser


def main() -> int:
    args = build_parser().parse_args()
    if args.duration is None:
        args.duration = 0.0 if args.realtime else 30.0
    if args.duration < 0 or args.axis_tolerance < 0 or args.rate_window <= 0:
        raise SystemExit("--duration/--axis-tolerance 不能为负数，--rate-window 必须大于 0")
    if args.list:
        for port in serial_ports():
            print(f"{port.device}\t{port.description}\t{port.hwid}")
        return 0

    try:
        import serial
    except ImportError as exc:
        raise SystemExit("缺少 pyserial，请运行: python -m pip install -r tools/requirements.txt") from exc
    port = args.port or auto_port()
    print(f"读取 {port} @ {args.baud} 8N1，采样 {args.duration or '无限'} 秒")
    try:
        with serial.Serial(port, args.baud, bytesize=8, parity="N", stopbits=1, timeout=0.1) as stream:
            report = analyze_stream(
                stream, args.duration, args.expected, args.axis_tolerance,
                args.csv, args.quiet, args.realtime, args.rate_window,
            )
    except serial.SerialException as exc:
        raise SystemExit(f"无法打开串口 {port}：端口可能被其他程序占用或设备已断开（{exc}）") from exc
    rendered = json.dumps(report, ensure_ascii=False, indent=2)
    print(rendered)
    if args.json:
        args.json.write_text(rendered + "\n", encoding="utf-8")
        print(f"报告已写入 {args.json}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
