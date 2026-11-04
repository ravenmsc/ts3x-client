package com.morsen.ts3xclient

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicInteger

/** Process-level poke delivery. The listener must outlive the foreground voice service. */
internal object PokeNotifications {
    private const val CHANNEL_ID = "ts3x_incoming_poke"
    private const val NOTIFICATION_BASE = 4900
    private val sequence = AtomicInteger(0)

    fun install(context: Context) {
        val app = context.applicationContext
        val manager = app.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "TeamSpeak pokes", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Incoming poke messages from other TeamSpeak clients"
                enableVibration(true)
            }
        )
        VoiceSession.backend(app).setIncomingPokeListener { sender, message ->
            show(app, sender, message)
        }
        Log.i("TS3X_POKE", "Process-level notification listener installed")
    }

    private fun show(context: Context, sender: String, message: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w("TS3X_POKE", "Notification skipped: POST_NOTIFICATIONS permission denied")
            return
        }
        if (!manager.areNotificationsEnabled()) {
            Log.w("TS3X_POKE", "Notification skipped: application notifications disabled")
            return
        }
        if (Build.VERSION.SDK_INT >= 26 &&
            manager.getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE
        ) {
            Log.w("TS3X_POKE", "Notification skipped: poke notification channel disabled")
            return
        }
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
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(launch)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        runCatching {
            val id = NOTIFICATION_BASE + sequence.incrementAndGet()
            manager.notify(id, notification)
            Log.i("TS3X_POKE", "Notification submitted id=$id sender=$sender")
        }.onFailure { Log.e("TS3X_POKE", "Notification posting failed", it) }
    }
}
