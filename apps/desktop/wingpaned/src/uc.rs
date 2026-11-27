//! Pure Universal Control policy. No sockets, native APIs, or wall-clock reads.
use serde::{Deserialize, Serialize};
use wingpane_proto::{ControlMessage, InputEvent, InputPacket, Mode, Packet, ScrollPhase, UcEdge,
    UcExitReason, FEATURE_UNIVERSAL_CONTROL, FEATURE_CLIPBOARD_TEXT, UC_STATUS_SERVICE_CONNECTED, UC_STATUS_GESTURES_OK,
    UC_STATUS_FGS_RUNNING, UC_STATUS_KEYGUARD_LOCKED};

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Rect { pub x: f32, pub y: f32, pub width: f32, pub height: f32 }
impl Rect {
    fn valid(self) -> bool {
        [self.x, self.y, self.width, self.height].iter().all(|v| v.is_finite())
            && self.width > 0.0 && self.height > 0.0
    }
}

pub struct EdgeDetector {
    pub edge: UcEdge,
    /// Main display first, in Quartz global points (including negative coordinates).
    pub displays: Vec<Rect>,
    pub push_pt: f32,
    pub window_ms: u64,
    push: f32,
    started: Option<u64>,
}
impl EdgeDetector {
    pub fn new(edge: UcEdge, displays: Vec<Rect>) -> Self {
        Self { edge, displays, push_pt: if matches!(edge, UcEdge::Top | UcEdge::Bottom) {64.0} else {24.0},
            window_ms: 300, push: 0.0, started: None }
    }
    pub fn reset(&mut self) { self.push = 0.0; self.started = None; }
    pub fn set_edge(&mut self, edge: UcEdge) {
        self.edge = edge;
        self.push_pt = if matches!(edge, UcEdge::Top | UcEdge::Bottom) {64.0} else {24.0};
        self.reset();
    }
    pub fn set_displays(&mut self, displays: Vec<Rect>) {
        if self.displays != displays { self.displays = displays; self.reset(); }
    }
    /// Free intervals normalized to 0..1 along the main display's selected edge.
    pub fn free_spans(&self) -> Vec<(f32, f32)> {
        let Some(m) = self.displays.first().copied().filter(|r| r.valid()) else { return vec![] };
        let vertical = matches!(self.edge, UcEdge::Left | UcEdge::Right);
        let (base, length) = if vertical {(m.y, m.height)} else {(m.x, m.width)};
        let mut spans = vec![(0.0, 1.0)];
        for r in self.displays.iter().skip(1).copied().filter(|r| r.valid()) {
            let adjacent = match self.edge {
                UcEdge::Left => r.x < m.x && r.x + r.width >= m.x - 1.0,
                UcEdge::Right => r.x <= m.x + m.width + 1.0 && r.x + r.width > m.x + m.width,
                UcEdge::Top => r.y < m.y && r.y + r.height >= m.y - 1.0,
                UcEdge::Bottom => r.y <= m.y + m.height + 1.0 && r.y + r.height > m.y + m.height,
            };
            if !adjacent { continue; }
            let (start, extent) = if vertical {(r.y, r.height)} else {(r.x, r.width)};
            let lo = ((start - base) / length).clamp(0.0, 1.0);
            let hi = ((start + extent - base) / length).clamp(0.0, 1.0);
            if lo >= hi { continue; }
            spans = spans.into_iter().flat_map(|(a,b)| {
                if hi <= a || lo >= b { vec![(a,b)] } else {
                    let mut rest = vec![];
                    if a < lo { rest.push((a,lo)); }
                    if hi < b { rest.push((hi,b)); }
                    rest
                }
            }).collect();
        }
        spans
    }
    pub fn on_move(&mut self, x: f32, y: f32, dx: f32, dy: f32, buttons_down: bool, now_ms: u64) -> Option<f32> {
        let Some(m) = self.displays.first().copied().filter(|r| r.valid()) else { self.reset(); return None };
        if buttons_down || [x,y,dx,dy].iter().any(|v| !v.is_finite()) { self.reset(); return None; }
        let (at_edge, push, along) = match self.edge {
            UcEdge::Left => ((x-m.x).abs() <= 1.0, -dx, (y-m.y)/m.height),
            UcEdge::Right => ((x-(m.x+m.width-1.0)).abs() <= 1.0, dx, (y-m.y)/m.height),
            UcEdge::Top => ((y-m.y).abs() <= 1.0, -dy, (x-m.x)/m.width),
            UcEdge::Bottom => ((y-(m.y+m.height-1.0)).abs() <= 1.0, dy, (x-m.x)/m.width),
        };
        let free = self.free_spans().iter().any(|&(a,b)| along >= a && along <= b);
        if !at_edge || !free || push <= 0.0 { self.reset(); return None; }
        if self.started.is_none_or(|start| now_ms < start || now_ms-start > self.window_ms) {
            self.reset(); self.started = Some(now_ms);
        }
        self.push += push;
        if self.push >= self.push_pt { self.reset(); Some(along.clamp(0.0,1.0)) } else { None }
    }
}

pub fn mac_to_hid(keycode: u16) -> Option<u16> { crate::keymap_macos::hid_usage(keycode) }
pub fn mac_flags_to_hid(flags: u64) -> u8 {
    let mut hid = 0;
    for (left, right, aggregate, bit) in [(0x1,0x2000,18,0), (0x2,0x4,17,1), (0x20,0x40,19,2), (0x8,0x10,20,3)] {
        if flags & left != 0 { hid |= 1 << bit; }
        if flags & right != 0 { hid |= 1 << (bit+4); }
        if flags & (left|right) == 0 && flags & (1 << aggregate) != 0 { hid |= 1 << bit; }
    }
    hid
}

