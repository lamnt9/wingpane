//! Host session loop and bounded, latency-oriented frame queue.
use std::{
    collections::VecDeque,
    sync::{
        atomic::{AtomicU64, Ordering},
        Arc, Mutex,
    },
};
use tokio::sync::Notify;

pub mod keymap_macos;

#[derive(Debug, Clone)]
pub struct EncodedFrame {
    pub data: Vec<u8>,
    pub keyframe: bool,
    pub pts_us: u64,
    pub encode_done_us: u64,
}

#[derive(Default)]
pub struct FrameQueue {
    frames: Mutex<VecDeque<EncodedFrame>>,
    awaiting_keyframe: Mutex<bool>,
    ready: Notify,
    dropped_frames: AtomicU64,
}
impl FrameQueue {
    pub fn push_with_overflow(&self, frame: EncodedFrame, refresh: impl FnOnce()) -> bool {
        let overflow = self.push(frame);
        if overflow {
            refresh();
        }
        overflow
    }

    pub fn push(&self, frame: EncodedFrame) -> bool {
        let mut q = self.frames.lock().expect("frame queue poisoned");
        let overflow = q.len() >= 3;
        let mut awaiting = self.awaiting_keyframe.lock().expect("frame queue poisoned");
        if overflow && !*awaiting {
            let previous = q.len();
            q.retain(|queued| queued.keyframe);
            self.dropped_frames
                .fetch_add((previous - q.len()) as u64, Ordering::Relaxed);
            *awaiting = true;
        }
        if *awaiting && !frame.keyframe {
            self.dropped_frames.fetch_add(1, Ordering::Relaxed);
            return overflow;
        }
        if frame.keyframe {
            *awaiting = false;
        }
        if q.len() >= 3 {
            q.pop_front();
            self.dropped_frames.fetch_add(1, Ordering::Relaxed);
        }
        q.push_back(frame);
        drop(q);
        drop(awaiting);
        self.ready.notify_one();
        overflow
    }
    pub async fn pop(&self) -> EncodedFrame {
        loop {
            if let Some(f) = self
                .frames
                .lock()
                .expect("frame queue poisoned")
                .pop_front()
            {
                return f;
            }
            self.ready.notified().await;
        }
    }
    pub fn len(&self) -> usize {
        self.frames.lock().expect("frame queue poisoned").len()
    }
    pub fn is_empty(&self) -> bool {
        self.frames.lock().expect("frame queue poisoned").is_empty()
    }
    pub fn dropped_frames(&self) -> u64 {
        self.dropped_frames.load(Ordering::Relaxed)
    }
}

pub fn shared_queue() -> Arc<FrameQueue> {
    Arc::new(FrameQueue::default())
}

/// Maps system gestures to the default macOS keyboard shortcuts. Modifier bits
/// follow USB HID boot-keyboard order: Ctrl=bit 0, Cmd=bit 3.
pub fn gesture_shortcut(gesture: wingpane_proto::GestureKind) -> Option<(u16, u8)> {
    use wingpane_proto::GestureKind::*;
    match gesture {
        Swipe3Up => Some((0x52, 1)),
        Swipe3Down => Some((0x51, 1)),
        Swipe3Left => Some((0x50, 1)),
        Swipe3Right => Some((0x4f, 1)),
        PinchZoom(scale) => Some((if scale >= 1.0 { 0x2e } else { 0x2d }, 8)),
        _ => None,
    }
}

/// A common control surface for native and test capture sources.
pub trait CaptureSource: Send + Sync {
    fn force_keyframe(&self);
    fn stop(&self);
}

pub trait DisplayResource: Send {
    fn destroy(&self);
}

