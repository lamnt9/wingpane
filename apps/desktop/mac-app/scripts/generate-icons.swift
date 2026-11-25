#!/usr/bin/swift
import AppKit
import CoreGraphics
import ImageIO
import UniformTypeIdentifiers

let script = URL(fileURLWithPath: #filePath)
let repo = script.deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
let iconset = script.deletingLastPathComponent().appendingPathComponent("Wingpane.iconset")
try? FileManager.default.removeItem(at: iconset)
try FileManager.default.createDirectory(at: iconset, withIntermediateDirectories: true)

func color(_ hex: UInt32) -> CGColor {
    CGColor(red: CGFloat((hex >> 16) & 255) / 255, green: CGFloat((hex >> 8) & 255) / 255, blue: CGFloat(hex & 255) / 255, alpha: 1)
}

func polygon(_ ctx: CGContext, _ pts: [(CGFloat, CGFloat)], fill: CGColor) {
    ctx.beginPath()
    ctx.move(to: CGPoint(x: pts[0].0, y: pts[0].1))
    for p in pts.dropFirst() { ctx.addLine(to: CGPoint(x: p.0, y: p.1)) }
    ctx.closePath()
    ctx.setFillColor(fill)
    ctx.fillPath()
}

func draw(_ size: Int) -> CGImage {
    let s = CGFloat(size)
    let space = CGColorSpace(name: CGColorSpace.sRGB)!
    let ctx = CGContext(data: nil, width: size, height: size, bitsPerComponent: 8, bytesPerRow: size * 4, space: space, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
    ctx.scaleBy(x: s / 512, y: s / 512)
    // Soft navy rounded tile, with a bright display pane as the central anchor.
    ctx.setFillColor(color(0x0A0E27)); ctx.addPath(CGPath(roundedRect: CGRect(x: 34, y: 34, width: 444, height: 444), cornerWidth: 100, cornerHeight: 100, transform: nil)); ctx.fillPath()
    ctx.setStrokeColor(color(0x7AA2FF)); ctx.setLineWidth(18); ctx.setLineCap(.round); ctx.setLineJoin(.round)
    ctx.stroke(CGRect(x: 154, y: 122, width: 260, height: 218))
    // Wing/eagle sweep rises from the screen's left edge toward the upper right.
    polygon(ctx, [(74, 329), (122, 292), (164, 258), (202, 220), (242, 184), (286, 151), (325, 133), (302, 172), (267, 204), (232, 236), (199, 271), (163, 306), (123, 336)], fill: color(0x3DDC97))
    polygon(ctx, [(104, 356), (149, 323), (187, 291), (222, 258), (253, 228), (276, 210), (254, 250), (221, 286), (184, 321), (146, 350)], fill: color(0x7AA2FF))
    return ctx.makeImage()!
}

let sizes: [(String, Int)] = [("icon_16x16.png",16),("icon_16x16@2x.png",32),("icon_32x32.png",32),("icon_32x32@2x.png",64),("icon_128x128.png",128),("icon_128x128@2x.png",256),("icon_256x256.png",256),("icon_256x256@2x.png",512),("icon_512x512.png",512),("icon_512x512@2x.png",1024)]
for (name, size) in sizes {
    let destination = CGImageDestinationCreateWithURL(iconset.appendingPathComponent(name) as CFURL, UTType.png.identifier as CFString, 1, nil)!
    CGImageDestinationAddImage(destination, draw(size), nil)
    precondition(CGImageDestinationFinalize(destination))
}
let android = repo.appendingPathComponent("apps/android/app/src/main/res/drawable/ic_wingpane_launcher_foreground.xml")
let vector = #"""
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="512" android:viewportHeight="512">
    <group android:scaleX="0.85" android:scaleY="0.85" android:pivotX="256" android:pivotY="256">
    <path android:strokeColor="#7AA2FF" android:strokeWidth="18" android:strokeLineCap="round" android:strokeLineJoin="round" android:fillColor="#00000000" android:pathData="M154,122h260v218h-260z" />
    <path android:fillColor="#3DDC97" android:pathData="M74,329 L122,292 L164,258 L202,220 L242,184 L286,151 L325,133 L302,172 L267,204 L232,236 L199,271 L163,306 L123,336z" />
    <path android:fillColor="#7AA2FF" android:pathData="M104,356 L149,323 L187,291 L222,258 L253,228 L276,210 L254,250 L221,286 L184,321 L146,350z" />
    </group>
</vector>
"""#
try vector.write(to: android, atomically: true, encoding: .utf8)
let icns = script.deletingLastPathComponent().appendingPathComponent("AppIcon.icns")
let process = Process()
process.executableURL = URL(fileURLWithPath: "/usr/bin/iconutil")
process.arguments = ["-c", "icns", iconset.path, "-o", icns.path]
try process.run()
process.waitUntilExit()
if process.terminationStatus != 0 {
    // Keep the generated icon usable on systems where iconutil rejects a PNG variant.
    let fallback = Process()
    fallback.executableURL = URL(fileURLWithPath: "/usr/bin/sips")
    fallback.arguments = ["-s", "format", "icns", iconset.appendingPathComponent("icon_512x512.png").path, "--out", icns.path]
    try fallback.run()
    fallback.waitUntilExit()
    precondition(fallback.terminationStatus == 0, "Could not create AppIcon.icns")
}
print("Generated \(icns.path), iconset, and Android foreground vector")
