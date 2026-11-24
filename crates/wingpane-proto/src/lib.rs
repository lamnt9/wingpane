//! Wingpane binary communication protocol.
#![no_std]
extern crate alloc;

mod control;
mod frame;
mod input;

pub use control::{ControlMessage, Mode, VideoCodec, PROTO_VERSION};
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
}