#[derive(Default)]
pub struct DeltaCoalescer { pending: Option<(f32, f32, u64)> }
impl DeltaCoalescer {
    pub const WINDOW_US: u64 = 4_000;
    pub fn push(&mut self, event: InputEvent, now_us: u64) -> Vec<InputEvent> {
        let mut out = self.tick(now_us);
        match event {
            InputEvent::PointerDelta {dx,dy} if dx.is_finite() && dy.is_finite() => {
                let p = self.pending.get_or_insert((0.0,0.0,now_us)); p.0 += dx; p.1 += dy;
            }
            InputEvent::PointerDelta {..} => {}
            other => { out.extend(self.flush()); out.push(other); }
        }
        out
    }
    pub fn tick(&mut self, now_us: u64) -> Vec<InputEvent> {
        if self.pending.is_some_and(|p| now_us.saturating_sub(p.2) >= Self::WINDOW_US) { self.flush() } else { vec![] }
    }
    pub fn deadline_us(&self) -> Option<u64> { self.pending.map(|p| p.2.saturating_add(Self::WINDOW_US)) }
    pub fn clear(&mut self) { self.pending = None; }
    pub fn flush(&mut self) -> Vec<InputEvent> {
        self.pending.take().map(|(dx,dy,_)| vec![InputEvent::PointerDelta {dx,dy}]).unwrap_or_default()
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all="snake_case")]
pub enum UcPhase { Unavailable, Disabled, Armed, Inside }
#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct UcStatusJson {
    pub state: UcPhase,
    pub edge: &'static str,
    pub client_ready: bool,
    #[serde(skip_serializing_if="Option::is_none")]
    pub reason: Option<&'static str>,
    pub edge_blocked: bool,
}
pub fn edge_name(edge: UcEdge) -> &'static str {
    match edge { UcEdge::Left=>"left", UcEdge::Right=>"right", UcEdge::Top=>"top", UcEdge::Bottom=>"bottom" }
}
pub fn parse_edge(edge: &str) -> Option<UcEdge> {
    match edge { "left"=>Some(UcEdge::Left), "right"=>Some(UcEdge::Right), "top"=>Some(UcEdge::Top), "bottom"=>Some(UcEdge::Bottom), _=>None }
}
pub fn reason_name(reason: UcExitReason) -> &'static str {
    match reason {
        UcExitReason::CursorReturned=>"cursor_returned", UcExitReason::Hotkey=>"hotkey",
        UcExitReason::ModeChanged=>"mode_changed", UcExitReason::ClientNotReady=>"client_not_ready",
        UcExitReason::TapDisabled=>"tap_disabled", UcExitReason::Watchdog=>"watchdog",
        UcExitReason::SecureInput=>"secure_input", UcExitReason::MacLocked=>"mac_locked",
        UcExitReason::SessionEnded=>"session_ended",
    }
}
#[derive(Debug, Clone, PartialEq)]
pub enum UcAction { Send(Packet), SetInside(bool, UcEdge, f32), Status(UcStatusJson) }
/// The native pasteboard stays behind this port so UC policy can use a fake.
pub trait ClipboardPort {
    fn change_count(&self) -> i64;
    fn get_text(&self) -> Option<String>;
    fn set_text(&mut self, text: &str) -> bool;
}
#[derive(Debug, Clone)]
pub enum BridgeEvent {
    Moved { x:f32, y:f32, dx:f32, dy:f32, buttons_down:bool, now_ms:u64 },
    Input { event:InputEvent, now_us:u64 },
    Key { keycode:u16, down:bool, flags:u64, now_us:u64 },
    Flags { flags:u64, now_us:u64 },
    Entered { ime_hint:u8 },
    Exited(UcExitReason),
    Status { tap_ok:bool, secure_input:bool },
}

