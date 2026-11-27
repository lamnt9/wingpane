mod ffi;
use std::io::Write;
use std::{
    ffi::c_void,
    path::Path,
    sync::{
        atomic::{AtomicBool, AtomicU32, AtomicU64, AtomicU8, Ordering},
        Arc, Mutex,
    },
    time::Duration,
};
use tokio::{
    net::{TcpListener, TcpStream},
    sync::mpsc,
    task::JoinSet,
};
use wingpane_core::{session::Session, transport::tcp::FramedTcp};
use wingpane_proto::{ControlMessage, Mode, Packet, RejectReason, TouchPhase, VideoPacket};
use wingpaned::{
    busy_rejection, display_transition, should_send_video, DisplayTransition, EncodedFrame, FrameQueue, InputRouter, InputSink, SessionResources,
};

struct CallbackContext {
    queue: Arc<FrameQueue>,
    latency: Arc<LatencyWindow>,
    handle: AtomicU64,
    video_enabled: AtomicBool,
    encoded: AtomicU64,
    backlog_dropped: AtomicU64,
}

#[derive(Default)]
struct LatencySamples {
    encode_us: Vec<u64>,
    write_us: Vec<u64>,
    frame_bytes: Vec<u64>,
    keyframes: u64,
}

#[derive(Default)]
struct LatencyWindow(Mutex<LatencySamples>);

impl LatencyWindow {
    fn encoded(&self, duration_us: u64) {
        let mut samples = self.0.lock().expect("latency stats poisoned");
        samples.encode_us.push(duration_us);
    }

    fn written(&self, duration_us: u64, bytes: usize, keyframe: bool) {
        let mut samples = self.0.lock().expect("latency stats poisoned");
        samples.write_us.push(duration_us);
        samples.frame_bytes.push(bytes as u64);
        if keyframe {
            samples.keyframes += 1;
        }
    }

    fn take(&self) -> LatencySamples {
        std::mem::take(&mut *self.0.lock().expect("latency stats poisoned"))
    }
}

fn percentile(values: &mut [u64], p: f64) -> u64 {
    if values.is_empty() {
        return 0;
    }
    values.sort_unstable();
    values[((values.len() - 1) as f64 * p) as usize]
}
struct ContextGuard(usize);
impl Drop for ContextGuard {
    fn drop(&mut self) {
        if self.0 != 0 {
            unsafe {
                drop(Box::from_raw(self.0 as *mut CallbackContext));
            }
        }
    }
}
unsafe extern "C" fn on_frame(
    ctx: *mut c_void,
    data: *const u8,
    len: usize,
    key: i32,
    pts: i64,
    _latency: i64,
) {
    if ctx.is_null() || data.is_null() || len == 0 {
        return;
    }
    let ctx = unsafe { &*(ctx.cast::<CallbackContext>()) };
    if !ctx.video_enabled.load(Ordering::Acquire) {
        return;
    }
    ctx.encoded.fetch_add(1, Ordering::Relaxed);
    let bytes = unsafe { std::slice::from_raw_parts(data, len) }.to_vec();
    let encode_done_us = ffi::host_time_us();
    ctx.latency
        .encoded(encode_done_us.saturating_sub(pts.max(0) as u64));
    if ctx.queue.push_with_overflow(
        EncodedFrame {
            data: bytes,
            keyframe: key != 0,
            pts_us: pts.max(0) as u64,
            encode_done_us,
        },
        || {
            let handle = ctx.handle.load(Ordering::Relaxed);
            if handle != 0 {
                unsafe { request_keyframe(handle) };
            }
        },
    ) {
        ctx.backlog_dropped.fetch_add(1, Ordering::Relaxed);
    }
}
unsafe extern "C" {
    fn wp_capture_force_keyframe(handle: u64);
    fn wp_test_pattern_show(display_id: u32);
    fn wp_test_pattern_hide(display_id: u32);
    fn wp_test_pattern_pump();
    fn wp_input_pointer(display_id: u32, x: f64, y: f64, phase: u8);
    fn wp_input_pen(
        display_id: u32,
        x: f64,
        y: f64,
        pressure: f64,
        tilt_x: f64,
        tilt_y: f64,
        buttons: u8,
        flags: u8,
    );
    fn wp_main_display_id() -> u32;
    fn wp_input_relative(dx: f64, dy: f64);
    fn wp_input_button(button: u8, down: bool);
    fn wp_input_scroll(dx: f64, dy: f64);
    fn wp_input_key(keycode: u16, down: bool, modifiers: u8);
    fn wp_input_text(units: *const u16, count: usize);
    fn wp_input_release_all();
    fn wp_input_trusted() -> bool;
    fn wp_input_request_trust() -> bool;
    fn wp_screen_capture_request() -> bool;
    fn wp_screen_capture_trusted() -> bool;
}
struct TestPatternGuard(Option<u32>);
impl Drop for TestPatternGuard {
    fn drop(&mut self) {
        if let Some(id) = self.0 {
            unsafe { wp_test_pattern_hide(id) };
        }
    }
}
struct DisconnectLog {
    reason: String,
    status_json: bool,
}
impl Drop for DisconnectLog {
    fn drop(&mut self) {
        eprintln!("client disconnected: {}", self.reason);
        if self.status_json {
            emit_status(StatusEvent::ClientDisconnected {
                reason: self.reason.clone(),
            });
        }
    }
}
struct NativeInputSink(Arc<AtomicU32>);

