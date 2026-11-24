# 00 - Tổng Quan Dự Án Wingpane

**Mục tiêu:** Biến tablet/điện thoại Android thành màn hình phụ mở rộng, bảng vẽ cảm ứng áp lực và trackpad/bàn phím rời cho máy tính (macOS, Windows, Linux) với độ trễ siêu thấp qua cáp USB và Wi-Fi nội bộ.

---

## 1. Ba Chế Độ Hoạt Động (3-in-1 Modes)

| Chế độ | Mô tả nghiệp vụ | Luồng dữ liệu chính |
|---|---|---|
| **1. Extended Display** | Màn hình phụ ảo độc lập (kéo thả cửa sổ từ PC sang tablet). | Host tạo Virtual Display ➔ GPU Capture ➔ HW Encode ➔ USB/Wi-Fi ➔ Tablet HW Decode (MediaCodec) ➔ SurfaceView. Tablet gửi ngược Touch/Pen event. |
| **2. Graphics Tablet** | Bảng vẽ cảm ứng chuyên nghiệp cho digital painting / đồ họa. | Không stream video. Tablet gửi tọa độ bút (x, y), áp lực (pressure), góc nghiêng (tilt) và nút bấm. Host inject pointer sự kiện bút. |
| **3. Trackpad & Keyboard** | Biến tablet thành bàn di chuột đa điểm và bàn phím rời kèm Numpad. | Không stream video. Tablet gửi delta con trỏ, cử chỉ vuốt 1-4 ngón và phím bấm USB HID. |

---

## 2. Mục Tiêu Hiệu Năng (SLO)

* **Chế độ 1 (Display):** Pipeline latency (glass-to-glass) ≤ 20ms ở 120fps (hoặc ≤ 35ms ở 60fps) qua cáp USB; ≤ 45ms qua Wi-Fi 5/6GHz.
* **Chế độ 2 & 3 (Input):** Input-to-cursor latency ≤ 10ms qua USB, ≤ 20ms qua Wi-Fi.
* **Băng thông:** Tối ưu CBR 15–30 Mbps cho 1080p/2K 60fps, 40–60 Mbps cho 120fps.

---

## 3. Phân Công Điều Phối (Session-Manager Protocol)

Tuân thủ skill `session-manager`:

| Vai trò | Thực thể đảm nhiệm | Trách nhiệm chính |
|---|---|---|
| **Solution Architect / Consultant** | Claude | Thiết kế kiến trúc tổng thể, đặc tả giao thức, ADR, UI/UX tokens, review chéo. |
| **Core Implementer / Engineer** | Codex (Antigravity) | Triển khai Rust core/proto/host, Kotlin Compose app, driver bridge, unit tests. |
| **Quality Inspector / QC** | Claude & QC Auditor | Thẩm định evidence, đo đạc benchmark latency, kiểm tra accessibility & contrast checklist. |
| **Project Owner / Approver** | **Anh Lâm** | Duyệt Roadmap, approve merge nhánh main, chốt release v1.0. |
