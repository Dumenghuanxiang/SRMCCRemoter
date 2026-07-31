//! `--probe <selector> [report.json]`: connect to a real BLE device, dump
//! every GATT service/characteristic (even when FFE1 is missing), try a
//! HELLO write and collect any notifications. Used to debug connectivity
//! without the GUI.

use std::process::ExitCode;
use std::sync::mpsc::channel;
use std::time::Duration;

use serde_json::{json, Value};

use crate::ble::{self, BleError};
use crate::protocol;

pub fn run(selector: &str, report_path: Option<&str>) -> ExitCode {
    let report = match probe(selector) {
        Ok(report) => report,
        Err(error) => json!({ "ok": false, "error": error.to_string() }),
    };
    let path = std::path::Path::new(report_path.unwrap_or("probe-report.json"));
    if let Err(error) = std::fs::write(path, serde_json::to_string_pretty(&report).unwrap()) {
        eprintln!("cannot write probe report: {error}");
        return ExitCode::FAILURE;
    }
    if report["ok"].as_bool().unwrap_or(false) {
        ExitCode::SUCCESS
    } else {
        ExitCode::FAILURE
    }
}

fn probe(selector: &str) -> Result<Value, BleError> {
    let device = ble::resolve_device(selector, Duration::from_secs(8))?;
    let discovered = ble::discover_services(device.address, device.address_type)?;

    let services: Vec<Value> = discovered
        .services
        .iter()
        .map(|service| {
            json!({
                "uuid": service.service_uuid,
                "is_ffe1": service.service_uuid == "0000ffe1-0000-1000-8000-00805f9b34fb",
                "characteristics": service.characteristics.iter().map(|characteristic| {
                    json!({
                        "uuid": characteristic.uuid,
                        "properties": characteristic.properties,
                        "write": characteristic.writable,
                        "write_without_response": characteristic.write_without_response,
                        "notify": characteristic.notifiable,
                    })
                }).collect::<Vec<_>>(),
            })
        })
        .collect();

    let ffe1_found = discovered.ffe1.is_some();
    let mut report = json!({
        "ok": ffe1_found,
        "device": device.label(),
        "address": device.address_text(),
        "address_type": if device.address_type == windows::Devices::Bluetooth::BluetoothAddressType::Public { "public" } else { "random/unspecified" },
        "services_count": discovered.services.len(),
        "services": services,
        "ffe1_found": ffe1_found,
        "hello_write": Value::Null,
        "notifications_received": 0,
        "notifications": [],
    });

    if ffe1_found {
        let (tx, rx) = channel::<Vec<u8>>();
        match ble::BleTransport::from_discovered(discovered, tx) {
            Ok(transport) => {
                let hello = protocol::encode_hello(0, true).unwrap_or_default();
                report["hello_frame"] = json!(
                    hello.iter().map(|byte| format!("{:02X}", byte)).collect::<Vec<_>>().join(" ")
                );
                report["hello_write"] = json!(match transport.send(&hello) {
                    Ok(()) => "ok".to_string(),
                    Err(error) => format!("failed: {error}"),
                });

                // Give the slave a moment to answer, then drain notifications.
                std::thread::sleep(Duration::from_millis(600));
                let mut notifications: Vec<String> = Vec::new();
                while let Ok(chunk) = rx.try_recv() {
                    notifications.push(
                        chunk
                            .iter()
                            .map(|byte| format!("{:02X}", byte))
                            .collect::<Vec<_>>()
                            .join(" "),
                    );
                }
                report["notifications_received"] = json!(notifications.len());
                report["notifications"] = json!(notifications);
            }
            Err(error) => {
                report["hello_write"] = json!(format!("transport setup failed: {error}"));
            }
        }
    } else {
        report["hello_write"] = json!("skipped (no FFE1 characteristic)");
    }

    Ok(report)
}