pub struct UcState {
    pub detector: EdgeDetector,
    pub phase: UcPhase,
    mode: Mode,
    tap_ok: bool,
    peer_uc: bool,
    peer_clipboard: bool,
    last_sent_change_count: Option<i64>,
    client_flags: u32,
    secure_input: bool,
    pending_enter: bool,
    pending_exit: bool,
    along: f32,
    modifiers: u8,
    seq: u32,
    deltas: DeltaCoalescer,
}
impl UcState {
    pub fn new(edge: UcEdge, displays: Vec<Rect>) -> Self {
        Self { detector:EdgeDetector::new(edge,displays), phase:UcPhase::Unavailable,
            mode:Mode::ExtendedDisplay, tap_ok:false, peer_uc:false, peer_clipboard:false, last_sent_change_count:None, client_flags:0, secure_input:false,
            pending_enter:false, pending_exit:false, along:0.5, modifiers:0, seq:0, deltas:DeltaCoalescer::default() }
    }
    pub fn negotiated(&self) -> bool { self.tap_ok && self.peer_uc }
    pub fn client_ready(&self) -> bool {
        let required = UC_STATUS_SERVICE_CONNECTED | UC_STATUS_GESTURES_OK | UC_STATUS_FGS_RUNNING;
        self.client_flags & required == required && self.client_flags & UC_STATUS_KEYGUARD_LOCKED == 0
    }
    fn can_arm(&self) -> bool { self.negotiated() && self.mode == Mode::AndroidControl && self.client_ready() && !self.secure_input }
    fn settle(&mut self) {
        if self.phase != UcPhase::Inside {
            self.phase = if !self.negotiated() {UcPhase::Unavailable} else if self.can_arm() {UcPhase::Armed} else {UcPhase::Disabled};
        }
    }
    pub fn status(&self, reason: Option<&'static str>) -> UcStatusJson {
        UcStatusJson { state:self.phase, edge:edge_name(self.detector.edge), client_ready:self.client_ready(),
            reason, edge_blocked:self.detector.free_spans().is_empty() }
    }
    fn finish(&mut self, mut actions:Vec<UcAction>, reason:Option<&'static str>) -> Vec<UcAction> {
        self.settle(); actions.push(UcAction::Status(self.status(reason))); actions
    }
    fn exit(&mut self, reason:UcExitReason, echo:bool) -> Vec<UcAction> {
        self.detector.reset(); self.deltas.clear(); self.modifiers = 0;
        if self.pending_exit || (!self.pending_enter && self.phase != UcPhase::Inside) { return vec![]; }
        self.pending_enter = false; self.pending_exit = true;
        let mut actions = vec![UcAction::SetInside(false,self.detector.edge,self.along)];
        if echo && self.peer_uc && self.phase == UcPhase::Inside {
            actions.push(UcAction::Send(Packet::Control(ControlMessage::UcExit {along:self.along,reason})));
        }
        actions
    }
    pub fn on_capabilities(&mut self, features:u32, tap_ok:bool) -> Vec<UcAction> {
        let was_negotiated = self.negotiated();
        self.peer_uc = features & FEATURE_UNIVERSAL_CONTROL != 0;
        self.peer_clipboard = self.peer_uc && features & FEATURE_CLIPBOARD_TEXT != 0;
        self.tap_ok = tap_ok;
        let mut out = if !self.negotiated() { self.exit(UcExitReason::ClientNotReady,false) } else {vec![]};
        if !was_negotiated && self.negotiated() { out.push(UcAction::Send(Packet::Control(ControlMessage::UcConfig {edge:self.detector.edge}))); }
        self.finish(out,None)
    }
    pub fn on_mode(&mut self, mode:Mode) -> Vec<UcAction> {
        self.mode = mode;
        let out = if mode != Mode::AndroidControl { self.exit(UcExitReason::ModeChanged,true) } else {vec![]};
        self.finish(out,None)
    }
    pub fn on_client_status(&mut self, flags:u32) -> Vec<UcAction> {
        if !self.peer_uc { return vec![]; }
        self.client_flags = flags;
        let out = if !self.client_ready() { self.exit(UcExitReason::ClientNotReady,true) } else {vec![]};
        self.finish(out,if self.client_ready() {None} else {Some("client_not_ready")})
    }
    pub fn on_client(&mut self, msg:ControlMessage) -> Vec<UcAction> {
        if !self.peer_uc { return vec![]; }
        match msg {
            ControlMessage::UcStatus {flags} => self.on_client_status(flags),
            ControlMessage::UcExit {along,reason} => {
                self.along = if along.is_finite() {along.clamp(0.0,1.0)} else {0.5};
                let out = self.exit(reason,false); self.finish(out,Some(reason_name(reason)))
            }
            _ => vec![],
        }
    }
    pub fn on_client_with_clipboard(&mut self, msg: ControlMessage, clipboard: &mut impl ClipboardPort) -> Vec<UcAction> {
        if let ControlMessage::Clipboard { text } = &msg {
            if self.peer_clipboard && text.len() <= 64 * 1024 && clipboard.set_text(text) {
                self.last_sent_change_count = Some(clipboard.change_count());
            }
            return vec![];
        }
        self.on_client(msg)
    }
    pub fn on_bridge_with_clipboard(&mut self, ev: BridgeEvent, clipboard: &mut impl ClipboardPort) -> Vec<UcAction> {
        let entered = matches!(ev, BridgeEvent::Entered { .. });
        let mut actions = self.on_bridge(ev);
        if entered && self.phase == UcPhase::Inside && self.peer_clipboard
            && actions.iter().any(|a| matches!(a, UcAction::Send(Packet::Control(ControlMessage::UcEnter { .. })))) {
            let change_count = clipboard.change_count();
            if self.last_sent_change_count != Some(change_count) {
                if let Some(text) = clipboard.get_text().filter(|text| text.len() <= 64 * 1024) {
                    actions.insert(0, UcAction::Send(Packet::Control(ControlMessage::Clipboard { text })));
                    self.last_sent_change_count = Some(change_count);
                }
            }
        }
        actions
    }
    pub fn on_edge(&mut self, edge:UcEdge) -> Vec<UcAction> {
        let mut out = self.exit(UcExitReason::ModeChanged,true);
        self.detector.set_edge(edge);
        if self.negotiated() { out.push(UcAction::Send(Packet::Control(ControlMessage::UcConfig {edge}))); }
        self.finish(out,None)
    }
    pub fn return_to_mac(&mut self) -> Vec<UcAction> {
        let out = self.exit(UcExitReason::Hotkey,true); self.finish(out,Some("hotkey"))
    }
    pub fn end_session(&mut self) -> Vec<UcAction> {
        let out = self.exit(UcExitReason::SessionEnded,true);
        self.phase = UcPhase::Disabled; self.mode = Mode::ExtendedDisplay;
        self.finish(out,Some("session_ended"))
    }
    fn packets(&mut self, events:Vec<InputEvent>, now_us:u64) -> Vec<UcAction> {
        events.into_iter().map(|event| { let seq=self.seq; self.seq=self.seq.wrapping_add(1);
            UcAction::Send(Packet::Input(InputPacket {seq,ts_us:now_us,event})) }).collect()
    }
    fn input(&mut self, event:InputEvent, now_us:u64) -> Vec<UcAction> {
        if self.phase != UcPhase::Inside || !self.can_arm() || self.pending_exit { return vec![]; }
        // Momentum is consumed on the Mac, never forwarded (D-17).
        if matches!(event, InputEvent::PreciseScroll {phase:ScrollPhase::Momentum,..}) {
            let events=self.deltas.flush(); return self.packets(events,now_us);
        }
        let events=self.deltas.push(event,now_us); self.packets(events,now_us)
    }
    pub fn delta_deadline_us(&self) -> Option<u64> { self.deltas.deadline_us() }
    pub fn tick(&mut self, now_us:u64) -> Vec<UcAction> {
        let events=self.deltas.tick(now_us); self.packets(events,now_us)
    }
    pub fn on_bridge(&mut self, ev:BridgeEvent) -> Vec<UcAction> {
        match ev {
            BridgeEvent::Moved {x,y,dx,dy,buttons_down,now_ms} => {
                if self.phase != UcPhase::Armed || !self.can_arm() || self.pending_enter || self.pending_exit { return vec![]; }
                if let Some(along)=self.detector.on_move(x,y,dx,dy,buttons_down,now_ms) {
                    self.along=along; self.pending_enter=true;
                    vec![UcAction::SetInside(true,self.detector.edge,along)]
                } else {vec![]}
            }
            BridgeEvent::Entered {ime_hint} => {
                if !self.pending_enter || !self.can_arm() || self.pending_exit {
                    self.pending_enter=false; self.pending_exit=true;
                    return vec![UcAction::SetInside(false,self.detector.edge,self.along)];
                }
                self.pending_enter=false; self.phase=UcPhase::Inside;
                self.finish(vec![UcAction::Send(Packet::Control(ControlMessage::UcEnter {edge:self.detector.edge,along:self.along,ime_hint}))],None)
            }
            BridgeEvent::Exited(reason) => {
                let notify = self.phase == UcPhase::Inside && !self.pending_exit && self.peer_uc;
                self.pending_exit=false; self.pending_enter=false; self.phase=UcPhase::Disabled;
                self.detector.reset(); self.deltas.clear(); self.modifiers=0;
                let out = if notify {vec![UcAction::Send(Packet::Control(ControlMessage::UcExit {along:self.along,reason}))]} else {vec![]};
                self.finish(out,Some(reason_name(reason)))
            }
            BridgeEvent::Status {tap_ok,secure_input} => {
                self.tap_ok=tap_ok; self.secure_input=secure_input;
                let reason = if !tap_ok {Some(UcExitReason::TapDisabled)} else if secure_input {Some(UcExitReason::SecureInput)} else {None};
                let out = reason.map(|r| self.exit(r,true)).unwrap_or_default();
                self.finish(out,reason.map(reason_name))
            }
            BridgeEvent::Input {event,now_us} => self.input(event,now_us),
            BridgeEvent::Key {keycode,down,flags,now_us} => {
                self.modifiers=mac_flags_to_hid(flags);
                mac_to_hid(keycode).map(|usb_hid_usage| self.input(InputEvent::Keyboard {usb_hid_usage,is_down:down,modifiers:self.modifiers},now_us)).unwrap_or_default()
            }
            BridgeEvent::Flags {flags,now_us} => {
                let modifiers=mac_flags_to_hid(flags); let changed=self.modifiers ^ modifiers; self.modifiers=modifiers;
                let mut out=vec![];
                for bit in 0..8 { if changed & (1<<bit) != 0 {
                    out.extend(self.input(InputEvent::Keyboard {usb_hid_usage:0xe0+bit,is_down:modifiers & (1<<bit)!=0,modifiers},now_us));
                }}
                out
            }
        }
    }
}

