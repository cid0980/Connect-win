package com.cid0980.connectwin.realtime

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Compact little-endian binary protocol for the realtime WebSocket hot path.
 * Avoids JSON parsing and base64 (which inflates payloads ~33%) for frames/touch.
 *
 * v1 (kept for compatibility):
 *   0x01 SHOT        [type:u8][max_width/8:u8][quality:u8]
 *   0x02 TOUCH_DOWN  [type:u8][pointer:u8][x:u16][y:u16]
 *   0x03 TOUCH_MOVE  [type:u8][pointer:u8][x:u16][y:u16]
 *   0x04 TOUCH_UP    [type:u8][pointer:u8]
 *   0x05 PING        [type:u8][seq:u32]
 *
 * v2 sequenced touch (every command gets an ordered ack):
 *   0x10 TOUCH_DOWN_SEQ [type:u8][pointer:u8][x:u16][y:u16][seq:u32]
 *   0x11 TOUCH_MOVE_SEQ [type:u8][pointer:u8][x:u16][y:u16][seq:u32]
 *   0x12 TOUCH_UP_SEQ   [type:u8][pointer:u8][seq:u32]
 *
 * Server -> Client:
 *   0x81 FRAME         [type:u8][seq:u32][w:u16][h:u16][orig_w:u16][orig_h:u16][jpeg...]
 *   0x82 TOUCH_ACK     [type:u8][pointer:u8][ok:u8]                 (v1)
 *   0x90 TOUCH_ACK_SEQ [type:u8][pointer:u8][seq:u32][status:u8]    (v2)
 *        status: 0=queued, 1=invalid, 2=accessibility_unavailable
 *   0x85 PONG          [type:u8][seq:u32][server_time_ms:u64]
 */
object BinaryProtocol {
    const val C_SHOT: Byte = 0x01
    const val C_TOUCH_DOWN: Byte = 0x02
    const val C_TOUCH_MOVE: Byte = 0x03
    const val C_TOUCH_UP: Byte = 0x04
    const val C_PING: Byte = 0x05
    const val C_TOUCH_DOWN_SEQ: Byte = 0x10
    const val C_TOUCH_MOVE_SEQ: Byte = 0x11
    const val C_TOUCH_UP_SEQ: Byte = 0x12

    const val S_FRAME: Byte = 0x81.toByte()
    const val S_TOUCH_ACK: Byte = 0x82.toByte()
    const val S_TOUCH_ACK_SEQ: Byte = 0x90.toByte()
    const val S_PONG: Byte = 0x85.toByte()

    const val ACK_QUEUED: Byte = 0
    const val ACK_INVALID: Byte = 1
    const val ACK_UNAVAILABLE: Byte = 2

    fun encodeFrame(seq: Long, w: Int, h: Int, origW: Int, origH: Int, jpeg: ByteArray): ByteArray {
        val buf = ByteBuffer.allocate(13 + jpeg.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(S_FRAME)
        buf.putInt(seq.toInt())
        buf.putShort(w.toShort())
        buf.putShort(h.toShort())
        buf.putShort(origW.toShort())
        buf.putShort(origH.toShort())
        buf.put(jpeg)
        return buf.array()
    }

    fun encodePong(seq: Int, serverTimeMs: Long): ByteArray {
        val buf = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(S_PONG)
        buf.putInt(seq)
        buf.putLong(serverTimeMs)
        return buf.array()
    }

    fun encodeTouchAck(pointer: Int, ok: Boolean): ByteArray =
        byteArrayOf(S_TOUCH_ACK, pointer.toByte(), if (ok) 1 else 0)

    fun encodeTouchAckSeq(pointer: Int, seq: Int, status: Byte): ByteArray {
        val buf = ByteBuffer.allocate(7).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(S_TOUCH_ACK_SEQ)
        buf.put(pointer.toByte())
        buf.putInt(seq)
        buf.put(status)
        return buf.array()
    }

    /**
     * Returns [type, a, b, c, seq]. For v1 touch, seq = -1. For SHOT/PING the unused
     * payload slots are zero. Returns null if malformed.
     */
    fun decode(data: ByteArray): IntArray? {
        if (data.isEmpty()) return null
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val type = buf.get().toInt() and 0xFF
        return when (type) {
            0x01 -> { // SHOT
                if (buf.remaining() < 2) return null
                val maxW = (buf.get().toInt() and 0xFF) * 8
                val q = buf.get().toInt() and 0xFF
                intArrayOf(type, maxW, q, 0, -1)
            }
            0x02, 0x03 -> { // DOWN / MOVE (v1)
                if (buf.remaining() < 5) return null
                val p = buf.get().toInt() and 0xFF
                val x = buf.short.toInt() and 0xFFFF
                val y = buf.short.toInt() and 0xFFFF
                intArrayOf(type, p, x, y, -1)
            }
            0x04 -> { // UP (v1)
                if (buf.remaining() < 1) return null
                val p = buf.get().toInt() and 0xFF
                intArrayOf(type, p, 0, 0, -1)
            }
            0x05 -> { // PING
                if (buf.remaining() < 4) return null
                val seq = buf.int
                intArrayOf(type, seq, 0, 0, seq)
            }
            0x10, 0x11 -> { // DOWN / MOVE (v2 sequenced)
                if (buf.remaining() < 9) return null
                val p = buf.get().toInt() and 0xFF
                val x = buf.short.toInt() and 0xFFFF
                val y = buf.short.toInt() and 0xFFFF
                val seq = buf.int
                intArrayOf(type, p, x, y, seq)
            }
            0x12 -> { // UP (v2 sequenced)
                if (buf.remaining() < 5) return null
                val p = buf.get().toInt() and 0xFF
                val seq = buf.int
                intArrayOf(type, p, 0, 0, seq)
            }
            else -> null
        }
    }
}
