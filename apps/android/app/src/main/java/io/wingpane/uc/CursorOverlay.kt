package io.wingpane.uc

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.Choreographer
import android.view.View
import android.view.WindowManager
import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat

/** Small accessibility overlay; hotspot sits one pixel outside its top-left corner. */
class CursorOverlay(private val service: AccessibilityService) {
    private val density = service.resources.displayMetrics.density
    private val size = (24 * density).toInt().coerceAtLeast(1)
    private val view = PointerView(service, size)
    private val params = WindowManager.LayoutParams(size,size,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT).apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS }
    private val wm = service.getSystemService(WindowManager::class.java)
    private var attached = false
    private var framePending = false
    private var px = 0f; private var py = 0f
    fun show() { if (!attached) { wm.addView(view,params); attached = true } }
    fun hide() { if (attached) { wm.removeView(view); attached = false } }
    fun update(x: Float,y: Float) { px=x; py=y; if (!attached || framePending) return; framePending=true; Choreographer.getInstance().postFrameCallback { framePending=false; if(attached){ params.x=(px*density).toInt()+1; params.y=(py*density).toInt()+1; wm.updateViewLayout(view,params) } } }
    private class PointerView(context: Context, private val size: Int): View(context) {
        private val outline=Paint(3).apply { color=android.graphics.Color.BLACK; style=Paint.Style.STROKE; strokeWidth=3f }
        private val fill=Paint(3).apply { color=android.graphics.Color.WHITE; style=Paint.Style.FILL }
        override fun onDraw(canvas: Canvas) { super.onDraw(canvas); val p=Path().apply { moveTo(0f,0f); lineTo(size*.68f,size*.78f); lineTo(size*.40f,size*.76f); lineTo(size*.31f,size.toFloat()); close() }; canvas.drawPath(p,outline); canvas.drawPath(p,fill) }
    }
}
