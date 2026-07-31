//! Windows Runtime BLE backend: advertisement scanning and an FFE1 GATT
//! client, mirroring the Python bleak/winrt transport.

use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{channel, Sender};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use windows::core::{GUID, IInspectable};
use windows::Devices::Bluetooth::Advertisement::{
    BluetoothLEAdvertisementReceivedEventArgs, BluetoothLEAdvertisementWatcher, BluetoothLEScanningMode,
};
use windows::Devices::Bluetooth::GenericAttributeProfile::{
    GattCharacteristic, GattCharacteristicProperties, GattClientCharacteristicConfigurationDescriptorValue,
    GattCommunicationStatus, GattDeviceServicesResult, GattSession, GattSessionStatus,
    GattValueChangedEventArgs, GattWriteOption,
};
use windows::Devices::Bluetooth::{
    BluetoothAddressType, BluetoothCacheMode, BluetoothLEDevice,
};
use windows::Foundation::TypedEventHandler;
use windows::Storage::Streams::{DataReader, DataWriter};
use windows::Win32::System::Com::{CoInitializeEx, COINIT_MULTITHREADED};

/// Standard FFE1 service characteristic UUID.
pub const FFE1_UUID: GUID = GUID::from_u128(0x0000ffe1_0000_1000_8000_00805f9b34fb);

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BleDevice {
    pub address: u64,
    pub name: String,
    pub rssi: i16,
    /// Advertised address type (public/random); needed to connect reliably.
    pub address_type: BluetoothAddressType,
}

impl BleDevice {
    pub fn address_text(&self) -> String {
        format_address(self.address)
    }

    pub fn label(&self) -> String {
        if self.name.is_empty() {
            format!("(unnamed) [{}]", self.address_text())
        } else {
            format!("{} [{}]", self.name, self.address_text())
        }
    }

    /// Combo-box item text with a live signal-strength suffix, e.g.
    /// `"RM_BLE [00:55:44:5E:19:13] · -62 dBm"`.
    pub fn label_with_signal(&self) -> String {
        format!("{} · {} dBm", self.label(), self.rssi)
    }
}

/// Format a 48-bit Bluetooth address the way bleak does: Windows returns the
/// address as a u64 with the octets stored little-endian, so the human
/// readable MAC is the big-endian view of the low 6 bytes. Example:
/// `0x00_55_44_5E_19_13` -> `"00:55:44:5E:19:13"`.
pub fn format_address(address: u64) -> String {
    format!(
        "{:02X}:{:02X}:{:02X}:{:02X}:{:02X}:{:02X}",
        ((address >> 40) & 0xFF) as u8,
        ((address >> 32) & 0xFF) as u8,
        ((address >> 24) & 0xFF) as u8,
        ((address >> 16) & 0xFF) as u8,
        ((address >> 8) & 0xFF) as u8,
        (address & 0xFF) as u8,
    )
}

/// Format a GUID as the canonical `8-4-4-4-12` text (lowercase, like UUIDs).
pub fn format_guid(guid: &GUID) -> String {
    format!(
        "{:08x}-{:04x}-{:04x}-{:02x}{:02x}-{:02x}{:02x}{:02x}{:02x}{:02x}{:02x}",
        guid.data1,
        guid.data2,
        guid.data3,
        guid.data4[0],
        guid.data4[1],
        guid.data4[2],
        guid.data4[3],
        guid.data4[4],
        guid.data4[5],
        guid.data4[6],
        guid.data4[7],
    )
}

#[derive(Debug)]
pub enum BleError {
    Windows(windows::core::Error),
    Scan(String),
    NoFfe1,
    NotWritable,
    Communication(GattCommunicationStatus),
    NoSession,
}

impl std::fmt::Display for BleError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            BleError::Windows(error) => write!(f, "WinRT error: {error}"),
            BleError::Scan(message) => write!(f, "scan error: {message}"),
            BleError::NoFfe1 => write!(f, "connected device has no FFE1 characteristic"),
            BleError::NotWritable => write!(f, "FFE1 characteristic is not writable"),
            BleError::Communication(status) => write!(f, "GATT status: {status:?}"),
            BleError::NoSession => write!(f, "device does not support GATT sessions"),
        }
    }
}

impl std::error::Error for BleError {}

impl From<windows::core::Error> for BleError {
    fn from(error: windows::core::Error) -> Self {
        BleError::Windows(error)
    }
}

