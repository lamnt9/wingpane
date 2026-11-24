# 03 - Đặc Tả UI/UX Chuẩn UI/UX Pro Max

Tuân thủ bộ tokens tại `design-system/wingpane/` và tiêu chuẩn thiết kế ứng dụng di động native/OLED:

## 1. Hệ Màu & Typography

* **Style ID:** `dark-mode-oled`
  * `--color-bg`: `#000000` (Đen OLED thuần túy, tiết kiệm pin tối đa cho màn hình AMOLED của tablet).
  * `--color-surface-1`: `#0A0E27` (Midnight Blue - thanh công cụ, HUD, dock).
  * `--color-surface-2`: `#121212` (Dark Grey - phím bấm bàn phím, pad di chuột).
  * `--color-primary`: `#5B8CFF` (Electric Blue - trạng thái active).
  * `--color-accent`: `#3DDC97` (Neon Mint - thông báo kết nối thành công, USB 120Hz).
  * `--color-text`: `#F2F4FF` (Độ tương phản 12:1 trên nền đen, vượt chuẩn WCAG AAA).
* **Typography:**
  * Heading: `Outfit` (Hình học hiện đại, sắc nét).
  * Body: `Work Sans` (Dễ đọc ở kích thước nhỏ).
* **Quy chuẩn xúc giác & tiếp xúc:**
  * Kích thước nút chạm tối thiểu: **48×48dp** (khoảng cách tối thiểu 8dp).
  * Phản hồi chạm (Haptic feedback): Rung nhẹ 15ms khi bấm phím ảo hoặc chạm toolbar.

---

## 2. Thiết Kế Chi Tiết 3 Chế Độ

### Chế Độ 1: Extended Virtual Display (Màn Hình Phụ)
* **Giao diện:** Fullscreen không viền (Immersive Sticky Mode), ẩn hoàn toàn thanh điều hướng Android.
* **Floating Quick HUD:**
  * Nút nổi hình tròn bán kính 24dp (48dp đường kính) ở góc màn hình.
  * Tự động làm mờ còn 20% opacity sau 3 giây không chạm.
  * Chạm 3 ngón tay bất kỳ lên màn hình để gọi nhanh HUD.
  * Menu HUD mở rộng gồm: Đo đạc FPS / Ping thời gian thực, Khóa tỷ lệ màn hình, Chuyển đổi nhanh chế độ Bút vẽ / Chuột.
* **Toolbar Bút vẽ (S-Pen / Stylus):** Dock nổi cạnh màn hình chứa các phím tắt nhanh (Ctrl, Z, Shift, Space - Hand tool).

### Chế Độ 2: Digital Graphics Tablet (Bảng Vẽ Cảm Ứng)
* **Canvas Vùng Vẽ:** Tận dụng 100% diện tích mặt kính.
* **Palm Rejection 3 Cấp Độ:**
  * *Standard:* Khi đầu bút S-Pen chạm hoặc hover trong bán kính 15mm, vô hiệu hóa hoàn toàn mọi cảm ứng từ ngón tay / lòng bàn tay.
  * *Strict:* Thêm vùng chết 16dp xung quanh 4 cạnh viền tablet để tì tay thoải mái.
* **Bộ Điều Chỉnh Lực Nhấn (Pressure Curve Editor):**
  * Biểu đồ đường cong Bézier 3 điểm (Soft - Linear - Hard) cho phép tùy chỉnh cảm giác nhấn cọ theo thói quen vẽ.

### Chế Độ 3: Standalone Trackpad & Keyboard (Chuột & Phím Rời)
* **Bàn di chuột (Trackpad):**
  * Hỗ trợ cử chỉ 1 ngón (Di chuột, Tap = Click), 2 ngón (Cuộn mượt tự nhiên, Tap 2 ngón = Chuột phải, Chụm = Zoom), 3 ngón (Vuốt lên = Mission Control / Task View, Vuốt ngang = Chuyển Desktop ảo).
* **Bàn phím ảo:**
  * Bố cục QWERTY đầy đủ hàng số và modifier (⌘/Win, Alt, Ctrl, Shift).
  * Numpad chuyên dụng dạng lưới 4×5 cho nhập liệu số nhanh.
  * Phím bấm có hiệu ứng thụt xuống (micro-depth) và âm thanh click xúc giác nhẹ.
