package io.wingpane.uc

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.InputMethod
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.ViewConfiguration
import android.view.inputmethod.EditorInfo
import android.view.accessibility.AccessibilityEvent
import java.lang.ref.WeakReference
import io.wingpane.WingpaneApplication

/** Standalone U1 injector. It has no dependency on a Mac connection or protocol traffic. */
class WingpaneAccessibilityService : AccessibilityService() {
    var editorFocused: Boolean = false
        private set

    // The framework only calls this on API 33+, where InputMethod exists.
    @SuppressLint("NewApi")
    override fun onCreateInputMethod(): InputMethod = object : InputMethod(this) {
        override fun onStartInput(editorInfo: EditorInfo, restarting: Boolean) {
            super.onStartInput(editorInfo, restarting)
            editorFocused = true
        }
        override fun onFinishInput() {
            editorFocused = false
            super.onFinishInput()
        }
    }

    fun setRemoteKeyboardActive(active: Boolean) {
        softKeyboardController.showMode = if (active) SHOW_MODE_HIDDEN else SHOW_MODE_AUTO
    }
    interface UcInjector {
        fun move(dx: Float, dy: Float, nowMs: Long = android.os.SystemClock.uptimeMillis())
        fun button(button: Int, down: Boolean, nowMs: Long = android.os.SystemClock.uptimeMillis())
        fun scroll(dx: Float, dy: Float, phase: CursorEvent.Phase, invert: Boolean = false, nowMs: Long = android.os.SystemClock.uptimeMillis())
        fun global(action: Int): Boolean
        fun text(value: String)
        fun cursor(): CursorModel.Position
    }
    private var overlay: CursorOverlay? = null
    private var cursorModel: CursorModel? = null
    private var queue: GestureQueue? = null
    private var controller: UcController? = null
    private lateinit var adapter: GestureAdapter
    private lateinit var planner: PointerGesturePlanner
    private lateinit var scrollPlanner: ScrollPlanner
    private val scrollHandler = Handler(Looper.getMainLooper())
    private val flushWheel = Runnable {
        scrollPlanner.flushWheel(cursorModel?.position() ?: return@Runnable, android.os.SystemClock.uptimeMillis())
            .forEach(adapter::submit)
    }
    private var buttonsDown = false
    private val injector = object : UcInjector {
        override fun move(dx: Float,dy: Float,nowMs:Long) {
            val model=cursorModel ?: return
            model.move(dx * resources.displayMetrics.density,dy * resources.displayMetrics.density,nowMs,resources.displayMetrics.density)?.let {
                controller?.returnMouse()
                return
            }
            overlay?.update(model.position().x/resources.displayMetrics.density,model.position().y/resources.displayMetrics.density)
            planner.onEvent(CursorEvent.Move(model.position().x,model.position().y,nowMs)).forEach(adapter::submit)
        }
        override fun button(button:Int,down:Boolean,nowMs:Long) {
            val p=cursorModel?.position() ?: return
            buttonsDown=down
            planner.onEvent(CursorEvent.Button(button,down,p.x,p.y,nowMs)).forEach(adapter::submit)
        }
        override fun scroll(dx:Float,dy:Float,phase:CursorEvent.Phase,invert:Boolean,nowMs:Long) {
            val density = resources.displayMetrics.density
            val cursor = cursorModel?.position() ?: return
            scrollPlanner.onScroll(CursorEvent.Scroll(dx*density,dy*density,phase,nowMs), cursor, invert).forEach(adapter::submit)
            if (phase == CursorEvent.Phase.Discrete) {
                scrollHandler.removeCallbacks(flushWheel)
                scrollHandler.postDelayed(flushWheel,41)
            }
        }
        override fun global(action:Int)=performGlobalAction(action)
        override fun text(value:String) {
            val node=rootInActiveWindow?.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT) ?: return
            node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply { putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value) })
            node.recycle()
        }
        override fun cursor()=cursorModel?.position() ?: CursorModel.Position(0f,0f)
    }
    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo.apply {
            eventTypes=AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOWS_CHANGED or AccessibilityEvent.TYPE_VIEW_FOCUSED
            feedbackType=AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags=AccessibilityServiceInfo.DEFAULT or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_INPUT_METHOD_EDITOR
            notificationTimeout=0
        }
        val dm=resources.displayMetrics
        planner=PointerGesturePlanner(ViewConfiguration.getLongPressTimeout()+100L,6f*dm.density)
        scrollPlanner=ScrollPlanner(ViewConfiguration.get(this).scaledTouchSlop.toFloat())
        cursorModel=CursorModel(dm.widthPixels,dm.heightPixels)
        queue=GestureQueue({ gesture,callback -> dispatchGesture(gesture,callback,null) })
        adapter=GestureAdapter(queue!!)
        overlay=CursorOverlay(this)
        Registry.ref=WeakReference(this)
        controller=UcController(this, (application as WingpaneApplication).clientHolder.client).also { it.start() }
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) { }
    override fun onInterrupt() {
        controller?.stop(); controller=null
        cancelUcGestures(); overlay?.hide(); setRemoteKeyboardActive(false); Registry.ref=null
    }
    override fun onUnbind(intent: Intent?): Boolean { onInterrupt(); return super.onUnbind(intent) }
    override fun onDestroy() { onInterrupt(); super.onDestroy() }
    fun injector(): UcInjector = injector
    fun enterUc(edge: CursorModel.Edge, along: Float, gain: Float) {
        val dm=resources.displayMetrics
        val model=cursorModel ?: CursorModel(dm.widthPixels,dm.heightPixels).also { cursorModel=it }
        model.resize(dm.widthPixels,dm.heightPixels)
        model.entryEdge=edge; model.gain=gain; model.setAlong(along)
        overlay?.show(); overlay?.update(model.x/dm.density,model.y/dm.density)
        setRemoteKeyboardActive(true)
    }
    fun exitUc() { cancelUcGestures(); overlay?.hide(); setRemoteKeyboardActive(false) }
    // Drop pending moves, then submit the StrokeEnd from cancelAll() so a drag in progress lifts its finger.
    private fun cancelUcGestures() {
        scrollHandler.removeCallbacks(flushWheel)
        queue?.clear()
        if (::scrollPlanner.isInitialized && ::adapter.isInitialized) scrollPlanner.cancel().forEach(adapter::submit)
        if (::planner.isInitialized && ::adapter.isInitialized) planner.cancelAll().forEach(adapter::submit)
    }
    fun cursorAlong(): Float = cursorModel?.along() ?: 0.5f
    fun returnMouse() { controller?.returnMouse() }
    fun overlayShown(): Boolean = controller?.inside == true
    fun ucEdge(): io.wingpane.proto.UcEdge? = controller?.edge
    fun gesturesAvailable(): Boolean = serviceInfo?.capabilities?.and(AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES) != 0
    private object Registry { @Volatile var ref: WeakReference<WingpaneAccessibilityService>?=null }
    companion object {
        fun current(): WingpaneAccessibilityService? = Registry.ref?.get()
    }
}