/// Initialize the current thread for WinRT (multithreaded apartment). Each
/// scan/connect call runs on its own worker thread, so calling this once at
/// the top of those threads is enough.
pub fn init_com() {
    unsafe {
        let _ = CoInitializeEx(None, COINIT_MULTITHREADED);
    }
}

/// Scan for BLE advertisements for `timeout`, deduplicated by address and
/// ordered strongest-signal first. One-shot convenience wrapper around
/// `scan_stream`.
pub fn scan(timeout: Duration) -> windows::core::Result<Vec<BleDevice>> {
    let (tx, rx) = channel::<std::result::Result<(Vec<BleDevice>, bool), String>>();
    scan_stream(timeout, tx)?;
    let mut devices = Vec::new();
    while let Ok(result) = rx.recv() {
        if let Ok((snapshot, _)) = result {
            devices = snapshot;
        }
    }
    Ok(devices)
}

/// Streaming BLE scan: live snapshots are sent to `sink` every ~400 ms as
/// `Ok((devices, false))`; the final snapshot carries `true`. Setup failures
/// are sent as `Err(message)`. Devices are sorted by RSSI (strongest first)
/// so the picker always surfaces the best target at the top.
pub fn scan_stream(
    timeout: Duration,
    sink: Sender<std::result::Result<(Vec<BleDevice>, bool), String>>,
) -> windows::core::Result<()> {
    init_com();
    let result = (|| -> windows::core::Result<()> {
        let watcher = BluetoothLEAdvertisementWatcher::new()?;
        watcher.SetScanningMode(BluetoothLEScanningMode::Active)?;

        let devices: Arc<Mutex<HashMap<u64, BleDevice>>> =
            Arc::new(Mutex::new(HashMap::new()));
        {
            let devices = devices.clone();
            let handler = TypedEventHandler::<BluetoothLEAdvertisementWatcher, BluetoothLEAdvertisementReceivedEventArgs>::new(
                move |_, args| {
                    if let Some(args) = &*args {
                        let address = args.BluetoothAddress()?;
                        let advertisement = args.Advertisement()?;
                        let name = advertisement.LocalName()?.to_string();
                        let rssi = args.RawSignalStrengthInDBm()?;
                        let address_type = args.BluetoothAddressType()?;
                        devices.lock().unwrap().entry(address).and_modify(|entry| {
                            if !name.is_empty() {
                                entry.name.clone_from(&name);
                            }
                            entry.rssi = rssi;
                            entry.address_type = address_type;
                        }).or_insert(BleDevice {
                            address,
                            name,
                            rssi,
                            address_type,
                        });
                    }
                    Ok(())
                },
            );
            watcher.Received(&handler)?;
        }

        watcher.Start()?;
        let tick = Duration::from_millis(400);
        let started = Instant::now();
        while started.elapsed() < timeout {
            std::thread::sleep(tick.min(timeout - started.elapsed()));
            let snapshot = snapshot_devices(&devices);
            if sink.send(Ok((snapshot, false))).is_err() {
                break;
            }
        }
        watcher.Stop()?;
        let _ = sink.send(Ok((snapshot_devices(&devices), true)));
        Ok(())
    })();
    if let Err(error) = &result {
        let _ = sink.send(Err(error.to_string()));
    }
    result
}

fn snapshot_devices(devices: &Arc<Mutex<HashMap<u64, BleDevice>>>) -> Vec<BleDevice> {
    let mut list: Vec<BleDevice> = devices.lock().unwrap().values().cloned().collect();
    sort_devices(&mut list);
    list
}

/// Strongest signal first; stable name/address tiebreak so the list does not
/// churn between devices with equal signal.
pub fn sort_devices(list: &mut [BleDevice]) {
    list.sort_by(|left, right| {
        right
            .rssi
            .cmp(&left.rssi)
            .then_with(|| left.name.to_lowercase().cmp(&right.name.to_lowercase()))
            .then_with(|| format_address(left.address).cmp(&format_address(right.address)))
    });
}

fn hex_value(byte: u8) -> Option<u8> {
    match byte {
        b'0'..=b'9' => Some(byte - b'0'),
        b'a'..=b'f' => Some(byte - b'a' + 10),
        b'A'..=b'F' => Some(byte - b'A' + 10),
        _ => None,
    }
}

