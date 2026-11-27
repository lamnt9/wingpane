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
    @Test fun tapThenTouchAndMoveDragsAndAlwaysReleases() {
        val d=TrackpadGestureDetector(); d.onTouch(TrackpadGestureDetector.Action.Down,listOf(p(0f)),0)
        d.onTouch(TrackpadGestureDetector.Action.Up,listOf(p(0f)),80)
        assertEquals(listOf(InputEvent.MouseButton(0u,true)), d.onTouch(TrackpadGestureDetector.Action.Down,listOf(p(0f)),200))
        assertEquals(listOf(InputEvent.PointerDelta(30f,0f)), d.onTouch(TrackpadGestureDetector.Action.Move,listOf(p(30f)),230))
        assertEquals(listOf(InputEvent.MouseButton(0u,false)), d.onTouch(TrackpadGestureDetector.Action.Up,emptyList(),400))
        // A touch long after the tap is a normal move, not a drag.
        d.onTouch(TrackpadGestureDetector.Action.Down,listOf(p(0f)),1000)
        d.onTouch(TrackpadGestureDetector.Action.Up,listOf(p(0f)),1050)
        assertTrue(d.onTouch(TrackpadGestureDetector.Action.Down,listOf(p(0f)),1500).isEmpty())
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

    private fun ring(radius: Float): List<TrackpadGestureDetector.PointerSample> = listOf(
        p(radius,0f,0), p(0f,radius,1), p(-radius,0f,2), p(0f,-radius,3), p(radius*.7f,radius*.7f,4)
    )
    @Test fun threeFingerTapIsEmittedOnUpOnly() {
        val d=TrackpadGestureDetector()
        d.onTouch(TrackpadGestureDetector.Action.Down,listOf(p(0f),p(10f,0f,1),p(20f,0f,2)),0)
        assertTrue(d.onTouch(TrackpadGestureDetector.Action.Up,emptyList(),100).contains(InputEvent.Gesture(GestureKind.ThreeFingerTap)))
        val moved=TrackpadGestureDetector()
        moved.onTouch(TrackpadGestureDetector.Action.Down,listOf(p(0f),p(10f,0f,1),p(20f,0f,2)),0)
        moved.onTouch(TrackpadGestureDetector.Action.Move,listOf(p(0f),p(20f,0f,1),p(20f,0f,2)),40)
        assertTrue(moved.onTouch(TrackpadGestureDetector.Action.Up,emptyList(),80).none { it == InputEvent.Gesture(GestureKind.ThreeFingerTap) })
    }
    @Test fun fourFingerSwipeDirectionsAndLatch() {
        val cases=listOf(-70f to 0f to GestureKind.Swipe4Left, 70f to 0f to GestureKind.Swipe4Right,
            0f to -70f to GestureKind.Swipe4Up, 0f to 70f to GestureKind.Swipe4Down)
        cases.forEach { (delta, kind) ->
            val d=TrackpadGestureDetector()
            val base=listOf(p(0f,0f,0),p(20f,0f,1),p(40f,0f,2),p(60f,0f,3))
            d.onTouch(TrackpadGestureDetector.Action.Down,base,0)
            val moved=base.map { it.copy(x=it.x+delta.first,y=it.y+delta.second) }
            val one=d.onTouch(TrackpadGestureDetector.Action.Move,moved,40)
            assertEquals(listOf(InputEvent.Gesture(kind)),one)
            assertTrue(d.onTouch(TrackpadGestureDetector.Action.Move,base.map { it.copy(x=it.x+delta.first*2,y=it.y+delta.second*2) },60).isEmpty())
        }
    }
    @Test fun fiveFingerPinchSpreadAndOneEventLatchUseCentroidSpread() {
        listOf(0.6f to GestureKind.FiveFingerPinchIn, 1.4f to GestureKind.FiveFingerSpread).forEach { (ratio,kind) ->
            val d=TrackpadGestureDetector()
            d.onTouch(TrackpadGestureDetector.Action.Down,ring(20f),0)
            val events=d.onTouch(TrackpadGestureDetector.Action.Move,ring(20f*ratio),50)
            assertEquals(listOf(InputEvent.Gesture(kind)),events)
            assertTrue(d.onTouch(TrackpadGestureDetector.Action.Move,ring(if (ratio<1) 8f else 32f),80).isEmpty())
        }
    }
    @Test fun maxThreeFourAndFiveFingerSequencesSuppressPointerScrollTapAndInertia() {
        // A 3 -> 2 transition must not become a two-finger scroll.
        val three=TrackpadGestureDetector()
        three.onTouch(TrackpadGestureDetector.Action.Down,listOf(p(0f),p(20f,0f,1),p(40f,0f,2)),0)
        assertTrue(three.onTouch(TrackpadGestureDetector.Action.Move,listOf(p(0f),p(20f,0f,1)),20).isEmpty())
        assertTrue(three.onTouch(TrackpadGestureDetector.Action.Move,listOf(p(20f),p(40f,0f,1)),40).isEmpty())
        assertTrue(three.tick(100).isEmpty())
        // One-by-one landing reaches five; no 2-finger scroll leaks before/after classification.
        val many=TrackpadGestureDetector()
        many.onTouch(TrackpadGestureDetector.Action.Down,listOf(p(0f)),0)
        many.onTouch(TrackpadGestureDetector.Action.Move,listOf(p(0f),p(20f,0f,1)),10)
        many.onTouch(TrackpadGestureDetector.Action.Move,listOf(p(0f),p(20f,0f,1),p(40f,0f,2)),20)
        many.onTouch(TrackpadGestureDetector.Action.Move,listOf(p(0f),p(20f,0f,1),p(40f,0f,2),p(60f,0f,3)),30)
        val five=listOf(p(0f),p(20f,0f,1),p(40f,0f,2),p(60f,0f,3),p(80f,0f,4))
        assertTrue(many.onTouch(TrackpadGestureDetector.Action.Move,five,40).isEmpty())
        assertTrue(many.onTouch(TrackpadGestureDetector.Action.Move,five.map { it.copy(y=20f) },60).isEmpty())
        assertTrue(many.onTouch(TrackpadGestureDetector.Action.Move,five.take(2).map { it.copy(x=it.x+30f) },80).isEmpty())
        assertTrue(many.onTouch(TrackpadGestureDetector.Action.Up,emptyList(),100).isEmpty())
        assertTrue(many.tick(150).isEmpty())
    }
    @Test fun buttonHeldTouchOnlyMovesAndNeverClicksEvenIfReleasedMidTouch() {
        val d=TrackpadGestureDetector(); d.setButtonHeld(true)
        d.onTouch(TrackpadGestureDetector.Action.Down,listOf(p(0f)),0)
        assertTrue(d.onTouch(TrackpadGestureDetector.Action.Move,listOf(p(20f)),30).single() is InputEvent.PointerDelta)
        d.setButtonHeld(false)
        assertTrue(d.onTouch(TrackpadGestureDetector.Action.Up,emptyList(),80).isEmpty())
        d.setButtonHeld(true)
        d.onTouch(TrackpadGestureDetector.Action.Down,listOf(p(0f)),200)
        assertTrue(d.onTouch(TrackpadGestureDetector.Action.Move,listOf(p(0f)),400).isEmpty())
        assertTrue(d.onTouch(TrackpadGestureDetector.Action.Up,emptyList(),420).isEmpty())
        d.setButtonHeld(false)
        assertTrue(d.onTouch(TrackpadGestureDetector.Action.Down,listOf(p(0f)),500).isEmpty()) // no tap-and-a-half from held touches
        assertEquals(listOf(InputEvent.Gesture(io.wingpane.proto.GestureKind.Tap)), d.onTouch(TrackpadGestureDetector.Action.Up,emptyList(),550))
    }
}
