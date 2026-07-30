"""SRM Campus Competition v4 wire protocol."""

from __future__ import annotations

from dataclasses import dataclass
SYNC = b"\xA5\x5A"
VERSION = 4
TYPE_CONTROL = 0
TYPE_HELLO = 1
TYPE_DEBUG = 2
TYPE_ACK = 3
TYPE_ERROR = 4
TYPE_LOG = 5
TYPE_STATUS = 6
TYPE_PRO_CONTROL = 7
MAX_PAYLOAD = 64
PRO_BUTTON_A = 1 << 0
PRO_BUTTON_B = 1 << 1
PRO_BUTTON_X = 1 << 2
PRO_BUTTON_Y = 1 << 3
PRO_BUTTON_L1 = 1 << 4
PRO_BUTTON_R1 = 1 << 5
PRO_BUTTON_L2 = 1 << 6
PRO_BUTTON_R2 = 1 << 7
PRO_BUTTON_THUMB_L = 1 << 8
PRO_BUTTON_THUMB_R = 1 << 9
PRO_BUTTON_START = 1 << 10
PRO_BUTTON_SELECT = 1 << 11
PRO_BUTTON_MODE = 1 << 12
PRO_BUTTON_DPAD_UP = 1 << 13
PRO_BUTTON_DPAD_DOWN = 1 << 14
PRO_BUTTON_DPAD_LEFT = 1 << 15
PRO_BUTTON_DPAD_RIGHT = 1 << 16
PRO_BUTTON_VALID_MASK = (1 << 17) - 1


@dataclass(frozen=True, slots=True)
class ControlState:
    left_x: int = 0
    left_y: int = 0
    right_x: int = 0
    right_y: int = 0
    buttons: int = 0
    switches: int = 0
    dpad: int = 0

    def __post_init__(self) -> None:
        axes = (self.left_x, self.left_y, self.right_x, self.right_y)
        if any(value < -512 or value > 511 for value in axes):
            raise ValueError("axis must be in -512..511")
        if not 0 <= self.buttons <= 0x0F:
            raise ValueError("buttons must fit four bits")
        if not 0 <= self.switches <= 0x3F:
            raise ValueError("switches must fit six bits")
        if not 0 <= self.dpad <= 4:
            raise ValueError("dpad must be 0..4")


@dataclass(frozen=True, slots=True)
class Frame:
    version: int
    type: int
    sequence: int
    payload: bytes


def crc8_atm(data: bytes | bytearray | memoryview) -> int:
    crc = 0
    for value in data:
        crc ^= value
        for _ in range(8):
            crc = ((crc << 1) ^ 0x07) & 0xFF if crc & 0x80 else (crc << 1) & 0xFF
    return crc


def encode_frame(message_type: int, sequence: int, payload: bytes) -> bytes:
    if not 0 <= message_type <= 0x0F:
        raise ValueError("message type must be 0..15")
    if not 0 <= sequence <= 0xFF:
        raise ValueError("sequence must be 0..255")
    if len(payload) > MAX_PAYLOAD:
        raise ValueError("payload exceeds 64 bytes")
    body = bytes(((VERSION << 4) | message_type, sequence, len(payload))) + payload
    return SYNC + body + bytes((crc8_atm(body),))


def encode_control(state: ControlState, sequence: int) -> bytes:
    controls = state.buttons | (state.switches << 4) | (state.dpad << 10)
    payload = pack_axes(state.left_x, state.left_y, state.right_x, state.right_y)
    payload += controls.to_bytes(2, "little")
    return encode_frame(TYPE_CONTROL, sequence, payload)


@dataclass(frozen=True, slots=True)
class ProControlState:
    left_x: int = 0
    left_y: int = 0
    right_x: int = 0
    right_y: int = 0
    left_trigger: int = 0
    right_trigger: int = 0
    buttons: int = 0

    def __post_init__(self) -> None:
        axes = (self.left_x, self.left_y, self.right_x, self.right_y)
        if any(value < -512 or value > 511 for value in axes):
            raise ValueError("axis must be in -512..511")
        if not 0 <= self.left_trigger <= 255 or not 0 <= self.right_trigger <= 255:
            raise ValueError("trigger must be in 0..255")
        if self.buttons & ~PRO_BUTTON_VALID_MASK:
            raise ValueError("buttons contain reserved bits")


def pack_axes(left_x: int, left_y: int, right_x: int, right_y: int) -> bytes:
    axes = (left_x, left_y, right_x, right_y)
    if any(value < -512 or value > 511 for value in axes):
        raise ValueError("axis must be in -512..511")
    packed = (
        (left_x & 0x3FF)
        | ((left_y & 0x3FF) << 10)
        | ((right_x & 0x3FF) << 20)
        | ((right_y & 0x3FF) << 30)
    )
    return packed.to_bytes(5, "little")


def encode_pro_control(state: ProControlState, sequence: int) -> bytes:
    payload = pack_axes(state.left_x, state.left_y, state.right_x, state.right_y)
    payload += bytes((state.left_trigger, state.right_trigger))
    payload += state.buttons.to_bytes(3, "little")
    return encode_frame(TYPE_PRO_CONTROL, sequence, payload)


def encode_hello(sequence: int, *, ble: bool) -> bytes:
    capabilities = 0x06 if ble else 0x05
    return encode_frame(TYPE_HELLO, sequence, bytes((1, capabilities, 2, 0)))


class StreamDecoder:
    """Decode frames from arbitrary BLE notification or serial byte chunks."""

    def __init__(self) -> None:
        self._buffer = bytearray()

    def feed(self, data: bytes | bytearray) -> list[Frame]:
        self._buffer.extend(data)
        frames: list[Frame] = []
        while True:
            start = self._buffer.find(SYNC)
            if start < 0:
                if self._buffer[-1:] != SYNC[:1]:
                    self._buffer.clear()
                else:
                    del self._buffer[:-1]
                break
            if start:
                del self._buffer[:start]
            if len(self._buffer) < 5:
                break
            if self._buffer[2] >> 4 != VERSION or self._buffer[4] > MAX_PAYLOAD:
                del self._buffer[0]
                continue
            total = self._buffer[4] + 6
            if len(self._buffer) < total:
                break
            candidate = bytes(self._buffer[:total])
            del self._buffer[:total]
            if crc8_atm(candidate[2:-1]) != candidate[-1]:
                continue
            frames.append(
                Frame(candidate[2] >> 4, candidate[2] & 0x0F, candidate[3], candidate[5:-1])
            )
        return frames
