package com.cid0980.connectwin

/** One notification per observed generating -> stable idle transition on the same visible page. */
internal class ArenaCompletionState {
    private var armedPage: String? = null
    private var idleSince: Long? = null
    fun reset() { armedPage = null; idleSince = null }
    fun sample(page: String?, generating: Boolean, nowMs: Long): Boolean {
        if (page == null) { reset(); return false }
        if (generating) { armedPage = page; idleSince = null; return false }
        if (armedPage != page) { reset(); return false }
        val start = idleSince
        if (start == null) { idleSince = nowMs; return false }
        if (nowMs - start < 3000) return false
        reset()
        return true
    }
}
