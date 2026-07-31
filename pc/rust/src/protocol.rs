//! SRM Campus Competition v4 wire protocol (Rust port of `srm_xbox/protocol.py`).
//!
//! Wire vectors in the tests are identical to the Python suite and to the
//! Android documentation / firmware test vectors.

pub const SYNC: [u8; 2] = [0xA5, 0x5A];
pub const VERSION: u8 = 4;
pub const TYPE_CONTROL: u8 = 0;
pub const TYPE_HELLO: u8 = 1;
pub const TYPE_DEBUG: u8 = 2;
pub const TYPE_ACK: u8 = 3;
pub const TYPE_ERROR: u8 = 4;
pub const TYPE_LOG: u8 = 5;
pub const TYPE_STATUS: u8 = 6;
pub const TYPE_PRO_CONTROL: u8 = 7;
pub const MAX_PAYLOAD: usize = 64;

pub const PRO_BUTTON_A: u32 = 1 << 0;
pub const PRO_BUTTON_B: u32 = 1 << 1;
pub const PRO_BUTTON_X: u32 = 1 << 2;
pub const PRO_BUTTON_Y: u32 = 1 << 3;
pub const PRO_BUTTON_L1: u32 = 1 << 4;
pub const PRO_BUTTON_R1: u32 = 1 << 5;
pub const PRO_BUTTON_L2: u32 = 1 << 6;
pub const PRO_BUTTON_R2: u32 = 1 << 7;
pub const PRO_BUTTON_THUMB_L: u32 = 1 << 8;
pub const PRO_BUTTON_THUMB_R: u32 = 1 << 9;
pub const PRO_BUTTON_START: u32 = 1 << 10;
pub const PRO_BUTTON_SELECT: u32 = 1 << 11;
pub const PRO_BUTTON_MODE: u32 = 1 << 12;
pub const PRO_BUTTON_DPAD_UP: u32 = 1 << 13;
pub const PRO_BUTTON_DPAD_DOWN: u32 = 1 << 14;
pub const PRO_BUTTON_DPAD_LEFT: u32 = 1 << 15;
pub const PRO_BUTTON_DPAD_RIGHT: u32 = 1 << 16;
pub const PRO_BUTTON_VALID_MASK: u32 = (1 << 17) - 1;

/// A plain v4 CONTROL state (13-byte frame payload variant).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct ControlState {
    pub left_x: i16,
    pub left_y: i16,
    pub right_x: i16,
    pub right_y: i16,
    pub buttons: u8,
    pub switches: u8,
    pub dpad: u8,
}

impl ControlState {
    pub fn new(
        left_x: i16,
        left_y: i16,
        right_x: i16,
        right_y: i16,
        buttons: u8,
        switches: u8,
        dpad: u8,
    ) -> Result<Self, &'static str> {
        for value in [left_x, left_y, right_x, right_y] {
            if !(-512..=511).contains(&value) {
                return Err("axis must be in -512..511");
            }
        }
        if buttons > 0x0F {
            return Err("buttons must fit four bits");
        }
        if switches > 0x3F {
            return Err("switches must fit six bits");
        }
        if dpad > 4 {
            return Err("dpad must be 0..4");
        }
        Ok(Self {
            left_x,
            left_y,
            right_x,
            right_y,
            buttons,
            switches,
            dpad,
        })
    }
}

/// Professional gamepad PRO_CONTROL state (10-byte frame payload variant).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct ProControlState {
    pub left_x: i16,
    pub left_y: i16,
    pub right_x: i16,
    pub right_y: i16,
    pub left_trigger: u8,
    pub right_trigger: u8,
    pub buttons: u32,
}

impl ProControlState {
    pub fn new(
        left_x: i16,
        left_y: i16,
        right_x: i16,
        right_y: i16,
        left_trigger: u8,
        right_trigger: u8,
        buttons: u32,
    ) -> Result<Self, &'static str> {
        for value in [left_x, left_y, right_x, right_y] {
            if !(-512..=511).contains(&value) {
                return Err("axis must be in -512..511");
            }
        }
        if buttons & !PRO_BUTTON_VALID_MASK != 0 {
            return Err("buttons contain reserved bits");
        }
        Ok(Self {
            left_x,
            left_y,
            right_x,
            right_y,
            left_trigger,
            right_trigger,
            buttons,
        })
    }
}

/// CRC-8/ATM with polynomial 0x07 (same as Python and firmware ports).
pub fn crc8_atm(data: &[u8]) -> u8 {
    let mut crc: u8 = 0;
    for &value in data {
        crc ^= value;
        for _ in 0..8 {
            crc = if crc & 0x80 != 0 {
                (crc << 1) ^ 0x07
            } else {
                crc << 1
            };
        }
    }
    crc
}

