package com.morsen.ts3xclient

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.morsen.ts3xclient.protocol.TeamSpeakForegroundSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The connected voice session is process-owned rather than Activity-owned. */
internal object VoiceSession {
    @Volatile private var instance: Ts3jBackend? = null
    @Synchronized fun backend(context: Context): Ts3jBackend =
        instance ?: Ts3jBackend(context.applicationContext).also { instance = it }
    @Volatile var notificationPtt = false
}

class VoiceSessionService : Service() {
    companion object {
        private const val CHANNEL_ID = "ts3x_connected_voice"
        private const val NOTIFICATION_ID = 4720
        private const val POKE_CHANNEL_ID = "ts3x_incoming_poke"
        private const val POKE_NOTIFICATION_BASE = 4900
        private const val ACTION_START = "com.morsen.ts3xclient.voice.START"
        private const val ACTION_STOP = "com.morsen.ts3xclient.voice.STOP"
        private const val ACTION_MIC = "com.morsen.ts3xclient.voice.MIC"
        private const val ACTION_OUTPUT = "com.morsen.ts3xclient.voice.OUTPUT"
        private const val ACTION_PTT = "com.morsen.ts3xclient.voice.PTT"
        private const val ACTION_DISCONNECT = "com.morsen.ts3xclient.voice.DISCONNECT"
        fun start(context: Context) {
            val intent = Intent(context, VoiceSessionService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }
        fun stop(context: Context) {
            context.stopService(Intent(context, VoiceSessionService::class.java))
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var updater: Job? = null
    private lateinit var manager: NotificationManager
    private val pokeSequence = java.util.concurrent.atomic.AtomicInteger(0)
    private val backend get() = VoiceSession.backend(applicationContext)
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var cpuWakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    /** These are session-scoped; never keep them for a disconnected backend. */
    private fun acquireSessionLocks() {
        if (!backend.connected) return
        if (cpuWakeLock?.isHeld != true) {
            runCatching {
                val power = getSystemService(Context.POWER_SERVICE) as PowerManager
                val lock = cpuWakeLock ?: power.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "TS3XClient:VoiceSession"
                ).apply { setReferenceCounted(false) }.also { cpuWakeLock = it }
                lock.acquire()
            }.onFailure {
                ConnectionDiagnostics.event("power.wakelock.acquire.failed", it.javaClass.simpleName)
                Log.w("TS3X_CONN", "Could not acquire CPU wake lock", it)
            }
        }
        if (wifiLock?.isHeld != true) {
            runCatching {
                val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                val lock = wifiLock ?: wifi.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                    "TS3XClient:VoiceWifi"
                ).apply { setReferenceCounted(false) }.also { wifiLock = it }
                lock.acquire()
            }.onFailure {
                ConnectionDiagnostics.event("power.wifilock.acquire.failed", it.javaClass.simpleName)
                Log.w("TS3X_CONN", "Could not acquire Wi-Fi lock", it)
            }
        }
        ConnectionDiagnostics.event(
            "power.locks.acquired",
            "cpu=${cpuWakeLock?.isHeld == true} wifi=${wifiLock?.isHeld == true}"
        )
    }

    private fun releaseSessionLocks(reason: String) {
        val cpuHeld = cpuWakeLock?.isHeld == true
        val wifiHeld = wifiLock?.isHeld == true
        runCatching { if (cpuHeld) cpuWakeLock?.release() }
            .onFailure { Log.w("TS3X_CONN", "CPU wake lock release failed", it) }
        runCatching { if (wifiHeld) wifiLock?.release() }
            .onFailure { Log.w("TS3X_CONN", "Wi-Fi lock release failed", it) }
        ConnectionDiagnostics.event("power.locks.released", "reason=$reason cpu=$cpuHeld wifi=$wifiHeld")
    }

    override fun onCreate() {
        super.onCreate()
        ConnectionDiagnostics.initialize(applicationContext)
        ConnectionDiagnostics.event("service.created")
        ConnectionDiagnostics.network(applicationContext, "service-created")
        val cm = getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager
        connectivityManager = cm
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                ConnectionDiagnostics.event("network.available")
                ConnectionDiagnostics.network(applicationContext, "available")
            }
            override fun onLost(network: Network) {
                ConnectionDiagnostics.event("network.lost")
                ConnectionDiagnostics.network(applicationContext, "lost")
            }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                ConnectionDiagnostics.event("network.capabilities", "validated=${capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)} " +
                    "wifi=${capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)} " +
                    "cellular=${capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)} " +
                    "vpn=${capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)}")
            }
        }
        runCatching { cm?.registerDefaultNetworkCallback(callback); networkCallback = callback }
            .onFailure { ConnectionDiagnostics.event("network.callback.failed", it.javaClass.simpleName) }
        manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "TeamSpeak voice connection", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Voice connection and audio controls"
            setShowBadge(false)
        })
        manager.createNotificationChannel(NotificationChannel(
            POKE_CHANNEL_ID,
            "TeamSpeak pokes",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Incoming poke messages from other TeamSpeak clients"
            enableVibration(true)
        })
    }

    private fun showIncomingPoke(sender: String, message: String) {
        if (!backend.connected) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED) {
            Log.w("TS3X_POKE", "Notification permission not granted")
            return
        }
        val launch = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, POKE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("TeamSpeak · $sender")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(launch)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        manager.notify(POKE_NOTIFICATION_BASE + pokeSequence.incrementAndGet(), notification)
    }

    private fun promoteSessionForeground(): Boolean = TeamSpeakForegroundSession.start(
        object : TeamSpeakForegroundSession.Host {
            override fun hasMicrophonePermission() = ContextCompat.checkSelfPermission(
                this@VoiceSessionService, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            override fun startForeground(microphone: Boolean) {
                val notification = buildNotification()
                if (Build.VERSION.SDK_INT >= 29) {
                    var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                    if (microphone && Build.VERSION.SDK_INT >= 30) {
                        types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    }
                    this@VoiceSessionService.startForeground(NOTIFICATION_ID, notification, types)
                } else this@VoiceSessionService.startForeground(NOTIFICATION_ID, notification)
            }

            override fun stopCapture() {
                backend.stopAlwaysOn()
                backend.stopPushToTalk()
                VoiceSession.notificationPtt = false
            }
        }
    )

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ConnectionDiagnostics.event("service.command", "action=${intent?.action?.substringAfterLast('.') ?: "null"} " +
            "connected=${backend.connected} startId=$startId")
        if (intent?.action == ACTION_STOP) {
            updater?.cancel()
            releaseSessionLocks("stop-command")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!backend.connected) {
            releaseSessionLocks("not-connected")
            stopSelf()
            return START_NOT_STICKY
        }
        val microphoneReady = try {
            promoteSessionForeground().also {
                ConnectionDiagnostics.event("service.foreground", "microphone=$it")
            }
        } catch (e: Exception) {
            Log.e("TS3X_VOICE_SERVICE", "Unable to start foreground voice service", e)
            releaseSessionLocks("foreground-start-failed")
            stopSelf()
            return START_NOT_STICKY
        }
        if (microphoneReady && (intent == null || intent.action == ACTION_START) &&
            backend.audioInputMode() == AudioInputMode.ALWAYS_ON && !backend.isInputMuted()) {
            backend.startAlwaysOn()
        }
        when (intent?.action) {
            ACTION_MIC -> serviceScope.launch {
                if (backend.connected && (!backend.isInputMuted() || microphoneReady)) {
                    if (!backend.isInputMuted()) {
                        backend.stopPushToTalk()
                        VoiceSession.notificationPtt = false
                    }
                    withContext(Dispatchers.IO) { backend.toggleInputMuted() }
                    publish()
                }
            }
            ACTION_OUTPUT -> {
                if (backend.connected) {
                    backend.setPlaybackMuted(!backend.audioStats().playbackMuted)
                    publish()
                }
            }
            ACTION_PTT -> {
                if (backend.connected && microphoneReady && backend.audioInputMode() == AudioInputMode.PTT && !backend.isInputMuted()) {
                    if (VoiceSession.notificationPtt) {
                        backend.stopPushToTalk()
                        VoiceSession.notificationPtt = false
                    } else {
                        val result = backend.startPushToTalk()
                        VoiceSession.notificationPtt = result.isSuccess
                    }
                    publish()
                }
            }
            ACTION_DISCONNECT -> serviceScope.launch {
                backend.stopPushToTalk()
                VoiceSession.notificationPtt = false
                withContext(Dispatchers.IO) { backend.disconnect() }
                updater?.cancel()
                releaseSessionLocks("user-disconnect")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        if (!backend.connected) {
            releaseSessionLocks("not-connected")
            stopSelf()
            return START_NOT_STICKY
        }
        acquireSessionLocks()
        if (updater?.isActive != true) {
            updater = serviceScope.launch {
                while (isActive && backend.connected) {
                    publish()
                    delay(1_500L)
                }
                if (!backend.connected) {
                    ConnectionDiagnostics.event("service.connection.inactive")
                    releaseSessionLocks("connection-lost")
                    VoiceSession.notificationPtt = false
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    private fun action(name: String, action: String, requestCode: Int): NotificationCompat.Action {
        val intent = Intent(this, VoiceSessionService::class.java).setAction(action)
        val pending = PendingIntent.getService(this, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Action.Builder(0, name, pending).build()
    }

    private fun buildNotification(): Notification {
        val stats = backend.audioStats()
        val mode = backend.audioInputMode()
        val launch = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val state = when {
            stats.inputMuted -> "Microphone muted"
            mode == AudioInputMode.PTT && VoiceSession.notificationPtt -> "Transmitting (PTT ON)"
            mode == AudioInputMode.PTT -> "Push to talk"
            else -> "Voice activated"
        }
        val title = backend.serverName()?.takeIf { it.isNotBlank() } ?: "TeamSpeak server"
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentTitle("TS3X · $title")
            .setContentText("Connected · $state")
            .setContentIntent(launch)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(action(if (stats.inputMuted) "Unmute mic" else "Mute mic", ACTION_MIC, 11))
            .addAction(action(if (stats.playbackMuted) "Sound on" else "Sound off", ACTION_OUTPUT, 12))
        if (mode == AudioInputMode.PTT) {
            builder.addAction(action(if (VoiceSession.notificationPtt) "PTT off" else "PTT on", ACTION_PTT, 13))
        }
        builder.addAction(action("Disconnect", ACTION_DISCONNECT, 14))
        return builder.build()
    }

    private fun publish() {
        if (backend.connected) manager.notify(NOTIFICATION_ID, buildNotification())
    }

    override fun onDestroy() {
        ConnectionDiagnostics.event("service.destroyed", "connected=${backend.connected}")
        releaseSessionLocks("service-destroyed")
        networkCallback?.let { callback ->
            runCatching { connectivityManager?.unregisterNetworkCallback(callback) }
        }
        networkCallback = null
        updater?.cancel()
        serviceScope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
