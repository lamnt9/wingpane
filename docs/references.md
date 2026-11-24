# 📚 Kế Hoạch Tham Khảo Mã Nguồn Mở (Reference Matrix)

Để xây dựng **Wingpane** nhanh, chuẩn xác và không "phát minh lại bánh xe", dự án sẽ chắt lọc tinh hoa từ 5 kho mã nguồn mở hàng đầu thế giới theo kế hoạch phân rã dưới đây:

---

## 1. Bảng Phân Rã & Mục Tiêu Nghiên Cứu

| STT | Repo Mã Nguồn Mở | Ngôn ngữ | Bộ phận / Tính năng Wingpane tham khảo | Các file & module cốt lõi cần mổ xẻ |
|---|---|---|---|---|
| **1** | **[SideScreen](https://github.com/tranvuongquocdat/SideScreen)** | Swift / Kotlin | **macOS Virtual Display & Android Decoder**<br/>• Tạo màn hình ảo bằng `CGVirtualDisplay`<br/>• Pipeline `MediaCodec` low-latency trên SurfaceView<br/>• Bắt sự kiện S-Pen lực nhấn | • `macos/SideScreen/Display/`<br/>• `android/app/.../Decoder.kt`<br/>• `android/app/.../PenHandler.kt` |
| **2** | **[scrcpy](https://github.com/Genymobile/scrcpy)** | C / Java | **Đường hầm USB (Transport) & Raw Packet**<br/>• Cơ chế `adb reverse` tunnel độ trễ < 5ms<br/>• Framing gói tin nhị phân không rườm rà<br/>• Quản lý kết nối USB tự động phục hồi | • `app/src/adb/adb.c`<br/>• `app/src/demuxer.c`<br/>• `server/.../ScreenEncoder.java` |
| **3** | **[Weylus](https://github.com/H-M-H/Weylus)** | Rust / TypeScript | **Xử lý Bút vẽ & Cử chỉ cảm ứng (Rust Core)**<br/>• Thuật toán đường cong áp lực Bézier<br/>• Inject sự kiện bút vào Windows (`InjectSyntheticPointerInput`) và Linux (`uinput`) | • `src/input/`<br/>• `src/desktop/windows.rs`<br/>• `src/desktop/linux.rs` |
| **4** | **[ghost-hid](https://github.com/ShubhamJ010/ghost-hid)** | Kotlin (Compose) | **Chế độ Chuột & Bàn phím Bluetooth rời**<br/>• Gọi native API `BluetoothHidDevice` của Android 9+<br/>• HID Descriptors cho bàn phím, trackpad, chuột không cần cài app máy tính | • `app/.../bluetooth/`<br/>• `app/.../hid/HidProfile.kt`<br/>• `app/.../ui/trackpad/` |
| **5** | **[Virtual-Display-Driver](https://github.com/itsmeiketh/Virtual-Display-Driver)** | C++ (UMDF) | **Màn hình ảo cho Windows 10/11**<br/>• Driver IddCx đã ký số sẵn<br/>• Tạo swap chain texture chia sẻ sang DirectX D3D11 | • `IddSampleDriver/`<br/>• `Driver.cpp` |

---

## 2. Kế Hoạch Clone & Spike Từng Repo

Các repo tham khảo được lưu vào thư mục `vendor-refs/` (đã cấu hình bỏ qua trong `.gitignore` để không làm nặng git history của dự án):

### Bước 1: Spike macOS Host & Android Decoder (Tuần 1 - Phase 0)
* **Nguồn chính:** `SideScreen` + `scrcpy`.
* **Mục tiêu trích xuất:**
  1. Bóc tách logic khởi tạo `CGVirtualDisplay` trên macOS và gói gọn vào module Rust FFI (`apps/desktop/src-tauri/src/host/macos.rs`).
  2. Bóc tách logic cấu hình `MediaCodec` với cờ `KEY_LOW_LATENCY = 1` từ SideScreen sang Android module (`apps/android/app/.../video/Decoder.kt`).

### Bước 2: Spike USB Transport & Framing (Tuần 2 - Phase 0)
* **Nguồn chính:** `scrcpy`.
* **Mục tiêu trích xuất:**
  1. Cơ chế gọi tiến trình `adb reverse tcp:7788 tcp:7788` tự động khi cắm cáp USB.
  2. Kênh heartbeat Ping/Pong đo latency nano-giây.

### Bước 3: Spike Stylus & Gesture Input (Tuần 3 - Phase 1 & 2)
* **Nguồn chính:** `Weylus`.
* **Mục tiêu trích xuất:**
  1. Logic ánh xạ tọa độ màn hình chuẩn hóa `0.0..=1.0` sang tọa độ pixel thực tế của Virtual Display.
  2. Công thức lọc rung tay và Palm Rejection.

### Bước 4: Spike Standalone Bluetooth HID (Tuần 4 - Phase 2)
* **Nguồn chính:** `ghost-hid`.
* **Mục tiêu trích xuất:**
  1. Đăng ký SDP Record và HID Device Service trên Android.
  2. Bố cục phím ảo QWERTY gửi raw HID scan-codes.
