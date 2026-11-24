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
| `streaming` | `width`, `height`, `hz`, `hidpi` | Đã Accept + tạo màn ảo + bắt đầu capture |
| `stats` | `fps`, `encode_p95_ms`, `write_p95_ms`, `backlog_dropped` | 1 lần/giây khi streaming |
| `client_disconnected` | `reason` | Phiên kết thúc |
| `error` | `message` | Lỗi không chết (vd mất quyền capture) |
| `stopped` | — | Teardown xong, ngay trước khi thoát |

* **MA-5:** `stats` chỉ phát khi `--status-json` (không cần `--stats`).
* **MA-6:** Chuỗi trong JSON được escape đúng chuẩn; mỗi dòng ≤ 4 KiB.

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
 Options ▸  HiDPI ✓ · Test pattern · Bitrate ▸ 10/20/30 Mbps
 Permissions ▸  Screen Recording ✓/✗ · Accessibility ✓/✗ (bấm → mở System Settings)
 Launch at login                        (SMAppService.mainApp)
 ──────────────────────────
 Quit Wingpane                         ⌘Q
```

* **MA-7:** Trạng thái 1 dòng lấy từ sự kiện mới nhất: `adb not found` · `Connect your phone (USB debugging on)` · `Allow USB debugging on the phone` · `Several devices — choose one` · `Waiting for the Wingpane app on <model>` · `Streaming W×H · N fps` · `Stopped`.
* **MA-8:** Đổi Options khi đang chạy → restart daemon với cờ mới.
