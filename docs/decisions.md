# Nhật Ký Quyết Định Kỹ Thuật (Architecture Decision Records - ADR)

File này lưu trữ các quyết định kiến trúc quan trọng của dự án Wingpane (chỉ ghi thêm, không chỉnh sửa quyết định cũ).

---

## [ADR-001] 2026-11-24: Đặt tên dự án và cấu trúc Monorepo
* **Trạng thái:** Approved
* **Bối cảnh:** Cần một cái tên cho dự án mã nguồn mở biến Android thành màn hình phụ, bảng vẽ và bàn phím/chuột cho PC.
* **Quyết định:** Chọn tên **`wingpane`** (Máy tính bảng như cánh phụ mở rộng màn hình).
* **Hệ quả:** Sử dụng cấu trúc Cargo Monorepo kết hợp Tauri (Host) và Kotlin Compose (Client Android).

---

## [ADR-002] 2026-11-24: Lựa chọn công nghệ Desktop Host & Giao thức
* **Trạng thái:** Approved
* **Bối cảnh:** Cần hiệu năng cao nhất, không bị giật lag do Garbage Collection khi truyền video 60-120 FPS.
* **Quyết định:**
  * Host sử dụng **Rust** làm core xử lý video, network và input injection. Giao diện desktop dùng **Tauri 2**.
  * Giao thức tuần tự hóa dùng **`postcard`** (binary compact, no_std friendly, giải mã cực nhanh).
  * Giai đoạn MVP kết nối qua cáp USB sử dụng đường hầm `adb reverse` (kế thừa tính ổn định tuyệt đối từ `scrcpy`).
  * Wi-Fi sử dụng giao thức **QUIC** (kênh video datagram không retransmit để ưu tiên độ trễ, kênh điều khiển tin cậy).

---

## [ADR-003] 2026-11-24: Thiết kế giao diện Dark Mode OLED
* **Trạng thái:** Approved
* **Bối cảnh:** Tablet Android thường sử dụng màn hình OLED/AMOLED; người dùng đồ họa và lập trình viên làm việc nhiều trong bóng tối.
* **Quyết định:** Áp dụng bộ Design Tokens Dark Mode OLED từ skill `ui-ux-pro-max`: nền đen thuần `#000000`, bề mặt `#0A0E27`, chữ `#F2F4FF`, kích thước nút chạm tối thiểu 48×48dp, tương phản ≥ 7:1.

---

## [ADR-004] 2026-11-24: Host MVP là daemon dòng lệnh Rust + Swift bridge
* **Trạng thái:** Approved
* **Bối cảnh:** `CGVirtualDisplay` là API riêng của Apple, chỉ gọi được từ Swift/ObjC. Dựng UI Tauri song song với spike pipeline video làm chậm Phase 1.
* **Quyết định:** Phase 1 host macOS chạy dạng daemon CLI: Rust (`wingpane-core`) lo session/transport, Swift package `apps/desktop/macos-bridge` lo màn hình ảo, ScreenCaptureKit, VideoToolbox, gọi qua C ABI. Tauri 2 (ADR-002) bổ sung sau khi pipeline chạy ổn.
* **Hệ quả:** Cần lớp FFI Swift↔Rust; UI desktop dời sang sau Phase 1.

---

## [ADR-005] 2026-11-24: Android tự viết codec postcard bằng Kotlin cho MVP
* **Trạng thái:** Approved (Claude điều phối, chờ anh xem lại)
* **Bối cảnh:** docs/01 dự kiến dùng `wingpane-core` qua uniffi/JNI trên Android. MVP chỉ cần mã hoá/giải mã `Packet` (docs/02 §4–§5).
* **Quyết định:** MVP viết codec postcard bằng Kotlin cho đúng tập `Packet` hiện có (varint, enum tag, length-prefix). Tính đúng được khoá bằng **golden vectors** do Rust sinh (`wingpane-proto` xuất file hex, test Kotlin đọc lại).
* **Lý do:** Bỏ toolchain NDK/uniffi khỏi đường găng MVP; đường giải mã video giữ thuần Kotlin, không phải copy qua JNI.
* **Rủi ro:** Hai bên schema lệch nhau → chặn bằng golden test bắt buộc trong CI. Xem lại khi cần dùng chung logic nặng (đường cong áp lực, gesture) ở Phase 2.

---

