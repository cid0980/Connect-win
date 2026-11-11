package com.cid0980.connectwin.realtime

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class BinaryProtocolTest {

    @Test
    fun shot_roundTrip() {
        val encoded = byteArrayOf(0x01, 60, 55) // max_width=480, quality=55
        val d = BinaryProtocol.decode(encoded)!!
        assertEquals(0x01, d[0])
        assertEquals(480, d[1])
        assertEquals(55, d[2])
    }

    @Test
    fun touchDown_v1_roundTrip() {
        val buf = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(0x02); buf.put(1); buf.putShort(300); buf.putShort(1500)
        val d = BinaryProtocol.decode(buf.array())!!
        assertEquals(0x02, d[0])
        assertEquals(1, d[1])
        assertEquals(300, d[2])
        assertEquals(1500, d[3])
        assertEquals(-1, d[4]) // v1 has no seq
    }

    @Test
    fun touchDown_v2_sequenced_roundTrip() {
        val buf = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(0x10); buf.put(2); buf.putShort(540); buf.putShort(1200); buf.putInt(777)
        val d = BinaryProtocol.decode(buf.array())!!
        assertEquals(0x10, d[0])
        assertEquals(2, d[1])
        assertEquals(540, d[2])
        assertEquals(1200, d[3])
        assertEquals(777, d[4])
    }

    @Test
    fun touchUp_v2_sequenced_roundTrip() {
        val buf = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(0x12); buf.put(0); buf.putInt(42)
        val d = BinaryProtocol.decode(buf.array())!!
        assertEquals(0x12, d[0])
        assertEquals(0, d[1])
        assertEquals(42, d[4])
    }

    @Test
    fun ping_roundTrip() {
        val d = BinaryProtocol.decode(BinaryProtocol.encodePong(123, 0L).let {
            // build a client ping instead of a pong
            ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN).put(0x05).putInt(123).array()
        })!!
        assertEquals(0x05, d[0])
        assertEquals(123, d[1])
    }

    @Test
    fun frame_header_layout() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
        val f = BinaryProtocol.encodeFrame(9, 480, 1066, 1080, 2400, jpeg)
        assertEquals(BinaryProtocol.S_FRAME, f[0])
        val buf = ByteBuffer.wrap(f).order(ByteOrder.LITTLE_ENDIAN)
        buf.get() // type
        assertEquals(9, buf.int)
        assertEquals(480, buf.short.toInt())
        assertEquals(1066, buf.short.toInt())
        assertEquals(1080, buf.short.toInt())
        assertEquals(2400, buf.short.toInt())
        val tail = ByteArray(buf.remaining()); buf.get(tail)
        assertArrayEquals(jpeg, tail)
    }

    @Test
    fun sequencedAck_layout() {
        val a = BinaryProtocol.encodeTouchAckSeq(3, 100, BinaryProtocol.ACK_QUEUED)
        assertEquals(7, a.size)
        val buf = ByteBuffer.wrap(a).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(BinaryProtocol.S_TOUCH_ACK_SEQ, buf.get())
        assertEquals(3, buf.get().toInt())
        assertEquals(100, buf.int)
        assertEquals(BinaryProtocol.ACK_QUEUED, buf.get())
    }

    @Test
    fun malformed_returnsNull() {
        assertNull(BinaryProtocol.decode(byteArrayOf()))
        assertNull(BinaryProtocol.decode(byteArrayOf(0x01)))           // shot too short
        assertNull(BinaryProtocol.decode(byteArrayOf(0x02, 0)))        // down too short
        assertNull(BinaryProtocol.decode(byteArrayOf(0x10, 0, 1, 2)))  // seq down too short
        assertNull(BinaryProtocol.decode(byteArrayOf(0x7F)))           // unknown type
    }

    @Test
    fun highCoordinates_areUnsigned() {
        val buf = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(0x03); buf.put(0); buf.putShort(40000.toShort()); buf.putShort(60000.toShort())
        val d = BinaryProtocol.decode(buf.array())!!
        assertEquals(40000, d[2])
        assertEquals(60000, d[3])
        assertTrue(d[2] > 0 && d[3] > 0)
    }
}
