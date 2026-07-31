//! Minimal native XInput bindings and SRM mapping (port of `srm_xbox/xinput.py`).
//!
//! The XInput DLL is loaded at runtime with the same fallback order as the
//! Python version: xinput1_4.dll -> xinput1_3.dll -> xinput9_1_0.dll.

use std::ffi::c_void;

use windows::core::{s, HSTRING, PCWSTR};
use windows::Win32::Foundation::HMODULE;
use windows::Win32::System::LibraryLoader::{GetProcAddress, LoadLibraryW};

use crate::protocol::{
    PRO_BUTTON_A, PRO_BUTTON_B, PRO_BUTTON_DPAD_DOWN, PRO_BUTTON_DPAD_LEFT, PRO_BUTTON_DPAD_RIGHT,
    PRO_BUTTON_DPAD_UP, PRO_BUTTON_L1, PRO_BUTTON_L2, PRO_BUTTON_R1, PRO_BUTTON_R2,
    PRO_BUTTON_SELECT, PRO_BUTTON_START, PRO_BUTTON_THUMB_L, PRO_BUTTON_THUMB_R, PRO_BUTTON_X,
    PRO_BUTTON_Y, ProControlState,
};

const ERROR_SUCCESS: u32 = 0;
const ERROR_DEVICE_NOT_CONNECTED: u32 = 1167;

pub const DPAD_UP: u16 = 0x0001;
pub const DPAD_DOWN: u16 = 0x0002;
pub const DPAD_LEFT: u16 = 0x0004;
pub const DPAD_RIGHT: u16 = 0x0008;
pub const START: u16 = 0x0010;
pub const BACK: u16 = 0x0020;
pub const LEFT_THUMB: u16 = 0x0040;
pub const RIGHT_THUMB: u16 = 0x0080;
pub const LEFT_SHOULDER: u16 = 0x0100;
pub const RIGHT_SHOULDER: u16 = 0x0200;
pub const A: u16 = 0x1000;
pub const B: u16 = 0x2000;
pub const X: u16 = 0x4000;
pub const Y: u16 = 0x8000;

#[repr(C)]
struct XInputGamepad {
    buttons: u16,
    left_trigger: u8,
    right_trigger: u8,
    left_x: i16,
    left_y: i16,
    right_x: i16,
    right_y: i16,
}

#[repr(C)]
struct XInputState {
    packet_number: u32,
    gamepad: XInputGamepad,
}

type XInputGetStateFn = unsafe extern "system" fn(u32, *mut XInputState) -> u32;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct RawGamepad {
    pub buttons: u16,
    pub left_trigger: u8,
    pub right_trigger: u8,
    pub left_x: i16,
    pub left_y: i16,
    pub right_x: i16,
    pub right_y: i16,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum XInputError {
    Disconnected,
    WindowsError(u32),
    LoadFailed(String),
}

impl std::fmt::Display for XInputError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            XInputError::Disconnected => write!(f, "XInput controller is disconnected"),
            XInputError::WindowsError(code) => write!(f, "XInputGetState failed with code {code}"),
            XInputError::LoadFailed(message) => write!(f, "cannot load an XInput DLL: {message}"),
        }
    }
}

impl std::error::Error for XInputError {}

pub struct XInputController {
    index: u32,
    get_state: XInputGetStateFn,
    /// Keep the loaded library alive for the lifetime of the controller.
    _module: HMODULE,
}

impl XInputController {
    pub fn new(index: u32) -> std::result::Result<Self, XInputError> {
        if index > 3 {
            return Err(XInputError::LoadFailed(format!(
                "XInput controller index must be 0..3, got {index}"
            )));
        }
        let mut errors = Vec::new();
        for name in ["xinput1_4.dll", "xinput1_3.dll", "xinput9_1_0.dll"] {
            let wide = HSTRING::from(name);
            unsafe {
                match LoadLibraryW(PCWSTR(wide.as_ptr())) {
                    Ok(module) => {
                        let proc = GetProcAddress(module, s!("XInputGetState"));
                        if let Some(proc) = proc {
                            let get_state: XInputGetStateFn =
                                std::mem::transmute(proc as *const c_void);
                            return Ok(Self {
                                index,
                                get_state,
                                _module: module,
                            });
                        }
                        errors.push(format!("{name}: XInputGetState not exported"));
                    }
                    Err(error) => errors.push(format!("{name}: {error}")),
                }
            }
        }
        Err(XInputError::LoadFailed(errors.join("; ")))
    }

