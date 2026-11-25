# 06 - Kế Hoạch Triển Khai Phase 2: Bút Áp Lực & Trackpad/Bàn Phím

Phạm vi: roadmap Phase 2 (docs/04) — Chế độ 2 *Graphics Tablet* (áp lực, nghiêng, palm rejection) và Chế độ 3 *Trackpad đa điểm + bàn phím ảo*. Host: macOS (`wingpaned` + `macos-bridge`). Không gồm Bluetooth HID (`BluetoothHidDevice`) — làm plan riêng sau.

---

## 1. Hiện trạng (đã đọc code, 2026-11-25)

### 1.1 Đã có

| Khu vực | Có gì | File |
|---|---|---|
| Proto Rust | `InputEvent::{Pen, Touch, PointerDelta, Scroll, Gesture(GestureKind), Keyboard}` đủ field như docs/02 §3; `Mode::{ExtendedDisplay, GraphicsTablet, TrackpadKeyboard}`; `ControlMessage::SetMode` | `crates/wingpane-proto/src/input.rs`, `control.rs` |
| Proto Kotlin | Codec postcard tay đủ các biến thể trên; golden vectors `input_pen…input_keyboard` đọc từ `crates/wingpane-proto/golden/packets.txt` | `apps/android/.../proto/Protocol.kt`, `test/.../proto/ProtocolTest.kt` |
| Core | `pen::apply_pressure_curve(pressure, &[x1,y1,x2,y2])` (Bézier, có test đơn điệu + NaN) | `crates/wingpane-core/src/pen.rs` |
| Core session | `Session::handle_message(SetMode)` đổi `state` khi Active | `crates/wingpane-core/src/session.rs` |
| Host input | `InputSink { pointer(x,y,phase); release_all() }`; `handle_input` chỉ xử lý `Touch`; `SessionResources::drop` gọi `release_all` trước `capture.stop`/`display.destroy` | `apps/desktop/wingpaned/src/lib.rs` |
| Bridge Swift | `wp_input_pointer` → `PointerState.post`: Down/Move/Up thành `leftMouseDown/Dragged/Up` (hoặc `mouseMoved`) trên `CGDisplayBounds(virtualDisplay)`, post `.cghidEventTap`; `wp_input_release_all` nhả chuột trái; `wp_input_trusted` = `AXIsProcessTrusted()` | `apps/desktop/macos-bridge/Sources/WingpaneBridge/Bridge.swift` |
| Daemon | Kiểm `wp_input_trusted()` lúc khởi động, chỉ in stderr; vòng session log 1 lần "only Touch is handled" cho event khác | `apps/desktop/wingpaned/src/main.rs` (`main`, `run_session`) |
| Mac app | Menu Permissions ▸ Accessibility ✓/✗, mở System Settings (`PermissionsChecker`) ; `DaemonEvent` bỏ qua event lạ (`.unknown`) | `apps/desktop/mac-app/Sources/...` |
| Android input | `TouchMapper` 1 con trỏ, letterbox theo tỉ lệ video, có historical points; `MainActivity.handleTouch` gắn `setOnTouchListener` lên `SurfaceView`, bỏ qua ngón phụ; `cancelTouch()` khi pause/stop/mất kết nối | `input/TouchMapper.kt`, `MainActivity.kt` |
| Android net | `WingpaneClient.sendInput(InputEvent)` chung cho mọi biến thể, hàng đợi riêng trên `ioDispatcher`; `sendControl(ControlMessage)` | `net/WingpaneClient.kt` |
| Android UI | HUD có hàng Mode: Display (active), Pen & Trackpad **disabled "Coming soon"**; tuỳ chọn 3-finger-tap mở HUD | `ui/hud/FloatingHud.kt`, `ui/screen/ConnectionScreen.kt` |

### 1.2 Chưa có / sai lệch với spec

1. **Host không xử lý** `Pen`, `PointerDelta`, `Scroll`, `Gesture`, `Keyboard` (rơi vào nhánh `false` của `handle_input`).
2. **`SetMode` bị bỏ qua** ở daemon: `run_session` chuyển mọi Control sang `handle_control_messages` → `handle_control` trả `None` cho `SetMode`. `Session` chỉ dùng cho Hello; Accept luôn `Mode::ExtendedDisplay`.
3. **`apply_pressure_curve` chưa được ai gọi** ngoài test.
4. **Android không phân biệt tool type**: S-Pen đi chung đường `Touch` (mất pressure/tilt/hover/nút). Không có `setOnGenericMotionListener` (hover), không `requestUnbufferedDispatch`, không palm rejection.
5. Không có `gesture.rs`/bộ nhận diện cử chỉ, không keymap HID→macOS, không UI canvas/trackpad/bàn phím.
6. **Tương thích wire yếu**: biến thể lạ → Rust `FrameError::Decode` → `run_session` `break` (đóng phiên); Kotlin `FrameException` → reconnect. Nghĩa là *thêm biến thể cuối (FR-4) vẫn làm rớt kết nối* nếu bên kia cũ. Phải gỡ trước khi thêm biến thể mới (WP-0).
7. Accessibility thiếu thì `CGEvent.post` bị macOS nuốt im lặng; client không biết.
8. Không có tín hiệu "nút chuột nhấn/nhả" độc lập (cần cho kéo-thả trên trackpad), không có sự kiện nhập text (IME tiếng Việt).

