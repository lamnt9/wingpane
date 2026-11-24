use crate::session::Session;
use std::net::SocketAddr;
use thiserror::Error;
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    net::{TcpListener, TcpStream},
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
}
impl From<FrameError> for TransportError {
    fn from(error: FrameError) -> Self {
        Self::Frame(error.to_string())
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
        Ok(decoder.next_packet()?)
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
