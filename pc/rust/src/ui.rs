#![allow(unused_unsafe)]
//! Small native Win32 UI (no Qt), modernized with zero extra dependencies:
//! - Common Controls v6 visual styles via the embedded manifest (build.rs)
//! - flat card layout drawn with GDI rounded rectangles (no GroupBox)
//! - responsive layout: cards and controls reflow on window resize
//! - owner-draw rounded buttons (primary/danger/secondary states) and pill badges
//! - two custom-drawn 2D stick dials instead of progress bars
//! - terminal-style dark log (RichEdit 4.1 / Msftedit.dll) with colored
//!   timestamps and level colors
//! - DWM caption color + rounded corners so the title bar matches the theme

use std::ffi::c_void;
use std::mem::size_of;
use std::sync::atomic::{AtomicBool, AtomicPtr, AtomicU64, Ordering};
use std::sync::mpsc::{channel, Receiver, Sender};
use std::sync::Arc;
use std::time::{Duration, Instant};

use windows::core::{w, HSTRING, PCWSTR};
use windows::Win32::Foundation::{COLORREF, HINSTANCE, HWND, LPARAM, LRESULT, POINT, RECT, WPARAM};
use windows::Win32::Graphics::Dwm::{
    DwmSetWindowAttribute, DWMWA_CAPTION_COLOR, DWMWA_TEXT_COLOR, DWMWA_WINDOW_CORNER_PREFERENCE,
    DWMWCP_ROUND,
};
use windows::Win32::Graphics::Gdi::{
    BeginPaint, CreateFontW, CreatePen, CreateRoundRectRgn, CreateSolidBrush, DeleteObject,
    DrawTextW, Ellipse, EndPaint, EnumDisplaySettingsW, FillRect, FillRgn, FrameRgn,
    InvalidateRect, LineTo, MoveToEx, SelectObject, SetBkColor, SetBkMode, SetTextColor,
    UpdateWindow, CLEARTYPE_QUALITY, CLIP_DEFAULT_PRECIS, DEFAULT_CHARSET, DEFAULT_PITCH,
    DT_CENTER, DT_NOCLIP, DT_SINGLELINE, DT_VCENTER, ENUM_CURRENT_SETTINGS, FF_DONTCARE,
    OUT_DEFAULT_PRECIS, PS_SOLID, TRANSPARENT,
    DEVMODEW, PAINTSTRUCT, HBRUSH, HDC, HGDIOBJ, HFONT,
};
use windows::Win32::System::LibraryLoader::{GetModuleHandleW, LoadLibraryW};
use windows::Win32::UI::Controls::{
    BST_CHECKED, DRAWITEMSTRUCT, EM_GETLINECOUNT, EM_REPLACESEL, EM_SETSEL, ODS_DISABLED,
    ODS_SELECTED,
};
use windows::Win32::System::SystemInformation::GetLocalTime;
use windows::Win32::UI::Input::KeyboardAndMouse::EnableWindow;
use windows::Win32::UI::WindowsAndMessaging::{
    CreateWindowExW, DefWindowProcW, DispatchMessageW, GetClientRect, GetMessageW, GetWindowLongW, GetParent,
    GetWindowTextW, GWL_STYLE, MINMAXINFO, PostMessageW, PostQuitMessage, RegisterClassW, SendMessageW,
    SetTimer, SetWindowPos, SetWindowTextW, ShowWindow, TranslateMessage, WindowFromPoint,
    BM_GETCHECK, BM_SETCHECK, BS_CHECKBOX, BS_OWNERDRAW, CB_ADDSTRING,
    CB_GETCURSEL, CB_RESETCONTENT, CB_SETCURSEL, CW_USEDEFAULT, ES_AUTOVSCROLL, ES_MULTILINE,
    ES_NUMBER, ES_READONLY, HMENU, MSG, SWP_NOACTIVATE, SWP_NOOWNERZORDER, SWP_NOREDRAW,
    SWP_NOZORDER, SW_SHOW, WM_COMMAND, WM_CREATE, WM_CTLCOLORBTN, WM_CTLCOLOREDIT,
    WM_CTLCOLORSTATIC, WM_DESTROY, WM_DRAWITEM, WM_ERASEBKGND, WM_GETMINMAXINFO, WM_MOUSEWHEEL,
    WM_APP, WM_DISPLAYCHANGE, WM_PAINT, WM_SIZE, WM_TIMER, WM_SETFONT, WS_CHILD, WS_DISABLED,
    WS_OVERLAPPEDWINDOW, WS_TABSTOP, WS_VISIBLE, WS_VSCROLL, WINDOW_EX_STYLE,
    WINDOW_STYLE,
};

use crate::ble::{self, BleDevice};
use crate::bridge::{BridgeConfig, BridgeWorker, Event, StatusKind};
use crate::protocol::{self, ProControlState};
use crate::xinput;

// Raise the system timer resolution to 1 ms so WM_TIMER can drive the render
// loop at the display refresh rate. winmm.dll ships with every Windows since
// Windows 95, so this adds no file-size cost worth mentioning.
#[link(name = "winmm")]
unsafe extern "system" {
    fn timeBeginPeriod(period: u32) -> u32;
    fn timeEndPeriod(period: u32) -> u32;
}


fn send_message(hwnd: HWND, message: u32, wparam: WPARAM, lparam: LPARAM) -> LRESULT {
    unsafe { SendMessageW(hwnd, message, Some(wparam), Some(lparam)) }
}

// ---- control ids -----------------------------------------------------------
const IDC_TARGET: i32 = 1003;
const IDC_SCAN: i32 = 1004;
const IDC_CONTROLLER: i32 = 1005;
const IDC_RATE: i32 = 1006;
const IDC_DEADZONE: i32 = 1007;
const IDC_TRIGGER: i32 = 1008;
const IDC_RECONNECT: i32 = 1010;
const IDC_START: i32 = 1011;
const IDC_STATUS: i32 = 1012;
const IDC_LOG: i32 = 1013;
const IDC_CLEAR_LOG: i32 = 1014;
const IDC_STATS: i32 = 1015;
const IDC_CHIP_A: i32 = 1200;
const IDC_CHIP_B: i32 = 1201;
const IDC_CHIP_X: i32 = 1202;
const IDC_CHIP_Y: i32 = 1203;
const IDC_CHIP_L1: i32 = 1204;
const IDC_CHIP_R1: i32 = 1205;
const IDC_CHIP_L2: i32 = 1206;
const IDC_CHIP_R2: i32 = 1207;
const IDC_CHIP_LS: i32 = 1208;
const IDC_CHIP_RS: i32 = 1209;
const IDC_CHIP_START: i32 = 1210;
const IDC_CHIP_BACK: i32 = 1211;
const IDC_DPAD: i32 = 1300;
const IDC_TRIGGER_L: i32 = 1301;
const IDC_TRIGGER_R: i32 = 1302;
const IDC_DIAL_L: i32 = 1310;
const IDC_DIAL_R: i32 = 1311;
const IDC_TITLE_SETUP: i32 = 1500;
const IDC_TITLE_INPUT: i32 = 1501;
const IDC_TITLE_LOG: i32 = 1502;
const IDC_LBL_TARGET: i32 = 1600;
const IDC_LBL_CONTROLLER: i32 = 1601;
const IDC_LBL_RATE: i32 = 1602;
const IDC_LBL_DEADZONE: i32 = 1603;
const IDC_LBL_TRIGGER: i32 = 1604;
const IDC_LBL_DPAD: i32 = 1606;

// ---- raw style bits (windows crate lacks the SS_*/CBS_* names) -------------
const SS_CENTER: u32 = 0x0001;
const SS_RIGHT: u32 = 0x0002;
const SS_CENTERIMAGE: u32 = 0x0200;
const CBS_DROPDOWN: u32 = 0x0003;
const CBS_DROPDOWNLIST: u32 = 0x0002;

// ---- RichEdit messages (richedit.h) ----------------------------------------
const EM_SETBKGNDCOLOR: u32 = 0x0443; // WM_USER + 67
const EM_SETCHARFORMAT: u32 = 0x0444; // WM_USER + 68
const EM_SCROLLCARET: u32 = 0x0431; // WM_USER + 49
const CFM_COLOR: u32 = 0x4000_0000;
const SCF_SELECTION: usize = 1;
const SCF_ALL: usize = 4;

const TIMER_STATE: usize = 1;
const TIMER_LOG: usize = 2;

/// Posted by the render pacing thread at the display refresh rate.
const WM_APP_RENDER: u32 = WM_APP;

// ---- layout constants (client coords) --------------------------------------
const STATUS_H: i32 = 46;
const MARGIN: i32 = 12;
const CARD_GAP: i32 = 10;
const SETUP_H: i32 = 200;
const DIAL_SIZE: i32 = 132;
const INPUT_H: i32 = DIAL_SIZE + 114; // 246
const MIN_W: i32 = 880;
const MIN_H: i32 = 700;