/// Parse a 48-bit Bluetooth address from a bare MAC string or from a combo
/// label such as `"RM_BLE [00:55:44:5E:19:13] · -62 dBm"`. Returns the
/// address in the same u64 form the scanner uses (round-trips through
/// `format_address`).
pub fn parse_mac(selector: &str) -> Option<u64> {
    let bytes = selector.as_bytes();
    let mut index = 0;
    while index + 17 <= bytes.len() {
        let mut address: u64 = 0;
        let mut valid = true;
        for octet in 0..6 {
            let offset = index + octet * 3;
            if octet > 0 && bytes[offset - 1] != b':' {
                valid = false;
                break;
            }
            let Some(hi) = hex_value(bytes[offset]) else {
                valid = false;
                break;
            };
            let Some(lo) = hex_value(bytes[offset + 1]) else {
                valid = false;
                break;
            };
            address = (address << 8) | (((hi << 4) | lo) as u64);
        }
        if valid {
            return Some(address);
        }
        index += 1;
    }
    None
}

/// Resolve a selector like `resolve_ble_device` in the Python transport.
/// Accepts a raw address, an exact name, a unique name substring, or a full
/// combo-box label such as `"(unnamed) [00:55:44:5E:19:13]"`.
pub fn resolve_device(selector: &str, timeout: Duration) -> std::result::Result<BleDevice, BleError> {
    if let Some(address) = parse_mac(selector) {
        // The address is already known (e.g. picked from the scanner list), so
        // a full rescan is unnecessary. A brief scan learns the address type
        // and a fresh RSSI; if the device is not advertising right now
        // (cached/paired), fall back to a direct connect with the public
        // address type instead of failing.
        if let Ok(devices) = scan(Duration::from_millis(1200)) {
            if let Some(found) = devices.iter().find(|device| device.address == address) {
                return Ok(found.clone());
            }
        }
        return Ok(BleDevice {
            address,
            name: String::new(),
            rssi: 0,
            address_type: BluetoothAddressType::Public,
        });
    }
    let devices = scan(timeout).map_err(BleError::Windows)?;
    match_selector(&devices, selector)
}

/// Pure selector matching, separated out so it can be unit tested without
/// hardware. Matching is case-insensitive and applies to the address, the
/// name, and the full label.
fn match_selector(devices: &[BleDevice], selector: &str) -> std::result::Result<BleDevice, BleError> {
    let selector_lower = selector.trim().to_lowercase();
    let exact: Vec<&BleDevice> = devices
        .iter()
        .filter(|device| {
            format_address(device.address).to_lowercase() == selector_lower
                || device.name.to_lowercase() == selector_lower
                || device.label().to_lowercase() == selector_lower
        })
        .collect();
    if exact.len() == 1 {
        return Ok(exact[0].clone());
    }
    let partial: Vec<&BleDevice> = devices
        .iter()
        .filter(|device| {
            let address = format_address(device.address).to_lowercase();
            let name = device.name.to_lowercase();
            let label = device.label().to_lowercase();
            address.contains(&selector_lower)
                || name.contains(&selector_lower)
                || label.contains(&selector_lower)
        })
        .collect();
    if partial.len() == 1 {
        return Ok(partial[0].clone());
    }
    if partial.is_empty() {
        return Err(BleError::Scan(format!(
            "BLE device not found: {selector:?} (scanned {} devices; rescan if the device is new)",
            devices.len()
        )));
    }
    let matches: Vec<String> = partial.iter().map(|device| device.label()).collect();
    Err(BleError::Scan(format!(
        "BLE selector is ambiguous: {}",
        matches.join(", ")
    )))
}

/// Diagnostic view of one GATT service and its characteristics.
#[derive(Debug, Clone)]
pub struct ServiceInfo {
    pub service_uuid: String,
    pub characteristics: Vec<CharacteristicInfo>,
}

#[derive(Debug, Clone)]
pub struct CharacteristicInfo {
    pub uuid: String,
    pub properties: u32,
    pub writable: bool,
    pub write_without_response: bool,
    pub notifiable: bool,
}

/// Everything discovered on the wire: the device handle, the kept-alive GATT
/// session, the full service table, and the FFE1 characteristic (if present).
pub struct Discovered {
    pub device: BluetoothLEDevice,
    pub session: GattSession,
    pub services: Vec<ServiceInfo>,
    pub ffe1: Option<GattCharacteristic>,
}

