# 07 - Kế Hoạch Triển Khai: Universal Control cho Android

Phạm vi: tính năng mới **"Universal Control for Android"** — rê chuột/trackpad thật của Mac qua một cạnh màn hình thì con trỏ *đi vào tablet Android*; lúc đó chuột + bàn phím Mac điều khiển chính hệ điều hành Android (Chrome, Zalo, Settings…), giống hai máy Mac dùng chung chuột. Rê ngược qua cạnh → trả quyền về Mac. Khác hẳn Display mode (màn ảo của Mac): ở đây đích là UI Android, không có màn ảo. Host: macOS (`wingpaned` + `macos-bridge` + `Wingpane.app`). Client: Galaxy Tab S9 (SM-X710, Android 14). Kết nối: USB `adb reverse tcp:7788`, giao thức postcard hiện có. Không gồm Wi‑Fi, Windows host, Bluetooth HID.

Ký hiệu file giữ như docs/06: `proto/` = `crates/wingpane-proto/src/`, `wpd/` = `apps/desktop/wingpaned/src/`, `bridge/` = `apps/desktop/macos-bridge/Sources/WingpaneBridge/`, `macapp/` = `apps/desktop/mac-app/Sources/`, `and/` = `apps/android/app/src/main/java/io/wingpane/`, `andres/` = `apps/android/app/src/main/res/`, `andt/` = `apps/android/app/src/test/java/io/wingpane/`.

---

## 1. Hiện trạng (đã đọc code, 2026-11-25)

### 1.1 Đã có, dùng lại được

| Khu vực | Có gì | File |
|---|---|---|
| Proto | `ControlMessage` tag 0..10 (`Hello`…`Reject`=7, `HostTime`=8, `Capabilities`=9, `PenConfig`=10); `InputEvent` tag 0..7 (`Pen`…`MouseButton`=6, `Text`=7); `Mode` tag 0..2; `RejectReason` 0..4 | `proto/control.rs`, `proto/input.rs` |
| Golden | 23 dòng trong `golden/packets.txt`, sinh bởi `golden_packets()` (`proto/lib.rs`) | `crates/wingpane-proto/golden/packets.txt` |
| Bỏ qua gói lạ | Rust `FrameDecoder` → `FrameError::UnknownPacket` khi `has_unknown_tag(body)`; daemon `continue`. Kotlin `Reader.enum(n,max)` → `UnknownPacketTag` → `skippedFrames++` | `proto/frame.rs`, `and/proto/Protocol.kt` |
| Capabilities | Bit 0..7 đã dùng (`PEN_PRESSURE`…`VIDEO_SUSPEND`). Client gửi `(1<<1)|(1<<2)|(1<<3)|(1<<7)` sau Accept; host trả `HOST_INPUT_FEATURES | INPUT_TRUSTED` và gửi lại khi Accessibility đổi | `and/net/WingpaneClient.kt:255`, `wpd/main.rs:210-223, 1207-1244` |
| Mode/video | `display_transition(prev,next,suspend)` gỡ/tạo lại màn ảo khi `mode_has_display` đổi; writer bỏ frame khi `!should_send_video` | `wpd/main.rs:177-227, 1128-1199` |
| Router | `InputRouter { mode, sink }`, `set_mode` gọi `release_all` trước; `SessionResources::drop` = `release_all → capture.stop → display.destroy` | `wpd/lib.rs` |
| Keymap | `keymap_macos::mac_keycode(hid) -> kVK` (bảng HID 0x07 → macOS) — đảo ngược được cho UC | `wpd/keymap_macos.rs` |
| Bridge | `@_cdecl` FFI, post CGEvent `.cghidEventTap`, `AXIsProcessTrusted()` | `bridge/Bridge.swift`, `bridge/Input.swift` |
| Mac app | `MenuBarExtra`, spawn daemon `--status-json`, đọc stdout JSON Lines; `DaemonEvent.unknown` bỏ qua event lạ | `macapp/Wingpane/WingpaneApp.swift`, `macapp/WingpaneAppCore/*` |
| Android | `WingpaneClient` nhận cả `Packet.Input` từ host (đẩy vào `otherIncoming`, chưa ai tiêu thụ); HUD có hàng Mode 3 segment | `and/net/WingpaneClient.kt`, `and/ui/hud/FloatingHud.kt:741` |

### 1.2 Chưa có / cản trở (phải xử lý trong plan này)

1. **`WingpaneClient` thuộc về `MainActivity`** (`client = WingpaneClient()` trong `onCreate`, `client.cancel()` trong `onDestroy`). UC đòi kết nối sống khi Activity ở nền (người dùng đang ở Chrome/Zalo) → phải chuyển chủ sở hữu ra `Application` + foreground service (WP-U2).
2. **Không có kênh lệnh app → daemon**: `DaemonController.start()` không gán `standardInput`; daemon chỉ có stdout. Menu Mac bật/tắt UC, đổi cạnh mà **không restart daemon** (MA-8 hiện restart khi đổi Options) → cần stdin JSON Lines (WP-U5/U7).
3. **Main thread daemon chạy tokio, không có CFRunLoop** (`wp_test_pattern_pump` ghi rõ). `CGEventTap` cần run loop → bridge phải tự tạo thread riêng chạy `CFRunLoopRun()` (WP-U4).
4. **`has_unknown_tag` Rust hard-code** `tag > 9` (control) và `tag > 7` (input); không nhìn enum lồng nhau. Hệ quả: host **cũ** nhận `SetMode(Mode tag 3)` → `FrameError::Decode` → **rớt phiên** (không phải skip). Kotlin thì skip được (`Mode.entries[r.enum(0,2)]` ném `UnknownPacketTag`). → Biến thể Mode mới **chỉ được gửi khi peer có bit 8** (D-3), và WP-U0 cập nhật hằng số.
5. Kotlin `decodeControl` dùng `else -> PenConfig` cho tag 10, `decodeInput` dùng `else -> Text` cho tag 7 → thêm tag mới phải đổi `else` thành nhánh tường minh.
6. `macos-bridge` **không có test target** (chỉ `mac-app` có `WingpaneAppCoreTests`). Logic thuần phải đặt ở Rust/`WingpaneAppCore` hoặc thêm test target mới (WP-U4).
7. Android không có `AccessibilityService`, không có foreground service, không có quyền `FOREGROUND_SERVICE*` trong `AndroidManifest.xml`.

---

## 2. Quyết định thiết kế

