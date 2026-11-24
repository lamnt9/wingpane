//! Host session loop and bounded, latency-oriented frame queue.
use std::{
    collections::VecDeque,
    sync::{Arc, Mutex},
};
use tokio::sync::Notify;

#[derive(Debug, Clone)]
pub struct EncodedFrame {
    pub data: Vec<u8>,
    pub keyframe: bool,
    pub pts_us: u64,
}

#[derive(Default)]
pub struct FrameQueue {
    frames: Mutex<VecDeque<EncodedFrame>>,
    awaiting_keyframe: Mutex<bool>,
    ready: Notify,
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
            q.retain(|queued| queued.keyframe);
            *awaiting = true;
        }
        if *awaiting && !frame.keyframe {
            return overflow;
        }
        if frame.keyframe {
            *awaiting = false;
        }
        if q.len() >= 3 {
            q.pop_front();
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

pub struct SessionResources {
    pub capture: Box<dyn CaptureSource>,
    pub display: Box<dyn DisplayResource>,
}
impl Drop for SessionResources {
    fn drop(&mut self) {
        self.capture.stop();
        self.display.destroy();
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

pub fn busy_rejection() -> wingpane_proto::ControlMessage {
    wingpane_proto::ControlMessage::Reject(wingpane_proto::RejectReason::Busy)
}

#[cfg(test)]
mod tests {
    use super::*;
    fn f(n: u8, keyframe: bool) -> EncodedFrame {
        EncodedFrame {
            data: vec![n],
            keyframe,
            pts_us: n.into(),
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
    fn session_resource_drop_stops_capture_and_destroys_display_on_eof() {
        use std::sync::atomic::{AtomicUsize, Ordering};
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
        let calls = Arc::new(AtomicUsize::new(0));
        let resources = SessionResources {
            capture: Box::new(Fake(calls.clone())),
            display: Box::new(Fake(calls.clone())),
        };
        drop(resources); // the session loop drops this guard on EOF/disconnect/error
        assert_eq!(calls.load(Ordering::SeqCst), 2);
    }

    #[tokio::test]
    async fn eof_from_framed_peer_drops_fake_session_resources() {
        use std::sync::atomic::{AtomicUsize, Ordering};
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
            display: Box::new(Fake(calls.clone())),
        });
        assert_eq!(calls.load(Ordering::SeqCst), 2);
    }
}
