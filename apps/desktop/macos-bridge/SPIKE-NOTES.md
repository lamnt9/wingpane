# CGVirtualDisplay spike notes

## Current result

Verified on macOS 26.6 (build 25G72) with Swift 6.2.4: release build succeeds with no warnings; the final CLI run creates a display at 2560 × 1600, 60 Hz, and `CGGetOnlineDisplayList` rises from 1 to 2, then returns to 1 after teardown. For `--hidpi --width 1280 --height 800`, the descriptor now advertises 2560 × 1600 maximum pixels; a live `wp-vdisplay` run reported `2560x1600` backing pixels. The private classes are declared in Objective-C headers and imported by `WingpaneDisplayKit`. A selector inventory made with the local Objective-C runtime showed that `hiDPI` belongs on settings, not on the descriptor.

## Display geometry

The descriptor advertises a physical size of **600 × 375 mm** (16:10). This is a fixed engineering choice for the spike, not a measured monitor. At 2560 × 1600, that geometry corresponds to approximately **108.4 PPI**. The CLI reports the mode returned by CoreGraphics so the requested and applied modes can be compared.

## Permissions and entitlements

The local standalone CLI succeeded without a configured entitlement or an interactive privacy permission prompt. This only verifies this machine and execution context; signing, sandboxed distribution, and other macOS releases remain unverified.

## macOS caveats and limits

- The API is private and can change or disappear between macOS releases.
- This package targets macOS 13 or later. Behavior on earlier supported macOS versions is unknown; only macOS 26.6 was exercised.
- Creation and settings application are checked through the private API; display count is sampled with `CGGetOnlineDisplayList` before/after creation and polled for up to two seconds after teardown.
- The C ABI registry retains displays created through `wp_vd_create` until `wp_vd_destroy` is called. A process must not lose the returned ID before destroying it.
- B2 adds `WingpaneCaptureKit` using ScreenCaptureKit and VideoToolbox. A 10-second live run produced an Annex-B H.264 Main stream at 2560 × 1600 with `has_b_frames=0`; ScreenCaptureKit delivered 579 frames and the encoder emitted 579 frames (57.9 fps). Encode latency was p50 9.475 ms, p95 9.992 ms, max 42.849 ms. VideoToolbox accepted the requested low-latency rate-control session option. This run did not establish behavior on other macOS releases or machines.
- No Rust integration, hot-plug recovery, or multi-display policy is included. `forceKeyframe()` maps to VideoToolbox's per-frame force-keyframe option.
- Screen recording was available to the terminal during the B2 live run; the missing-permission path is implemented but was not exercised.