* **D-1 UC là chế độ thứ tư `Mode::AndroidControl`, ngang hàng Display/Pen/Trackpad.** Lý do: tái dùng nguyên cơ chế `SetMode` + `display_transition` + `VIDEO_SUSPEND` đã chạy (WP-10 docs/06): vào AndroidControl → `mode_has_display = false` → host gỡ màn ảo, dừng capture, video = 0. Không còn màn ảo kề cạnh màn chính nên **cạnh UC không bị màn ảo chiếm**; tablet không cần hiển thị gì của Mac. Trade-off: không xem được màn ảo Mac và điều khiển Android cùng lúc — chấp nhận (tablet chỉ có một màn). Đổi mode khác → UC tắt, màn ảo tạo lại như hiện nay.
* **D-2 Host là nguồn sự thật về mode khi UC bật từ menu Mac.** `SetMode` thành **hai chiều**: client→host (HUD) như cũ; host→client khi người dùng bật/tắt UC ở menu Mac. Bên nhận áp dụng và **không gửi lại** (không echo). Nếu hai bên đổi đồng thời: host thắng (host gửi `SetMode` cuối cùng sau khi áp).
* **D-3 Gating bằng `Capabilities` bit 8 `UNIVERSAL_CONTROL`.** Không bên nào gửi `Mode::AndroidControl`, `Uc*`, `PreciseScroll` hay `Input` chiều host→client nếu peer chưa báo bit 8 (bắt buộc vì mục 1.2-4). `Accept.selected_mode` không bao giờ là `AndroidControl`.
* **D-4 Đầu vào host→client tái dùng `InputEvent` có sẵn** (`PointerDelta`, `MouseButton`, `Keyboard`) — `Packet::Input` vốn đối xứng. Chỉ thêm `PreciseScroll` (cần phase để giữ "ngón ảo" khi cuộn trackpad). Đơn vị host→client: `PointerDelta` = **point macOS sau gia tốc của macOS** (`kCGMouseEventDeltaX/Y`); `Keyboard.modifiers` = HID boot (Cmd = GUI bit 3/7).
* **D-5 Vị trí con trỏ trong Android do Android sở hữu.** Mac chỉ gửi delta; Android cộng dồn, kẹp trong màn, vẽ con trỏ, và **tự phát hiện rời cạnh** rồi gửi `UcExit{along}`. Lý do: Android biết kích thước/xoay màn của nó; host không cần biết. Gia tốc: macOS đã áp gia tốc của người dùng → Android chỉ nhân `gain` (mặc định 1 point = 1 dp, chỉnh 0.5–3.0 trong HUD). Không chồng thêm đường cong gia tốc thứ hai (cảm giác sẽ khác Mac).
* **D-6 Bắt input Mac bằng `CGEventTap` active ở `kCGHIDEventTap`, đặt trong daemon** (process đã có Accessibility và đang inject). Tap chạy trên thread riêng có `CFRunLoop`. Khi "đang ở Android": callback trả `nil` (nuốt) mọi mouse/key/scroll/flagsChanged, chuyển sang Rust qua C callback; con trỏ Mac bị **warp về giữa màn chính**, `CGAssociateMouseAndMouseCursorPosition(false)`, ẩn bằng `CGDisplayHideCursor`. Tham khảo cách làm của Barrier/Deskflow trên macOS (warp-center + delta) — *chưa đối chiếu mã nguồn của họ*.
* **D-7 Ẩn con trỏ khi daemon không phải app foreground:** `CGDisplayHideCursor` từ process nền thường **không có tác dụng**; dùng private `CGSSetConnectionProperty(_CGSDefaultConnection(), conn, "SetsCursorInBackground", kCFBooleanTrue)` nạp qua `dlsym` (không link cứng). Không có symbol → fallback: không ẩn, con trỏ đứng yên giữa màn. Dự án đã dùng private API (`CGVirtualDisplay`) nên chấp nhận. **Chưa kiểm trên macOS hiện tại — spike ở WP-U4.**
* **D-8 Phát hiện cạnh ở Rust (thuần, test được), nuốt sự kiện ở Swift (đồng bộ).** Khi chưa vào Android, Swift chuyển *mọi* `mouseMoved/Dragged` (location + delta) sang Rust rồi cho qua; Rust `EdgeDetector` quyết định vào → gọi `wp_uc_set_inside(true)`; Swift xác nhận bằng event `Entered`. Trạng thái "inside" chỉ có **một chủ** là Swift (`UcTap.inside`, atomic); Rust chỉ phản chiếu theo event `Entered/Exited(reason)`.
* **D-9 Luật vào cạnh:** chỉ cạnh đã chọn của **màn chính** (`CGMainDisplayID()`), chỉ đoạn cạnh **không có màn khác kề** (kiểm bằng hình chữ nhật các display), phải "đẩy xuyên" ≥ 24 pt trong 300 ms (cạnh trên/dưới: ≥ 64 pt vì menu bar/Dock), **không** vào khi đang giữ nút chuột (đang kéo cửa sổ). `along` = vị trí dọc cạnh chuẩn hoá 0..1.
* **D-10 Thoát luôn an toàn — 5 lớp:**
  1. Hotkey **Ctrl+⌥+Esc** xử lý ngay trong callback Swift (không phụ thuộc Rust), không bao giờ chuyển sang Android.
  2. Watchdog Swift: Rust gọi `wp_uc_heartbeat()` mỗi 250 ms; im > 1 s khi đang inside → tự thoát (`Watchdog`).
  3. `kCGEventTapDisabledByTimeout/ByUserInput` → bật lại tap, nếu đang inside → thoát (`TapDisabled`).
  4. Rust: `SessionResources::drop` gọi `uc.release()` **đầu tiên** (trước `release_all`); panic hook + SIGINT/SIGTERM path gọi `wp_uc_release()`. Rớt cáp/Pong timeout/`Disconnect` đều đi qua drop.
  5. `Wingpane.app`: `terminationHandler` của daemon gọi `CGAssociateMouseAndMouseCursorPosition(1)` + warp về giữa màn; nếu status `uc` = `inside` mà không có dòng stdout nào trong 3 s → `SIGKILL` daemon (WindowServer dọn ẩn-con-trỏ theo connection khi process chết — *chưa kiểm cho `CGAssociateMouseAndMouseCursorPosition`*, nên app tự gọi lại).
* **D-11 Secure Input macOS:** khi một app bật `EnableSecureEventInput` (ô mật khẩu, Terminal "Secure Keyboard Entry", 1Password), event tap **không nhận phím** → phím sẽ rơi vào app Mac trong khi người dùng tưởng đang gõ Android. Quyết định: `IsSecureEventInputEnabled()` = true → **không cho vào** (status `uc` reason `secure_input`); đang inside mà bật lên → thoát (`SecureInput`). Bảo thủ nhưng không bao giờ lộ phím vào ô mật khẩu Mac.
* **D-12 Inject phía Android chỉ qua `AccessibilityService`** (`WingpaneAccessibilityService`), không root, không `adb shell input`:
  - Tap/drag/scroll/long-press: `dispatchGesture` + `GestureDescription.StrokeDescription(path, start, dur, willContinue)` (API 26) và `continueStroke` cho kéo liên tục.
  - Back/Home/Recents/Notifications/Screenshot/Lock: `performGlobalAction`.
  - Text: **API 33+** `AccessibilityService.InputMethod` (`flagInputMethodEditor`) → `AccessibilityInputConnection.commitText / deleteSurroundingText / sendKeyEvent / performContextMenuAction / performEditorAction`. API < 33 hoặc không có connection: fallback `ACTION_SET_TEXT` + `ACTION_SET_SELECTION` trên node `findFocus(FOCUS_INPUT)`.
  - Ẩn bàn phím ảo Samsung khi đang điều khiển: `softKeyboardController.showMode = SHOW_MODE_HIDDEN`, trả `SHOW_MODE_AUTO` khi thoát/mất kết nối/service unbind.
* **D-13 Giới hạn chấp nhận của `dispatchGesture` (ghi vào docs, hiện trong HUD "Giới hạn"):**
  - Mọi thao tác là **chạm (SOURCE_TOUCHSCREEN), không phải chuột**: không hover, không tooltip; kéo chuột trong Chrome = **cuộn trang**, không bôi đen text (bôi đen: long-press rồi kéo handle). Chuột phải = long-press 600 ms.
  - Một `dispatchGesture` mới **huỷ** gesture đang chạy → mọi dispatch phải **xếp hàng** (`GestureQueue`, dispatch kế tiếp chỉ sau `onCompleted/onCancelled`).
  - Kéo liên tục bằng `continueStroke`: mỗi đoạn phải bắt đầu đúng điểm kết thúc đoạn trước và chỉ gửi sau khi đoạn trước xong → trễ thêm ≈ độ dài đoạn (chọn 16 ms); lỡ nhịp = hệ thống nhấc ngón (drag đứt). Không có multi-touch kéo liên tục thật (pinch chỉ là gesture một lần, stretch WP-U8).
  - Người dùng chạm tay thật vào màn trong lúc gesture chạy → gesture bị huỷ.
  - App có `FLAG_SECURE`: inject vẫn chạy (*chưa kiểm*), chỉ không chụp được. App bật `filterTouchesWhenObscured` (nhiều app ngân hàng VN) sẽ bỏ chạm nếu cửa sổ khác che **đúng điểm chạm** → con trỏ là cửa sổ nhỏ đặt **lệch 1 px khỏi hotspot** (D-14) để chỉ là "partially obscured". *Chưa kiểm với app ngân hàng thật.*
  - Nhiều app ngân hàng VN **từ chối chạy khi có Accessibility service không rõ nguồn đang bật** → hướng dẫn tắt service khi dùng app đó; HUD có nút mở trang Accessibility. Không lách.
  - Màn khoá (keyguard): không hỗ trợ; client báo `KEYGUARD_LOCKED` → host không cho vào/thoát ra.
  - Phím không có ô nhập đang focus (vd Space để pause YouTube) **không** inject được (a11y không có API key toàn cục) — chỉ có bảng phím tắt toàn cục (D-16).
  - Google Play Protect/Play policy: dùng Accessibility API cho mục đích không phải trợ năng có thể bị cảnh báo nếu phát hành Play; hiện chỉ sideload (`adb install`). Android 13+ "Restricted settings" chặn bật a11y cho APK cài từ trình duyệt/file — *cài qua `adb install` được cho là không bị chặn, chưa kiểm trên Tab S9*.
* **D-14 Con trỏ Android = cửa sổ `TYPE_ACCESSIBILITY_OVERLAY` nhỏ (24×24 dp)** tạo từ context của service (không cần `SYSTEM_ALERT_WINDOW`), cờ `FLAG_NOT_TOUCHABLE|FLAG_NOT_FOCUSABLE|FLAG_LAYOUT_IN_SCREEN|FLAG_LAYOUT_NO_LIMITS`, `layoutInDisplayCutoutMode = ALWAYS`. Vị trí cập nhật bằng `updateViewLayout` **gộp theo vsync** (`Choreographer`, tối đa 1 lần/khung, Tab S9 120 Hz). Đỉnh mũi tên (hotspot) nằm **ngoài** cửa sổ 1 px (cửa sổ đặt tại `(x+1, y+1)`, vẽ mũi tên từ góc). Chọn cửa sổ nhỏ thay vì overlay toàn màn để không làm cờ `FLAG_WINDOW_IS_OBSCURED` bật trên mọi app.
* **D-15 Tiếng Việt: gửi phím thô, gõ Telex ở phía Android (`TelexComposer` thuần Kotlin).** Phương án bị loại:
  - *Cầu IME Mac (NSTextField/NSTextInputClient ẩn)*: phải kích hoạt `Wingpane.app` làm app key mỗi lần vào Android (cướp focus của app Mac đang dùng), daemon CLI không có NSApp run loop, và EVKey/OpenKey (phổ biến ở VN) **không phải** IME TSM mà là event tap tự thay ký tự — thứ tự giữa tap của họ và của ta không đảm bảo → gõ đúp/sai.
  - *Để IME Android (Samsung Keyboard/Gboard) ghép*: `AccessibilityInputConnection.sendKeyEvent` đi thẳng vào editor, **không qua IME** → không ghép Telex.
  - Chọn: Mac gửi HID thô; tap ở `kCGHIDEventTap` (trước session tap của EVKey/OpenKey — *chưa kiểm*) và **bỏ event tổng hợp** (`eventSourceStateID != kCGEventSourceStateHIDSystemState`) khi inside để không nhận ký tự EVKey chèn. Android có `TelexComposer` (quy tắc Telex chuẩn, kiểu bỏ dấu mới "hoà" mặc định, tuỳ chọn kiểu cũ "hòa"), ghép bằng `deleteSurroundingText(n,0) + commitText(suffix)` theo phần khác nhau với lần render trước (`AccessibilityInputConnection` **không có** `setComposingText`). Bật/tắt VI/EN bằng **Ctrl+Space** (đúng thói quen đổi nguồn nhập trên Mac; đang inside nên bị nuốt, không đổi nguồn của Mac). Trạng thái ban đầu theo gợi ý host: `UcEnter.ime_hint` lấy từ `TISCopyCurrentKeyboardInputSource` (id chứa `Vietnamese` → VI); EVKey/OpenKey không phải input source → `unknown` → giữ lựa chọn lần trước của người dùng. Badge "VI/EN" hiện cạnh con trỏ 1.5 s khi đổi.
  - Layout bàn phím: v1 chỉ **US ANSI** (HID → ký tự theo bảng US, như layout "ABC" của Mac). Layout khác (AZERTY…) ghi giới hạn.
