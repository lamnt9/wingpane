import CoreMedia
import CoreVideo
import Foundation
import ScreenCaptureKit
import VideoToolbox
import Darwin

public struct EncodedFrame: Sendable {
    public let annexB: Data
    public let isKeyframe: Bool
    public let ptsUs: Int64
    public let encodeLatencyUs: Int64
}

public enum CaptureError: Error, CustomStringConvertible {
    case displayMissing, session(OSStatus), stream(String)
    public var description: String {
        switch self { case .displayMissing: return "ScreenCaptureKit display not found"; case .session(let s): return "VTCompressionSession error \(s)"; case .stream(let s): return s }
    }
}

public final class CaptureEncoder: NSObject, SCStreamOutput, @unchecked Sendable {
    private let displayID: CGDirectDisplayID
    private let width: Int
    private let height: Int
    private let hz: Double
    private let bitrate: Int
    private var stream: SCStream?
    private var session: VTCompressionSession?
    private var callback: (@Sendable (EncodedFrame) -> Void)?
    private let keyframeLock = NSLock()
    private var wantsKeyframe = false
    private let captureCountLock = NSLock()
    private var capturedFrames = 0
    public var framesCaptured: Int { captureCountLock.withLock { capturedFrames } }
    public private(set) var lowLatencyRateControlAccepted = false

    public init(displayID: CGDirectDisplayID, width: Int, height: Int, hz: Double, bitrate: Int = 20_000_000) {
        self.displayID = displayID; self.width = width; self.height = height; self.hz = hz; self.bitrate = bitrate
    }

    public func start(onFrame: @escaping @Sendable (EncodedFrame) -> Void) async throws {
        callback = onFrame
        let content = try await SCShareableContent.excludingDesktopWindows(false, onScreenWindowsOnly: false)
        let found = content.displays.first { $0.displayID == displayID }
        guard let display = found else { throw CaptureError.displayMissing }
        let spec: [CFString: Any] = [kVTVideoEncoderSpecification_EnableLowLatencyRateControl: true]
        let status = VTCompressionSessionCreate(allocator: kCFAllocatorDefault, width: Int32(width), height: Int32(height), codecType: kCMVideoCodecType_H264, encoderSpecification: spec as CFDictionary, imageBufferAttributes: nil, compressedDataAllocator: nil, outputCallback: { ref, sourceRef, status, _, sample in
            // Balance passRetained from encode() on every path, including encoder errors.
            let startedAtUs = sourceRef.map { Unmanaged<NSNumber>.fromOpaque($0).takeRetainedValue().int64Value }
            guard status == noErr, let sample, let owner = ref.map({ Unmanaged<CaptureEncoder>.fromOpaque($0).takeUnretainedValue() }) else { return }
            owner.consume(sample, startedAtUs: startedAtUs ?? Int64(DispatchTime.now().uptimeNanoseconds / 1000))
        }, refcon: Unmanaged.passUnretained(self).toOpaque(), compressionSessionOut: &session)
        guard status == noErr, let session else { throw CaptureError.session(status) }
        // Session creation succeeded WITH the low-latency spec; VideoToolbox offers no read-back to prove it is active.
        lowLatencyRateControlAccepted = true
        VTSessionSetProperty(session, key: kVTCompressionPropertyKey_RealTime, value: kCFBooleanTrue)
        VTSessionSetProperty(session, key: kVTCompressionPropertyKey_AllowFrameReordering, value: kCFBooleanFalse)
        VTSessionSetProperty(session, key: kVTCompressionPropertyKey_ProfileLevel, value: kVTProfileLevel_H264_Main_AutoLevel)
        VTSessionSetProperty(session, key: kVTCompressionPropertyKey_AverageBitRate, value: bitrate as CFNumber)
        VTSessionSetProperty(session, key: kVTCompressionPropertyKey_MaxKeyFrameIntervalDuration, value: 2.0 as CFNumber)
        VTSessionSetProperty(session, key: kVTCompressionPropertyKey_PrioritizeEncodingSpeedOverQuality, value: kCFBooleanTrue)
        VTCompressionSessionPrepareToEncodeFrames(session)

        let config = SCStreamConfiguration()
        config.width = width; config.height = height; config.minimumFrameInterval = CMTime(seconds: 1 / hz, preferredTimescale: 1_000_000_000); config.queueDepth = 3
        config.pixelFormat = kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange; config.showsCursor = true
        let capture = SCContentFilter(display: display, excludingWindows: [])
        let stream = SCStream(filter: capture, configuration: config, delegate: nil)
        try stream.addStreamOutput(self, type: .screen, sampleHandlerQueue: DispatchQueue(label: "wingpane.capture.frames"))
        self.stream = stream
        try await stream.startCapture()
    }

