package io.wingpane.proto

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction

const val PROTO_VERSION = 1
const val MAX_FRAME_LEN = 8 * 1024 * 1024

enum class Mode { ExtendedDisplay, GraphicsTablet, TrackpadKeyboard }
enum class VideoCodec { H264, Hevc }
enum class TouchPhase { Down, Move, Up, Cancel }
enum class RejectReason { VersionMismatch, UnsupportedCodec, InvalidHello, Busy }
sealed interface GestureKind {
    data object Tap : GestureKind
    data object TwoFingerTap : GestureKind
    data object DoubleTapDrag : GestureKind
    data object Swipe3Up : GestureKind
    data object Swipe3Down : GestureKind
    data object Swipe3Left : GestureKind
    data object Swipe3Right : GestureKind
    data class PinchZoom(val scale: Float) : GestureKind
}
sealed interface InputEvent {
    data class Pen(val x: Float, val y: Float, val pressure: Float, val tiltX: Byte, val tiltY: Byte, val isDown: Boolean, val isHover: Boolean, val buttonMask: UByte, val timestampUs: ULong) : InputEvent
    data class Touch(val pointerId: UByte, val x: Float, val y: Float, val phase: TouchPhase) : InputEvent
    data class PointerDelta(val dx: Float, val dy: Float) : InputEvent
    data class Scroll(val dx: Float, val dy: Float) : InputEvent
    data class Gesture(val kind: GestureKind) : InputEvent
    data class Keyboard(val usbHidUsage: UShort, val isDown: Boolean, val modifiers: UByte) : InputEvent
}
data class InputPacket(val seq: UInt, val tsUs: ULong, val event: InputEvent)
data class VideoPacket(val frameSeq: UInt, val ptsUs: ULong, val isKeyframe: Boolean, val payload: ByteArray) {
    override fun equals(other: Any?) = other is VideoPacket && frameSeq == other.frameSeq && ptsUs == other.ptsUs && isKeyframe == other.isKeyframe && payload.contentEquals(other.payload)
    override fun hashCode() = 31 * frameSeq.hashCode() + payload.contentHashCode()
}
sealed interface ControlMessage {
    data class Hello(val version: UShort, val deviceName: String, val screenSizePx: Pair<UInt, UInt>, val supportedCodecs: List<VideoCodec>, val refreshRateHz: UInt) : ControlMessage
    data class Accept(val selectedMode: Mode, val virtualDisplaySpec: Triple<UInt, UInt, UInt>?, val selectedCodec: VideoCodec, val bitrateKbps: UInt) : ControlMessage
    data class SetMode(val mode: Mode) : ControlMessage
    data object RequestKeyframe : ControlMessage
    data class Ping(val timestampNs: ULong) : ControlMessage
    data class Pong(val timestampNs: ULong) : ControlMessage
    data object Disconnect : ControlMessage
    data class Reject(val reason: RejectReason, val hostVersion: UShort? = null) : ControlMessage
    data class HostTime(val echoNs: ULong, val hostUs: ULong) : ControlMessage
}
sealed interface Packet {
    data class Control(val message: ControlMessage) : Packet
    data class Video(val packet: VideoPacket) : Packet
    data class Input(val packet: InputPacket) : Packet
    fun encodeFrame(): ByteArray = Codec.encode(this)
}

class FrameException(message: String, cause: Throwable? = null) : Exception(message, cause)

private class Writer {
    val out = ByteArrayOutputStream()
    fun byte(v: Int) { out.write(v and 255) }
    fun varint(v: ULong) { var n = v; while (n >= 128u) { byte((n.toInt() and 127) or 128); n = n shr 7 }; byte(n.toInt()) }
    fun u8(v: UByte) = byte(v.toInt())
    fun i8(v: Byte) = byte(v.toInt())
    fun bool(v: Boolean) = byte(if (v) 1 else 0)
    fun float(v: Float) { val b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(v).array(); out.write(b) }
    fun raw(b: ByteArray) { out.write(b) }
    fun string(s: String) { val b=s.toByteArray(Charsets.UTF_8); varint(b.size.toULong()); raw(b) }
}
private class Reader(val b: ByteArray) {
    var p=0
    fun byte(): Int { if(p>=b.size) throw FrameException("truncated body"); return b[p++].toInt() and 255 }
    fun varint(): ULong { var v=0uL; var shift=0; repeat(10) { val x=byte(); if(shift==63 && x>1) throw FrameException("varint overflow"); v = v or ((x and 127).toULong() shl shift); if(x<128) return v; shift+=7 }; throw FrameException("invalid varint") }
    fun count(): Int { val n=varint(); if(n>MAX_FRAME_LEN.toULong() || n>remaining().toULong()) throw FrameException("invalid length"); return n.toInt() }
    fun remaining()=b.size-p
    fun bool(): Boolean = when(val x=byte()){0->false;1->true;else->throw FrameException("invalid bool $x")}
    fun float(): Float { if(remaining()<4) throw FrameException("truncated float"); return ByteBuffer.wrap(b,p.also{p+=4},4).order(ByteOrder.LITTLE_ENDIAN).float }
    fun bytes(n:Int):ByteArray { if(n>remaining()) throw FrameException("truncated bytes"); return b.copyOfRange(p,p+n).also{p+=n} }
    fun string():String = try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes(count()))).toString() } catch(e:Exception){throw FrameException("invalid UTF-8",e)}
    fun enum(n:Int,max:Int):Int { val x=varint(); if(x>max.toULong()) throw FrameException("invalid enum tag"); return x.toInt() }
    fun done(){if(p!=b.size) throw FrameException("trailing body bytes")}
}