* **D-16 Phím tắt toàn cục (mọi lúc, kể cả có editor):** `Esc` → BACK; `⌘H` → HOME; `⌘Tab` và `Ctrl+↑` → RECENTS; `Ctrl+↓` → NOTIFICATIONS; `⌘⇧3` → TAKE_SCREENSHOT (API 28); `⌘Ctrl+Q` → LOCK_SCREEN (API 28). Có editor: `⌘C/X/V/A` → `performContextMenuAction(android.R.id.copy/cut/paste/selectAll)`; `⌘Z` → `sendKeyEvent(KEYCODE_Z, META_CTRL_ON)`; `⌘←/→` → `MOVE_HOME/MOVE_END`; `⌥⌫` → xoá từ. *Chưa kiểm `⌘Tab`, `⌘Space` có tới được event tap không* (WindowServer/Dock có thể xử lý trước) — nếu không tới thì Mac vẫn mở app switcher/Spotlight; ghi giới hạn, không lách bằng private API tắt symbolic hotkey.
* **D-17 Cuộn:** trackpad Mac có phase → `PreciseScroll{phase}`: `Began` đặt "ngón ảo" tại con trỏ (stroke `willContinue`), `Changed` kéo theo delta, `Ended` giữ yên 32 ms rồi nhấc (không fling, vì đã theo tay người dùng); bỏ qua `Momentum` của Mac (để không cuộn đôi). Chuột bánh xe (`Discrete`) → gộp 40 ms, mỗi lần một swipe ≥ 2× touch slop, kết thúc bằng giữ 16 ms để fling nhỏ. Dấu hướng: finger = theo hướng nội dung mà macOS đã quy đổi theo "natural scrolling" — **kiểm trên máy (U9)**, có công tắc "Đảo hướng cuộn" trong HUD.
* **D-18 Click:** `MouseButton down` không dispatch ngay; `up` ≤ 250 ms và di < 6 dp → tap 40 ms tại điểm down; giữ > 250 ms hoặc di ≥ 6 dp → bắt đầu drag (`continueStroke`, đoạn 16 ms); `up` → đoạn cuối `willContinue=false`. Nút phải (1) → long-press `ViewConfiguration.getLongPressTimeout() + 100` ms. Nút giữa (2) → bỏ. Double-click = hai tap xếp hàng (khoảng cách thật, < `getDoubleTapTimeout()`).
* **D-19 Kết nối nền:** `WingpaneClient` chuyển sang `WingpaneApplication` (`ClientHolder`, một instance/process). `UcForegroundService` (`foregroundServiceType="connectedDevice"`) chạy suốt phiên khi client đã quảng bá bit 8 **và** a11y service đang kết nối; khởi động lúc `Active` (Activity đang foreground → không vướng hạn chế start FGS từ nền của Android 12+). Notification: "Wingpane — Mac đang điều khiển tablet" + action "Về Display" / "Trả chuột cho Mac". Android 14 yêu cầu `FOREGROUND_SERVICE_CONNECTED_DEVICE` + một quyền điều kiện → dùng `CHANGE_NETWORK_STATE` (normal). *Chưa kiểm loopback có bị Doze/standby chặn khi không có FGS; FGS làm cho câu hỏi này không còn quan trọng.*
* **D-20 Cấu hình cạnh chỉ ở Mac** (vì là vị trí vật lý của tablet so với Mac): menu `Control Android ▸ Edge ▸ Left/Right/Top/Bottom`, lưu `UserDefaults`, truyền daemon `--uc-edge right` lúc spawn và lệnh stdin lúc chạy (đổi cạnh **không restart** daemon — ngoại lệ của MA-8). Android nhận `UcConfig{edge}` để HUD hiển thị và để biết cạnh thoát = **cạnh đối diện** (Mac phải → tablet nằm bên phải → con trỏ vào từ cạnh trái tablet, thoát qua cạnh trái).
* **D-21 Bật/tắt ở cả hai nơi:** menu Mac `Control Android ✓` (host gửi `SetMode(AndroidControl)`/`SetMode(<mode trước>)`), HUD Android segment thứ tư **"Android"** (client gửi `SetMode`). Vào AndroidControl → Activity `moveTaskToBack(true)` (người dùng thấy Android); mở lại app khi đang AndroidControl → màn `AndroidControlScreen` (trạng thái + nút "Về Display").
* **D-22 Clipboard là stretch (WP-U9):** Mac→Android lúc vào (đọc `NSPasteboard.general` trong daemon, `setPrimaryClip` từ service — ghi clipboard từ nền được phép). Android→Mac: Android 10+ **cấm đọc clipboard từ nền** (trừ IME mặc định/app foreground) → chỉ làm qua action notification mở Activity trong suốt đọc clipboard khi có focus. Không hứa đồng bộ tự động hai chiều.

### 2.1 Thay đổi protocol (append-only, `PROTO_VERSION` giữ = 1)

| Enum | Tag mới | Biến thể | Chiều | WP |
|---|---|---|---|---|
| `Mode` | 3 | `AndroidControl` | cả hai (trong `SetMode`) | U0 |
| `ControlMessage` | 11 | `UcConfig { edge: UcEdge }` | host→client (sau Capabilities, và khi đổi cạnh) | U0 |
| `ControlMessage` | 12 | `UcEnter { edge: UcEdge, along: f32, ime_hint: u8 }` | host→client | U0 |
| `ControlMessage` | 13 | `UcExit { along: f32, reason: UcExitReason }` | cả hai | U0 |
| `ControlMessage` | 14 | `UcStatus { flags: u32 }` | client→host (khi đổi, và ngay sau Capabilities của host) | U0 |
| `ControlMessage` | 15 | `Clipboard { text: String }` (UTF‑8 ≤ 64 KiB) | cả hai | U9 |
| `InputEvent` | 8 | `PreciseScroll { dx: f32, dy: f32, phase: ScrollPhase }` | host→client | U0 |
| `UcEdge` (mới) | 0..3 | `Left, Right, Top, Bottom` (cạnh **của màn chính Mac**) | | U0 |
| `UcExitReason` (mới) | 0..8 | `CursorReturned, Hotkey, ModeChanged, ClientNotReady, TapDisabled, Watchdog, SecureInput, MacLocked, SessionEnded` | | U0 |
| `ScrollPhase` (mới) | 0..4 | `Discrete, Began, Changed, Ended, Momentum` | | U0 |

* `along`: 0..1 dọc cạnh (Left/Right: theo y từ trên xuống; Top/Bottom: theo x từ trái sang). `ime_hint`: 0 unknown, 1 latin, 2 vietnamese.
* `UcStatus.flags` (bit 0 = LSB): `0 SERVICE_CONNECTED`, `1 GESTURES_OK` (đã có `canPerformGestures`), `2 IME_CONNECTION` (API ≥ 33 và `getInputMethod() != null`), `3 KEYGUARD_LOCKED`, `4 FGS_RUNNING`, `5 OVERLAY_SHOWN`. Host coi client **ready** = `0 && 1 && 4 && !3`.
* **Capabilities:** bit `8 UNIVERSAL_CONTROL` (hiểu `Mode::AndroidControl`, tag control 11–14, input tag 8, và `Input` chiều host→client). Host chỉ bật bit 8 khi `wp_uc_available()` (tạo thử được event tap). Bit `9 CLIPBOARD_TEXT` (U9). Bit 10..31 dự trữ, bỏ qua.
* **Hằng số tag lạ** (mục 1.2-4): `has_unknown_tag` đổi thành `tag > LAST_CONTROL_TAG` (15 sau U9; 14 ở U0) và `tag > LAST_INPUT_TAG` (8); Kotlin `decodeControl` `r.enum(0,14)` (U9: 15), `decodeInput` `r.enum(0,8)`, `mode(r)` `r.enum(0,3)`; nhánh `else` đổi thành tag tường minh.
* **Golden mới** (thêm vào `golden_packets()`, sinh bằng `cargo run -p wingpane-proto --example golden`, thêm entry Kotlin `ProtocolTest.expected()`): `control_set_mode_android_control`, `control_uc_config` (`Right`), `control_uc_enter` (`Right, 0.375, 2`), `control_uc_exit` (`0.625, Hotkey`), `control_uc_status` (`0x13`), `input_precise_scroll` (`-3.5, 12.25, Changed`); U9: `control_clipboard` (`"Tiếng Việt 😀"`).

### 2.2 Trạng thái UC (host, `wpd/uc.rs`)

