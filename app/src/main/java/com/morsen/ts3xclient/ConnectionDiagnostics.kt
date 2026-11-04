package com.morsen.ts3xclient

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Process-wide, bounded diagnostic timeline. Never log credentials or payloads. */
internal object ConnectionDiagnostics {
    private const val TAG = "TS3X_CONN"
    private const val MAX_BYTES = 512 * 1024L
    private const val KEEP_BYTES = 256 * 1024
    private val lock = Any()
    private var file: File? = null
    private val clock = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun initialize(context: Context) {
        synchronized(lock) {
            if (file == null) file = File(context.filesDir, "ts3x_connection_diagnostics.log")
        }
    }

    fun event(kind: String, details: String = "") {
        // Call sites must provide only non-sensitive status codes / booleans / timings.
        val line = synchronized(lock) {
            "${clock.format(Date())} elapsed=${SystemClock.elapsedRealtime()} $kind $details".trim()
        }
        Log.i(TAG, line)
        synchronized(lock) {
            runCatching {
                val target = file ?: return@runCatching
                if (target.length() > MAX_BYTES) {
                    val previous = target.readBytes()
                    target.writeBytes(previous.takeLast(KEEP_BYTES).toByteArray())
                }
                target.appendText(line + "\n")
            }.onFailure { Log.w(TAG, "Unable to persist diagnostics: ${it.javaClass.simpleName}") }
        }
    }

    fun network(context: Context, label: String) {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val active = manager?.activeNetwork
        val caps = active?.let { manager.getNetworkCapabilities(it) }
        event("network.$label", "active=${active != null} validated=${caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)} " +
            "internet=${caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)} " +
            "wifi=${caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)} " +
            "cellular=${caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)} " +
            "vpn=${caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)}")
    }
}