---

## 2. Quyết định thiết kế chung

* **D-1 Không đụng đường stream:** đổi mode **không** huỷ virtual display/capture. Display + capture sống suốt phiên; Android chỉ phủ UI mode lên trên `SurfaceView` (không gỡ `AndroidView`, không đổi z-order). Tạm dừng video là WP-10 tuỳ chọn, tách riêng.
* **D-2 Bút ở chế độ Display = "Sidecar-like":** pen vẽ trực tiếp lên màn ảo (toạ độ theo `CGDisplayBounds(virtualDisplay)`). Chế độ GraphicsTablet: pen map lên **màn hình đích** (mặc định `CGMainDisplayID()`, aspect-fit), tablet hiện canvas đen.
* **D-3 Cử chỉ trackpad diễn giải trên Android** (đã có `PointerDelta/Scroll/Gesture` trong proto), host chỉ inject. Không làm `wingpane-core/gesture.rs` ở Phase 2 (ADR-005: Kotlin trước, dùng chung khi có Windows).
* **D-4 Đường cong áp lực áp ở host** bằng `wingpane_core::pen::apply_pressure_curve` (một nguồn sự thật, Windows dùng lại). Android chỉ gửi pressure thô 0..1.
* **D-5 Quy ước proximity:** `Pen { is_down: false, is_hover: false }` = bút rời vùng cảm ứng (proximity exit). Không cần biến thể mới.
* **D-6 Gating theo capability:** biến thể mới chỉ được gửi sau khi bên kia báo hỗ trợ qua `ControlMessage::Capabilities` (WP-0).

### 2.1 Thay đổi protocol (append-only, `PROTO_VERSION` giữ = 1)

| Enum | Tag mới | Biến thể | WP |
|---|---|---|---|
| `ControlMessage` | 9 | `Capabilities { features: u32 }` | WP-0 |
| `ControlMessage` | 10 | `PenConfig { pressure_curve: [f32; 4], target: PenTarget }` | WP-5 |
| `InputEvent` | 6 | `MouseButton { button: u8 /*0 L,1 R,2 M*/, is_down: bool }` | WP-6 |
| `InputEvent` | 7 | `Text(String)` (UTF-8, ≤ 256 byte) | WP-9 |
| `PenTarget` (mới) | 0/1 | `MainDisplay`, `VirtualDisplay` | WP-5 |

Bit `features` (bit 0 = LSB): `0 PEN_PRESSURE`, `1 POINTER_RELATIVE` (PointerDelta/Scroll/MouseButton), `2 GESTURE`, `3 KEYBOARD`, `4 TEXT`, `5 PEN_CONFIG`, `6 INPUT_TRUSTED` (host có Accessibility), `7 VIDEO_SUSPEND` (WP-10). Bit lạ bỏ qua.

Bắt tay: sau `Accept`, client gửi `Capabilities(client bits)`; host trả `Capabilities(host bits)` (và gửi lại khi `INPUT_TRUSTED` đổi). Host **không bao giờ** gửi `Capabilities` trước khi nhận của client → client cũ không bao giờ thấy tag 9.

Mỗi biến thể mới: thêm vào `golden_packets()` (`crates/wingpane-proto/src/lib.rs`), chạy `cargo run -p wingpane-proto --example golden`, thêm entry vào `ProtocolTest.expected()` Kotlin, thêm roundtrip vào `packet_variants_roundtrip`.

---

## 3. Work packages

Ký hiệu file: `proto/` = `crates/wingpane-proto/src/`, `wpd/` = `apps/desktop/wingpaned/src/`, `bridge/` = `apps/desktop/macos-bridge/Sources/WingpaneBridge/`, `and/` = `apps/android/app/src/main/java/io/wingpane/`, `andt/` = `apps/android/app/src/test/java/io/wingpane/`.

