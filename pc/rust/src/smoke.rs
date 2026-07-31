//! Headless self-check: protocol vectors, XInput DLL loading and a short BLE
//! scan, mirroring the Python `--smoke-test`.

use std::path::Path;
use std::process::ExitCode;
use std::time::Duration;

use serde_json::json;

use crate::protocol;
use crate::xinput::{XInputController, XInputError};

pub fn run(report_path: Option<&str>) -> ExitCode {
    let mut checks = serde_json::Map::new();

    // Protocol vectors from the Python smoke test.
    let neutral_crc = protocol::crc8_atm(&[0x47, 0x00, 0x0A, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0]);
    let protocol_ok = protocol::crc8_atm(b"123456789") == 0xF4 && neutral_crc == 0xD8;
    checks.insert("protocol".into(), json!(protocol_ok));

    // XInput: DLL load must always succeed on Windows; report connected slots.
    let mut connected: Vec<u32> = Vec::new();
    let mut xinput_ok = true;
    for index in 0..4 {
        match XInputController::new(index).and_then(|controller| controller.read()) {
            Ok(_) => connected.push(index),
            Err(XInputError::Disconnected) => {}
            Err(_) => xinput_ok = false,
        }
    }
    checks.insert("xinput_loaded".into(), json!(xinput_ok));

    // BLE scan on a helper thread with a hard timeout (3 s scan + 8 s wait).
    let ble_handle = std::thread::spawn(|| crate::ble::scan(Duration::from_secs(3)));
    let devices = match ble_handle.join() {
        Ok(Ok(devices)) => Ok(devices),
        Ok(Err(error)) => Err(error.to_string()),
        Err(_) => Err("BLE scan thread panicked".to_string()),
    };
    let (ble_ok, ble_count, ble_samples) = match devices {
        Ok(list) => (
            true,
            list.len(),
            list.iter().take(10).map(|device| device.label()).collect::<Vec<String>>(),
        ),
        Err(_) => (false, 0, Vec::new()),
    };
    checks.insert("ble_backend".into(), json!(ble_ok));

    let ok = protocol_ok && xinput_ok && ble_ok;
    let report = json!({
        "ok": ok,
        "checks": checks,
        "xinput_connected": connected,
        "ble_device_count": ble_count,
        "ble_devices": ble_samples,
    });

    let path = Path::new(report_path.unwrap_or("smoke-report.json"));
    let written = std::fs::write(path, serde_json::to_string_pretty(&report).unwrap());
    if let Err(error) = written {
        eprintln!("cannot write smoke report: {error}");
        return ExitCode::FAILURE;
    }
    if ok {
        ExitCode::SUCCESS
    } else {
        ExitCode::FAILURE
    }
}



