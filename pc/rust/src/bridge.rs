//! Background bridge worker: samples XInput at ~100 Hz, sends the newest
//! PRO_CONTROL snapshot at the configured wire rate, decodes downstream
//! frames, and reconnects automatically (port of `srm_xbox/gui.py`).

use std::collections::VecDeque;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{channel, Receiver, Sender};
use std::sync::{Arc, Mutex};
use std::thread::JoinHandle;
use std::time::{Duration, Instant};

use crate::ble::{self, BleTransport};
use crate::protocol::{self, ProControlState, StreamDecoder};
use crate::xinput::{self, XInputController, XInputError};

#[derive(Debug, Clone)]
pub struct BridgeConfig {
    /// BLE device selector (address or label).
    pub target: String,
    pub controller: u32,
    pub rate: u32,
    pub deadzone: i32,
    pub trigger_threshold: u8,
    pub auto_reconnect: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum StatusKind {
    Connecting,
    Connected,
    Error,
}

impl StatusKind {
    pub fn as_str(&self) -> &'static str {
        match self {
            StatusKind::Connecting => "connecting",
            StatusKind::Connected => "connected",
            StatusKind::Error => "error",
        }
    }
}

#[derive(Debug, Clone)]
pub enum Event {
    Status(StatusKind, String),
    Log(String),
    State(ProControlState),
    /// Cumulative number of PRO_CONTROL frames written (~4x/s).
    Tx(u64),
    Finished,
}

/// Owns the bridge thread. Drop or `stop_and_join` to shut down cleanly.
pub struct BridgeWorker {
    stop: Arc<AtomicBool>,
    handle: Option<JoinHandle<()>>,
}

impl BridgeWorker {
    pub fn start(config: BridgeConfig, tx: Sender<Event>) -> Self {
        let stop = Arc::new(AtomicBool::new(false));
        let worker_stop = stop.clone();
        let handle = std::thread::Builder::new()
            .name("srm-bridge".into())
            .spawn(move || run_bridge(config, tx, worker_stop))
            .expect("failed to spawn bridge thread");
        Self {
            stop,
            handle: Some(handle),
        }
    }

    pub fn request_stop(&self) {
        self.stop.store(true, Ordering::Relaxed);
    }

    pub fn stop_and_join(&mut self) {
        self.stop.store(true, Ordering::Relaxed);
        if let Some(handle) = self.handle.take() {
            let _ = handle.join();
        }
    }
}

type RxQueue = Arc<Mutex<VecDeque<u8>>>;

/// A connected BLE transport, ready to send frames.
pub struct ConnectedTransport {
    transport: Option<BleTransport>,
    reader: Option<JoinHandle<()>>,
    description: String,
}

impl ConnectedTransport {
    fn send(&self, data: &[u8]) -> Result<(), String> {
        self.transport
            .as_ref()
            .ok_or_else(|| "transport is not connected".to_string())?
            .send(data)
            .map_err(|error| error.to_string())
    }

    fn description(&self) -> &str {
        &self.description
    }

    fn close(&mut self) {
        // Drop the transport first so its senders close and the notify
        // thread can exit, then join it.
        self.transport.take();
        if let Some(handle) = self.reader.take() {
            let _ = handle.join();
        }
    }
}

impl Drop for ConnectedTransport {
    fn drop(&mut self) {
        self.close();
    }
}