### WP-0 — Nền protocol: bỏ qua gói lạ + `Capabilities`
* **Mục tiêu:** thêm biến thể cuối không còn làm rớt phiên; có cơ chế báo tính năng.
* **File:** `proto/control.rs`, `proto/lib.rs` (export, golden), `crates/wingpane-core/src/transport/tcp.rs` (`FramedReader::recv`, `FramedTcp::recv`: khi body đã đọc đủ mà `FrameError::Decode` → trả `TransportError::UnknownPacket`), `wpd/main.rs` (vòng `run_session`: `UnknownPacket` → log 1 lần rồi `continue`, không `break`), `and/proto/Protocol.kt` (tag `Capabilities`; `FrameDecoder.next()` khi body đủ độ dài nhưng tag enum vượt max → bỏ frame, tăng `skippedFrames`, đọc tiếp; lỗi khác vẫn ném), `and/net/WingpaneClient.kt` (gửi `Capabilities` ngay sau Accept, lưu `hostFeatures: StateFlow<Int>`), `wpd/lib.rs` (`handle_control_messages` trả `Capabilities(host_bits)`).
* **Protocol:** `ControlMessage::Capabilities` tag 9.
* **macOS:** không inject gì. `INPUT_TRUSTED` = `wp_input_trusted()`.
* **Android:** `hostFeatures` = 0 cho tới khi nhận trả lời; mọi WP sau kiểm bit trước khi gửi biến thể mới.
* **Chấp nhận:** host nhận frame có tag Control 99 → phiên vẫn sống, Ping/Pong tiếp tục. Client nhận frame tag lạ → không reconnect. Golden mới khớp hai phía.
* **Test:** Rust: `tcp.rs` loopback gửi body postcard tag lạ rồi Ping → nhận được Ping; `lib.rs` roundtrip + golden. Kotlin: `ProtocolTest` thêm case "unknown control tag is skipped, next frame decodes"; `WingpaneClientTest` client gửi `Capabilities` sau Accept. Thủ công: phiên stream 5 phút trên SM-X710, HUD FPS ổn định như trước.
* **Rủi ro:** nuốt nhầm lỗi thật (body hỏng) → chỉ bỏ qua khi tag *ngoài dải* và độ dài hợp lệ; lỗi khác giữ hành vi cũ.

### WP-1 — Host: InputSink mở rộng, định tuyến `SetMode`, Accessibility
* **Mục tiêu:** khung host cho mọi loại input, không đổi hành vi Touch.
* **File:** `wpd/lib.rs`: `InputSink` thêm method có default no-op: `pen(PenSample)`, `relative(dx,dy)`, `button(u8,bool)`, `scroll(dx,dy)`, `key(hid,down,mods)`, `text(&str)`, `set_mode(Mode)`; `handle_input` dispatch theo `InputEvent` + `Mode` hiện tại (struct `InputRouter { mode, sink }`), `SetMode` → `release_all()` rồi đổi mode. `wpd/main.rs`: giữ `Mode` trong `run_session`, chuyển `SetMode` vào router (không qua `handle_control`), bỏ log "only Touch"; status-json: thêm trường `input_trusted: bool` vào `streaming` và event mới `mode {mode}` (MA §2: app bỏ qua event lạ — đã kiểm `DaemonEvent.unknown`). `bridge/Bridge.swift`: tách `PointerState` sang `bridge/Input.swift` (file mới, cùng target), `wp_input_release_all` nhả **mọi** nút + phím đang giữ.
* **Protocol:** không.
* **macOS:** nếu `!AXIsProcessTrusted()` thì không post (tránh nuốt im lặng), đếm `dropped_untrusted`; kiểm lại mỗi 2 s khi có input, đổi trạng thái → gửi lại `Capabilities` (bit 6) + status `error {message:"Accessibility permission missing"}`.
* **Android:** HUD hiện banner "Mac chưa cấp Accessibility" khi bit 6 = 0 (chỉ khi host đã trả Capabilities).
* **Chấp nhận:** Touch ở Display mode y hệt trước; `SetMode` đổi mode trong log; rút cáp khi đang giữ chuột → không kẹt nút.
* **Test:** Rust: fake sink ghi lại lời gọi — `Touch` vẫn gọi `pointer`; `SetMode` gọi `release_all` trước; thứ tự drop `["pointer","release","stop","destroy"]` (test hiện có) vẫn pass. Swift: build. Thủ công: tắt Accessibility của Wingpane.app → banner hiện trên tablet; bật lại → banner tắt trong ≤ 3 s.
* **Phụ thuộc:** WP-0.

