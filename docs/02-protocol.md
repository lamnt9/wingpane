# 02 - Đặc Tả Giao Thức Nhị Phân (wingpane-proto)

Giao thức Wingpane được thiết kế tối giản, sử dụng thư viện `postcard` (serde binary) cho tốc độ giải mã nano-giây, không cấp phát heap thừa.

---

## 1. Handshake & Control Channel

```rust
pub const PROTO_VERSION: u16 = 1;

#[derive(Serialize, Deserialize, Debug, Clone, Copy, PartialEq)]
pub enum Mode {
    ExtendedDisplay,
    GraphicsTablet,
    TrackpadKeyboard,
}

#[derive(Serialize, Deserialize, Debug)]
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
        virtual_display_spec: Option<(u32, u32, u32)>, // width, height, refresh_hz
        selected_codec: VideoCodec,
        bitrate_kbps: u32,
    },
    SetMode(Mode),
    RequestKeyframe,
    Ping(u64), // Nano-second timestamp
    Pong(u64),
    Disconnect,
}

#[derive(Serialize, Deserialize, Debug, Clone, Copy, PartialEq)]
pub enum VideoCodec {
    H264,
    Hevc,
}
```

---

## 2. Kênh Dữ Liệu Video (Video Frame Datagram)

```rust
#[derive(Serialize, Deserialize, Debug)]
pub struct VideoPacket {
    pub frame_seq: u32,
    pub pts_us: u64,
    pub is_keyframe: bool,
    pub payload: Vec<u8>, // Raw NAL units (SPS, PPS, IDR, Slice)
}
```

---

## 3. Kênh Dữ Liệu Input (InputEvent)

Tất cả tọa độ màn hình được chuẩn hóa về dải `0.0..=1.0` (tỷ lệ tuyệt đối) để độc lập với độ phân giải của Host và Client.

```rust
#[derive(Serialize, Deserialize, Debug, Clone)]
pub enum InputEvent {
    Pen {
        x: f32,          // 0.0 ..= 1.0
        y: f32,          // 0.0 ..= 1.0
        pressure: f32,   // 0.0 ..= 1.0
        tilt_x: i8,      // -90 ..= 90 độ
        tilt_y: i8,
        is_down: bool,
        is_hover: bool,
        button_mask: u8, // Nút cạnh bút S-Pen
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
        modifiers: u8, // Shift, Ctrl, Alt, Meta
    },
}

#[derive(Serialize, Deserialize, Debug, Clone, Copy)]
pub enum TouchPhase {
    Down,
    Move,
    Up,
    Cancel,
}

#[derive(Serialize, Deserialize, Debug, Clone, Copy)]
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
```
