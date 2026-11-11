package com.cid0980.connectwin.realtime

import android.graphics.Bitmap
import android.os.SystemClock

/**
 * Holds the most recent captured frame. The capture thread publishes; consumers
 * either read a few pixels in-place under the lock ([use]) or take a fast private
 * [copy] and do heavier work (JPEG encode) outside the lock so capture never stalls.
 */
class FrameStore {
    private val lock = Any()
    private var current: Bitmap? = null

    @Volatile var seq: Long = 0L; private set
    @Volatile var timestampMs: Long = 0L; private set
    @Volatile var width: Int = 0; private set
    @Volatile var height: Int = 0; private set

    fun publish(bmp: Bitmap) {
        synchronized(lock) {
            val old = current
            current = bmp
            width = bmp.width
            height = bmp.height
            seq++
            timestampMs = SystemClock.elapsedRealtime()
            if (old != null && old !== bmp && !old.isRecycled) old.recycle()
        }
    }

    /** Run [block] against the live frame while holding the lock. Keep it short. */
    fun <T> use(block: (Bitmap) -> T): T? =
        synchronized(lock) { current?.takeIf { !it.isRecycled }?.let(block) }

    /** Fast private copy; encode/compress outside the lock. */
    fun copy(): Bitmap? =
        synchronized(lock) { current?.takeIf { !it.isRecycled }?.copy(Bitmap.Config.ARGB_8888, false) }

    fun ageMs(): Long {
        val ts = timestampMs
        return if (ts == 0L) -1L else SystemClock.elapsedRealtime() - ts
    }

    fun clear() {
        synchronized(lock) {
            current?.takeIf { !it.isRecycled }?.recycle()
            current = null
        }
    }
}
