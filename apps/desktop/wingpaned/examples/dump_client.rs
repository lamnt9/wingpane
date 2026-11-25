use std::{fs::File, io::Write, time::Duration};
use tokio::{
    io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt},
    net::TcpStream,
    time::Instant,
};
use wingpane_proto::{
    encode_frame, ControlMessage, FrameDecoder, Packet, VideoCodec, PROTO_VERSION, Mode,
    UcExitReason, FEATURE_UNIVERSAL_CONTROL, UC_STATUS_SERVICE_CONNECTED, UC_STATUS_GESTURES_OK, UC_STATUS_FGS_RUNNING,
};

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let mut seconds = 5u64;
    let mut uc = false;
    let mut return_after = 5u64;
    let mut out = std::env::temp_dir().join("wingpane.h264");
    let mut args = std::env::args().skip(1);
    while let Some(a) = args.next() {
        match a.as_str() {
            "--seconds" => seconds = args.next().and_then(|s| s.parse().ok()).unwrap_or(5),
            "--uc" => uc = true,
            "--uc-return-after" => return_after = args.next().and_then(|s| s.parse().ok()).unwrap_or(5),
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
    // Keep partial frame reads alive across timer ticks (no cancelled read_exact).
    let (mut reader, mut stream) = stream.into_split();
    let (packet_tx, mut packet_rx) = tokio::sync::mpsc::unbounded_channel();
    let read_task = tokio::spawn(async move {
        loop {
            let result = recv(&mut reader).await.map_err(|e| e.to_string());
            let ended = !matches!(result, Ok(Some(_)));
            if packet_tx.send(result).is_err() || ended { break; }
        }
    });
    let mut file = File::create(&out)?;
    let start = Instant::now();
    let mut frames = 0u64;
    let mut bytes = 0u64;
    let mut accepted = false;
    let mut return_at = None;
    let mut negotiated = false;
    while start.elapsed() < Duration::from_secs(seconds) {
        let p = tokio::select! {
            _ = tokio::time::sleep_until(start + Duration::from_secs(seconds)) => break,
            _ = tokio::time::sleep_until(return_at.unwrap_or(start + Duration::from_secs(seconds))), if return_at.is_some() => {
                send(&mut stream, &Packet::Control(ControlMessage::UcExit {along:0.5,reason:UcExitReason::CursorReturned})).await?;
                println!("sent UcExit {{ along: 0.5, CursorReturned }}");
                return_at = None;
                continue;
            }
            result = packet_rx.recv() => match result {Some(result)=>result?,None=>break},
        };
        match p {
            Some(Packet::Control(ControlMessage::Accept { .. })) => {
                accepted = true;
                println!("Accept");
                if uc {
                    send(&mut stream, &Packet::Control(ControlMessage::Capabilities {features:FEATURE_UNIVERSAL_CONTROL | (1<<7)})).await?;
                }
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
            Some(Packet::Control(ControlMessage::Ping(id))) => {
                send(&mut stream,&Packet::Control(ControlMessage::Pong(id))).await?;
            }
            Some(Packet::Control(message)) => {
                println!("{message:?}");
                match message {
                    ControlMessage::Capabilities {features} if uc && !negotiated && features & FEATURE_UNIVERSAL_CONTROL != 0 => {
                        negotiated = true;
                        send(&mut stream,&Packet::Control(ControlMessage::UcStatus {flags:UC_STATUS_SERVICE_CONNECTED | UC_STATUS_GESTURES_OK | UC_STATUS_FGS_RUNNING})).await?;
                        send(&mut stream,&Packet::Control(ControlMessage::SetMode(Mode::AndroidControl))).await?;
                    }
                    ControlMessage::UcEnter {..} if negotiated => return_at = Some(Instant::now()+Duration::from_secs(return_after)),
                    ControlMessage::UcExit {..} => return_at = None,
                    _ => {}
                }
            }
            Some(Packet::Input(input)) => println!("{input:?}"),
            None => break,
        }
    }
    send(&mut stream, &Packet::Control(ControlMessage::Disconnect)).await?;
    read_task.abort();
    let elapsed = start.elapsed().as_secs_f64();
    println!(
        "frames={frames} bytes={bytes} fps={:.2} accepted={accepted} out={}",
        frames as f64 / elapsed,
        out.display()
    );
    Ok(())
}
async fn send(s: &mut (impl AsyncWrite + Unpin), p: &Packet) -> Result<(), Box<dyn std::error::Error>> {
    let mut b = Vec::new();
    encode_frame(p, &mut b).map_err(|e| e.to_string())?;
    s.write_all(&b).await?;
    Ok(())
}
async fn recv(s: &mut (impl AsyncRead + Unpin)) -> Result<Option<Packet>, Box<dyn std::error::Error>> {
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
