package com.morsen.ts3xclient

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicInteger

/** Notifications for private chat events received on the existing full TS3 connection. */
internal object ChatNotifications {
    private const val CHANNEL_ID = "ts3x_private_chat"
    private val nextId = AtomicInteger(5400)

    fun install(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "TeamSpeak private messages", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "New private messages from people on the connected TeamSpeak server"
            }
        )
    }

    fun show(context: Context, sender: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled() ||
            manager.getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE
        ) return
        val launch = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("TeamSpeak · $sender")
            .setContentText(text.take(160))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text.take(500)))
            .setContentIntent(launch)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        manager.notify(nextId.incrementAndGet(), notification)
    }
}
