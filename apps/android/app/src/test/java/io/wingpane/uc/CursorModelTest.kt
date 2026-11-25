package io.wingpane.uc

import org.junit.Assert.*
import org.junit.Test

class CursorModelTest {
    @Test fun movementAppliesGainAndClamps() { val c=CursorModel(100,80,gain=2f); c.move(10f,-100f,1); assertEquals(69.5f,c.position().x,0.01f); assertEquals(0f,c.position().y,0.01f) }
    @Test fun exitNeedsOutwardPushAtConfiguredEdge() { val c=CursorModel(100,80,entryEdge=CursorModel.Edge.Left); c.setAlong(.5f); assertNull(c.move(-10f,0f,0)); assertNull(c.move(-10f,0f,100)); assertNotNull(c.move(-10f,0f,200)) }
    @Test fun alongRoundTripsAndRotationPreservesRatio() { val c=CursorModel(100,80); c.setAlong(.3f); assertEquals(.3f,c.along(),.01f); c.resize(80,100); assertEquals(.3f,c.along(),.01f) }
}
