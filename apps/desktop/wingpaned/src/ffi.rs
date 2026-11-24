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
