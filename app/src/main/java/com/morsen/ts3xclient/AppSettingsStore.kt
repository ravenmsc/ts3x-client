package com.morsen.ts3xclient

import android.content.Context

enum class ProxyMode {
    NONE,
    SOCKS5,
    HTTPS
}

enum class AudioInputMode {
    PTT,
    ALWAYS_ON
}

data class ProxySettings(
    val mode: ProxyMode = ProxyMode.NONE,
    val host: String = "",
    val port: Int = 0,
    val username: String = "",
    val password: String = ""
)

class AppSettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "teamspeak_app_settings",
        Context.MODE_PRIVATE
    )

    fun inputDeviceId(): Int? =
        prefs.getInt(KEY_INPUT_DEVICE, NO_DEVICE).takeIf { it != NO_DEVICE }

    fun outputDeviceId(): Int? =
        prefs.getInt(KEY_OUTPUT_DEVICE, NO_DEVICE).takeIf { it != NO_DEVICE }

    fun setInputDeviceId(id: Int?) {
        prefs.edit().putInt(KEY_INPUT_DEVICE, id ?: NO_DEVICE).apply()
    }

    fun setOutputDeviceId(id: Int?) {
        prefs.edit().putInt(KEY_OUTPUT_DEVICE, id ?: NO_DEVICE).apply()
    }

    fun nickname(): String =
        prefs.getString(KEY_NICKNAME, "AndroidUser")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: "AndroidUser"

    fun setNickname(value: String) {
        val normalized = value.trim().takeIf { it.isNotBlank() } ?: "AndroidUser"
        prefs.edit().putString(KEY_NICKNAME, normalized).commit()
    }

    fun avatarPath(): String =
        prefs.getString(KEY_AVATAR_PATH, "")
            ?.trim()
            .orEmpty()

    fun avatarHash(): String =
        prefs.getString(KEY_AVATAR_HASH, "")
            ?.trim()
            .orEmpty()

    fun setAvatar(path: String, hash: String) {
        prefs.edit()
            .putString(KEY_AVATAR_PATH, path)
            .putString(KEY_AVATAR_HASH, hash)
            .commit()
    }

    fun clearAvatar() {
        prefs.edit()
            .remove(KEY_AVATAR_PATH)
            .remove(KEY_AVATAR_HASH)
            .commit()
    }

    fun audioInputMode(): AudioInputMode =
        runCatching {
            AudioInputMode.valueOf(
                prefs.getString(KEY_AUDIO_INPUT_MODE, AudioInputMode.PTT.name)
                    ?: AudioInputMode.PTT.name
            )
        }.getOrDefault(AudioInputMode.PTT)

    fun voiceActivationThreshold(): Float =
        prefs.getFloat(KEY_VOICE_THRESHOLD, DEFAULT_VOICE_THRESHOLD)
            .coerceIn(0.005f, 0.50f)

    fun setAudioInputSettings(mode: AudioInputMode, threshold: Float) {
        prefs.edit()
            .putString(KEY_AUDIO_INPUT_MODE, mode.name)
            .putFloat(KEY_VOICE_THRESHOLD, threshold.coerceIn(0.005f, 0.50f))
            .apply()
    }

    fun monitoringIntervalMinutes(): Int =
        prefs.getInt(
            KEY_MONITOR_INTERVAL_MINUTES,
            DEFAULT_MONITOR_INTERVAL_MINUTES
        ).coerceAtLeast(MIN_MONITOR_INTERVAL_MINUTES)

    fun setMonitoringIntervalMinutes(minutes: Int) {
        prefs.edit()
            .putInt(
                KEY_MONITOR_INTERVAL_MINUTES,
                minutes.coerceAtLeast(MIN_MONITOR_INTERVAL_MINUTES)
            )
            .apply()
    }

    fun collapsedChannels(serverKey: String): Set<Long> =
        prefs.getStringSet(collapsedKey(serverKey), emptySet()).orEmpty()
            .mapNotNull { it.toLongOrNull() }
            .toSet()

    fun setCollapsedChannels(serverKey: String, channelIds: Set<Long>) {
        prefs.edit()
            .putStringSet(collapsedKey(serverKey), channelIds.map(Long::toString).toSet())
            .apply()
    }

    private fun collapsedKey(serverKey: String): String =
        KEY_COLLAPSED_PREFIX + serverKey.replace(Regex("[^A-Za-z0-9_.-]"), "_")

    fun proxySettings(): ProxySettings {
        val mode = runCatching {
            ProxyMode.valueOf(
                prefs.getString(KEY_PROXY_MODE, ProxyMode.NONE.name)
                    ?: ProxyMode.NONE.name
            )
        }.getOrDefault(ProxyMode.NONE)

        return ProxySettings(
            mode = mode,
            host = prefs.getString(KEY_PROXY_HOST, "") ?: "",
            port = prefs.getInt(KEY_PROXY_PORT, 0),
            username = prefs.getString(KEY_PROXY_USER, "") ?: "",
            password = prefs.getString(KEY_PROXY_PASSWORD, "") ?: ""
        )
    }

    fun setProxySettings(settings: ProxySettings) {
        prefs.edit()
            .putString(KEY_PROXY_MODE, settings.mode.name)
            .putString(KEY_PROXY_HOST, settings.host)
            .putInt(KEY_PROXY_PORT, settings.port)
            .putString(KEY_PROXY_USER, settings.username)
            .putString(KEY_PROXY_PASSWORD, settings.password)
            .apply()
    }

    companion object {
        private const val NO_DEVICE = -1

        private const val KEY_INPUT_DEVICE = "audio_input_device"
        private const val KEY_OUTPUT_DEVICE = "audio_output_device"
        private const val KEY_NICKNAME = "client_nickname"
        private const val KEY_AVATAR_PATH = "client_avatar_path"
        private const val KEY_AVATAR_HASH = "client_avatar_hash"
        private const val KEY_AUDIO_INPUT_MODE = "audio_input_mode"
        private const val KEY_VOICE_THRESHOLD = "voice_activation_threshold"
        private const val KEY_COLLAPSED_PREFIX = "collapsed_channels_"
        private const val DEFAULT_VOICE_THRESHOLD = 0.04f

        private const val KEY_MONITOR_INTERVAL_MINUTES =
            "monitor_interval_minutes"
        private const val DEFAULT_MONITOR_INTERVAL_MINUTES = 15
        private const val MIN_MONITOR_INTERVAL_MINUTES = 1

        private const val KEY_PROXY_MODE = "proxy_mode"
        private const val KEY_PROXY_HOST = "proxy_host"
        private const val KEY_PROXY_PORT = "proxy_port"
        private const val KEY_PROXY_USER = "proxy_username"
        private const val KEY_PROXY_PASSWORD = "proxy_password"
    }
}
