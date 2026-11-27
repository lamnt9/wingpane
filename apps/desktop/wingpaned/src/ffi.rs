use std::ffi::CString;

unsafe extern "C" {
    fn wp_vd_create(width: u32, height: u32, hz: f64, hidpi: bool, name: *const i8) -> u32;
    fn wp_vd_destroy(display_id: u32);
    fn wp_capture_start(
        display_id: u32,
        width: u32,
        height: u32,
        hz: u32,
        bitrate_bps: u32,
        cb: FrameCallback,
        ctx: *mut std::ffi::c_void,
    ) -> u64;
    fn wp_capture_force_keyframe(handle: u64);
    fn wp_capture_stop(handle: u64);
    fn wp_capture_frames_captured(handle: u64) -> u64;
    fn wp_host_time_us() -> u64;
}

pub type FrameCallback =
    unsafe extern "C" fn(*mut std::ffi::c_void, *const u8, usize, i32, i64, i64);

pub struct VirtualDisplay(u32);
impl VirtualDisplay {
    pub fn create(width: u32, height: u32, hz: u32, hidpi: bool) -> Result<Self, String> {
        let name = CString::new("Wingpane").unwrap();
        let id = unsafe { wp_vd_create(width, height, f64::from(hz), hidpi, name.as_ptr()) };
        if id == 0 {
            Err("CGVirtualDisplay creation failed".to_owned())
        } else {
            Ok(Self(id))
        }
    }
    pub fn id(&self) -> u32 {
        self.0
    }
}
impl Drop for VirtualDisplay {
    fn drop(&mut self) {
        unsafe { wp_vd_destroy(self.id()) };
    }
}
impl wingpaned::DisplayResource for VirtualDisplay {
    fn destroy(&self) {
        unsafe { wp_vd_destroy(self.id()) };
    }
}

pub struct Capture {
    handle: u64,
}
impl Capture {
    pub unsafe fn start(
        display_id: u32,
        width: u32,
        height: u32,
        hz: u32,
        bitrate: u32,
        cb: FrameCallback,
        ctx: *mut std::ffi::c_void,
    ) -> Result<Self, String> {
        let handle = unsafe { wp_capture_start(display_id, width, height, hz, bitrate, cb, ctx) };
        if handle == 0 {
            Err("ScreenCaptureKit / VideoToolbox capture start failed".into())
        } else {
            Ok(Self { handle })
        }
    }
    pub fn force_keyframe(&self) {
        unsafe { wp_capture_force_keyframe(self.handle) }
    }
    pub fn handle(&self) -> u64 {
        self.handle
    }
}
pub fn host_time_us() -> u64 {
    unsafe { wp_host_time_us() }
}
pub fn capture_frames_captured(handle: u64) -> u64 {
    unsafe { wp_capture_frames_captured(handle) }
}
impl Drop for Capture {
    fn drop(&mut self) {
        unsafe { wp_capture_stop(self.handle) }
    }
}
impl wingpaned::CaptureSource for Capture {
    fn force_keyframe(&self) {
        self.force_keyframe();
    }
    fn stop(&self) {
        unsafe { wp_capture_stop(self.handle) };
    }
}

// WP-U4 C ABI. Native event tags/reason codes are NOT protocol enum tags.
use std::{ffi::c_void, sync::Mutex};
use tokio::sync::mpsc;
use wingpaned::uc::{BridgeEvent, ClipboardPort, Rect};
use wingpane_proto::{InputEvent, ScrollPhase, UcEdge};

pub use wingpaned::uc::WpUcEvent;
unsafe extern "C" {
    fn wp_uc_available() -> bool;
    fn wp_uc_start(callback: unsafe extern "C" fn(*mut c_void, *const WpUcEvent), ctx: *mut c_void) -> bool;
    fn wp_uc_set_inside(inside: bool, edge: u8, along: f64);
    fn wp_uc_heartbeat();
    fn wp_uc_release();
    fn wp_uc_stop();
    fn wp_uc_display_rects(out: *mut f64, max: u32) -> u32;
    fn wp_uc_ime_hint() -> u8;
    fn wp_uc_pasteboard_change_count() -> i64;
    fn wp_uc_pasteboard_get(buf: *mut u8, len: usize) -> isize;
    fn wp_uc_pasteboard_set(ptr: *const u8, len: usize) -> bool;
    fn CGPreflightListenEventAccess() -> bool;
    fn CGEventSourceButtonState(state: i32, button: u32) -> bool;
}
#[link(name="Carbon", kind="framework")]
unsafe extern "C" { fn IsSecureEventInputEnabled() -> bool; }

/// Restores ownership even on early setup failure, cancellation, or unwinding.
pub struct UcReleaseGuard;
impl Drop for UcReleaseGuard { fn drop(&mut self) { UcBridge::release(); } }

