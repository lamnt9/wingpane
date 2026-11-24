# 04 - Lộ Trình Triển Khai & Cổng Kiểm Soát Chất Lượng (QC Gates)

## 1. Các Giai Đoạn Triển Khai (Roadmap)

```
[Phase 0: Foundation] ➔ [Phase 1: MVP macOS] ➔ [Phase 2: 3-in-1 Features] ➔ [Phase 3: Windows] ➔ [Phase 4: Wi-Fi & Linux] ➔ [v1.0 Release]
```

### Phase 0: Spike & Foundation (Tuần 1–2)
- [x] Thiết kế cấu trúc Monorepo và Design System tokens.
- [ ] Xây dựng crate `wingpane-proto` với serde binary bằng `postcard` + Unit tests serialization.
- [ ] Thử nghiệm (Spike) `CGVirtualDisplay` trên macOS 14/15.

### Phase 1: MVP macOS Host + Android qua cáp USB (Tuần 3–6)
- [ ] Host macOS: Tạo Virtual Display ➔ ScreenCaptureKit ➔ VideoToolbox H.264 encode.
- [ ] Transport: Tích hợp `adb reverse tcp:7788 tcp:7788`.
- [ ] Android Client: MediaCodec decode ➔ SurfaceView render.
- [ ] Chạm tap-to-click cơ bản điều khiển lại Mac.
- **Tiêu chí hoàn thành (DoD):** Màn hình phụ hiển thị ổn định 60fps qua cáp USB, latency đo được ≤ 25ms.

### Phase 2: Hoàn Thiện 3 Chế Độ (Tuần 7–10)
- [ ] Tích hợp bộ bắt sự kiện S-Pen: Áp lực (pressure), góc nghiêng (tilt) và palm rejection.
- [ ] Xây dựng giao diện Chế độ 2 (Canvas vẽ) và Chế độ 3 (Trackpad đa điểm + Virtual Keyboard).
- [ ] Chế độ Bluetooth HID cho Android (sử dụng `BluetoothHidDevice`).

### Phase 3: Windows Host Support (Tuần 11–14)
- [ ] Tích hợp driver IddCx màn hình ảo cho Windows 10/11.
- [ ] Capture qua Desktop Duplication API + Hardware encode NVENC / QuickSync / AMF.
- [ ] Inject pointer qua `InjectSyntheticPointerInput`.

### Phase 4: Linux, Wi-Fi Không Dây & Tối Ưu (Tuần 15+)
- [ ] Hỗ trợ Linux (Wayland / PipeWire + uinput).
- [ ] Kết nối Wi-Fi UDP nội bộ qua QUIC protocol + Cơ chế pairing mã QR.
- [ ] Tùy chọn codec HEVC (H.265) và hỗ trợ tần số quét 120Hz.

---

## 2. Tiêu Chuẩn Thẩm Định QC (Quality Control Gate)

Trước khi đóng dấu phát hành từng phiên bản, QC Inspector bắt buộc phải kiểm duyệt:
1. **Đo đạc Latency thực tế:** Chạy đồng hồ milli-giây trên màn hình chính và màn hình tablet, chụp ảnh bằng máy ảnh tốc độ cao để tính toán chính xác Glass-to-Glass latency.
2. **Kiểm tra Touch Target & Contrast:** Đảm bảo 100% nút bấm đạt kích thước ≥ 48dp và tỷ lệ tương phản chữ đạt chuẩn AAA (≥ 7:1) theo `ui-ux-pro-max`.
3. **Stress test ngắt kết nối:** Rút cáp USB đột ngột khi đang gõ phím hoặc rê chuột — đảm bảo Host tự giải phóng (release) toàn bộ phím bị kẹt, không gây treo chuột hệ thống.
