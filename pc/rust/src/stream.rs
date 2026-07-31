//! `--stream <selector> [seconds] [report.json]`: run the exact GUI bridge
//! code path (`BridgeWorker`) headlessly against a real device and report
//! how many PRO_CONTROL frames were actually written. Used to prove the
//! full connect-and-send chain without clicking through the window.

use std::process::ExitCode;
use std::sync::mpsc::channel;
use std::time::{Duration, Instant};

use serde_json::json;

use crate::bridge::{BridgeConfig, BridgeWorker, Event, StatusKind};

pub fn run(selector: &str, seconds: f64, report_path: Option<&str>) -> ExitCode {
    let report = stream(selector, seconds);
    let path = std::path::Path::new(report_path.unwrap_or("stream-report.json"));
    if let Err(error) = std::fs::write(path, serde_json::to_string_pretty(&report).unwrap()) {
        eprintln!("cannot write stream report: {error}");
        return ExitCode::FAILURE;
    }
    for line in report["log"].as_array().unwrap_or(&vec![]).clone() {
        if let Some(text) = line.as_str() {
            println!("{text}");
        }
    }
    println!(
        "connected={} tx_frames={} tx_rate_hz={:.1}",
        report["connected"].as_bool().unwrap_or(false),
        report["tx_frames"].as_u64().unwrap_or(0),
        report["tx_rate_hz"].as_f64().unwrap_or(0.0),
    );
    if report["ok"].as_bool().unwrap_or(false) {
        ExitCode::SUCCESS
    } else {
        ExitCode::FAILURE
    }
}

fn stream(selector: &str, seconds: f64) -> serde_json::Value {
    let window = seconds.max(1.0);
    let (tx, rx) = channel::<Event>();
    let config = BridgeConfig {
        target: selector.to_string(),
        controller: 0,
        rate: 50,
        deadzone: 4096,
        trigger_threshold: 30,
        auto_reconnect: false,
    };
    let mut worker = BridgeWorker::start(config, tx);
    let mut logs: Vec<String> = Vec::new();
    let mut connected = false;
    let mut connected_at: Option<Instant> = None;
    let mut tx_frames: u64 = 0;
    let mut errors: Vec<String> = Vec::new();
    let mut finished = false;

    let started = Instant::now();
    // The bridge spends ~8 s scanning before connecting, so the streaming
    // window only starts ticking once the device is actually connected.
    let hard_deadline = started + Duration::from_secs_f64(window + 20.0);
    while Instant::now() < hard_deadline && !finished {
        let window_open = connected_at
            .map(|moment| moment.elapsed().as_secs_f64() < window)
            .unwrap_or(true);
        if connected_at.is_some() && !window_open {
            break;
        }
        match rx.recv_timeout(Duration::from_millis(200)) {
            Ok(Event::Status(StatusKind::Connected, text)) => {
                connected = true;
                connected_at = Some(Instant::now());
                logs.push(format!("[{:.1}s] status connected: {text}", started.elapsed().as_secs_f64()));
            }
            Ok(Event::Status(StatusKind::Connecting, text)) => {
                logs.push(format!("[{:.1}s] status connecting: {text}", started.elapsed().as_secs_f64()));
            }
            Ok(Event::Status(StatusKind::Error, text)) => {
                errors.push(text.clone());
                logs.push(format!("[{:.1}s] status error: {text}", started.elapsed().as_secs_f64()));
            }
            Ok(Event::Log(text)) => {
                logs.push(format!("[{:.1}s] {text}", started.elapsed().as_secs_f64()));
            }
            Ok(Event::Tx(count)) => {
                tx_frames = count;
                let when = connected_at
                    .map(|moment| Instant::now().duration_since(moment).as_secs_f64())
                    .unwrap_or(0.0);
                println!("TX {count} @ {when:.2}s");
            }
            Ok(Event::State(_)) => {}
            Ok(Event::Finished) => {
                finished = true;
                logs.push(format!("[{:.1}s] bridge finished", started.elapsed().as_secs_f64()));
            }
            Err(_) => {}
        }
    }
    worker.request_stop();
    worker.stop_and_join();
    while let Ok(event) = rx.try_recv() {
        match event {
            Event::Tx(count) => tx_frames = count,
            Event::Log(text) => logs.push(format!("[drain] {text}")),
            Event::Status(StatusKind::Error, text) => errors.push(text),
            _ => {}
        }
    }

    let elapsed = connected_at
        .map(|moment| moment.elapsed().as_secs_f64().min(window))
        .unwrap_or(window);
    json!({
        "ok": connected && errors.is_empty() && tx_frames > 0,
        "device": selector,
        "connected": connected,
        "tx_frames": tx_frames,
        "tx_rate_hz": (tx_frames as f64) / elapsed,
        "errors": errors,
        "log": logs,
    })
}
