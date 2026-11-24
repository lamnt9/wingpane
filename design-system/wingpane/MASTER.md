# Wingpane Design System — MASTER (dark-mode-oled)

> Nguồn chuẩn cho mọi UI Wingpane. Trang con trong `pages/` ghi đè MASTER cho màn cụ thể.
> Quyết định: ADR-003 (dark OLED), duyệt bởi anh Lâm 2026-11-24 cùng prototype Android (pages/android-client.md).

## Màu (đo tương phản WCAG trên #000 / #0A0E27)

| Token | Hex | Tương phản | Dùng cho |
|---|---|---|---|
| bg | #000000 | — | Nền, viền letterbox video |
| surface-1 | #0A0E27 | — | Card, HUD panel |
| surface-2 | #121212 | — | Nút phụ, chip — LUÔN kèm viền 1dp `border` |
| border | #5A6380 | 3.5 / 3.2 (non-text ≥3:1) | Viền control surface-2, viền HUD pill |
| text | #F2F4FF | 19.2 / 17.3 | Chữ chính |
| text-muted | #A3AED0 | 9.5 / 8.6 | Chữ phụ, nhãn số đo |
| primary | #7AA2FF | 8.4 / 7.6 | Trạng thái active, nút chính (chữ trên nền: #000, 8.4) |
| success | #3DDC97 | 11.9 / 10.8 | Đã kết nối (chữ trên nền: #000) |
| warning | #FFB547 | 12.0 / 10.8 | Độ trễ cao (chữ trên nền: #000) |
| danger | #FF8080 | 8.7 / 7.8 | Lỗi, Disconnect (chữ trên nền: #000) |

Không bao giờ dùng màu một mình để báo trạng thái: luôn kèm icon + chữ.

## Chữ
Outfit (tiêu đề 28/22/18sp, 600) · Work Sans (thân 16sp, line-height 1.5; caption ≥13sp). Hỗ trợ font scale tới 200%.

## Khoảng cách, bo góc, chạm
Spacing 4/8/16/24/32/48 · Radius card 12, pill full · Vùng chạm ≥48×48dp, cách nhau ≥8dp · Icon Material Symbols Rounded, có contentDescription · Haptic 15ms khi chạm HUD/toolbar.

## Chuyển động
Vào 150–250ms ease-out, ra 120ms. "Remove animations" bật → chỉ fade.

## File
`tokens.json` (W3C DTCG) · `tokens.css` · `pages/android-client.md` (brief màn Android đã duyệt) · `prototype/` (ảnh prototype đã duyệt).
