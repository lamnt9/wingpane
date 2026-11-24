//! Core business logic, session handling, and input math for Wingpane.

pub mod pen {
    /// Applies a cubic Bézier curve to stylus pressure values.
    ///
    /// Controls: `[x1, y1, x2, y2]` where P0 = (0,0) and P3 = (1,1).
    pub fn apply_pressure_curve(pressure: f32, curve: &[f32; 4]) -> f32 {
        if pressure.is_nan() {
            return 0.0;
        }
        let p = pressure.clamp(0.0, 1.0);
        // x controls stay in 0..=1 so B_x is monotonic and bisection is exact.
        let (x1, y1, x2, y2) = (
            curve[0].clamp(0.0, 1.0),
            curve[1],
            curve[2].clamp(0.0, 1.0),
            curve[3],
        );
        let bezier = |t: f32, a: f32, b: f32| {
            3.0 * (1.0 - t).powi(2) * t * a + 3.0 * (1.0 - t) * t * t * b + t.powi(3)
        };

        let (mut lo, mut hi) = (0.0_f32, 1.0_f32);
        for _ in 0..24 {
            let mid = (lo + hi) / 2.0;
            if bezier(mid, x1, x2) < p {
                lo = mid;
            } else {
                hi = mid;
            }
        }

        bezier((lo + hi) / 2.0, y1, y2).clamp(0.0, 1.0)
    }
}

pub mod session {
    use wingpane_proto::{ControlMessage, Mode, VideoCodec, PROTO_VERSION};

    #[derive(Debug, Clone, Copy, PartialEq, Eq)]
    pub enum SessionState {
        Disconnected,
        Active(Mode),
    }

    pub struct Session {
        pub state: SessionState,
    }

    impl Session {
        pub fn new() -> Self {
            Self {
                state: SessionState::Disconnected,
            }
        }

        pub fn handle_message(&mut self, msg: ControlMessage) -> Option<ControlMessage> {
            match msg {
                ControlMessage::Hello {
                    version,
                    screen_size_px: (width, height),
                    supported_codecs,
                    refresh_rate_hz,
                    ..
                } if self.state == SessionState::Disconnected => {
                    // MVP host encodes H.264 only and targets 60 fps (roadmap Phase 1).
                    if version != PROTO_VERSION
                        || !supported_codecs.contains(&VideoCodec::H264)
                        || width == 0
                        || height == 0
                        || refresh_rate_hz == 0
                    {
                        return None;
                    }
                    let selected_mode = Mode::ExtendedDisplay;
                    self.state = SessionState::Active(selected_mode);
                    Some(ControlMessage::Accept {
                        selected_mode,
                        virtual_display_spec: Some((width, height, refresh_rate_hz.min(60))),
                        selected_codec: VideoCodec::H264,
                        bitrate_kbps: 20_000,
                    })
                }
                ControlMessage::SetMode(mode) if matches!(self.state, SessionState::Active(_)) => {
                    self.state = SessionState::Active(mode);
                    None
                }
                ControlMessage::Ping(timestamp) => Some(ControlMessage::Pong(timestamp)),
                ControlMessage::Disconnect => {
                    self.state = SessionState::Disconnected;
                    None
                }
                _ => None,
            }
        }
    }

    impl Default for Session {
        fn default() -> Self {
            Self::new()
        }
    }
}

#[cfg(test)]
mod tests {
    use super::pen::apply_pressure_curve;
    use super::session::{Session, SessionState};
    use wingpane_proto::{ControlMessage, Mode, VideoCodec, PROTO_VERSION};

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
        assert_eq!(apply_pressure_curve(f32::NAN, &[0.25, 0.25, 0.75, 0.75]), 0.0);
    }

    #[test]
    fn set_mode_requires_handshake() {
        let mut s = Session::new();
        s.handle_message(ControlMessage::SetMode(Mode::GraphicsTablet));
        assert_eq!(s.state, SessionState::Disconnected);
    }

    #[test]
    fn ping_gets_pong() {
        let mut s = Session::new();
        assert_eq!(s.handle_message(ControlMessage::Ping(7)), Some(ControlMessage::Pong(7)));
    }

    #[test]
    fn accept_uses_client_screen_and_activates() {
        let mut s = Session::new();
        let reply = s.handle_message(hello(PROTO_VERSION, vec![VideoCodec::H264]));
        assert!(matches!(
            reply,
            Some(ControlMessage::Accept { virtual_display_spec: Some((2560, 1600, 60)), .. })
        ));
        assert_eq!(s.state, SessionState::Active(Mode::ExtendedDisplay));
    }
}
