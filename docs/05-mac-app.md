# 05 - App macOS (menu bar) — v1

Quyết định: anh Lâm duyệt 2026-11-25 — app **menu bar SwiftUI** (`MenuBarExtra`), dùng menu native của macOS nên **không qua prototype**. Daemon `wingpaned` (ADR-004) được đóng gói trong `Wingpane.app` và chạy như tiến trình con.

## 1. Kiến trúc

```
Wingpane.app (SwiftUI, LSUIElement — không có icon Dock)
 └── Contents/MacOS/Wingpane          ← UI menu bar
 └── Contents/Resources/wingpaned     ← daemon Rust (đã link Swift bridge)
         ▲ spawn với --status-json [--serial X] [--test-pattern] …
         │ stdout: JSON Lines (§2)  ·  dừng: SIGINT (teardown sạch)
```

* **MA-1:** App không tự xử lý video/input; mọi việc do daemon làm. App chỉ spawn/stop daemon và hiển thị trạng thái từ §2.
* **MA-2:** Quyền macOS (Screen Recording, Accessibility) được cấp cho **Wingpane.app** (tiến trình cha chịu trách nhiệm). App kiểm `CGPreflightScreenCaptureAccess()` và `AXIsProcessTrusted()` khi khởi động và có mục menu mở đúng trang System Settings.
* **MA-3:** Tìm `adb` theo thứ tự: `$ANDROID_HOME/platform-tools/adb`, `~/Library/Android/sdk/platform-tools/adb`, `/opt/homebrew/bin/adb`, `/usr/local/bin/adb`. Không thấy → trạng thái "adb not found" + hướng dẫn cài platform-tools. Đường dẫn tìm được truyền cho daemon qua biến môi trường `WINGPANE_ADB`.
* **MA-4:** Quit app → SIGINT daemon, chờ tối đa 3 s, quá hạn thì SIGKILL. Daemon tự thoát nếu tiến trình cha chết (kiểm `getppid()==1` mỗi 1 s khi chạy với `--status-json`).

## 2. Giao thức trạng thái daemon → app (`--status-json`)

Mỗi dòng stdout là một object JSON có trường `event`. Log người đọc vẫn đi stderr. Trường không biết thì app bỏ qua (thêm trường mới không phá app).

| `event` | Trường | Khi nào |
|---|---|---|
| `starting` | `version` | Daemon khởi động |
| `adb_missing` | — | Không tìm thấy adb |
| `waiting_device` | `reason`: `none`·`multiple`·`unauthorized`, `devices`: [{`serial`,`state`,`model`?}] | Đang chờ đúng 1 máy hợp lệ |
| `device` | `serial`, `model`? | Đã chọn máy, đã `adb reverse` |
| `listening` | `port` | Socket sẵn sàng |
| `client_connected` | `peer` | App Android nối vào |
| `streaming` | `width`, `height`, `hz`, `hidpi`, `input_trusted` | Đã Accept + tạo màn ảo + bắt đầu capture; Accessibility hiện tại |
| `mode` | `mode` | Host áp dụng SetMode từ client hoặc lệnh stdin |
| `permissions` | `input_trusted`, `screen_recording`, `input_monitoring` (bool) | Khi quyền của daemon thay đổi; Input Monitoring dùng `CGPreflightListenEventAccess()` |
| `uc` | `state`: `unavailable`·`disabled`·`armed`·`inside`, `edge`: `left`·`right`·`top`·`bottom`, `client_ready` (bool), `reason`? (string), `edge_blocked` (bool) | Khi trạng thái UC thay đổi và heartbeat 250 ms; app cũ bỏ qua event lạ |
| `stats` | `fps`, `encode_p95_ms`, `write_p95_ms`, `backlog_dropped` | 1 lần/giây khi streaming |
| `client_disconnected` | `reason` | Phiên kết thúc |
| `error` | `message` | Lỗi không chết (vd mất quyền capture) |
| `stopped` | — | Teardown xong, ngay trước khi thoát |

* **MA-5:** `stats` chỉ phát khi `--status-json` (không cần `--stats`).
* **MA-6:** Chuỗi trong JSON được escape đúng chuẩn; mỗi dòng ≤ 4 KiB.

### 2.1 Lệnh app → daemon (stdin JSON Lines, WP-U5)

Bật `--status-json` để daemon đọc stdin. Mỗi dòng UTF-8 là một object (tối đa 4095 byte trước newline); dòng lỗi sinh `error`, bỏ dòng đó rồi đọc tiếp. EOF đóng riêng kênh lệnh, không dừng phiên. CLI `--uc-edge <left|right|top|bottom>` đặt cạnh ban đầu (mặc định `right`).

```json
{"cmd":"uc_enable","enabled":true}
{"cmd":"uc_edge","edge":"left"}
{"cmd":"uc_return"}
{"cmd":"uc_enable","enabled":false}
```

