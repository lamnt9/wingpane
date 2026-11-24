use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    net::{TcpListener, TcpStream},
};
use wingpane_core::{host::serve_one, transport::tcp::FramedTcp};
use wingpane_proto::{
    ControlMessage, Packet, RejectReason, VideoCodec, MAX_FRAME_LEN, PROTO_VERSION,
};

fn hello(version: u16) -> Packet {
    Packet::Control(ControlMessage::Hello {
        version,
        device_name: "loopback".into(),
        screen_size_px: (1600, 900),
        supported_codecs: vec![VideoCodec::H264],
        refresh_rate_hz: 120,
    })
}

#[tokio::test]
async fn handshake_ping_disconnect_loopback() {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    let host = tokio::spawn(async move { serve_one(&listener).await });
    let mut client = FramedTcp::new(TcpStream::connect(addr).await.unwrap()).unwrap();
    client.send(&hello(PROTO_VERSION)).await.unwrap();
    let Some(Packet::Control(ControlMessage::Accept {
        virtual_display_spec: Some((1600, 900, hz)),
        ..
    })) = client.recv().await.unwrap()
    else {
        panic!("expected Accept")
    };
    assert!(hz <= 60);
    client
        .send(&Packet::Control(ControlMessage::Ping(42)))
        .await
        .unwrap();
    assert_eq!(
        client.recv().await.unwrap(),
        Some(Packet::Control(ControlMessage::Pong(42)))
    );
    client
        .send(&Packet::Control(ControlMessage::Disconnect))
        .await
        .unwrap();
    assert!(host.await.unwrap().is_ok());
}

#[tokio::test]
async fn version_mismatch_rejects_then_closes() {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    let host = tokio::spawn(async move { serve_one(&listener).await });
    let mut client = FramedTcp::new(TcpStream::connect(addr).await.unwrap()).unwrap();
    client.send(&hello(99)).await.unwrap();
    assert_eq!(
        client.recv().await.unwrap(),
        Some(Packet::Control(ControlMessage::Reject(
            RejectReason::VersionMismatch {
                host_version: PROTO_VERSION
            }
        )))
    );
    assert_eq!(client.recv().await.unwrap(), None);
    assert!(host.await.unwrap().is_ok());
}

async fn malformed_frame(bytes: &[u8]) -> bool {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    let host = tokio::spawn(async move { serve_one(&listener).await });
    let mut stream = TcpStream::connect(addr).await.unwrap();
    stream.write_all(bytes).await.unwrap();
    let mut tail = [0u8; 1];
    let _ = stream.read(&mut tail).await;
    drop(stream);
    host.await.unwrap().is_err()
}

#[tokio::test]
async fn garbage_and_oversize_frames_error_and_close() {
    assert!(malformed_frame(&[1, 0, 0, 0, 0xff]).await);
    assert!(malformed_frame(&(MAX_FRAME_LEN + 1).to_le_bytes()).await);
}
