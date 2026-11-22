package com.chun.pibridge

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import org.json.JSONObject

/** Match the list's name/tab priority, but never expose an internal pane ID as fallback. */
fun notificationSessionTitle(session: JSONObject?): String {
    val name = (session?.opt("sessionName") as? String)?.trim()?.takeIf { it.isNotEmpty() }
    if (name != null) return name
    val tab = (session?.opt("tab") as? String)?.trim()?.takeIf { it.isNotEmpty() }
    return tab?.takeUnless { it == session.opt("sessionKey") || it == session.opt("paneId") }
        ?: "名前未設定のセッション"
}

fun sessionForNotification(sessions: List<JSONObject>, key: String): JSONObject? =
    sessions.firstOrNull { it.opt("sessionKey") == key }

fun agentNotification(
    context: Context, session: JSONObject?, state: String, intent: PendingIntent, channel: String = "agent",
): Notification = NotificationCompat.Builder(context, channel)
    .setSmallIcon(android.R.drawable.stat_notify_chat)
    .setContentTitle(notificationSessionTitle(session))
    .setContentText(if (state == "blocked") "ターミナルで応答してください" else "応答が完了しました")
    .setContentIntent(intent)
    .setAutoCancel(true)
    .build()