/// Native input boundary. Tests use a fake sink so no real pointer events are posted.
pub trait InputSink: Send {
    fn pointer(&self, x: f32, y: f32, phase: wingpane_proto::TouchPhase);
    fn release_all(&self);
    fn pen(&self, _sample: PenSample) {}
    fn pen_main(&self, sample: PenSample) {
        self.pen(sample);
    }
    fn relative(&self, _dx: f32, _dy: f32) {}
    fn button(&self, _button: u8, _is_down: bool) {}
    fn scroll(&self, _dx: f32, _dy: f32) {}
    fn key(&self, _hid: u16, _down: bool, _mods: u8) {}
    fn text(&self, _text: &str) {}
    fn set_mode(&self, _mode: wingpane_proto::Mode) {}
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct PenSample {
    pub x: f32,
    pub y: f32,
    pub pressure: f32,
    pub tilt_x: i8,
    pub tilt_y: i8,
    pub is_down: bool,
    pub is_hover: bool,
    pub button_mask: u8,
}

pub struct InputRouter<'a> {
    mode: wingpane_proto::Mode,
    pressure_curve: [f32; 4],
    pen_target: wingpane_proto::PenTarget,
    pen_configured: bool,
    sink: &'a mut dyn InputSink,
}
impl<'a> InputRouter<'a> {
    pub fn new(mode: wingpane_proto::Mode, sink: &'a mut dyn InputSink) -> Self {
        Self {
            mode,
            pressure_curve: [0.25, 0.25, 0.75, 0.75],
            pen_target: wingpane_proto::PenTarget::VirtualDisplay,
            pen_configured: false,
            sink,
        }
    }
    pub fn mode(&self) -> wingpane_proto::Mode {
        self.mode
    }
    pub fn set_mode(&mut self, mode: wingpane_proto::Mode) {
        self.sink.release_all();
        self.mode = mode;
        self.sink.set_mode(mode);
    }
    /// Stores a peer supplied curve only when its x handles are in range.
    pub fn set_pen_config(&mut self, curve: [f32; 4], target: wingpane_proto::PenTarget) -> bool {
        if curve.iter().any(|value| !value.is_finite())
            || !(0.0..=1.0).contains(&curve[0])
            || !(0.0..=1.0).contains(&curve[2])
        {
            return false;
        }
        self.pressure_curve = curve;
        self.pen_target = target;
        self.pen_configured = true;
        true
    }
    pub fn handle_input(&self, packet: wingpane_proto::InputPacket) -> bool {
        use wingpane_proto::{InputEvent, Mode};
        match packet.event {
            InputEvent::Touch { x, y, phase, .. } if self.mode == Mode::ExtendedDisplay => {
                self.sink.pointer(x, y, phase)
            }
            InputEvent::Pen {
                x,
                y,
                pressure,
                tilt_x,
                tilt_y,
                is_down,
                is_hover,
                button_mask,
                ..
            } if self.mode == Mode::ExtendedDisplay || self.mode == Mode::GraphicsTablet => {
                let sample = PenSample {
                    x,
                    y,
                    pressure: wingpane_core::pen::apply_pressure_curve(
                        pressure,
                        &self.pressure_curve,
                    ),
                    tilt_x,
                    tilt_y,
                    is_down,
                    is_hover,
                    button_mask,
                };
                if (self.pen_configured
                    && self.pen_target == wingpane_proto::PenTarget::MainDisplay)
                    || (!self.pen_configured && self.mode == Mode::GraphicsTablet)
                {
                    self.sink.pen_main(sample)
                } else {
                    self.sink.pen(sample)
                }
            }
            InputEvent::PointerDelta { dx, dy } if self.mode == Mode::TrackpadKeyboard => {
                self.sink.relative(dx, dy)
            }
            InputEvent::Scroll { dx, dy } if self.mode == Mode::TrackpadKeyboard => {
                self.sink.scroll(dx, dy)
            }
            InputEvent::Keyboard {
                usb_hid_usage,
                is_down,
                modifiers,
            } if self.mode == Mode::TrackpadKeyboard => {
                self.sink.key(usb_hid_usage, is_down, modifiers)
            }
            InputEvent::Gesture(gesture) if self.mode == Mode::TrackpadKeyboard => {
                if let Some((usage, modifiers)) = gesture_shortcut(gesture) {
                    key_tap(self.sink, usage, modifiers)
                } else if gesture == wingpane_proto::GestureKind::Tap {
                    self.sink.button(0, true);
                    self.sink.button(0, false)
                } else if gesture == wingpane_proto::GestureKind::TwoFingerTap {
                    self.sink.button(1, true);
                    self.sink.button(1, false)
                } else {
                    return false;
                }
            }
            InputEvent::MouseButton { button, is_down } if self.mode == Mode::TrackpadKeyboard => {
                self.sink.button(button, is_down)
            }
            _ => return false,
        }
        true
    }
}

