package io.wingpane.ui

import io.wingpane.net.ClientState
import io.wingpane.proto.ControlMessage
import io.wingpane.proto.Mode
import io.wingpane.proto.RejectReason
import io.wingpane.proto.VideoCodec
import io.wingpane.ui.state.ClientStateMapper
import io.wingpane.ui.state.DisconnectConfirmStateMachine
import io.wingpane.ui.state.HudStatusDot
import io.wingpane.ui.state.LiveRegionPoliteness
import io.wingpane.ui.state.ScreenId
import io.wingpane.ui.state.SetupGuideUiModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientStateMappingTest {

    @Test
    fun `Waiting maps to S1 with exact copy and actions`() {
        val model = ClientStateMapper.map(ClientState.Waiting)
        assertEquals(ScreenId.S1_WAITING, model.screenId)
        assertEquals("Waiting for your Mac", model.title)
        assertEquals("Connect the USB cable and run Wingpane on your Mac.", model.body)
        assertEquals(
            "Waiting for your Mac — Connect the USB cable and run Wingpane on your Mac.",
            model.briefCopy,
        )
        assertNull(model.primaryActionLabel)
        assertEquals("Setup guide", model.secondaryActionLabel)
        assertFalse(model.isStreamScreen)
        assertEquals(LiveRegionPoliteness.POLITE, model.liveRegion)
    }

    @Test
    fun `Setup guide model has exact 3 steps and actions`() {
        val guide = SetupGuideUiModel()
        assertEquals("Setup guide", guide.title)
        assertEquals(3, guide.steps.size)
        assertEquals("Open Developer options", guide.primaryActionLabel)
        assertEquals("Done", guide.secondaryActionLabel)
        assertEquals("wingpane", guide.steps[2].codeBadge)
    }

    @Test
    fun `Handshaking maps to S3 with exact copy and Cancel action`() {
        val model = ClientStateMapper.map(ClientState.Handshaking)
        assertEquals(ScreenId.S3_HANDSHAKING, model.screenId)
        assertEquals("Connecting to your Mac…", model.title)
        assertNull(model.body)
        assertEquals("Connecting to your Mac…", model.briefCopy)
        assertEquals("Cancel", model.primaryActionLabel)
        assertNull(model.secondaryActionLabel)
        assertFalse(model.isStreamScreen)
    }

    @Test
    fun `Active maps to Stream screen with display spec and bitrate`() {
        val accept = ControlMessage.Accept(
            selectedMode = Mode.ExtendedDisplay,
            virtualDisplaySpec = Triple(2560u, 1600u, 60u),
            selectedCodec = VideoCodec.H264,
            bitrateKbps = 35_200u,
        )
        val model = ClientStateMapper.map(ClientState.Active(accept))
        assertEquals(ScreenId.STREAM_ACTIVE, model.screenId)
        assertTrue(model.isStreamScreen)
        assertFalse(model.isReconnecting)
        assertEquals(35_200u, model.streamBitrateKbps)
        assertEquals(2560, model.virtualDisplayWidth)
        assertEquals(1600, model.virtualDisplayHeight)
    }

    @Test
    fun `Reconnecting maps to Stream Reconnecting overlay with Cancel action`() {
        val model = ClientStateMapper.map(ClientState.Reconnecting)
        assertEquals(ScreenId.STREAM_RECONNECTING, model.screenId)
        assertTrue(model.isStreamScreen)
        assertTrue(model.isReconnecting)
        assertEquals("Reconnecting…", model.title)
        assertEquals("Cancel", model.primaryActionLabel)
    }

    @Test
    fun `Rejected VersionMismatch maps to S5a with formatted protocol version copy`() {
        val modelDefault = ClientStateMapper.map(ClientState.Rejected(RejectReason.VersionMismatch))
        assertEquals(ScreenId.S5A_VERSION_MISMATCH, modelDefault.screenId)
        assertEquals("Update needed", modelDefault.title)
        assertEquals("Mac uses protocol v2, this app v1.", modelDefault.body)
        assertEquals("Update needed — Mac uses protocol v2, this app v1.", modelDefault.briefCopy)
        assertEquals("Update app", modelDefault.primaryActionLabel)
        assertEquals("Try again", modelDefault.secondaryActionLabel)
        assertEquals("Troubleshooting", modelDefault.tertiaryActionLabel)

        val modelV4 = ClientStateMapper.map(
            ClientState.Rejected(RejectReason.VersionMismatch),
            hostVersionOverride = 4,
        )
        assertEquals("Mac uses protocol v4, this app v1.", modelV4.body)
        assertEquals("Update needed — Mac uses protocol v4, this app v1.", modelV4.briefCopy)
    }

    @Test
    fun `Rejected UnsupportedCodec maps to S5b with exact copy and Troubleshooting`() {
        val model = ClientStateMapper.map(ClientState.Rejected(RejectReason.UnsupportedCodec))
        assertEquals(ScreenId.S5B_UNSUPPORTED_DEVICE, model.screenId)
        assertEquals("This tablet can't decode H.264 video.", model.title)
        assertNull(model.body)
        assertEquals("This tablet can't decode H.264 video.", model.briefCopy)
        assertEquals("Troubleshooting", model.primaryActionLabel)
        assertNull(model.secondaryActionLabel)
    }

    @Test
    fun `Rejected Busy maps to S5c with exact copy and Try again`() {
        val model = ClientStateMapper.map(ClientState.Rejected(RejectReason.Busy))
        assertEquals(ScreenId.S5C_BUSY, model.screenId)
        assertEquals("Your Mac is already connected to another device.", model.title)
        assertNull(model.body)
        assertEquals("Your Mac is already connected to another device.", model.briefCopy)
        assertEquals("Try again", model.primaryActionLabel)
        assertNull(model.secondaryActionLabel)
    }

    @Test
    fun `Rejected InvalidHello and HandshakeFailed map to S5d with brief copy only`() {
        val fromReject = ClientStateMapper.map(ClientState.Rejected(RejectReason.InvalidHello))
        val fromTimeout = ClientStateMapper.map(ClientState.HandshakeFailed)

        for (model in listOf(fromReject, fromTimeout)) {
            assertEquals(ScreenId.S5D_HANDSHAKE_FAILED, model.screenId)
            assertEquals("Couldn't connect to your Mac.", model.title)
            // Review note: S5d uses the brief copy only (no "secure link" sentence)
            assertNull(model.body)
            assertEquals("Couldn't connect to your Mac.", model.briefCopy)
            assertFalse(model.briefCopy.contains("secure link", ignoreCase = true))
            assertEquals("Try again", model.primaryActionLabel)
            assertEquals("Troubleshooting", model.secondaryActionLabel)
            assertEquals(LiveRegionPoliteness.POLITE, model.liveRegion)
        }
    }

    @Test
    fun `Lost maps to S5e with exact copy and Assertive liveRegion`() {
        val model = ClientStateMapper.map(ClientState.Lost)
        assertEquals(ScreenId.S5E_CONNECTION_LOST, model.screenId)
        assertEquals("Connection to your Mac was lost.", model.title)
        assertNull(model.body)
        assertEquals("Connection to your Mac was lost.", model.briefCopy)
        assertEquals("Try again", model.primaryActionLabel)
        assertEquals("Troubleshooting", model.secondaryActionLabel)
        assertEquals(LiveRegionPoliteness.ASSERTIVE, model.liveRegion)
    }

    @Test
    fun `HUD status dot rules follow 35ms warning and reconnecting danger thresholds`() {
        assertEquals(HudStatusDot.NONE, ClientStateMapper.resolveHudStatusDot(isReconnecting = false, latencyMs = 12L))
        assertEquals(HudStatusDot.NONE, ClientStateMapper.resolveHudStatusDot(isReconnecting = false, latencyMs = 35L))
        assertEquals(HudStatusDot.WARNING, ClientStateMapper.resolveHudStatusDot(isReconnecting = false, latencyMs = 36L))
        assertEquals(HudStatusDot.DANGER, ClientStateMapper.resolveHudStatusDot(isReconnecting = true, latencyMs = 10L))
        assertEquals(HudStatusDot.DANGER, ClientStateMapper.resolveHudStatusDot(isReconnecting = true, latencyMs = 50L))
    }

    @Test
    fun `Disconnect confirm timer arms for 3s, confirms on second tap, and never expires when TalkBack is on`() {
        val machine = DisconnectConfirmStateMachine()
        assertFalse(machine.isConfirming)
        assertEquals("Disconnect", machine.label())

        // First tap arms confirm state
        assertFalse(machine.onTap(nowMs = 1_000L, talkBackEnabled = false))
        assertTrue(machine.isConfirming)
        assertEquals("Tap again to disconnect", machine.label())

        // Second tap within 3s confirms disconnect
        assertTrue(machine.onTap(nowMs = 3_999L, talkBackEnabled = false))
        assertFalse(machine.isConfirming)
        assertEquals("Disconnect", machine.label())

        // Re-arm and let 3s expire
        assertFalse(machine.onTap(nowMs = 5_000L, talkBackEnabled = false))
        assertTrue(machine.isConfirming)
        assertFalse(machine.updateTime(nowMs = 8_000L, talkBackEnabled = false))
        assertFalse(machine.isConfirming)
        assertEquals("Disconnect", machine.label())

        // Tap after expiry only re-arms (does not disconnect)
        assertFalse(machine.onTap(nowMs = 8_500L, talkBackEnabled = false))
        assertTrue(machine.isConfirming)

        // When TalkBack is enabled, confirm timer does not expire after 3s
        assertTrue(machine.updateTime(nowMs = 20_000L, talkBackEnabled = true))
        assertTrue(machine.onTap(nowMs = 20_000L, talkBackEnabled = true))
        assertFalse(machine.isConfirming)
    }
}