### WP-2 — Bút áp lực ở chế độ Display (S-Pen → tablet CGEvent)
* **Mục tiêu:** S-Pen vẽ lên màn ảo với pressure/tilt/hover/nút; ngón tay giữ hành vi Touch cũ.
* **File:** `and/input/StylusMapper.kt` (mới, thuần Kotlin, nhận primitive như `TouchMapper`: `x,y,pressure,tiltRad,orientationRad,buttonState,action,isHover,eventTimeNs` → `InputEvent.Pen`, dùng lại letterbox của `TouchMapper` — tách `mapPoint` thành hàm `LetterboxMapper` dùng chung), `and/MainActivity.kt` (`handleTouch`: `event.getToolType(i) == TOOL_TYPE_STYLUS/ERASER` → StylusMapper; thêm `setOnGenericMotionListener` cho `ACTION_HOVER_ENTER/MOVE/EXIT`; `view.requestUnbufferedDispatch(event)` ở ACTION_DOWN của stylus; gửi đủ historical samples kèm `getHistoricalEventTimeNanos`), `wpd/lib.rs` (router: `Pen` ở `ExtendedDisplay` → `sink.pen` toạ độ màn ảo; pressure qua `apply_pressure_curve` với curve mặc định linear `[0.25,0.25,0.75,0.75]`), `wpd/main.rs` (`NativeInputSink::pen` → FFI), `bridge/Input.swift` (`wp_input_pen(displayID, x, y, pressure, tiltX, tiltY, buttons, flags)`).
* **Protocol:** không (Pen có sẵn). Tilt: `tilt_x = deg(atan(tan θ·sin φ))`, `tilt_y = deg(atan(tan θ·cos φ))` với θ=`AXIS_TILT`, φ=`AXIS_ORIENTATION`; kẹp −90..90; dấu kiểm trên máy. `button_mask` bit0 = `BUTTON_STYLUS_PRIMARY`, bit1 = `BUTTON_STYLUS_SECONDARY`, bit2 = eraser.
* **macOS:** 
  - Vào vùng (`is_hover` lần đầu hoặc down không qua hover): post `CGEvent` type `.tabletProximity` với `tabletProximityEventEnterProximity=1`, `PointerType=1 (pen)`/`3 (eraser)`, `DeviceID/VendorID/TabletID` cố định, `CapabilityMask` (absX|absY|buttons|pressure|tiltX|tiltY).
  - Hover: `mouseMoved`; chạm: `leftMouseDown` → `leftMouseDragged` → `leftMouseUp`. Mọi event chuột set `mouseEventSubtype = 1 (TabletPoint)`, `tabletEventPointPressure` & `mouseEventPressure` = pressure đã qua curve (0 khi hover), `tabletEventTiltX/Y` = tilt/90 (−1..1), `tabletEventDeviceID` khớp proximity.
  - Nút cạnh khi chạm/hover: map `rightMouseDown/Up` (mặc định); rời vùng (D-5) → `.tabletProximity` enter=0 và nhả mọi nút.
* **Android:** ngón tay vẫn qua `TouchMapper`; khi bút đang down, ngón bị bỏ (palm cơ bản; bản đầy đủ ở WP-3).
* **Chấp nhận:** Krita/Photoshop trên Mac nhận nét đậm-nhạt theo lực; nghiêng bút đổi cọ có tilt; hover thấy con trỏ chạy không vẽ; latency input→cursor ≤ 10 ms qua USB (đo bằng log `ts_us` + host time).
* **Test:** Kotlin `StylusMapperTest`: letterbox, hover→`is_hover`, tilt 0 khi θ=0, tilt dấu đúng cho 4 hướng φ, eraser bit, historical giữ thứ tự & timestamp tăng. Rust: router gọi `pen` với pressure đã qua curve; NaN pressure → 0. Thủ công (SM-X710 + S-Pen): checklist §4 mục P1–P6.
* **Rủi ro:** app Mac chỉ đọc pressure khi có proximity trước → bắt buộc gửi proximity; Samsung gộp event (batching) → bắt buộc dùng historical; tilt S-Pen có thể không có `AXIS_ORIENTATION` → fallback tilt=0.
* **Phụ thuộc:** WP-1.

### WP-3 — Palm rejection 3 cấp
* **Mục tiêu:** tay tì lên kính không tạo click/nét khi dùng bút (docs/03 §2 Chế độ 2).
* **File:** `and/input/PalmRejectionFilter.kt` (mới, thuần Kotlin, input = list `PointerSample(id, toolType, x, y, touchMajorPx, flags, timeNs)` + trạng thái bút), `and/MainActivity.kt` (lọc trước `TouchMapper`), `ui/hud/FloatingHud.kt` (chọn Off/Standard/Strict, lưu `rememberSaveable` + `SharedPreferences`), `res/values/strings.xml`.
* **Quy tắc:** *Standard*: khi bút hover/down hoặc trong 300 ms sau khi rời, bỏ mọi pointer `TOOL_TYPE_FINGER`; pointer có `TOOL_TYPE_PALM` hoặc `FLAG_CANCELED` (API 33+) → gửi `Touch Cancel` cho pointer đang active; `touchMajor` > 30 mm → coi là lòng bàn tay. *Strict*: thêm vùng chết 16 dp ở 4 cạnh cho ngón. *Off*: không lọc.
* **Protocol / macOS:** không.
* **Chấp nhận:** tì cả bàn tay khi vẽ 1 phút trong Krita: 0 click lạ. Off → hành vi như WP-2.
* **Test:** `PalmRejectionFilterTest`: ngón trong lúc bút hover bị bỏ; sau 300 ms được nhận; palm flag → Cancel đúng 1 lần; Strict loại điểm trong 16 dp; Off không lọc. Thủ công P7–P8.
* **Phụ thuộc:** WP-2.

