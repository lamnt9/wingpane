use std::{env, fs, path::PathBuf, process::Command};

fn main() {
    let root = PathBuf::from(env::var("CARGO_MANIFEST_DIR").unwrap());
    let package = root.join("../macos-bridge");
    let status = Command::new("swift")
        .args(["build", "-c", "release", "--product", "WingpaneBridge"])
        .current_dir(&package)
        .status()
        .expect("swift is required to build WingpaneBridge");
    assert!(status.success(), "Swift package build failed");
    let bin_path = Command::new("swift")
        .args(["build", "-c", "release", "--show-bin-path"])
        .current_dir(&package)
        .output()
        .expect("swift is required to locate WingpaneBridge");
    assert!(
        bin_path.status.success(),
        "swift build --show-bin-path failed"
    );
    let lib = PathBuf::from(
        String::from_utf8(bin_path.stdout)
            .expect("Swift bin path is UTF-8")
            .trim(),
    );
    // SwiftPM's static product archive contains only the product target. Its
    // dependency objects are listed separately for executable products, so
    // fold the product's exact link list into one archive for Cargo.
    let link_list = lib.join("WingpaneBridge.product/Objects.LinkFileList");
    let objects = fs::read_to_string(&link_list)
        .expect("SwiftPM did not emit the WingpaneBridge object link list");
    let archive = lib.join("libWingpaneBridgeAll.a");
    let status = Command::new("libtool")
        .arg("-static")
        .arg("-o")
        .arg(&archive)
        .args(objects.lines())
        .status()
        .expect("libtool is required to combine Swift bridge objects");
    assert!(
        status.success(),
        "libtool could not create the combined Swift archive"
    );
    println!("cargo:rustc-link-search=native={}", lib.display());
    println!("cargo:rustc-link-lib=static=WingpaneBridgeAll");
    for framework in [
        "ScreenCaptureKit",
        "VideoToolbox",
        "CoreMedia",
        "CoreVideo",
        "CoreGraphics",
        "Foundation",
        "AppKit",
    ] {
        println!("cargo:rustc-link-lib=framework={framework}");
    }
    let sdk = Command::new("xcrun")
        .args(["--show-sdk-path"])
        .output()
        .expect("xcrun required");
    assert!(sdk.status.success(), "xcrun --show-sdk-path failed");
    let sdk = String::from_utf8(sdk.stdout).expect("SDK path is UTF-8");
    println!(
        "cargo:rustc-link-search=native={}/System/Library/Frameworks",
        sdk.trim()
    );
    let toolchain = Command::new("xcode-select")
        .arg("-p")
        .output()
        .expect("xcode-select required");
    assert!(toolchain.status.success(), "xcode-select -p failed");
    let developer = String::from_utf8(toolchain.stdout).expect("developer path is UTF-8");
    let toolchain_lib =
        PathBuf::from(developer.trim()).join("Toolchains/XcodeDefault.xctoolchain/usr/lib");
    for swift_runtime in [
        toolchain_lib.join("swift/macosx"),
        toolchain_lib.join("swift-5.5/macosx"),
    ] {
        println!("cargo:rustc-link-search=native={}", swift_runtime.display());
    }
    // Use the OS-compatible runtime at load time to avoid loading a second
    // copy beside the system Swift runtime. Toolchain paths remain link paths.
    println!("cargo:rustc-link-arg=-Wl,-rpath,/usr/lib/swift");
    println!(
        "cargo:rerun-if-changed={}",
        package.join("Sources").display()
    );
    println!(
        "cargo:rerun-if-changed={}",
        package.join("Package.swift").display()
    );
}