/// Establish a kept-alive GATT session and enumerate all services, mirroring
/// bleak's WinRT connect flow. Unlike a bare `GetGattServicesAsync`, this
/// creates a `GattSession` with `MaintainConnection` (Windows only lazily
/// connects otherwise) and retries discovery when `GattServicesChanged` fires
/// while the first (uncached) pass is still running.
pub fn discover_services(
    address: u64,
    address_type: BluetoothAddressType,
) -> std::result::Result<Discovered, BleError> {
    init_com();
    let device = BluetoothLEDevice::FromBluetoothAddressWithBluetoothAddressTypeAsync(
        address,
        address_type,
    )?
    .get()?;

    let session = GattSession::FromDeviceIdAsync(&device.BluetoothDeviceId()?)?.get()?;
    if !session.CanMaintainConnection()? {
        return Err(BleError::NoSession);
    }
    session.SetMaintainConnection(true)?;

    let services_changed = Arc::new(AtomicBool::new(false));
    {
        let flag = services_changed.clone();
        let handler = TypedEventHandler::<BluetoothLEDevice, IInspectable>::new(move |_, _| {
            flag.store(true, Ordering::SeqCst);
            Ok(())
        });
        device.GattServicesChanged(&handler)?;
    }

    let mut services = get_services(&device, BluetoothCacheMode::Uncached)?;
    if services_changed.swap(false, Ordering::SeqCst) {
        // The lazy connection was established during discovery; give the
        // stack a moment and re-read the (now populated) cache.
        std::thread::sleep(Duration::from_millis(250));
        services = get_services(&device, BluetoothCacheMode::Cached)?;
    }

    // Wait (bounded, max ~1 s) for the GATT session to become active.
    for _ in 0..20 {
        if session.SessionStatus()? == GattSessionStatus::Active {
            break;
        }
        std::thread::sleep(Duration::from_millis(50));
    }

    // Enumerate everything.
    let service_view = services.Services()?;
    let service_count = service_view.Size()?;
    let mut enumerated: Vec<(windows::Devices::Bluetooth::GenericAttributeProfile::GattDeviceService, Vec<GattCharacteristic>)> = Vec::new();
    for index in 0..service_count {
        let service = service_view.GetAt(index)?;
        let characteristics_result = service.GetCharacteristicsAsync()?.get()?;
        if characteristics_result.Status()? != GattCommunicationStatus::Success {
            continue;
        }
        let characteristic_view = characteristics_result.Characteristics()?;
        let characteristic_count = characteristic_view.Size()?;
        let mut characteristics = Vec::with_capacity(characteristic_count as usize);
        for char_index in 0..characteristic_count {
            characteristics.push(characteristic_view.GetAt(char_index)?);
        }
        enumerated.push((service, characteristics));
    }

    let services: Vec<ServiceInfo> = enumerated
        .iter()
        .map(|(service, characteristics)| ServiceInfo {
            service_uuid: service
                .Uuid()
                .map(|guid| format_guid(&guid))
                .unwrap_or_else(|_| "?".to_string()),
            characteristics: characteristics
                .iter()
                .map(|characteristic| {
                    let properties = characteristic
                        .CharacteristicProperties()
                        .unwrap_or(GattCharacteristicProperties(0));
                    CharacteristicInfo {
                        uuid: characteristic
                            .Uuid()
                            .map(|guid| format_guid(&guid))
                            .unwrap_or_else(|_| "?".to_string()),
                        properties: properties.0 as u32,
                        writable: properties & GattCharacteristicProperties::Write
                            != GattCharacteristicProperties(0),
                        write_without_response: properties
                            & GattCharacteristicProperties::WriteWithoutResponse
                            != GattCharacteristicProperties(0),
                        notifiable: properties & GattCharacteristicProperties::Notify
                            != GattCharacteristicProperties(0)
                            || properties & GattCharacteristicProperties::Indicate
                                != GattCharacteristicProperties(0),
                    }
                })
                .collect(),
        })
        .collect();

    // The FFE1 *characteristic* may live under any service (the common
    // JDY-31 style layout is service FFE0 + characteristic FFE1), so search
    // across every service like bleak's `get_characteristic` does.
    let ffe1 = enumerated
        .iter()
        .flat_map(|(_, characteristics)| characteristics.iter())
        .find(|characteristic| characteristic.Uuid().unwrap_or_default() == FFE1_UUID)
        .cloned();

    Ok(Discovered {
        device,
        session,
        services,
        ffe1,
    })
}

/// Connected FFE1 GATT client. `receive` receives raw notification bytes.
pub struct BleTransport {
    // Keep the device and the GATT session alive for the whole connection.
    _device: BluetoothLEDevice,
    _session: GattSession,
    characteristic: GattCharacteristic,
    write_without_response: bool,
}