pub struct NativeClipboard;
impl ClipboardPort for NativeClipboard {
    fn change_count(&self) -> i64 { unsafe { wp_uc_pasteboard_change_count() } }
    fn get_text(&self) -> Option<String> {
        let needed = unsafe { wp_uc_pasteboard_get(std::ptr::null_mut(), 0) };
        if !(0..=64 * 1024).contains(&needed) { return None; }
        let mut bytes = vec![0; needed as usize];
        if unsafe { wp_uc_pasteboard_get(bytes.as_mut_ptr(), bytes.len()) } != needed { return None; }
        String::from_utf8(bytes).ok()
    }
    fn set_text(&mut self, text: &str) -> bool {
        text.len() <= 64 * 1024 && unsafe { wp_uc_pasteboard_set(text.as_ptr(), text.len()) }
    }
}

struct UcCallbackContext { tx: mpsc::UnboundedSender<(WpUcEvent, u64)> }
/// Stable callback allocation. wp_uc_stop clears the callback under the Swift
/// tap lock before we free it, including when startup fails.
#[derive(Default)]
pub struct UcBridge { context: Mutex<Option<Box<UcCallbackContext>>> }
unsafe extern "C" fn on_uc_event(ctx: *mut c_void, event: *const WpUcEvent) {
    if ctx.is_null() || event.is_null() { return; }
    let ctx = unsafe { &*ctx.cast::<UcCallbackContext>() };
    let event = unsafe { *event };
    let _ = ctx.tx.send((event,host_time_us()));
}
impl UcBridge {
    pub fn available() -> bool { unsafe { wp_uc_available() } }
    pub fn input_monitoring() -> bool { unsafe { CGPreflightListenEventAccess() } }
    pub fn secure_input() -> bool { unsafe { IsSecureEventInputEnabled() } }
    pub fn start(&self, tx:mpsc::UnboundedSender<(WpUcEvent,u64)>) -> bool {
        let mut context=self.context.lock().unwrap();
        if context.is_some() { return true; }
        let mut allocation=Box::new(UcCallbackContext {tx});
        let ok=unsafe { wp_uc_start(on_uc_event, (&mut *allocation as *mut UcCallbackContext).cast()) };
        if ok { *context=Some(allocation); } else { unsafe { wp_uc_stop() }; }
        ok
    }
    pub fn stop(&self) {
        let mut context=self.context.lock().unwrap();
        unsafe { wp_uc_release(); wp_uc_stop(); }
        context.take();
    }
    pub fn release() { unsafe { wp_uc_release() } }
    pub fn heartbeat() { unsafe { wp_uc_heartbeat() } }
    pub fn set_inside(inside:bool, edge:UcEdge, along:f32) {
        unsafe { wp_uc_set_inside(inside,edge as u8, f64::from(along)) }
    }
    pub fn displays() -> Vec<Rect> {
        let mut buffer=[0.0; 64*4];
        let count=unsafe { wp_uc_display_rects(buffer.as_mut_ptr(),64) }.min(64) as usize;
        buffer[..count*4].chunks_exact(4).map(|r| Rect {x:r[0] as f32,y:r[1] as f32,width:r[2] as f32,height:r[3] as f32}).collect()
    }
    pub fn event(event:WpUcEvent, now_us:u64) -> Option<BridgeEvent> {
        let input=|event| Some(BridgeEvent::Input {event,now_us});
        match event.kind {
            0 => Some(BridgeEvent::Moved { x:event.x as f32,y:event.y as f32,dx:event.dx as f32,dy:event.dy as f32,
                // U4 does not encode outside button state. Query before requesting entry;
                // never permit a currently held mouse button to cross the edge.
                buttons_down:(0..32).any(|b| unsafe { CGEventSourceButtonState(1,b) }), now_ms:now_us/1000 }),
            1 => input(InputEvent::PointerDelta {dx:event.dx as f32,dy:event.dy as f32}),
            2 => input(InputEvent::MouseButton {button:event.button,is_down:event.down!=0}),
            3 => {
                // U4 emits raw NSEventPhase bits, not ScrollPhase tags. U4 currently
                // aliases finger and momentum phases; see docs/05 bridge limitations.
                let phase=if event.phase & (8|16)!=0 {ScrollPhase::Ended}
                    else if event.phase & 1!=0 {ScrollPhase::Began}
                    else if event.phase & (2|4|32)!=0 {ScrollPhase::Changed}
                    else {ScrollPhase::Discrete};
                input(InputEvent::PreciseScroll {dx:event.dx as f32,dy:event.dy as f32,phase})
            }
            4 => Some(BridgeEvent::Key {keycode:event.keycode,down:event.down!=0,flags:event.flags,now_us}),
            5 => Some(BridgeEvent::Flags {flags:event.flags,now_us}),
            6 => Some(BridgeEvent::Entered {ime_hint:unsafe {wp_uc_ime_hint()}}),
            7 => Some(BridgeEvent::Exited(wingpaned::uc::bridge_exit_reason(event.reason))),
            8 => Some(BridgeEvent::Status {tap_ok:event.autorepeat!=0,secure_input:event.down!=0}),
            _ => None,
        }
    }
}
pub struct UcGuard(pub std::sync::Arc<UcBridge>);
impl wingpaned::UcResource for UcGuard {
    fn release(&self) { self.0.stop(); }
}
impl Drop for UcBridge { fn drop(&mut self) { self.stop(); } }