### WP-4 — Chế độ Graphics Tablet (canvas, map màn chính)
* **Mục tiêu:** bật segment "Pen" trong HUD; tablet thành bảng vẽ map lên màn hình Mac chính.
* **File:** `ui/hud/FloatingHud.kt` (Pen segment enabled khi host có bit `PEN_PRESSURE`, callback `onModeChange(Mode)`), `and/MainActivity.kt` (state `mode`, `client.sendControl(SetMode(..))`, khi về Display gửi `RequestKeyframe`), `and/ui/screen/PenCanvasScreen.kt` (mới: nền `#000000` phủ toàn màn, không gỡ SurfaceView; khung tỉ lệ màn đích; dock nút nhanh để trống chờ WP-8), `wpd/lib.rs` (router: `Pen` ở `GraphicsTablet` → target = main display), `bridge/Input.swift` (`wp_input_pen` nhận `targetDisplayID`; daemon truyền `CGMainDisplayID()` qua FFI mới `wp_main_display_id()`).
* **Protocol:** dùng `SetMode` có sẵn. Mapping aspect-fit: tablet 16:10 vs màn đích → letterbox trên tablet (Android vẽ khung), host nhận toạ độ 0..1 trong khung.
* **macOS:** như WP-2, chỉ đổi bounds = `CGDisplayBounds(CGMainDisplayID())`.
* **Chấp nhận:** chuyển Display→Pen→Display 20 lần không mất video, không kẹt nút; ở Pen mode bút điều khiển màn chính, ngón tay không làm gì (trừ 3-finger mở HUD nếu bật).
* **Test:** Rust: router map pen theo mode; `SetMode` liên tiếp gọi `release_all`. Kotlin: `ClientStateMappingTest`/test mới cho reducer mode (bật/tắt segment theo bit). Thủ công P9–P11.
* **Rủi ro:** `SurfaceView` bị destroy khi ẩn → decoder mất surface (đã có `setSurface(null)`) — vì vậy *phủ*, không ẩn. Màn ảo vẫn tồn tại bên Mac (đã biết, xem WP-10).
* **Phụ thuộc:** WP-2 (WP-3 khuyến nghị).

### WP-5 — Pressure curve editor + `PenConfig`
* **Mục tiêu:** chỉnh cảm giác lực (Soft/Linear/Hard + kéo 2 điểm Bézier), chọn đích bút.
* **File:** `proto/control.rs` (`PenConfig`, `PenTarget`), golden + `Protocol.kt`, `wpd/lib.rs` (router lưu curve/target, kiểm `curve[0], curve[2] ∈ [0,1]`), `and/ui/hud/PressureCurveEditor.kt` (mới, Canvas Compose 48dp handle), `and/MainActivity.kt` (gửi `PenConfig` khi đổi & sau mỗi Accept nếu host có bit 5).
* **macOS:** không mới.
* **Chấp nhận:** preset Hard → cần lực mạnh hơn rõ rệt; cấu hình giữ qua reconnect.
* **Test:** golden `control_pen_config`; Rust router dùng curve mới; Kotlin preset → 4 số đúng. Thủ công P12.
* **Phụ thuộc:** WP-0, WP-4.