    pub fn read(&self) -> std::result::Result<RawGamepad, XInputError> {
        let mut state = XInputState {
            packet_number: 0,
            gamepad: XInputGamepad {
                buttons: 0,
                left_trigger: 0,
                right_trigger: 0,
                left_x: 0,
                left_y: 0,
                right_x: 0,
                right_y: 0,
            },
        };
        let result = unsafe { (self.get_state)(self.index, &mut state) };
        if result == ERROR_DEVICE_NOT_CONNECTED {
            return Err(XInputError::Disconnected);
        }
        if result != ERROR_SUCCESS {
            return Err(XInputError::WindowsError(result));
        }
        let gamepad = state.gamepad;
        Ok(RawGamepad {
            buttons: gamepad.buttons,
            left_trigger: gamepad.left_trigger,
            right_trigger: gamepad.right_trigger,
            left_x: gamepad.left_x,
            left_y: gamepad.left_y,
            right_x: gamepad.right_x,
            right_y: gamepad.right_y,
        })
    }
}

/// Remove an axial deadzone and rescale the remaining range.
/// Uses Python's round-half-even semantics to stay byte-identical.
pub fn apply_deadzone(value: i16, deadzone: i32) -> i16 {
    let value = value.clamp(-32767, 32767);
    let magnitude = value.unsigned_abs() as i32;
    if magnitude <= deadzone {
        return 0;
    }
    let scaled = py_round((magnitude - deadzone) as f64 * 32767.0 / (32767 - deadzone) as f64);
    if value < 0 {
        -scaled.clamp(0, 32767) as i16
    } else {
        scaled.clamp(0, 32767) as i16
    }
}

fn py_round(value: f64) -> i64 {
    let floor = value.floor();
    let fraction = value - floor;
    if fraction < 0.5 {
        floor as i64
    } else if fraction > 0.5 {
        (floor + 1.0) as i64
    } else if (floor as i64) % 2 == 0 {
        floor as i64
    } else {
        (floor + 1.0) as i64
    }
}

pub fn quantize_axis(value: i16) -> i16 {
    let value = value.clamp(-32767, 32767) as i32;
    if value >= 0 {
        ((value as i64 * 511 + 16383) / 32767) as i16
    } else {
        -(((-(value as i64)) * 512 + 16383) / 32767) as i16
    }
}

pub fn map_to_srm(raw: &RawGamepad, deadzone: i32, trigger_threshold: u8) -> ProControlState {
    let b = raw.buttons;
    let buttons = (if b & A != 0 { PRO_BUTTON_A } else { 0 })
        | (if b & B != 0 { PRO_BUTTON_B } else { 0 })
        | (if b & X != 0 { PRO_BUTTON_X } else { 0 })
        | (if b & Y != 0 { PRO_BUTTON_Y } else { 0 })
        | (if b & LEFT_SHOULDER != 0 { PRO_BUTTON_L1 } else { 0 })
        | (if b & RIGHT_SHOULDER != 0 { PRO_BUTTON_R1 } else { 0 })
        | (if raw.left_trigger >= trigger_threshold { PRO_BUTTON_L2 } else { 0 })
        | (if raw.right_trigger >= trigger_threshold { PRO_BUTTON_R2 } else { 0 })
        | (if b & LEFT_THUMB != 0 { PRO_BUTTON_THUMB_L } else { 0 })
        | (if b & RIGHT_THUMB != 0 { PRO_BUTTON_THUMB_R } else { 0 })
        | (if b & START != 0 { PRO_BUTTON_START } else { 0 })
        | (if b & BACK != 0 { PRO_BUTTON_SELECT } else { 0 })
        | (if b & DPAD_UP != 0 { PRO_BUTTON_DPAD_UP } else { 0 })
        | (if b & DPAD_DOWN != 0 { PRO_BUTTON_DPAD_DOWN } else { 0 })
        | (if b & DPAD_LEFT != 0 { PRO_BUTTON_DPAD_LEFT } else { 0 })
        | (if b & DPAD_RIGHT != 0 { PRO_BUTTON_DPAD_RIGHT } else { 0 });
    ProControlState {
        left_x: quantize_axis(apply_deadzone(raw.left_x, deadzone)),
        left_y: quantize_axis(apply_deadzone(raw.left_y, deadzone)),
        right_x: quantize_axis(apply_deadzone(raw.right_x, deadzone)),
        right_y: quantize_axis(apply_deadzone(raw.right_y, deadzone)),
        left_trigger: raw.left_trigger,
        right_trigger: raw.right_trigger,
        buttons,
    }
}