fn run_bridge(config: BridgeConfig, tx: Sender<Event>, stop: Arc<AtomicBool>) {
    let mut sequence: u8 = 0;
    let mut decoder = StreamDecoder::new();
    let rx_queue: RxQueue = Arc::new(Mutex::new(VecDeque::new()));
    let mut ble_device: Option<ble::BleDevice> = None;

    loop {
        if stop.load(Ordering::Relaxed) {
            break;
        }
        let mut transport = match connect_transport(&config, &mut ble_device, rx_queue.clone()) {
            Ok(transport) => transport,
            Err(error) => {
                if !stop.load(Ordering::Relaxed) {
                    let _ = tx.send(Event::Status(StatusKind::Error, error.clone()));
                    let _ = tx.send(Event::Log(format!("连接失败: {error}")));
                }
                if !config.auto_reconnect || stop.load(Ordering::Relaxed) {
                    break;
                }
                if !wait_retry(&stop) {
                    break;
                }
                continue;
            }
        };

        let description = transport.description().to_string();
        let _ = tx.send(Event::Status(StatusKind::Connecting, format!("正在连接 {description}")));
        let _ = tx.send(Event::Log(format!("已连接 {description}")));
        let _ = tx.send(Event::Status(StatusKind::Connected, description.clone()));
        if let Ok(hello) = protocol::encode_hello(sequence, true) {
            sequence = sequence.wrapping_add(1);
            let hello_start = Instant::now();
            match transport.send(&hello) {
                Ok(()) => {
                    let _ = tx.send(Event::Log(format!(
                        "HELLO 写入成功（{:.0}ms）",
                        hello_start.elapsed().as_secs_f64() * 1000.0
                    )));
                }
                Err(error) => {
                    let _ = tx.send(Event::Log(format!(
                        "HELLO 写入失败: {error}（{:.0}ms）",
                        hello_start.elapsed().as_secs_f64() * 1000.0
                    )));
                }
            }
        }

        let outcome = stream(&transport, &config, &tx, &stop, &rx_queue, &mut decoder, &mut sequence);
        drain_rx(&mut decoder, &rx_queue, &tx);

        // Best-effort neutral frames so the slave's safety logic sees a stop.
        send_neutral(&transport, &mut sequence);
        transport.close();

        match outcome {
            Ok(()) => {}
            Err(error) => {
                if !stop.load(Ordering::Relaxed) {
                    let _ = tx.send(Event::Status(StatusKind::Error, error.clone()));
                    let _ = tx.send(Event::Log(format!("链路错误: {error}")));
                }
            }
        }

        if !config.auto_reconnect || stop.load(Ordering::Relaxed) {
            break;
        }
        let _ = tx.send(Event::Status(StatusKind::Connecting, "2 秒后重新连接".to_string()));
        if !wait_retry(&stop) {
            break;
        }
    }

    let _ = tx.send(Event::Finished);
}

fn connect_transport(
    config: &BridgeConfig,
    ble_device: &mut Option<ble::BleDevice>,
    rx_queue: RxQueue,
) -> Result<ConnectedTransport, String> {
    if ble_device.is_none() {
        let device = ble::resolve_device(&config.target, Duration::from_secs(8))
            .map_err(|error| error.to_string())?;
        *ble_device = Some(device.clone());
    }
    let device = ble_device.clone().unwrap();
    let (tx, rx) = channel::<Vec<u8>>();
    let feed = rx_queue.clone();
    let reader = spawn_feed_thread("srm-ble-notify", rx, feed)?;
    let transport = BleTransport::connect(&device, tx).map_err(|error| error.to_string())?;
    let description = format!("BLE [{}]", ble::format_address(device.address));
    Ok(ConnectedTransport {
        transport: Some(transport),
        reader: Some(reader),
        description,
    })
}

fn spawn_feed_thread(name: &'static str, rx: Receiver<Vec<u8>>, feed: RxQueue) -> Result<JoinHandle<()>, String> {
    std::thread::Builder::new()
        .name(name.into())
        .spawn(move || {
            while let Ok(chunk) = rx.recv() {
                feed.lock().unwrap().extend(chunk);
            }
        })
        .map_err(|error| format!("cannot spawn {name} thread: {error}"))
}

fn wait_retry(stop: &Arc<AtomicBool>) -> bool {
    for _ in 0..20 {
        if stop.load(Ordering::Relaxed) {
            return false;
        }
        std::thread::sleep(Duration::from_millis(100));
    }
    !stop.load(Ordering::Relaxed)
}

fn drain_rx(decoder: &mut StreamDecoder, rx_queue: &RxQueue, tx: &Sender<Event>) {
    let bytes: Vec<u8> = {
        let mut queue = rx_queue.lock().unwrap();
        queue.drain(..).collect()
    };
    if bytes.is_empty() {
        return;
    }
    for frame in decoder.feed(&bytes) {
        let _ = tx.send(Event::Log(protocol::describe_frame(&frame)));
    }
}

fn send_neutral(transport: &ConnectedTransport, sequence: &mut u8) {
    let neutral = ProControlState::default();
    for _ in 0..3 {
        if let Ok(frame) = protocol::encode_pro_control(&neutral, *sequence) {
            *sequence = sequence.wrapping_add(1);
            if transport.send(&frame).is_err() {
                return;
            }
        }
        std::thread::sleep(Duration::from_millis(20));
    }
}