### WP-6 — Trackpad lõi: di chuột, click, cuộn 2 ngón, kéo-thả
* **Mục tiêu:** bật segment "Trackpad"; 1 ngón di chuột tương đối, tap = click, 2 ngón cuộn, tap 2 ngón = chuột phải, tap-giữ-kéo = drag.
* **File:** `and/input/TrackpadGestureDetector.kt` (mới, thuần Kotlin, state machine nhận `PointerSample` → list `InputEvent`; tham số: tap ≤ 180 ms & ≤ 8 dp, gia tốc con trỏ `gain = 1 + k·speed` cap 4×, cuộn theo pixel, quán tính cuộn giảm dần 325 ms gửi `Scroll` nhỏ), `and/ui/screen/TrackpadScreen.kt` (mới, mặt pad `surface-2`, phủ trên SurfaceView), `FloatingHud.kt` (enable khi bit 1), `proto/input.rs` + `Protocol.kt` (`MouseButton`), `wpd/lib.rs` (router `PointerDelta/Scroll/MouseButton/Gesture(Tap|TwoFingerTap)` mọi mode), `bridge/Input.swift`: `wp_input_relative(dx,dy)`, `wp_input_button(btn, down)`, `wp_input_scroll(dx,dy)`.
* **Protocol:** `InputEvent::MouseButton` tag 6. `Tap` = click trái tại vị trí hiện tại; `TwoFingerTap` = click phải; drag = `MouseButton down` … `PointerDelta` … `MouseButton up`.
* **macOS:** vị trí hiện tại `CGEvent(source:nil)?.location`; cộng delta, kẹp trong hợp các `CGGetActiveDisplayList` bounds; post `.mouseMoved` (hoặc `.leftMouseDragged` nếu nút trái giữ) set `mouseEventDeltaX/Y`. Click: `Down/Up` cùng vị trí, `mouseEventClickState` = 2 khi 2 tap trong `NSEvent.doubleClickInterval`. Cuộn: `CGEvent(scrollWheelEvent2Source:nil, units:.pixel, wheelCount:2, wheel1:dy, wheel2:dx)`; "natural scrolling" để macOS tự áp (gửi theo hướng ngón tay).
* **Android:** trong Trackpad mode ngón tay **không** đi `TouchMapper`; 3-finger-tap HUD vẫn ưu tiên nếu bật (tắt mặc định trong Trackpad để tránh đụng WP-7).
* **Chấp nhận:** di con trỏ qua cả màn chính và màn ảo; double-tap mở file Finder; kéo cửa sổ được; cuộn Safari mượt; rút cáp khi đang drag → không kẹt nút.
* **Test:** `TrackpadGestureDetectorTest` (tap/move threshold, 2-finger tap, scroll, drag lifecycle luôn kết thúc bằng `MouseButton up` kể cả Cancel); Rust router + golden `input_mouse_button`. Thủ công T1–T6.
* **Phụ thuộc:** WP-0, WP-1.

### WP-7 — Cử chỉ 3 ngón & pinch
* **Mục tiêu:** Swipe3Up = Mission Control, Swipe3Down = App Exposé, Swipe3Left/Right = chuyển Space, Pinch = zoom.
* **File:** `TrackpadGestureDetector.kt` (nhận diện 3 ngón: dịch ≥ 60 dp theo trục trội; pinch tích luỹ scale), `wpd/lib.rs` (`Gesture` → chuỗi phím), `bridge/Input.swift` (dùng `wp_input_key` của WP-8, nên làm sau WP-8 hoặc có hàm nội bộ `postKeyCombo`).
* **macOS:** không có API công khai để tổng hợp gesture magnify/swipe → dùng phím tắt hệ thống: Mission Control `Ctrl+↑` (kVK 0x7E), App Exposé `Ctrl+↓` (0x7D), Space `Ctrl+←/→` (0x7B/0x7C); Pinch: mỗi bước scale ±15% → `Cmd+=` / `Cmd+-`. Ghi chú giới hạn: nếu user đổi phím tắt Mission Control trong System Settings thì cử chỉ không chạy.
* **Chấp nhận:** 4 hướng swipe chạy trên macOS mặc định; pinch zoom Safari/Preview.
* **Test:** Kotlin detector (ngưỡng, không nhầm 2 ngón cuộn thành 3 ngón); Rust map `GestureKind` → keycode + flags. Thủ công T7–T8.
* **Phụ thuộc:** WP-6, WP-8.

