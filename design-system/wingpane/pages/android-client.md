# Android client UI — brief v0.3 (APPROVED)

Scope: (1) Connection screen, (2) Floating HUD in Extended Display mode. Tablet-first (landscape 2560×1600), phone 1080×2400 supported. Kotlin + Jetpack Compose (Material 3).
Out of scope (deferred to Phase 2, explicitly): stylus modifier dock (Ctrl/Z/Shift/Space, docs/03 §2 Chế độ 1), Pen & Trackpad modes UI.
Sources: docs/03-ui-ux-spec.md §1–§2, ADR-003/ADR-004 (docs/decisions.md), docs/02-protocol.md §4, ui-ux-pro-max `dark-mode-oled` + ux Error Recovery / Error Messages, agy + Codex critiques (local session notes).

## Status
APPROVED by anh Lâm 2026-11-24 together with the prototype in `../prototype/` (GALLERY.png) and all 5 deviations listed below. Tokens now live in `../tokens.json` / `../MASTER.md`.
Implementation notes from review (do not ship prototype artefacts): S5d uses the brief copy only (no "secure link" sentence); the "A11Y 200% FONT SCALE / 420dp Max" chip in prototype 12 is an annotation, not UI.

## Tokens (dark-mode-oled) — contrast measured (WCAG) on #000 / #0A0E27
| Role | Hex | Contrast | Use |
|---|---|---|---|
| bg | #000000 | — | Screen, video letterbox |
| surface-1 | #0A0E27 | — | Cards, HUD panel |
| surface-2 | #121212 | — | Secondary buttons, chips — ALWAYS with 1dp `border` |
| border | #5A6380 | 3.5 / 3.2 (non-text ≥3:1) | Outlines of surface-2 controls, HUD pill outline |
| text | #F2F4FF | 19.2 / 17.3 | Body, headings |
| text-muted | #A3AED0 | 9.5 / 8.6 | Helper text, metric labels |
| primary | #7AA2FF | 8.4 / 7.6 | Active, primary button fill; on-primary #000 (8.4) |
| success | #3DDC97 | 11.9 / 10.8 | Connected chip; on-success #000 (11.9) |
| warning | #FFB547 | 12.0 / 10.8 | High latency; on-warning #000 (12.0) |
| danger | #FF8080 | 8.7 / 7.8 | Errors, Disconnect; on-danger #000 (8.7) |
Filled status chips use #000 text. Never color alone: every state = icon + text.

Type: Outfit headings 28/22/18sp 600; Work Sans body 16sp/1.5, caption ≥13sp. Support font scale to 200% (see HUD sizing).
Spacing 4/8/16/24/32/48 · Radius 12 cards, full pill for HUD · Touch ≥48×48dp, gap ≥8dp · Material Symbols Rounded, contentDescription on all icon buttons · Haptic 15ms on HUD/toolbar taps (docs/03 §1).
Motion: enter 150–250ms ease-out, exit 120ms; system "Remove animations" → fades only.

## Screen 1 — Connection
Connection model (ADR-004): the Mac daemon runs `adb reverse tcp:7788`; the app connects to `127.0.0.1:7788` as TCP client, retrying every 1s (backoff to 2s after 30s). The app CANNOT detect: whether USB debugging is on (`ADB_ENABLED` not relied on), whether the RSA "Allow USB debugging?" prompt was shown/accepted, or whether the cable is plugged. Copy must never claim these.
Layout tablet landscape: left = line diagram tablet ⇄ cable ⇄ Mac (animated dots while S3); right = state card. Phone: stacked. Top-right Settings. Footer: app version + protocol v1 (muted).

| State | Trigger | Copy | Primary | Secondary |
|---|---|---|---|---|
| S1 Waiting | retrying, refused/timeout | "Waiting for your Mac" — "Connect the USB cable and run Wingpane on your Mac." | — (passive pulse) | "Setup guide" |
| S1 Setup guide (sheet, user-opened; also auto-suggested once after 30s) | — | 3 steps: enable Developer options + USB debugging; accept "Allow USB debugging?" on the tablet (tick Always allow); run `wingpane` on the Mac | "Open Developer options" | "Done" |
| S3 Handshaking | TCP connected → Hello sent | "Connecting to your Mac…" | "Cancel" → S1 | — |
| S1 Disconnected (added 2026-11-24, approved) | user pressed Disconnect in HUD; NO auto-retry | "Disconnected" — "Tap Connect when you want to use your Mac display again." | "Connect" | — |
| S5a Version mismatch | Reject(VersionMismatch{host_version}) | "Update needed — Mac uses protocol v{n}, this app v1." | "Update app" / "Try again" | "Troubleshooting" |
| S5b Unsupported device | Reject(UnsupportedCodec) | "This tablet can't decode H.264 video." | "Troubleshooting" | — |
| S5c Busy | Reject(Busy) | "Your Mac is already connected to another device." | "Try again" | — |
| S5d Handshake failed | Reject(InvalidHello), malformed reply, or no Accept/Reject within 3s of Hello | "Couldn't connect to your Mac." | "Try again" | "Troubleshooting" |
| S5e Connection lost | Reconnecting > 10s | "Connection to your Mac was lost." | "Try again" | "Troubleshooting" |

