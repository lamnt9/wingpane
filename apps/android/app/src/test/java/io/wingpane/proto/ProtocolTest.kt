package io.wingpane.proto

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random
import io.wingpane.ui.hud.PressureCurvePresets

class ProtocolTest {
    private fun expected(): Map<String, Packet> {
        val c=listOf(VideoCodec.H264,VideoCodec.Hevc)
        val controls=mapOf(
            "control_hello" to ControlMessage.Hello(1u,"Wingpane",2560u to 1600u,c,120u),
            "control_accept" to ControlMessage.Accept(Mode.GraphicsTablet,Triple(1920u,1080u,60u),VideoCodec.H264,12000u),
            "control_set_mode" to ControlMessage.SetMode(Mode.TrackpadKeyboard),
            "control_request_keyframe" to ControlMessage.RequestKeyframe,
            "control_ping" to ControlMessage.Ping(0x0102030405060708uL),
            "control_pong" to ControlMessage.Pong(0x0807060504030201uL),
            "control_disconnect" to ControlMessage.Disconnect,
            "control_host_time" to ControlMessage.HostTime(0x1020304050607080uL, 0x2010203040506070uL),
            "control_capabilities" to ControlMessage.Capabilities(0x4fu),
            "control_pen_config" to ControlMessage.PenConfig(listOf(.25f,.25f,.75f,.75f), PenTarget.MainDisplay),
            "control_set_mode_android_control" to ControlMessage.SetMode(Mode.AndroidControl),
            "control_uc_config" to ControlMessage.UcConfig(UcEdge.Right),
            "control_uc_enter" to ControlMessage.UcEnter(UcEdge.Right,.375f,2u.toUByte()),
            "control_uc_exit" to ControlMessage.UcExit(.625f,UcExitReason.Hotkey),
            "control_uc_status" to ControlMessage.UcStatus(0x13u),
            "reject_version_mismatch" to ControlMessage.Reject(RejectReason.VersionMismatch,1u),
            "reject_unsupported_codec" to ControlMessage.Reject(RejectReason.UnsupportedCodec),
            "reject_invalid_hello" to ControlMessage.Reject(RejectReason.InvalidHello),
            "reject_busy" to ControlMessage.Reject(RejectReason.Busy),
        ).mapValues{Packet.Control(it.value)}
        val base=300u;val ts=5_000_000_123uL
        return controls + mapOf(
            "video_keyframe" to Packet.Video(VideoPacket(base,ts,true,byteArrayOf(0,0,0,1,0x67))),
            "input_pen" to Packet.Input(InputPacket(base,ts,InputEvent.Pen(.125f,.875f,.75f,(-37).toByte(),22.toByte(),true,false,5u,ts))),
            "input_touch" to Packet.Input(InputPacket(base,ts,InputEvent.Touch(7u,.25f,.625f,TouchPhase.Move))),
            "input_pointer_delta" to Packet.Input(InputPacket(base,ts,InputEvent.PointerDelta(-12.5f,3.25f))),
            "input_scroll" to Packet.Input(InputPacket(base,ts,InputEvent.Scroll(1.5f,-2.75f))),
            "input_gesture" to Packet.Input(InputPacket(base,ts,InputEvent.Gesture(GestureKind.PinchZoom(1.25f)))),
            "input_keyboard" to Packet.Input(InputPacket(base,ts,InputEvent.Keyboard(0x1234u,true,5u))),
            "input_mouse_button" to Packet.Input(InputPacket(base,ts,InputEvent.MouseButton(0u,true))),
            "input_text" to Packet.Input(InputPacket(base,ts,InputEvent.Text("Tiếng Việt 😀"))),
            "input_precise_scroll" to Packet.Input(InputPacket(base,ts,InputEvent.PreciseScroll(-3.5f,12.25f,ScrollPhase.Changed))),
        )
    }

