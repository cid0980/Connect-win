package com.cid0980.connectwin

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedDeque

class BridgeNotificationService : NotificationListenerService() {

    data class NotifHistoryItem(
        val key: String,
        val pkg: String,
        val title: String,
        val text: String,
        val bigText: String,
        val postedAt: Long,
        val actions: List<String>
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("key", key)
            put("package", pkg)
            put("title", title)
            put("text", text)
            if (bigText.isNotEmpty() && bigText != text) put("big_text", bigText)
            put("posted_at", postedAt)
            put("actions", JSONArray(actions))
        }
    }

    companion object {
        @Volatile
        var instance: BridgeNotificationService? = null
            private set

        private val history = ConcurrentLinkedDeque<NotifHistoryItem>()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        BridgeState.log("NotificationListener connected")
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (sbn.packageName == packageName) return
        val parsed = extractItem(sbn)
        if (parsed.title.isNotEmpty() || parsed.text.isNotEmpty()) {
            history.addLast(parsed)
            while (history.size > 60) {
                history.pollFirst()
            }
        }
    }

    private fun extractItem(sbn: StatusBarNotification): NotifHistoryItem {
        val extras = sbn.notification.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
        val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim() ?: ""
        val big = extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim() ?: ""
        val acts = sbn.notification.actions?.mapNotNull { a ->
            val label = a.title?.toString()?.trim() ?: return@mapNotNull null
            val hasReply = !a.remoteInputs.isNullOrEmpty()
            if (hasReply) "$label [reply]" else label
        } ?: emptyList()

        return NotifHistoryItem(
            key = sbn.key ?: "",
            pkg = sbn.packageName ?: "",
            title = title,
            text = text,
            bigText = big,
            postedAt = sbn.postTime,
            actions = acts
        )
    }

    fun listNotificationsJson(includeHistory: Boolean = true): JSONObject {
        val activeArr = JSONArray()
        val sbns = runCatching { activeNotifications }.getOrNull() ?: emptyArray()
        for (sbn in sbns) {
            if (sbn.packageName == packageName) continue
            val item = extractItem(sbn)
            activeArr.put(item.toJson().apply {
                put("ongoing", sbn.isOngoing)
            })
        }

        return JSONObject().apply {
            put("active_count", activeArr.length())
            put("active", activeArr)
            if (includeHistory) {
                val histArr = JSONArray()
                history.toList().takeLast(30).reversed().forEach { histArr.put(it.toJson()) }
                put("recent_history", histArr)
            }
        }
    }

    private fun findActiveSbn(query: String): StatusBarNotification? {
        val q = query.trim()
        val sbns = runCatching { activeNotifications }.getOrNull() ?: return null
        sbns.firstOrNull { it.key == q }?.let { return it }
        return sbns.firstOrNull { sbn ->
            val item = extractItem(sbn)
            sbn.packageName.contains(q, ignoreCase = true) ||
                item.title.contains(q, ignoreCase = true) ||
                item.text.contains(q, ignoreCase = true)
        }
    }

    fun actOnNotification(
        query: String,
        actionLabel: String? = null,
        replyText: String? = null
    ): JSONObject {
        val sbn = findActiveSbn(query) ?: return JSONObject().apply {
            put("ok", false)
            put("error", "notification_not_found")
        }

        // If no specific action button requested, tap the notification itself
        if (actionLabel.isNullOrBlank() && replyText.isNullOrBlank()) {
            val pi = sbn.notification.contentIntent ?: return JSONObject().apply {
                put("ok", false)
                put("error", "no_content_intent")
            }
            return try {
                pi.send()
                JSONObject().put("ok", true).put("opened_key", sbn.key)
            } catch (e: Exception) {
                JSONObject().put("ok", false).put("error", e.message ?: "PendingIntent failed")
            }
        }

        val actions = sbn.notification.actions ?: emptyArray()
        val targetAction = actions.firstOrNull { a ->
            val t = a.title?.toString() ?: ""
            if (!actionLabel.isNullOrBlank()) t.contains(actionLabel, ignoreCase = true)
            else !a.remoteInputs.isNullOrEmpty()
        } ?: return JSONObject().apply {
            put("ok", false)
            put("error", "matching_action_not_found")
            put("available_actions", JSONArray(actions.map { it.title?.toString() ?: "" }))
        }

        return try {
            val remoteInputs = targetAction.remoteInputs
            if (!replyText.isNullOrEmpty() && !remoteInputs.isNullOrEmpty()) {
                val fillIntent = Intent()
                val bundle = Bundle()
                for (ri in remoteInputs) {
                    bundle.putCharSequence(ri.resultKey, replyText)
                }
                RemoteInput.addResultsToIntent(remoteInputs, fillIntent, bundle)
                targetAction.actionIntent.send(this, 0, fillIntent)
                JSONObject().apply {
                    put("ok", true)
                    put("replied", replyText)
                    put("action", targetAction.title?.toString() ?: "")
                }
            } else {
                targetAction.actionIntent.send()
                JSONObject().apply {
                    put("ok", true)
                    put("triggered_action", targetAction.title?.toString() ?: "")
                }
            }
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message ?: "action send failed")
        }
    }

    fun dismissNotifications(key: String? = null, all: Boolean = false): JSONObject {
        if (all) {
            cancelAllNotifications()
            return JSONObject().put("ok", true).put("dismissed", "all")
        }
        if (key.isNullOrBlank()) {
            return JSONObject().put("ok", false).put("error", "provide key or all=true")
        }
        val sbn = findActiveSbn(key)
        val realKey = sbn?.key ?: key
        cancelNotification(realKey)
        return JSONObject().put("ok", true).put("dismissed_key", realKey)
    }
}