### WP-8 — Bàn phím ảo HID + numpad
* **Mục tiêu:** QWERTY đầy đủ hàng số + modifier (⌘, ⌥, ⌃, ⇧), numpad 4×5; phím modifier dính (sticky) 1 lần / khoá 2 lần.
* **File:** `and/ui/keyboard/VirtualKeyboard.kt` + `KeyboardLayout.kt` (mới, mỗi phím có `usbHidUsage`, haptic 15 ms), `and/input/KeyboardState.kt` (mới: tính byte `modifiers` theo HID boot: bit0 LCtrl, 1 LShift, 2 LAlt, 3 LGui, 4–7 bên phải; đảm bảo mỗi down có up), `TrackpadScreen.kt` (nửa dưới bàn phím, toggle trackpad/keyboard/numpad), `wpd/keymap_macos.rs` (mới: bảng HID usage page 0x07 → macOS `kVK_*`, kể cả numpad 0x53–0x63, F1–F12, mũi tên), `wpd/lib.rs` (router `Keyboard` → `sink.key`, giữ set phím đang nhấn để `release_all`), `bridge/Input.swift` `wp_input_key(keycode, down, flags)`.
* **Protocol:** dùng `Keyboard` có sẵn (`usb_hid_usage`, `modifiers`).
* **macOS:** `CGEvent(keyboardEventSource:nil, virtualKey:, keyDown:)`, `flags` từ modifiers (`.maskCommand/.maskAlternate/.maskControl/.maskShift`); modifier tự post như phím riêng (kVK 0x37/0x3A/0x3B/0x38) để app thấy `flagsChanged`. `release_all`: post key-up cho mọi phím trong set + modifier, theo thứ tự thường → modifier.
* **Chấp nhận:** gõ được trong TextEdit/Terminal; `⌘C/⌘V`, `⌘Tab`; numpad nhập số; rút cáp khi đang giữ ⇧ → Mac không kẹt Shift (QC gate 3 docs/04).
* **Test:** Rust `keymap_macos` (A=0x04→kVK 0x00, Enter 0x28→0x24, numpad 1 0x59→0x53, không trùng keycode); router release_all nhả đúng. Kotlin `KeyboardStateTest` (sticky, mask bits, up luôn sau down). Thủ công K1–K5.
* **Phụ thuộc:** WP-1 (WP-0 cho bit `KEYBOARD`).

### WP-9 — Nhập text qua IME hệ thống (tuỳ chọn)
* **Mục tiêu:** gõ tiếng Việt (Telex/VNI qua Samsung Keyboard/Gboard) mà không cần IME bên Mac.
* **File:** `and/ui/keyboard/SystemImeBridge.kt` (EditText/`BasicTextField` ẩn, lấy commit text → `InputEvent.Text`, backspace/enter → `Keyboard`), `proto/input.rs` + `Protocol.kt` (`Text`), `wpd/lib.rs`, `bridge/Input.swift` `wp_input_text(utf8, len)`.
* **macOS:** `CGEvent(keyboardEventSource:nil, virtualKey:0, keyDown:true)` + `keyboardSetUnicodeString` theo khối ≤ 20 UTF-16 unit, rồi key-up.
* **Chấp nhận:** gõ "Tiếng Việt có dấu" vào Notes đúng dấu. **Test:** golden `input_text`; Rust tách khối UTF-16 không cắt giữa surrogate. Thủ công K6.
* **Phụ thuộc:** WP-8.

### WP-10 — Tạm dừng video ở mode không phải Display (tuỳ chọn)
* **Mục tiêu:** tiết kiệm băng thông/pin khi ở Pen/Trackpad.
* **File:** `wpd/main.rs` writer task: khi mode ≠ Display thì *bỏ frame lấy từ queue* (không gửi), khi về Display gọi `force_keyframe` trước khi gửi lại. Không đụng capture/encoder.
* **Protocol:** bit `VIDEO_SUSPEND`; client chỉ kỳ vọng ngừng video khi bit bật.
* **Chấp nhận:** Pen mode: băng thông ≈ 0; về Display hình xuất hiện ≤ 300 ms, không xanh/vỡ.
* **Test:** Rust test cho hàm chọn frame (`should_send(mode, awaiting_key)`); thủ công P11 lặp 20 lần.
* **Rủi ro:** chạm đúng đường stream → làm **cuối cùng**, chỉ sau khi WP-4 ổn định; nếu hỏng thì revert riêng commit này.

### 3.1 Thứ tự & phụ thuộc

```
WP-0 ─► WP-1 ─► WP-2 ─► WP-3 ─► WP-4 ─► WP-5
          │                       └────────────► WP-10
          ├─► WP-6 ─────────┐
          └─► WP-8 ─► WP-9  └─► WP-7 (cần WP-8)
```

Thứ tự khuyến nghị: **WP-0, WP-1, WP-2, WP-3, WP-4, WP-6, WP-8, WP-7, WP-5, WP-9, WP-10.** Mốc demo: sau WP-2 (bút áp lực trên màn ảo), sau WP-4 (Chế độ 2), sau WP-8 (Chế độ 3).

---

## 4. Checklist thủ công trên SM-X710 (Galaxy Tab S9) + S-Pen

Chuẩn bị: `Wingpane.app` build từ `apps/desktop/mac-app/build/` (không cài vào /Applications), Accessibility + Screen Recording đã cấp, cáp USB, Krita hoặc Photoshop trên Mac.