// Colors are stored as BGR (0x00BBGGRR).
const COLOR_WINDOW_BG: COLORREF = COLORREF(0x00F8F6F4); // #F4F6F8
const COLOR_STATUS_BG: COLORREF = COLORREF(0x00F0EDE9); // #E9EDF0
const COLOR_CARD: COLORREF = COLORREF(0x00FFFFFF);
const COLOR_BORDER: COLORREF = COLORREF(0x00E8E3DE); // #DEE3E8
const COLOR_TEXT: COLORREF = COLORREF(0x00262018); // #182026
const COLOR_TEXT_MUTED: COLORREF = COLORREF(0x00736B5E); // #5E6B73
const COLOR_ACTIVE: COLORREF = COLORREF(0x00B26716); // #1667B2
const COLOR_ACTIVE_PRESSED: COLORREF = COLORREF(0x009C570E); // #0E579C
const COLOR_ACTIVE_TEXT: COLORREF = COLORREF(0x00FFFFFF);
const COLOR_DANGER: COLORREF = COLORREF(0x004545D6); // #D64545
const COLOR_DANGER_PRESSED: COLORREF = COLORREF(0x003939B7); // #B73939
const COLOR_INACTIVE: COLORREF = COLORREF(0x00EDE9E4); // #E4E9ED
const COLOR_INACTIVE_TEXT: COLORREF = COLORREF(0x00736B5E);
const COLOR_DISABLED_FILL: COLORREF = COLORREF(0x00F4F2F0); // #F0F2F4
const COLOR_DISABLED_BORDER: COLORREF = COLORREF(0x00E6E1DC); // #DCE1E6
const COLOR_DISABLED_TEXT: COLORREF = COLORREF(0x00AFA59A); // #9AA5AF
const COLOR_SECONDARY_PRESSED: COLORREF = COLORREF(0x00F8F1EA); // #EAF1F8
const COLOR_GREEN: COLORREF = COLORREF(0x005B8316); // #16835B
const COLOR_ORANGE: COLORREF = COLORREF(0x00165FB2); // #B25F16
const COLOR_RED: COLORREF = COLORREF(0x001C2BC4); // #C42B1C
const COLOR_GRID: COLORREF = COLORREF(0x00F4F1EE); // #EEF1F4
const COLOR_LOG_BG: COLORREF = COLORREF(0x0017110D); // #0D1117
const COLOR_LOG_TEXT: COLORREF = COLORREF(0x00F3EDE6); // #E6EDF3
const COLOR_LOG_TS: COLORREF = COLORREF(0x009E948B); // #8B949E
const COLOR_LOG_WARN: COLORREF = COLORREF(0x005CA4F0); // #F0A45C
const COLOR_LOG_ERROR: COLORREF = COLORREF(0x006B6BFF); // #FF6B6B
const COLOR_LOG_OK: COLORREF = COLORREF(0x0087E77E); // #7EE787

#[repr(C)]
#[derive(Clone, Copy)]
struct CharFormat2 {
    cb_size: u32,
    dw_mask: u32,
    dw_effects: u32,
    y_height: i32,
    y_offset: i32,
    cr_text_color: u32,
    b_char_set: u8,
    b_pitch_and_family: u8,
    sz_face_name: [u16; 32],
    w_weight: u16,
    s_spacing: i16,
    cr_back_color: u32,
    lcid: u32,
    dw_reserved: u32,
    s_style: i16,
    w_kerning: u16,
    b_underline_type: u8,
    b_animation: u8,
    b_rev_author: u8,
    b_reserved1: u8,
}

impl Default for CharFormat2 {
    fn default() -> Self {
        unsafe { std::mem::zeroed() }
    }
}

fn char_format_color(color: COLORREF) -> CharFormat2 {
    let mut format = CharFormat2::default();
    format.cb_size = size_of::<CharFormat2>() as u32;
    format.dw_mask = CFM_COLOR;
    format.cr_text_color = color.0;
    format
}

static APP_PTR: AtomicPtr<App> = AtomicPtr::new(std::ptr::null_mut());

struct App {
    hwnd: HWND,
    hinstance: HINSTANCE,
    font: HFONT,
    font_title: HFONT,
    font_console: HFONT,
    brush_window: HBRUSH,
    brush_status_bg: HBRUSH,
    brush_card: HBRUSH,
    brush_border: HBRUSH,
    brush_active: HBRUSH,
    brush_active_pressed: HBRUSH,
    brush_danger: HBRUSH,
    brush_danger_pressed: HBRUSH,
    brush_inactive: HBRUSH,
    brush_disabled_fill: HBRUSH,
    brush_disabled_border: HBRUSH,
    brush_secondary_pressed: HBRUSH,
    brush_log: HBRUSH,
    dial_l: HWND,
    dial_r: HWND,
    status_level: u8,
    controls: Vec<(i32, HWND)>,
    event_rx: Receiver<Event>,
    event_tx: Sender<Event>,
    scan_rx: Receiver<std::result::Result<(Vec<BleDevice>, bool), String>>,
    scan_tx: Sender<std::result::Result<(Vec<BleDevice>, bool), String>>,
    worker: Option<BridgeWorker>,
    scanning: bool,
    stop_requested: bool,
    pending_logs: Vec<String>,
    last_state: ProControlState,
    painted_state: Option<ProControlState>,
    running: bool,
    client_w: i32,
    client_h: i32,
    tx_total: u64,
    tx_last_count: u64,
    tx_hz: u32,
    tx_last_at: Instant,
    controller_ok: [bool; 4],
    last_controller_check: Instant,
    controller_connected: usize,
    refresh_rate: u32,
    frame_interval: Duration,
    next_frame: Instant,
    /// Frame budget in microseconds, shared with the render pacing thread so
    /// a monitor change (WM_DISPLAYCHANGE) retunes the cadence live.
    render_interval_us: Arc<AtomicU64>,
    /// Set by the render thread when a WM_APP_RENDER is posted, cleared by
    /// the UI thread when it is handled. Lets the render thread drop ticks
    /// while the UI is busy so the message queue can never pile up.
    render_pending: Arc<AtomicBool>,
}