/// Pack four signed 10-bit axes into the 5-byte little-endian layout.
pub fn pack_axes(left_x: i16, left_y: i16, right_x: i16, right_y: i16) -> Result<[u8; 5], &'static str> {
    for value in [left_x, left_y, right_x, right_y] {
        if !(-512..=511).contains(&value) {
            return Err("axis must be in -512..511");
        }
    }
    let packed = ((left_x as i64 & 0x3FF)
        | ((left_y as i64 & 0x3FF) << 10)
        | ((right_x as i64 & 0x3FF) << 20)
        | ((right_y as i64 & 0x3FF) << 30)) as u64;
    let bytes = packed.to_le_bytes();
    Ok([bytes[0], bytes[1], bytes[2], bytes[3], bytes[4]])
}

fn encode_frame(message_type: u8, sequence: u8, payload: &[u8]) -> Result<Vec<u8>, &'static str> {
    if message_type > 0x0F {
        return Err("message type must be 0..15");
    }
    if payload.len() > MAX_PAYLOAD {
        return Err("payload exceeds 64 bytes");
    }
    let mut body = Vec::with_capacity(3 + payload.len());
    body.push((VERSION << 4) | message_type);
    body.push(sequence);
    body.push(payload.len() as u8);
    body.extend_from_slice(payload);
    let mut frame = Vec::with_capacity(body.len() + 3);
    frame.extend_from_slice(&SYNC);
    frame.extend_from_slice(&body);
    frame.push(crc8_atm(&body));
    Ok(frame)
}

/// Encode a v4 CONTROL frame (13 bytes on the wire for a full payload).
pub fn encode_control(state: &ControlState, sequence: u8) -> Result<Vec<u8>, &'static str> {
    let axes = pack_axes(state.left_x, state.left_y, state.right_x, state.right_y)?;
    let controls = (state.buttons as u16) | ((state.switches as u16) << 4) | ((state.dpad as u16) << 10);
    let mut payload = Vec::with_capacity(7);
    payload.extend_from_slice(&axes);
    payload.extend_from_slice(&controls.to_le_bytes());
    encode_frame(TYPE_CONTROL, sequence, &payload)
}

/// Encode a v4 PRO_CONTROL frame (16 bytes on the wire for a full payload).
pub fn encode_pro_control(state: &ProControlState, sequence: u8) -> Result<Vec<u8>, &'static str> {
    let axes = pack_axes(state.left_x, state.left_y, state.right_x, state.right_y)?;
    let mut payload = Vec::with_capacity(10);
    payload.extend_from_slice(&axes);
    payload.push(state.left_trigger);
    payload.push(state.right_trigger);
    payload.extend_from_slice(&state.buttons.to_le_bytes()[..3]);
    encode_frame(TYPE_PRO_CONTROL, sequence, &payload)
}

/// HELLO: role=1 (App), capabilities (0x06 BLE / 0x05 SPP), version=2, 0.
pub fn encode_hello(sequence: u8, ble: bool) -> Result<Vec<u8>, &'static str> {
    let capabilities: u8 = if ble { 0x06 } else { 0x05 };
    encode_frame(TYPE_HELLO, sequence, &[1, capabilities, 2, 0])
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Frame {
    pub version: u8,
    pub message_type: u8,
    pub sequence: u8,
    pub payload: Vec<u8>,
}

/// Incremental frame parser for arbitrary BLE notification or serial chunks.
#[derive(Default)]
pub struct StreamDecoder {
    buffer: Vec<u8>,
}

impl StreamDecoder {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn feed(&mut self, data: &[u8]) -> Vec<Frame> {
        self.buffer.extend_from_slice(data);
        let mut frames = Vec::new();
        loop {
            let start = find_sync(&self.buffer);
            if start < 0 {
                if self.buffer.last() != Some(&SYNC[0]) {
                    self.buffer.clear();
                } else {
                    let keep = self.buffer.last().copied().unwrap();
                    self.buffer.clear();
                    self.buffer.push(keep);
                }
                break;
            }
            if start > 0 {
                self.buffer.drain(..start as usize);
            }
            if self.buffer.len() < 5 {
                break;
            }
            if self.buffer[2] >> 4 != VERSION || self.buffer[4] as usize > MAX_PAYLOAD {
                self.buffer.remove(0);
                continue;
            }
            let total = self.buffer[4] as usize + 6;
            if self.buffer.len() < total {
                break;
            }
            let candidate: Vec<u8> = self.buffer.drain(..total).collect();
            if crc8_atm(&candidate[2..candidate.len() - 1]) != candidate[candidate.len() - 1] {
                continue;
            }
            frames.push(Frame {
                version: candidate[2] >> 4,
                message_type: candidate[2] & 0x0F,
                sequence: candidate[3],
                payload: candidate[5..candidate.len() - 1].to_vec(),
            });
        }
        frames
    }
}

