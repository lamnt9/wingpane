//! Input event protocol types.
use serde::{Deserialize, Serialize};

/// Unified input events streamed from Android client to host.
#[derive(Serialize, Deserialize, Debug, Clone, PartialEq)]
pub enum InputEvent {
    Pen {
        x: f32,
        y: f32,
        pressure: f32,
        tilt_x: i8,
        tilt_y: i8,
        is_down: bool,
        is_hover: bool,
        button_mask: u8,
        timestamp_us: u64,
    },
    Touch {
        pointer_id: u8,
        x: f32,
        y: f32,
        phase: TouchPhase,
    },
    PointerDelta {
        dx: f32,
        dy: f32,
    },
    Scroll {
        dx: f32,
        dy: f32,
    },
    Gesture(GestureKind),
    Keyboard {
        usb_hid_usage: u16,
        is_down: bool,
        modifiers: u8,
    },
    /// Mouse button state; appended as input tag 6.
    MouseButton {
        button: u8,
        is_down: bool,
    },
    /// Committed UTF-8 text; appended as input tag 7 (maximum 256 UTF-8 bytes).
    Text(alloc::string::String),
    /// Host-side precise scroll with trackpad phase; appended as input tag 8.
    PreciseScroll { dx: f32, dy: f32, phase: ScrollPhase },
}

#[derive(Serialize, Deserialize, Debug, Clone, Copy, PartialEq, Eq)]
pub enum ScrollPhase { Discrete, Began, Changed, Ended, Momentum }

#[derive(Serialize, Deserialize, Debug, Clone, Copy, PartialEq, Eq)]
pub enum TouchPhase {
    Down,
    Move,
    Up,
    Cancel,
}

#[derive(Serialize, Deserialize, Debug, Clone, Copy, PartialEq)]
pub enum GestureKind {
    Tap,
    TwoFingerTap,
    DoubleTapDrag,
    Swipe3Up,
    Swipe3Down,
    Swipe3Left,
    Swipe3Right,
    PinchZoom(f32),
}

#[derive(Serialize, Deserialize, Debug, Clone, PartialEq)]
pub struct InputPacket {
    pub seq: u32,
    pub ts_us: u64,
    pub event: InputEvent,
}
