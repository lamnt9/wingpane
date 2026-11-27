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
    AndroidControl, // tag 3; Universal Control (requires capability bit 8)
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
    // Appended tag 9: feature bitset; unknown bits are ignored.
    Capabilities { features: u32 },
    // Appended tag 10: stylus response curve and output display.
    PenConfig { pressure_curve: [f32; 4], target: PenTarget },
    // Tags 11..14: Universal Control. Sent only after peer advertises bit 8.
    UcConfig { edge: UcEdge },
    UcEnter { edge: UcEdge, along: f32, ime_hint: u8 },
    UcExit { along: f32, reason: UcExitReason },
    UcStatus { flags: u32 },
}

#[derive(Serialize, Deserialize, Debug, Clone, Copy, PartialEq, Eq)]
pub enum UcEdge { Left, Right, Top, Bottom }
#[derive(Serialize, Deserialize, Debug, Clone, Copy, PartialEq, Eq)]
pub enum UcExitReason { CursorReturned, Hotkey, ModeChanged, ClientNotReady, TapDisabled, Watchdog, SecureInput, MacLocked, SessionEnded }

#[derive(Serialize, Deserialize, Debug, Clone, Copy, PartialEq, Eq)]
pub enum PenTarget { MainDisplay, VirtualDisplay }

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
    MouseButton { button: u8, is_down: bool }, // tag 6; 0=left, 1=right, 2=middle
    Text(String), // tag 7; committed UTF-8 text, at most 256 bytes
    PreciseScroll { dx: f32, dy: f32, phase: ScrollPhase }, // tag 8; host → client UC scroll
}

#[derive(Serialize, Deserialize, Debug, Clone, Copy, PartialEq, Eq)]
pub enum ScrollPhase { Discrete, Began, Changed, Ended, Momentum }

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

---

## 5. Từ Chối Kết Nối (Reject) — v1, bổ sung 2026-11-24

* **RJ-1:** Thêm biến thể cuối `ControlMessage::Reject(RejectReason)` (đúng FR-4: chỉ thêm ở cuối, không đổi `PROTO_VERSION`).
  ```rust
  pub enum RejectReason {
      VersionMismatch { host_version: u16 }, // tag 0
      UnsupportedCodec,                       // tag 1 — client không có H.264
      InvalidHello,                           // tag 2 — kích thước/tần số = 0
      Busy,                                   // tag 3 — host đang có session khác
  }
  ```
* **RJ-2:** Host nhận `Hello` không hợp lệ → gửi `Reject(..)` rồi đóng kết nối, không im lặng bỏ qua. Client hiện lỗi theo lý do (UI S5).
* **RJ-3:** `Hello` khi session đang `Active` → `Reject(Busy)`.

---

## 6. Đồng Bộ Đồng Hồ (HostTime) — v1, bổ sung 2026-11-24

* **TS-1:** Thêm biến thể cuối `ControlMessage::HostTime { echo_ns: u64, host_us: u64 }` (sau `Reject`, đúng FR-4, không đổi `PROTO_VERSION`).
* **TS-2:** Host trả lời mỗi `Ping(t)` bằng `Pong(t)` **rồi ngay sau đó** `HostTime { echo_ns: t, host_us: <đồng hồ monotonic host, cùng gốc với VideoPacket.pts_us> }`.
* **TS-3:** Client ước lượng `offset_us = host_us − (t_send_us + t_recv_us)/2`, chỉ nhận mẫu có RTT nhỏ nhất trong cửa sổ 10 s gần nhất. Độ trễ một frame = `client_now_us_at_render + offset_us − pts_us`.
* **TS-4:** Client hiện tại nhận `HostTime`; enum tag chưa biết được bỏ qua theo cơ chế tương thích WP-0.

## 8. Universal Control — v1, bổ sung 2026-11-25 (WP-U0)

