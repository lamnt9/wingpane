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

---

## 4. Đóng Gói Gói Tin Trên Luồng Byte (Stream Framing) — v1

Áp dụng cho USB (`adb reverse`, TCP). MVP dùng **một kết nối TCP duy nhất** cho cả control, video, input. QUIC (Phase 4) sẽ tách kênh.

* **FR-1 — Khung:** `[len: u32 little-endian][body: len byte]`. `body` = `postcard` của `Packet`.
* **FR-2 — Giới hạn:** `MAX_FRAME_LEN = 8 MiB`. `len == 0` hoặc `len > MAX_FRAME_LEN` → lỗi `FrameTooLarge`/`EmptyFrame`, bên nhận đóng kết nối.
* **FR-3 — Phong bì:**
  ```rust
  pub enum Packet {
      Control(ControlMessage),   // tag 0
      Video(VideoPacket),        // tag 1
      Input(InputPacket),        // tag 2
  }
  pub struct InputPacket { pub seq: u32, pub ts_us: u64, pub event: InputEvent }
  ```
* **FR-4 — Hợp đồng wire:** Thứ tự biến thể enum/field là hợp đồng. Chỉ thêm biến thể ở cuối; đổi/xoá/sắp lại → tăng `PROTO_VERSION`.
* **FR-5 — Đồng hồ:** `VideoPacket.pts_us`, `InputPacket.ts_us` = micro-giây đồng hồ monotonic của bên gửi. `Ping/Pong` = nano-giây, bên gửi Ping tự tính RTT.
* **FR-6 — Bộ giải mã tăng dần:** `FrameDecoder` nhận byte từng mảnh bất kỳ (`push(&[u8])`), trả lần lượt `Packet` hoàn chỉnh; byte rác/thiếu không bao giờ panic, chỉ trả lỗi.
* **FR-7 — Thứ tự:** Bên gửi giữ thứ tự frame. Video chậm không được chặn Input: host ưu tiên ghi Input/Control trước Video khi hàng đợi gửi có cả hai.
