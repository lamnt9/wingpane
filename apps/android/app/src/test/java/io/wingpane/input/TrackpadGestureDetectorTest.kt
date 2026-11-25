package io.wingpane.input

import io.wingpane.proto.InputEvent
import io.wingpane.proto.GestureKind
import org.junit.Assert.*
import org.junit.Test

class TrackpadGestureDetectorTest {
    private fun p(x:Float,y:Float=0f,id:Int=0)=TrackpadGestureDetector.PointerSample(id,x,y)
    @Test fun tapAndMoveThreshold() {
        val d=TrackpadGestureDetector(); d.onTouch(TrackpadGestureDetector.Action.Down,listOf(p(0f)),0)
        assertEquals(listOf(InputEvent.Gesture(GestureKind.Tap)),d.onTouch(TrackpadGestureDetector.Action.Up,listOf(p(1f)),100))
        d.onTouch(TrackpadGestureDetector.Action.Down,listOf(p(0f)),200)
        assertTrue(d.onTouch(TrackpadGestureDetector.Action.Move,listOf(p(12f)),220).single() is InputEvent.PointerDelta)
    }
    @Test fun twoFingerTapProducesRightClick() {
        val d=TrackpadGestureDetector(); d.onTouch(TrackpadGestureDetector.Action.Down,listOf(p(0f)),0)
        d.onTouch(TrackpadGestureDetector.Action.Move,listOf(p(0f),p(0f,12f,1)),40)
        assertEquals(listOf(InputEvent.MouseButton(1u,true),InputEvent.MouseButton(1u,false)), d.onTouch(TrackpadGestureDetector.Action.Up,emptyList(),90))
    }
    @Test fun twoFingerScrollAndDragCancelAlwaysReleases() {
        val d=TrackpadGestureDetector(); d.onTouch(TrackpadGestureDetector.Action.Down,listOf(p(0f),p(0f,10f,1)),0)
        assertTrue(d.onTouch(TrackpadGestureDetector.Action.Move,listOf(p(20f),p(20f,10f,1)),20).single() is InputEvent.Scroll)
        val drag=TrackpadGestureDetector();drag.onTouch(TrackpadGestureDetector.Action.Down,listOf(p(0f)),0)
        assertEquals(InputEvent.MouseButton(0u,true),drag.onTouch(TrackpadGestureDetector.Action.Move,listOf(p(0f)),181).single())
        assertEquals(InputEvent.MouseButton(0u,false),drag.onTouch(TrackpadGestureDetector.Action.Cancel,emptyList(),190).single())
    }
    @Test fun threeFingerSwipeAndPinchAreDetected() {
        val swipe=TrackpadGestureDetector()
        swipe.onTouch(TrackpadGestureDetector.Action.Down,listOf(p(0f),p(20f,0f,1),p(40f,0f,2)),0)
        assertTrue(swipe.onTouch(TrackpadGestureDetector.Action.Move,listOf(p(0f,-70f),p(20f,-70f,1),p(40f,-70f,2)),50).contains(InputEvent.Gesture(GestureKind.Swipe3Up)))
        val pinch=TrackpadGestureDetector()
        pinch.onTouch(TrackpadGestureDetector.Action.Down,listOf(p(0f),p(20f,0f,1)),0)
        val events=pinch.onTouch(TrackpadGestureDetector.Action.Move,listOf(p(-3f),p(23f,0f,1)),50)
        assertTrue(events.contains(InputEvent.Gesture(GestureKind.PinchZoom(1.15f))))
    }
}
