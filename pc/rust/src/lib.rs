pub mod ble;
pub mod bridge;
pub mod protocol;
pub mod ui;
pub mod xinput;

// CLI debugging tools; only compiled into srm-xbox-tools (feature
// "cli-tools") so the GUI exe stays free of serde_json and tooling code.
#[cfg(feature = "cli-tools")]
pub mod probe;
#[cfg(feature = "cli-tools")]
pub mod smoke;
#[cfg(feature = "cli-tools")]
pub mod stream;

