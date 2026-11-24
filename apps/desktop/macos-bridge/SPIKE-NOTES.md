# CGVirtualDisplay spike notes

## Current result

Verified on macOS 26.6 (build 25G72) with Swift 6.2.4: release build succeeds with no warnings; the final CLI run creates display ID 6 at 2560 × 1600, 60 Hz, and `CGGetOnlineDisplayList` rises from 1 to 2, then returns to 1 after teardown. A separate `--hidpi` run also created a display and reported the same pixel dimensions. The private classes are declared in Objective-C headers and imported by `WingpaneDisplayKit`. A selector inventory made with the local Objective-C runtime showed that `hiDPI` belongs on settings, not on the descriptor.

## Display geometry

The descriptor advertises a physical size of **600 × 375 mm** (16:10). This is a fixed engineering choice for the spike, not a measured monitor. At 2560 × 1600, that geometry corresponds to approximately **108.4 PPI**. The CLI reports the mode returned by CoreGraphics so the requested and applied modes can be compared.

## Permissions and entitlements

The local standalone CLI succeeded without a configured entitlement or an interactive privacy permission prompt. This only verifies this machine and execution context; signing, sandboxed distribution, and other macOS releases remain unverified.

## macOS caveats and limits

- The API is private and can change or disappear between macOS releases.
- This package targets macOS 13 or later. Behavior on earlier supported macOS versions is unknown; only macOS 26.6 was exercised.
- Creation and settings application are checked through the private API; display count is sampled with `CGGetOnlineDisplayList` before/after creation and polled for up to two seconds after teardown.
- The C ABI registry retains displays created through `wp_vd_create` until `wp_vd_destroy` is called. A process must not lose the returned ID before destroying it.
- No Rust integration, capture, encoding, hot-plug recovery, or multi-display policy is included.
- Screen-recording permission requirements for future capture are unknown and were not tested here.
