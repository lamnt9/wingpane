//! Wingpane binary communication protocol.
#![no_std]
extern crate alloc;

mod control;
mod frame;
mod input;

pub use control::{ControlMessage, Mode, PenTarget, RejectReason, VideoCodec, PROTO_VERSION};
pub use frame::{encode_frame, FrameDecoder, FrameError, Packet, VideoPacket, MAX_FRAME_LEN};
pub use input::{GestureKind, InputEvent, InputPacket, TouchPhase};

#[cfg(test)]
mod tests {
    use super::*;
    use alloc::{string::String, vec, vec::Vec};
    fn roundtrip(packet: Packet) {
        let mut frame = Vec::new();
        encode_frame(&packet, &mut frame).unwrap();
        let mut decoder = FrameDecoder::new();
        decoder.push(&frame);
        assert_eq!(decoder.next_packet().unwrap(), Some(packet));
        assert_eq!(decoder.next_packet().unwrap(), None);
    }
    #[test]
    fn packet_variants_roundtrip() {
        let controls = vec![
            ControlMessage::Hello {
                version: PROTO_VERSION,
                device_name: String::from("device"),
                screen_size_px: (2560, 1600),
                supported_codecs: vec![VideoCodec::H264, VideoCodec::Hevc],
                refresh_rate_hz: 120,
            },
            ControlMessage::Accept {
                selected_mode: Mode::GraphicsTablet,
                virtual_display_spec: Some((1920, 1080, 60)),
                selected_codec: VideoCodec::Hevc,
                bitrate_kbps: 9000,
            },
            ControlMessage::SetMode(Mode::TrackpadKeyboard),
            ControlMessage::RequestKeyframe,
            ControlMessage::Ping(1),
            ControlMessage::Pong(2),
            ControlMessage::Disconnect,
            ControlMessage::Reject(RejectReason::VersionMismatch { host_version: 1 }),
            ControlMessage::Reject(RejectReason::UnsupportedCodec),
            ControlMessage::Reject(RejectReason::InvalidHello),
            ControlMessage::Reject(RejectReason::Busy),
            ControlMessage::Reject(RejectReason::ScreenRecordingDenied),
            ControlMessage::Capabilities { features: 0x45 },
            ControlMessage::PenConfig {
                pressure_curve: [0.25, 0.25, 0.75, 0.75],
                target: PenTarget::MainDisplay,
            },
        ];
        for c in controls {
            roundtrip(Packet::Control(c));
        }
        roundtrip(Packet::Video(VideoPacket {
            frame_seq: 3,
            pts_us: 4,
            is_keyframe: true,
            payload: vec![1, 2, 3],
        }));
        let events = vec![
            InputEvent::Pen {
                x: 0.1,
                y: 0.2,
                pressure: 0.5,
                tilt_x: -1,
                tilt_y: 2,
                is_down: true,
                is_hover: false,
                button_mask: 3,
                timestamp_us: 4,
            },
            InputEvent::Touch {
                pointer_id: 1,
                x: 0.2,
                y: 0.3,
                phase: TouchPhase::Cancel,
            },
            InputEvent::PointerDelta { dx: 1.0, dy: -1.0 },
            InputEvent::Scroll { dx: 2.0, dy: 3.0 },
            InputEvent::Gesture(GestureKind::PinchZoom(1.5)),
            InputEvent::Keyboard {
                usb_hid_usage: 4,
                is_down: true,
                modifiers: 2,
            },
            InputEvent::MouseButton {
                button: 0,
                is_down: true,
            },
            InputEvent::Text(String::from("Tiếng Việt 😀")),
        ];
        for e in events {
            roundtrip(Packet::Input(InputPacket {
                seq: 5,
                ts_us: 6,
                event: e,
            }));
        }
    }
    #[test]
    fn decoder_handles_coalesced_and_fragmented_frames() {
        let p = Packet::Control(ControlMessage::Ping(1));
        let mut frame = Vec::new();
        encode_frame(&p, &mut frame).unwrap();
        let mut both = frame.clone();
        both.extend_from_slice(&frame);
        let mut d = FrameDecoder::new();
        d.push(&both);
        assert_eq!(d.next_packet().unwrap(), Some(p.clone()));
        assert_eq!(d.next_packet().unwrap(), Some(p.clone()));
        let mut d = FrameDecoder::new();
        for b in &frame {
            d.push(core::slice::from_ref(b));
        }
        assert_eq!(d.next_packet().unwrap(), Some(p));
    }
    #[test]
    fn rejects_empty_and_oversized_lengths() {
        let mut d = FrameDecoder::new();
        d.push(&[0, 0, 0, 0]);
        assert_eq!(d.next_packet(), Err(FrameError::EmptyFrame));
        let mut d = FrameDecoder::new();
        d.push(&(MAX_FRAME_LEN + 1).to_le_bytes());
        assert_eq!(
            d.next_packet(),
            Err(FrameError::FrameTooLarge(MAX_FRAME_LEN + 1))
        );
    }
    #[test]
    fn deterministic_arbitrary_bytes_never_panic() {
        let mut state = 0x1234_5678u32;
        for _ in 0..10_000 {
            state = state.wrapping_mul(1664525).wrapping_add(1013904223);
            let n = (state as usize) % 128;
            let mut bytes = Vec::with_capacity(n);
            for _ in 0..n {
                state = state.wrapping_mul(1664525).wrapping_add(1013904223);
                bytes.push((state >> 24) as u8);
            }
            let mut d = FrameDecoder::new();
            d.push(&bytes);
            for _ in 0..64 {
                if !matches!(d.next_packet(), Ok(Some(_))) {
                    break;
                }
            }
        }
    }
    #[test]
    fn ping_golden_wire_bytes() {
        let mut bytes = Vec::new();
        encode_frame(&Packet::Control(ControlMessage::Ping(1)), &mut bytes).unwrap();
        assert_eq!(bytes, [0x03, 0x00, 0x00, 0x00, 0x00, 0x04, 0x01]);
    }
    #[test]
    fn reject_version_mismatch_golden_wire_bytes() {
        let mut bytes = Vec::new();
        encode_frame(
            &Packet::Control(ControlMessage::Reject(RejectReason::VersionMismatch {
                host_version: 1,
            })),
            &mut bytes,
        )
        .unwrap();
        assert_eq!(bytes, [0x04, 0, 0, 0, 0, 7, 0, 1]);
    }
    #[test]
    fn utf16_chunks_stay_within_limit_and_keep_surrogate_pairs() {
        let mut text = alloc::string::String::from("a".repeat(19));
        text.push_str("😀");
        text.push_str(&"b".repeat(25));
        let chunks = utf16_chunks(&text);
        assert!(chunks.iter().all(|chunk| chunk.len() <= 20));
        let joined: alloc::vec::Vec<u16> = chunks.iter().flatten().copied().collect();
        assert_eq!(joined, text.encode_utf16().collect::<alloc::vec::Vec<_>>());
        assert!(chunks.iter().all(|chunk| String::from_utf16(chunk).is_ok()));
    }
    #[test]
    fn text_event_rejects_payload_over_256_utf8_bytes() {
        let packet = Packet::Input(InputPacket {
            seq: 1,
            ts_us: 1,
            event: InputEvent::Text(String::from("é".repeat(129))),
        });
        assert_eq!(encode_frame(&packet, &mut Vec::new()), Err(FrameError::Encode));
    }
}

