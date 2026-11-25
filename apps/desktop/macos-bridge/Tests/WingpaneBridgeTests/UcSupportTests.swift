import XCTest
@testable import WingpaneUcSupport

final class UcSupportTests: XCTestCase {
    func testHotkeyRequiresEscapeControlAndOption() {
        XCTAssertTrue(UcHotkey.matches(keycode: 0x35, flags: (1 << 18) | (1 << 19)))
        XCTAssertTrue(UcHotkey.matches(keycode: 0x35, flags: (1 << 18) | (1 << 19) | (1 << 16)))
        XCTAssertFalse(UcHotkey.matches(keycode: 0x35, flags: 1 << 18))
        XCTAssertFalse(UcHotkey.matches(keycode: 0x35, flags: 1 << 19))
        XCTAssertFalse(UcHotkey.matches(keycode: 0x36, flags: (1 << 18) | (1 << 19)))
    }

    func testEventRecordHasStableCLayout() {
        XCTAssertEqual(MemoryLayout<UcEventRecord>.size, 48)
        XCTAssertEqual(MemoryLayout<UcEventRecord>.offset(of: \.flags), 8)
        XCTAssertEqual(MemoryLayout<UcEventRecord>.offset(of: \.x), 16)
        XCTAssertEqual(MemoryLayout<UcEventRecord>.offset(of: \.dy), 40)
    }

    func testQuartzFlagsMapToLeftSideNXFlags() {
        XCTAssertEqual(ucMacDeviceFlags(1 << 18), 0x0001)
        XCTAssertEqual(ucMacDeviceFlags(1 << 17), 0x0002)
        XCTAssertEqual(ucMacDeviceFlags(1 << 19), 0x0020)
        XCTAssertEqual(ucMacDeviceFlags(1 << 20), 0x0008)
        XCTAssertEqual(ucMacDeviceFlags((1 << 17) | (1 << 18) | (1 << 19) | (1 << 20)), 0x002B)
    }
}
