//! Control channel protocol types.
use alloc::{string::String, vec::Vec};
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
    Reject(RejectReason),
}

/// Why the host declined a connection. Variant order is part of the wire format.
#[derive(Serialize, Deserialize, Debug, Clone, Copy, PartialEq, Eq)]
pub enum RejectReason {
    VersionMismatch { host_version: u16 },
    UnsupportedCodec,
    InvalidHello,
    Busy,
}

/// Supported low-latency video codecs.
#[derive(Serialize, Deserialize, Debug, Clone, Copy, PartialEq, Eq)]
pub enum VideoCodec {
    H264,
    Hevc,
}
