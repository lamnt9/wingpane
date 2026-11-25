use crate::session::Session;
use std::net::SocketAddr;
use thiserror::Error;
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    net::{
        tcp::{OwnedReadHalf, OwnedWriteHalf},
        TcpListener, TcpStream,
    },
};
use wingpane_proto::{
    encode_frame, ControlMessage, FrameDecoder, FrameError, Packet, MAX_FRAME_LEN,
};

#[derive(Debug, Error)]
pub enum TransportError {
    #[error("TCP I/O: {0}")]
    Io(#[from] std::io::Error),
    #[error("protocol framing: {0}")]
    Frame(String),
    #[error("incomplete frame at EOF")]
    Truncated,
    #[error("unknown packet tag")]
    UnknownPacket,
}
impl From<FrameError> for TransportError {
    fn from(error: FrameError) -> Self {
        Self::Frame(error.to_string())
    }
}
fn finish_received_frame(decoder: &mut FrameDecoder) -> Result<Option<Packet>, TransportError> {
    match decoder.next_packet() {
        Err(FrameError::UnknownPacket) => Err(TransportError::UnknownPacket),
        result => Ok(result?),
    }
}

pub struct FramedTcp {
    stream: TcpStream,
}
impl FramedTcp {
    pub fn new(stream: TcpStream) -> Result<Self, TransportError> {
        stream.set_nodelay(true)?;
        Ok(Self { stream })
    }
    pub fn peer_addr(&self) -> Result<SocketAddr, TransportError> {
        Ok(self.stream.peer_addr()?)
    }
    pub fn into_split(self) -> (FramedReader, FramedWriter) {
        let (reader, writer) = self.stream.into_split();
        (
            FramedReader { stream: reader },
            FramedWriter { stream: writer },
        )
    }
    pub async fn send(&mut self, packet: &Packet) -> Result<(), TransportError> {
        // TODO(FR-7): prioritize Input/Control ahead of queued Video packets.
        let mut bytes = Vec::new();
        encode_frame(packet, &mut bytes)?;
        self.stream.write_all(&bytes).await?;
        Ok(())
    }
    pub async fn recv(&mut self) -> Result<Option<Packet>, TransportError> {
        let mut header = [0; 4];
        let n = self.stream.read(&mut header[..1]).await?;
        if n == 0 {
            return Ok(None);
        }
        self.stream
            .read_exact(&mut header[1..])
            .await
            .map_err(|e| {
                if e.kind() == std::io::ErrorKind::UnexpectedEof {
                    TransportError::Truncated
                } else {
                    e.into()
                }
            })?;
        let len = u32::from_le_bytes(header);
        let mut decoder = FrameDecoder::new();
        decoder.push(&header);
        let _ = decoder.next_packet()?; // validates empty/oversize lengths before allocation
        let mut body = vec![0; len.min(MAX_FRAME_LEN) as usize];
        self.stream.read_exact(&mut body).await.map_err(|e| {
            if e.kind() == std::io::ErrorKind::UnexpectedEof {
                TransportError::Truncated
            } else {
                e.into()
            }
        })?;
        decoder.push(&body);
        finish_received_frame(&mut decoder)
    }
}

pub struct FramedReader {
    stream: OwnedReadHalf,
}
impl FramedReader {
    pub async fn recv(&mut self) -> Result<Option<Packet>, TransportError> {
        let mut header = [0; 4];
        let n = self.stream.read(&mut header[..1]).await?;
        if n == 0 {
            return Ok(None);
        }
        self.stream
            .read_exact(&mut header[1..])
            .await
            .map_err(|e| {
                if e.kind() == std::io::ErrorKind::UnexpectedEof {
                    TransportError::Truncated
                } else {
                    e.into()
                }
            })?;
        let len = u32::from_le_bytes(header);
        let mut decoder = FrameDecoder::new();
        decoder.push(&header);
        let _ = decoder.next_packet()?;
        let mut body = vec![0; len as usize];
        self.stream.read_exact(&mut body).await.map_err(|e| {
            if e.kind() == std::io::ErrorKind::UnexpectedEof {
                TransportError::Truncated
            } else {
                e.into()
            }
        })?;
        decoder.push(&body);
        finish_received_frame(&mut decoder)
    }
}

pub struct FramedWriter {
    stream: OwnedWriteHalf,
}
impl FramedWriter {
    pub async fn send(&mut self, packet: &Packet) -> Result<(), TransportError> {
        let mut bytes = Vec::new();
        encode_frame(packet, &mut bytes)?;
        self.stream.write_all(&bytes).await?;
        Ok(())
    }
}

/// Accept a single client and serve its handshake/control session until EOF or disconnect.
pub async fn serve_one(listener: &TcpListener) -> Result<(), TransportError> {
    let (stream, _) = listener.accept().await?;
    serve_session(FramedTcp::new(stream)?).await
}

pub async fn serve_session(mut framed: FramedTcp) -> Result<(), TransportError> {
    let mut session = Session::new();
    while let Some(packet) = framed.recv().await? {
        let Packet::Control(message) = packet else {
            continue;
        };
        if matches!(message, ControlMessage::Disconnect) {
            break;
        }
        if let Some(reply) = session.handle_message(message) {
            framed.send(&Packet::Control(reply.send)).await?;
            if reply.close {
                break;
            }
        }
    }
    Ok(())
}

#[cfg(test)]
mod unknown_packet_tests {
    use super::*;
    #[test]
    fn unknown_frame_is_reported_and_following_ping_decodes() {
        let mut decoder = FrameDecoder::new();
        decoder.push(&[2, 0, 0, 0, 0, 99]);
        let mut ping = Vec::new();
        encode_frame(&Packet::Control(ControlMessage::Ping(7)), &mut ping).unwrap();
        decoder.push(&ping);
        assert!(matches!(
            finish_received_frame(&mut decoder),
            Err(TransportError::UnknownPacket)
        ));
        assert_eq!(
            finish_received_frame(&mut decoder).unwrap(),
            Some(Packet::Control(ControlMessage::Ping(7)))
        );
    }
}
