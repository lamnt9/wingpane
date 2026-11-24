use wingpane_proto::{ControlMessage, Mode, RejectReason, VideoCodec, PROTO_VERSION};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SessionState {
    Disconnected,
    Active(Mode),
}

/// A response and whether the peer must be closed immediately after sending it.
#[derive(Debug, Clone, PartialEq)]
pub struct Reply {
    pub send: ControlMessage,
    pub close: bool,
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
    pub fn handle_message(&mut self, msg: ControlMessage) -> Option<Reply> {
        match msg {
            ControlMessage::Hello {
                version,
                screen_size_px: (width, height),
                supported_codecs,
                refresh_rate_hz,
                ..
            } => {
                if self.state != SessionState::Disconnected {
                    return Some(Reply {
                        send: ControlMessage::Reject(RejectReason::Busy),
                        close: true,
                    });
                }
                let reason = if version != PROTO_VERSION {
                    Some(RejectReason::VersionMismatch {
                        host_version: PROTO_VERSION,
                    })
                } else if !supported_codecs.contains(&VideoCodec::H264) {
                    Some(RejectReason::UnsupportedCodec)
                } else if width == 0 || height == 0 || refresh_rate_hz == 0 {
                    Some(RejectReason::InvalidHello)
                } else {
                    None
                };
                if let Some(reason) = reason {
                    return Some(Reply {
                        send: ControlMessage::Reject(reason),
                        close: true,
                    });
                }
                let selected_mode = Mode::ExtendedDisplay;
                self.state = SessionState::Active(selected_mode);
                Some(Reply {
                    send: ControlMessage::Accept {
                        selected_mode,
                        virtual_display_spec: Some((width, height, refresh_rate_hz.min(60))),
                        selected_codec: VideoCodec::H264,
                        bitrate_kbps: 20_000,
                    },
                    close: false,
                })
            }
            ControlMessage::SetMode(mode) if matches!(self.state, SessionState::Active(_)) => {
                self.state = SessionState::Active(mode);
                None
            }
            ControlMessage::Ping(ts) => Some(Reply {
                send: ControlMessage::Pong(ts),
                close: false,
            }),
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
