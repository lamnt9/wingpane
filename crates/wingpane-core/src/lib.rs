//! Core business logic, session handling, and input math for Wingpane.

pub mod pen {
    /// Applies a cubic Bézier curve to stylus pressure values.
    ///
    /// Controls: `[x1, y1, x2, y2]` where P0 = (0,0) and P3 = (1,1).
    pub fn apply_pressure_curve(pressure: f32, curve: &[f32; 4]) -> f32 {
        let p = pressure.clamp(0.0, 1.0);
        let (x1, y1, x2, y2) = (curve[0], curve[1], curve[2], curve[3]);

        // Solve for parameter t where B_x(t) == p using Newton-Raphson
        let mut t = p;
        for _ in 0..4 {
            let x = 3.0 * (1.0 - t).powi(2) * t * x1 + 3.0 * (1.0 - t) * t * t * x2 + t.powi(3) - p;
            let dx = 3.0 * (1.0 - t).powi(2) * x1 + 6.0 * (1.0 - t) * t * (x2 - x1) + 3.0 * t * t * (1.0 - x2);
            if dx.abs() < 1e-6 {
                break;
            }
            t -= x / dx;
        }

        let t = t.clamp(0.0, 1.0);
        3.0 * (1.0 - t).powi(2) * t * y1 + 3.0 * (1.0 - t) * t * t * y2 + t.powi(3)
    }
}

pub mod session {
    use wingpane_proto::{ControlMessage, Mode, PROTO_VERSION};

    #[derive(Debug, Clone, Copy, PartialEq, Eq)]
    pub enum SessionState {
        Disconnected,
        Handshaking,
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
                ControlMessage::Hello { version, .. } if version == PROTO_VERSION => {
                    self.state = SessionState::Handshaking;
                    Some(ControlMessage::Accept {
                        selected_mode: Mode::ExtendedDisplay,
                        virtual_display_spec: Some((1920, 1080, 60)),
                        selected_codec: wingpane_proto::VideoCodec::H264,
                        bitrate_kbps: 20_000,
                    })
                }
                ControlMessage::SetMode(mode) => {
                    self.state = SessionState::Active(mode);
                    None
                }
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
