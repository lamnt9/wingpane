//! Stream framing and packet envelope.
use crate::{ControlMessage, InputPacket};
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
    Encode,
}
impl fmt::Display for FrameError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::EmptyFrame => f.write_str("empty frame"),
            Self::FrameTooLarge(n) => write!(f, "frame too large: {n}"),
            Self::Decode => f.write_str("frame decode failed"),
            Self::Encode => f.write_str("frame encode failed"),
        }
    }
}

pub fn encode_frame(p: &Packet, out: &mut Vec<u8>) -> Result<(), FrameError> {
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
        let decoded = postcard::from_bytes::<Packet>(&self.bytes[start..start + len as usize])
            .map_err(|_| FrameError::Decode);
        self.cursor += total;
        if self.cursor == self.bytes.len() {
            self.bytes.clear();
            self.cursor = 0;
        } else if self.cursor >= 64 * 1024 {
            self.bytes.drain(..self.cursor);
            self.cursor = 0;
        }
        decoded.map(Some)
    }
}
impl Default for FrameDecoder {
    fn default() -> Self {
        Self::new()
    }
}