#[cfg(test)]
mod tests {
    use super::*;
    use crate::protocol::{
        PRO_BUTTON_B, PRO_BUTTON_DPAD_LEFT, PRO_BUTTON_DPAD_UP, PRO_BUTTON_L1, PRO_BUTTON_L2,
        PRO_BUTTON_R1, PRO_BUTTON_R2, PRO_BUTTON_SELECT, PRO_BUTTON_START, PRO_BUTTON_THUMB_L,
        PRO_BUTTON_THUMB_R, PRO_BUTTON_X, PRO_BUTTON_Y,
    };

    fn raw(buttons: u16, left_trigger: u8, right_trigger: u8, lx: i16, ly: i16, rx: i16, ry: i16) -> RawGamepad {
        RawGamepad {
            buttons,
            left_trigger,
            right_trigger,
            left_x: lx,
            left_y: ly,
            right_x: rx,
            right_y: ry,
        }
    }

    #[test]
    fn deadzone_is_zero_and_full_range_is_preserved() {
        assert_eq!(apply_deadzone(4096, 4096), 0);
        assert_eq!(apply_deadzone(-4096, 4096), 0);
        assert_eq!(apply_deadzone(32767, 4096), 32767);
        assert_eq!(apply_deadzone(-32768, 4096), -32767);
        assert_eq!(quantize_axis(-32767), -512);
        assert_eq!(quantize_axis(32767), 511);
    }

    #[test]
    fn abxy_bits_match_protocol() {
        let state = map_to_srm(&raw(A | B | X | Y, 0, 0, 0, 0, 0, 0), 0, 30);
        assert_eq!(
            state.buttons,
            PRO_BUTTON_A | PRO_BUTTON_B | PRO_BUTTON_X | PRO_BUTTON_Y
        );
    }

    #[test]
    fn six_switch_mapping() {
        let state = map_to_srm(
            &raw(
                LEFT_SHOULDER | RIGHT_SHOULDER | BACK | START | LEFT_THUMB | RIGHT_THUMB,
                30,
                255,
                0,
                0,
                0,
                0,
            ),
            0,
            30,
        );
        assert_eq!(
            state.buttons,
            PRO_BUTTON_L1 | PRO_BUTTON_R1 | PRO_BUTTON_L2 | PRO_BUTTON_R2 | PRO_BUTTON_THUMB_L
                | PRO_BUTTON_THUMB_R | PRO_BUTTON_SELECT | PRO_BUTTON_START
        );
        assert_eq!((state.left_trigger, state.right_trigger), (30, 255));
    }

    #[test]
    fn vertical_direction_wins_for_diagonal_dpad() {
        let state = map_to_srm(&raw(DPAD_UP | DPAD_LEFT, 0, 0, 0, 0, 0, 0), 0, 30);
        assert_eq!(state.buttons, PRO_BUTTON_DPAD_UP | PRO_BUTTON_DPAD_LEFT);
    }

    #[test]
    fn xinput_y_direction_matches_protocol() {
        let state = map_to_srm(&raw(0, 0, 0, 0, 20000, 0, -20000), 0, 30);
        assert!(state.left_y > 0);
        assert!(state.right_y < 0);
    }
}



