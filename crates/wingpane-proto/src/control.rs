//! Control channel protocol types.
use alloc::{string::String, vec::Vec};
use serde::{Deserialize, Serialize};

pub const PROTO_VERSION: u16 = 1;

/// Operating modes of Wingpane.
#[derive(Serialize, Deserialize, Debug, Clone, Copy, PartialEq, Eq)]
pub enum Mode {
    ExtendedDisplay,
    GraphicsTablet,
    TrackpadKeyboard,
    AndroidControl,
}

#[derive(Serialize, Deserialize, Debug, Clone, Copy, PartialEq, Eq)]
pub enum UcEdge { Left, Right, Top, Bottom }

#[derive(Serialize, Deserialize, Debug, Clone, Copy, PartialEq, Eq)]
pub enum UcExitReason { CursorReturned, Hotkey, ModeChanged, ClientNotReady, TapDisabled, Watchdog, SecureInput, MacLocked, SessionEnded }

/// Destination used by the host when routing pen samples.
#[derive(Serialize, Deserialize, Debug, Clone, Copy, PartialEq, Eq)]
pub enum PenTarget {
    MainDisplay,
    VirtualDisplay,
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
    HostTime {
        echo_ns: u64,
        host_us: u64,
    },
    /// Feature bits supported by this peer. Appended to preserve existing wire tags.
    Capabilities {
        features: u32,
    },
    /// Stylus response curve and destination. Appended to preserve existing tags.
    PenConfig {
        pressure_curve: [f32; 4],
        target: PenTarget,
    },
    UcConfig { edge: UcEdge },
    UcEnter { edge: UcEdge, along: f32, ime_hint: u8 },
    UcExit { along: f32, reason: UcExitReason },
    UcStatus { flags: u32 },
    Clipboard { text: String },
}

/// Why the host declined a connection. Variant order is part of the wire format.
#[derive(Serialize, Deserialize, Debug, Clone, Copy, PartialEq, Eq)]
pub enum RejectReason {
    VersionMismatch {
        host_version: u16,
    },
    UnsupportedCodec,
    InvalidHello,
    Busy,
    /// Host lacks macOS Screen Recording permission. Appended last to keep the wire format stable.
    ScreenRecordingDenied,
}

/// Supported low-latency video codecs.
#[derive(Serialize, Deserialize, Debug, Clone, Copy, PartialEq, Eq)]
pub enum VideoCodec {
    H264,
    Hevc,
}
