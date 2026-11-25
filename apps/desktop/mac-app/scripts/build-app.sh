#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PACKAGE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_ROOT="$(cd "$PACKAGE_DIR/../../.." && pwd)"

echo "▶ Building wingpaned (Rust daemon + Swift bridge)…"
(cd "$REPO_ROOT" && cargo build -p wingpaned --release)

cd "$PACKAGE_DIR"
echo "▶ Building menu bar app…"
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
if [ ! -x "$DAEMON_BIN" ]; then
    echo "✗ $DAEMON_BIN not found" >&2; exit 1
fi
cp "$DAEMON_BIN" "$RESOURCES_DIR/wingpaned"
chmod +x "$RESOURCES_DIR/wingpaned"

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

# Sign the nested daemon first, then the bundle. A stable identity keeps the macOS
# Screen Recording / Accessibility grants across rebuilds (ad-hoc re-prompts every build).
# Override with SIGN_ID=<sha1 or name>; falls back to ad-hoc ("-") if the identity is missing.
SIGN_ID="${SIGN_ID:-88072D8B9AD80A9236B591484514851F88DB5F87}"
if ! security find-identity -v -p codesigning | grep -q "$SIGN_ID"; then
    echo "⚠ signing identity $SIGN_ID not found, using ad-hoc" >&2
    SIGN_ID="-"
fi
codesign --force -s "$SIGN_ID" "$RESOURCES_DIR/wingpaned"
codesign --force -s "$SIGN_ID" "$APP_BUNDLE"
codesign --verify --strict --deep "$APP_BUNDLE"
echo "✔ $APP_BUNDLE"

if [ "${1:-}" = "--install" ]; then
    rm -rf /Applications/Wingpane.app
    cp -R "$APP_BUNDLE" /Applications/Wingpane.app
    echo "✔ Installed /Applications/Wingpane.app"
fi
