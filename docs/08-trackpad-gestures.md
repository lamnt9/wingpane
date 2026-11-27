# 08 — Trackpad: multi-finger gestures, mouse buttons, no virtual display

Status: approved 2026-11-28 (wave G). Base branch: `uc/wave-f`.

## 1. Goals
- TG-1 Trackpad mode supports 1–5 finger gestures mapped to macOS 26 defaults (table §3).
- TG-2 Trackpad screen gets an on-screen Left | Right mouse button bar.
- TG-3 The trackpad screen no longer shows the Mac screen above the pad; the pad fills the tablet.
- TG-4 Only `Mode::ExtendedDisplay` keeps the virtual display. Switching to Trackpad, Pen (GraphicsTablet) or AndroidControl removes it; switching back to Display recreates it.

Non-goals: 3-finger drag (conflicts with 3-finger swipe; dragging = tap-and-a-half or holding the Left button). Real macOS trackpad gesture events (no public API) — gestures are mapped to shortcuts / app launch.

## 2. Display rule (host)
- DR-1 `mode_has_display(mode) == (mode == Mode::ExtendedDisplay)` (`apps/desktop/wingpaned/src/lib.rs`, fn `mode_has_display`).
- DR-2 `display_transition` / `should_send_video` keep using DR-1; tests in `display_transition_tests` updated: Display→Trackpad = Remove, Trackpad→Display = Create, Trackpad↔Pen = None, Trackpad↔AndroidControl = None.
- DR-3 Android: in `Mode.TrackpadKeyboard` the video surface is not laid out (no 0.42 split); `TrackpadScreen` fills the content area. Video packets arriving while in trackpad are ignored as today for Pen.

## 3. Gesture map
| ID | Fingers | Gesture | Wire event | macOS action |
|---|---|---|---|---|
| GM-1 | 1 | move / tap / tap-and-a-half drag | existing | unchanged |
| GM-2 | 2 | scroll / tap / pinch | existing | unchanged (scroll, right click, ⌘+/⌘-) |
| GM-3 | 3 | swipe ↑ ↓ ← → | existing `Swipe3*` | Ctrl+↑ / Ctrl+↓ / Ctrl+← / Ctrl+→ (unchanged) |
| GM-4 | 3 | tap (all 3 down then up, no move, ≤ TAP_MS) | `Gesture(ThreeFingerTap)` | Look up: Ctrl+⌘+D (HID 0x07, mods Ctrl\|Cmd = 0x09) |
| GM-5 | 4 | swipe ↑ ↓ ← → | `Gesture(Swipe4Up/Down/Left/Right)` | same as GM-3 |
| GM-6 | 5 | pinch in (spread shrinks ≥ 25 %) | `Gesture(FiveFingerPinchIn)` | open the Apps app (`open -a Apps`; macOS 26 has no Launchpad) |
| GM-7 | 5 | spread out (spread grows ≥ 25 %) | `Gesture(FiveFingerSpread)` | Show Desktop: F11 (HID 0x44, no modifiers) |

- GM-8 One gesture event per touch sequence for GM-4..GM-7 (same `gestureSent` latch as 3-finger swipe). Swipe threshold for 4 fingers = the existing `threeFingerSwipePx`.
- GM-9 Spread for 5 fingers = mean distance of the pointers to their centroid, base taken when the 5th finger lands.
- GM-10 Finger count of a sequence = max simultaneous pointers (`startCount`). A 4/5-finger sequence must never emit Scroll, PointerDelta, Tap or right click.

## 4. Protocol (append-only, PROTO_VERSION stays 1)
- PR-1 Append to `GestureKind` (Rust `crates/wingpane-proto/src/input.rs`, Kotlin `Protocol.kt`) after `PinchZoom`, in this order: `ThreeFingerTap`=8, `Swipe4Up`=9, `Swipe4Down`=10, `Swipe4Left`=11, `Swipe4Right`=12, `FiveFingerPinchIn`=13, `FiveFingerSpread`=14. Unit variants, no payload.
- PR-2 New capability `FEATURE_EXTENDED_GESTURES = 1 << 10` in `crates/wingpane-proto/src/lib.rs`, advertised by the host in `HOST_INPUT_FEATURES`.
- PR-3 The Android client sends tags 8..14 only if the host advertised bit 10; otherwise it drops them (old hosts would fail to decode an unknown tag).
- PR-4 Golden/roundtrip tests for every new variant in both Rust and Kotlin; Kotlin decoder accepts tags 0..14.

## 5. Host mapping
- HM-1 `gesture_shortcut` returns shortcuts for GM-4, GM-5, GM-7 per §3.
- HM-2 GM-6 is not a shortcut: add `InputSink::launch_app(&self, _name: &str) {}` (default no-op); the native sink spawns `open -a <name>` without blocking the input thread and ignores failures except a log line. Gesture dispatch calls `launch_app("Apps")`.
- HM-3 Fake sink in tests records `launch_app` calls; unit tests cover every new variant.

## 6. Mouse button bar (Android UI)
- MB-1 Bottom of `TrackpadScreen`: a bar with two buttons, Left and Right, height 64 dp, full width split 50/50, separated by a 1 dp divider; labels localized EN/VI ("Left"/"Right", "Trái"/"Phải"). Pad area takes all remaining height above it (keyboard/panel switch that exists today keeps working).
- MB-2 Press on a button → `MouseButton(0|1, true)`; release or cancel → `MouseButton(0|1, false)`. Haptic on press (existing `performWingpaneHaptic`).
- MB-3 Button presses are independent pointers: holding Left with one finger while moving another finger on the pad drags. The detector is told via `TrackpadGestureDetector.setButtonHeld(held: Boolean)`; while held, a 1-finger pad touch never emits `Gesture(Tap)` nor starts tap-and-a-half/long-press drag (it only emits PointerDelta), so the click is not doubled.
- MB-4 Leaving trackpad mode / disconnect while a button is held → send the release (no stuck button on Mac).
- MB-5 Touch targets ≥ 48 dp, contrast per existing theme tokens; works in portrait and landscape.

## 7. Done criteria
- `cargo test --workspace` green; `./gradlew testDebugUnitTest lintDebug` with no new lint errors (the known AppLanguage.kt:40 error stays).
- New tests: `TrackpadGestureDetectorTest` cases for GM-4..GM-10 and MB-3; Rust tests for DR-2, PR-4, HM-1..HM-3.
- QC on SM-X710 (wave G4): each row of §3 and §6 performed by hand; switching Display→Trackpad removes the virtual display from macOS Settings ▸ Displays within 1 s.
