package com.cid0980.connectwin.realtime

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Compact little-endian binary protocol for the realtime WebSocket hot path.
 * Avoids JSON parsing and base64 (which inflates payloads ~33%) for frames/touch.
 *
 * Client -> Server:
 *   0x01 SHOT        [type:u8][max_width/8:u8][quality:u8]
 *   0x02 TOUCH_DOWN  [type:u8][pointer:u8][x:u16][y:u16]
 *   0x03 TOUCH_MOVE  [type:u8][pointer:u8][x:u16][y:u16]
 *   0x04 TOUCH_UP    [type:u8][pointer:u8]
 *   0x05 PING        [type:u8][seq:u32]
 *
 * Server -> Client:
 *   0x81 FRAME       [type:u8][seq:u32][w:u16][h:u16][orig_w:u16][orig_h:u16][jpeg...]
 *   0x82 TOUCH_ACK   [type:u8][pointer:u8][ok:u8]
 *   0x85 PONG        [type:u8][seq:u32][server_time_ms:u64]
 */
object BinaryProtocol {
    const val C_SHOT: Byte = 0x01
    const val C_TOUCH_DOWN: Byte = 0x02
    const val C_TOUCH_MOVE: Byte = 0x03
    const val C_TOUCH_UP: Byte = 0x04
    const val C_PING: Byte = 0x05

    const val S_FRAME: Byte = 0x81.toByte()
    const val S_TOUCH_ACK: Byte = 0x82.toByte()
    const val S_PONG: Byte = 0x85.toByte()

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

    /** Returns [type, a, b, c] where meaning depends on type; null if malformed. */
    fun decode(data: ByteArray): IntArray? {
        if (data.isEmpty()) return null
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val type = buf.get().toInt() and 0xFF
        return when (type) {
            0x01 -> { // SHOT
                if (buf.remaining() < 2) return null
                val maxW = (buf.get().toInt() and 0xFF) * 8
                val q = buf.get().toInt() and 0xFF
                intArrayOf(type, maxW, q, 0)
            }
            0x02, 0x03 -> { // DOWN / MOVE
                if (buf.remaining() < 5) return null
                val p = buf.get().toInt() and 0xFF
                val x = buf.short.toInt() and 0xFFFF
                val y = buf.short.toInt() and 0xFFFF
                intArrayOf(type, p, x, y)
            }
            0x04 -> { // UP
                if (buf.remaining() < 1) return null
                val p = buf.get().toInt() and 0xFF
                intArrayOf(type, p, 0, 0)
            }
            0x05 -> { // PING
                if (buf.remaining() < 4) return null
                val seq = buf.int
                intArrayOf(type, seq, 0, 0)
            }
            else -> null
        }
    }
}
