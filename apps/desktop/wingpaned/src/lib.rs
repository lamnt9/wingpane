//! Host session loop and bounded, latency-oriented frame queue.
use std::{
    collections::VecDeque,
    sync::{
        atomic::{AtomicU64, Ordering},
        Arc, Mutex,
    },
};
use tokio::sync::Notify;

#[derive(Debug, Clone)]
pub struct EncodedFrame {
    pub data: Vec<u8>,
    pub keyframe: bool,
    pub pts_us: u64,
    pub encode_done_us: u64,
}

#[derive(Default)]
pub struct FrameQueue {
    frames: Mutex<VecDeque<EncodedFrame>>,
    awaiting_keyframe: Mutex<bool>,
    ready: Notify,
    dropped_frames: AtomicU64,
}
impl FrameQueue {
    pub fn push_with_overflow(&self, frame: EncodedFrame, refresh: impl FnOnce()) -> bool {
        let overflow = self.push(frame);
        if overflow {
            refresh();
        }
        overflow
    }

    pub fn push(&self, frame: EncodedFrame) -> bool {
        let mut q = self.frames.lock().expect("frame queue poisoned");
        let overflow = q.len() >= 3;
        let mut awaiting = self.awaiting_keyframe.lock().expect("frame queue poisoned");
        if overflow && !*awaiting {
            let previous = q.len();
            q.retain(|queued| queued.keyframe);
            self.dropped_frames
                .fetch_add((previous - q.len()) as u64, Ordering::Relaxed);
            *awaiting = true;
        }
        if *awaiting && !frame.keyframe {
            self.dropped_frames.fetch_add(1, Ordering::Relaxed);
            return overflow;
        }
        if frame.keyframe {
            *awaiting = false;
        }
        if q.len() >= 3 {
            q.pop_front();
            self.dropped_frames.fetch_add(1, Ordering::Relaxed);
        }
        q.push_back(frame);
        drop(q);
        drop(awaiting);
        self.ready.notify_one();
        overflow
    }
    pub async fn pop(&self) -> EncodedFrame {
        loop {
            if let Some(f) = self
                .frames
                .lock()
                .expect("frame queue poisoned")
                .pop_front()
            {
                return f;
            }
            self.ready.notified().await;
        }
    }
    pub fn len(&self) -> usize {
        self.frames.lock().expect("frame queue poisoned").len()
    }
    pub fn is_empty(&self) -> bool {
        self.frames.lock().expect("frame queue poisoned").is_empty()
    }
    pub fn dropped_frames(&self) -> u64 {
        self.dropped_frames.load(Ordering::Relaxed)
    }
}

pub fn shared_queue() -> Arc<FrameQueue> {
    Arc::new(FrameQueue::default())
}

/// A common control surface for native and test capture sources.
pub trait CaptureSource: Send + Sync {
    fn force_keyframe(&self);
    fn stop(&self);
}

pub trait DisplayResource: Send {
    fn destroy(&self);
}

/// Native input boundary. Tests use a fake sink so no real pointer events are posted.
pub trait InputSink: Send {
    fn pointer(&self, x: f32, y: f32, phase: wingpane_proto::TouchPhase);
    fn release_all(&self);
}

pub struct SessionResources {
    pub capture: Box<dyn CaptureSource>,
    pub input: Box<dyn InputSink>,
    pub display: Box<dyn DisplayResource>,
}
impl Drop for SessionResources {
    fn drop(&mut self) {
        self.input.release_all();
        self.capture.stop();
        self.display.destroy();
    }
}

pub fn handle_input(sink: &dyn InputSink, packet: wingpane_proto::InputPacket) -> bool {
    if let wingpane_proto::InputEvent::Touch { x, y, phase, .. } = packet.event {
        sink.pointer(x, y, phase);
        true
    } else {
        false
    }
}

pub fn handle_control(
    source: &dyn CaptureSource,
    message: wingpane_proto::ControlMessage,
) -> Option<wingpane_proto::ControlMessage> {
    use wingpane_proto::ControlMessage;
    match message {
        ControlMessage::RequestKeyframe => {
            source.force_keyframe();
            None
        }
        ControlMessage::Ping(ts) => Some(ControlMessage::Pong(ts)),
        ControlMessage::Disconnect => None,
        _ => None,
    }
}