private object Codec {
    fun encode(packet:Packet):ByteArray {
        val w=Writer()
        when(packet){
            is Packet.Control->{w.varint(0u); control(w,packet.message)}
            is Packet.Video->{w.varint(1u); val v=packet.packet; w.varint(v.frameSeq.toULong());w.varint(v.ptsUs);w.bool(v.isKeyframe);w.varint(v.payload.size.toULong());w.raw(v.payload)}
            is Packet.Input->{w.varint(2u);input(w,packet.packet)}
        }
        val body=w.out.toByteArray()
        if(body.isEmpty()) throw FrameException("empty frame")
        if(body.size>MAX_FRAME_LEN) throw FrameException("frame too large: ${body.size}")
        return ByteBuffer.allocate(4+body.size).order(ByteOrder.LITTLE_ENDIAN).putInt(body.size).put(body).array()
    }
    private fun control(w:Writer,c:ControlMessage){ when(c){
        is ControlMessage.Hello->{w.varint(0u);w.varint(c.version.toULong());w.string(c.deviceName);w.varint(c.screenSizePx.first.toULong());w.varint(c.screenSizePx.second.toULong());w.varint(c.supportedCodecs.size.toULong());c.supportedCodecs.forEach{w.varint(it.ordinal.toULong())};w.varint(c.refreshRateHz.toULong())}
        is ControlMessage.Accept->{w.varint(1u);w.varint(c.selectedMode.ordinal.toULong());val s=c.virtualDisplaySpec;if(s==null)w.byte(0) else {w.byte(1);w.varint(s.first.toULong());w.varint(s.second.toULong());w.varint(s.third.toULong())};w.varint(c.selectedCodec.ordinal.toULong());w.varint(c.bitrateKbps.toULong())}
        is ControlMessage.SetMode->{w.varint(2u);w.varint(c.mode.ordinal.toULong())}; ControlMessage.RequestKeyframe->w.varint(3u)
        is ControlMessage.Ping->{w.varint(4u);w.varint(c.timestampNs)};is ControlMessage.Pong->{w.varint(5u);w.varint(c.timestampNs)};ControlMessage.Disconnect->w.varint(6u)
        is ControlMessage.Reject->{w.varint(7u);w.varint(c.reason.ordinal.toULong());if(c.reason==RejectReason.VersionMismatch)w.varint((c.hostVersion?:throw FrameException("missing host version")).toULong())}
        is ControlMessage.HostTime->{w.varint(8u);w.varint(c.echoNs);w.varint(c.hostUs)}
    }}
    private fun input(w:Writer,v:InputPacket){w.varint(v.seq.toULong());w.varint(v.tsUs);when(val e=v.event){
        is InputEvent.Pen->{w.varint(0u);w.float(e.x);w.float(e.y);w.float(e.pressure);w.i8(e.tiltX);w.i8(e.tiltY);w.bool(e.isDown);w.bool(e.isHover);w.u8(e.buttonMask);w.varint(e.timestampUs)}
        is InputEvent.Touch->{w.varint(1u);w.u8(e.pointerId);w.float(e.x);w.float(e.y);w.varint(e.phase.ordinal.toULong())}
        is InputEvent.PointerDelta->{w.varint(2u);w.float(e.dx);w.float(e.dy)}
        is InputEvent.Scroll->{w.varint(3u);w.float(e.dx);w.float(e.dy)}
        is InputEvent.Gesture->{w.varint(4u);gesture(w,e.kind)}
        is InputEvent.Keyboard->{w.varint(5u);w.varint(e.usbHidUsage.toULong());w.bool(e.isDown);w.u8(e.modifiers)}
    }}
    private fun gesture(w:Writer,g:GestureKind){val i=when(g){GestureKind.Tap->0;GestureKind.TwoFingerTap->1;GestureKind.DoubleTapDrag->2;GestureKind.Swipe3Up->3;GestureKind.Swipe3Down->4;GestureKind.Swipe3Left->5;GestureKind.Swipe3Right->6;is GestureKind.PinchZoom->7};w.varint(i.toULong());if(g is GestureKind.PinchZoom)w.float(g.scale)}
    fun decode(b:ByteArray):Packet {
        val r=Reader(b); val packet=when(r.enum(0,2)){
            0->Packet.Control(decodeControl(r));1->Packet.Video(VideoPacket(r.varint().toUInt(),r.varint(),r.bool(),r.bytes(r.count())))
            else->Packet.Input(decodeInput(r))
        };r.done();return packet
    }
    private fun mode(r:Reader)=Mode.entries[r.enum(0,2)]
    private fun codec(r:Reader)=VideoCodec.entries[r.enum(0,1)]
    private fun decodeControl(r:Reader):ControlMessage=when(r.enum(0,8)){
        0->{val ver=r.varint().toUShort();val name=r.string();val size=r.varint().toUInt() to r.varint().toUInt();val codecs=List(r.count()){codec(r)};ControlMessage.Hello(ver,name,size,codecs,r.varint().toUInt())}
        1->{val m=mode(r);val has=r.byte();val s=when(has){0->null;1->Triple(r.varint().toUInt(),r.varint().toUInt(),r.varint().toUInt());else->throw FrameException("invalid option")};ControlMessage.Accept(m,s,codec(r),r.varint().toUInt())}
        2->ControlMessage.SetMode(mode(r));3->ControlMessage.RequestKeyframe;4->ControlMessage.Ping(r.varint());5->ControlMessage.Pong(r.varint());6->ControlMessage.Disconnect
        7->{val reason=RejectReason.entries[r.enum(0,3)];ControlMessage.Reject(reason,if(reason==RejectReason.VersionMismatch)r.varint().toUShort() else null)}
        else->ControlMessage.HostTime(r.varint(),r.varint())
    }
    private fun decodeInput(r:Reader):InputPacket {val seq=r.varint().toUInt();val ts=r.varint();val e=when(r.enum(0,5)){
        0->InputEvent.Pen(r.float(),r.float(),r.float(),r.byte().toByte(),r.byte().toByte(),r.bool(),r.bool(),r.byte().toUByte(),r.varint())
        1->InputEvent.Touch(r.byte().toUByte(),r.float(),r.float(),TouchPhase.entries[r.enum(0,3)])
        2->InputEvent.PointerDelta(r.float(),r.float());3->InputEvent.Scroll(r.float(),r.float())
        4->{val tag=r.enum(0,7);InputEvent.Gesture(if(tag==7)GestureKind.PinchZoom(r.float()) else listOf(GestureKind.Tap,GestureKind.TwoFingerTap,GestureKind.DoubleTapDrag,GestureKind.Swipe3Up,GestureKind.Swipe3Down,GestureKind.Swipe3Left,GestureKind.Swipe3Right)[tag])}
        else->InputEvent.Keyboard(r.varint().toUShort(),r.bool(),r.byte().toUByte())
    };return InputPacket(seq,ts,e)}
}

/** Incremental length-prefixed decoder. Header validation occurs before allocating a body. */
class FrameDecoder {
    private var buffer=ByteArray(0)
    fun push(bytes:ByteArray, off:Int=0, len:Int=bytes.size-off) {
        if(off<0||len<0||off>bytes.size-len) throw IndexOutOfBoundsException()
        if(len==0)return
        if(buffer.size.toLong()+len>2L*(MAX_FRAME_LEN+4))throw FrameException("buffer limit exceeded")
        buffer=buffer+bytes.copyOfRange(off,off+len)
    }
    fun next():Packet? {
        if(buffer.size<4)return null
        val len=ByteBuffer.wrap(buffer,0,4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
        if(len==0L)throw FrameException("empty frame")
        if(len>MAX_FRAME_LEN)throw FrameException("frame too large: $len")
        if(buffer.size<4+len)return null
        val packet=Codec.decode(buffer.copyOfRange(4,4+len.toInt()))
        buffer=buffer.copyOfRange(4+len.toInt(),buffer.size)
        return packet
    }
}