struct CaptureSlot(Arc<Mutex<Option<ffi::Capture>>>);
impl wingpaned::CaptureSource for CaptureSlot {
    fn force_keyframe(&self) {
        if let Some(c) = self.0.lock().unwrap().as_ref() {
            c.force_keyframe();
        }
    }
    fn stop(&self) {
        self.0.lock().unwrap().take();
    }
}
struct DisplaySlot(Arc<Mutex<Option<ffi::VirtualDisplay>>>);
impl wingpaned::DisplayResource for DisplaySlot {
    fn destroy(&self) {
        self.0.lock().unwrap().take();
    }
}
const FEATURE_PEN_PRESSURE: u32 = 1 << 0;
const FEATURE_POINTER_RELATIVE: u32 = 1 << 1;
const FEATURE_GESTURE: u32 = 1 << 2;
const FEATURE_KEYBOARD: u32 = 1 << 3;
const FEATURE_TEXT: u32 = 1 << 4;
const FEATURE_PEN_CONFIG: u32 = 1 << 5;
const FEATURE_VIDEO_SUSPEND: u32 = 1 << 7;
const FEATURE_EXTENDED_GESTURES: u32 = 1 << 10;
const HOST_INPUT_FEATURES: u32 = FEATURE_PEN_PRESSURE
    | FEATURE_POINTER_RELATIVE
    | FEATURE_GESTURE
    | FEATURE_KEYBOARD
    | FEATURE_TEXT
    | FEATURE_PEN_CONFIG
    | FEATURE_VIDEO_SUSPEND
    | FEATURE_EXTENDED_GESTURES;

impl InputSink for NativeInputSink {
    fn pointer(&self, x: f32, y: f32, phase: TouchPhase) {
        unsafe {
            wp_input_pointer(
                self.0.load(Ordering::Acquire),
                f64::from(x),
                f64::from(y),
                phase as u8,
            )
        };
    }
    fn release_all(&self) {
        unsafe { wp_input_release_all() }
    }
    fn pen(&self, sample: wingpaned::PenSample) {
        let mut flags = 0;
        if sample.is_down {
            flags |= 1;
        }
        if sample.is_hover {
            flags |= 2;
        }
        if !sample.is_down && !sample.is_hover {
            flags |= 4;
        }
        unsafe {
            wp_input_pen(
                {
                    let id = self.0.load(Ordering::Acquire);
                    if id == 0 {
                        wp_main_display_id()
                    } else {
                        id
                    }
                },
                f64::from(sample.x),
                f64::from(sample.y),
                f64::from(sample.pressure),
                f64::from(sample.tilt_x),
                f64::from(sample.tilt_y),
                sample.button_mask,
                flags,
            )
        }
    }
    fn pen_main(&self, sample: wingpaned::PenSample) {
        let mut flags = if sample.is_down { 1 } else { 0 };
        if sample.is_hover {
            flags |= 2;
        }
        if !sample.is_down && !sample.is_hover {
            flags |= 4;
        }
        unsafe {
            wp_input_pen(
                wp_main_display_id(),
                f64::from(sample.x),
                f64::from(sample.y),
                f64::from(sample.pressure),
                f64::from(sample.tilt_x),
                f64::from(sample.tilt_y),
                sample.button_mask,
                flags,
            );
        }
    }
    fn relative(&self, dx: f32, dy: f32) {
        unsafe {
            wp_input_relative(f64::from(dx), f64::from(dy));
        }
    }
    fn button(&self, button: u8, down: bool) {
        unsafe {
            wp_input_button(button, down);
        }
    }
    fn scroll(&self, dx: f32, dy: f32) {
        unsafe {
            wp_input_scroll(f64::from(dx), f64::from(dy));
        }
    }
    fn key(&self, hid: u16, down: bool, mods: u8) {
        if let Some(keycode) = wingpaned::keymap_macos::mac_keycode(hid) {
            unsafe { wp_input_key(keycode, down, mods) }
        }
    }
    fn text(&self, text: &str) {
        for chunk in wingpane_proto::utf16_chunks(text) {
            unsafe { wp_input_text(chunk.as_ptr(), chunk.len()) }
        }
    }
    fn launch_app(&self, name: &str) {
        let name = name.to_owned();
        std::thread::spawn(move || {
            match std::process::Command::new("open").args(["-a", &name]).status() {
                Ok(status) if status.success() => {}
                Ok(status) => eprintln!("failed to launch app {name}: {status}"),
                Err(error) => eprintln!("failed to launch app {name}: {error}"),
            }
        });
    }
}
unsafe fn request_keyframe(handle: u64) {
    wp_capture_force_keyframe(handle);
}

