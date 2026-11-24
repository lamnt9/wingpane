/// Applies a cubic Bézier curve to stylus pressure values.
/// Controls: `[x1, y1, x2, y2]` where P0 = (0,0) and P3 = (1,1).
pub fn apply_pressure_curve(pressure: f32, curve: &[f32; 4]) -> f32 {
    if pressure.is_nan() {
        return 0.0;
    }
    let p = pressure.clamp(0.0, 1.0);
    let (x1, y1, x2, y2) = (
        curve[0].clamp(0.0, 1.0),
        curve[1],
        curve[2].clamp(0.0, 1.0),
        curve[3],
    );
    let bezier = |t: f32, a: f32, b: f32| {
        3.0 * (1.0 - t).powi(2) * t * a + 3.0 * (1.0 - t) * t * t * b + t.powi(3)
    };
    let (mut lo, mut hi) = (0.0_f32, 1.0_f32);
    for _ in 0..24 {
        let mid = (lo + hi) / 2.0;
        if bezier(mid, x1, x2) < p {
            lo = mid
        } else {
            hi = mid
        }
    }
    bezier((lo + hi) / 2.0, y1, y2).clamp(0.0, 1.0)
}