Protocol vẫn giữ `PROTO_VERSION = 1`; mọi biến thể mới chỉ nối cuối enum. `Mode::AndroidControl` là tag 3. Các control tag 11–14 lần lượt là `UcConfig`, `UcEnter`, `UcExit`, `UcStatus`; input tag 8 là `PreciseScroll`. `UcEdge` tags 0..3 = Left/Right/Top/Bottom; `UcExitReason` tags 0..8 = CursorReturned, Hotkey, ModeChanged, ClientNotReady, TapDisabled, Watchdog, SecureInput, MacLocked, SessionEnded. `ScrollPhase` tags 0..4 = Discrete, Began, Changed, Ended, Momentum.

| Control tag | Message | Direction | Limit |
|---|---|---|---|
| 15 | `Clipboard { text: String }` | host ↔ client | UTF-8 ≤ 64 KiB; larger messages are dropped without truncation |

Capabilities bit 9 is `CLIPBOARD_TEXT` and is advertised only with bit 8 `UNIVERSAL_CONTROL`. Mac sends clipboard text on UC entry when pasteboard `changeCount` differs from its last send. Android sends clipboard text through the notification action after its Activity gains focus.

`along` nằm trong 0..1 dọc cạnh; `ime_hint`: 0 unknown, 1 latin, 2 vietnamese. `UcStatus.flags` bit 0..5 lần lượt là SERVICE_CONNECTED, GESTURES_OK, IME_CONNECTION, KEYGUARD_LOCKED, FGS_RUNNING, OVERLAY_SHOWN. UC-ready yêu cầu bit 0, 1, 4 và không có bit 3.

Capabilities bit 8 là `UNIVERSAL_CONTROL`; không gửi AndroidControl, Uc*, PreciseScroll hay Input host→client đến peer chưa công bố bit này. `Accept.selected_mode` không dùng AndroidControl. Bit 8 được định nghĩa trong schema nhưng chưa được daemon quảng bá ở U0.

`PointerDelta` host→client đo point macOS sau gia tốc; `Keyboard.modifiers` là HID boot modifiers (Cmd ở bit 3/7). PreciseScroll mang delta pixel cùng phase trackpad; scroll Momentum từ macOS được nhận dạng để client có thể bỏ qua.

## 7. Capabilities — v1, Phase 2 WP-0

* `MouseButton { button: u8, is_down: bool }` là `InputEvent` tag 6 (0 trái, 1 phải, 2 giữa); Rust/Kotlin golden: `input_mouse_button`.
* `Text(String)` là `InputEvent` tag 7, chứa tối đa 256 byte UTF-8 đã commit từ IME; Kotlin không gửi các lần cập nhật composing. Host chuyển sang UTF-16 và gửi theo khối tối đa 20 code unit, không tách cặp surrogate; golden Rust/Kotlin: `input_text`.
* `Capabilities { features: u32 }` là biến thể tag 9. Bit 0..7 lần lượt: `PEN_PRESSURE`, `POINTER_RELATIVE`, `GESTURE`, `KEYBOARD`, `TEXT`, `PEN_CONFIG`, `INPUT_TRUSTED`, `VIDEO_SUSPEND`. Bit lạ được bỏ qua.
* Sau `Accept`, client gửi capabilities; host chỉ trả lời sau khi nhận message đó. Host không chủ động phát capability trước đó. `INPUT_TRUSTED` biểu thị quyền Accessibility trên macOS.
* Frame có enum tag chưa biết nhưng độ dài hợp lệ được bỏ qua; lỗi framing/body khác vẫn giữ hành vi lỗi.
* `PenConfig { pressure_curve: [f32; 4], target: PenTarget }` là control tag 10. Curve là hai handle `[x1, y1, x2, y2]` của cubic Bézier từ (0,0) tới (1,1); host từ chối giá trị không hữu hạn hoặc `x1`/`x2` ngoài [0,1]. `MainDisplay` chọn màn hình chính, `VirtualDisplay` chọn màn hình ảo.
* Host quảng bá hỗ trợ `PEN_CONFIG`; Android chỉ gửi cấu hình sau khi nhận capability này. Curve được áp tại host để cùng một config cho kết quả nhất quán.