fn find_sync(buffer: &[u8]) -> i64 {
    if buffer.len() < 2 {
        if buffer.first() == Some(&SYNC[0]) {
            return 0;
        }
        return -1;
    }
    buffer
        .windows(2)
        .position(|pair| pair == SYNC)
        .map(|pos| pos as i64)
        .unwrap_or(-1)
}

/// Human readable form of a received downstream frame, matching the Qt app.
pub fn describe_frame(frame: &Frame) -> String {
    let name = match frame.message_type {
        1 => "HELLO",
        3 => "ACK",
        4 => "ERROR",
        5 => "LOG",
        6 => "STATUS",
        other => return format!("RX TYPE_{} #{}: {}", other, frame.sequence, hex_spaces(&frame.payload)),
    };
    let detail = if frame.message_type == TYPE_LOG && !frame.payload.is_empty() {
        let text = String::from_utf8_lossy(&frame.payload[1..])
            .trim_end_matches(['\r', '\n'])
            .to_string();
        format!("level={} {}", frame.payload[0], text)
    } else if frame.message_type == TYPE_ERROR && frame.payload.len() >= 2 {
        let text = String::from_utf8_lossy(&frame.payload[2..])
            .trim_end_matches(['\r', '\n'])
            .to_string();
        format!("request={} code={} {}", frame.payload[0], frame.payload[1], text).trim().to_string()
    } else {
        hex_spaces(&frame.payload)
    };
    format!("RX {name} #{}: {detail}", frame.sequence)
}

fn hex_spaces(data: &[u8]) -> String {
    data.iter()
        .map(|byte| format!("{:02X}", byte))
        .collect::<Vec<_>>()
        .join(" ")
        .to_uppercase()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn hex(s: &str) -> Vec<u8> {
        s.split_whitespace()
            .map(|part| u8::from_str_radix(part, 16).unwrap())
            .collect()
    }

    #[test]
    fn crc_standard_vector() {
        assert_eq!(crc8_atm(b"123456789"), 0xF4);
    }

    #[test]
    fn neutral_control_matches_android_documentation() {
        let state = ControlState::default();
        let wire = encode_control(&state, 0).unwrap();
        assert_eq!(wire, hex("A5 5A 40 00 07 00 00 00 00 00 00 00 3F"));
    }

    #[test]
    fn full_control_matches_firmware_vector() {
        let state = ControlState::new(-512, 511, -193, 365, 0x0A, 0x21, 3).unwrap();
        let wire = encode_control(&state, 0x2A).unwrap();
        assert_eq!(wire, hex("A5 5A 40 2A 07 00 FE F7 73 5B 1A 0E 98"));
    }

    #[test]
    fn pro_control_matches_android_and_firmware_vector() {
        let state = ProControlState::new(-512, 511, -257, 256, 31, 255, 0x013569).unwrap();
        let wire = encode_pro_control(&state, 0x2C).unwrap();
        assert_eq!(wire, hex("A5 5A 47 2C 0A 00 FE F7 2F 40 1F FF 69 35 01 54"));
    }

    #[test]
    fn ble_hello_matches_android_documentation() {
        let wire = encode_hello(0, true).unwrap();
        assert_eq!(wire, hex("A5 5A 41 00 04 01 06 02 00 54"));
    }

    #[test]
    fn stream_decoder_accepts_noise_and_fragments() {
        let frame = encode_control(&ControlState::new(123, 0, 0, 0, 0, 0, 0).unwrap(), 7).unwrap();
        let mut decoder = StreamDecoder::new();
        assert!(decoder.feed(b"noise\xA5").is_empty());
        assert!(decoder.feed(&frame[1..8]).is_empty());
        let decoded = decoder.feed(&frame[8..]);
        assert_eq!(decoded.len(), 1);
        assert_eq!(decoded[0].sequence, 7);
        assert_eq!(decoded[0].version, 4);
        assert_eq!(decoded[0].payload[..2], [0x7B, 0x00]);
    }

    #[test]
    fn stream_decoder_rejects_bad_crc() {
        let mut frame = encode_control(&ControlState::default(), 0).unwrap();
        frame[5] ^= 1;
        assert!(StreamDecoder::new().feed(&frame).is_empty());
    }

    #[test]
    fn reserved_axis_value_is_rejected() {
        assert!(ControlState::new(-32768, 0, 0, 0, 0, 0, 0).is_err());
    }

    #[test]
    fn pro_control_rejects_reserved_buttons() {
        assert!(ProControlState::new(0, 0, 0, 0, 0, 0, 1 << 17).is_err());
    }

    #[test]
    fn describe_frames() {
        let hello = encode_hello(0, true).unwrap();
        let frames = StreamDecoder::new().feed(&hello);
        assert_eq!(frames.len(), 1);
        assert_eq!(describe_frame(&frames[0]), "RX HELLO #0: 01 06 02 00");
    }
}


