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
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * LOW FUNCTION MODE v2
 *
 * No persistent TeamSpeak connection.
 *
 * WorkManager wakes periodically (Android's minimum repeat interval is 15 min),
 * briefly creates a no-audio full-client session, reads the initial view that
 * ts3j delivers during connect(), compares it with the previous snapshot and
 * immediately disconnects.
 */
class LowFunctionMonitorWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (FullFunctionRuntime.monitoringBlocked()) {

            return Result.success()
        }

        // Only one probe Worker may hold a TeamSpeak session at a time.
        if (!RuntimeState.probeRunning.compareAndSet(false, true)) {
            return Result.success()
        }
        RuntimeState.activeWorkers.incrementAndGet()

        try {
            val store = ServerProfileStore(applicationContext)
            val profiles = store.list().filter { it.monitorEnabled }

            if (profiles.isEmpty()) {
                return Result.success()
            }

            createNotificationChannel()

            var hadRetryableFailure = false

            for (profile in profiles) {
                if (isStopped || FullFunctionRuntime.monitoringBlocked()) {
                    break
                }

                val outcome = checkProfile(profile, store)
                if (!outcome) {
                    hadRetryableFailure = true
                }
            }

            // Avoid WorkManager retry storms: the next scheduled poll will retry.
            if (hadRetryableFailure) {
                Log.w(TAG, "Probe failed; deferring until the next scheduled poll")
            }
            return Result.success()
        } finally {
            RuntimeState.activeWorkers.decrementAndGet()
            RuntimeState.probeRunning.set(false)

            if (
                inputData.getString(KEY_WORK_KIND) ==
                    WORK_KIND_SHORT_LOOP
            ) {
                scheduleNextShortRun(applicationContext)
            }
        }
    }

    /**
     * Returns false only for transport failures worth retrying.
     * Authentication/server-password failures are left for the next periodic run
     * rather than creating aggressive WorkManager retry loops.
     */
    private suspend fun checkProfile(
        profile: SavedServerProfile,
        store: ServerProfileStore
    ): Boolean {
        val backend = Ts3jBackend(
            context = applicationContext,
            enableAudio = false
        )

        // Use the same persisted nickname and TeamSpeak identity as the regular
        // client session. The probe is excluded by ownClientId below, not by name.
        val regularNickname = backend.savedNickname()

        if (FullFunctionRuntime.monitoringBlocked() || isStopped) return true

        val result = backend.connect(
            host = profile.host,
            port = profile.port,
            nickname = regularNickname,
            password = profile.password,
            persistNickname = false
        )

        if (result.isFailure || !backend.connected) {
            Log.w(
                TAG,
                "Probe failed ${profile.displayName}: ${backend.status}"
            )
            runCatching { backend.disconnect() }

            val text = backend.status.lowercase()
            return !(
                "timeout" in text ||
                    "connection" in text ||
                    "network" in text
                )
        }

        try {
            // connect() already processes the initial view. Give listener
            // callbacks a short scheduling margin without keeping the client
            // on the server for an extended period.
            delay(350L)

            backend.refreshServerInfo()

            backend.serverName()?.let { serverName ->
                store.updateServerName(profile.id, serverName)
            }

            val clients = backend.clients()
                .asSequence()
                .filterNot { it.id == backend.ownClientId }
                .filterNot { it.isServerQuery }
                .filterNot {
                    it.nickname.equals(
                        "serveradmin",
                        ignoreCase = true
                    )
                }
                .toList()

            val previous = PresenceStateStore(applicationContext)
                .load(profile.id)

            // Stable UID keys also deduplicate repeated client snapshots.
            val current = clients.associateBy(::presenceKey)

            if (previous != null) {
                val joinedKeys = current.keys - previous.keys

                joinedKeys.forEach { key ->
                    val client = current[key] ?: return@forEach

                    if (
                        profile.monitoredChannelIds.isNotEmpty() &&
                        client.channelId !in profile.monitoredChannelIds
                    ) {
                        return@forEach
                    }

                    val channelName = backend.channels()
                        .firstOrNull { it.id == client.channelId }
                        ?.name
                        ?.takeIf { it.isNotBlank() }
                        ?: "channel #${client.channelId}"

                    showJoinNotification(
                        profile = store.get(profile.id) ?: profile,
                        client = client,
                        channelName = channelName
                    )
                }
            } else {

            }

            PresenceStateStore(applicationContext)
                .save(profile.id, current)



            return true
        } finally {
            runCatching { backend.disconnect() }
        }
    }

    private fun presenceKey(client: BackendClient): String =
        client.uniqueId
            .trim()
            .takeIf { it.isNotBlank() }
            ?.let { "uid:$it" }
            ?: "nick:${client.nickname.trim().lowercase()}"

    private fun showJoinNotification(
        profile: SavedServerProfile,
        client: BackendClient,
        channelName: String
    ) {
        if (
            Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Notification permission missing")
            return
        }

        val manager = applicationContext.getSystemService(
            Context.NOTIFICATION_SERVICE
        ) as NotificationManager

        val intent = Intent(
            applicationContext,
            MainActivity::class.java
        ).addFlags(
            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        )

        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            7,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
        )

        // Preserve the native Android notification layout, colors and typography.
        // Only the nickname receives text styling. The remaining title stays normal.
        val title = SpannableString("${client.nickname} joined ${profile.displayName}").apply {
            if (client.nickname.isNotEmpty()) {
                setSpan(StyleSpan(Typeface.BOLD), 0, client.nickname.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(TypefaceSpan("sans-serif-black"), 0, client.nickname.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }

        val notification = NotificationCompat.Builder(
            applicationContext,
            CHANNEL_EVENTS
        )
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText("Channel: $channelName")
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .build()

        manager.notify(
            NOTIFICATION_ID_BASE +
                ((profile.id.hashCode() * 31 + client.nickname.hashCode()) and
                    0x0FFFFFFF),
            notification
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return

        val manager = applicationContext.getSystemService(
            Context.NOTIFICATION_SERVICE
        ) as NotificationManager

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_EVENTS,
                "TeamSpeak user connections",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description =
                    "Users detected by periodic TeamSpeak monitoring"
            }
        )
    }

    companion object {
        private const val TAG = "TS3LOW"
        private const val CHANNEL_EVENTS = "ts3_user_events"
        private const val NOTIFICATION_ID_BASE = 5000

        private const val PERIODIC_WORK =
            "teamspeak-low-periodic-monitor"

        private const val SHORT_INTERVAL_WORK =
            "teamspeak-low-short-interval-monitor"

        private const val IMMEDIATE_WORK =
            "teamspeak-low-immediate-monitor"

        private const val KEY_WORK_KIND =
            "low_monitor_work_kind"

        private const val WORK_KIND_SHORT_LOOP =
            "short_loop"

        private const val WORK_KIND_IMMEDIATE =
            "immediate"

        private val networkConstraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        fun sync(context: Context) {
            val workManager =
                WorkManager.getInstance(context)

            val enabled =
                ServerProfileStore(context)
                    .list()
                    .any { it.monitorEnabled }

            if (!enabled) {
                workManager.cancelUniqueWork(
                    PERIODIC_WORK
                )
                workManager.cancelUniqueWork(
                    SHORT_INTERVAL_WORK
                )
                workManager.cancelUniqueWork(
                    IMMEDIATE_WORK
                )
                return
            }

            // Manual disconnect is not a request to launch a probe session.
            // Drop pending immediate work and defer short polling from now.
            workManager.cancelUniqueWork(IMMEDIATE_WORK)

            val intervalMinutes =
                AppSettingsStore(context)
                    .monitoringIntervalMinutes()
                    .coerceAtLeast(1)

            if (intervalMinutes < 15) {
                // PeriodicWorkRequest has a platform minimum of 15 minutes.
                // For 1/2/5/10 minute choices use a unique chain of delayed
                // OneTimeWorkRequests instead.
                workManager.cancelUniqueWork(
                    PERIODIC_WORK
                )

                scheduleShortRun(
                    context = context,
                    intervalMinutes =
                        intervalMinutes,
                    replaceExisting = true
                )


            } else {
                workManager.cancelUniqueWork(
                    SHORT_INTERVAL_WORK
                )

                val request =
                    PeriodicWorkRequestBuilder<
                        LowFunctionMonitorWorker
                    >(
                        intervalMinutes.toLong(),
                        TimeUnit.MINUTES
                    )
                        .setConstraints(
                            networkConstraints
                        )
                        .addTag(PERIODIC_WORK)
                        .build()

                workManager.enqueueUniquePeriodicWork(
                    PERIODIC_WORK,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    request
                )


            }
        }

        private fun scheduleNextShortRun(
            context: Context
        ) {
            if (FullFunctionRuntime.active) {
                return
            }

            val enabled =
                ServerProfileStore(context)
                    .list()
                    .any { it.monitorEnabled }

            if (!enabled) {
                WorkManager.getInstance(context)
                    .cancelUniqueWork(
                        SHORT_INTERVAL_WORK
                    )
                return
            }

            val intervalMinutes =
                AppSettingsStore(context)
                    .monitoringIntervalMinutes()
                    .coerceAtLeast(1)

            // If Settings changed to >=15 min while this worker was running,
            // hand scheduling back to the normal periodic path.
            if (intervalMinutes >= 15) {
                sync(context)
                return
            }

            scheduleShortRun(
                context = context,
                intervalMinutes =
                    intervalMinutes,
                replaceExisting = true
            )
        }

        private fun scheduleShortRun(
            context: Context,
            intervalMinutes: Int,
            replaceExisting: Boolean
        ) {
            val input = workDataOf(
                KEY_WORK_KIND to
                    WORK_KIND_SHORT_LOOP
            )

            val request =
                OneTimeWorkRequestBuilder<
                    LowFunctionMonitorWorker
                >()
                    .setInitialDelay(
                        intervalMinutes
                            .coerceAtLeast(1)
                            .toLong(),
                        TimeUnit.MINUTES
                    )
                    .setConstraints(
                        networkConstraints
                    )
                    .setInputData(input)
                    .addTag(
                        SHORT_INTERVAL_WORK
                    )
                    .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    SHORT_INTERVAL_WORK,
                    if (replaceExisting) {
                        ExistingWorkPolicy.REPLACE
                    } else {
                        ExistingWorkPolicy.KEEP
                    },
                    request
                )
        }

        fun runNow(context: Context) {
            if (FullFunctionRuntime.monitoringBlocked()) return
            if (
                !ServerProfileStore(context)
                    .list()
                    .any { it.monitorEnabled }
            ) {
                return
            }

            val request =
                OneTimeWorkRequestBuilder<
                    LowFunctionMonitorWorker
                >()
                    .setConstraints(
                        networkConstraints
                    )
                    .setInputData(
                        workDataOf(
                            KEY_WORK_KIND to
                                WORK_KIND_IMMEDIATE
                        )
                    )
                    .addTag(IMMEDIATE_WORK)
                    .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    IMMEDIATE_WORK,
                    ExistingWorkPolicy.REPLACE,
                    request
                )
        }

        suspend fun awaitIdle(timeoutMs: Long = 12_000L) {
            val started = System.currentTimeMillis()

            while (
                RuntimeState.activeWorkers.get() > 0 &&
                System.currentTimeMillis() - started < timeoutMs
            ) {
                delay(50L)
            }
        }

        private object RuntimeState {
            val activeWorkers = AtomicInteger(0)
            val probeRunning = java.util.concurrent.atomic.AtomicBoolean(false)
        }
    }
}

/**
 * Process-local gate. If Android kills the process, this resets to false;
 * that is correct because the Full TS3 socket is gone as well.
 */
object FullFunctionRuntime {
    @Volatile
    var active: Boolean = false

    @Volatile
    private var probesPausedUntil: Long = 0L

    fun pauseMonitoringAfterDisconnect(durationMs: Long = 90_000L) {
        probesPausedUntil = android.os.SystemClock.elapsedRealtime() + durationMs
    }

    fun monitoringBlocked(): Boolean =
        active || android.os.SystemClock.elapsedRealtime() < probesPausedUntil
}

private class PresenceStateStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "teamspeak_low_presence_state",
        Context.MODE_PRIVATE
    )

    fun load(profileId: String): Map<String, String>? {
        if (!prefs.contains(key(profileId))) {
            return null
        }

        val raw = prefs.getStringSet(
            key(profileId),
            emptySet()
        ).orEmpty()

        return raw.associateWith { it }
    }

    fun save(
        profileId: String,
        clients: Map<String, BackendClient>
    ) {
        prefs.edit()
            .putStringSet(
                key(profileId),
                clients.keys.toSet()
            )
            .apply()
    }

    private fun key(profileId: String): String =
        "presence_$profileId"
}
