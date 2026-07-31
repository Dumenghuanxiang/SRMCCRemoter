//! Embeds `assets/app.manifest` (Common Controls v6 visual styles) into the
//! final exe using the MinGW `windres` resource compiler. Zero new runtime
//! dependencies; without this manifest Win32 controls fall back to the
//! classic Windows 98 look.

use std::path::PathBuf;

fn main() {
    let manifest_dir = PathBuf::from(std::env::var("CARGO_MANIFEST_DIR").unwrap());
    let rc = manifest_dir.join("assets/app.rc");
    let out = PathBuf::from(std::env::var("OUT_DIR").unwrap());
    let object = out.join("app_res.o");
    let status = std::process::Command::new("windres")
        .arg(&rc)
        .args(["-O", "coff", "-o"])
        .arg(&object)
        .status()
        .expect("failed to run windres (is MinGW bin/ on PATH?)");
    assert!(status.success(), "windres failed while embedding app.manifest");
    let link_path = object.display().to_string().replace('\\', "/");
    println!("cargo:rustc-link-arg={link_path}");
    println!("cargo:rerun-if-changed=assets/app.manifest");
    println!("cargo:rerun-if-changed=assets/app.rc");
}
