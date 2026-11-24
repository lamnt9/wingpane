#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PACKAGE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_ROOT="$(cd "$PACKAGE_DIR/../../.." && pwd)"

cd "$PACKAGE_DIR"

swift build -c release

BIN_DIR="$(swift build -c release --show-bin-path)"
APP_BUNDLE="$PACKAGE_DIR/build/Wingpane.app"
CONTENTS_DIR="$APP_BUNDLE/Contents"
MACOS_DIR="$CONTENTS_DIR/MacOS"
RESOURCES_DIR="$CONTENTS_DIR/Resources"

rm -rf "$APP_BUNDLE"
mkdir -p "$MACOS_DIR" "$RESOURCES_DIR"

cp "$BIN_DIR/Wingpane" "$MACOS_DIR/Wingpane"
chmod +x "$MACOS_DIR/Wingpane"

DAEMON_BIN="$REPO_ROOT/target/release/wingpaned"
if [ -f "$DAEMON_BIN" ]; then
    cp "$DAEMON_BIN" "$RESOURCES_DIR/wingpaned"
    chmod +x "$RESOURCES_DIR/wingpaned"
fi

cat > "$CONTENTS_DIR/Info.plist" <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleDevelopmentRegion</key>
    <string>en</string>
    <key>CFBundleDisplayName</key>
    <string>Wingpane</string>
    <key>CFBundleExecutable</key>
    <string>Wingpane</string>
    <key>CFBundleIdentifier</key>
    <string>io.wingpane.mac</string>
    <key>CFBundleInfoDictionaryVersion</key>
    <string>6.0</string>
    <key>CFBundleName</key>
    <string>Wingpane</string>
    <key>CFBundlePackageType</key>
    <string>APPL</string>
    <key>CFBundleShortVersionString</key>
    <string>0.1.0</string>
    <key>CFBundleVersion</key>
    <string>1</string>
    <key>LSMinimumSystemVersion</key>
    <string>14.0</string>
    <key>LSUIElement</key>
    <true/>
    <key>NSHumanReadableCopyright</key>
    <string>Copyright © 2026 Wingpane. All rights reserved.</string>
</dict>
</plist>
EOF

codesign --force --deep -s - "$APP_BUNDLE"
