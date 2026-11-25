package io.wingpane.uc

import org.junit.Assert.*
import org.junit.Test

class PointerGesturePlannerTest {
    @Test fun shortClickTapsOnce() { val p=PointerGesturePlanner(); p.onEvent(CursorEvent.Button(0,true,10f,20f,0)); assertEquals(listOf(GesturePlan.Tap(10f,20f)),p.onEvent(CursorEvent.Button(0,false,10f,20f,80))) }
    @Test fun movementPastSlopStartsDragAndCancelEndsIt() { val p=PointerGesturePlanner(); p.onEvent(CursorEvent.Button(0,true,0f,0f,0)); assertTrue(p.onEvent(CursorEvent.Move(7f,0f,20)).first() is GesturePlan.StrokeStart); assertTrue(p.cancelAll().single() is GesturePlan.StrokeEnd) }
    @Test fun rightButtonLongPressAndMiddleIgnored() { val p=PointerGesturePlanner(); p.onEvent(CursorEvent.Button(1,true,1f,2f,0)); assertTrue(p.onEvent(CursorEvent.Button(1,false,1f,2f,10)).single() is GesturePlan.LongPress); p.onEvent(CursorEvent.Button(2,true,1f,2f,20)); assertTrue(p.onEvent(CursorEvent.Button(2,false,1f,2f,30)).isEmpty()) }
    @Test fun momentumScrollIgnored() { val p=PointerGesturePlanner(); assertTrue(p.onEvent(CursorEvent.Scroll(0f,10f,CursorEvent.Phase.Momentum,0)).isEmpty()) }
}
