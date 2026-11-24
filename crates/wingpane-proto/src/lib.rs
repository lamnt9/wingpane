//! Wingpane binary communication protocol.

#![no_std]

extern crate alloc;

use alloc::string::String;
use alloc::vec::Vec;
use serde::{Deserialize, Serialize};

pub const PROTO_VERSION: u16 = 1;

/// Three operating modes of Wingpane.
#[derive(Serialize, Deserialize, Debug, Clone, Copy, PartialEq, Eq)]
pub enum Mode {
    ExtendedDisplay,
    GraphicsTablet,
    TrackpadKeyboard,
}

/// Control channel messages between Host and Client.
#[derive(Serialize, Deserialize, Debug, Clone, PartialEq)]
pub enum ControlMessage {
    Hello {
        version: u16,
        device_name: String,
        screen_size_px: (u32, u32),
        supported_codecs: Vec<VideoCodec>,
        refresh_rate_hz: u32,
    },
    Accept {
        selected_mode: Mode,
        virtual_display_spec: Option<(u32, u32, u32)>,
        selected_codec: VideoCodec,
        bitrate_kbps: u32,
    },
    SetMode(Mode),
    RequestKeyframe,
    Ping(u64),
    Pong(u64),
    Disconnect,
}

/// Supported low-latency video codecs.
#[derive(Serialize, Deserialize, Debug, Clone, Copy, PartialEq, Eq)]
pub enum VideoCodec {
    H264,
    Hevc,
}

/// Video frame packet delivered over transport.
#[derive(Serialize, Deserialize, Debug, Clone)]
pub struct VideoPacket {
    pub frame_seq: u32,
    pub pts_us: u64,
    pub is_keyframe: bool,
    pub payload: Vec<u8>,
}

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
}

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

#[cfg(test)]
mod tests {
    use super::*;
    use alloc::vec;

    #[test]
    fn test_roundtrip_control_message() {
        let msg = ControlMessage::Hello {
            version: PROTO_VERSION,
            device_name: String::from("Galaxy Tab S9"),
            screen_size_px: (2560, 1600),
            supported_codecs: vec![VideoCodec::H264, VideoCodec::Hevc],
            refresh_rate_hz: 120,
        };

        let mut buf = [0u8; 512];
        let bytes = postcard::to_slice(&msg, &mut buf).expect("serialization failed");
        let decoded: ControlMessage = postcard::from_bytes(bytes).expect("deserialization failed");

        assert_eq!(msg, decoded);
    }
}
