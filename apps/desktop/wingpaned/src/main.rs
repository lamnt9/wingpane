mod ffi;
use std::{
    ffi::c_void,
    sync::{
        atomic::{AtomicU64, Ordering},
        Arc,
    },
    time::Duration,
};
use tokio::{
    net::{TcpListener, TcpStream},
    sync::mpsc,
    task::JoinSet,
};
use wingpane_core::{session::Session, transport::tcp::FramedTcp};
use wingpane_proto::{ControlMessage, Packet, RejectReason, TouchPhase, VideoPacket};
use wingpaned::{
    busy_rejection, handle_control, handle_input, EncodedFrame, FrameQueue, InputSink,
    SessionResources,
};

struct CallbackContext {
    queue: Arc<FrameQueue>,
    handle: AtomicU64,
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
    let bytes = unsafe { std::slice::from_raw_parts(data, len) }.to_vec();
    ctx.queue.push_with_overflow(
        EncodedFrame {
            data: bytes,
            keyframe: key != 0,
            pts_us: pts.max(0) as u64,
        },
        || {
            let handle = ctx.handle.load(Ordering::Relaxed);
            if handle != 0 {
                unsafe { request_keyframe(handle) };
            }
        },
    );
}
unsafe extern "C" {
    fn wp_capture_force_keyframe(handle: u64);
    fn wp_test_pattern_show(display_id: u32);
    fn wp_test_pattern_hide(display_id: u32);
    fn wp_test_pattern_pump();
    fn wp_input_pointer(display_id: u32, x: f64, y: f64, phase: u8);
    fn wp_input_release_all();
    fn wp_input_trusted() -> bool;
}
struct TestPatternGuard(Option<u32>);
impl Drop for TestPatternGuard {
    fn drop(&mut self) {
        if let Some(id) = self.0 {
            unsafe { wp_test_pattern_hide(id) };
        }
    }
}
struct NativeInputSink(u32);
impl InputSink for NativeInputSink {
    fn pointer(&self, x: f32, y: f32, phase: TouchPhase) {
        unsafe { wp_input_pointer(self.0, f64::from(x), f64::from(y), phase as u8) };
    }
    fn release_all(&self) {
        unsafe { wp_input_release_all() }
    }
}
unsafe fn request_keyframe(handle: u64) {
    wp_capture_force_keyframe(handle);
}

