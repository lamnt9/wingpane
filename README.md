# 🦅 Wingpane

> **Transform your Android tablet or phone into a high-performance secondary display, pressure-sensitive graphics tablet, and precision trackpad/keyboard for macOS, Windows, and Linux.**

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Protocol Version](https://img.shields.io/badge/proto-v1-green.svg)](docs/02-protocol.md)
[![Rust](https://img.shields.io/badge/rust-1.75%2B-orange.svg)](https://www.rust-lang.org/)
[![Kotlin](https://img.shields.io/badge/kotlin-1.9%2B-purple.svg)](https://kotlinlang.org/)

---

## ⚡ 3-in-1 Modes

1. **Extended Virtual Display**: Low-latency video streaming (< 20ms glass-to-glass over USB cable, 60–120 FPS) turning your tablet into a real secondary desktop monitor.
2. **Digital Graphics Tablet**: Pressure-sensitive stylus input (S-Pen / Stylus) with tilt detection and custom pressure curves for Photoshop, Krita, Clip Studio, and Blender.
3. **Standalone Trackpad & Keyboard**: Full gesture trackpad (Mac & Windows gestures) + custom on-screen keyboard & numpad. Works over USB and native Bluetooth HID without desktop software.

---

## 🏗️ Architecture & Monorepo Layout

```
wingpane/
├── Cargo.toml                     # Cargo workspace root
├── crates/
│   ├── wingpane-proto/            # Shared binary packet schema (Postcard)
│   └── wingpane-core/             # Session manager, transport (USB/QUIC), input curves
├── apps/
│   ├── desktop/                   # Desktop Host (Tauri 2 + Rust)
│   │   ├── src-tauri/             # Virtual display (macOS CGVirtualDisplay, Windows IddCx, Linux Wayland)
│   │   └── ui/                    # Desktop companion tray & settings UI
│   └── android/                   # Android Client (Kotlin + Jetpack Compose)
│       └── app/src/main/          # Hardware MediaCodec SurfaceView + S-Pen engine
├── design-system/wingpane/        # UI/UX Pro Max tokens (Dark Mode OLED #000000, #0A0E27)
└── docs/                          # Engineering & Architectural Specifications
```

---

## 🎨 Design System & UI/UX

Wingpane interfaces follow the **UI/UX Pro Max** guidelines:
* **Style:** OLED-optimized dark theme (`#000000`, `#0A0E27`, `#121212`) for battery conservation on AMOLED screens.
* **Typography:** `Outfit` (Headings) + `Work Sans` (Body).
* **Accessibility:** Minimum touch target **48×48dp**, contrast ratio **7:1+**, full TalkBack support.

---

## 📖 Documentation

* [00 - Overview & Objectives](docs/00-overview.md)
* [01 - Monorepo & System Architecture](docs/01-architecture.md)
* [02 - Binary Framing & Transport Protocol](docs/02-protocol.md)
* [03 - UI/UX 3-Mode Specification](docs/03-ui-ux-spec.md)
* [04 - Engineering Roadmap & QC Gates](docs/04-roadmap.md)
* [Decisions Log (ADR)](docs/decisions.md)

---

## 🚀 Quick Start (Development)

### Prerequisites
* Rust 1.75+ (`rustup default stable`)
* Android Studio Iguana+ / NDK 26+
* Node.js 20+ (for Tauri desktop companion UI)
* `adb` installed and present in PATH

### Clone & Build
```bash
git clone https://github.com/lamnt9/wingpane.git
cd wingpane

# Build Rust crates
cargo check --workspace
```

---

## 📜 License

MIT License. Designed & developed with love by **Lam Nguyen** and autonomous AI agents.