```
Unavailable ──(tap OK + client bit 8)──► Disabled ──(mode = AndroidControl && client ready)──► Armed
   ▲                                        ▲  ◄──(mode khác / client not ready)──────────────┘ │ ▲
   └──(tap mất quyền)                       │                                                    │ │ Exited(reason)
                                            └──────────── (SessionEnded: release) ◄── Inside ◄──┘ │ EdgeDetector → set_inside(true) → Entered
```

* `Armed → Inside`: chỉ khi Swift gửi `Entered` (sau khi đã warp/ẩn/decouple); lúc đó host gửi `UcEnter`.
* `Inside → Armed`: Swift gửi `Exited(reason)` (hotkey/watchdog/tap disabled/secure input) → host gửi `UcExit{reason}`; hoặc client gửi `UcExit{CursorReturned, along}` → host gọi `wp_uc_set_inside(false, along)` → Swift warp con trỏ tới cạnh tại `along` (lùi vào 2 pt), hiện, associate → `Exited(CursorReturned)` (host không gửi lại `UcExit` cho lý do do client khởi xướng).
* Mọi `Inside → *` phía Android: huỷ gesture đang chạy, nhấc stroke, xoá modifier, commit/xoá buffer Telex, ẩn con trỏ, trả `SHOW_MODE_AUTO`.

---

## 3. Work packages

Thứ tự đảm bảo mỗi WP **build độc lập và test được**; hai spike rủi ro nhất (U1 Android, U4 macOS) không phụ thuộc protocol và có driver debug riêng.

### WP-U0 — Protocol: Mode mới, Uc*, PreciseScroll, bit 8
* **Mục tiêu:** khoá wire contract hai phía, chưa có hành vi.
* **File:** `proto/control.rs` (`Mode::AndroidControl`, `UcEdge`, `UcExitReason`, `ControlMessage::{UcConfig, UcEnter, UcExit, UcStatus}`), `proto/input.rs` (`ScrollPhase`, `InputEvent::PreciseScroll`), `proto/lib.rs` (re-export, `golden_packets()`, `packet_variants_roundtrip`, hằng `pub const FEATURE_UNIVERSAL_CONTROL: u32 = 1 << 8;` và `UC_STATUS_*`), `proto/frame.rs` (`has_unknown_tag`: hằng `LAST_CONTROL_TAG = 14`, `LAST_INPUT_TAG = 8`), `crates/wingpane-proto/golden/packets.txt` (sinh lại), `wpd/main.rs` (`match next_mode` thêm `Mode::AndroidControl => 3`; writer `3 => Mode::AndroidControl`; `mode_has_display` trả `false` cho AndroidControl; **chưa** quảng bá bit 8), `wpd/lib.rs` (router: AndroidControl bỏ mọi input client→host — đã đúng nhờ nhánh `_`, thêm test), `and/proto/Protocol.kt` (enum/sealed mới, encode/decode, max tag), `andt/proto/ProtocolTest.kt`, `and/ui/hud/FloatingHud.kt` + `and/MainActivity.kt` (chỉ để `when` exhaustive biên dịch; segment Android **chưa hiện**), `docs/02-protocol.md` (§8 Universal Control: bảng §2.1, quy tắc D-3, đơn vị D-4).
* **Protocol:** như §2.1. `UcStatus.flags`, `features` là `u32` varint.
* **macOS / Android:** không hành vi mới.
* **Chấp nhận:** golden khớp hai phía; host nhận `UcStatus` từ client mới → bỏ qua không rớt phiên; Kotlin nhận `UcEnter` → decode được (chưa xử lý).
* **Test:** Rust: roundtrip mọi biến thể mới; `golden_vectors_are_current`; test mới `unknown_tag_constants_cover_all_known_variants` (encode biến thể cuối của mỗi enum → `has_unknown_tag == false`; tag cuối+1 → `true`); `display_transition(ExtendedDisplay, AndroidControl, true) == Remove`, `(AndroidControl, ExtendedDisplay, true) == Create`; `should_send_video(AndroidControl, …) == false`; router AndroidControl trả `false` cho Touch/Pen/PointerDelta/Keyboard. Kotlin: `ProtocolTest` 6 golden mới; "control tag 15 is skipped"; "input tag 9 is skipped"; "SetMode with mode tag 4 is skipped". Thủ công: R1 (stream 5 phút không đổi).
* **Phụ thuộc:** không. **Rủi ro:** quên đổi `else` nhánh Kotlin → tag 11 bị decode thành `PenConfig` sai → test "each control tag decodes to its own type" bắt.

### WP-U1 — Spike Android: AccessibilityService + gesture + con trỏ (không cần Mac)
* **Mục tiêu:** chứng minh/đo phần rủi ro nhất trên Tab S9 trước khi nối mạng: tap, drag liên tục, long-press, cuộn, global action, overlay con trỏ.
* **File:** `and/uc/WingpaneAccessibilityService.kt` (mới: `onServiceConnected` tạo overlay, `GestureQueue`, expose `UcInjector` qua `ServiceRegistry` static weak ref), `and/uc/GestureQueue.kt` (mới: hàng đợi tuần tự, callback `onCompleted/onCancelled`, timeout 2 s → bỏ và chạy tiếp), `and/uc/PointerGesturePlanner.kt` (mới, **thuần Kotlin**: nhận `CursorEvent` (move/button/scroll + `nowMs`) → `List<GesturePlan>` với `Tap(x,y,durMs)`, `LongPress`, `StrokeStart/StrokeSegment/StrokeEnd(id, from, to, durMs, willContinue)`; D-17/D-18), `and/uc/CursorModel.kt` (mới, thuần: `gain`, kẹp `[0,W-1]×[0,H-1]`, `entryEdge`, phát hiện rời cạnh với ngưỡng đẩy 24 dp/300 ms, `along` ↔ toạ độ, xử lý xoay bằng W/H hiện tại), `and/uc/CursorOverlay.kt` (mới: View 24 dp vẽ mũi tên trắng viền đen, D-14, `Choreographer` gộp vsync), `and/uc/GestureAdapter.kt` (mới: `GesturePlan` → `GestureDescription`, giữ `StrokeDescription` trước để `continueStroke`), `and/uc/UcDebugReceiver.kt` (mới, **chỉ debug build** qua `src/debug/AndroidManifest.xml`: broadcast `io.wingpane.debug.UC` với extra `dx,dy,btn,down,scroll,text,global` để lái bằng adb khi test — không phải đường runtime), `andres/xml/wingpane_accessibility.xml` (mới), `andres/values/strings.xml` + `values-vi/strings.xml` (mô tả service, cảnh báo ngân hàng), `apps/android/app/src/main/AndroidManifest.xml` (khai báo service `BIND_ACCESSIBILITY_SERVICE`), `apps/android/app/src/debug/AndroidManifest.xml` (mới, receiver debug), `and/uc/UcLatencyProbe.kt` (debug: ghi `SystemClock.uptimeMillis()` khi dispatch và khi `onCompleted`; activity test `UcProbeActivity` (debug) log `MotionEvent.eventTime` của ACTION_DOWN để đo trễ thật).
* **Config a11y:** `accessibilityEventTypes="typeWindowStateChanged|typeWindowsChanged|typeViewFocused"`, `accessibilityFeedbackType="feedbackGeneric"`, `canPerformGestures="true"`, `canRetrieveWindowContent="true"`, `accessibilityFlags="flagDefault|flagRetrieveInteractiveWindows|flagInputMethodEditor"`, `notificationTimeout="0"`, `isAccessibilityTool="false"`.
* **Protocol / macOS:** không.
* **Android chi tiết:** drag = `StrokeDescription(path, 0, 16, willContinue=true)` rồi `stroke.continueStroke(path', 0, 16, true)` mỗi khi `onCompleted` của đoạn trước; nếu không có delta mới trong 16 ms → gửi đoạn "giữ yên" (path 1 điểm, 16 ms) để không nhấc ngón; nút nhả → đoạn cuối `willContinue=false`. Global: `performGlobalAction(GLOBAL_ACTION_BACK/HOME/RECENTS/NOTIFICATIONS/TAKE_SCREENSHOT/LOCK_SCREEN)`.
* **Chấp nhận (đo trên SM-X710, ghi vào `docs/07` §5 kết quả spike):** tap trong Settings/Chrome/Zalo trúng đích 20/20; dispatch→ACTION_DOWN p50 ≤ 30 ms, p95 ≤ 60 ms (*mục tiêu, chưa biết thực tế*); drag 3 s trong Samsung Notes (bút/ngón vẽ) liền một nét ≥ 95% lần thử; long-press mở context menu Chrome; con trỏ theo broadcast không giật ở 120 Hz; con trỏ hiện trên app `FLAG_SECURE` (vd app ngân hàng/màn Netflix) và **ghi lại** tap có ăn trên app đó không; ghi lại Samsung có tự tắt service sau khi cài lại APK không.
* **Test:** Kotlin `PointerGesturePlannerTest` (click ngắn → 1 Tap; giữ 300 ms → StrokeStart…; di 6 dp khi giữ → drag; `cancelAll()` luôn sinh `StrokeEnd` nếu đang drag; double-click → 2 Tap; nút phải → LongPress ≥ 500 ms; nút giữa → rỗng), `CursorModelTest` (gain, kẹp, rời cạnh trái chỉ khi đẩy ≥ 24 dp trong 300 ms, không rời khi chạm cạnh khác, `along` khứ hồi, đổi W/H khi xoay giữ tỉ lệ), `GestureQueueTest` (fake dispatcher: không dispatch chồng, timeout giải phóng hàng). Thủ công U1–U4.
* **Phụ thuộc:** không. **Rủi ro:** `continueStroke` chập chờn trên One UI → nếu drag không đạt, hạ yêu cầu: drag = một stroke duy nhất phát khi nhả nút (vẽ lại đường đã đi, trễ = thời gian kéo) và ghi giới hạn.

