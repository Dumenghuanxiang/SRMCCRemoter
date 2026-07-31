#![windows_subsystem = "windows"]

use std::process::ExitCode;

fn main() -> ExitCode {
    srm_xbox::ui::run();
    ExitCode::SUCCESS
}

