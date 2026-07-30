"""Minimal native Windows XInput bindings and SRM mapping."""

from __future__ import annotations

import ctypes
from ctypes import wintypes
from dataclasses import dataclass
import os

from .protocol import (
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
)

ERROR_SUCCESS = 0
ERROR_DEVICE_NOT_CONNECTED = 1167

DPAD_UP = 0x0001
DPAD_DOWN = 0x0002
DPAD_LEFT = 0x0004
DPAD_RIGHT = 0x0008
START = 0x0010
BACK = 0x0020
LEFT_THUMB = 0x0040
RIGHT_THUMB = 0x0080
LEFT_SHOULDER = 0x0100
RIGHT_SHOULDER = 0x0200
A = 0x1000
B = 0x2000
X = 0x4000
Y = 0x8000


class XInputGamepad(ctypes.Structure):
    _fields_ = [
        ("buttons", wintypes.WORD),
        ("left_trigger", wintypes.BYTE),
        ("right_trigger", wintypes.BYTE),
        ("left_x", ctypes.c_short),
        ("left_y", ctypes.c_short),
        ("right_x", ctypes.c_short),
        ("right_y", ctypes.c_short),
    ]


class XInputState(ctypes.Structure):
    _fields_ = [("packet_number", wintypes.DWORD), ("gamepad", XInputGamepad)]


@dataclass(frozen=True, slots=True)
class RawGamepad:
    buttons: int
    left_trigger: int
    right_trigger: int
    left_x: int
    left_y: int
    right_x: int
    right_y: int


class ControllerDisconnected(RuntimeError):
    pass


def _load_xinput() -> ctypes.WinDLL:
    if os.name != "nt":
        raise OSError("native XInput is only available on Windows")
    errors: list[str] = []
    for library_name in ("xinput1_4.dll", "xinput1_3.dll", "xinput9_1_0.dll"):
        try:
            library = ctypes.WinDLL(library_name)
            library.XInputGetState.argtypes = [wintypes.DWORD, ctypes.POINTER(XInputState)]
            library.XInputGetState.restype = wintypes.DWORD
            return library
        except OSError as error:
            errors.append(str(error))
    raise OSError("cannot load an XInput DLL: " + "; ".join(errors))


class XInputController:
    def __init__(self, index: int = 0) -> None:
        if not 0 <= index <= 3:
            raise ValueError("XInput controller index must be 0..3")
        self.index = index
        self._library = _load_xinput()

    def read(self) -> RawGamepad:
        state = XInputState()
        result = self._library.XInputGetState(self.index, ctypes.byref(state))
        if result == ERROR_DEVICE_NOT_CONNECTED:
            raise ControllerDisconnected(f"XInput controller {self.index} is disconnected")
        if result != ERROR_SUCCESS:
            raise OSError(result, f"XInputGetState failed for controller {self.index}")
        gamepad = state.gamepad
        return RawGamepad(
            gamepad.buttons,
            gamepad.left_trigger,
            gamepad.right_trigger,
            gamepad.left_x,
            gamepad.left_y,
            gamepad.right_x,
            gamepad.right_y,
        )


def apply_deadzone(value: int, deadzone: int) -> int:
    """Remove an axial deadzone and rescale the remaining range."""
    value = max(-32767, min(32767, value))
    magnitude = abs(value)
    if magnitude <= deadzone:
        return 0
    scaled = round((magnitude - deadzone) * 32767 / (32767 - deadzone))
    return scaled if value > 0 else -scaled


def quantize_axis(value: int) -> int:
    value = max(-32767, min(32767, value))
    if value >= 0:
        return (value * 511 + 16383) // 32767
    return -((-value * 512 + 16383) // 32767)


def map_to_srm(raw: RawGamepad, *, deadzone: int, trigger_threshold: int) -> ProControlState:
    buttons = (
        (PRO_BUTTON_A if raw.buttons & A else 0)
        | (PRO_BUTTON_B if raw.buttons & B else 0)
        | (PRO_BUTTON_X if raw.buttons & X else 0)
        | (PRO_BUTTON_Y if raw.buttons & Y else 0)
        | (PRO_BUTTON_L1 if raw.buttons & LEFT_SHOULDER else 0)
        | (PRO_BUTTON_R1 if raw.buttons & RIGHT_SHOULDER else 0)
        | (PRO_BUTTON_L2 if raw.left_trigger >= trigger_threshold else 0)
        | (PRO_BUTTON_R2 if raw.right_trigger >= trigger_threshold else 0)
        | (PRO_BUTTON_THUMB_L if raw.buttons & LEFT_THUMB else 0)
        | (PRO_BUTTON_THUMB_R if raw.buttons & RIGHT_THUMB else 0)
        | (PRO_BUTTON_START if raw.buttons & START else 0)
        | (PRO_BUTTON_SELECT if raw.buttons & BACK else 0)
        | (PRO_BUTTON_DPAD_UP if raw.buttons & DPAD_UP else 0)
        | (PRO_BUTTON_DPAD_DOWN if raw.buttons & DPAD_DOWN else 0)
        | (PRO_BUTTON_DPAD_LEFT if raw.buttons & DPAD_LEFT else 0)
        | (PRO_BUTTON_DPAD_RIGHT if raw.buttons & DPAD_RIGHT else 0)
    )
    return ProControlState(
        quantize_axis(apply_deadzone(raw.left_x, deadzone)),
        quantize_axis(apply_deadzone(raw.left_y, deadzone)),
        quantize_axis(apply_deadzone(raw.right_x, deadzone)),
        quantize_axis(apply_deadzone(raw.right_y, deadzone)),
        raw.left_trigger,
        raw.right_trigger,
        buttons,
    )