#[derive(serde::Serialize)]
#[serde(tag = "event", rename_all = "snake_case")]
enum StatusEvent {
    Starting { version: String },
    AdbMissing,
    WaitingDevice { reason: String, devices: Vec<DeviceStatus> },
    Device { serial: String, #[serde(skip_serializing_if = "Option::is_none")] model: Option<String> },
    Listening { port: u16 },
    ClientConnected { peer: String },
    Streaming { width: u32, height: u32, hz: u32, hidpi: bool, input_trusted: bool },
    Mode { mode: Mode },
    Permissions { input_trusted: bool, screen_recording: bool },
    Stats { fps: u64, encode_p95_ms: f64, write_p95_ms: f64, backlog_dropped: u64 },
    ClientDisconnected { reason: String },
    Error { message: String },
    Stopped,
}
#[derive(serde::Serialize)]
struct DeviceStatus {
    serial: String,
    state: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    model: Option<String>,
}

#[derive(Clone)]
struct Options {
    port: u16,
    adb: bool,
    hidpi: bool,
    bitrate: u32,
    test_pattern: bool,
    serial: Option<String>,
    stats: bool,
    status_json: bool,
}

fn emit_status(event: StatusEvent) {
    let mut line = serde_json::to_vec(&event).unwrap_or_else(|_| br#"{"event":"error","message":"status serialization failed"}"#.to_vec());
    if line.len() > 4095 { line = br#"{"event":"error","message":"status event exceeded 4 KiB"}"#.to_vec(); }
    line.push(b'\n');
    let stdout = std::io::stdout();
    let mut out = stdout.lock();
    let _ = out.write_all(&line);
    let _ = out.flush();
}
fn options() -> Options {
    let mut o = Options {
        port: 7788,
        adb: true,
        hidpi: true,
        bitrate: 20_000_000,
        test_pattern: false,
        serial: std::env::var("ANDROID_SERIAL").ok(),
        stats: false,
        status_json: false,
    };
    let mut a = std::env::args().skip(1);
    while let Some(k) = a.next() {
        match k.as_str() {
            "--port" => o.port = a.next().and_then(|v| v.parse().ok()).unwrap_or(7788),
            "--no-adb" => o.adb = false,
            "--test-pattern" => o.test_pattern = true,
            "--stats" => o.stats = true,
            "--status-json" => o.status_json = true,
            "--serial" => o.serial = a.next(),
            "--hidpi" => o.hidpi = a.next().map(|v| v != "false").unwrap_or(true),
            "--bitrate-mbps" => {
                o.bitrate = a.next().and_then(|v| v.parse::<u32>().ok()).unwrap_or(20) * 1_000_000
            }
            _ => {}
        }
    }
    o
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    if std::env::args().any(|arg| arg == "--request-permissions") {
        // One-shot: get this binary listed in Accessibility / Screen Recording, then exit.
        unsafe {
            if !wp_input_trusted() {
                wp_input_request_trust();
            }
            if !wp_screen_capture_trusted() {
                wp_screen_capture_request();
            }
        }
        tokio::time::sleep(Duration::from_secs(2)).await;
        return Ok(());
    }
    if std::env::args().any(|arg| arg == "--help" || arg == "-h") {
        println!("Usage: wingpaned [--help] [--port <port>] [--no-adb] [--serial <id>] [--hidpi <true|false>] [--bitrate-mbps <n>] [--test-pattern] [--stats] [--status-json]");
        return Ok(());
    }
    let mut opts = options();
    if opts.status_json {
        // Keep the blocking read off Tokio; parent-process polling handles shutdown.
        std::thread::spawn(|| wait_for_stdin_eof(std::io::stdin().lock()));
    }
    if opts.status_json {
        emit_status(StatusEvent::Starting {
            version: env!("CARGO_PKG_VERSION").into(),
        });
        // Report the permissions of THIS process (macOS grants are per code identity) on every change.
        tokio::spawn(async {
            let mut last: Option<(bool, bool, bool)> = None;
            loop {
                let now = unsafe { (wp_input_trusted(), wp_screen_capture_trusted(), false) };
                if last != Some(now) {
                    last = Some(now);
                    emit_status(StatusEvent::Permissions { input_trusted: now.0, screen_recording: now.1 });
                }
                tokio::time::sleep(Duration::from_secs(2)).await;
            }
        });
    }
    #[cfg(unix)]
    let mut sigint = tokio::signal::unix::signal(tokio::signal::unix::SignalKind::interrupt())?;
    #[cfg(unix)]
    let mut sigterm = tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate())?;
    if !unsafe { wp_input_trusted() } {
        eprintln!("Grant Accessibility to this terminal: System Settings › Privacy & Security › Accessibility");
    }
    if opts.adb {
        #[cfg(unix)]
        let serial = tokio::select! {
            result = wait_for_adb(opts.port, opts.serial.as_deref(), opts.status_json) => Some(result?),
            _ = sigint.recv() => None,
            _ = sigterm.recv() => None,
        };
        #[cfg(not(unix))]
        let serial = tokio::select! {
            result = wait_for_adb(opts.port, opts.serial.as_deref(), opts.status_json) => Some(result?),
            _ = tokio::signal::ctrl_c() => None,
        };
        let Some(serial) = serial else {
            eprintln!("shutdown signal received while waiting for adb");
            if opts.status_json {
                emit_status(StatusEvent::Stopped);
            }
            return Ok(());
        };
        opts.serial = Some(serial);
        if opts.status_json {
            let serial = opts.serial.as_deref().unwrap();
            emit_status(StatusEvent::Device {
                serial: serial.into(),
                model: device_model(serial).await,
            });
        }
    }
    let listener = match TcpListener::bind(("127.0.0.1", opts.port)).await {
        Ok(listener) => listener,
        Err(error) => {
            if opts.status_json {
                emit_status(StatusEvent::Error {
                    message: error.to_string(),
                });
            }
            return Err(error.into());
        }
    };
    eprintln!("wingpaned listening on 127.0.0.1:{}", opts.port);
    if opts.status_json {
        emit_status(StatusEvent::Listening { port: opts.port });
    }
    let adb_task = opts
        .adb
        .then(|| tokio::spawn(adb_monitor(opts.port, opts.serial.clone().unwrap())));
    let mut sessions = JoinSet::new();
    // Match the display refresh and avoid flooding the main queue with redundant AppKit pumps.
    let mut ui_tick = tokio::time::interval(Duration::from_micros(16_667));
    loop {
        tokio::select! {
            _ = ui_tick.tick(), if opts.test_pattern => unsafe { wp_test_pattern_pump() },
            _ = async { #[cfg(unix)] { tokio::select! { _ = sigint.recv() => (), _ = sigterm.recv() => () } } #[cfg(not(unix))] { let _ = tokio::signal::ctrl_c().await; } } => { eprintln!("shutdown signal received"); sessions.abort_all(); while sessions.join_next().await.is_some() {} break; },
            _ = async { loop { tokio::time::sleep(Duration::from_secs(1)).await; if if_parent_dead() { break; } } }, if opts.status_json => { eprintln!("parent process exited"); sessions.abort_all(); while sessions.join_next().await.is_some() {} break; },
            result = listener.accept() => {
                let (stream, peer) = result?;
                if !sessions.is_empty() {
                    reject_busy(stream).await;
                    continue;
                }
                eprintln!("client connected: {peer}");
                if opts.status_json { emit_status(StatusEvent::ClientConnected { peer: peer.to_string() }); }
                let status = opts.status_json;
                sessions.spawn(run_session(stream, opts.clone(), status));
            }
            _ = sessions.join_next(), if !sessions.is_empty() => {}
        }
    }
    if let Some(task) = adb_task {
        task.abort();
        let _ = task.await;
    }
    if opts.adb {
        let _ = adb(&[
            "-s",
            opts.serial.as_deref().unwrap(),
            "reverse",
            "--remove",
            &format!("tcp:{}", opts.port),
        ])
        .await;
    }
    eprintln!("wingpaned stopped");
    if opts.status_json {
        emit_status(StatusEvent::Stopped);
    }
    Ok(())
}

async fn reject_busy(stream: TcpStream) {
    if let Ok(mut framed) = FramedTcp::new(stream) {
        let _ = framed.send(&Packet::Control(busy_rejection())).await;
    }
}

async fn wait_for_adb(
    port: u16,
    requested: Option<&str>,
    status_json: bool,
) -> Result<String, Box<dyn std::error::Error>> {
    let mut missing_reported = false;
    if let Some(serial) = requested {
        loop {
            let state = match adb(&["-s", serial, "get-state"]).await {
                Ok(s) => {
                    missing_reported = false;
                    s
                }
                Err(e) if e.kind() == std::io::ErrorKind::NotFound => {
                    if status_json && !missing_reported {
                        emit_status(StatusEvent::AdbMissing);
                        missing_reported = true;
                    }
                    tokio::time::sleep(Duration::from_secs(2)).await;
                    continue;
                }
                Err(e) => return Err(e.into()),
            };
            if state.status.success() && String::from_utf8_lossy(&state.stdout).trim() == "device" {
                let spec = format!("tcp:{port}");
                let result = adb(&["-s", serial, "reverse", &spec, &spec]).await?;
                if result.status.success() {
                    eprintln!("adb reverse active for {serial}");
                    return Ok(serial.to_owned());
                }
                eprintln!(
                    "adb reverse failed: {}",
                    String::from_utf8_lossy(&result.stderr)
                );
            } else {
                eprintln!("waiting for selected adb device {serial}");
                if status_json {
                    let state_name = String::from_utf8_lossy(&state.stdout).trim().to_owned();
                    let model = device_model(serial).await;
                    emit_status(StatusEvent::WaitingDevice {
                        reason: if state_name == "unauthorized" {
                            "unauthorized"
                        } else {
                            "none"
                        }
                        .into(),
                        devices: vec![DeviceStatus {
                            serial: serial.into(),
                            state: state_name,
                            model,
                        }],
                    });
                }
            }
            tokio::time::sleep(Duration::from_secs(1)).await;
        }
    }
    loop {
        let out = match adb(&["devices"]).await {
            Ok(v) => v,
            Err(e) => {
                eprintln!("waiting for adb: {e}");
                if e.kind() == std::io::ErrorKind::NotFound && status_json && !missing_reported {
                    emit_status(StatusEvent::AdbMissing);
                    missing_reported = true;
                }
                tokio::time::sleep(Duration::from_secs(2)).await;
                continue;
            }
        };
        missing_reported = false;
        let text = String::from_utf8_lossy(&out.stdout);
        let devices: Vec<_> = text
            .lines()
            .skip(1)
            .filter_map(|l| {
                let mut p = l.split_whitespace();
                let id = p.next()?;
                let state = p.next()?;
                Some(DeviceStatus {
                    serial: id.into(),
                    state: state.into(),
                    model: None,
                })
            })
            .collect();
        let ready: Vec<_> = devices.iter().filter(|d| d.state == "device").collect();
        if devices.iter().any(|d| d.state == "unauthorized") {
            eprintln!("adb device unauthorized: accept the USB debugging prompt on the tablet");
        }
        let serial = if ready.len() == 1 && devices.len() == 1 {
            ready[0].serial.to_owned()
        } else if devices.len() > 1 {
            eprintln!("Multiple adb devices are attached:");
            for d in &devices {
                eprintln!("  {}\t{}", d.serial, d.state);
            }
            if status_json {
                emit_status(StatusEvent::WaitingDevice {
                    reason: "multiple".into(),
                    devices: with_models(devices).await,
                });
            }
            tokio::time::sleep(Duration::from_secs(2)).await;
            continue;
        } else {
            eprintln!(
                "waiting for one adb device in device state (found {})",
                ready.len()
            );
            let reason = if devices.iter().any(|d| d.state == "unauthorized") {
                "unauthorized"
            } else {
                "none"
            };
            if status_json {
                emit_status(StatusEvent::WaitingDevice {
                    reason: reason.into(),
                    devices: with_models(devices).await,
                });
            }
            tokio::time::sleep(Duration::from_secs(2)).await;
            continue;
        };
        {
            let spec = format!("tcp:{port}");
            let result = adb(&["-s", &serial, "reverse", &spec, &spec]).await?;
            if !result.status.success() {
                eprintln!(
                    "adb reverse failed: {}",
                    String::from_utf8_lossy(&result.stderr)
                );
                tokio::time::sleep(Duration::from_secs(1)).await;
                continue;
            }
            eprintln!("adb reverse active for {serial}");
            return Ok(serial);
        }
    }
}
async fn adb_monitor(port: u16, serial: String) {
    let mut reverse_active = true;
    loop {
        tokio::time::sleep(Duration::from_secs(1)).await;
        let state = match adb(&["-s", &serial, "get-state"]).await {
            Ok(v) => v,
            Err(e) => {
                eprintln!("adb monitor: {e}");
                continue;
            }
        };
        if state.status.success() && String::from_utf8_lossy(&state.stdout).trim() == "device" {
            let spec = format!("tcp:{port}");
            // An adb server restart drops the tunnel without the device ever leaving `device` state,
            // so verify it is still listed instead of trusting the flag.
            if reverse_active {
                let listed = adb(&["-s", &serial, "reverse", "--list"]).await;
                let still_there = matches!(&listed, Ok(r) if r.status.success()
                    && String::from_utf8_lossy(&r.stdout).contains(&spec));
                if still_there || listed.is_err() {
                    continue;
                }
                eprintln!("adb reverse lost for {serial}; re-adding");
            }
            match adb(&["-s", &serial, "reverse", &spec, &spec]).await {
                Ok(result) if result.status.success() => reverse_active = true,
                Ok(result) => eprintln!(
                    "adb reverse failed: {}",
                    String::from_utf8_lossy(&result.stderr)
                ),
                Err(e) => eprintln!("adb reverse failed: {e}"),
            }
        } else {
            reverse_active = false;
        }
    }
}
fn if_parent_dead() -> bool {
    #[cfg(unix)]
    {
        unsafe extern "C" {
            fn getppid() -> i32;
        }
        unsafe { getppid() == 1 }
    }
    #[cfg(not(unix))]
    {
        false
    }
}
async fn device_model(serial: &str) -> Option<String> {
    let result = tokio::time::timeout(
        Duration::from_secs(2),
        adb(&["-s", serial, "shell", "getprop", "ro.product.model"]),
    )
    .await
    .ok()?
    .ok()?;
    if !result.status.success() {
        return None;
    }
    let model = String::from_utf8_lossy(&result.stdout).trim().to_owned();
    (!model.is_empty()).then_some(model)
}
async fn with_models(mut devices: Vec<DeviceStatus>) -> Vec<DeviceStatus> {
    for d in &mut devices {
        d.model = device_model(&d.serial).await;
    }
    devices
}
fn adb_candidates(
    android_home: Option<&str>,
    home: Option<&str>,
    wingpane_adb: Option<&str>,
    path: Option<&str>,
) -> Vec<String> {
    let mut c = Vec::new();
    if let Some(a) = wingpane_adb {
        c.push(a.into());
    }
    if let Some(a) = android_home {
        c.push(format!("{a}/platform-tools/adb"));
    }
    if let Some(a) = home {
        c.push(format!("{a}/Library/Android/sdk/platform-tools/adb"));
    }
    c.extend(["/opt/homebrew/bin/adb".into(), "/usr/local/bin/adb".into()]);
    if let Some(path) = path {
        c.extend(std::env::split_paths(path).map(|p| p.join("adb").to_string_lossy().into_owned()));
    }
    c
}
fn find_adb_path(candidates: Vec<String>, is_file: impl Fn(&str) -> bool) -> Option<String> {
    candidates.into_iter().find(|candidate| is_file(candidate))
}
async fn adb(args: &[&str]) -> Result<std::process::Output, std::io::Error> {
    let candidates = adb_candidates(
        std::env::var("ANDROID_HOME").ok().as_deref(),
        std::env::var("HOME").ok().as_deref(),
        std::env::var("WINGPANE_ADB").ok().as_deref(),
        std::env::var("PATH").ok().as_deref(),
    );
    let Some(found) = find_adb_path(candidates, |c| Path::new(c).is_file()) else {
        return Err(std::io::Error::new(
            std::io::ErrorKind::NotFound,
            "adb not found in configured locations or PATH",
        ));
    };
    let candidates = std::iter::once(found);
    for a in candidates {
        let result = tokio::process::Command::new(&a)
            .kill_on_drop(true)
            .args(args)
            .output()
            .await;
        if let Ok(o) = result {
            return Ok(o);
        }
    }
    Err(std::io::Error::new(
        std::io::ErrorKind::NotFound,
        "adb not found in configured locations or PATH",
    ))
}

// WP-U5 extraction boundary: the display transition body below is unchanged.
struct ModeContext<'a> {
    status_json: bool,
    client_video_suspend: &'a AtomicBool,
    shared_mode: &'a AtomicU8,
    context_addr: usize,
    display_id: &'a AtomicU32,
    capture_slot: &'a Mutex<Option<ffi::Capture>>,
    display_slot: &'a Mutex<Option<ffi::VirtualDisplay>>,
    queue: &'a FrameQueue,
    logical_w: u32,
    logical_h: u32,
    hz: u32,
    opts: &'a Options,
    client_w: u32,
    client_h: u32,
    bitrate: u32,
    awaiting_key: &'a AtomicBool,
}
impl ModeContext<'_> {
    fn apply_mode(&self, next_mode: Mode, input_router: &mut InputRouter<'_>) -> Result<(), ()> {
        if next_mode == Mode::AndroidControl {
            eprintln!("ignoring removed AndroidControl mode request");
            return Ok(());
        }
        // Every mode change restores local input before InputRouter::set_mode.
        let previous_mode = input_router.mode();
        input_router.set_mode(next_mode);
        self.shared_mode.store(
            match next_mode {
                Mode::ExtendedDisplay => 0,
                Mode::GraphicsTablet => 1,
                Mode::TrackpadKeyboard => 2,
                Mode::AndroidControl => unreachable!("removed mode is rejected above"),
            },
            Ordering::Release,
        );
        if self.status_json {
            emit_status(StatusEvent::Mode { mode: next_mode });
        }
        let suspend = self.client_video_suspend.load(Ordering::Acquire);
        match display_transition(previous_mode, next_mode, suspend) {
            DisplayTransition::Remove => {
                let ctx = unsafe { &*(self.context_addr as *const CallbackContext) };
                ctx.video_enabled.store(false, Ordering::Release);
                self.display_id.store(0, Ordering::Release);
                self.capture_slot.lock().unwrap().take();
                self.display_slot.lock().unwrap().take();
                self.queue.clear();
            }
            DisplayTransition::Create => {
                let recreated = (|| -> Result<(), String> {
                    let new_display = ffi::VirtualDisplay::create(
                        self.logical_w, self.logical_h, self.hz, self.opts.hidpi,
                    )?;
                    let new_capture = unsafe {
                        ffi::Capture::start(
                            new_display.id(),
                            self.client_w,
                            self.client_h,
                            self.hz,
                            self.bitrate,
                            on_frame,
                            (self.context_addr as *mut CallbackContext).cast(),
                        )?
                    };
                    let handle = new_capture.handle();
                    *self.display_slot.lock().unwrap() = Some(new_display);
                    *self.capture_slot.lock().unwrap() = Some(new_capture);
                    self.display_id.store(
                        self.display_slot.lock().unwrap().as_ref().unwrap().id(),
                        Ordering::Release,
                    );
                    let ctx = unsafe { &*(self.context_addr as *const CallbackContext) };
                    ctx.handle.store(handle, Ordering::Release);
                    ctx.video_enabled.store(true, Ordering::Release);
                    Ok(())
                })();
                if let Err(e) = recreated {
                    eprintln!("virtual display recreation failed: {e}");
                    if self.status_json {
                        emit_status(StatusEvent::Error { message: e });
                    }
                    return Err(());
                }
                self.awaiting_key.store(true, Ordering::Release);
                let handle = unsafe { &*(self.context_addr as *const CallbackContext) }
                    .handle
                    .load(Ordering::Acquire);
                if handle != 0 {
                    unsafe { request_keyframe(handle) };
                }
            }
            DisplayTransition::None => {}
        }
        Ok(())
    }
}

async fn run_session(stream: TcpStream, opts: Options, status_json: bool) {
    let mut end_log = DisconnectLog {
        reason: "session setup ended".to_owned(),
        status_json,
    };
    match set_send_buffer(&stream, 256 * 1024) {
        Ok(effective) => eprintln!("TCP SO_SNDBUF requested=262144 effective={effective} bytes"),
        Err(error) => eprintln!("TCP SO_SNDBUF request failed: {error}"),
    }
    let Ok(mut framed) = FramedTcp::new(stream) else {
        end_log.reason = "invalid framed stream".into();
        return;
    };
    let Ok(Some(Packet::Control(first))) = framed.recv().await else {
        end_log.reason = "peer closed before hello".into();
        return;
    };
    let dims = match &first {
        ControlMessage::Hello { screen_size_px, .. } => *screen_size_px,
        _ => (0, 0),
    };
    if !matches!(first, ControlMessage::Hello { .. }) {
        end_log.reason = "expected hello".into();
        let _ = framed
            .send(&Packet::Control(ControlMessage::Reject(
                RejectReason::InvalidHello,
            )))
            .await;
        return;
    }
    let mut session = Session::new();
    let Some(mut reply) = session.handle_message(first) else {
        end_log.reason = "hello ignored".into();
        return;
    };
    if matches!(reply.send, ControlMessage::Reject(_)) {
        end_log.reason = "hello rejected".into();
        let _ = framed.send(&Packet::Control(reply.send)).await;
        return;
    }
    let hz = match reply.send {
        ControlMessage::Accept {
            virtual_display_spec: Some((_, _, hz)),
            ..
        } => hz,
        _ => return,
    };
    if let ControlMessage::Accept { bitrate_kbps, .. } = &mut reply.send {
        *bitrate_kbps = opts.bitrate / 1_000;
    }
    if !unsafe { wp_screen_capture_trusted() } {
        end_log.reason = "screen recording permission missing".into();
        eprintln!("Screen Recording permission missing: System Settings › Privacy & Security › Screen & System Audio Recording → enable Wingpane");
        if status_json {
            emit_status(StatusEvent::Error {
                message: "Screen Recording permission missing".to_owned(),
            });
        }
        let _ = framed
            .send(&Packet::Control(ControlMessage::Reject(
                RejectReason::ScreenRecordingDenied,
            )))
            .await;
        return;
    }
    let (client_w, client_h) = dims;
    let logical_w = if opts.hidpi { client_w / 2 } else { client_w };
    let logical_h = if opts.hidpi { client_h / 2 } else { client_h };
    let display = match ffi::VirtualDisplay::create(logical_w, logical_h, hz, opts.hidpi) {
        Ok(d) => d,
        Err(e) => {
            end_log.reason = "display creation failed".into();
            eprintln!("display creation failed: {e}");
            if status_json {
                emit_status(StatusEvent::Error {
                    message: e.to_string(),
                });
            }
            return;
        }
    };
    if opts.test_pattern {
        unsafe { wp_test_pattern_show(display.id()) };
    }
    let _test_pattern = TestPatternGuard(opts.test_pattern.then(|| display.id()));
    let _ = framed.send(&Packet::Control(reply.send)).await;
    let queue = Arc::new(FrameQueue::default());
    let latency = Arc::new(LatencyWindow::default());
    let context = Box::new(CallbackContext {
        queue: queue.clone(),
        latency: latency.clone(),
        handle: AtomicU64::new(0),
        video_enabled: AtomicBool::new(true),
        encoded: AtomicU64::new(0),
        backlog_dropped: AtomicU64::new(0),
    });
    let context_addr = Box::into_raw(context) as usize;
    let _context_guard = ContextGuard(context_addr);
    let bitrate = opts.bitrate;
    let capture = match unsafe {
        ffi::Capture::start(
            display.id(),
            client_w,
            client_h,
            hz,
            bitrate,
            on_frame,
            (context_addr as *mut CallbackContext).cast(),
        )
    } {
        Ok(c) => c,
        Err(e) => {
            end_log.reason = "capture start failed".into();
            eprintln!("capture start failed: {e}");
            if status_json {
                emit_status(StatusEvent::Error {
                    message: e.to_string(),
                });
            }
            return;
        }
    };
    unsafe {
        (*(context_addr as *mut CallbackContext))
            .handle
            .store(capture.handle(), Ordering::Relaxed);
    }
    let display_id = Arc::new(AtomicU32::new(display.id()));
    let capture_slot = Arc::new(Mutex::new(Some(capture)));
    let display_slot = Arc::new(Mutex::new(Some(display)));
    let mut resources = SessionResources {
        capture: Box::new(CaptureSlot(capture_slot.clone())),
        input: Box::new(NativeInputSink(display_id.clone())),
        display: Box::new(DisplaySlot(display_slot.clone())),
    };
    if status_json {
        emit_status(StatusEvent::Streaming {
            width: client_w,
            height: client_h,
            hz,
            hidpi: opts.hidpi,
            input_trusted: unsafe { wp_input_trusted() },
        });
    }
    let stats_queue = queue.clone();
    let stats_latency = latency.clone();
    let writer_latency = latency.clone();
    let writer_queue = queue.clone();
    let stats_sent = Arc::new(AtomicU64::new(0));
    let writer_sent = stats_sent.clone();
    let shared_mode = Arc::new(AtomicU8::new(0));
    let writer_mode = shared_mode.clone();
    let client_video_suspend = Arc::new(AtomicBool::new(false));
    let writer_video_suspend = client_video_suspend.clone();
    let awaiting_key = Arc::new(AtomicBool::new(false));
    let writer_awaiting_key = awaiting_key.clone();
    // First tick after 1 s (tokio intervals fire immediately), so the first stats line is a full second.
    let mut stats_tick = tokio::time::interval_at(
        tokio::time::Instant::now() + Duration::from_secs(1),
        Duration::from_secs(1),
    );
    let mut previous_stats = (0u64, 0u64, 0u64, 0u64);
    let (mut reader, mut writer) = framed.into_split();
    // FramedReader::recv uses read_exact and is not cancellation-safe. Keep each
    // read alive across UC events/timers; only select on the bounded packet queue.
    let (incoming_tx, mut incoming_rx) = mpsc::channel(32);
    let reader_task = tokio::spawn(async move {
        loop {
            let result = reader.recv().await;
            let ended = matches!(&result, Ok(None)) || matches!(&result, Err(error)
                if !matches!(error, wingpane_core::transport::tcp::TransportError::UnknownPacket));
            if incoming_tx.send(result).await.is_err() || ended { break; }
        }
    });
    let _reader_guard = AbortTask(reader_task.abort_handle());
    let (control_tx, mut control_rx) = mpsc::unbounded_channel::<Packet>();
    let mut writer_task = tokio::spawn(async move {
        let mut seq = 0u32;
        loop {
            tokio::select! { biased;
                Some(p) = control_rx.recv() => if writer.send(&p).await.is_err() { break; },
                frame = writer_queue.pop() => {
                    let mode = match writer_mode.load(Ordering::Acquire) {
                        1 => Mode::GraphicsTablet,
                        2 => Mode::TrackpadKeyboard,
                        _ => Mode::ExtendedDisplay,
                    };
                    let suspend = writer_video_suspend.load(Ordering::Acquire);
                    let waiting_for_key = writer_awaiting_key.load(Ordering::Acquire);
                    if suspend && !should_send_video(mode, waiting_for_key, frame.keyframe) {
                        continue;
                    }
                    let p = Packet::Video(VideoPacket { frame_seq: seq, pts_us: frame.pts_us, is_keyframe: frame.keyframe, payload: frame.data }); seq = seq.wrapping_add(1);
                    let frame_bytes = match &p { Packet::Video(video) => video.payload.len(), _ => 0 };
                    let encode_done_us = frame.encode_done_us;
                    if writer.send(&p).await.is_err() { break; }
                    if suspend && frame.keyframe {
                        writer_awaiting_key.store(false, Ordering::Release);
                    }
                    writer_latency.written(ffi::host_time_us().saturating_sub(encode_done_us), frame_bytes, frame.keyframe);
                    writer_sent.fetch_add(1, Ordering::Relaxed);
                }
            }
        }
    });
    let _writer_guard = AbortTask(writer_task.abort_handle());
    let mode_context = ModeContext {
        status_json, client_video_suspend: &client_video_suspend, shared_mode: &shared_mode,
        context_addr, display_id: &display_id, capture_slot: &capture_slot,
        display_slot: &display_slot, queue: &queue, logical_w, logical_h, hz, opts: &opts,
        client_w, client_h, bitrate, awaiting_key: &awaiting_key,
    };
    let mut input_router = InputRouter::new(Mode::ExtendedDisplay, resources.input.as_mut());
    let mut unknown_packet_warned = false;
    let mut input_trusted = unsafe { wp_input_trusted() };
    let mut client_capabilities_seen = false;
    let mut trust_check_at = tokio::time::Instant::now();
    loop {
        let received = tokio::select! { biased;
                _ = &mut writer_task => { end_log.reason = "writer ended".into(); break; }
                // Apply queued client requests before a simultaneous host command;
                // the host then sends the final SetMode (D-2).
                result = incoming_rx.recv() => match result {
                    Some(result) => Some(result),
                    None => { end_log.reason = "reader ended".into(); break; }
                },
                _ = stats_tick.tick(), if opts.stats || status_json => {
                    let ctx = unsafe { &*(context_addr as *const CallbackContext) };
                    let handle = ctx.handle.load(Ordering::Relaxed);
                    let current = (ffi::capture_frames_captured(handle), ctx.encoded.load(Ordering::Relaxed), stats_sent.load(Ordering::Relaxed), stats_queue.dropped_frames());
                    let mut samples = stats_latency.take();
                    if status_json {
                        let fps = current.0.saturating_sub(previous_stats.0);
                        let encode = percentile(&mut samples.encode_us, 0.95) as f64 / 1000.0;
                        let write = percentile(&mut samples.write_us, 0.95) as f64 / 1000.0;
                        emit_status(StatusEvent::Stats { fps, encode_p95_ms: encode, write_p95_ms: write, backlog_dropped: current.3 });
                    }
                    let encode_max = samples.encode_us.iter().copied().max().unwrap_or(0);
                    let write_max = samples.write_us.iter().copied().max().unwrap_or(0);
                    eprintln!("host stats: sck={} encoded={} sent={} backlog_dropped={} encode_capture_p50_us={} encode_capture_p95_us={} encode_capture_max_us={} encode_samples={} write_p50_us={} write_p95_us={} write_max_us={} write_samples={} bytes_frame_p95={} keyframes={}", current.0-previous_stats.0, current.1-previous_stats.1, current.2-previous_stats.2, current.3-previous_stats.3, percentile(&mut samples.encode_us, 0.50), percentile(&mut samples.encode_us, 0.95), encode_max, samples.encode_us.len(), percentile(&mut samples.write_us, 0.50), percentile(&mut samples.write_us, 0.95), write_max, samples.write_us.len(), percentile(&mut samples.frame_bytes, 0.95), samples.keyframes);
                    previous_stats = current;
                    None
                }
        };
        let Some(received) = received else { continue };
        match received {
            Ok(Some(Packet::Control(ControlMessage::Disconnect))) => {
                end_log.reason = "client requested disconnect".into();
                break;
            }
            Ok(None) => {
                end_log.reason = "peer closed connection".into();
                break;
            }
            Err(e) => {
                if matches!(
                    e,
                    wingpane_core::transport::tcp::TransportError::UnknownPacket
                ) {
                    if !unknown_packet_warned {
                        eprintln!("ignoring unknown protocol packet");
                        unknown_packet_warned = true;
                    }
                    continue;
                }
                end_log.reason = format!("read error: {e}");
                break;
            }
            Ok(Some(Packet::Control(message))) => {
                let message = match message {
                    ControlMessage::SetMode(next_mode) => {
                        if mode_context.apply_mode(next_mode, &mut input_router).is_err() {
                            end_log.reason = "display recreation failed".into();
                            break;
                        }
                        continue;
                    }
                    ControlMessage::PenConfig {
                        pressure_curve,
                        target,
                    } => {
                        input_router.set_pen_config(pressure_curve, target);
                        continue;
                    }
                    ControlMessage::Capabilities { features } => {
                        client_capabilities_seen = true;
                        client_video_suspend.store(features & FEATURE_VIDEO_SUSPEND != 0, Ordering::Release);
                        let _ = control_tx.send(Packet::Control(ControlMessage::Capabilities {features:host_features(input_trusted)}));
                        continue;
                    }
                    other => other,
                };
                for reply in wingpaned::handle_control_messages(
                    resources.capture.as_ref(),
                    message,
                    ffi::host_time_us(),
                    host_features(input_trusted),
                ) {
                    if control_tx.send(Packet::Control(reply)).is_err() {
                        break;
                    }
                }
            }
            Ok(Some(Packet::Input(input))) => {
                if tokio::time::Instant::now() >= trust_check_at {
                    trust_check_at = tokio::time::Instant::now() + Duration::from_secs(2);
                    let now_trusted = unsafe { wp_input_trusted() };
                    if now_trusted != input_trusted {
                        input_trusted = now_trusted;
                        if status_json && !input_trusted {
                            emit_status(StatusEvent::Error {
                                message: "Accessibility permission missing".into(),
                            });
                        }
                        if client_capabilities_seen {
                            let features =
                                host_features(input_trusted);
                            let _ = control_tx
                                .send(Packet::Control(ControlMessage::Capabilities { features }));
                        }
                    }
                }
                input_router.handle_input(input);
            }
            Ok(Some(_)) => {}
        }
    }
    drop(_test_pattern);
    drop(input_router);
    drop(resources);
    if !writer_task.is_finished() { writer_task.abort(); let _ = writer_task.await; }
}

#[cfg(target_os = "macos")]
fn set_send_buffer(stream: &TcpStream, bytes: i32) -> std::io::Result<i32> {
    use std::os::fd::AsRawFd;
    unsafe extern "C" {
        fn setsockopt(fd: i32, level: i32, option: i32, value: *const c_void, length: u32) -> i32;
        fn getsockopt(
            fd: i32,
            level: i32,
            option: i32,
            value: *mut c_void,
            length: *mut u32,
        ) -> i32;
    }
    // macOS/BSD socket constants. FramedTcp sets TCP_NODELAY separately.
    const SOL_SOCKET: i32 = 0xffff;
    const SO_SNDBUF: i32 = 0x1001;
    let result = unsafe {
        setsockopt(
            stream.as_raw_fd(),
            SOL_SOCKET,
            SO_SNDBUF,
            (&bytes as *const i32).cast(),
            std::mem::size_of_val(&bytes) as u32,
        )
    };
    if result == 0 {
        let mut effective = 0i32;
        let mut length = std::mem::size_of_val(&effective) as u32;
        let read_result = unsafe {
            getsockopt(
                stream.as_raw_fd(),
                SOL_SOCKET,
                SO_SNDBUF,
                (&mut effective as *mut i32).cast(),
                &mut length,
            )
        };
        if read_result == 0 {
            Ok(effective)
        } else {
            Err(std::io::Error::last_os_error())
        }
    } else {
        Err(std::io::Error::last_os_error())
    }
}

#[cfg(not(target_os = "macos"))]
fn set_send_buffer(_: &TcpStream, _: i32) -> std::io::Result<i32> {
    Ok(0)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn adb_candidate_precedence_is_stable() {
        let paths = adb_candidates(
            Some("/sdk"),
            Some("/users/u"),
            Some("/custom/adb"),
            Some("/bin:/sbin"),
        );
        assert_eq!(
            paths,
            [
                "/custom/adb",
                "/sdk/platform-tools/adb",
                "/users/u/Library/Android/sdk/platform-tools/adb",
                "/opt/homebrew/bin/adb",
                "/usr/local/bin/adb",
                "/bin/adb",
                "/sbin/adb"
            ]
        );
        let absent_android_home = adb_candidates(None, None, None, None);
        assert_eq!(
            absent_android_home,
            ["/opt/homebrew/bin/adb", "/usr/local/bin/adb"]
        );
        let selected = find_adb_path(paths, |p| p == "/usr/local/bin/adb");
        assert_eq!(selected.as_deref(), Some("/usr/local/bin/adb"));
        assert_eq!(find_adb_path(vec!["missing".into()], |_| false), None);
    }
    #[tokio::test]
    async fn concurrent_client_reads_reject_busy() {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let client = TcpStream::connect(listener.local_addr().unwrap())
            .await
            .unwrap();
        let (server, _) = listener.accept().await.unwrap();
        let reject = tokio::spawn(reject_busy(server));
        let mut framed = FramedTcp::new(client).unwrap();
        assert_eq!(
            framed.recv().await.unwrap(),
            Some(Packet::Control(busy_rejection()))
        );
        reject.await.unwrap();
    }
}

struct AbortTask(tokio::task::AbortHandle);
impl Drop for AbortTask { fn drop(&mut self) { self.0.abort(); } }

fn host_features(input_trusted: bool) -> u32 {
    HOST_INPUT_FEATURES | if input_trusted {1<<6} else {0}
}


fn wait_for_stdin_eof(mut reader: impl std::io::Read) {
    let mut buffer = [0u8; 4096];
    loop {
        match reader.read(&mut buffer) {
            Ok(0) | Err(_) => break,
            Ok(_) => {} // Legacy command input is ignored; parent polling handles shutdown.
        }
    }
}
