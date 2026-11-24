//! Core business logic, session handling, and input math for Wingpane.

pub mod host;
pub mod pen;
pub mod session;
pub mod transport;

#[cfg(test)]
mod tests {
    use crate::{
        pen::apply_pressure_curve,
        session::{Session, SessionState},
    };
    use wingpane_proto::{ControlMessage, Mode, RejectReason, VideoCodec, PROTO_VERSION};

    fn hello(version: u16, codecs: Vec<VideoCodec>) -> ControlMessage {
        ControlMessage::Hello {
            version,
            device_name: "Tab".into(),
            screen_size_px: (2560, 1600),
            supported_codecs: codecs,
            refresh_rate_hz: 120,
        }
    }

    #[test]
    fn pressure_curve_is_monotonic_on_steep_curve() {
        let curve = [0.9, 0.0, 0.1, 1.0];
        let mut last = 0.0;
        for i in 0..=1000 {
            let v = apply_pressure_curve(i as f32 / 1000.0, &curve);
            assert!(v + 1e-3 >= last, "dropped at {i}: {last} -> {v}");
            last = v;
        }
    }

    #[test]
    fn pressure_curve_handles_nan() {
        assert_eq!(
            apply_pressure_curve(f32::NAN, &[0.25, 0.25, 0.75, 0.75]),
            0.0
        );
    }

    #[test]
    fn set_mode_requires_handshake() {
        let mut s = Session::new();
        assert_eq!(
            s.handle_message(ControlMessage::SetMode(Mode::GraphicsTablet)),
            None
        );
        assert_eq!(s.state, SessionState::Disconnected);
    }

    #[test]
    fn invalid_hello_is_rejected_with_reason_and_closes() {
        let mut s = Session::new();
        let reply = s.handle_message(hello(99, vec![VideoCodec::H264])).unwrap();
        assert!(reply.close);
        assert_eq!(
            reply.send,
            ControlMessage::Reject(RejectReason::VersionMismatch {
                host_version: PROTO_VERSION
            })
        );
    }

    #[test]
    fn missing_codec_and_bad_dimensions_are_rejected() {
        let mut s = Session::new();
        assert_eq!(
            s.handle_message(hello(PROTO_VERSION, vec![VideoCodec::Hevc]))
                .unwrap()
                .send,
            ControlMessage::Reject(RejectReason::UnsupportedCodec)
        );
        let mut bad = hello(PROTO_VERSION, vec![VideoCodec::H264]);
        if let ControlMessage::Hello { screen_size_px, .. } = &mut bad {
            *screen_size_px = (0, 10);
        }
        assert_eq!(
            s.handle_message(bad).unwrap().send,
            ControlMessage::Reject(RejectReason::InvalidHello)
        );
    }

    #[test]
    fn hello_while_active_is_busy() {
        let mut s = Session::new();
        s.handle_message(hello(PROTO_VERSION, vec![VideoCodec::H264]));
        let reply = s
            .handle_message(hello(PROTO_VERSION, vec![VideoCodec::H264]))
            .unwrap();
        assert!(reply.close);
        assert_eq!(reply.send, ControlMessage::Reject(RejectReason::Busy));
    }

    #[test]
    fn accept_uses_client_screen_and_caps_refresh_rate() {
        let mut s = Session::new();
        let reply = s
            .handle_message(hello(PROTO_VERSION, vec![VideoCodec::H264]))
            .unwrap();
        assert!(matches!(reply.send,
            ControlMessage::Accept { virtual_display_spec: Some((2560, 1600, hz)), .. } if hz <= 60));
        assert!(!reply.close);
        assert_eq!(s.state, SessionState::Active(Mode::ExtendedDisplay));
    }
}