/// Send the newest controller snapshot at the configured rate while a
/// separate sampler thread keeps input sampling responsive during slow
/// writes. Returns an error string when the link or sampler fails.
fn stream(
    transport: &ConnectedTransport,
    config: &BridgeConfig,
    tx: &Sender<Event>,
    stop: &Arc<AtomicBool>,
    rx_queue: &RxQueue,
    decoder: &mut StreamDecoder,
    sequence: &mut u8,
) -> Result<(), String> {
    let latest: Arc<Mutex<ProControlState>> = Arc::new(Mutex::new(ProControlState::default()));
    let sampler_error: Arc<Mutex<Option<String>>> = Arc::new(Mutex::new(None));

    let controller_index = config.controller;
    let deadzone = config.deadzone;
    let trigger_threshold = config.trigger_threshold;

    let sampler_stop_flag = Arc::new(AtomicBool::new(false));
    let sampler_stop = sampler_stop_flag.clone();
    let sampler_latest = latest.clone();
    let sampler_tx = tx.clone();
    let sampler_err = sampler_error.clone();
    let sampler = std::thread::Builder::new()
        .name("srm-sampler".into())
        .spawn(move || {
            let controller = match XInputController::new(controller_index) {
                Ok(controller) => controller,
                Err(error) => {
                    *sampler_err.lock().unwrap() = Some(format!("XInput 初始化失败: {error}"));
                    return;
                }
            };
            let mut missing = false;
            let mut last_ui = Instant::now();
            while !sampler_stop.load(Ordering::Relaxed) {
                let state = match controller.read() {
                    Ok(raw) => {
                        if missing {
                            let _ = sampler_tx.send(Event::Log("XInput 手柄已重新连接".to_string()));
                            missing = false;
                        }
                        xinput::map_to_srm(&raw, deadzone, trigger_threshold)
                    }
                    Err(XInputError::Disconnected) => {
                        if !missing {
                            let _ =
                                sampler_tx.send(Event::Log("XInput 手柄断开，正在发送中立状态".to_string()));
                            missing = true;
                        }
                        ProControlState::default()
                    }
                    Err(error) => {
                        *sampler_err.lock().unwrap() = Some(error.to_string());
                        return;
                    }
                };
                *sampler_latest.lock().unwrap() = state;
                // Publish every sample; the UI coalesces them to the display
                // refresh rate. Throttle to ~500 Hz so a 120/144 Hz display
                // always receives a fresh snapshot without flooding the queue.
                if last_ui.elapsed() >= Duration::from_millis(2) {
                    let _ = sampler_tx.send(Event::State(state));
                    last_ui = Instant::now();
                }
                if cfg!(debug_assertions) {
                    // Debug builds keep a quiet ~100 Hz cadence.
                    std::thread::sleep(Duration::from_millis(10));
                } else {
                    // Release: with timeBeginPeriod(1) active (ui.rs) a 1 ms
                    // sleep wakes at ~1 ms, so sampling stays far above the
                    // fastest common display (240 Hz).
                    std::thread::sleep(Duration::from_millis(1));
                }
            }
        })
        .map_err(|error| format!("cannot spawn sampler thread: {error}"))?;

    let mut sent: u64 = 0;
    let mut last_tx_report = Instant::now();
    let period = Duration::from_secs_f64(1.0 / config.rate.max(1) as f64);
    let mut deadline = Instant::now();
    let result = loop {
        if stop.load(Ordering::Relaxed) {
            break Ok(());
        }
        if let Some(error) = sampler_error.lock().unwrap().clone() {
            break Err(error);
        }
        drain_rx(decoder, rx_queue, tx);
        let state = *latest.lock().unwrap();
        let frame = protocol::encode_pro_control(&state, *sequence)
            .map_err(|error| error.to_string())?;
        *sequence = sequence.wrapping_add(1);
        if let Err(error) = transport.send(&frame) {
            break Err(error);
        }
        sent += 1;
        // Report cumulative frames on a fixed time cadence (not per frame
        // count) so the status bar refreshes ~4x/s regardless of the
        // configured wire rate.
        if last_tx_report.elapsed() >= Duration::from_millis(250) {
            let _ = tx.send(Event::Tx(sent));
            last_tx_report = Instant::now();
        }
        deadline += period;
        let now = Instant::now();
        if deadline < now.checked_sub(period).unwrap_or(now) {
            deadline = now;
        }
        let wait = deadline.saturating_duration_since(now);
        if !wait.is_zero() {
            std::thread::sleep(wait);
        }
    };

    sampler_stop_flag.store(true, Ordering::Relaxed);
    let _ = sampler.join();
    result
}










