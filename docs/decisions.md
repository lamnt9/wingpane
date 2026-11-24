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