| # | Bước | Kỳ vọng |
|---|---|---|
| P1 | Display mode, hover S-Pen cách kính 5 mm | Con trỏ Mac chạy theo, không vẽ |
| P2 | Vẽ nét nhẹ → mạnh trong Krita (brush có pressure) | Nét mảnh → dày liên tục, không bậc thang |
| P3 | Nghiêng bút ~45° bốn hướng (brush tilt) | Hình cọ đổi theo hướng, đúng chiều |
| P4 | Bấm nút cạnh S-Pen khi hover | Context menu (chuột phải) |
| P5 | Nhấc bút ra xa | Proximity exit, không kẹt nút |
| P6 | Vẽ vòng tròn nhanh | Không gãy góc (historical đủ), cảm giác trễ ≤ chuột thường |
| P7 | Palm Standard: tì bàn tay rồi vẽ 1 phút | 0 click/nét lạ |
| P8 | Palm Strict: chạm ngón sát mép 1 cm | Bị bỏ qua |
| P9 | HUD → Pen | Canvas đen, khung đúng tỉ lệ màn chính; bút điều khiển màn chính |
| P10 | Ngón tay trên canvas ở Pen mode | Không tác động Mac |
| P11 | Display↔Pen 20 lần | Video về ngay, không vỡ hình, FPS như trước |
| P12 | Curve Hard vs Soft | Khác biệt rõ cùng lực tay |
| T1 | Trackpad: 1 ngón di | Con trỏ mượt, qua được 2 màn |
| T2 | Tap / tap 2 ngón | Click trái / phải |
| T3 | Double-tap file trong Finder | Mở file |
| T4 | Tap-giữ-kéo cửa sổ | Kéo được, thả đúng chỗ |
| T5 | Cuộn 2 ngón trong Safari, hất mạnh | Cuộn mượt có quán tính |
| T6 | Rút cáp giữa lúc kéo | Mac không kẹt chuột trái |
| T7 | Vuốt 3 ngón lên/xuống/trái/phải | Mission Control / App Exposé / đổi Space |
| T8 | Pinch trong Preview | Zoom in/out |
| K1 | Gõ "hello world" + Enter trong TextEdit | Đúng ký tự |
| K2 | ⌘C, ⌘V, ⌘Tab | Hoạt động |
| K3 | Numpad 0–9, Enter, +−×÷ | Đúng ký tự |
| K4 | Giữ ⇧ rồi rút cáp | Gõ bàn phím Mac sau đó không bị in hoa |
| K5 | Tắt Accessibility rồi gõ | Tablet hiện banner, Mac không nhận, không crash |
| K6 | (WP-9) Gõ Telex "Tiếng Việt" | Notes hiện đúng dấu |
| R1 | Sau mỗi WP: Display mode stream 5 phút | FPS, p50 latency không tệ hơn ADR-006 |

---

## 5. Ràng buộc cho coding agent

1. **Không phá đường display-streaming:** không sửa `CaptureEncoder`, `VirtualDisplay`, `FrameQueue`, `VideoDecoder`, `AnnexB`, z-order/`AndroidView` của `SurfaceView`. Riêng WP-10 được chạm writer task, commit riêng.
2. **Wire-compat:** chỉ thêm biến thể **ở cuối** enum (FR-4), không thêm/sắp lại field của biến thể cũ, không đổi `PROTO_VERSION`. Mỗi biến thể mới: golden Rust + Kotlin cập nhật trong **cùng commit**. Chỉ gửi biến thể mới sau khi peer báo bit trong `Capabilities`.
3. **Test bắt buộc trước mỗi commit** (tất cả phải xanh):
   ```bash
   cargo test --workspace
   (cd apps/android && ./gradlew testDebugUnitTest)
   apps/desktop/mac-app/scripts/build-app.sh          # KHÔNG truyền --install
   ```
4. **Không bao giờ cài vào `/Applications`** (không chạy `build-app.sh --install`, không `cp` vào `/Applications`). Test bằng `apps/desktop/mac-app/build/Wingpane.app`.
5. **Một commit cho mỗi WP**, message dạng `feat(input): WP-2 stylus pressure/tilt on virtual display`. Không gộp WP, không commit file ngoài phạm vi WP.
6. Logic mới viết dạng thuần (Kotlin nhận primitive, Rust trait fake) để unit test được không cần thiết bị — theo mẫu `TouchMapper`/`InputSink` hiện có. Unit test **không** được post CGEvent thật.
7. Mọi đường kết thúc phiên/đổi mode/pause app phải nhả hết nút chuột + phím (`release_all`, `cancelTouch`) — QC gate 3 docs/04.
8. Cập nhật docs/02 (bảng biến thể mới, `Capabilities`, D-5) và docs/05 §2 (trường `input_trusted`, event `mode`) trong cùng commit với WP đưa thay đổi đó vào; ADR mới vào `docs/decisions.md` cho D-1, D-4, D-6 ở WP-0.
9. UI tuân docs/03: nút ≥ 48dp, tương phản ≥ 7:1, token màu `WingpaneTheme`.