impl BleTransport {
    /// Connect without collecting the full service table (bridge path).
    pub fn connect(
        device: &BleDevice,
        receive: Sender<Vec<u8>>,
    ) -> std::result::Result<Self, BleError> {
        let discovered = discover_services(device.address, device.address_type)?;
        Self::from_discovered(discovered, receive)
    }

    /// Connect and also return the full service table (probe path).
    pub fn connect_with_info(
        device: &BleDevice,
        receive: Sender<Vec<u8>>,
    ) -> std::result::Result<(Self, Vec<ServiceInfo>), BleError> {
        let discovered = discover_services(device.address, device.address_type)?;
        let services = discovered.services.clone();
        Ok((Self::from_discovered(discovered, receive)?, services))
    }

    /// Turn a successful discovery into a live transport: pick the write mode
    /// and enable notifications when the characteristic supports them.
    pub fn from_discovered(
        discovered: Discovered,
        receive: Sender<Vec<u8>>,
    ) -> std::result::Result<Self, BleError> {
        let characteristic = discovered.ffe1.ok_or(BleError::NoFfe1)?;
        let properties = characteristic.CharacteristicProperties()?;
        let write_without_response = properties
            & GattCharacteristicProperties::WriteWithoutResponse
            != GattCharacteristicProperties(0);
        if !write_without_response
            && properties & GattCharacteristicProperties::Write == GattCharacteristicProperties(0)
        {
            return Err(BleError::NotWritable);
        }

        if properties & GattCharacteristicProperties::Notify != GattCharacteristicProperties(0)
            || properties & GattCharacteristicProperties::Indicate != GattCharacteristicProperties(0)
        {
            let handler = TypedEventHandler::<GattCharacteristic, GattValueChangedEventArgs>::new(
                move |_, args| {
                    if let Some(args) = &*args {
                        let buffer = args.CharacteristicValue()?;
                        let reader = DataReader::FromBuffer(&buffer)?;
                        let length = reader.UnconsumedBufferLength()? as usize;
                        let mut bytes = vec![0u8; length];
                        reader.ReadBytes(&mut bytes)?;
                        let _ = receive.send(bytes);
                    }
                    Ok(())
                },
            );
            characteristic.ValueChanged(&handler)?;
            let status = characteristic
                .WriteClientCharacteristicConfigurationDescriptorAsync(
                    GattClientCharacteristicConfigurationDescriptorValue::Notify,
                )?
                .get()?;
            if status != GattCommunicationStatus::Success {
                return Err(BleError::Communication(status));
            }
        }

        Ok(Self {
            _device: discovered.device,
            _session: discovered.session,
            characteristic,
            write_without_response,
        })
    }

    pub fn send(&self, data: &[u8]) -> std::result::Result<(), BleError> {
        let writer = DataWriter::CreateDataWriter(None::<&windows::Storage::Streams::IOutputStream>)?;
        writer.WriteBytes(data)?;
        let buffer = writer.DetachBuffer()?;
        let option = if self.write_without_response {
            GattWriteOption::WriteWithoutResponse
        } else {
            GattWriteOption::WriteWithResponse
        };
        let status = self
            .characteristic
            .WriteValueWithOptionAsync(&buffer, option)?
            .get()?;
        if status != GattCommunicationStatus::Success {
            return Err(BleError::Communication(status));
        }
        Ok(())
    }
}