fn key_tap(sink: &dyn InputSink, hid: u16, modifiers: u8) {
    sink.key(hid, true, modifiers);
    sink.key(hid, false, 0);
}

pub struct SessionResources {
    pub capture: Box<dyn CaptureSource>,
    pub input: Box<dyn InputSink>,
    pub display: Box<dyn DisplayResource>,
}
impl Drop for SessionResources {
    fn drop(&mut self) {
        self.input.release_all();
        self.capture.stop();
        self.display.destroy();
    }
}

pub fn handle_input(sink: &dyn InputSink, packet: wingpane_proto::InputPacket) -> bool {
    use wingpane_proto::InputEvent;
    match packet.event {
        InputEvent::Touch { x, y, phase, .. } => sink.pointer(x, y, phase),
        InputEvent::Pen {
            x,
            y,
            pressure,
            tilt_x,
            tilt_y,
            is_down,
            is_hover,
            button_mask,
            ..
        } => sink.pen(PenSample {
            x,
            y,
            pressure: wingpane_core::pen::apply_pressure_curve(pressure, &[0.25, 0.25, 0.75, 0.75]),
            tilt_x,
            tilt_y,
            is_down,
            is_hover,
            button_mask,
        }),
        InputEvent::PointerDelta { dx, dy } => sink.relative(dx, dy),
        InputEvent::Scroll { dx, dy } => sink.scroll(dx, dy),
        InputEvent::Gesture(_) => return false,
        InputEvent::MouseButton { .. } => return false,
        InputEvent::Keyboard {
            usb_hid_usage,
            is_down,
            modifiers,
        } => sink.key(usb_hid_usage, is_down, modifiers),
    }
    true
}

pub fn handle_control(
    source: &dyn CaptureSource,
    message: wingpane_proto::ControlMessage,
) -> Option<wingpane_proto::ControlMessage> {
    use wingpane_proto::ControlMessage;
    match message {
        ControlMessage::RequestKeyframe => {
            source.force_keyframe();
            None
        }
        ControlMessage::Ping(ts) => Some(ControlMessage::Pong(ts)),
        ControlMessage::Disconnect => None,
        _ => None,
    }
}

#[cfg(test)]
mod gesture_shortcut_tests {
    use super::gesture_shortcut;
    use wingpane_proto::GestureKind::*;
    #[test]
    fn default_macos_gesture_shortcuts_match_plan() {
        assert_eq!(gesture_shortcut(Swipe3Up), Some((0x52, 1)));
        assert_eq!(gesture_shortcut(Swipe3Down), Some((0x51, 1)));
        assert_eq!(gesture_shortcut(Swipe3Left), Some((0x50, 1)));
        assert_eq!(gesture_shortcut(Swipe3Right), Some((0x4f, 1)));
        assert_eq!(gesture_shortcut(PinchZoom(1.15)), Some((0x2e, 8)));
        assert_eq!(gesture_shortcut(PinchZoom(0.85)), Some((0x2d, 8)));
        assert_eq!(gesture_shortcut(Tap), None);
    }
}

pub fn handle_control_messages(
    source: &dyn CaptureSource,
    message: wingpane_proto::ControlMessage,
    host_us: u64,
    host_features: u32,
) -> Vec<wingpane_proto::ControlMessage> {
    use wingpane_proto::ControlMessage;
    match message {
        ControlMessage::Ping(echo_ns) => vec![
            ControlMessage::Pong(echo_ns),
            ControlMessage::HostTime { echo_ns, host_us },
        ],
        ControlMessage::Capabilities { .. } => {
            vec![ControlMessage::Capabilities {
                features: host_features,
            }]
        }
        other => handle_control(source, other).into_iter().collect(),
    }
}

