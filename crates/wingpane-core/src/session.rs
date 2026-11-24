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
    pub after: Option<ControlMessage>,
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
                        after: None,
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
                        after: None,
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
                    after: None,
                    close: false,
                })
            }
            ControlMessage::SetMode(mode) if matches!(self.state, SessionState::Active(_)) => {
                self.state = SessionState::Active(mode);
                None
            }
            ControlMessage::Ping(ts) => Some(Reply {
                send: ControlMessage::Pong(ts),
                after: Some(ControlMessage::HostTime {
                    echo_ns: ts,
                    host_us: 0,
                }),
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

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn ping_reply_is_pong_then_host_time() {
        let mut session = Session::new();
        let reply = session
            .handle_message(ControlMessage::Ping(0x1_0000_0001))
            .unwrap();
        assert_eq!(reply.send, ControlMessage::Pong(0x1_0000_0001));
        assert_eq!(
            reply.after,
            Some(ControlMessage::HostTime {
                echo_ns: 0x1_0000_0001,
                host_us: 0
            })
        );
    }
}