State machine:
- S1 → S3 (TCP connected, Hello sent) · S3 → Active (Accept) · S3 → S5a–d (Reject / malformed / 3s deadline) · S3 → S1 (Cancel)
- Active → Reconnecting (EOF, reset, or no Pong within 2s of a Ping; app pings every 500ms) · Reconnecting → Active (new TCP + Hello + Accept; same `adb reverse` stays valid while the daemon runs) · Reconnecting → S5e (10s) · Reconnecting → S1 (Cancel)
- Active → S1 Disconnected (Disconnect from HUD; stays until user taps Connect) · S5* → S1 (Try again)
On Accept → straight into Extended Display stream (no mode picker in MVP). Errors: liveRegion Polite (Assertive for S5e); focus to card title on state change.

## Screen 2 — Floating HUD (Extended Display stream)
- Stream forces `sensorLandscape`; immersive sticky; video edge-to-edge, letterbox #000. System bars may reappear transiently after edge swipes — HUD must stay reachable.
- Rendering: video in `SurfaceView` with DEFAULT z-order (surface behind window, Compose HUD composited above). NOT TextureView (extra GPU composition ≈ +1 frame latency, breaks the ≤20ms SLO) and NOT `setZOrderOnTop`. Verify layering on target device in C2.
- Collapsed: edge-flush half-pill 24×48dp visual, 48×48dp hit area, docked to the RIGHT edge at vertical center by default (avoids macOS menu bar + Android top swipe zone). Draggable only vertically along left/right edges; position from live `WindowInsets.systemGestures` ∪ `mandatorySystemGestures` ∪ `displayCutout` + 8dp.
- Idle: fades to 40% after 3s (pill keeps 1dp `border` at 100% so it stays visible on black letterbox). Accessibility setting "Keep HUD visible" disables fading.
- Touch pass-through rule: HUD hides only when an active pointer that started outside the HUD comes within 64dp of it; reappears 300ms after that pointer lifts. No blinking on unrelated taps.
- Status dot on the pill only when latency > 35ms (warning) or reconnecting (danger); details in panel.
- Open: tap pill. 3-finger tap is OFF by default (opt-in in Settings) to avoid clashing with Mac apps.
- Expanded panel `widthIn(min=280dp, max=420dp)`, opens from the pill's edge:
  1. Metrics: FPS · latency ms · bitrate Mbps (tabular numerals, refresh 2×/s, NOT a live region; TalkBack reads one summary on focus). At fontScale > 1.3 stack vertically.
  2. Mode: segmented Display / Pen / Trackpad (Pen/Trackpad disabled "Coming soon"); at fontScale > 1.3 becomes vertical list.
  3. Toggle: Lock aspect ratio. 4. Toggle: Show touches.
  5. Disconnect (danger): first tap turns row into "Tap again to disconnect" for 3s; no modal.
- Close: tap outside, back gesture, or 5s idle; any tap inside the panel resets the 5s timer.
- TalkBack on (`isTouchExplorationEnabled`): all HUD timers disabled (no fade, no auto-close, Disconnect confirm stays until dismissed).
- Reconnecting overlay: 60% black scrim on last frame, "Reconnecting…" + Cancel; after 10s → S5 "Connection lost".

## Deviations from docs/03 / ADR-003 (need anh's approval at the prototype gate)
1. Stylus modifier dock deferred to Phase 2.
2. HUD: edge half-pill instead of 48dp circle; idle opacity 40% instead of 20%.
3. 3-finger tap to open HUD is opt-in (off by default).
4. primary #5B8CFF → #7AA2FF (6.6:1 → 8.4:1); danger #FF8080; new `border` #5A6380.
5. No mode picker on connect (auto Extended Display).

## Prototype deliverables
Tablet landscape: S1, S1 setup-guide sheet, S3, S5a, S5c, S5d, S5e, stream + HUD collapsed, HUD expanded, HUD warning dot, reconnecting overlay, HUD expanded at 200% font. Phone portrait: S1, S5e. Show one device with gesture-nav + camera cutout.