pub fn run() -> i32 {
    unsafe {
        // 1 ms system timer resolution: WM_TIMER callbacks then arrive at
        // ~1 kHz, which lets the render loop pace itself to the display
        // refresh rate instead of being capped at the default ~64 Hz.
        let _ = timeBeginPeriod(1);
        let hinstance: HINSTANCE = GetModuleHandleW(None).expect("GetModuleHandleW failed").into();

        let font = CreateFontW(
            -15, 0, 0, 0, 400, false.into(), false.into(), false.into(), DEFAULT_CHARSET,
            OUT_DEFAULT_PRECIS, CLIP_DEFAULT_PRECIS, CLEARTYPE_QUALITY,
            (DEFAULT_PITCH.0 | FF_DONTCARE.0) as u32, w!("Microsoft YaHei UI"),
        );
        let font_title = CreateFontW(
            -16, 0, 0, 0, 600, false.into(), false.into(), false.into(), DEFAULT_CHARSET,
            OUT_DEFAULT_PRECIS, CLIP_DEFAULT_PRECIS, CLEARTYPE_QUALITY,
            (DEFAULT_PITCH.0 | FF_DONTCARE.0) as u32, w!("Microsoft YaHei UI"),
        );
        let font_console = CreateFontW(
            -14, 0, 0, 0, 400, false.into(), false.into(), false.into(), DEFAULT_CHARSET,
            OUT_DEFAULT_PRECIS, CLIP_DEFAULT_PRECIS, CLEARTYPE_QUALITY,
            (DEFAULT_PITCH.0 | FF_DONTCARE.0) as u32, w!("Consolas"),
        );

        let brush_window = CreateSolidBrush(COLOR_WINDOW_BG);
        let brush_status_bg = CreateSolidBrush(COLOR_STATUS_BG);
        let brush_card = CreateSolidBrush(COLOR_CARD);
        let brush_border = CreateSolidBrush(COLOR_BORDER);
        let brush_active = CreateSolidBrush(COLOR_ACTIVE);
        let brush_active_pressed = CreateSolidBrush(COLOR_ACTIVE_PRESSED);
        let brush_danger = CreateSolidBrush(COLOR_DANGER);
        let brush_danger_pressed = CreateSolidBrush(COLOR_DANGER_PRESSED);
        let brush_inactive = CreateSolidBrush(COLOR_INACTIVE);
        let brush_disabled_fill = CreateSolidBrush(COLOR_DISABLED_FILL);
        let brush_disabled_border = CreateSolidBrush(COLOR_DISABLED_BORDER);
        let brush_secondary_pressed = CreateSolidBrush(COLOR_SECONDARY_PRESSED);
        let brush_log = CreateSolidBrush(COLOR_LOG_BG);

        let class_name = w!("SRMXboxBetaWindow");
        let wc = windows::Win32::UI::WindowsAndMessaging::WNDCLASSW {
            lpfnWndProc: Some(wnd_proc),
            hInstance: hinstance,
            hbrBackground: HBRUSH(brush_window.0),
            lpszClassName: class_name,
            ..Default::default()
        };
        RegisterClassW(&wc);

        let dial_class = w!("SRMDialBox");
        let dial_wc = windows::Win32::UI::WindowsAndMessaging::WNDCLASSW {
            lpfnWndProc: Some(dial_proc),
            hInstance: hinstance,
            hbrBackground: HBRUSH(brush_card.0),
            lpszClassName: dial_class,
            ..Default::default()
        };
        RegisterClassW(&dial_wc);

        let (event_tx, event_rx) = channel::<Event>();
        let (scan_tx, scan_rx) = channel::<std::result::Result<(Vec<BleDevice>, bool), String>>();
        let now = Instant::now();
        let refresh_rate = detect_refresh_rate();
        let initial_frame_interval = frame_interval(refresh_rate);
        let mut app = App {
            hwnd: HWND::default(),
            hinstance,
            font,
            font_title,
            font_console,
            brush_window,
            brush_status_bg,
            brush_card,
            brush_border,
            brush_active,
            brush_active_pressed,
            brush_danger,
            brush_danger_pressed,
            brush_inactive,
            brush_disabled_fill,
            brush_disabled_border,
            brush_secondary_pressed,
            brush_log,
            dial_l: HWND::default(),
            dial_r: HWND::default(),
            status_level: 0,
            controls: Vec::new(),
            event_rx,
            event_tx,
            scan_rx,
            scan_tx,
            worker: None,
            scanning: false,
            stop_requested: false,
            pending_logs: Vec::new(),
            last_state: ProControlState::default(),
            painted_state: None,
            running: false,
            client_w: 0,
            client_h: 0,
            tx_total: 0,
            tx_last_count: 0,
            tx_hz: 0,
            tx_last_at: now,
            controller_ok: [false; 4],
            last_controller_check: now,
            controller_connected: 0,
            refresh_rate,
            frame_interval: initial_frame_interval,
            next_frame: now,
            render_interval_us: Arc::new(AtomicU64::new(
                initial_frame_interval.as_micros() as u64,
            )),
            render_pending: Arc::new(AtomicBool::new(false)),
        };
        APP_PTR.store(&mut app as *mut App, Ordering::SeqCst);

        let hwnd = CreateWindowExW(
            WINDOW_EX_STYLE(0),
            class_name,
            w!("SRM Xbox Bridge"),
            WS_OVERLAPPEDWINDOW,
            CW_USEDEFAULT, CW_USEDEFAULT, 1040, 760,
            None, None, Some(hinstance), None,
        )
        .expect("CreateWindowExW failed");
        debug_assert_eq!(hwnd, app.hwnd);

        // Match the light theme in the title bar (Win11; ignored elsewhere).
        let corner = DWMWCP_ROUND.0 as u32;
        let _ = DwmSetWindowAttribute(
            hwnd,
            DWMWA_WINDOW_CORNER_PREFERENCE,
            &corner as *const u32 as *const c_void,
            4,
        );
        let caption = COLOR_CARD.0;
        let _ = DwmSetWindowAttribute(hwnd, DWMWA_CAPTION_COLOR, &caption as *const u32 as *const c_void, 4);
        let caption_text = COLOR_TEXT.0;
        let _ = DwmSetWindowAttribute(hwnd, DWMWA_TEXT_COLOR, &caption_text as *const u32 as *const c_void, 4);

        let _ = ShowWindow(hwnd, SW_SHOW);
        let _ = UpdateWindow(hwnd);

        // Render pacing thread: posts WM_APP_RENDER once per display frame.
        // SetTimer/WM_TIMER is coalesced to ~64 Hz on modern Windows even
        // with timeBeginPeriod(1), so a dedicated thread with high-resolution
        // sleeps is what actually reaches 120/144/210 Hz. The frame gate in
        // refresh_state still decides whether a repaint is due.
        let render_stop = Arc::new(AtomicBool::new(false));
        let render_stop_flag = render_stop.clone();
        // HWND is not Send; carry the raw pointer value and rebuild inside.
        let render_hwnd = hwnd.0 as isize;
        let render_interval_us = app.render_interval_us.clone();
        let render_pending = app.render_pending.clone();
        let render_thread = std::thread::Builder::new()
            .name("srm-render".into())
            .spawn(move || {
                let mut deadline = Instant::now();
                while !render_stop_flag.load(Ordering::Relaxed) {
                    let interval = Duration::from_micros(
                        render_interval_us.load(Ordering::Relaxed).max(1000),
                    );
                    deadline += interval;
                    let now = Instant::now();
                    if deadline < now {
                        deadline = now;
                    }
                    // If the UI thread has not consumed the previous tick yet,
                    // drop this one instead of queueing it (skip, don't burst).
                    if !render_pending.swap(true, Ordering::Relaxed) {
                        let hwnd = HWND(render_hwnd as *mut c_void);
                        let _ = unsafe { PostMessageW(Some(hwnd), WM_APP_RENDER, WPARAM(0), LPARAM(0)) };
                    }
                    std::thread::sleep(deadline.saturating_duration_since(now));
                }
            })
            .expect("failed to spawn render thread");

        let mut message = MSG::default();
        while GetMessageW(&mut message, None, 0, 0).as_bool() {
            let _ = TranslateMessage(&message);
            DispatchMessageW(&message);
        }
        render_stop.store(true, Ordering::Relaxed);
        let _ = render_thread.join();
        if let Some(mut worker) = app.worker.take() {
            worker.stop_and_join();
        }
        for handle in [
            HGDIOBJ(app.font.0), HGDIOBJ(app.font_title.0), HGDIOBJ(app.font_console.0),
            HGDIOBJ(app.brush_window.0), HGDIOBJ(app.brush_status_bg.0), HGDIOBJ(app.brush_card.0),
            HGDIOBJ(app.brush_border.0), HGDIOBJ(app.brush_active.0),
            HGDIOBJ(app.brush_active_pressed.0), HGDIOBJ(app.brush_danger.0),
            HGDIOBJ(app.brush_danger_pressed.0), HGDIOBJ(app.brush_inactive.0),
            HGDIOBJ(app.brush_disabled_fill.0), HGDIOBJ(app.brush_disabled_border.0),
            HGDIOBJ(app.brush_secondary_pressed.0), HGDIOBJ(app.brush_log.0),
        ] {
            let _ = DeleteObject(handle);
        }
        APP_PTR.store(std::ptr::null_mut(), Ordering::SeqCst);
        let _ = timeEndPeriod(1);
        0
    }
}
impl App {
    fn find(&self, id: i32) -> Option<HWND> {
        self.controls.iter().find(|(cid, _)| *cid == id).map(|(_, hwnd)| *hwnd)
    }

    fn set_text(&self, id: i32, text: &str) {
        if let Some(hwnd) = self.find(id) {
            // refresh_state runs once per display frame; skip the repaint
            // churn when the text did not actually change.
            if self.get_text(id) == text {
                return;
            }
            let wide = HSTRING::from(text);
            unsafe {
                let _ = SetWindowTextW(hwnd, PCWSTR(wide.as_ptr()));
                let _ = InvalidateRect(Some(hwnd), None, true.into());
            }
        }
    }

    fn get_text(&self, id: i32) -> String {
        let mut buffer = [0u16; 256];
        if let Some(hwnd) = self.find(id) {
            let length = unsafe { GetWindowTextW(hwnd, &mut buffer) };
            if length > 0 {
                return String::from_utf16_lossy(&buffer[..length as usize]);
            }
        }
        String::new()
    }

    fn set_enabled(&self, id: i32, enabled: bool) {
        if let Some(hwnd) = self.find(id) {
            unsafe {
                let _ = EnableWindow(hwnd, enabled.into());
                let _ = InvalidateRect(Some(hwnd), None, true.into());
            }
        }
    }

    fn combo_select(&self, id: i32, index: i32) {
        if let Some(hwnd) = self.find(id) {
            unsafe {
                send_message(hwnd, CB_SETCURSEL, WPARAM(index as usize), LPARAM(0));
            }
        }
    }

    fn combo_set_items(&self, id: i32, items: &[String]) {
        if let Some(hwnd) = self.find(id) {
            unsafe {
                send_message(hwnd, CB_RESETCONTENT, WPARAM(0), LPARAM(0));
                for item in items {
                    let wide = HSTRING::from(item);
                    send_message(hwnd, CB_ADDSTRING, WPARAM(0), LPARAM(wide.as_ptr() as isize));
                }
            }
        }
    }

    fn log(&mut self, text: String) {
        self.pending_logs.push(text);
    }

    fn update_refresh_rate(&mut self) {
        self.refresh_rate = detect_refresh_rate();
        self.frame_interval = frame_interval(self.refresh_rate);
        self.render_interval_us
            .store(self.frame_interval.as_micros() as u64, Ordering::Relaxed);
        self.next_frame = Instant::now();
    }

    fn append_log_now(&mut self) {
        if self.pending_logs.is_empty() {
            return;
        }
        let Some(log) = self.find(IDC_LOG) else { return };
        let timestamp = now_text();
        let lines = std::mem::take(&mut self.pending_logs);
        unsafe {
            let count = send_message(log, EM_GETLINECOUNT, WPARAM(0), LPARAM(0)).0 as i32;
            if count > 800 {
                send_message(log, EM_SETSEL, WPARAM(0), LPARAM(-1));
                let empty = HSTRING::new();
                send_message(log, EM_REPLACESEL, WPARAM(0), LPARAM(empty.as_ptr() as isize));
            }
        }
        for line in lines {
            append_rich(log, &format!("[{timestamp}] "), COLOR_LOG_TS);
            append_rich(log, &format!("{line}\r\n"), level_color(classify_log(&line)));
        }
    }
}

