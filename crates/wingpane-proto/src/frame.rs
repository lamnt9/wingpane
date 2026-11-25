//! Stream framing and packet envelope.
use crate::{ControlMessage, InputEvent, InputPacket};
use alloc::vec::Vec;
use core::fmt;
use serde::{Deserialize, Serialize};

pub const MAX_FRAME_LEN: u32 = 8 * 1024 * 1024;

/// Video frame packet delivered over transport.
#[derive(Serialize, Deserialize, Debug, Clone, PartialEq)]
pub struct VideoPacket {
    pub frame_seq: u32,
    pub pts_us: u64,
    pub is_keyframe: bool,
    pub payload: Vec<u8>,
}

/// Top-level framed protocol packet. Variant order is part of the wire format.
#[derive(Serialize, Deserialize, Debug, Clone, PartialEq)]
pub enum Packet {
    Control(ControlMessage),
    Video(VideoPacket),
    Input(InputPacket),
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum FrameError {
    EmptyFrame,
    FrameTooLarge(u32),
    Decode,
    UnknownPacket,
    Encode,
}
impl fmt::Display for FrameError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::EmptyFrame => f.write_str("empty frame"),
            Self::FrameTooLarge(n) => write!(f, "frame too large: {n}"),
            Self::Decode => f.write_str("frame decode failed"),
            Self::UnknownPacket => f.write_str("unknown packet tag"),
            Self::Encode => f.write_str("frame encode failed"),
        }
    }
}

pub fn encode_frame(p: &Packet, out: &mut Vec<u8>) -> Result<(), FrameError> {
    if matches!(p, Packet::Input(InputPacket { event: InputEvent::Text(text), .. }) if text.len() > 256) {
        return Err(FrameError::Encode);
    }
    let mut body = Vec::new();
    body = postcard::to_extend(p, body).map_err(|_| FrameError::Encode)?;
    let len = u32::try_from(body.len()).map_err(|_| FrameError::Encode)?;
    if len == 0 {
        return Err(FrameError::EmptyFrame);
    }
    if len > MAX_FRAME_LEN {
        return Err(FrameError::FrameTooLarge(len));
    }
    out.extend_from_slice(&len.to_le_bytes());
    out.extend_from_slice(&body);
    Ok(())
}

/// Incremental decoder. Retains consumed bytes until a threshold to avoid repeated front-drains.
pub struct FrameDecoder {
    bytes: Vec<u8>,
    cursor: usize,
    failed: Option<FrameError>,
}
impl FrameDecoder {
    pub fn new() -> Self {
        Self {
            bytes: Vec::new(),
            cursor: 0,
            failed: None,
        }
    }
    /// Buffers raw bytes. Headers are validated by `next_packet` before any body is awaited,
    /// so an oversize length fails fast. Unconsumed bytes are capped at one max frame plus
    /// one read chunk; callers must drain `next_packet` after every `push`.
    pub fn push(&mut self, incoming: &[u8]) {
        if self.failed.is_some() {
            return;
        }
        let unconsumed = self.bytes.len() - self.cursor + incoming.len();
        if unconsumed > 2 * (4 + MAX_FRAME_LEN as usize) {
            self.failed = Some(FrameError::FrameTooLarge(
                u32::try_from(unconsumed).unwrap_or(u32::MAX),
            ));
            return;
        }
        self.bytes.extend_from_slice(incoming);
    }
    pub fn next_packet(&mut self) -> Result<Option<Packet>, FrameError> {
        if let Some(e) = self.failed.clone() {
            return Err(e);
        }
        let available = self.bytes.len().saturating_sub(self.cursor);
        if available < 4 {
            return Ok(None);
        }
        let len = u32::from_le_bytes(
            self.bytes[self.cursor..self.cursor + 4]
                .try_into()
                .map_err(|_| FrameError::Decode)?,
        );
        if len == 0 {
            self.failed = Some(FrameError::EmptyFrame);
            return Err(FrameError::EmptyFrame);
        }
        if len > MAX_FRAME_LEN {
            let e = FrameError::FrameTooLarge(len);
            self.failed = Some(e.clone());
            return Err(e);
        }
        let total = 4usize + len as usize;
        if available < total {
            return Ok(None);
        }
        let start = self.cursor + 4;
        let body = &self.bytes[start..start + len as usize];
        let decoded = postcard::from_bytes::<Packet>(body).map_err(|_| {
            if has_unknown_tag(body) {
                FrameError::UnknownPacket
            } else {
                FrameError::Decode
            }
        });
        self.cursor += total;
        if self.cursor == self.bytes.len() {
            self.bytes.clear();
            self.cursor = 0;
        } else if self.cursor >= 64 * 1024 {
            self.bytes.drain(..self.cursor);
            self.cursor = 0;
        }
        decoded
            .and_then(|packet| {
                if matches!(&packet, Packet::Input(InputPacket { event: InputEvent::Text(text), .. }) if text.len() > 256) {
                    Err(FrameError::Decode)
                } else {
                    Ok(packet)
                }
            })
            .map(Some)
    }
}

// Classify only a valid varint enum discriminant outside the currently known range.
// All other postcard failures remain hard decode errors.
fn has_unknown_tag(body: &[u8]) -> bool {
    fn varint(bytes: &[u8], at: &mut usize) -> Option<u64> {
        let mut out = 0u64;
        for shift in (0..=63).step_by(7) {
            let b = *bytes.get(*at)?;
            *at += 1;
            if shift == 63 && b > 1 {
                return None;
            }
            out |= u64::from(b & 0x7f) << shift;
            if b & 0x80 == 0 {
                return Some(out);
            }
        }
        None
    }
    let mut at = 0;
    let Some(packet_tag) = varint(body, &mut at) else {
        return false;
    };
    match packet_tag {
        0 => varint(body, &mut at).is_some_and(|tag| tag > 9),
        1 => false,
        2 => {
            if varint(body, &mut at).is_none() || varint(body, &mut at).is_none() {
                return false;
            }
            varint(body, &mut at).is_some_and(|tag| tag > 7)
        }
        _ => true,
    }
}
impl Default for FrameDecoder {
    fn default() -> Self {
        Self::new()
    }
}
