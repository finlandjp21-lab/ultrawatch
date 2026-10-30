package com.harshvardhan.ultrawatch.comms

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A message from any chat app (SMS, WhatsApp, Telegram, Messages...), captured from its notification. */
data class ChatMessage(
    val key: String,
    val app: String,
    val packageName: String,
    val sender: String,
    val text: String,
    val time: Long,
    val canReply: Boolean,
)

object MessageStore {
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages
    internal val replyActions = HashMap<String, Notification.Action>()

    internal fun put(m: ChatMessage, action: Notification.Action?) {
        _messages.value = (listOf(m) + _messages.value.filter { it.key != m.key }).take(50)
        if (action != null) replyActions[m.key] = action else replyActions.remove(m.key)
    }

    internal fun remove(key: String) {
        _messages.value = _messages.value.filter { it.key != key }
        replyActions.remove(key)
    }

    /** Sends a reply through the chat app's own reply action, exactly as the phone notification would. */
    fun reply(ctx: Context, key: String, text: String): Boolean {
        val action = replyActions[key] ?: return false
        val inputs = action.remoteInputs ?: return false
        val intent = Intent()
        val results = Bundle()
        for (ri in inputs) results.putCharSequence(ri.resultKey, text)
        RemoteInput.addResultsToIntent(inputs, intent, results)
        return try { action.actionIntent.send(ctx, 0, intent); true } catch (_: PendingIntent.CanceledException) { false }
    }

    fun listenerEnabled(ctx: Context): Boolean {
        val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
        val me = ComponentName(ctx, MessageListenerService::class.java).flattenToString()
        return flat.split(":").any { it == me }
    }

    fun openListenerSettings() = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

class MessageListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        runCatching { activeNotifications?.forEach { handle(it) } }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = handle(sbn)

    override fun onNotificationRemoved(sbn: StatusBarNotification) = MessageStore.remove(sbn.key)

    private fun handle(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val n = sbn.notification
        val isMessage = n.category == Notification.CATEGORY_MESSAGE ||
            NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n) != null
        if (!isMessage || (n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return

        val extras = n.extras
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        val last = style?.messages?.lastOrNull()
        val sender = last?.person?.name?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: "Unknown"
        val text = last?.text?.toString() ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: return

        val replyAction = (n.actions ?: emptyArray()).firstOrNull { a ->
            a.remoteInputs?.any { it.allowFreeFormInput } == true
        }
        val appName = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        }.getOrDefault(sbn.packageName)

        MessageStore.put(ChatMessage(sbn.key, appName, sbn.packageName, sender, text, sbn.postTime, replyAction != null), replyAction)
    }
}