#[derive(Debug, Clone, PartialEq)]
pub enum StdinCommand { Enable(bool), Edge(UcEdge), Return }
#[derive(Deserialize)]
#[serde(tag="cmd", rename_all="snake_case", deny_unknown_fields)]
enum WireCommand { UcEnable {enabled:bool}, UcEdge {edge:String}, UcReturn {} }
pub fn parse_command(line:&str) -> Result<StdinCommand,String> {
    if line.len() > 4095 { return Err("stdin command exceeded 4 KiB".into()); }
    let command:WireCommand=serde_json::from_str(line).map_err(|e| format!("invalid stdin command: {e}"))?;
    match command {
        WireCommand::UcEnable {enabled}=>Ok(StdinCommand::Enable(enabled)),
        WireCommand::UcEdge {edge}=>parse_edge(&edge).map(StdinCommand::Edge).ok_or_else(|| "invalid UC edge".into()),
        WireCommand::UcReturn {}=>Ok(StdinCommand::Return),
    }
}

/// Latest desired settings for watch: rapid edge/enable lines cannot overwrite
/// each other. Revisions distinguish repeated commands and one-shot returns.
#[derive(Clone, Debug)]
pub struct UcCommand {
    pub enabled: Option<bool>,
    pub enable_revision: u64,
    pub edge: UcEdge,
    pub return_revision: u64,
}
impl UcCommand {
    pub fn new(edge: UcEdge) -> Self { Self {enabled:None,enable_revision:0,edge,return_revision:0} }
    pub fn apply(&mut self, command:StdinCommand) {
        match command {
            StdinCommand::Enable(enabled) => { self.enabled=Some(enabled); self.enable_revision=self.enable_revision.wrapping_add(1); }
            StdinCommand::Edge(edge) => self.edge=edge,
            StdinCommand::Return => self.return_revision=self.return_revision.wrapping_add(1),
        }
    }
}

#[derive(Serialize, Clone)]
#[serde(tag = "event", rename_all = "snake_case")]
pub enum StatusEvent {
    Starting {
        version: String,
    },
    AdbMissing,
    WaitingDevice {
        reason: String,
        devices: Vec<DeviceStatus>,
    },
    Device {
        serial: String,
        #[serde(skip_serializing_if = "Option::is_none")]
        model: Option<String>,
    },
    Listening {
        port: u16,
    },
    ClientConnected {
        peer: String,
    },
    Streaming {
        width: u32,
        height: u32,
        hz: u32,
        hidpi: bool,
        input_trusted: bool,
    },
    Mode {
        mode: Mode,
    },
    Uc {
        #[serde(flatten)]
        status: UcStatusJson,
    },
    Permissions {
        input_monitoring: bool,
        input_trusted: bool,
        screen_recording: bool,
    },
    Stats {
        fps: u64,
        encode_p95_ms: f64,
        write_p95_ms: f64,
        backlog_dropped: u64,
    },
    ClientDisconnected {
        reason: String,
    },
    Error {
        message: String,
    },
    Stopped,
}
#[derive(Serialize, Clone)]
pub struct DeviceStatus {
    pub serial: String,
    pub state: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub model: Option<String>,
}