pub fn busy_rejection() -> wingpane_proto::ControlMessage {
    wingpane_proto::ControlMessage::Reject(wingpane_proto::RejectReason::Busy)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicUsize, Ordering};
    struct NoopInput;
    impl InputSink for NoopInput {
        fn pointer(&self, _: f32, _: f32, _: wingpane_proto::TouchPhase) {}
        fn release_all(&self) {}
    }
    fn f(n: u8, keyframe: bool) -> EncodedFrame {
        EncodedFrame {
            data: vec![n],
            keyframe,
            pts_us: n.into(),
            encode_done_us: n.into(),
        }
    }
    #[test]
    fn overflow_discards_predictive_frames_until_a_keyframe() {
        let q = FrameQueue::default();
        q.push(f(1, true));
        q.push(f(2, false));
        q.push(f(3, false));
        assert!(q.push(f(4, false)));
        assert!(!q.push(f(5, false)));
        q.push(f(6, true));
        let g = q.frames.lock().unwrap();
        let emitted: Vec<_> = g.iter().map(|x| (x.data[0], x.keyframe)).collect();
        assert_eq!(emitted, [(1, true), (6, true)]);
        assert!(!emitted.iter().any(|(id, key)| *id > 1 && !key));
    }
    #[test]
    fn overflow_requests_refresh_once_and_keeps_queue_bounded() {
        let q = FrameQueue::default();
        use std::sync::atomic::{AtomicUsize, Ordering};
        let requests = AtomicUsize::new(0);
        let refresh = || {
            requests.fetch_add(1, Ordering::SeqCst);
        };
        q.push(f(1, true));
        q.push(f(2, true));
        q.push(f(3, true));
        assert!(q.push_with_overflow(f(4, true), refresh));
        assert_eq!(q.len(), 3);
        assert_eq!(requests.load(Ordering::SeqCst), 1);
    }

    #[test]
    fn request_keyframe_calls_fake_source_and_ping_gets_pong() {
        use std::sync::atomic::{AtomicUsize, Ordering};
        struct Fake(AtomicUsize);
        impl CaptureSource for Fake {
            fn force_keyframe(&self) {
                self.0.fetch_add(1, Ordering::SeqCst);
            }
            fn stop(&self) {}
        }
        let fake = Fake(AtomicUsize::new(0));
        assert_eq!(
            handle_control(&fake, wingpane_proto::ControlMessage::RequestKeyframe),
            None
        );
        assert_eq!(
            handle_control(&fake, wingpane_proto::ControlMessage::Ping(9)),
            Some(wingpane_proto::ControlMessage::Pong(9))
        );
        assert_eq!(fake.0.load(Ordering::SeqCst), 1);
    }

    #[test]
    fn capabilities_reply_advertises_accessibility_bit_only() {
        struct Fake;
        impl CaptureSource for Fake {
            fn force_keyframe(&self) {}
            fn stop(&self) {}
        }
        assert_eq!(
            handle_control_messages(
                &Fake,
                wingpane_proto::ControlMessage::Capabilities { features: 0 },
                0,
                1 << 6
            ),
            vec![wingpane_proto::ControlMessage::Capabilities { features: 1 << 6 }]
        );
    }

    #[test]
    fn busy_client_receives_busy_rejection() {
        assert_eq!(
            busy_rejection(),
            wingpane_proto::ControlMessage::Reject(wingpane_proto::RejectReason::Busy)
        );
    }

    #[test]
    fn session_resource_drop_releases_input_before_destroying_display() {
        struct OrderedInput(Arc<Mutex<Vec<&'static str>>>);
        impl InputSink for OrderedInput {
            fn pointer(&self, _: f32, _: f32, _: wingpane_proto::TouchPhase) {
                self.0.lock().unwrap().push("pointer");
            }
            fn release_all(&self) {
                self.0.lock().unwrap().push("release");
            }
        }
        struct OrderedCapture(Arc<Mutex<Vec<&'static str>>>);
        impl CaptureSource for OrderedCapture {
            fn force_keyframe(&self) {}
            fn stop(&self) {
                self.0.lock().unwrap().push("stop");
            }
        }
        struct OrderedDisplay(Arc<Mutex<Vec<&'static str>>>);
        impl DisplayResource for OrderedDisplay {
            fn destroy(&self) {
                self.0.lock().unwrap().push("destroy");
            }
        }
        let calls = Arc::new(Mutex::new(Vec::new()));
        let resources = SessionResources {
            capture: Box::new(OrderedCapture(calls.clone())),
            input: Box::new(OrderedInput(calls.clone())),
            display: Box::new(OrderedDisplay(calls.clone())),
        };
        assert!(handle_input(
            resources.input.as_ref(),
            wingpane_proto::InputPacket {
                seq: 1,
                ts_us: 1,
                event: wingpane_proto::InputEvent::Touch {
                    pointer_id: 0,
                    x: 0.5,
                    y: 0.5,
                    phase: wingpane_proto::TouchPhase::Down,
                },
            },
        ));
        drop(resources); // EOF/error/task cancellation all release before display destruction.
        assert_eq!(
            *calls.lock().unwrap(),
            ["pointer", "release", "stop", "destroy"]
        );
    }

    #[test]
    fn touch_input_packets_reach_fake_sink_in_order() {
        struct FakeInput(Mutex<Vec<(f32, f32, wingpane_proto::TouchPhase)>>);
        impl InputSink for FakeInput {
            fn pointer(&self, x: f32, y: f32, phase: wingpane_proto::TouchPhase) {
                self.0.lock().unwrap().push((x, y, phase));
            }
            fn release_all(&self) {}
        }
        let sink = FakeInput(Mutex::new(Vec::new()));
        for (seq, phase) in [
            (1, wingpane_proto::TouchPhase::Down),
            (2, wingpane_proto::TouchPhase::Move),
            (3, wingpane_proto::TouchPhase::Up),
        ] {
            assert!(handle_input(
                &sink,
                wingpane_proto::InputPacket {
                    seq,
                    ts_us: seq.into(),
                    event: wingpane_proto::InputEvent::Touch {
                        pointer_id: 0,
                        x: seq as f32 / 10.0,
                        y: 0.5,
                        phase,
                    },
                }
            ));
        }
        let got = sink.0.lock().unwrap();
        assert_eq!(
            got.iter().map(|(_, _, p)| *p).collect::<Vec<_>>(),
            [
                wingpane_proto::TouchPhase::Down,
                wingpane_proto::TouchPhase::Move,
                wingpane_proto::TouchPhase::Up
            ]
        );
        assert_eq!(
            got.iter().map(|(x, _, _)| *x).collect::<Vec<_>>(),
            [0.1, 0.2, 0.3]
        );
    }

    #[test]
    fn router_keeps_touch_and_releases_before_mode_change() {
        struct Recording(Mutex<Vec<&'static str>>);
        impl InputSink for Recording {
            fn pointer(&self, _: f32, _: f32, _: wingpane_proto::TouchPhase) {
                self.0.lock().unwrap().push("pointer");
            }
            fn release_all(&self) {
                self.0.lock().unwrap().push("release");
            }
            fn set_mode(&self, _: wingpane_proto::Mode) {
                self.0.lock().unwrap().push("mode");
            }
            fn relative(&self, _: f32, _: f32) {
                self.0.lock().unwrap().push("relative");
            }
        }
        let mut sink = Recording(Mutex::new(Vec::new()));
        let mut router = InputRouter::new(wingpane_proto::Mode::ExtendedDisplay, &mut sink);
        assert!(router.handle_input(wingpane_proto::InputPacket {
            seq: 1,
            ts_us: 1,
            event: wingpane_proto::InputEvent::Touch {
                pointer_id: 0,
                x: 0.1,
                y: 0.2,
                phase: wingpane_proto::TouchPhase::Down
            }
        }));
        router.set_mode(wingpane_proto::Mode::TrackpadKeyboard);
        assert!(router.handle_input(wingpane_proto::InputPacket {
            seq: 2,
            ts_us: 2,
            event: wingpane_proto::InputEvent::PointerDelta { dx: 1.0, dy: 2.0 }
        }));
        assert_eq!(
            *sink.0.lock().unwrap(),
            ["pointer", "release", "mode", "relative"]
        );
    }

    #[test]
    fn graphics_tablet_routes_pen_to_main_target_and_trackpad_routes_mouse_inputs() {
        struct Recording(Mutex<Vec<&'static str>>);
        impl InputSink for Recording {
            fn pointer(&self, _: f32, _: f32, _: wingpane_proto::TouchPhase) {}
            fn release_all(&self) {}
            fn pen(&self, _: PenSample) {
                self.0.lock().unwrap().push("virtual_pen");
            }
            fn pen_main(&self, _: PenSample) {
                self.0.lock().unwrap().push("main_pen");
            }
            fn relative(&self, _: f32, _: f32) {
                self.0.lock().unwrap().push("move");
            }
            fn scroll(&self, _: f32, _: f32) {
                self.0.lock().unwrap().push("scroll");
            }
            fn button(&self, _: u8, _: bool) {
                self.0.lock().unwrap().push("button");
            }
        }
        let mut sink = Recording(Mutex::new(Vec::new()));
        let mut router = InputRouter::new(wingpane_proto::Mode::GraphicsTablet, &mut sink);
        let pen = wingpane_proto::InputPacket {
            seq: 1,
            ts_us: 1,
            event: wingpane_proto::InputEvent::Pen {
                x: 0.4,
                y: 0.5,
                pressure: 0.3,
                tilt_x: 0,
                tilt_y: 0,
                is_down: true,
                is_hover: false,
                button_mask: 0,
                timestamp_us: 1,
            },
        };
        assert!(router.handle_input(pen));
        router.set_mode(wingpane_proto::Mode::TrackpadKeyboard);
        for event in [
            wingpane_proto::InputEvent::PointerDelta { dx: 1.0, dy: 2.0 },
            wingpane_proto::InputEvent::Scroll { dx: 3.0, dy: 4.0 },
            wingpane_proto::InputEvent::MouseButton {
                button: 0,
                is_down: true,
            },
        ] {
            assert!(router.handle_input(wingpane_proto::InputPacket {
                seq: 2,
                ts_us: 2,
                event
            }));
        }
        assert_eq!(
            *sink.0.lock().unwrap(),
            ["main_pen", "move", "scroll", "button"]
        );
    }

    #[test]
    fn pen_config_validates_handles_and_applies_curve_and_target() {
        struct Recording(Mutex<Vec<(&'static str, PenSample)>>);
        impl InputSink for Recording {
            fn pointer(&self, _: f32, _: f32, _: wingpane_proto::TouchPhase) {}
            fn release_all(&self) {}
            fn pen(&self, sample: PenSample) {
                self.0.lock().unwrap().push(("virtual", sample));
            }
            fn pen_main(&self, sample: PenSample) {
                self.0.lock().unwrap().push(("main", sample));
            }
        }
        let mut sink = Recording(Mutex::new(Vec::new()));
        let mut router = InputRouter::new(wingpane_proto::Mode::ExtendedDisplay, &mut sink);
        assert!(!router.set_pen_config(
            [f32::NAN, 0.2, 0.5, 0.8],
            wingpane_proto::PenTarget::MainDisplay
        ));
        assert!(!router.set_pen_config(
            [0.2, f32::NAN, 0.5, 0.8],
            wingpane_proto::PenTarget::MainDisplay
        ));
        assert!(
            !router.set_pen_config([1.1, 0.2, 0.5, 0.8], wingpane_proto::PenTarget::MainDisplay)
        );
        assert!(router.set_pen_config(
            [0.45, 0.20, 0.88, 0.60],
            wingpane_proto::PenTarget::MainDisplay
        ));
        assert!(router.handle_input(wingpane_proto::InputPacket {
            seq: 1,
            ts_us: 1,
            event: wingpane_proto::InputEvent::Pen {
                x: 0.4,
                y: 0.5,
                pressure: 0.5,
                tilt_x: 0,
                tilt_y: 0,
                is_down: true,
                is_hover: false,
                button_mask: 0,
                timestamp_us: 1
            },
        }));
        let got = sink.0.lock().unwrap();
        assert_eq!(got[0].0, "main");
        assert_eq!(
            got[0].1.pressure,
            wingpane_core::pen::apply_pressure_curve(0.5, &[0.45, 0.20, 0.88, 0.60])
        );
    }

    #[test]
    fn display_router_curves_pen_pressure_and_preserves_sample_fields() {
        struct Recording(Mutex<Vec<PenSample>>);
        impl InputSink for Recording {
            fn pointer(&self, _: f32, _: f32, _: wingpane_proto::TouchPhase) {}
            fn release_all(&self) {}
            fn pen(&self, sample: PenSample) {
                self.0.lock().unwrap().push(sample);
            }
        }
        let mut sink = Recording(Mutex::new(Vec::new()));
        let router = InputRouter::new(wingpane_proto::Mode::ExtendedDisplay, &mut sink);
        assert!(router.handle_input(wingpane_proto::InputPacket {
            seq: 1,
            ts_us: 1,
            event: wingpane_proto::InputEvent::Pen {
                x: 0.2,
                y: 0.8,
                pressure: 0.5,
                tilt_x: -30,
                tilt_y: 40,
                is_down: true,
                is_hover: false,
                button_mask: 3,
                timestamp_us: 1,
            }
        }));
        drop(router);
        let got = sink.0.lock().unwrap()[0];
        assert_eq!(
            (
                got.x,
                got.y,
                got.tilt_x,
                got.tilt_y,
                got.is_down,
                got.is_hover,
                got.button_mask
            ),
            (0.2, 0.8, -30, 40, true, false, 3)
        );
        assert!((got.pressure - 0.5).abs() < 0.001);
    }

    #[test]
    fn display_router_sanitizes_nan_pen_pressure_and_ignores_pen_in_other_modes() {
        struct Recording(Mutex<Vec<PenSample>>);
        impl InputSink for Recording {
            fn pointer(&self, _: f32, _: f32, _: wingpane_proto::TouchPhase) {}
            fn release_all(&self) {}
            fn pen(&self, sample: PenSample) {
                self.0.lock().unwrap().push(sample);
            }
        }
        let mut sink = Recording(Mutex::new(Vec::new()));
        let packet = wingpane_proto::InputPacket {
            seq: 1,
            ts_us: 1,
            event: wingpane_proto::InputEvent::Pen {
                x: 0.5,
                y: 0.5,
                pressure: f32::NAN,
                tilt_x: 0,
                tilt_y: 0,
                is_down: false,
                is_hover: true,
                button_mask: 0,
                timestamp_us: 1,
            },
        };
        let router = InputRouter::new(wingpane_proto::Mode::ExtendedDisplay, &mut sink);
        assert!(router.handle_input(packet.clone()));
        drop(router);
        assert_eq!(sink.0.lock().unwrap()[0].pressure, 0.0);
        let mut sink2 = Recording(Mutex::new(Vec::new()));
        let router2 = InputRouter::new(wingpane_proto::Mode::TrackpadKeyboard, &mut sink2);
        assert!(!router2.handle_input(packet));
        drop(router2);
        assert!(sink2.0.lock().unwrap().is_empty());
    }

    #[tokio::test]
    async fn eof_from_framed_peer_drops_fake_session_resources() {
        use tokio::net::{TcpListener, TcpStream};
        use wingpane_core::transport::tcp::FramedTcp;
        struct Fake(Arc<AtomicUsize>);
        impl CaptureSource for Fake {
            fn force_keyframe(&self) {}
            fn stop(&self) {
                self.0.fetch_add(1, Ordering::SeqCst);
            }
        }
        impl DisplayResource for Fake {
            fn destroy(&self) {
                self.0.fetch_add(1, Ordering::SeqCst);
            }
        }
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let client = TcpStream::connect(listener.local_addr().unwrap())
            .await
            .unwrap();
        let (server, _) = listener.accept().await.unwrap();
        let mut framed = FramedTcp::new(server).unwrap();
        drop(client);
        assert!(framed.recv().await.unwrap().is_none());
        let calls = Arc::new(AtomicUsize::new(0));
        drop(SessionResources {
            capture: Box::new(Fake(calls.clone())),
            input: Box::new(NoopInput),
            display: Box::new(Fake(calls.clone())),
        });
        assert_eq!(calls.load(Ordering::SeqCst), 2);
    }
}