    public func stop() async {
        if let stream { try? await stream.stopCapture() }
        self.stream = nil
        if let session { VTCompressionSessionCompleteFrames(session, untilPresentationTimeStamp: .invalid); VTCompressionSessionInvalidate(session) }
        session = nil
    }

    public func forceKeyframe() {
        keyframeLock.lock(); wantsKeyframe = true; keyframeLock.unlock()
    }

    public func stream(_ stream: SCStream, didOutputSampleBuffer sampleBuffer: CMSampleBuffer, of type: SCStreamOutputType) {
        guard type == .screen, let image = CMSampleBufferGetImageBuffer(sampleBuffer), let session else { return }
        captureCountLock.withLock { capturedFrames += 1 }
        let pts = CMSampleBufferGetPresentationTimeStamp(sampleBuffer)
        let startedAt = NSNumber(value: DispatchTime.now().uptimeNanoseconds / 1000)
        keyframeLock.lock(); let keyframe = wantsKeyframe; wantsKeyframe = false; keyframeLock.unlock()
        let properties: CFDictionary? = keyframe ? [kVTEncodeFrameOptionKey_ForceKeyFrame: true] as CFDictionary : nil
        VTCompressionSessionEncodeFrame(session, imageBuffer: image, presentationTimeStamp: pts, duration: .invalid, frameProperties: properties, sourceFrameRefcon: Unmanaged.passRetained(startedAt).toOpaque(), infoFlagsOut: nil)
    }

    private func consume(_ sample: CMSampleBuffer, startedAtUs: Int64) {
        guard let block = CMSampleBufferGetDataBuffer(sample) else { return }
        // Copy out so a non-contiguous block buffer can never be read past its first segment.
        let length = CMBlockBufferGetDataLength(block)
        var avcc = [UInt8](repeating: 0, count: length)
        guard length > 0, CMBlockBufferCopyDataBytes(block, atOffset: 0, dataLength: length, destination: &avcc) == kCMBlockBufferNoErr else { return }
        let key = (CMSampleBufferGetSampleAttachmentsArray(sample, createIfNecessary: false) as? [[CFString: Any]])?.first?[kCMSampleAttachmentKey_NotSync] as? Bool != true
        var output = Data()
        if key, let desc = CMSampleBufferGetFormatDescription(sample) {
            for index in 0..<2 {
                var size = 0; var bytes: UnsafePointer<UInt8>?; var count = 0
                if CMVideoFormatDescriptionGetH264ParameterSetAtIndex(desc, parameterSetIndex: index, parameterSetPointerOut: &bytes, parameterSetSizeOut: &size, parameterSetCountOut: &count, nalUnitHeaderLengthOut: nil) == noErr, let bytes { output.append(contentsOf: [0,0,0,1]); output.append(bytes, count: size) }
            }
        }
        let raw = avcc; var cursor = 0
        while cursor + 4 <= length {
            let n = raw[cursor..<cursor+4].reduce(UInt32(0)) { ($0 << 8) | UInt32($1) }; cursor += 4
            guard n > 0, cursor + Int(n) <= length else { break }
            output.append(contentsOf: [0,0,0,1]); output.append(contentsOf: raw[cursor..<cursor+Int(n)]); cursor += Int(n)
        }
        let now = Int64(DispatchTime.now().uptimeNanoseconds / 1000)
        let presentationTime = CMSampleBufferGetPresentationTimeStamp(sample)
        var timebase = mach_timebase_info_data_t()
        mach_timebase_info(&timebase)
        let ptsTicks = presentationTime.isValid ? CMClockConvertHostTimeToSystemUnits(presentationTime) : 0
        let ptsUs = presentationTime.isValid ? Int64(ptsTicks &* UInt64(timebase.numer) / UInt64(timebase.denom) / 1_000) : startedAtUs
        callback?(EncodedFrame(annexB: output, isKeyframe: key, ptsUs: ptsUs, encodeLatencyUs: now - startedAtUs))
    }
}