    @Test fun everyRustGoldenVectorDecodesAndEncodes() {
        val expected=expected()
        val lines=javaClass.classLoader!!.getResource("packets.txt")!!.readText().lines().filter{it.isNotBlank()&& !it.startsWith("#")}
        assertEquals(expected.keys,lines.map{it.substringBefore(' ')}.toSet())
        lines.forEach { line ->
            val (name,hex)=line.split(' ',limit=2)
            val bytes=hex.chunked(2).map{it.toInt(16).toByte()}.toByteArray()
            val decoder=FrameDecoder();decoder.push(bytes)
            assertEquals(name,expected[name],decoder.next())
            assertNull(decoder.next())
            assertArrayEquals(name,bytes,expected.getValue(name).encodeFrame())
        }
    }

    @Test fun decoderHandlesFragmentedAndCoalescedFrames() {
        val frame=Packet.Control(ControlMessage.Ping(0x1_0000_0000uL)).encodeFrame()
        val d=FrameDecoder();frame.forEach{d.push(byteArrayOf(it))}
        assertEquals(Packet.Control(ControlMessage.Ping(0x1_0000_0000uL)),d.next())
        val both=FrameDecoder();both.push(frame+frame)
        assertEquals(Packet.Control(ControlMessage.Ping(0x1_0000_0000uL)),both.next())
        assertEquals(Packet.Control(ControlMessage.Ping(0x1_0000_0000uL)),both.next())
    }
    @Test fun unknownControlTagIsSkippedAndNextFrameDecodes() {
        val unknown = byteArrayOf(2,0,0,0,0,99)
        val ping = Packet.Control(ControlMessage.Ping(42uL)).encodeFrame()
        val decoder = FrameDecoder(); decoder.push(unknown + ping)
        assertEquals(Packet.Control(ControlMessage.Ping(42uL)), decoder.next())
        assertEquals(1L, decoder.skippedFrames)
        assertNull(decoder.next())
    }
    @Test fun unknownNestedModeAndInputTagsAreSkipped() {
        val ping = Packet.Control(ControlMessage.Ping(42uL)).encodeFrame()
        // Packet::Control, SetMode with unknown Mode discriminant.
        val unknownMode = byteArrayOf(3,0,0,0,0,2,99)
        val decoder = FrameDecoder(); decoder.push(unknownMode + ping)
        assertEquals(Packet.Control(ControlMessage.Ping(42uL)), decoder.next())
        assertEquals(1L, decoder.skippedFrames)
        // Packet::Input, seq=0, ts=0, unknown InputEvent discriminant.
        val unknownInput = byteArrayOf(4,0,0,0,2,0,0,99)
        val inputDecoder = FrameDecoder(); inputDecoder.push(unknownInput + ping)
        assertEquals(Packet.Control(ControlMessage.Ping(42uL)), inputDecoder.next())
        assertEquals(1L, inputDecoder.skippedFrames)
    }

    @Test fun textPayloadIsLimitedTo256Utf8Bytes() {
        try {
            Packet.Input(InputPacket(0u, 0uL, InputEvent.Text("é".repeat(129)))).encodeFrame()
            fail("expected FrameException")
        } catch (_: FrameException) {}
    }
    @Test fun rejectsEmptyAndOversizedBeforeBodyAllocation() {
        listOf(byteArrayOf(0,0,0,0),byteArrayOf(1,0,0x80.toByte(),0)).forEach { b ->
            try { FrameDecoder().also{it.push(b)}.next();fail("expected FrameException") } catch(_:FrameException){}
        }
    }
    @Test fun pressurePresetsAreOrderedAndHandlesStayInRange() {
        val soft = PressureCurvePresets.named("Soft")
        val linear = PressureCurvePresets.named("Linear")
        val hard = PressureCurvePresets.named("Hard")
        assertEquals(4, soft.size)
        assertEquals(4, linear.size)
        assertEquals(4, hard.size)
        assertTrue(soft[1] > soft[0])
        assertTrue(hard[1] < hard[0])
        assertEquals(1f, PressureCurvePresets.normalized(linear, 0, 2f)[0])
    }
    @Test fun deterministicRandomInputsOnlyProducePacketsOrFrameException() {
        val random=Random(0x12345678)
        repeat(10_000) {
            val bytes=ByteArray(random.nextInt(128)){random.nextInt(256).toByte()}
            try { val d=FrameDecoder();d.push(bytes);repeat(64){d.next()?:return@repeat} } catch(_:FrameException) {}
        }
    }
}
