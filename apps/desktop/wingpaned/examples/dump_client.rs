use std::{fs::File, io::Write, time::Duration};
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    net::TcpStream,
    time::Instant,
};
use wingpane_proto::{
    encode_frame, ControlMessage, FrameDecoder, Packet, VideoCodec, PROTO_VERSION,
};

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let mut seconds = 5u64;
    let mut out = std::env::temp_dir().join("wingpane.h264");
    let mut args = std::env::args().skip(1);
    while let Some(a) = args.next() {
        match a.as_str() {
            "--seconds" => seconds = args.next().and_then(|s| s.parse().ok()).unwrap_or(5),
            "--out" => out = args.next().map(Into::into).unwrap_or(out),
            _ => (),
        }
    }
    let mut stream = TcpStream::connect("127.0.0.1:7788").await?;
    send(
        &mut stream,
        &Packet::Control(ControlMessage::Hello {
            version: PROTO_VERSION,
            device_name: "dump-client".into(),
            screen_size_px: (2560, 1600),
            supported_codecs: vec![VideoCodec::H264],
            refresh_rate_hz: 60,
        }),
    )
    .await?;
    let mut file = File::create(&out)?;
    let start = Instant::now();
    let mut frames = 0u64;
    let mut bytes = 0u64;
    let mut accepted = false;
    while start.elapsed() < Duration::from_secs(seconds) {
        let p = match tokio::time::timeout(Duration::from_secs(1), recv(&mut stream)).await {
            Ok(packet) => packet?,
            Err(_) => continue,
        };
        match p {
            Some(Packet::Control(ControlMessage::Accept { .. })) => {
                accepted = true;
                println!("Accept")
            }
            Some(Packet::Control(ControlMessage::Reject(reason))) => {
                println!("Reject: {reason:?}");
                break;
            }
            Some(Packet::Video(v)) => {
                file.write_all(&v.payload)?;
                frames += 1;
                bytes += v.payload.len() as u64
            }
            Some(_) => {}
            None => break,
        }
    }
    send(&mut stream, &Packet::Control(ControlMessage::Disconnect)).await?;
    let elapsed = start.elapsed().as_secs_f64();
    println!(
        "frames={frames} bytes={bytes} fps={:.2} accepted={accepted} out={}",
        frames as f64 / elapsed,
        out.display()
    );
    Ok(())
}
async fn send(s: &mut TcpStream, p: &Packet) -> Result<(), Box<dyn std::error::Error>> {
    let mut b = Vec::new();
    encode_frame(p, &mut b).map_err(|e| e.to_string())?;
    s.write_all(&b).await?;
    Ok(())
}
async fn recv(s: &mut TcpStream) -> Result<Option<Packet>, Box<dyn std::error::Error>> {
    let mut h = [0; 4];
    if s.read(&mut h[..1]).await? == 0 {
        return Ok(None);
    }
    s.read_exact(&mut h[1..]).await?;
    let n = u32::from_le_bytes(h);
    if n == 0 || n > wingpane_proto::MAX_FRAME_LEN {
        return Err("bad frame length".into());
    }
    let mut body = vec![0; n as usize];
    s.read_exact(&mut body).await?;
    let mut d = FrameDecoder::new();
    d.push(&h);
    let _ = d.next_packet().map_err(|e| e.to_string())?;
    d.push(&body);
    Ok(d.next_packet().map_err(|e| e.to_string())?)
}