* `uc_enable`: bật `AndroidControl`; tắt trở về mode trước khi bật (D-21). Nếu chưa có bit 8 hai phía/tap không dùng được, báo `error`, không đổi mode. Lệnh trước khi kết nối được giữ đến khi nhận Capabilities.
* `uc_edge`: đổi cạnh tại chỗ, không restart (ngoại lệ MA-8, D-20). Nếu đang inside, trả chuột về cạnh cũ trước rồi gửi `UcConfig` mới.
* `uc_return`: trả chuột/bàn phím cho Mac, giữ mode AndroidControl để có thể vào lại cạnh.
* Kênh `watch` giữ cấu hình mới nhất; đổi cạnh và bật/tắt liên tiếp không ghi đè mất trường của nhau. Nhiều lệnh bật/tắt chưa được xử lý được gộp thành giá trị cuối. Host gửi `SetMode` sau khi áp dụng; yêu cầu từ client không echo (D-2).
* `uc.client_ready` = SERVICE_CONNECTED && GESTURES_OK && FGS_RUNNING && !KEYGUARD_LOCKED (§2.1 docs/07). `edge_blocked=true` khi toàn bộ cạnh bị display khác che hoặc chưa có hình học màn chính. `reason` dùng snake_case: `cursor_returned`, `hotkey`, `mode_changed`, `client_not_ready`, `tap_disabled`, `watchdog`, `secure_input`, `mac_locked`, `session_ended`.
* Host chỉ quảng bá bit 8 khi tạo thử tap thành công; chỉ gửi UC/Input host→client sau bit 8 của peer (D-3). Bit 8 cũng yêu cầu ngừng video/gỡ màn ảo khi vào AndroidControl (D-1). Heartbeat Swift 250 ms; delta có deadline riêng 4 ms; refresh display mỗi 2 s. Peer UC không trả Pong trong 3 s kết thúc phiên và release UC trước input.

Kiểm thủ công (orchestrator, ngoài sandbox): chạy daemon với `--status-json --uc-edge right`, rồi `cargo run -p wingpaned --example dump_client -- --uc --seconds 60 --uc-return-after 5`. Client giả quảng bá bit 8, đợi host bit 8, gửi ready + AndroidControl, in input/control; 5 s sau `UcEnter` gửi `UcExit{along:0.5}`. Kiểm Ctrl+C khi inside và stdin đổi cạnh theo docs/07 U14–U16; chạy R1 cho thay đổi `apply_mode` (§6.4).

Giới hạn bridge U4 ở commit `6df577d` (chưa sửa trong phạm vi daemon): `UniversalControl.swift` gộp finger phase và momentum phase vào cùng byte; Rust không thể phân biệt chắc chắn để bỏ momentum theo D-17. `UcSupport.ucMacDeviceFlags` chỉ giữ modifier trái nên thông tin trái/phải đã mất trước callback. Reducer Rust hỗ trợ Momentum và đủ 8 modifier, nhưng dữ liệu runtime cần sửa bridge ở hai điểm này. Outside button state được kiểm tra qua Quartz trước yêu cầu vào; trạng thái Secure Input được đọc thêm ở heartbeat để nhận cả chiều tắt. Warp lùi vào 2 pt (§2.2 docs/07) vẫn phụ thuộc bridge U4; hiện bridge trả về ngay biên màn. Các hành vi native này cần kiểm trên máy thật.

## 3. Menu

```
[icon]  ← xám: tắt · xanh: đang stream · vàng: chờ máy/chờ app · đỏ: lỗi
 ──────────────────────────
 Wingpane — <trạng thái 1 dòng>       (vd "Streaming 2340×1080 · 57 fps")
 Device: <model/serial> ▸ (submenu chọn máy khi có nhiều máy)
 ──────────────────────────
 Start / Stop                          ⌘S
 Open app on phone                     (adb shell am start …)
 ──────────────────────────
 Control Android ▸  Enabled ✓            (gửi uc_enable; disabled khi uc=unavailable)
                    Edge ▸ Left · Right ✓ · Top · Bottom
                    Return to Mac now      (chỉ khi inside; gửi uc_return)
                    ─ Ctrl+⌥+Esc: luôn trả chuột về Mac
                    <trạng thái 1 dòng>
 Options ▸  HiDPI ✓ · Test pattern · Bitrate ▸ 10/20/30 Mbps
 Permissions ▸  Screen Recording ✓/✗ · Accessibility ✓/✗ · Input Monitoring ✓/✗ (bấm → mở System Settings)
 Launch at login                        (SMAppService.mainApp)
 ──────────────────────────
 Quit Wingpane                         ⌘Q
```

* **MA-7:** Trạng thái 1 dòng lấy từ sự kiện mới nhất: `adb not found` · `Connect your phone (USB debugging on)` · `Allow USB debugging on the phone` · `Several devices — choose one` · `Waiting for the Wingpane app on <model>` · `Streaming W×H · N fps` · `Stopped`. Dòng trạng thái trong `Control Android`: `Android: ready` · `Android: inside` · `Android: Accessibility service off on tablet` · `Android: Edge blocked by another display` · `Android: Secure input on`.
* **MA-8:** Đổi Options khi đang chạy → restart daemon với cờ mới (ngoại lệ D-20: đổi `ucEdge` gửi lệnh stdin `uc_edge`, không restart daemon). Khi daemon thoát, khi Quit app, hoặc khi `uc.state == inside` mà quá 3 s không có dòng stdout → `SIGKILL` daemon và gọi `CursorSafety.restore()` (`CGAssociateMouseAndMouseCursorPosition(1)` + warp về giữa màn chính, D-10.5).