pub fn handle_control_messages(
    source: &dyn CaptureSource,
    message: wingpane_proto::ControlMessage,
    host_us: u64,
) -> Vec<wingpane_proto::ControlMessage> {
    use wingpane_proto::ControlMessage;
    match message {
        ControlMessage::Ping(echo_ns) => vec![
            ControlMessage::Pong(echo_ns),
            ControlMessage::HostTime { echo_ns, host_us },
        ],
        other => handle_control(source, other).into_iter().collect(),
    }
}

pub fn busy_rejection() -> wingpane_proto::ControlMessage {
    wingpane_proto::ControlMessage::Reject(wingpane_proto::RejectReason::Busy)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicUsize, Ordering};
    struct NoopInput;
    impl InputSink for NoopInput {
        fn pointer(&self, _: f32, _: f32, _: wingpane_proto::TouchPhase) {}
        fn release_all(&self) {}
    }
    fn f(n: u8, keyframe: bool) -> EncodedFrame {
        EncodedFrame {
            data: vec![n],
            keyframe,
            pts_us: n.into(),
            encode_done_us: n.into(),
        }
    }
    #[test]
    fn overflow_discards_predictive_frames_until_a_keyframe() {
        let q = FrameQueue::default();
        q.push(f(1, true));
        q.push(f(2, false));
        q.push(f(3, false));
        assert!(q.push(f(4, false)));
        assert!(!q.push(f(5, false)));
        q.push(f(6, true));
        let g = q.frames.lock().unwrap();
        let emitted: Vec<_> = g.iter().map(|x| (x.data[0], x.keyframe)).collect();
        assert_eq!(emitted, [(1, true), (6, true)]);
        assert!(!emitted.iter().any(|(id, key)| *id > 1 && !key));
    }
    #[test]
    fn overflow_requests_refresh_once_and_keeps_queue_bounded() {
        let q = FrameQueue::default();
        use std::sync::atomic::{AtomicUsize, Ordering};
        let requests = AtomicUsize::new(0);
        let refresh = || {
            requests.fetch_add(1, Ordering::SeqCst);
        };
        q.push(f(1, true));
        q.push(f(2, true));
        q.push(f(3, true));
        assert!(q.push_with_overflow(f(4, true), refresh));
        assert_eq!(q.len(), 3);
        assert_eq!(requests.load(Ordering::SeqCst), 1);
    }

    #[test]
    fn request_keyframe_calls_fake_source_and_ping_gets_pong() {
        use std::sync::atomic::{AtomicUsize, Ordering};
        struct Fake(AtomicUsize);
        impl CaptureSource for Fake {
            fn force_keyframe(&self) {
                self.0.fetch_add(1, Ordering::SeqCst);
            }
            fn stop(&self) {}
        }
        let fake = Fake(AtomicUsize::new(0));
        assert_eq!(
            handle_control(&fake, wingpane_proto::ControlMessage::RequestKeyframe),
            None
        );
        assert_eq!(
            handle_control(&fake, wingpane_proto::ControlMessage::Ping(9)),
            Some(wingpane_proto::ControlMessage::Pong(9))
        );
        assert_eq!(fake.0.load(Ordering::SeqCst), 1);
    }

    #[test]
    fn busy_client_receives_busy_rejection() {
        assert_eq!(
            busy_rejection(),
            wingpane_proto::ControlMessage::Reject(wingpane_proto::RejectReason::Busy)
        );
    }

    #[test]
    fn session_resource_drop_releases_input_before_destroying_display() {
        struct OrderedInput(Arc<Mutex<Vec<&'static str>>>);
        impl InputSink for OrderedInput {
            fn pointer(&self, _: f32, _: f32, _: wingpane_proto::TouchPhase) {
                self.0.lock().unwrap().push("pointer");
            }
            fn release_all(&self) {
                self.0.lock().unwrap().push("release");
            }
        }
        struct OrderedCapture(Arc<Mutex<Vec<&'static str>>>);
        impl CaptureSource for OrderedCapture {
            fn force_keyframe(&self) {}
            fn stop(&self) {
                self.0.lock().unwrap().push("stop");
            }
        }
        struct OrderedDisplay(Arc<Mutex<Vec<&'static str>>>);
        impl DisplayResource for OrderedDisplay {
            fn destroy(&self) {
                self.0.lock().unwrap().push("destroy");
            }
        }
        let calls = Arc::new(Mutex::new(Vec::new()));
        let resources = SessionResources {
            capture: Box::new(OrderedCapture(calls.clone())),
            input: Box::new(OrderedInput(calls.clone())),
            display: Box::new(OrderedDisplay(calls.clone())),
        };
        assert!(handle_input(
            resources.input.as_ref(),
            wingpane_proto::InputPacket {
                seq: 1,
                ts_us: 1,
                event: wingpane_proto::InputEvent::Touch {
                    pointer_id: 0,
                    x: 0.5,
                    y: 0.5,
                    phase: wingpane_proto::TouchPhase::Down,
                },
            },
        ));
        drop(resources); // EOF/error/task cancellation all release before display destruction.
        assert_eq!(
            *calls.lock().unwrap(),
            ["pointer", "release", "stop", "destroy"]
        );
    }

    #[test]
    fn touch_input_packets_reach_fake_sink_in_order() {
        struct FakeInput(Mutex<Vec<(f32, f32, wingpane_proto::TouchPhase)>>);
        impl InputSink for FakeInput {
            fn pointer(&self, x: f32, y: f32, phase: wingpane_proto::TouchPhase) {
                self.0.lock().unwrap().push((x, y, phase));
            }
            fn release_all(&self) {}
        }
        let sink = FakeInput(Mutex::new(Vec::new()));
        for (seq, phase) in [
            (1, wingpane_proto::TouchPhase::Down),
            (2, wingpane_proto::TouchPhase::Move),
            (3, wingpane_proto::TouchPhase::Up),
        ] {
            assert!(handle_input(
                &sink,
                wingpane_proto::InputPacket {
                    seq,
                    ts_us: seq.into(),
                    event: wingpane_proto::InputEvent::Touch {
                        pointer_id: 0,
                        x: seq as f32 / 10.0,
                        y: 0.5,
                        phase,
                    },
                }
            ));
        }
        let got = sink.0.lock().unwrap();
        assert_eq!(
            got.iter().map(|(_, _, p)| *p).collect::<Vec<_>>(),
            [
                wingpane_proto::TouchPhase::Down,
                wingpane_proto::TouchPhase::Move,
                wingpane_proto::TouchPhase::Up
            ]
        );
        assert_eq!(
            got.iter().map(|(x, _, _)| *x).collect::<Vec<_>>(),
            [0.1, 0.2, 0.3]
        );
    }

    #[tokio::test]
    async fn eof_from_framed_peer_drops_fake_session_resources() {
        use tokio::net::{TcpListener, TcpStream};
        use wingpane_core::transport::tcp::FramedTcp;
        struct Fake(Arc<AtomicUsize>);
        impl CaptureSource for Fake {
            fn force_keyframe(&self) {}
            fn stop(&self) {
                self.0.fetch_add(1, Ordering::SeqCst);
            }
        }
        impl DisplayResource for Fake {
            fn destroy(&self) {
                self.0.fetch_add(1, Ordering::SeqCst);
            }
        }
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let client = TcpStream::connect(listener.local_addr().unwrap())
            .await
            .unwrap();
        let (server, _) = listener.accept().await.unwrap();
        let mut framed = FramedTcp::new(server).unwrap();
        drop(client);
        assert!(framed.recv().await.unwrap().is_none());
        let calls = Arc::new(AtomicUsize::new(0));
        drop(SessionResources {
            capture: Box::new(Fake(calls.clone())),
            input: Box::new(NoopInput),
            display: Box::new(Fake(calls.clone())),
        });
        assert_eq!(calls.load(Ordering::SeqCst), 2);
    }
}
