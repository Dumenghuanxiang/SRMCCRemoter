#![windows_subsystem = "windows"]
//! CLI debugging tools (built as srm-xbox-tools.exe with --features cli-tools):
//!   srm-xbox-tools --smoke-test [report.json]
//!   srm-xbox-tools --stream <selector> [seconds] [report.json]
//!   srm-xbox-tools --probe <selector> [report.json]
//! Kept separate from the GUI exe so serde_json never ships in it.

use std::process::ExitCode;

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().collect();
    if args.iter().any(|arg| arg == "--smoke-test") {
        let report_path = args
            .iter()
            .position(|arg| arg == "--smoke-test")
            .and_then(|index| args.get(index + 1))
            .map(String::as_str);
        return srm_xbox::smoke::run(report_path);
    }
    if let Some(index) = args.iter().position(|arg| arg == "--stream") {
        let selector = args.get(index + 1).map(String::as_str).unwrap_or("");
        let seconds = args.get(index + 2).and_then(|value| value.parse::<f64>().ok()).unwrap_or(10.0);
        let report_path = args.get(index + 3).map(String::as_str);
        if selector.is_empty() {
            eprintln!("usage: srm-xbox-tools --stream <selector> [seconds] [report.json]");
            return ExitCode::FAILURE;
        }
        return srm_xbox::stream::run(selector, seconds, report_path);
    }
    if let Some(index) = args.iter().position(|arg| arg == "--probe") {
        let selector = args.get(index + 1).map(String::as_str).unwrap_or("");
        let report_path = args.get(index + 2).map(String::as_str);
        if selector.is_empty() {
            eprintln!("usage: srm-xbox-tools --probe <selector> [report.json]");
            return ExitCode::FAILURE;
        }
        return srm_xbox::probe::run(selector, report_path);
    }
    eprintln!("usage: srm-xbox-tools --smoke-test | --stream <selector> | --probe <selector>");
    ExitCode::FAILURE
}