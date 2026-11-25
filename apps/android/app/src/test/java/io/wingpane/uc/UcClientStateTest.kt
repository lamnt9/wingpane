package io.wingpane.uc

import io.wingpane.proto.UcEdge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UcClientStateTest {
    @Test fun idleArmedInsideArmed() {
        val armed = reduceUc(UcClientState.Idle, UcClientEvent.Config(UcEdge.Right))
        assertEquals(UcClientState.Armed(UcEdge.Right), armed.state)
        val inside = reduceUc(armed.state, UcClientEvent.Enter(UcEdge.Right))
        assertEquals(UcClientState.Inside(UcEdge.Right), inside.state)
        assertTrue(UcClientEffect.ShowCursor in inside.effects)
        val exited = reduceUc(inside.state, UcClientEvent.Exit)
        assertEquals(UcClientState.Armed(UcEdge.Right), exited.state)
        assertTrue(UcClientEffect.CancelAll in exited.effects)
        assertTrue(UcClientEffect.HideCursor in exited.effects)
    }

    @Test fun exitWhileIdleIsIgnored() {
        assertEquals(UcClientTransition(UcClientState.Idle), reduceUc(UcClientState.Idle, UcClientEvent.Exit))
    }

    @Test fun disconnectInsideCancelsEverything() {
        val result = reduceUc(UcClientState.Inside(UcEdge.Top), UcClientEvent.Disconnected)
        assertEquals(UcClientState.Idle, result.state)
        assertEquals(listOf(UcClientEffect.CancelAll, UcClientEffect.HideCursor), result.effects)
    }
}