## [ADR-006] 2026-11-25: Chấp nhận p95 độ trễ cho MVP USB
* **Trạng thái:** Approved (anh Lâm)
* **Bối cảnh:** Đo trên Galaxy A36, 2340×1080@60 qua `adb reverse`: e2e p50 18.8 ms (đạt ≤25 ms), p95 40 ms. Phân rã: encode p95 8.7 ms, ghi socket 0.1 ms, giải mã p95 14 ms, đường adb p95 ≈18 ms.
* **Quyết định:** Phát hành MVP với p95 40 ms là known issue. Không tối ưu thêm trong Phase 1.
* **Hệ quả:** Phase sau spike transport thay thế (USB tethering/RNDIS, Wi‑Fi QUIC theo roadmap Phase 4) và đo lại theo telemetry theo chặng (`wingpaned --stats`, logcat `WingpaneLatency`).

## [ADR-007] 2026-11-25: Giữ stream khi đổi chế độ input
* **Trạng thái:** Đã chốt trong kế hoạch Phase 2 (`docs/06-pen-trackpad-plan.md`).
* **Quyết định:** Android giữ stream surface qua đổi mode. Nếu client quảng bá `VIDEO_SUSPEND`, host gỡ virtual display và capture khi vào control mode, tạo lại khi quay về Display; nếu không, giữ hành vi hiện tại (D-1). Áp đường cong pressure ở host làm nguồn thống nhất (D-4). Chỉ gửi tính năng sau capabilities (D-6), với `INPUT_TRUSTED` phản ánh Accessibility.
* **Hệ quả:** Display stream chỉ tạm ngừng phía host khi client hỗ trợ; khi tạo lại, host yêu cầu keyframe. Input bút không dùng ID display cũ.

## [ADR-008] 2026-11-25: Universal Control là mode thứ tư
* **Trạng thái:** Approved theo kế hoạch `docs/07-universal-control-plan.md` (D-1).
* **Quyết định:** Thêm `Mode::AndroidControl` tag 3; mode này không có virtual display. Tái dùng SetMode và display transition để gỡ capture/display khi điều khiển Android.
* **Hệ quả:** Không xem được stream Mac đồng thời; trở lại mode có display sẽ tạo lại display như luồng hiện tại.

## [ADR-009] 2026-11-25: Gating Universal Control bằng capability bit 8
* **Trạng thái:** Approved theo kế hoạch (D-3).
* **Quyết định:** Chỉ trao đổi mode AndroidControl, Uc*, PreciseScroll và Input host→client sau khi peer báo bit 8. Accept.selected_mode không bao giờ là AndroidControl.
* **Hệ quả:** Peer cũ không bị gửi các enum tag mới; bit được định nghĩa ở WP-U0 nhưng daemon chưa quảng bá.

## [ADR-010] 2026-11-25: Bắt input Mac bằng CGEventTap trong daemon
* **Trạng thái:** Approved theo kế hoạch (D-6), cần xác nhận bằng spike macOS WP-U4.
* **Quyết định:** Universal Control bắt mouse, keyboard và scroll bằng active `kCGHIDEventTap`; callback nuốt event khi đang điều khiển Android và đẩy bản sao vào Rust.
* **Hệ quả:** Tap cần thread có CFRunLoop; hotkey/watchdog/khôi phục con trỏ phải bảo đảm thoát an toàn.

## [ADR-011] 2026-11-25: Android inject qua AccessibilityService
* **Trạng thái:** Approved theo kế hoạch (D-12).
* **Quyết định:** Dùng `AccessibilityService` gesture API và global actions; không root hoặc `adb shell input`.
* **Hệ quả:** Android áp dụng các giới hạn của dispatchGesture, quyền Accessibility và hỗ trợ phiên bản API đã nêu trong kế hoạch.

## [ADR-012] 2026-11-25: Gõ tiếng Việt bằng Telex ở Android
* **Trạng thái:** Approved theo kế hoạch (D-15), quy tắc nhập liệu cần kiểm nghiệm khi triển khai.
* **Quyết định:** Gửi HID thô, ghép Telex tại Android; không làm cầu IME Mac và không dựa vào IME Android để ghép chuỗi.
* **Hệ quả:** V1 nhắm bàn phím US ANSI; cách xử lý event tap với EVKey/OpenKey cần kiểm trên máy thực.
