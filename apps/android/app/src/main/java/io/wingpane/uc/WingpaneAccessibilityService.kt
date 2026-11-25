package io.wingpane.uc

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.InputMethod
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Path
import android.view.inputmethod.EditorInfo
import android.view.accessibility.AccessibilityEvent
import java.lang.ref.WeakReference

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
        fun scroll(dx: Float, dy: Float, phase: CursorEvent.Phase, nowMs: Long = android.os.SystemClock.uptimeMillis())
        fun global(action: Int): Boolean
        fun text(value: String)
        fun cursor(): CursorModel.Position
    }
    private var overlay: CursorOverlay? = null
    private var cursorModel: CursorModel? = null
    private lateinit var adapter: GestureAdapter
    private val planner = PointerGesturePlanner()
    private var buttonsDown = false
    private val injector = object : UcInjector {
        override fun move(dx: Float,dy: Float,nowMs:Long) {
            val model=cursorModel ?: return
            val old=model.position(); model.move(dx,dy,nowMs,resources.displayMetrics.density)
            overlay?.update(model.position().x,model.position().y)
            planner.onEvent(CursorEvent.Move(model.position().x,model.position().y,nowMs)).forEach(adapter::submit)
        }
        override fun button(button:Int,down:Boolean,nowMs:Long) {
            val p=cursorModel?.position() ?: return
            buttonsDown=down
            planner.onEvent(CursorEvent.Button(button,down,p.x,p.y,nowMs)).forEach(adapter::submit)
        }
        override fun scroll(dx:Float,dy:Float,phase:CursorEvent.Phase,nowMs:Long) {
            planner.onEvent(CursorEvent.Scroll(dx,dy,phase,nowMs)).forEach(adapter::submit)
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
        cursorModel=CursorModel(dm.widthPixels,dm.heightPixels)
        val queue=GestureQueue({ gesture,callback -> dispatchGesture(gesture,callback,null) })
        adapter=GestureAdapter(queue)
        overlay=CursorOverlay(this).also { it.show(); it.update(cursorModel!!.position().x,cursorModel!!.position().y) }
        Registry.ref=WeakReference(this)
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) { }
    override fun onInterrupt() { planner.cancelAll().forEach(adapter::submit); overlay?.hide(); setRemoteKeyboardActive(false); Registry.ref=null }
    override fun onUnbind(intent: Intent?): Boolean { onInterrupt(); return super.onUnbind(intent) }
    override fun onDestroy() { onInterrupt(); super.onDestroy() }
    fun injector(): UcInjector = injector
    private object Registry { @Volatile var ref: WeakReference<WingpaneAccessibilityService>?=null }
    companion object {
        fun current(): WingpaneAccessibilityService? = Registry.ref?.get()
    }
}