fn get_services(
    device: &BluetoothLEDevice,
    mode: BluetoothCacheMode,
) -> std::result::Result<GattDeviceServicesResult, BleError> {
    let result = device.GetGattServicesWithCacheModeAsync(mode)?.get()?;
    if result.Status()? != GattCommunicationStatus::Success {
        return Err(BleError::Communication(result.Status()?));
    }
    Ok(result)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn device(address: u64, name: &str) -> BleDevice {
        BleDevice {
            address,
            name: name.to_string(),
            rssi: -50,
            address_type: BluetoothAddressType::Public,
        }
    }

    fn devices() -> Vec<BleDevice> {
        vec![
            device(0x00_55_44_5E_19_13, ""),
            device(0x00_90_58_2C_04_2C, "RM_BLE"),
            device(0xC8_24_70_D1_33_87, "EDIFIER B"),
        ]
    }

    #[test]
    fn format_address_is_big_endian_like_bleak() {
        assert_eq!(format_address(0x00_55_44_5E_19_13), "00:55:44:5E:19:13");
        assert_eq!(format_address(0), "00:00:00:00:00:00");
        assert_eq!(format_address(0xFFFF_FFFF_FFFF), "FF:FF:FF:FF:FF:FF");
    }

    #[test]
    fn format_guid_matches_textual_uuid() {
        assert_eq!(
            format_guid(&FFE1_UUID),
            "0000ffe1-0000-1000-8000-00805f9b34fb"
        );
    }

    #[test]
    fn selector_matches_full_combo_label_with_unnamed_device() {
        let result = match_selector(&devices(), "(unnamed) [00:55:44:5E:19:13]");
        assert_eq!(result.unwrap().address, 0x00_55_44_5E_19_13);
    }

    #[test]
    fn selector_matches_full_combo_label_with_named_device() {
        let result = match_selector(&devices(), "EDIFIER B [C8:24:70:D1:33:87]");
        assert_eq!(result.unwrap().name, "EDIFIER B");
    }

    #[test]
    fn selector_matches_raw_address_and_name_substring() {
        let result = match_selector(&devices(), "00:55:44:5e:19:13");
        assert_eq!(result.unwrap().address, 0x00_55_44_5E_19_13);
        let result = match_selector(&devices(), "edifier");
        assert_eq!(result.unwrap().name, "EDIFIER B");
    }

    #[test]
    fn selector_rejects_missing_and_reports_scan_count() {
        let error = match_selector(&devices(), "FF:FF:FF:FF:FF:FF").unwrap_err();
        match error {
            BleError::Scan(message) => {
                assert!(message.contains("not found"));
                assert!(message.contains("scanned 3 devices"));
            }
            other => panic!("unexpected error: {other:?}"),
        }
    }

    #[test]
    fn selector_is_ambiguous_when_substring_matches_many() {
        let mut list = devices();
        list.push(device(0x00_90_58_2C_04_2D, ""));
        let error = match_selector(&list, "unnamed").unwrap_err();
        match error {
            BleError::Scan(message) => assert!(message.contains("ambiguous")),
            other => panic!("unexpected error: {other:?}"),
        }
    }

    #[test]
    fn parse_mac_from_bare_address_label_and_garbage() {
        assert_eq!(parse_mac("00:55:44:5E:19:13"), Some(0x00_55_44_5E_19_13));
        assert_eq!(parse_mac("00:55:44:5e:19:13"), Some(0x00_55_44_5E_19_13));
        assert_eq!(
            parse_mac("RM_BLE [00:55:44:5E:19:13] · -62 dBm"),
            Some(0x00_55_44_5E_19_13)
        );
        assert_eq!(
            parse_mac("(unnamed) [C8:24:70:D1:33:87]"),
            Some(0xC8_24_70_D1_33_87)
        );
        assert_eq!(parse_mac("no address here"), None);
        assert_eq!(parse_mac("00:55:44:5E:19:1"), None);
        assert_eq!(parse_mac("00:55:44:5E:19:13x"), Some(0x00_55_44_5E_19_13));
    }

    #[test]
    fn parse_mac_round_trips_with_format_address() {
        for address in [0x00_55_44_5E_19_13u64, 0xC8_24_70_D1_33_87, 0x00_00_00_00_00_01] {
            let text = format_address(address);
            assert_eq!(parse_mac(&text), Some(address));
        }
    }

    #[test]
    fn label_with_signal_suffixes_rssi() {
        let mut device = device(0x00_55_44_5E_19_13, "RM_BLE");
        device.rssi = -62;
        assert_eq!(
            device.label_with_signal(),
            "RM_BLE [00:55:44:5E:19:13] · -62 dBm"
        );
    }

    #[test]
    fn sort_devices_orders_by_signal_strength() {
        let mut list = vec![
            device(0x00_00_00_00_00_01, "b"),
            device(0x00_00_00_00_00_02, "a"),
            device(0x00_00_00_00_00_03, "c"),
        ];
        list[0].rssi = -80;
        list[1].rssi = -50;
        list[2].rssi = -65;
        sort_devices(&mut list);
        assert_eq!(list[0].address, 0x00_00_00_00_00_02); // -50 strongest
        assert_eq!(list[1].address, 0x00_00_00_00_00_03); // -65
        assert_eq!(list[2].address, 0x00_00_00_00_00_01); // -80 weakest
    }
}