### WP-U2 — Android: client sống ngoài Activity + foreground service
* **Mục tiêu:** kết nối không chết khi Wingpane ở nền; **không đổi hành vi stream**.
* **File:** `and/WingpaneApplication.kt` (mới: `ClientHolder.client` lazy, scope ứng dụng), `apps/android/app/src/main/AndroidManifest.xml` (`android:name=".WingpaneApplication"`, quyền `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `CHANGE_NETWORK_STATE`, `POST_NOTIFICATIONS`; `<service android:name=".uc.UcForegroundService" android:foregroundServiceType="connectedDevice" android:exported="false"/>`), `and/uc/UcForegroundService.kt` (mới: notification channel `uc`, action "Về Display" / "Trả chuột cho Mac" qua `PendingIntent.getActivity`/`getService`, `stopSelf` khi client `Disconnected/Lost`), `and/MainActivity.kt` (lấy client từ `ClientHolder`; `onDestroy` **không** `client.cancel()` nếu FGS đang chạy — chỉ `decoder.close()`; `onCreate` không `connect()` lại nếu đã Active; collector video giữ trong Activity), `and/net/WingpaneClient.kt` (không đổi logic; thêm `fun isActive()` tiện dụng).
* **Protocol / macOS:** không.
* **Android:** FGS chỉ bật khi (a) client Active và (b) `ServiceRegistry.service != null` (người dùng đã bật a11y) — chưa liên quan bit 8 vì U0 chưa quảng bá; bật thử bằng cờ debug `uc_force_fgs`. Xoay màn/recreate Activity không tạo client thứ hai.
* **Chấp nhận:** bật FGS, bấm Home, 10 phút sau mở lại app → phiên vẫn Active (host log không có `client disconnected`), video về ≤ 300 ms sau `RequestKeyframe`; không FGS → hành vi như cũ; xoay màn 10 lần không rò client (log 1 lần `client connected`).
* **Test:** Kotlin `ClientHolderTest` (cùng instance qua 2 lần lấy); `WingpaneClientTest` hiện có xanh nguyên. Thủ công U5, R1.
* **Phụ thuộc:** không (chạy song song U1). **Rủi ro:** chạm đường stream → commit riêng, nếu R1 tệ đi thì revert riêng commit này.

### WP-U3 — Android: bàn phím, phím tắt, Telex
* **Mục tiêu:** nhận `Keyboard` (HID) → ký tự/phím/global action vào app Android, gõ tiếng Việt Telex.
* **File:** `and/uc/HidKeymap.kt` (mới, thuần: HID 0x07 → `KeyEvent.KEYCODE_*`; HID+shift → ký tự US ANSI; modifier HID bit → `META_*`), `and/uc/ShortcutMap.kt` (mới, thuần: bảng D-16 → `GlobalAction | ContextAction | KeyAction | null`), `and/uc/TelexComposer.kt` (mới, thuần: `onChar(c): Edit`, `onBackspace(): Edit?`, `commit()`, `reset()`, `style: New|Old`, `Edit(deleteBefore: Int, insert: String)`), `and/uc/KeyRouter.kt` (mới, thuần: nhận HID down/up + trạng thái `editorFocused`, `viMode` → `List<KeyAction>`: `Commit(Edit)`, `SendKey(keycode, meta)`, `Global(action)`, `Context(id)`, `ToggleVi`), `and/uc/TextSink.kt` (mới: interface; `ImeTextSink` dùng `getInputMethod().currentInputConnection` (API 33), `SetTextFallbackSink` dùng `ACTION_SET_TEXT/ACTION_SET_SELECTION`), `and/uc/WingpaneAccessibilityService.kt` (`onCreateInputMethod()` trả `InputMethod(this)` override `onStartInput` để biết editor focus; `softKeyboardController.showMode`), `UcDebugReceiver.kt` (extra `hid`, `mods`).
* **Protocol / macOS:** dùng `InputEvent::Keyboard` có sẵn (chưa nối mạng; lái bằng broadcast debug).
* **Android chi tiết:** Telex: `aa→â aw→ă ee→ê oo→ô ow→ơ uw→ư dd→đ`, `w` đơn đầu từ → `ư`, `uow→ươ`; dấu `s f r x j`, `z` xoá dấu; gõ lặp phím dấu/phím mũ → hoàn nguyên + ký tự thường (`ass→as`); đặt dấu theo quy tắc nguyên âm (kiểu mới: `oà, uý`); ranh giới từ = space, dấu câu, Enter, Tab, phím mũi tên, click chuột, `UcExit` → `commit()`. Backspace khi buffer khác rỗng → bỏ ký tự thô cuối và render lại (ra `Edit`), rỗng → `sendKeyEvent(DEL)`. Không editor focus → chỉ phím tắt toàn cục; ký tự bỏ. Phím lặp (autorepeat từ Mac) → lặp lại hành động.
* **Chấp nhận:** trong Chrome (ô tìm kiếm), Zalo (ô chat), Samsung Notes: gõ `Tieengs Vieetj cos daaus dduwowcj khoong` → "Tiếng Việt có dấu được không"; `⌘C/⌘V` giữa hai ô; `Esc` = Back; bàn phím Samsung không bật lên khi focus ô nhập; API 33 path dùng được trên Tab S9 (kiểm `IME_CONNECTION`).
* **Test:** `TelexComposerTest` (≥ 40 ca: bảng từ thông dụng, `ươ`, `gi/qu` (`giaf→già`, `quaf→quà`), `ass→as`, `z`, backspace giữa từ, kiểu cũ/mới, chữ hoa `DDuwowcj→Được`), `HidKeymapTest` (A–Z, 0–9 + shift ký hiệu US, Enter/Tab/Esc/mũi tên/Del), `ShortcutMapTest`, `KeyRouterTest` (có/không editor, Ctrl+Space đổi VI, ⌘C → Context copy, phím trong VI đi qua composer). Thủ công U6–U8.
* **Phụ thuộc:** U1. **Rủi ro:** `AccessibilityInputConnection` không hoạt động ở một số editor (WebView cũ, React Native) → fallback SET_TEXT; Samsung Keyboard vẫn hiện dù `SHOW_MODE_HIDDEN` → ghi giới hạn.

### WP-U4 — Spike macOS: event tap, giữ/ẩn con trỏ, hotkey, watchdog (không cần tablet)
* **Mục tiêu:** chứng minh bắt/nuốt input Mac và **luôn trả lại được chuột**, chạy được không cần Android.
* **File:** `bridge/UniversalControl.swift` (mới: `final class UcTap` — thread riêng `Thread { CFRunLoopRun() }`, `CGEvent.tapCreate(tap: .cghidEventTap, place: .headInsertEventTap, options: .defaultTap, eventsOfInterest: mouseMoved|L/R/OtherMouseDown/Up/Dragged|scrollWheel|keyDown|keyUp|flagsChanged)`; `inside` atomic; `enter()` = `CGWarpMouseCursorPosition(center)` + `CGAssociateMouseAndMouseCursorPosition(0)` + hide (D-7) + `CGEventSource.setLocalEventsSuppressionInterval(0)`; `exit(along:)` = warp về cạnh + show + associate(1); hotkey Ctrl+⌥+Esc (keycode 0x35, flags control+alternate) → `exit(Hotkey)`; timer 250 ms trên run loop của tap: watchdog heartbeat (1 s), `IsSecureEventInputEnabled()`, `CGSessionCopyCurrentDictionary` (`CGSSessionScreenIsLocked`/`kCGSessionOnConsoleKey`) → `exit(MacLocked)`; xử lý `.tapDisabledByTimeout/.tapDisabledByUserInput`; event có `eventSourceStateID != HIDSystemState` khi inside → nuốt + đếm), `bridge/Bridge.swift` (FFI mới), `bridge/UcSupport.swift` (mới, **thuần, không gọi CG**: `UcHotkey.matches(keycode:flags:)`, `UcEventRecord` struct C-layout, mã hoá flags), `apps/desktop/macos-bridge/Package.swift` (thêm `testTarget WingpaneBridgeTests` chỉ phụ thuộc target thuần mới `WingpaneUcSupport` để test không cần quyền), `apps/desktop/macos-bridge/Tests/WingpaneBridgeTests/UcSupportTests.swift` (mới), `wpd/main.rs` (cờ `--uc-selftest <edge>`: không mở socket/adb, tạo tap, in event ra stderr, vào inside khi chạm cạnh theo logic đơn giản tạm (`x >= maxX-1`), **tự thoát sau 15 s** và luôn gọi `wp_uc_release()`), `wpd/ffi.rs` (khai báo FFI).
* **FFI (C ABI):**
  ```c
  typedef struct { uint8_t kind; uint8_t button; uint8_t phase; uint8_t reason;
                   uint16_t keycode; uint8_t down; uint8_t autorepeat;
                   uint64_t flags; double x, y, dx, dy; } WPUcEvent;
  // kind: 0 Moved(outside: x,y,dx,dy) 1 Delta(inside: dx,dy) 2 Button 3 Scroll(dx,dy,phase) 4 Key(keycode,down,flags,autorepeat)
  //       5 Flags(flags) 6 Entered 7 Exited(reason) 8 Status(secure_input, tap_ok)
  typedef void (*WPUcCallback)(void *ctx, const WPUcEvent *ev);
  bool wp_uc_available(void);                 // AXIsProcessTrusted && tạo thử tap OK
  bool wp_uc_start(WPUcCallback cb, void *ctx); // tạo tap + thread, idempotent
  void wp_uc_set_inside(bool inside, uint8_t edge, double along);
  void wp_uc_heartbeat(void);
  void wp_uc_release(void);                   // idempotent: exit nếu inside, show, associate(1)
  void wp_uc_stop(void);                      // release + gỡ tap + dừng thread
  uint32_t wp_uc_display_rects(double *out, uint32_t max); // [x,y,w,h]*n, phần tử 0 = main
  uint8_t wp_uc_ime_hint(void);               // TIS: 0/1/2
  ```
  Callback chạy trên thread tap → Rust chỉ được đẩy vào channel không chặn (`std::sync::mpsc`/`tokio::sync::mpsc::UnboundedSender::send`), không lock dài.
* **macOS chi tiết:** scroll: `scrollWheelEventPointDeltaAxis1/2` (pixel) + `scrollWheelEventScrollPhase`/`MomentumPhase` → `phase`; chuột thường (`isContinuous == 0`) → `Discrete` với `scrollWheelEventDeltaAxis1 × 16`. Quyền: thêm `CGPreflightListenEventAccess()` báo lên status (Input Monitoring) — *chưa chắc active tap ở HID chỉ cần Accessibility; spike ghi lại cần quyền nào trên macOS của máy anh*.
* **Chấp nhận (chạy `target/release/wingpaned --uc-selftest right` từ `Wingpane.app/Contents/Resources` đã cấp Accessibility):** đẩy chuột qua cạnh phải → con trỏ biến mất, Mac không nhận click/phím (TextEdit không gõ được), stderr in delta/phím; Ctrl+⌥+Esc → con trỏ hiện lại ở cạnh phải; sau 15 s tự trả; `kill -9` daemon khi đang inside → chuột Mac dùng được ngay (nếu không → ghi lại, app phải gọi associate như D-10.5); ô mật khẩu (Safari) đang focus → không vào (in `secure_input`); EVKey/OpenKey bật → ghi lại tap thấy phím thô hay ký tự đã ghép.
* **Test:** Swift `UcSupportTests` (hotkey đúng/sai modifier, encode struct size/offset ổn định = 48 byte), `swift build` bridge; Rust: `--uc-selftest` parse option. Thủ công U10–U13.
* **Phụ thuộc:** không. **Rủi ro:** `SetsCursorInBackground` không còn → fallback D-7; tap ở HID bị từ chối → thử `.cgSessionEventTap` và ghi lại (EVKey sẽ chen trước, D-15 suy giảm: chỉ dùng khi người dùng tắt EVKey).

### WP-U5 — Daemon: lõi UC + tích hợp phiên + kênh lệnh stdin
* **Mục tiêu:** host chạy đủ vòng Armed → Inside → Armed với client, thoát an toàn mọi đường.
* **File:** `wpd/uc.rs` (mới, **thuần**, `pub mod uc` trong `lib.rs`):
  - `EdgeDetector { edge, displays: Vec<Rect>, push_pt, window_ms }` → `fn on_move(x,y,dx,dy,buttons_down,now_ms) -> Option<f32 /*along*/>`; `fn free_spans() -> Vec<(f32,f32)>` (đoạn cạnh không kề màn khác, cho status).
  - `UcState` (§2.2) → `fn on_bridge(ev) -> Vec<UcAction>`, `fn on_client(msg) -> Vec<UcAction>`, `fn on_mode(mode)`, `fn on_client_status(flags)`; `UcAction = Send(Packet) | SetInside(bool, edge, along) | Status(UcStatusJson)`.
  - `DeltaCoalescer` (gộp `Delta` ≤ 4 ms; flush trước mọi Button/Key/Scroll để giữ thứ tự).
  - `mac_to_hid(keycode) -> Option<u16>` (đảo `keymap_macos::mac_keycode`), `mac_flags_to_hid(flags: u64) -> u8` (dùng bit device-dependent trái/phải `NX_DEVICELCTLKEYMASK=0x1, LSHIFT=0x2, RSHIFT=0x4, LCMD=0x8, RCMD=0x10, LALT=0x20, RALT=0x40, RCTL=0x2000`; không có bit device → coi là trái).
  - `wpd/keymap_macos.rs` (thêm `pub fn hid_usage(mac: u16) -> Option<u16>` + test khứ hồi).
  - `wpd/main.rs`: `HOST_INPUT_FEATURES |= FEATURE_UNIVERSAL_CONTROL` khi `wp_uc_available()`; sau `Capabilities` của client có bit 8 → gửi `UcConfig{edge}`; `wp_uc_start` khi phiên bắt đầu nếu bit 8 hai phía; nhánh select mới: `uc_rx.recv()` (event bridge) và `uc_tick` 250 ms (`wp_uc_heartbeat`, flush coalescer, refresh `displays` mỗi 2 s); `ControlMessage::UcExit/UcStatus` từ client → `UcState`; `SetMode` (cả từ client lẫn lệnh stdin) đi qua một hàm chung `apply_mode(next, origin)` tách từ khối hiện có (không đổi logic display transition); `UcGuard` (giữ trong `SessionResources` mới field `uc: Box<dyn UcResource>`, drop thứ tự **uc_release → release_all → stop → destroy**); `std::panic::set_hook` gọi `wp_uc_release()`; `options()` thêm `--uc-edge <left|right|top|bottom>`; khi `--status-json`: task đọc stdin JSON Lines → `watch::Sender<UcCommand>`.
  - Lệnh stdin: `{"cmd":"uc_enable","enabled":true}`, `{"cmd":"uc_edge","edge":"left"}`, `{"cmd":"uc_return"}`. Dòng sai → `error` status, không thoát.
  - Status JSON mới (thêm, app cũ bỏ qua): `{"event":"uc","state":"unavailable|disabled|armed|inside","edge":"right","client_ready":true,"reason":"secure_input"?, "edge_blocked":false}`; `permissions` thêm `input_monitoring`.
  - `wpd/ffi.rs` (bọc FFI U4 thành `UcBridge`), `wpd/lib.rs` (`SessionResources.uc`, trait `UcResource { fn release(&self); }`).
* **Protocol:** gửi `UcConfig`, `UcEnter{edge, along, ime_hint: wp_uc_ime_hint()}`, `UcExit`, `Input{PointerDelta|MouseButton|Keyboard|PreciseScroll}`; nhận `UcExit`, `UcStatus`. Tất cả Input/Control host→client đi qua `control_tx` (writer `biased` ưu tiên trước video — FR-7).
* **macOS:** như U4; `wp_uc_set_inside(false, edge, along)` khi client `UcExit`.
* **Chấp nhận (với client giả `apps/desktop/wingpaned/examples/dump_client.rs` mở rộng: gửi Capabilities bit 8 + `UcStatus(ready)` + `SetMode(AndroidControl)`, in gói nhận):** đẩy chuột qua cạnh → dump_client in `UcEnter` rồi `PointerDelta…`; gõ phím → `Keyboard` HID đúng; dump_client gửi `UcExit{0.5}` → con trỏ Mac hiện ở giữa cạnh; Ctl+C dump_client khi inside → chuột Mac trở lại ≤ 1 s; lệnh stdin `uc_edge left` đổi cạnh không restart.
* **Test (Rust thuần, không socket — Codex chạy được):** `EdgeDetector` (cạnh phải vào khi đẩy đủ; không vào khi giữ nút; màn ngoài kề cạnh phải chặn đoạn đó; cạnh trên cần 64 pt; `along` đúng 4 cạnh; display âm toạ độ), `UcState` (không ready → không vào; `Entered` → gửi `UcEnter`; hotkey `Exited` → gửi `UcExit(Hotkey)`; client `UcExit` → `SetInside(false)` và **không** gửi lại; đổi mode khi inside → `SetInside(false)` + `UcExit(ModeChanged)`; client `KEYGUARD_LOCKED` khi inside → thoát `ClientNotReady`), `DeltaCoalescer` (thứ tự), `hid_usage` khứ hồi với `mac_keycode` cho mọi usage có map, `mac_flags_to_hid`, `SessionResources` drop order `["uc_release","release","stop","destroy"]`, parse lệnh stdin, JSON `uc` contract (mở rộng `status_event_json_contract_and_line_cap`). Thủ công U14–U16.
* **Phụ thuộc:** U0, U4. **Rủi ro:** tách `apply_mode` chạm vòng phiên → giữ nguyên test `display_transition_tests`, `video_suspend_tests`; R1 bắt buộc.

### WP-U6 — Android: nối UC vào phiên
* **Mục tiêu:** đầu-cuối: chuột/bàn phím Mac điều khiển Android qua cáp.
* **File:** `and/net/WingpaneClient.kt` (Capabilities client thêm `1u shl 8` **chỉ khi** `BuildConfig`/feature flag bật; `hostFeatures` đã có; flow mới `ucControl: SharedFlow<ControlMessage>` cho `UcConfig/UcEnter/UcExit/SetMode` từ host; `Input` từ host tiếp tục qua `packets`), `and/uc/UcController.kt` (mới: sống trong `WingpaneAccessibilityService`, collect `ClientHolder.client.packets` (Input) + `ucControl`; reducer thuần `UcClientState` (`Idle/Armed(edge)/Inside(edge)`); `Inside` → `CursorModel`, `PointerGesturePlanner`, `KeyRouter`; rời cạnh → `sendControl(UcExit(along, CursorReturned))`; `UcExit` từ host → `cancelAll()`; gửi `UcStatus` khi flags đổi), `and/uc/UcClientState.kt` (mới, thuần), `and/MainActivity.kt` (`mode` nhận `SetMode` từ host; vào `AndroidControl` → `moveTaskToBack(true)`; `onResume` khi mode = AndroidControl → `AndroidControlScreen`), `and/ui/screen/AndroidControlScreen.kt` (mới: trạng thái service/FGS/cạnh, nút "Về Display", "Mở cài đặt Trợ năng", gain slider, đảo cuộn, kiểu bỏ dấu), `and/ui/hud/FloatingHud.kt` (segment thứ 4 "Android": enabled khi `capabilitiesReceived && hostFeatures and (1 shl 8) != 0`; nếu service chưa bật → mở `Settings.ACTION_ACCESSIBILITY_SETTINGS` + hướng dẫn), `and/uc/UcForegroundService.kt` (bật theo D-19), `andres/values*/strings.xml`.
* **Protocol:** như §2.1; client gửi `SetMode(AndroidControl)` chỉ khi host bit 8 (D-3).
* **Android:** `UcEnter` → đặt con trỏ ở cạnh đối diện D-20 tại `along`, hiện overlay, `SHOW_MODE_HIDDEN`, `TelexComposer.viMode` theo `ime_hint` (2→VI, 1→EN, 0→giữ); `UcExit`/mất kết nối/service `onUnbind`/`onInterrupt` → `cancelAll()` + ẩn + `SHOW_MODE_AUTO`. Client mất Active khi inside → như `UcExit`.
* **Chấp nhận:** Display → HUD "Android" → tablet về Home Android, màn ảo trên Mac biến mất; rê chuột Mac qua cạnh phải → con trỏ xuất hiện cạnh trái tablet, mở Chrome, gõ URL, cuộn, quay lại Mac bằng cách rê qua cạnh trái tablet; trễ di chuột cảm nhận ≤ chuột thường (đo: `ts_us` host → vẽ overlay, mục tiêu p50 ≤ 16 ms qua `ClockOffsetEstimator`).
* **Test:** `UcClientStateTest` (Idle→Armed→Inside→Armed; `UcExit` khi Idle bỏ qua; mất kết nối khi Inside → effect `CancelAll`), `WingpaneClientTest` (gửi Capabilities có bit 8 khi flag bật; `UcEnter` từ host phát lên `ucControl`; `SetMode(AndroidControl)` không gửi khi host thiếu bit 8). Thủ công U17–U22.
* **Phụ thuộc:** U0, U1, U2, U3, U5.

### WP-U7 — Mac app: menu "Control Android", cạnh, an toàn khi daemon chết
* **File:** `macapp/WingpaneAppCore/Options.swift` (`ucEdge: UcEdge = .right`, key `wingpane.options.ucEdge`; `daemonArguments` thêm `--uc-edge`; **đổi `ucEdge` không tính là thay đổi cần restart** — tách `restartRelevant`), `macapp/WingpaneAppCore/DaemonController.swift` (`proc.standardInput = Pipe()`; `send(command:)` ghi một dòng JSON, bỏ qua nếu daemon chưa chạy; `terminationHandler` gọi `CursorSafety.restore()`; watchdog: status `uc.state == inside` và không có dòng stdout 3 s → `SIGKILL`), `macapp/WingpaneAppCore/CursorSafety.swift` (mới: `CGAssociateMouseAndMouseCursorPosition(1)` + warp giữa màn chính, idempotent), `macapp/WingpaneAppCore/DaemonEvent.swift` (`case uc(state:edge:clientReady:reason:edgeBlocked:)`, `permissions` thêm `inputMonitoring?`), `macapp/WingpaneAppCore/StatusModel.swift` (`ucState`, dòng trạng thái "Android: ready / inside / Accessibility service off on tablet / Edge blocked by another display / Secure input on"), `macapp/Wingpane/WingpaneApp.swift` (menu bên dưới), `docs/05-mac-app.md` (§2 event `uc`, field `input_monitoring`, stdin commands; §3 menu).
* **Menu:**
  ```
  Control Android ▸  Enabled ✓            (gửi uc_enable; disabled khi uc=unavailable)
                     Edge ▸ Left · Right ✓ · Top · Bottom
                     Return to Mac now      (chỉ khi inside; gửi uc_return)
                     ─ Ctrl+⌥+Esc: luôn trả chuột về Mac
                     <trạng thái 1 dòng>
  Permissions ▸ … · Input Monitoring ✓/✗
  ```
* **Chấp nhận:** bật/tắt từ menu đổi mode trên tablet ≤ 500 ms; đổi cạnh khi đang chạy không rớt stream/không restart (log daemon không có `starting` mới); Quit app khi inside → chuột Mac về bình thường.
* **Test:** `WingpaneAppCoreTests`: Options round-trip `ucEdge`, `daemonArguments` có `--uc-edge`, đổi `ucEdge` không trigger restart (fake), `DaemonEvent` parse `uc` + bỏ qua field lạ, StatusModel dòng trạng thái, encode lệnh stdin đúng 1 dòng JSON. Thủ công U23–U25.
* **Phụ thuộc:** U5.

### WP-U8 — Cuộn chính xác, double-click, chuột phải, (pinch)
* **Mục tiêu:** hoàn thiện cảm giác D-17/D-18 và đo trên app thật.
* **File:** `and/uc/ScrollPlanner.kt` (mới, thuần: `PreciseScroll` → plan stroke; wheel gộp 40 ms; `invert`), `PointerGesturePlanner.kt` (double-click, long-press tinh chỉnh theo `ViewConfiguration`), `GestureAdapter.kt`; stretch: `bridge/UniversalControl.swift` bắt `NSEventTypeMagnify` (raw type 30) qua `NSEvent(cgEvent:)` → `InputEvent::Gesture(PinchZoom(scale))` host→client (đã có biến thể) → Android gesture 2 stroke một lần.
* **Chấp nhận:** cuộn 2 ngón trackpad trong Chrome/Zalo theo tay, dừng tay → trang dừng (không trượt thêm); bánh xe chuột cuộn từng nấc; double-click chọn từ trong Samsung Notes; chuột phải mở menu giữ-lâu.
* **Test:** `ScrollPlannerTest` (Began/Changed/Ended → 1 stroke liên tục kết thúc bằng giữ yên; Momentum bỏ; wheel nhỏ hơn slop được gộp; invert). Thủ công U9, U26–U27.
* **Phụ thuộc:** U6.

### WP-U9 — Clipboard văn bản (stretch)
* **File:** `proto/control.rs` (`Clipboard { text }` tag 15, `LAST_CONTROL_TAG = 15`), golden + `Protocol.kt`, `bridge/UniversalControl.swift` (`wp_uc_pasteboard_get(buf,len)`/`set`, `NSPasteboard.general.changeCount`), `wpd/uc.rs` (gửi khi `Entered` nếu `changeCount` đổi; nhận → set pasteboard), `and/uc/ClipboardBridge.kt` (service `setPrimaryClip` khi nhận), `and/uc/ClipboardSendActivity.kt` (mới, trong suốt, mở từ action notification "Gửi clipboard sang Mac": đọc clip khi `onWindowFocusChanged(true)`, gửi, `finish()`).
* **Protocol:** bit 9 `CLIPBOARD_TEXT`; text ≤ 64 KiB UTF‑8, quá thì bỏ (không cắt).
* **Chấp nhận:** copy trên Mac → vào Android → dán trong Zalo đúng (kể cả tiếng Việt + emoji); notification action đưa clipboard Android sang Mac.
* **Test:** golden `control_clipboard`; Rust giới hạn 64 KiB; Kotlin roundtrip. Thủ công U28.
* **Phụ thuộc:** U6.

### 3.1 Thứ tự & phụ thuộc

```
U0 ───────────────────────────┐
U1 (spike Android) ─► U3 ─────┤
U2 (client nền) ──────────────┼─► U6 ─► U8
U4 (spike macOS) ─► U5 ───────┘    └──► U9 (stretch)
                     └─► U7
```

Thứ tự khuyến nghị: **U1, U4** (hai spike rủi ro, có thể song song; nếu một spike trượt tiêu chí thì dừng và quyết định lại trước khi tiêu công cho phần còn lại) → **U0, U2** → **U3, U5** → **U6** → **U7** → **U8** → U9. Mốc demo: sau U1 (tablet tự điều khiển bằng broadcast), sau U4 (Mac bắt/trả chuột), sau U6 (đầu-cuối).

---

## 4. Checklist thủ công trên SM-X710 (Galaxy Tab S9, Android 14) + Mac

Chuẩn bị: `Wingpane.app` từ `apps/desktop/mac-app/build/` (không cài `/Applications`), `wingpaned` đã cấp Accessibility (+ Input Monitoring nếu U4 cho thấy cần), APK debug cài bằng `adb install -r`, Settings ▸ Accessibility ▸ Installed apps ▸ Wingpane bật, Battery ▸ Wingpane = Unrestricted. Ghi **ngày + build + môi trường** cho mỗi lần chạy.

| # | WP | Bước | Kỳ vọng |
|---|---|---|---|
| U1 | U1 | Broadcast `dx/dy` liên tục | Con trỏ mượt 120 Hz, không chặn chạm tay thật |
| U2 | U1 | Broadcast click vào icon Settings, Chrome, Zalo (20 lần) | 20/20 trúng; trễ p50/p95 ghi lại |
| U3 | U1 | Broadcast giữ nút + di 3 s trong Samsung Notes | Một nét liền (≥ 95%); kết quả drag trong Chrome = cuộn trang (đúng D-13) |
| U4 | U1 | Click vào app ngân hàng / app có `FLAG_SECURE` | Ghi lại: con trỏ có hiện, tap có ăn, app có cảnh báo a11y |
| U5 | U2 | Bật FGS, Home, đợi 10 phút, mở lại app | Phiên còn Active, video về ≤ 300 ms |
| U6 | U3 | Gõ Telex trong Chrome/Zalo/Notes | "Tiếng Việt có dấu được không" đúng, bàn phím Samsung không hiện |
| U7 | U3 | Ctrl+Space rồi gõ `viet` | EN: "viet"; badge VI/EN đổi |
| U8 | U3 | ⌘C ô A, ⌘V ô B; Esc; ⌘H; Ctrl+↑ | Copy/paste; Back; Home; Recents |
| U9 | U8 | Cuộn 2 ngón trackpad (bật/tắt natural scrolling Mac) | Hướng đúng cảm giác, dừng tay dừng trang |
| U10 | U4 | `--uc-selftest right`, đẩy qua cạnh phải | Con trỏ Mac biến mất, Mac không nhận click/phím |
| U11 | U4 | Ctrl+⌥+Esc khi inside | Con trỏ hiện ở cạnh phải ngay |
| U12 | U4 | `kill -9` daemon khi inside | Chuột Mac dùng được ngay (hoặc sau khi app chạy CursorSafety) |
| U13 | U4 | Focus ô mật khẩu Safari rồi đẩy qua cạnh | Không vào; log `secure_input` |
| U14 | U5 | Màn ngoài nằm bên phải, chọn cạnh phải | Menu báo "Edge blocked"; chỉ đoạn không kề mới vào được |
| U15 | U5 | Đang kéo cửa sổ Mac chạm cạnh | Không vào Android |
| U16 | U5 | Đổi cạnh bằng menu khi đang stream Display | Không restart, không rớt hình |
| U17 | U6 | HUD → Android | Tablet về Home, màn ảo Mac biến mất |
| U18 | U6 | Rê qua cạnh vào, mở Chrome, gõ URL, Enter, cuộn | Hoạt động; trễ di chuột cảm giác như chuột thường |
| U19 | U6 | Rê qua cạnh trái tablet | Con trỏ Mac xuất hiện cạnh phải đúng độ cao tương ứng |
| U20 | U6 | Rút cáp khi inside | Chuột + bàn phím Mac về bình thường ≤ 1 s; tablet ẩn con trỏ, bàn phím Samsung hoạt động lại |
| U21 | U6 | Khoá màn tablet khi inside | Host thoát (`ClientNotReady`), chuột về Mac |
| U22 | U6 | Tắt a11y service trong Settings khi inside | Chuột về Mac; HUD/ menu Mac báo service tắt |
| U23 | U7 | Menu Control Android ▸ Enabled khi tablet đang Display | Tablet chuyển Android ≤ 500 ms |
| U24 | U7 | Quit Wingpane khi inside | Chuột Mac bình thường |
| U25 | U7 | Thu hồi Accessibility của wingpaned khi inside | Tap bị disable → thoát, menu báo ✗ |
| U26 | U8 | Double-click từ trong Notes; chuột phải trên link Chrome | Chọn từ; menu ngữ cảnh |
| U27 | U8 | Bánh xe chuột USB thường | Cuộn từng nấc |
| U28 | U9 | Copy Mac → dán Zalo; action notification gửi ngược | Đúng nội dung tiếng Việt |
| U29 | — | Sau mỗi WP: Display stream 5 phút, Display↔Pen↔Trackpad↔Android 20 lần | FPS/latency như ADR-006; không kẹt nút/phím Mac (QC gate 3) |
| R1 | — | Regression docs/06 §4 P1–P11, T1–T8, K1–K6 sau U0, U2, U5 | Không đổi |

---

## 5. Kết quả spike (điền sau U1/U4)

| Hạng mục | Giá trị đo | Ngày/build/môi trường | Quyết định kéo theo |
|---|---|---|---|
| dispatch→ACTION_DOWN p50/p95 | _ | _ | _ |
| Drag `continueStroke` tỉ lệ liền nét | _ | _ | giữ D-18 hay fallback "vẽ lại khi nhả" |
| Tap trên `FLAG_SECURE` / app ngân hàng | _ | _ | _ |
| `SetsCursorInBackground` hoạt động | _ | _ | giữ D-7 hay fallback |
| Quyền cần cho active HID tap | _ | _ | thêm Input Monitoring vào menu? |
| EVKey/OpenKey: tap thấy phím thô? | _ | _ | D-15 giữ/giảm |
| `⌘Tab`/`⌘Space` tới tap? | _ | _ | D-16 |

---

## 6. Ràng buộc cho coding agent

1. **Mỗi WP do Codex CLI viết code**, sau đó **orchestrator (Claude) kiểm chứng**. Sandbox của Codex **không chạy được Gradle, Swift (`swift build/test`), và socket localhost** → Codex chỉ được chạy: `cargo build --workspace`, `cargo test -p wingpane-proto`, `cargo test -p wingpaned --lib` cho test **thuần** (không `TcpListener`), `cargo run -p wingpane-proto --example golden`. Orchestrator chạy toàn bộ:
   ```bash
   cargo test --workspace
   (cd apps/android && ./gradlew testDebugUnitTest assembleDebug)
   swift test --package-path apps/desktop/macos-bridge        # sau U4 có test target
   (cd apps/desktop/mac-app && swift test)
   apps/desktop/mac-app/scripts/build-app.sh                 # KHÔNG --install
   adb install -r apps/android/app/build/outputs/apk/debug/app-debug.apk
   ```
   rồi đưa anh Lâm checklist §4 của WP đó. Codex phải liệt kê trong báo cáo những gì **chưa** chạy được.
2. Test mới phải **thuần** (Rust: fake trait/struct, không socket, không gọi CG; Kotlin: nhận primitive như `TouchMapper`, không cần thiết bị; Swift: target `WingpaneUcSupport` không gọi CG) để Codex tự chạy được phần Rust.
3. **Wire-compat:** chỉ thêm biến thể ở **cuối** enum, không đổi `PROTO_VERSION`, golden Rust + Kotlin cùng commit; không gửi gì của §2.1 khi peer chưa báo bit 8 (bit 9 cho Clipboard).
4. **Không phá đường stream:** không sửa `CaptureEncoder`, `VirtualDisplay`, `FrameQueue`, `VideoDecoder`, `AnnexB`, z-order `SurfaceView`. U2 (chủ sở hữu client) và U5 (`apply_mode`) chạm vùng gần stream → **commit riêng**, chạy R1, hỏng thì revert riêng.
5. **Không bao giờ để Mac kẹt chuột/bàn phím:** mọi đường kết thúc phiên, đổi mode, panic, SIGINT/SIGTERM, mất quyền phải đi qua `wp_uc_release()` trước `release_all()`. Test drop order bắt buộc.
6. **Không cài vào `/Applications`**; một commit cho mỗi WP, message dạng `feat(uc): WP-U5 daemon universal-control core`; không commit file ngoài phạm vi WP.
7. Cập nhật cùng commit: `docs/02-protocol.md` (§8 UC) ở U0/U9; `docs/05-mac-app.md` ở U5/U7; ADR mới trong `docs/decisions.md` ở U0 cho D-1, D-3, D-6, D-12, D-15 (chỉ ghi thêm).
8. UI theo docs/03: nút ≥ 48 dp, tương phản ≥ 7:1, token `WingpaneTheme`; chuỗi có bản `values-vi`.

---

## 7. Điều chưa kiểm chứng (phải xác nhận trong spike/thủ công)

1. Trễ thực tế của `dispatchGesture` và độ ổn định `continueStroke` trên One UI 6 (U1).
2. Gesture inject có ăn trên cửa sổ `FLAG_SECURE`; cửa sổ con trỏ lệch 1 px có tránh được `filterTouchesWhenObscured` không (U1).
3. `AccessibilityService.InputMethod` (API 33) hoạt động trong Chrome, Zalo, Samsung Notes và `SHOW_MODE_HIDDEN` thực sự ẩn Samsung Keyboard (U3).
4. Private `SetsCursorInBackground` còn hiệu lực trên macOS đang dùng; `CGAssociateMouseAndMouseCursorPosition` có tự reset khi process chết không (U4).
5. Active tap ở `kCGHIDEventTap` cần Accessibility hay thêm Input Monitoring; tap của ta có đứng trước EVKey/OpenKey không; `⌘Tab`/`⌘Space`/cử chỉ 3 ngón (Mission Control) có bị macOS xử lý trước tap không — nếu có, các cử chỉ hệ thống Mac vẫn chạy khi đang ở Android (ghi giới hạn) (U4).
6. Hướng `scrollWheelEventPointDelta` so với natural scrolling (U9).
7. Android 13+ "Restricted settings" với APK cài qua `adb install`; Samsung có tự tắt a11y service sau cập nhật APK (U1).
8. Loopback qua `adb reverse` khi app ở nền không có FGS (không chặn plan vì đã dùng FGS — D-19).