/// Canonical packet set used by the checked-in Kotlin interoperability vectors.
#[doc(hidden)]
pub fn golden_packets() -> alloc::vec::Vec<(&'static str, Packet)> {
    use alloc::{string::String, vec, vec::Vec};
    let controls = vec![
        (
            "control_hello",
            ControlMessage::Hello {
                version: PROTO_VERSION,
                device_name: String::from("Wingpane"),
                screen_size_px: (2560, 1600),
                supported_codecs: vec![VideoCodec::H264, VideoCodec::Hevc],
                refresh_rate_hz: 120,
            },
        ),
        (
            "control_accept",
            ControlMessage::Accept {
                selected_mode: Mode::GraphicsTablet,
                virtual_display_spec: Some((1920, 1080, 60)),
                selected_codec: VideoCodec::H264,
                bitrate_kbps: 12000,
            },
        ),
        (
            "control_set_mode",
            ControlMessage::SetMode(Mode::TrackpadKeyboard),
        ),
        ("control_request_keyframe", ControlMessage::RequestKeyframe),
        ("control_ping", ControlMessage::Ping(0x0102_0304_0506_0708)),
        ("control_pong", ControlMessage::Pong(0x0807_0605_0403_0201)),
        ("control_disconnect", ControlMessage::Disconnect),
        (
            "reject_version_mismatch",
            ControlMessage::Reject(RejectReason::VersionMismatch { host_version: 1 }),
        ),
        (
            "reject_unsupported_codec",
            ControlMessage::Reject(RejectReason::UnsupportedCodec),
        ),
        (
            "reject_invalid_hello",
            ControlMessage::Reject(RejectReason::InvalidHello),
        ),
        ("reject_busy", ControlMessage::Reject(RejectReason::Busy)),
    ];
    let mut controls = controls;
    controls.push((
        "control_host_time",
        ControlMessage::HostTime {
            echo_ns: 0x1020_3040_5060_7080,
            host_us: 0x2010_2030_4050_6070,
        },
    ));
    controls.push((
        "control_capabilities",
        ControlMessage::Capabilities { features: 0x4f },
    ));
    controls.push((
        "control_pen_config",
        ControlMessage::PenConfig {
            pressure_curve: [0.25, 0.25, 0.75, 0.75],
            target: PenTarget::MainDisplay,
        },
    ));
    let mut packets: Vec<(&'static str, Packet)> = controls
        .into_iter()
        .map(|(n, c)| (n, Packet::Control(c)))
        .collect();
    packets.push((
        "video_keyframe",
        Packet::Video(VideoPacket {
            frame_seq: 300,
            pts_us: 5_000_000_123,
            is_keyframe: true,
            payload: vec![0, 0, 0, 1, 0x67],
        }),
    ));
    let events = vec![
        (
            "input_pen",
            InputEvent::Pen {
                x: 0.125,
                y: 0.875,
                pressure: 0.75,
                tilt_x: -37,
                tilt_y: 22,
                is_down: true,
                is_hover: false,
                button_mask: 5,
                timestamp_us: 5_000_000_123,
            },
        ),
        (
            "input_touch",
            InputEvent::Touch {
                pointer_id: 7,
                x: 0.25,
                y: 0.625,
                phase: TouchPhase::Move,
            },
        ),
        (
            "input_pointer_delta",
            InputEvent::PointerDelta {
                dx: -12.5,
                dy: 3.25,
            },
        ),
        ("input_scroll", InputEvent::Scroll { dx: 1.5, dy: -2.75 }),
        (
            "input_gesture",
            InputEvent::Gesture(GestureKind::PinchZoom(1.25)),
        ),
        (
            "input_keyboard",
            InputEvent::Keyboard {
                usb_hid_usage: 0x1234,
                is_down: true,
                modifiers: 5,
            },
        ),
        (
            "input_mouse_button",
            InputEvent::MouseButton {
                button: 0,
                is_down: true,
            },
        ),
        ("input_text", InputEvent::Text(String::from("Tiếng Việt 😀"))),
    ];
    for (name, event) in events {
        packets.push((
            name,
            Packet::Input(InputPacket {
                seq: 300,
                ts_us: 5_000_000_123,
                event,
            }),
        ));
    }
    packets
}

/// Split text into CoreGraphics-sized UTF-16 chunks without separating a
/// surrogate pair. Each returned chunk contains at most 20 code units.
pub fn utf16_chunks(text: &str) -> alloc::vec::Vec<alloc::vec::Vec<u16>> {
    let mut chunks = alloc::vec::Vec::new();
    let mut current = alloc::vec::Vec::with_capacity(20);
    for character in text.chars() {
        let mut encoded = [0u16; 2];
        let units = character.encode_utf16(&mut encoded);
        if current.len() + units.len() > 20 {
            chunks.push(core::mem::take(&mut current));
            current = alloc::vec::Vec::with_capacity(20);
        }
        current.extend_from_slice(units);
    }
    if !current.is_empty() { chunks.push(current); }
    chunks
}

#[cfg(test)]
mod golden_test {
    use super::*;
    use alloc::{string::String, vec::Vec};
    #[test]
    fn golden_vectors_are_current() {
        let mut actual = String::new();
        for (name, packet) in golden_packets() {
            let mut bytes = Vec::new();
            encode_frame(&packet, &mut bytes).unwrap();
            actual.push_str(name);
            actual.push(' ');
            for byte in bytes {
                use core::fmt::Write;
                write!(&mut actual, "{byte:02x}").unwrap();
            }
            actual.push('\n');
        }
        assert_eq!(actual, include_str!("../golden/packets.txt"));
    }
}
