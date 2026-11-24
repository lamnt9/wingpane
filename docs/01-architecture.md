# 01 - Kiến Trúc Monorepo & Hệ Thống

## 1. Cấu Trúc Monorepo

```
wingpane/
├── Cargo.toml                     # Cargo workspace definition
├── crates/
│   ├── wingpane-proto/            # Schemas, serialisation/deserialisation (Postcard)
│   │   ├── Cargo.toml
│   │   └── src/
│   │       ├── lib.rs
│   │       ├── control.rs
│   │       ├── frame.rs
│   │       └── input.rs
│   └── wingpane-core/             # Session lifecycle, transport abstraction, jitter buffer
│       ├── Cargo.toml
│       └── src/
│           ├── lib.rs
│           ├── session.rs
│           ├── transport/
│           │   ├── mod.rs
│           │   ├── usb.rs         # adb reverse tunnel
│           │   └── quic.rs        # UDP QUIC stream
│           ├── pen.rs             # Bézier curve calculation for pressure
│           └── gesture.rs         # Trackpad multi-touch gesture interpreter
├── apps/
│   ├── desktop/                   # Desktop Host App (Tauri 2 + Rust)
│   │   ├── src-tauri/
│   │   │   ├── Cargo.toml
│   │   │   ├── tauri.conf.json
│   │   │   └── src/
│   │   │       ├── main.rs
│   │   │       └── host/
│   │   │           ├── mod.rs     # Host trait
│   │   │           ├── macos.rs   # CGVirtualDisplay + ScreenCaptureKit + VideoToolbox
│   │   │           ├── windows.rs # IddCx driver + D3D11 + NVENC/AMF + InjectSyntheticPointer
│   │   │           └── linux.rs   # Wayland portal / PipeWire + uinput
│   │   └── ui/                    # System tray & pairing settings (Svelte / HTML-Tailwind)
│   └── android/                   # Android App (Kotlin + Jetpack Compose)
│       ├── app/
│       │   ├── build.gradle.kts
│       │   └── src/main/java/io/wingpane/
│       │       ├── ui/            # Compose screens (HUD, Canvas, Pad, Keyboard)
│       │       ├── video/         # MediaCodec hardware low-latency surface decoder
│       │       ├── input/         # MotionEvent, Stylus, Palm rejection
│       │       └── net/           # Transport client (USB / QUIC)
│       └── rust-jni/              # Shared wingpane-core via uniffi bindings
├── design-system/wingpane/        # UI/UX Pro Max tokens (tokens.json, tokens.css, MASTER.md)
└── docs/                          # Detailed technical documentation
```

---

## 2. Pipeline Xử Lý Video & Màn Hình Ảo

```
[Host] OS Virtual Display (macOS CGVirtualDisplay / Win IddCx / Linux Portal)
   │
   ▼
[Host] Zero-Copy GPU Capture (ScreenCaptureKit / D3D11 Texture / PipeWire DMA-BUF)
   │
   ▼
[Host] Hardware Low-Latency Encoder (VideoToolbox / NVENC / AMF / VAAPI)
   │  - Baseline/Main H.264 (hoặc HEVC), No B-frame, Slice mode, intra-refresh
   ▼
[Transport] Packetizer (Timestamp nano-giây nhúng vào mỗi packet)
   │  - USB Mode: TCP tunnel qua `adb reverse tcp:7788 tcp:7788`
   │  - Wi-Fi Mode: QUIC Datagram (kênh video) + Reliable Stream (kênh control)
   ▼
[Android] Hardware MediaCodec (KEY_LOW_LATENCY=1, KEY_PRIORITY=0)
   │
   ▼
[Android] SurfaceView Render (Choreographer sync)
```

---

## 3. Pipeline Xử Lý Input & Cảm Ứng Bút

```
[Android] S-Pen / Touch / Trackpad (MotionEvent with unbuffered dispatch)
   │  - Historical batches (getHistoricalX/Y)
   │  - Pressure, Tilt X/Y, Hover, ToolType
   ▼
[Android] Palm Rejection Filter (Lọc tiếp xúc bàn tay khi phát hiện Stylus)
   │
   ▼
[Transport] InputEvent Binary Packet (Độ trễ < 5ms)
   │
   ▼
[Host] Input Injector:
   - macOS: CGEvent (tablet subtype, kCGTabletEventPointPressure)
   - Windows: InjectSyntheticPointerInput (PT_PEN, PT_TOUCH) & SendInput
   - Linux: uinput kernel device (/dev/uinput)
```