fn now_text() -> String {
    let system = unsafe { GetLocalTime() };
    format!("{:02}:{:02}:{:02}", system.wHour, system.wMinute, system.wSecond)
}

fn control_id(app: &App, hwnd: HWND) -> Option<i32> {
    app.controls.iter().find(|(_, current)| *current == hwnd).map(|(id, _)| *id)
}

fn control_disabled(app: &App, hwnd: HWND) -> bool {
    if is_disabled(hwnd) {
        return true;
    }
    // CBS_DROPDOWN combos forward WM_CTLCOLOREDIT for their inner edit;
    // the inner edit is not in our control list, so check the combo parent.
    let parent = unsafe { GetParent(hwnd) };
    let parent = if parent.is_err() { HWND::default() } else { parent.unwrap() };
    control_id(app, parent).map(|_| is_disabled(parent)).unwrap_or(false)
}
fn is_disabled(hwnd: HWND) -> bool {
    unsafe { GetWindowLongW(hwnd, GWL_STYLE) & WS_DISABLED.0 as i32 != 0 }
}

fn status_color(level: u8) -> COLORREF {
    match level {
        1 => COLOR_ORANGE,
        2 => COLOR_GREEN,
        3 => COLOR_RED,
        _ => COLOR_TEXT_MUTED,
    }
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum LogLevel {
    Info,
    Ok,
    Warn,
    Error,
}

fn classify_log(text: &str) -> LogLevel {
    if text.contains("失败") || text.contains("错误") || text.contains("异常") || text.contains("无法")
    {
        LogLevel::Error
    } else if text.contains("重连")
        || text.contains("警告")
        || text.contains("断开")
        || text.contains("未连接")
        || text.contains("未发现")
        || text.contains("请先")
    {
        LogLevel::Warn
    } else if text.contains("已连接") || text.contains("成功") || text.contains("已启动")
        || text.contains("扫描到") || text.contains("完成")
    {
        LogLevel::Ok
    } else {
        LogLevel::Info
    }
}

fn level_color(level: LogLevel) -> COLORREF {
    match level {
        LogLevel::Info => COLOR_LOG_TEXT,
        LogLevel::Ok => COLOR_LOG_OK,
        LogLevel::Warn => COLOR_LOG_WARN,
        LogLevel::Error => COLOR_LOG_ERROR,
    }
}

fn chip_active(app: &App, id: i32) -> bool {
    let buttons = app.last_state.buttons;
    match id {
        IDC_CHIP_A => buttons & protocol::PRO_BUTTON_A != 0,
        IDC_CHIP_B => buttons & protocol::PRO_BUTTON_B != 0,
        IDC_CHIP_X => buttons & protocol::PRO_BUTTON_X != 0,
        IDC_CHIP_Y => buttons & protocol::PRO_BUTTON_Y != 0,
        IDC_CHIP_L1 => buttons & protocol::PRO_BUTTON_L1 != 0,
        IDC_CHIP_R1 => buttons & protocol::PRO_BUTTON_R1 != 0,
        IDC_CHIP_L2 => buttons & protocol::PRO_BUTTON_L2 != 0,
        IDC_CHIP_R2 => buttons & protocol::PRO_BUTTON_R2 != 0,
        IDC_CHIP_LS => buttons & protocol::PRO_BUTTON_THUMB_L != 0,
        IDC_CHIP_RS => buttons & protocol::PRO_BUTTON_THUMB_R != 0,
        IDC_CHIP_START => buttons & protocol::PRO_BUTTON_START != 0,
        IDC_CHIP_BACK => buttons & protocol::PRO_BUTTON_SELECT != 0,
        _ => false,
    }
}

// ---- responsive layout ------------------------------------------------------

fn layout_rects(w: i32, h: i32) -> (RECT, RECT, RECT, RECT) {
    let status = RECT { left: 0, top: 0, right: w, bottom: STATUS_H, ..Default::default() };
    let setup_top = STATUS_H + MARGIN;
    let setup = RECT {
        left: MARGIN,
        top: setup_top,
        right: w - MARGIN,
        bottom: setup_top + SETUP_H,
        ..Default::default()
    };
    let input_top = setup.bottom + CARD_GAP;
    let input = RECT {
        left: MARGIN,
        top: input_top,
        right: w - MARGIN,
        bottom: input_top + INPUT_H,
        ..Default::default()
    };
    let log = RECT {
        left: MARGIN,
        top: input.bottom + CARD_GAP,
        right: w - MARGIN,
        bottom: h - MARGIN,
        ..Default::default()
    };
    (status, setup, input, log)
}

fn control_rect(id: i32, w: i32, h: i32) -> Option<(i32, i32, i32, i32)> {
    let (_, setup, input, log) = layout_rects(w, h);
    let x0 = setup.left + 16;
    let x1 = setup.right - 16;
    let inner = x1 - x0;
    let r1 = setup.top + 42;
    let r2 = setup.top + 78;
    let r3 = setup.top + 114;
    let r4 = setup.top + 152;
    // row 1 fixed width; extra space is shared by the two combos
    let slack = (inner - 712).max(0);
    let cs = slack / 2;
    match id {
        IDC_STATUS => Some((16, 8, w * 3 / 5 - 24, 30)),
        IDC_STATS => Some((w * 3 / 5, 8, w - 16 - w * 3 / 5, 30)),
        IDC_TITLE_SETUP => Some((x0 + 12, setup.top + 12, 150, 22)),
        IDC_TITLE_INPUT => Some((x0 + 12, input.top + 12, 150, 22)),
        IDC_TITLE_LOG => Some((x0 + 12, log.top + 12, 150, 22)),
        IDC_CLEAR_LOG => Some((x1 - 84, log.top + 9, 84, 26)),
        IDC_LOG => Some((x0, log.top + 42, inner, (log.bottom - 12) - (log.top + 42))),
        // ---- setup row 1 ----
        IDC_LBL_CONTROLLER => Some((x0, r1, 40, 30)),
        IDC_CONTROLLER => Some((x0 + 40, r1, 128 + cs, 30)),
        IDC_LBL_RATE => Some((x0 + 40 + 128 + cs + 12, r1, 40, 30)),
        IDC_RATE => Some((x0 + 40 + 128 + cs + 52, r1, 68, 30)),
        IDC_LBL_DEADZONE => Some((x0 + 40 + 128 + cs + 120, r1, 40, 30)),
        IDC_DEADZONE => Some((x0 + 40 + 128 + cs + 160, r1, 68, 30)),
        IDC_LBL_TRIGGER => Some((x0 + 40 + 128 + cs + 228, r1, 48, 30)),
        IDC_TRIGGER => Some((x0 + 40 + 128 + cs + 276, r1, 68, 30)),
        // ---- setup row 2 ----
        IDC_LBL_TARGET => Some((x0, r2, 64, 30)),
        IDC_TARGET => Some((x0 + 64, r2, inner - 64 - 96 - 12, 30)),
        IDC_SCAN => Some((x1 - 96, r2, 96, 30)),
        // ---- setup row 3: reconnect toggle only (BLE-only build) ----
        IDC_RECONNECT => Some((x0, r3, 116, 32)),
        // ---- setup row 4: primary action, full card width ----
        IDC_START => Some((x0, r4, inner, 34)),
        // ---- input card ----
        IDC_DIAL_L | IDC_DIAL_R => {
            let col = (inner - 24) / 2;
            let d = DIAL_SIZE.min(col - 16);
            let dx = if id == IDC_DIAL_L { x0 } else { x0 + col + 24 };
            Some((dx + (col - d) / 2, input.top + 42, d, d))
        }
        IDC_TRIGGER_L | IDC_TRIGGER_R => {
            let col = (inner - 24) / 2;
            let d = DIAL_SIZE.min(col - 16);
            let dx = if id == IDC_TRIGGER_L { x0 } else { x0 + col + 24 };
            Some((dx + (col - 90) / 2, input.top + 42 + d + 8, 90, 20))
        }
        id if (IDC_CHIP_A..=IDC_CHIP_BACK).contains(&id) => {
            let idx = (id - IDC_CHIP_A) as i32;
            let y = input.top + 42 + DIAL_SIZE + 34;
            Some((x0 + idx * 50, y, 44, 26))
        }
        IDC_LBL_DPAD => Some((x1 - 140, input.top + 42 + DIAL_SIZE + 34, 48, 26)),
        IDC_DPAD => Some((x1 - 92, input.top + 42 + DIAL_SIZE + 34, 92, 26)),
        _ => None,
    }
}

fn relayout(app: &App) {
    let (w, h) = (app.client_w, app.client_h);
    if w <= 0 || h <= 0 {
        return;
    }
    for (id, hwnd) in &app.controls {
        if let Some((x, y, cx, cy)) = control_rect(*id, w, h) {
            unsafe {
                let _ = SetWindowPos(
                    *hwnd,
                    None,
                    x,
                    y,
                    cx,
                    cy,
                    SWP_NOZORDER | SWP_NOACTIVATE | SWP_NOOWNERZORDER | SWP_NOREDRAW,
                );
            }
        }
    }
}

// ---- drawing ---------------------------------------------------------------

unsafe fn fill_rounded(hdc: HDC, rect: &RECT, radius: i32, fill: HBRUSH, border: HBRUSH) {
    let rgn = CreateRoundRectRgn(rect.left, rect.top, rect.right + 1, rect.bottom + 1, radius, radius);
    let _ = FillRgn(hdc, rgn, fill);
    let _ = FrameRgn(hdc, rgn, border, 1, 1);
    let _ = DeleteObject(HGDIOBJ(rgn.0));
}

unsafe fn draw_centered(hdc: HDC, font: HFONT, text: &str, rect: &mut RECT, color: COLORREF) {
    let mut wide: Vec<u16> = text.encode_utf16().chain(std::iter::once(0)).collect();
    let old = SelectObject(hdc, HGDIOBJ(font.0));
    let _ = SetBkMode(hdc, TRANSPARENT);
    let _ = SetTextColor(hdc, color);
    let _ = DrawTextW(hdc, &mut wide, rect, DT_CENTER | DT_VCENTER | DT_SINGLELINE | DT_NOCLIP);
    let _ = SelectObject(hdc, old);
}

unsafe fn paint_cards(app: &App, hdc: HDC) {
    let (status, setup, input, log) = layout_rects(app.client_w, app.client_h);
    let _ = FillRect(hdc, &status, app.brush_status_bg);
    let line = RECT { left: 0, top: status.bottom - 1, right: status.right, bottom: status.bottom };
    let _ = FillRect(hdc, &line, app.brush_border);
    for card in [setup, input, log] {
        let rect = RECT { left: card.left, top: card.top, right: card.right, bottom: card.bottom };
        fill_rounded(hdc, &rect, 10, app.brush_card, app.brush_border);
        // title accent bar
        let accent = RECT {
            left: card.left + 16,
            top: card.top + 15,
            right: card.left + 19,
            bottom: card.top + 31,
        };
        let _ = FillRect(hdc, &accent, app.brush_active);
    }
}

fn append_rich(log: HWND, text: &str, color: COLORREF) {
    send_message(log, EM_SETSEL, WPARAM(-1i32 as usize), LPARAM(-1i32 as isize));
    let format = char_format_color(color);
    send_message(
        log,
        EM_SETCHARFORMAT,
        WPARAM(SCF_SELECTION),
        LPARAM(&format as *const CharFormat2 as isize),
    );
    let wide = HSTRING::from(text);
    send_message(log, EM_REPLACESEL, WPARAM(0), LPARAM(wide.as_ptr() as isize));
    send_message(log, EM_SCROLLCARET, WPARAM(0), LPARAM(0));
}

unsafe fn draw_owner_item(app: &App, item: &DRAWITEMSTRUCT) {
    let id = item.CtlID as i32;
    let hdc = item.hDC;
    let mut rect = item.rcItem;
    let disabled = item.itemState.0 & ODS_DISABLED.0 != 0;
    let pressed = item.itemState.0 & ODS_SELECTED.0 != 0;

    if (IDC_CHIP_A..=IDC_CHIP_BACK).contains(&id) {
        let active = chip_active(app, id);
        let (fill, text_color) = if active {
            (app.brush_active, COLOR_ACTIVE_TEXT)
        } else {
            (app.brush_inactive, COLOR_INACTIVE_TEXT)
        };
        let radius = (rect.bottom - rect.top) / 2;
        fill_rounded(hdc, &rect, radius, fill, app.brush_card);
        draw_centered(hdc, app.font, &button_text(item.hwndItem), &mut rect, text_color);
        return;
    }

    match id {
        IDC_SCAN | IDC_START | IDC_CLEAR_LOG => {
            let primary = id == IDC_START;
            let danger = primary && app.running;
            let (fill, border, text_color) = if disabled {
                (app.brush_disabled_fill, app.brush_disabled_border, COLOR_DISABLED_TEXT)
            } else if pressed {
                if danger {
                    (app.brush_danger_pressed, app.brush_danger_pressed, COLOR_ACTIVE_TEXT)
                } else if primary {
                    (app.brush_active_pressed, app.brush_active_pressed, COLOR_ACTIVE_TEXT)
                } else {
                    (app.brush_secondary_pressed, app.brush_border, COLOR_TEXT)
                }
            } else if danger {
                (app.brush_danger, app.brush_danger, COLOR_ACTIVE_TEXT)
            } else if primary {
                (app.brush_active, app.brush_active, COLOR_ACTIVE_TEXT)
            } else {
                (app.brush_card, app.brush_border, COLOR_TEXT)
            };
            fill_rounded(hdc, &rect, 6, fill, border);
            draw_centered(hdc, app.font, &button_text(item.hwndItem), &mut rect, text_color);
        }
        _ => {}
    }
}

fn button_text(hwnd: HWND) -> String {
    let mut buffer = [0u16; 64];
    let length = unsafe { GetWindowTextW(hwnd, &mut buffer) };
    if length > 0 {
        String::from_utf16_lossy(&buffer[..length as usize])
    } else {
        String::new()
    }
}

unsafe fn paint_dial(window: HWND, app: &App) {
    let mut ps = PAINTSTRUCT::default();
    let hdc = BeginPaint(window, &mut ps);
    let mut rect = RECT::default();
    let _ = GetClientRect(window, &mut rect);
    let _ = FillRect(hdc, &rect, app.brush_card);
    let w = rect.right - rect.left;
    let h = rect.bottom - rect.top;
    let cx = w / 2;
    let cy = (h - 26) / 2 + 10;
    let radius = (w.min(h - 26) / 2) - 8;

    let pen = CreatePen(PS_SOLID, 2, COLOR_BORDER);
    let old_pen = SelectObject(hdc, HGDIOBJ(pen.0));
    let old_brush = SelectObject(hdc, HGDIOBJ(app.brush_card.0));
    let _ = Ellipse(hdc, cx - radius, cy - radius, cx + radius, cy + radius);

    let grid = CreatePen(PS_SOLID, 1, COLOR_GRID);
    let _ = SelectObject(hdc, HGDIOBJ(grid.0));
    let _ = MoveToEx(hdc, cx - radius, cy, None);
    let _ = LineTo(hdc, cx + radius, cy);
    let _ = MoveToEx(hdc, cx, cy - radius, None);
    let _ = LineTo(hdc, cx, cy + radius);

    let state = app.last_state;
    let (axis_x, axis_y) = if window == app.dial_l {
        (state.left_x, state.left_y)
    } else {
        (state.right_x, state.right_y)
    };
    let dx = cx + ((axis_x as f32 / 512.0) * radius as f32) as i32;
    let dy = cy - ((axis_y as f32 / 512.0) * radius as f32) as i32;
    let dot = CreateSolidBrush(COLOR_ACTIVE);
    let _ = SelectObject(hdc, HGDIOBJ(dot.0));
    let _ = Ellipse(hdc, dx - 7, dy - 7, dx + 7, dy + 7);

    let _ = SelectObject(hdc, old_pen);
    let _ = SelectObject(hdc, old_brush);
    let _ = DeleteObject(HGDIOBJ(pen.0));
    let _ = DeleteObject(HGDIOBJ(grid.0));
    let _ = DeleteObject(HGDIOBJ(dot.0));

    let (name, axis_names) = if window == app.dial_l { ("L", ("LX", "LY")) } else { ("R", ("RX", "RY")) };
    let mut title_rect = RECT { left: 4, top: 2, right: w - 4, bottom: 18, ..Default::default() };
    let _ = draw_centered(hdc, app.font_title, &format!("{name} 摇杆"), &mut title_rect, COLOR_TEXT);
    let mut values: Vec<u16> = format!("{} {:<5} {} {:<5}", axis_names.0, axis_x, axis_names.1, axis_y)
        .encode_utf16().chain(std::iter::once(0)).collect();
    let mut value_rect = RECT { left: 4, top: h - 20, right: w - 4, bottom: h - 2, ..Default::default() };
    let old_font = SelectObject(hdc, HGDIOBJ(app.font.0));
    let _ = SetBkMode(hdc, TRANSPARENT);
    let _ = SetTextColor(hdc, COLOR_TEXT_MUTED);
    let _ = DrawTextW(hdc, &mut values, &mut value_rect, DT_CENTER | DT_SINGLELINE | DT_NOCLIP);
    let _ = SelectObject(hdc, old_font);

    let _ = EndPaint(window, &ps);
}
unsafe extern "system" fn dial_proc(window: HWND, message: u32, wparam: WPARAM, lparam: LPARAM) -> LRESULT {
    let pointer = APP_PTR.load(Ordering::SeqCst);
    let app = if pointer.is_null() { None } else { Some(&mut *pointer) };
    match message {
        WM_ERASEBKGND => {
            if let Some(app) = app {
                let hdc = HDC(wparam.0 as *mut c_void);
                let mut rect = RECT::default();
                let _ = GetClientRect(window, &mut rect);
                let _ = FillRect(hdc, &rect, app.brush_card);
            }
            LRESULT(1)
        }
        WM_PAINT => {
            if let Some(app) = app {
                paint_dial(window, app);
            }
            LRESULT(0)
        }
        _ => DefWindowProcW(window, message, wparam, lparam),
    }
}

unsafe extern "system" fn wnd_proc(window: HWND, message: u32, wparam: WPARAM, lparam: LPARAM) -> LRESULT {
    let pointer = APP_PTR.load(Ordering::SeqCst);
    let app = if pointer.is_null() { None } else { Some(&mut *pointer) };
    let Some(app) = app else {
        return DefWindowProcW(window, message, wparam, lparam);
    };

    match message {
        WM_CREATE => {
            app.hwnd = window;
            if let Err(error) = create_controls(app) {
                app.log(format!("UI 创建失败: {error}"));
            }
            // Fallback only; the render thread drives the real cadence.
            let _ = SetTimer(Some(window), TIMER_STATE, 33, None);
            let _ = SetTimer(Some(window), TIMER_LOG, 100, None);
            LRESULT(0)
        }
        WM_COMMAND => {
            let id = (wparam.0 & 0xFFFF) as i32;
            let code = (wparam.0 >> 16) as u32;
            if code == 0 {
                match id {
                    IDC_SCAN => start_scan(app),
                    IDC_START => toggle_bridge(app),
                    IDC_CLEAR_LOG => {
                        if let Some(log) = app.find(IDC_LOG) {
                            send_message(log, EM_SETSEL, WPARAM(0), LPARAM(-1));
                            let empty = HSTRING::new();
                            send_message(log, EM_REPLACESEL, WPARAM(0), LPARAM(empty.as_ptr() as isize));
                        }
                    }
                    _ => {}
                }
            }
            LRESULT(0)
        }
        WM_DISPLAYCHANGE => {
            // Monitor/refresh-rate changed: re-detect and drop the frame
            // budget so the next repaint happens immediately.
            app.update_refresh_rate();
            LRESULT(0)
        }
        WM_APP_RENDER => {
            // Paced by the render thread at the display refresh rate.
            app.render_pending.store(false, Ordering::Relaxed);
            refresh_state(app);
            LRESULT(0)
        }
        WM_TIMER => {
            match wparam.0 {
                TIMER_STATE => refresh_state(app),
                TIMER_LOG => app.append_log_now(),
                _ => {}
            }
            LRESULT(0)
        }
        WM_GETMINMAXINFO => {
            let minmax = &mut *(lparam.0 as *mut MINMAXINFO);
            minmax.ptMinTrackSize = POINT { x: MIN_W, y: MIN_H };
            LRESULT(0)
        }
        WM_SIZE => {
            app.client_w = (lparam.0 & 0xFFFF) as u16 as i32;
            app.client_h = ((lparam.0 >> 16) & 0xFFFF) as u16 as i32;
            relayout(app);
            let _ = InvalidateRect(Some(window), None, true.into());
            LRESULT(0)
        }
        WM_MOUSEWHEEL => {
            let coords = lparam.0 as i32;
            let point = POINT {
                x: (coords & 0xFFFF) as i16 as i32,
                y: ((coords >> 16) & 0xFFFF) as i16 as i32,
            };
            if let Some(log) = app.find(IDC_LOG) {
                if WindowFromPoint(point) == log {
                    let _ = SendMessageW(log, message, Some(wparam), Some(lparam));
                    return LRESULT(0);
                }
            }
            LRESULT(0)
        }
        WM_ERASEBKGND => {
            let hdc = HDC(wparam.0 as *mut c_void);
            let mut rect = RECT::default();
            let _ = GetClientRect(window, &mut rect);
            let _ = FillRect(hdc, &rect, app.brush_window);
            LRESULT(1)
        }
        WM_PAINT => {
            let mut ps = PAINTSTRUCT::default();
            let hdc = BeginPaint(window, &mut ps);
            paint_cards(app, hdc);
            let _ = EndPaint(window, &ps);
            LRESULT(0)
        }
        WM_DRAWITEM => {
            let item = &*(lparam.0 as *const DRAWITEMSTRUCT);
            draw_owner_item(app, item);
            LRESULT(1)
        }
        WM_CTLCOLORSTATIC => {
            let hdc = HDC(wparam.0 as *mut c_void);
            let control = HWND(lparam.0 as *mut c_void);
            if control_id(app, control) == Some(IDC_LOG) {
                let _ = SetTextColor(hdc, COLOR_LOG_TEXT);
                let _ = SetBkColor(hdc, COLOR_LOG_BG);
                LRESULT(app.brush_log.0 as isize)
            } else if control_disabled(app, control) {
                let _ = SetTextColor(hdc, COLOR_DISABLED_TEXT);
                let _ = SetBkMode(hdc, TRANSPARENT);
                LRESULT(app.brush_card.0 as isize)
            } else {
                let color = match control_id(app, control) {
                    Some(IDC_STATUS) => status_color(app.status_level),
                    Some(IDC_TITLE_SETUP | IDC_TITLE_INPUT | IDC_TITLE_LOG) => COLOR_TEXT,
                    _ => COLOR_TEXT_MUTED,
                };
                let _ = SetTextColor(hdc, color);
                let _ = SetBkMode(hdc, TRANSPARENT);
                let brush = match control_id(app, control) {
                    Some(IDC_STATUS | IDC_STATS) => app.brush_status_bg,
                    _ => app.brush_card,
                };
                LRESULT(brush.0 as isize)
            }
        }
        WM_CTLCOLORBTN => {
            let hdc = HDC(wparam.0 as *mut c_void);
            let control = HWND(lparam.0 as *mut c_void);
            if is_disabled(control) {
                let _ = SetTextColor(hdc, COLOR_DISABLED_TEXT);
                LRESULT(app.brush_disabled_fill.0 as isize)
            } else {
                let _ = SetBkMode(hdc, TRANSPARENT);
                LRESULT(app.brush_card.0 as isize)
            }
        }
        WM_CTLCOLOREDIT => {
            let hdc = HDC(wparam.0 as *mut c_void);
            let control = HWND(lparam.0 as *mut c_void);

            if control_id(app, control) == Some(IDC_LOG) {
                let _ = SetTextColor(hdc, COLOR_LOG_TEXT);
                let _ = SetBkColor(hdc, COLOR_LOG_BG);
                LRESULT(app.brush_log.0 as isize)
            } else if control_disabled(app, control) {
                let _ = SetTextColor(hdc, COLOR_DISABLED_TEXT);
                let _ = SetBkColor(hdc, COLOR_DISABLED_FILL);
                LRESULT(app.brush_disabled_fill.0 as isize)
            } else {
                let _ = SetTextColor(hdc, COLOR_TEXT);
                let _ = SetBkColor(hdc, COLOR_CARD);
                LRESULT(app.brush_card.0 as isize)
            }
        }
        WM_DESTROY => {
            if let Some(mut worker) = app.worker.take() {
                worker.stop_and_join();
            }
            PostQuitMessage(0);
            LRESULT(0)
        }
        _ => DefWindowProcW(window, message, wparam, lparam),
    }
}

fn create_controls(app: &mut App) -> Result<(), windows::core::Error> {
    let mut client = RECT::default();
    unsafe {
        let _ = GetClientRect(app.hwnd, &mut client);
    }
    app.client_w = client.right;
    app.client_h = client.bottom;

    // RichEdit 4.1 ships inside Msftedit.dll (Windows XP+); class: RICHEDIT50W.
    unsafe {
        let _ = LoadLibraryW(w!("Msftedit.dll"));
    }

    macro_rules! add {
        ($id:expr, $class:expr, $text:expr, $style:expr, $ex:expr $(,)?) => {{
            let (x, y, cx, cy) = control_rect($id, app.client_w, app.client_h)
                .expect("control_rect: missing rect for created control");
            let wide = HSTRING::from($text);
            let hwnd = unsafe {
                CreateWindowExW(
                    $ex,
                    $class,
                    PCWSTR(wide.as_ptr()),
                    $style | WS_CHILD | WS_VISIBLE,
                    x,
                    y,
                    cx,
                    cy,
                    Some(app.hwnd),
                    Some(HMENU($id as isize as *mut c_void)),
                    Some(app.hinstance),
                    None,
                )
            }?;
            unsafe {
                send_message(hwnd, WM_SETFONT, WPARAM(app.font.0 as usize), LPARAM(1));
            }
            app.controls.push(($id, hwnd));
            hwnd
        }};
    }

    // ---- status bar ----
    let _ = add!(IDC_STATUS, w!("STATIC"), "● 就绪：请选择目标设备", WINDOW_STYLE(0), WINDOW_EX_STYLE(0));
    let _ = add!(IDC_STATS, w!("STATIC"), "", WINDOW_STYLE(SS_RIGHT | SS_CENTERIMAGE), WINDOW_EX_STYLE(0));

    // ---- card 1: 传输与设置 ----
    let _ = add!(IDC_TITLE_SETUP, w!("STATIC"), "传输与设置", WINDOW_STYLE(0), WINDOW_EX_STYLE(0));
    let _ = add!(IDC_LBL_CONTROLLER, w!("STATIC"), "手柄", WINDOW_STYLE(SS_RIGHT | SS_CENTERIMAGE), WINDOW_EX_STYLE(0));
    let _ = add!(IDC_CONTROLLER, w!("COMBOBOX"), "", WINDOW_STYLE(WS_TABSTOP.0 | WS_VSCROLL.0 | CBS_DROPDOWNLIST), WINDOW_EX_STYLE(0));
    let _ = add!(IDC_LBL_RATE, w!("STATIC"), "频率", WINDOW_STYLE(SS_RIGHT | SS_CENTERIMAGE), WINDOW_EX_STYLE(0));
    let _ = add!(IDC_RATE, w!("EDIT"), "50", WINDOW_STYLE(ES_NUMBER as u32 | WS_TABSTOP.0 | 0x00800000 /* WS_BORDER */), WINDOW_EX_STYLE(0));
    let _ = add!(IDC_LBL_DEADZONE, w!("STATIC"), "死区", WINDOW_STYLE(SS_RIGHT | SS_CENTERIMAGE), WINDOW_EX_STYLE(0));
    let _ = add!(IDC_DEADZONE, w!("EDIT"), "4096", WINDOW_STYLE(ES_NUMBER as u32 | WS_TABSTOP.0 | 0x00800000), WINDOW_EX_STYLE(0));
    let _ = add!(IDC_LBL_TRIGGER, w!("STATIC"), "扳机阈", WINDOW_STYLE(SS_RIGHT | SS_CENTERIMAGE), WINDOW_EX_STYLE(0));
    let _ = add!(IDC_TRIGGER, w!("EDIT"), "30", WINDOW_STYLE(ES_NUMBER as u32 | WS_TABSTOP.0 | 0x00800000), WINDOW_EX_STYLE(0));
    let _ = add!(IDC_LBL_TARGET, w!("STATIC"), "目标设备", WINDOW_STYLE(SS_RIGHT | SS_CENTERIMAGE), WINDOW_EX_STYLE(0));
    let _ = add!(IDC_TARGET, w!("COMBOBOX"), "", WINDOW_STYLE(WS_TABSTOP.0 | WS_VSCROLL.0 | CBS_DROPDOWN), WINDOW_EX_STYLE(0));
    let _ = add!(IDC_SCAN, w!("BUTTON"), "扫描", WINDOW_STYLE(BS_OWNERDRAW as u32 | WS_TABSTOP.0), WINDOW_EX_STYLE(0));
    let _ = add!(IDC_RECONNECT, w!("BUTTON"), "异常自动重连", WINDOW_STYLE(BS_CHECKBOX as u32 | WS_TABSTOP.0), WINDOW_EX_STYLE(0));
    let _ = add!(IDC_START, w!("BUTTON"), "开始遥控", WINDOW_STYLE(BS_OWNERDRAW as u32 | WS_TABSTOP.0), WINDOW_EX_STYLE(0));
    send_message(app.find(IDC_RECONNECT).unwrap(), BM_SETCHECK, WPARAM(BST_CHECKED.0 as usize), LPARAM(0));

    // ---- card 2: 实时输入 ----
    let _ = add!(IDC_TITLE_INPUT, w!("STATIC"), "实时输入", WINDOW_STYLE(0), WINDOW_EX_STYLE(0));
    let dial_l = add!(IDC_DIAL_L, w!("SRMDialBox"), "", WINDOW_STYLE(0), WINDOW_EX_STYLE(0));
    let dial_r = add!(IDC_DIAL_R, w!("SRMDialBox"), "", WINDOW_STYLE(0), WINDOW_EX_STYLE(0));
    app.dial_l = dial_l;
    app.dial_r = dial_r;
    let _ = add!(IDC_TRIGGER_L, w!("STATIC"), "LT 0", WINDOW_STYLE(SS_CENTER | SS_CENTERIMAGE), WINDOW_EX_STYLE(0));
    let _ = add!(IDC_TRIGGER_R, w!("STATIC"), "RT 0", WINDOW_STYLE(SS_CENTER | SS_CENTERIMAGE), WINDOW_EX_STYLE(0));
    let chips = [
        (IDC_CHIP_A, "A"), (IDC_CHIP_B, "B"), (IDC_CHIP_X, "X"), (IDC_CHIP_Y, "Y"),
        (IDC_CHIP_L1, "L1"), (IDC_CHIP_R1, "R1"), (IDC_CHIP_L2, "L2"), (IDC_CHIP_R2, "R2"),
        (IDC_CHIP_LS, "LS"), (IDC_CHIP_RS, "RS"), (IDC_CHIP_START, "Start"), (IDC_CHIP_BACK, "Back"),
    ];
    for (id, text) in chips {
        let _ = add!(id, w!("BUTTON"), text, WINDOW_STYLE(BS_OWNERDRAW as u32), WINDOW_EX_STYLE(0));
    }
    let _ = add!(IDC_LBL_DPAD, w!("STATIC"), "十字键", WINDOW_STYLE(SS_RIGHT | SS_CENTERIMAGE), WINDOW_EX_STYLE(0));
    let _ = add!(IDC_DPAD, w!("STATIC"), "中立", WINDOW_STYLE(SS_CENTER | SS_CENTERIMAGE), WINDOW_EX_STYLE(0));

    // ---- card 3: 通信日志 ----
    let _ = add!(IDC_TITLE_LOG, w!("STATIC"), "通信日志", WINDOW_STYLE(0), WINDOW_EX_STYLE(0));
    let _ = add!(IDC_CLEAR_LOG, w!("BUTTON"), "清空日志", WINDOW_STYLE(BS_OWNERDRAW as u32 | WS_TABSTOP.0), WINDOW_EX_STYLE(0));
    let log = add!(
        IDC_LOG,
        w!("RICHEDIT50W"),
        "",
        WINDOW_STYLE(ES_MULTILINE as u32 | ES_AUTOVSCROLL as u32 | ES_READONLY as u32 | WS_VSCROLL.0 | WS_TABSTOP.0),
        WINDOW_EX_STYLE(0x00000200 /* WS_EX_CLIENTEDGE */),
    );
    unsafe {
        send_message(log, WM_SETFONT, WPARAM(app.font_console.0 as usize), LPARAM(1));
        send_message(log, EM_SETBKGNDCOLOR, WPARAM(0), LPARAM(COLOR_LOG_BG.0 as isize));
        let format = char_format_color(COLOR_LOG_TEXT);
        send_message(
            log,
            EM_SETCHARFORMAT,
            WPARAM(SCF_ALL),
            LPARAM(&format as *const CharFormat2 as isize),
        );
    }

    // Titles and status use the bold face.
    for id in [
        IDC_STATUS, IDC_STATS, IDC_TITLE_SETUP, IDC_TITLE_INPUT, IDC_TITLE_LOG,
    ] {
        if let Some(hwnd) = app.find(id) {
            unsafe {
                send_message(hwnd, WM_SETFONT, WPARAM(app.font_title.0 as usize), LPARAM(1));
            }
        }
    }

    refresh_controllers(app);
    app.log("SRM Xbox Bridge 已启动".to_string());
    app.log("初始化完成，请选择目标设备".to_string());
    Ok(())
}

fn checked(app: &App, id: i32) -> bool {
    app.find(id)
        .map(|hwnd| unsafe { send_message(hwnd, BM_GETCHECK, WPARAM(0), LPARAM(0)).0 != 0 })
        .unwrap_or(false)
}

fn start_scan(app: &mut App) {
    if app.scanning || app.running {
        return;
    }
    app.scanning = true;
    app.set_text(IDC_SCAN, "扫描中…");
    app.set_text(IDC_STATS, "扫描中…");
    app.set_enabled(IDC_SCAN, false);
    let scan_tx = app.scan_tx.clone();
    std::thread::Builder::new()
        .name("srm-scan".into())
        .spawn(move || {
            let _ = ble::scan_stream(Duration::from_secs(5), scan_tx);
        })
        .expect("failed to spawn scan thread");
}

fn toggle_bridge(app: &mut App) {
    if app.running {
        app.stop_requested = true;
        if let Some(worker) = &app.worker {
            worker.request_stop();
        }
        app.status_level = 1;
        app.set_text(IDC_STATUS, "● 正在停止...");
        app.set_text(IDC_START, "停止中...");
        app.set_enabled(IDC_START, false);
        return;
    }
    let config = BridgeConfig {
        target: app.get_text(IDC_TARGET).trim().to_string(),
        controller: app
            .find(IDC_CONTROLLER)
            .map(|hwnd| unsafe { send_message(hwnd, CB_GETCURSEL, WPARAM(0), LPARAM(0)).0 as u32 })
            .unwrap_or(0),
        rate: parse_clamped(&app.get_text(IDC_RATE), 1, 100, 50) as u32,
        deadzone: parse_clamped(&app.get_text(IDC_DEADZONE), 0, 32766, 4096),
        trigger_threshold: parse_clamped(&app.get_text(IDC_TRIGGER), 1, 255, 30) as u8,
        auto_reconnect: checked(app, IDC_RECONNECT),
    };
    if config.target.is_empty() {
        app.log("请先选择目标设备".to_string());
        return;
    }
    let controller_connected = xinput::XInputController::new(config.controller)
        .map(|controller| controller.read().is_ok())
        .unwrap_or(false);
    if !controller_connected {
        app.log(format!("警告：手柄 {} 未连接，将发送中立状态", config.controller));
    }
    let tx = app.event_tx.clone();
    app.worker = Some(BridgeWorker::start(config, tx));
    app.running = true;
    app.stop_requested = false;
    app.status_level = 1;
    app.set_text(IDC_STATUS, "● 正在启动...");
    app.set_text(IDC_START, "停止遥控");
    app.tx_last_count = 0;
    app.tx_hz = 0;
    app.set_text(IDC_STATS, "已发送 0 帧");
    for id in [
        IDC_TARGET, IDC_SCAN, IDC_CONTROLLER, IDC_RATE, IDC_DEADZONE, IDC_TRIGGER, IDC_RECONNECT,
    ] {
        app.set_enabled(id, false);
    }
    app.log("正在启动...".to_string());
}

fn parse_clamped(text: &str, minimum: i32, maximum: i32, fallback: i32) -> i32 {
    text.trim()
        .parse::<i32>()
        .map(|value| value.clamp(minimum, maximum))
        .unwrap_or(fallback)
}

/// Current primary-display refresh rate in Hz (60 if detection fails).
fn detect_refresh_rate() -> u32 {
    unsafe {
        let mut mode = DEVMODEW::default();
        let ok = EnumDisplaySettingsW(None, ENUM_CURRENT_SETTINGS, &mut mode);
        let frequency = if ok.as_bool() { mode.dmDisplayFrequency } else { 0 };
        (24..=1000).contains(&frequency).then_some(frequency).unwrap_or(60)
    }
}

/// Frame budget for one vsync at the given refresh rate.
fn frame_interval(refresh_rate: u32) -> Duration {
    Duration::from_micros((1_000_000 / refresh_rate.max(1) as u64).max(1_000))
}

fn refresh_controllers(app: &mut App) {
    let now = Instant::now();
    if now.duration_since(app.last_controller_check).as_millis() < 250 {
        return;
    }
    app.last_controller_check = now;
    let mut ok = [false; 4];
    let mut connected = 0usize;
    for (index, slot) in ok.iter_mut().enumerate() {
        // XInputController::new only loads the DLL; a real presence probe
        // requires XInputGetState, i.e. read() succeeding.
        let present = xinput::XInputController::new(index as u32)
            .map(|controller| controller.read().is_ok())
            .unwrap_or(false);
        *slot = present;
        if present {
            connected += 1;
        }
    }
    if ok != app.controller_ok {
        for index in 0..4 {
            if ok[index] != app.controller_ok[index] {
                let text = if ok[index] {
                    format!("XInput 手柄 {index} 已连接")
                } else {
                    format!("XInput 手柄 {index} 已断开")
                };
                app.log(text);
            }
        }
        app.controller_ok = ok;
        let items: Vec<String> = (0..4)
            .map(|index| {
                if ok[index] {
                    format!("手柄 {index} · 已连接")
                } else {
                    format!("手柄 {index} · 未连接")
                }
            })
            .collect();
        let selected = app
            .find(IDC_CONTROLLER)
            .map(|hwnd| unsafe { send_message(hwnd, CB_GETCURSEL, WPARAM(0), LPARAM(0)).0 as i32 })
            .unwrap_or(0);
        app.combo_set_items(IDC_CONTROLLER, &items);
        app.combo_select(IDC_CONTROLLER, selected.max(0));
    }
    app.controller_connected = connected;

    if !app.running && !app.scanning {
        let stats = if connected == 0 {
            "未检测到手柄".to_string()
        } else {
            format!("{connected} 个手柄在线")
        };
        app.set_text(IDC_STATS, &stats);
    }
}

fn refresh_state(app: &mut App) {
    while let Ok(event) = app.event_rx.try_recv() {
        match event {
            Event::Status(kind, text) => {
                app.status_level = match kind {
                    StatusKind::Connecting => 1,
                    StatusKind::Connected => 2,
                    StatusKind::Error => 3,
                };
                app.set_text(IDC_STATUS, &format!("● {text}"));
            }
            Event::Log(text) => app.log(text),
            Event::State(state) => app.last_state = state,
            Event::Tx(count) => {
                let delta = count.saturating_sub(app.tx_last_count);
                if delta > 0 {
                    app.tx_total += delta as u64;
                    let now = Instant::now();
                    let dt = now.duration_since(app.tx_last_at).as_secs_f64().max(0.001);
                    // 250 ms windows jitter by a few Hz; smooth the readout
                    // with an exponential moving average.
                    let instant = delta as f64 / dt;
                    app.tx_hz = if app.tx_hz == 0 {
                        instant.round() as u32
                    } else {
                        (app.tx_hz as f64 * 0.7 + instant * 0.3).round() as u32
                    };
                    app.tx_last_at = now;
                    app.tx_last_count = count;
                    app.set_text(IDC_STATS, &format!("已发送 {} 帧 · {} Hz", app.tx_total, app.tx_hz));
                }
            }
            Event::Finished => {
                app.running = false;
                app.stop_requested = false;
                if let Some(mut worker) = app.worker.take() {
                    worker.stop_and_join();
                }
                app.status_level = 0;
                app.set_text(IDC_STATUS, "● 已停止");
                app.set_text(IDC_START, "开始遥控");
                app.set_enabled(IDC_START, true);
                app.tx_last_count = 0;
                app.tx_hz = 0;
                for id in [
                    IDC_TARGET, IDC_SCAN, IDC_CONTROLLER, IDC_RATE, IDC_DEADZONE, IDC_TRIGGER,
                    IDC_RECONNECT,
                ] {
                    app.set_enabled(id, true);
                }
            }
        }
    }

    while let Ok(result) = app.scan_rx.try_recv() {
        match result {
            Ok((devices, is_final)) => {
                let items: Vec<String> = devices.iter().map(|device| device.label_with_signal()).collect();
                let selected = app
                    .find(IDC_TARGET)
                    .map(|hwnd| unsafe { send_message(hwnd, CB_GETCURSEL, WPARAM(0), LPARAM(0)).0 as i32 })
                    .unwrap_or(-1);
                app.combo_set_items(IDC_TARGET, &items);
                let pick = if selected >= 0 {
                    selected.min(items.len() as i32 - 1)
                } else if !items.is_empty() {
                    0
                } else {
                    -1
                };
                if pick >= 0 {
                    app.combo_select(IDC_TARGET, pick);
                }

                if is_final {
                    app.scanning = false;
                    app.set_enabled(IDC_SCAN, true);
                    app.set_text(IDC_SCAN, "扫描");
                    app.log(format!("扫描到 {} 个 BLE 设备", items.len()));
                } else {
                    app.set_text(IDC_STATS, &format!("扫描中… 已发现 {} 个设备", items.len()));
                }
            }
            Err(error) => {
                app.scanning = false;
                app.set_enabled(IDC_SCAN, true);
                app.set_text(IDC_SCAN, "扫描");
                app.log(format!("扫描失败: {error}"));
            }
        }
    }


    refresh_controllers(app);

    // Dynamic components (stick dials, button chips, LT/RT and D-pad
    // readouts) repaint exactly once per display frame. The sampler may
    // publish state at ~500 Hz; we coalesce it to the detected refresh rate
    // and skip any frame budget that fell behind (no burst catch-up).
    let state = app.last_state;
    let now = Instant::now();
    if app.painted_state != Some(state) && now >= app.next_frame {
        let previous = app.painted_state.replace(state);
        let mut next = app.next_frame + app.frame_interval;
        if next < now {
            next = now;
        }
        app.next_frame = next;

        // Repaint only the regions whose input actually changed this frame:
        // chips/D-pad for buttons, each dial for its own stick, and each
        // trigger label for its own trigger. Redrawing all 14 dynamic
        // controls at 120/144/210 Hz is what made the UI feel laggy.
        if previous.map(|p| p.buttons != state.buttons).unwrap_or(true) {
            app.set_text(IDC_DPAD, &dpad_text(state.buttons));
            for (id, hwnd) in app.controls.iter() {
                if (IDC_CHIP_A..=IDC_CHIP_BACK).contains(id) {
                    unsafe {
                        let _ = InvalidateRect(Some(*hwnd), None, false.into());
                    }
                }
            }
        }
        if previous.map(|p| p.left_trigger != state.left_trigger).unwrap_or(true) {
            app.set_text(IDC_TRIGGER_L, &format!("LT {}", state.left_trigger));
        }
        if previous.map(|p| p.right_trigger != state.right_trigger).unwrap_or(true) {
            app.set_text(IDC_TRIGGER_R, &format!("RT {}", state.right_trigger));
        }
        if previous.map(|p| p.left_x != state.left_x || p.left_y != state.left_y).unwrap_or(true) {
            unsafe {
                let _ = InvalidateRect(Some(app.dial_l), None, false.into());
            }
        }
        if previous.map(|p| p.right_x != state.right_x || p.right_y != state.right_y).unwrap_or(true) {
            unsafe {
                let _ = InvalidateRect(Some(app.dial_r), None, false.into());
            }
        }
    }
}

fn dpad_text(buttons: u32) -> String {
    let mut parts = Vec::new();
    if buttons & protocol::PRO_BUTTON_DPAD_UP != 0 {
        parts.push("上");
    }
    if buttons & protocol::PRO_BUTTON_DPAD_DOWN != 0 {
        parts.push("下");
    }
    if buttons & protocol::PRO_BUTTON_DPAD_LEFT != 0 {
        parts.push("左");
    }
    if buttons & protocol::PRO_BUTTON_DPAD_RIGHT != 0 {
        parts.push("右");
    }
    if parts.is_empty() {
        "中立".to_string()
    } else {
        parts.join("")
    }
}