#[derive(Clone)]
struct Options {
    port: u16,
    adb: bool,
    hidpi: bool,
    bitrate: u32,
    test_pattern: bool,
    serial: Option<String>,
}
fn options() -> Options {
    let mut o = Options {
        port: 7788,
        adb: true,
        hidpi: true,
        bitrate: 20_000_000,
        test_pattern: false,
        serial: std::env::var("ANDROID_SERIAL").ok(),
    };
    let mut a = std::env::args().skip(1);
    while let Some(k) = a.next() {
        match k.as_str() {
            "--port" => o.port = a.next().and_then(|v| v.parse().ok()).unwrap_or(7788),
            "--no-adb" => o.adb = false,
            "--test-pattern" => o.test_pattern = true,
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
    if std::env::args().any(|arg| arg == "--help" || arg == "-h") {
        println!("Usage: wingpaned [--help] [--port <port>] [--no-adb] [--serial <id>] [--hidpi <true|false>] [--bitrate-mbps <n>] [--test-pattern]");
        return Ok(());
    }
    let mut opts = options();
    if !unsafe { wp_input_trusted() } {
        eprintln!("Grant Accessibility to this terminal: System Settings › Privacy & Security › Accessibility");
    }
    if opts.adb {
        opts.serial = Some(wait_for_adb(opts.port, opts.serial.as_deref()).await?);
    }
    let listener = TcpListener::bind(("127.0.0.1", opts.port)).await?;
    eprintln!("wingpaned listening on 127.0.0.1:{}", opts.port);
    let adb_task = opts
        .adb
        .then(|| tokio::spawn(adb_monitor(opts.port, opts.serial.clone().unwrap())));
    let mut sessions = JoinSet::new();
    // 120 Hz gives AppKit enough pump opportunities to redraw the test pattern.
    let mut ui_tick = tokio::time::interval(Duration::from_micros(8_333));
    loop {
        tokio::select! {
            _ = ui_tick.tick(), if opts.test_pattern => unsafe { wp_test_pattern_pump() },
            _ = tokio::signal::ctrl_c() => { sessions.abort_all(); while sessions.join_next().await.is_some() {} break; },
            result = listener.accept() => {
                let (stream, peer) = result?;
                if !sessions.is_empty() {
                    reject_busy(stream).await;
                    continue;
                }
                eprintln!("client connected: {peer}");
                sessions.spawn(run_session(stream, opts.clone()));
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
) -> Result<String, Box<dyn std::error::Error>> {
    if let Some(serial) = requested {
        loop {
            let state = adb(&["-s", serial, "get-state"]).await?;
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
            }
            tokio::time::sleep(Duration::from_secs(1)).await;
        }
    }
    loop {
        let out = match adb(&["devices"]).await {
            Ok(v) => v,
            Err(e) => {
                eprintln!("waiting for adb: {e}");
                tokio::time::sleep(Duration::from_secs(1)).await;
                continue;
            }
        };
        let text = String::from_utf8_lossy(&out.stdout);
        let devices: Vec<_> = text
            .lines()
            .skip(1)
            .filter_map(|l| {
                let mut p = l.split_whitespace();
                let id = p.next()?;
                let state = p.next()?;
                Some((id, state))
            })
            .collect();
        let ready: Vec<_> = devices.iter().filter(|(_, s)| *s == "device").collect();
        if devices.iter().any(|(_, s)| *s == "unauthorized") {
            eprintln!("adb device unauthorized: accept the USB debugging prompt on the tablet");
        }
        let serial = if ready.len() == 1 && devices.len() == 1 {
            ready[0].0.to_owned()
        } else if devices.len() > 1 {
            eprintln!("Multiple adb devices are attached:");
            for (id, state) in &devices {
                eprintln!("  {id}\t{state}");
            }
            return Err("select a device with --serial <id> or ANDROID_SERIAL".into());
        } else {
            eprintln!(
                "waiting for one adb device in device state (found {})",
                ready.len()
            );
            tokio::time::sleep(Duration::from_secs(1)).await;
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
            if reverse_active {
                continue;
            }
            let spec = format!("tcp:{port}");
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
async fn adb(args: &[&str]) -> Result<std::process::Output, std::io::Error> {
    let mut candidates = Vec::new();
    if let Ok(home) = std::env::var("ANDROID_HOME") {
        candidates.push(format!("{home}/platform-tools/adb"));
    }
    if let Ok(home) = std::env::var("HOME") {
        candidates.push(format!("{home}/Library/Android/sdk/platform-tools/adb"));
    }
    candidates.push("adb".into());
    for a in candidates {
        let result = tokio::process::Command::new(a).args(args).output().await;
        if let Ok(o) = result {
            return Ok(o);
        }
    }
    Err(std::io::Error::new(
        std::io::ErrorKind::NotFound,
        "adb not found in Android SDK or PATH",
    ))
}

async fn run_session(stream: TcpStream, opts: Options) {
    let Ok(mut framed) = FramedTcp::new(stream) else {
        return;
    };
    let Ok(Some(Packet::Control(first))) = framed.recv().await else {
        return;
    };
    let dims = match &first {
        ControlMessage::Hello { screen_size_px, .. } => *screen_size_px,
        _ => (0, 0),
    };
    if !matches!(first, ControlMessage::Hello { .. }) {
        let _ = framed
            .send(&Packet::Control(ControlMessage::Reject(
                RejectReason::InvalidHello,
            )))
            .await;
        return;
    }
    let mut session = Session::new();
    let Some(mut reply) = session.handle_message(first) else {
        return;
    };
    if matches!(reply.send, ControlMessage::Reject(_)) {
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
    let (client_w, client_h) = dims;
    let logical_w = if opts.hidpi { client_w / 2 } else { client_w };
    let logical_h = if opts.hidpi { client_h / 2 } else { client_h };
    let display = match ffi::VirtualDisplay::create(logical_w, logical_h, hz, opts.hidpi) {
        Ok(d) => d,
        Err(e) => {
            eprintln!("display creation failed: {e}");
            return;
        }
    };
    if opts.test_pattern {
        unsafe { wp_test_pattern_show(display.id()) };
    }
    let _test_pattern = TestPatternGuard(opts.test_pattern.then(|| display.id()));
    let _ = framed.send(&Packet::Control(reply.send)).await;
    let queue = Arc::new(FrameQueue::default());
    let context = Box::new(CallbackContext {
        queue: queue.clone(),
        handle: AtomicU64::new(0),
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
            eprintln!("capture start failed: {e}");
            return;
        }
    };
    unsafe {
        (*(context_addr as *mut CallbackContext))
            .handle
            .store(capture.handle(), Ordering::Relaxed);
    }
    let resources = SessionResources {
        capture: Box::new(capture),
        input: Box::new(NativeInputSink(display.id())),
        display: Box::new(display),
    };
    let (mut reader, mut writer) = framed.into_split();
    let (control_tx, mut control_rx) = mpsc::unbounded_channel::<Packet>();
    let writer_task = tokio::spawn(async move {
        let mut seq = 0u32;
        loop {
            tokio::select! { biased;
                Some(p) = control_rx.recv() => if writer.send(&p).await.is_err() { break; },
                frame = queue.pop() => {
                    let p = Packet::Video(VideoPacket { frame_seq: seq, pts_us: frame.pts_us, is_keyframe: frame.keyframe, payload: frame.data }); seq = seq.wrapping_add(1);
                    if writer.send(&p).await.is_err() { break; }
                }
            }
        }
    });
    let mut warned_unsupported_input = false;
    loop {
        match reader.recv().await {
            Ok(Some(Packet::Control(ControlMessage::Disconnect))) | Ok(None) | Err(_) => break,
            Ok(Some(Packet::Control(message))) => {
                if let Some(reply) = handle_control(resources.capture.as_ref(), message) {
                    if control_tx.send(Packet::Control(reply)).is_err() {
                        break;
                    }
                }
            }
            Ok(Some(Packet::Input(input))) => {
                if !handle_input(resources.input.as_ref(), input) && !warned_unsupported_input {
                    eprintln!("ignoring unsupported input event kinds (only Touch is handled)");
                    warned_unsupported_input = true;
                }
            }
            Ok(Some(_)) => {}
        }
    }
    drop(_test_pattern);
    drop(resources);
    writer_task.abort();
    let _ = writer_task.await;
}

#[cfg(test)]
mod tests {
    use super::*;
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