/// One line including newline, capped at 4 KiB (MA-6).
pub fn status_line(event:&StatusEvent) -> Vec<u8> {
    let mut line=serde_json::to_vec(event).unwrap_or_else(|_| br#"{"event":"error","message":"status serialization failed"}"#.to_vec());
    if line.len()>4095 { line=br#"{"event":"error","message":"status event exceeded 4 KiB"}"#.to_vec(); }
    line.push(b'\n'); line
}

#[cfg(test)]
mod status_tests {
use super::*;
use serde_json::{json,Value};
    #[test]
    fn status_event_json_contract_and_line_cap() {
        let cases = [
            (
                StatusEvent::Starting {
                    version: "1.2.3".into(),
                },
                json!({"event":"starting","version":"1.2.3"}),
            ),
            (StatusEvent::AdbMissing, json!({"event":"adb_missing"})),
            (
                StatusEvent::WaitingDevice {
                    reason: "unauthorized".into(),
                    devices: vec![DeviceStatus {
                        serial: "s1".into(),
                        state: "unauthorized".into(),
                        model: Some("Pixel".into()),
                    }],
                },
                json!({"event":"waiting_device","reason":"unauthorized","devices":[{"serial":"s1","state":"unauthorized","model":"Pixel"}]}),
            ),
            (
                StatusEvent::Device {
                    serial: "s1".into(),
                    model: None,
                },
                json!({"event":"device","serial":"s1"}),
            ),
            (
                StatusEvent::Listening { port: 7788 },
                json!({"event":"listening","port":7788}),
            ),
            (
                StatusEvent::ClientConnected {
                    peer: "127.0.0.1:9".into(),
                },
                json!({"event":"client_connected","peer":"127.0.0.1:9"}),
            ),
            (
                StatusEvent::Streaming {
                    width: 1280,
                    height: 720,
                    hz: 60,
                    hidpi: true,
                    input_trusted: false,
                },
                json!({"event":"streaming","width":1280,"height":720,"hz":60,"hidpi":true,"input_trusted":false}),
            ),
            (
                StatusEvent::Stats {
                    fps: 60,
                    encode_p95_ms: 1.5,
                    write_p95_ms: 0.5,
                    backlog_dropped: 2,
                },
                json!({"event":"stats","fps":60,"encode_p95_ms":1.5,"write_p95_ms":0.5,"backlog_dropped":2}),
            ),
            (
                StatusEvent::ClientDisconnected {
                    reason: "peer closed\n".into(),
                },
                json!({"event":"client_disconnected","reason":"peer closed\n"}),
            ),
            (
                StatusEvent::Error {
                    message: "failed".into(),
                },
                json!({"event":"error","message":"failed"}),
            ),
            (
                StatusEvent::Uc { status: UcStatusJson {
                    state: UcPhase::Armed, edge: "right", client_ready: true,
                    reason: None, edge_blocked: false,
                } },
                json!({"event":"uc","state":"armed","edge":"right","client_ready":true,"edge_blocked":false}),
            ),
            (
                StatusEvent::Permissions { input_trusted: true, screen_recording: false, input_monitoring: true },
                json!({"event":"permissions","input_trusted":true,"screen_recording":false,"input_monitoring":true}),
            ),
            (StatusEvent::Stopped, json!({"event":"stopped"})),
        ];
        for (event, expected) in cases {
            let line = status_line(&event);
            assert!(line.len() <= 4096);
            let actual: Value = serde_json::from_slice(&line).unwrap();
            assert_eq!(actual, expected);
            assert_eq!(actual["event"], expected["event"]);
        }
    }

}

#[cfg(test)]
mod tests {
    use super::*;
    struct FakeClipboard { count: i64, text: Option<String>, writes: Vec<String> }
    impl ClipboardPort for FakeClipboard {
        fn change_count(&self) -> i64 { self.count }
        fn get_text(&self) -> Option<String> { self.text.clone() }
        fn set_text(&mut self, text: &str) -> bool { self.count += 1; self.text = Some(text.to_owned()); self.writes.push(text.to_owned()); true }
    }
    fn fake_clipboard() -> FakeClipboard { FakeClipboard { count: 1, text: Some("Tiếng Việt 😀".into()), writes: vec![] } }
    fn main_rect() -> Rect { Rect {x:0.0,y:0.0,width:1000.0,height:800.0} }
    fn detector(edge:UcEdge) -> EdgeDetector { EdgeDetector::new(edge,vec![main_rect()]) }
    fn ready() -> u32 { UC_STATUS_SERVICE_CONNECTED | UC_STATUS_GESTURES_OK | UC_STATUS_FGS_RUNNING }
    fn armed() -> UcState {
        let mut s=UcState::new(UcEdge::Right,vec![main_rect()]);
        s.on_capabilities(FEATURE_UNIVERSAL_CONTROL,true); s.on_client_status(ready()); s.on_mode(Mode::AndroidControl); s
    }
    fn push(s:&mut UcState) -> Vec<UcAction> {
        s.on_bridge(BridgeEvent::Moved {x:999.0,y:200.0,dx:24.0,dy:0.0,buttons_down:false,now_ms:1})
    }
    fn inside() -> UcState { let mut s=armed(); push(&mut s); s.on_bridge(BridgeEvent::Entered {ime_hint:2}); s }
    fn controls(actions: &[UcAction]) -> Vec<ControlMessage> {
        actions.iter().filter_map(|a| match a {UcAction::Send(Packet::Control(m))=>Some(m.clone()),_=>None}).collect()
    }
    #[test]
    fn clipboard_is_sent_on_enter_only_when_change_count_changes() {
        let mut s = armed();
        s.on_capabilities(FEATURE_UNIVERSAL_CONTROL | FEATURE_CLIPBOARD_TEXT, true);
        let mut clipboard = fake_clipboard();
        push(&mut s);
        assert_eq!(controls(&s.on_bridge_with_clipboard(BridgeEvent::Entered {ime_hint:2}, &mut clipboard)), vec![
            ControlMessage::Clipboard {text:"Tiếng Việt 😀".into()},
            ControlMessage::UcEnter {edge:UcEdge::Right,along:0.25,ime_hint:2},
        ]);
        s.on_bridge_with_clipboard(BridgeEvent::Exited(UcExitReason::CursorReturned), &mut clipboard);
        push(&mut s);
        assert_eq!(controls(&s.on_bridge_with_clipboard(BridgeEvent::Entered {ime_hint:2}, &mut clipboard)), vec![
            ControlMessage::UcEnter {edge:UcEdge::Right,along:0.25,ime_hint:2},
        ]);
        s.on_bridge_with_clipboard(BridgeEvent::Exited(UcExitReason::CursorReturned), &mut clipboard);
        clipboard.count += 1;
        push(&mut s);
        assert_eq!(controls(&s.on_bridge_with_clipboard(BridgeEvent::Entered {ime_hint:2}, &mut clipboard))[0], ControlMessage::Clipboard {text:"Tiếng Việt 😀".into()});
        s.on_bridge_with_clipboard(BridgeEvent::Exited(UcExitReason::CursorReturned), &mut clipboard);
        clipboard.count += 1;
        clipboard.text = Some("é".repeat(32_769));
        push(&mut s);
        assert_eq!(controls(&s.on_bridge_with_clipboard(BridgeEvent::Entered {ime_hint:2}, &mut clipboard)).len(), 1);
    }
    #[test]
    fn clipboard_from_client_sets_pasteboard_without_echo() {
        let mut s = armed();
        s.on_capabilities(FEATURE_UNIVERSAL_CONTROL | FEATURE_CLIPBOARD_TEXT, true);
        let mut clipboard = fake_clipboard();
        s.on_client_with_clipboard(ControlMessage::Clipboard {text:"Android 😀".into()}, &mut clipboard);
        assert_eq!(clipboard.writes, vec!["Android 😀"]);
        push(&mut s);
        assert_eq!(controls(&s.on_bridge_with_clipboard(BridgeEvent::Entered {ime_hint:2}, &mut clipboard)).len(), 1);
        s.on_client_with_clipboard(ControlMessage::Clipboard {text:"é".repeat(32_769)}, &mut clipboard);
        assert_eq!(clipboard.writes.len(), 1);
    }
    #[test]
    fn clipboard_requires_peer_bit_nine_and_universal_control() {
        let mut s = armed();
        let mut clipboard = fake_clipboard();
        push(&mut s);
        assert_eq!(controls(&s.on_bridge_with_clipboard(BridgeEvent::Entered {ime_hint:2}, &mut clipboard)).len(), 1);
        s.on_client_with_clipboard(ControlMessage::Clipboard {text:"ignored".into()}, &mut clipboard);
        assert!(clipboard.writes.is_empty());
        s.on_capabilities(FEATURE_CLIPBOARD_TEXT, true);
        s.on_client_with_clipboard(ControlMessage::Clipboard {text:"ignored".into()}, &mut clipboard);
        assert!(clipboard.writes.is_empty());
    }
    #[test]
    fn right_edge_requires_push_in_window_and_no_buttons() {
        let mut d=detector(UcEdge::Right);
        assert_eq!(d.on_move(999.0,400.0,12.0,0.0,false,0),None);
        assert_eq!(d.on_move(999.0,400.0,12.0,0.0,false,300),Some(0.5));
        assert_eq!(d.on_move(999.0,400.0,23.0,0.0,false,400),None);
        assert_eq!(d.on_move(999.0,400.0,1.0,0.0,false,701),None);
        assert_eq!(d.on_move(999.0,400.0,50.0,0.0,true,702),None);
        assert_eq!(d.on_move(999.0,400.0,1.0,0.0,false,703),None);
        assert_eq!(d.on_move(998.0,400.0,-4.0,0.0,false,704),None);
        assert_eq!(d.on_move(999.0,400.0,23.0,0.0,false,705),None);
    }
    #[test]
    fn adjacent_display_blocks_only_its_span_and_refresh_resets_push() {
        let mut d=detector(UcEdge::Right);
        let neighbor=Rect {x:1000.0,y:200.0,width:600.0,height:400.0};
        d.set_displays(vec![main_rect(),neighbor]);
        assert_eq!(d.free_spans(),vec![(0.0,0.25),(0.75,1.0)]);
        assert_eq!(d.on_move(999.0,400.0,100.0,0.0,false,0),None);
        assert_eq!(d.on_move(999.0,100.0,24.0,0.0,false,1),Some(0.125));
        d.on_move(999.0,100.0,23.0,0.0,false,2);
        d.set_displays(vec![main_rect()]);
        assert_eq!(d.on_move(999.0,100.0,1.0,0.0,false,3),None);
        d.set_displays(vec![main_rect(),Rect {x:1000.0,y:-100.0,width:600.0,height:1000.0}]);
        assert!(d.free_spans().is_empty());
    }
    #[test]
    fn top_and_bottom_need_64_points_and_along_is_correct_on_all_edges() {
        for (edge,x,y,dx,dy) in [(UcEdge::Left,0.0,200.0,-24.0,0.0), (UcEdge::Right,999.0,200.0,24.0,0.0),
            (UcEdge::Top,250.0,0.0,0.0,-64.0), (UcEdge::Bottom,250.0,799.0,0.0,64.0)] {
            let mut d=detector(edge);
            assert_eq!(d.on_move(x,y,dx,dy,false,0),Some(0.25));
        }
        let mut d=detector(UcEdge::Top);
        assert_eq!(d.on_move(500.0,0.0,0.0,-24.0,false,0),None);
        assert_eq!(d.on_move(500.0,0.0,0.0,-39.0,false,10),None);
        assert_eq!(d.on_move(500.0,0.0,0.0,-1.0,false,20),Some(0.5));
    }
    #[test]
    fn negative_display_coordinates_and_invalid_samples() {
        let mut d=EdgeDetector::new(UcEdge::Left,vec![Rect {x:-1000.0,y:-800.0,width:1000.0,height:800.0}]);
        assert_eq!(d.on_move(-1000.0,-600.0,-24.0,0.0,false,0),Some(0.25));
        assert_eq!(d.on_move(f32::NAN,-600.0,-100.0,0.0,false,1),None);
        d.set_displays(vec![]); assert!(d.free_spans().is_empty());
        assert_eq!(d.on_move(0.0,0.0,100.0,0.0,false,2),None);
    }
    #[test]
    fn not_ready_or_missing_capability_never_enters_or_sends_uc() {
        let mut s=UcState::new(UcEdge::Right,vec![main_rect()]);
        assert!(controls(&s.on_capabilities(0,true)).is_empty());
        s.on_mode(Mode::AndroidControl); s.on_client_status(ready());
        assert!(push(&mut s).is_empty());
        assert!(controls(&s.on_edge(UcEdge::Left)).is_empty());
        let mut s=armed(); s.on_client_status(ready() & !UC_STATUS_FGS_RUNNING);
        assert_eq!(s.phase,UcPhase::Disabled); assert!(push(&mut s).is_empty());
        assert!(controls(&s.on_bridge(BridgeEvent::Entered {ime_hint:0})).is_empty());
    }
    #[test]
    fn only_bridge_entered_announces_entry_and_preserves_hint() {
        let mut s=armed();
        assert_eq!(push(&mut s),vec![UcAction::SetInside(true,UcEdge::Right,0.25)]);
        assert_eq!(s.phase,UcPhase::Armed);
        assert!(push(&mut s).is_empty());
        assert_eq!(controls(&s.on_bridge(BridgeEvent::Entered {ime_hint:2})), vec![ControlMessage::UcEnter {edge:UcEdge::Right,along:0.25,ime_hint:2}]);
        assert_eq!(s.phase,UcPhase::Inside);
    }
    #[test]
    fn hotkey_exit_notifies_client_then_rearms() {
        let mut s=inside();
        assert_eq!(controls(&s.on_bridge(BridgeEvent::Exited(UcExitReason::Hotkey))),vec![ControlMessage::UcExit {along:0.25,reason:UcExitReason::Hotkey}]);
        assert_eq!(s.phase,UcPhase::Armed);
    }
    #[test]
    fn client_exit_warps_without_echo_even_after_bridge_ack() {
        let mut s=inside();
        let out=s.on_client(ControlMessage::UcExit {along:0.5,reason:UcExitReason::CursorReturned});
        assert!(out.contains(&UcAction::SetInside(false,UcEdge::Right,0.5)));
        assert!(controls(&out).is_empty());
        assert!(controls(&s.on_bridge(BridgeEvent::Exited(UcExitReason::CursorReturned))).is_empty());
        assert_eq!(s.phase,UcPhase::Armed);
    }
    #[test]
    fn mode_change_and_keyguard_exit_once_with_correct_reason() {
        let mut s=inside(); let out=s.on_mode(Mode::ExtendedDisplay);
        assert!(out.contains(&UcAction::SetInside(false,UcEdge::Right,0.25)));
        assert_eq!(controls(&out),vec![ControlMessage::UcExit {along:0.25,reason:UcExitReason::ModeChanged}]);
        assert!(controls(&s.on_bridge(BridgeEvent::Exited(UcExitReason::CursorReturned))).is_empty());
        assert_eq!(s.phase,UcPhase::Disabled);
        let mut s=inside(); let out=s.on_client_status(ready()|UC_STATUS_KEYGUARD_LOCKED);
        assert!(out.contains(&UcAction::SetInside(false,UcEdge::Right,0.25)));
        assert_eq!(controls(&out),vec![ControlMessage::UcExit {along:0.25,reason:UcExitReason::ClientNotReady}]);
    }
    #[test]
    fn cancelled_entry_and_revoked_capability_cannot_forward_stale_input() {
        let mut s=armed(); push(&mut s); s.on_mode(Mode::ExtendedDisplay);
        assert!(controls(&s.on_bridge(BridgeEvent::Entered {ime_hint:1})).is_empty());
        assert!(s.on_bridge(BridgeEvent::Input {event:InputEvent::MouseButton {button:0,is_down:true},now_us:1}).is_empty());
        s.on_bridge(BridgeEvent::Exited(UcExitReason::CursorReturned)); assert_eq!(s.phase,UcPhase::Disabled);
        let mut s=inside(); let out=s.on_capabilities(0,true);
        assert!(controls(&out).is_empty()); assert!(out.contains(&UcAction::SetInside(false,UcEdge::Right,0.25)));
        assert!(controls(&s.on_bridge(BridgeEvent::Exited(UcExitReason::Watchdog))).is_empty());
        assert_eq!(s.phase,UcPhase::Unavailable);
    }
    #[test]
    fn secure_input_tap_loss_and_session_end_restore_ownership() {
        for (tap_ok,secure_input,reason) in [(true,true,UcExitReason::SecureInput),(false,false,UcExitReason::TapDisabled)] {
            let mut s=inside(); let out=s.on_bridge(BridgeEvent::Status {tap_ok,secure_input});
            assert!(out.contains(&UcAction::SetInside(false,UcEdge::Right,0.25)));
            assert_eq!(controls(&out),vec![ControlMessage::UcExit {along:0.25,reason}]);
            s.on_bridge(BridgeEvent::Exited(reason)); assert!(push(&mut s).is_empty());
            s.on_bridge(BridgeEvent::Status {tap_ok:true,secure_input:false}); assert_eq!(s.phase,UcPhase::Armed);
        }
        let mut s=inside(); assert_eq!(controls(&s.end_session()),vec![ControlMessage::UcExit {along:0.25,reason:UcExitReason::SessionEnded}]);
    }
    #[test]
    fn coalescing_flushes_by_four_ms_and_before_button_key_and_scroll() {
        let mut c=DeltaCoalescer::default();
        let delta=InputEvent::PointerDelta {dx:1.0,dy:2.0};
        assert!(c.push(delta.clone(),100).is_empty()); assert!(c.push(delta.clone(),4000).is_empty());
        assert!(c.tick(4099).is_empty());
        assert_eq!(c.tick(4100),vec![InputEvent::PointerDelta {dx:2.0,dy:4.0}]);
        for event in [InputEvent::MouseButton {button:0,is_down:true},InputEvent::Keyboard {usb_hid_usage:4,is_down:true,modifiers:0},
            InputEvent::PreciseScroll {dx:0.0,dy:5.0,phase:ScrollPhase::Began}] {
            assert!(c.push(delta.clone(),5000).is_empty());
            assert_eq!(c.push(event.clone(),5001),vec![delta.clone(),event]);
        }
    }
    #[test]
    fn flags_distinguish_sides_and_fallback_per_modifier_family() {
        for (flag,bit) in [(0x1,0),(0x2,1),(0x20,2),(0x8,3),(0x2000,4),(0x4,5),(0x40,6),(0x10,7)] { assert_eq!(mac_flags_to_hid(flag),1<<bit); }
        assert_eq!(mac_flags_to_hid(0x207f),255);
        assert_eq!(mac_flags_to_hid((1<<18)|(1<<17)|(1<<19)|(1<<20)),15);
        assert_eq!(mac_flags_to_hid((1<<18)|0x2000|(1<<17)),0x12);
        assert_eq!(mac_flags_to_hid(1<<16),0); // Caps Lock is not a boot modifier.
    }
    #[test]
    fn inputs_keep_sequence_order_and_momentum_is_dropped() {
        let mut s=inside();
        assert!(s.on_bridge(BridgeEvent::Input {event:InputEvent::PointerDelta {dx:3.0,dy:4.0},now_us:1}).is_empty());
        let out=s.on_bridge(BridgeEvent::Key {keycode:0,down:true,flags:0x10,now_us:2});
        assert_eq!(out,vec![
            UcAction::Send(Packet::Input(InputPacket {seq:0,ts_us:2,event:InputEvent::PointerDelta {dx:3.0,dy:4.0}})),
            UcAction::Send(Packet::Input(InputPacket {seq:1,ts_us:2,event:InputEvent::Keyboard {usb_hid_usage:4,is_down:true,modifiers:128}}))]);
        assert!(s.on_bridge(BridgeEvent::Input {event:InputEvent::PreciseScroll {dx:0.0,dy:100.0,phase:ScrollPhase::Momentum},now_us:3}).is_empty());
        let out=s.on_bridge(BridgeEvent::Flags {flags:0,now_us:4});
        assert!(matches!(&out[0],UcAction::Send(Packet::Input(InputPacket {event:InputEvent::Keyboard {usb_hid_usage:0xe7,is_down:false,modifiers:0},..}))));
    }
    #[test]
    fn stdin_parser_rejects_bad_lines_and_watch_settings_keep_independent_changes() {
        assert_eq!(parse_command(r#"{"cmd":"uc_enable","enabled":true}"#),Ok(StdinCommand::Enable(true)));
        assert_eq!(parse_command(r#"{"cmd":"uc_edge","edge":"left"}"#),Ok(StdinCommand::Edge(UcEdge::Left)));
        assert_eq!(parse_command("{\"cmd\":\"uc_return\"}\r"),Ok(StdinCommand::Return));
        for bad in ["", "{}", r#"{"cmd":"uc_enable","enabled":1}"#,r#"{"cmd":"uc_edge","edge":"Left"}"#,r#"{"cmd":"uc_return","extra":1}"#,"[1]","{",r#"{"cmd":"unknown"}"#] { assert!(parse_command(bad).is_err(),"{bad}"); }
        assert!(parse_command(&" ".repeat(4096)).is_err());
        let mut settings=UcCommand::new(UcEdge::Right);
        settings.apply(StdinCommand::Enable(true)); settings.apply(StdinCommand::Edge(UcEdge::Left)); settings.apply(StdinCommand::Return);
        assert_eq!(settings.enabled,Some(true)); assert_eq!(settings.edge,UcEdge::Left); assert_eq!(settings.enable_revision,1); assert_eq!(settings.return_revision,1);
    }
    #[test]
    fn uc_status_contract_and_actual_line_cap() {
        let mut s=armed();
        let expected=serde_json::json!({"event":"uc","state":"armed","edge":"right","client_ready":true,"edge_blocked":false});
        assert_eq!(serde_json::from_slice::<serde_json::Value>(&status_line(&StatusEvent::Uc {status:s.status(None)})).unwrap(),expected);
        s.on_bridge(BridgeEvent::Status {tap_ok:true,secure_input:true});
        let json=serde_json::to_value(StatusEvent::Uc {status:s.status(Some("secure_input"))}).unwrap();
        assert_eq!(json["reason"],"secure_input"); assert_eq!(json["state"],"disabled");
        let permissions=serde_json::to_value(StatusEvent::Permissions {input_trusted:true,screen_recording:false,input_monitoring:true}).unwrap();
        assert_eq!(permissions,serde_json::json!({"event":"permissions","input_trusted":true,"screen_recording":false,"input_monitoring":true}));
        let capped=status_line(&StatusEvent::Error {message:"\n".repeat(5000)});
        assert!(capped.len()<=4096); assert_eq!(capped.iter().filter(|&&c|c==b'\n').count(),1);
        assert_eq!(serde_json::from_slice::<serde_json::Value>(&capped).unwrap()["event"],"error");
    }
}

// Native ABI record shared with WP-U4; only data, so its layout is testable here.
#[repr(C)]
#[derive(Clone, Copy, Default)]
pub struct WpUcEvent {
    pub kind: u8, pub button: u8, pub phase: u8, pub reason: u8,
    pub keycode: u16, pub down: u8, pub autorepeat: u8,
    pub flags: u64, pub x: f64, pub y: f64, pub dx: f64, pub dy: f64,
}

/// WP-U4's private native reasons differ from the wire UcExitReason tags.
pub fn bridge_exit_reason(raw: u8) -> UcExitReason {
    match raw {
        0 => UcExitReason::CursorReturned,
        1 => UcExitReason::Hotkey,
        2 => UcExitReason::Watchdog,
        3 => UcExitReason::TapDisabled,
        4 => UcExitReason::SecureInput,
        5 => UcExitReason::MacLocked,
        _ => UcExitReason::TapDisabled,
    }
}

#[cfg(test)]
mod abi_tests {
    use super::*;
    #[test]
    fn u4_record_layout_and_native_exit_reasons_are_stable() {
        assert_eq!(std::mem::size_of::<WpUcEvent>(), 48);
        assert_eq!(std::mem::align_of::<WpUcEvent>(), 8);
        assert_eq!(std::mem::offset_of!(WpUcEvent, keycode), 4);
        assert_eq!(std::mem::offset_of!(WpUcEvent, flags), 8);
        assert_eq!(std::mem::offset_of!(WpUcEvent, x), 16);
        assert_eq!(std::mem::offset_of!(WpUcEvent, dy), 40);
        for (raw, reason) in [UcExitReason::CursorReturned, UcExitReason::Hotkey,
            UcExitReason::Watchdog, UcExitReason::TapDisabled, UcExitReason::SecureInput,
            UcExitReason::MacLocked].into_iter().enumerate() {
            assert_eq!(bridge_exit_reason(raw as u8), reason);
        }
    }
}
