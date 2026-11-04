package com.morsen.ts3xclient

import android.Manifest
import android.content.Intent
import android.graphics.BitmapFactory
import android.content.Context
import android.net.Uri
import android.util.Log
import android.util.Base64
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.ClickableText
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.zIndex
import androidx.compose.ui.input.pointer.positionChange
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlin.math.min
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.annotation.StringRes
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified
import java.util.Locale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.ImageLoader
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.CachePolicy
import coil.request.ErrorResult
import coil.request.SuccessResult
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Protocol
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image as ChatImageIcon
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.GroupWork
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.HideImage
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.HeadsetOff
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider

/**
 * Process-owned Coil prefetch queue. Compose recompositions / channel list changes
 * cannot cancel image downloads. Coil handles memory/disk cache persistence.
 */
private object ChannelBannerPrefetcher {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Semaphore(3)
    private val inFlight = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val completed = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val failedUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val FETCH_TIMEOUT_MS = 12_000L
    private const val FAILURE_COOLDOWN_MS = 120_000L
    private const val MAX_IMAGE_BYTES = 8L * 1024L * 1024L
    private val localFallbacks = mutableStateMapOf<String, String>()
    private val http1Client = OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(16, TimeUnit.SECONDS)
        .build()

    private fun fallbackFile(context: Context, url: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(File(context.filesDir, "channel_banner_http1"), "$digest.img")
    }

    // Row composition must never hash URLs or access the filesystem.
    fun displayData(url: String): Any = localFallbacks[url]?.let(::File) ?: url

    private fun tryHttp1Fallback(context: Context, channelId: Long, channelName: String, url: String): Boolean {
        if (!url.startsWith("https://", ignoreCase = true)) return false
        val target = fallbackFile(context, url)
        if (target.isFile && target.length() > 0L) {
            localFallbacks[url] = target.absolutePath
            return true
        }
        val request = Request.Builder().url(url)
            .header("Accept", "image/avif,image/webp,image/png,image/jpeg,image/gif,*/*;q=0.8")
            .header("User-Agent", "Mozilla/5.0 (Android) TS3XClient/0.6")
            .build()
        Log.i("TS3X_BANNER", "HTTP1 fallback started cid=$channelId channel=$channelName")
        val response = http1Client.newCall(request).execute()
        response.use {
            if (!it.isSuccessful) {
                Log.w("TS3X_BANNER", "HTTP1 fallback HTTP ${it.code} cid=$channelId channel=$channelName")
                return false
            }
            val body = it.body ?: return false
            val contentType = body.contentType()?.toString().orEmpty()
            if (!contentType.startsWith("image/", ignoreCase = true)) {
                Log.w("TS3X_BANNER", "HTTP1 fallback non-image contentType=$contentType cid=$channelId")
                return false
            }
            if (body.contentLength() > MAX_IMAGE_BYTES) return false
            target.parentFile?.mkdirs()
            val part = File(target.parentFile, target.name + ".part")
            try {
                var total = 0L
                body.byteStream().use { input ->
                    part.outputStream().use { output ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > MAX_IMAGE_BYTES) error("Banner is larger than 8 MiB")
                            output.write(buffer, 0, count)
                        }
                    }
                }
                if (total == 0L) return false
                if (!part.renameTo(target)) {
                    part.copyTo(target, overwrite = true)
                    part.delete()
                }
                localFallbacks[url] = target.absolutePath
                Log.i("TS3X_BANNER", "HTTP1 fallback loaded cid=$channelId channel=$channelName bytes=$total")
                return true
            } finally {
                part.delete()
            }
        }
    }

    fun enqueue(context: Context, banners: List<Triple<Long, String, String>>) {
        val appContext = context.applicationContext
        banners.forEach { (channelId, channelName, url) ->
            if (completed.contains(url)) return@forEach
            val now = SystemClock.elapsedRealtime()
            if ((failedUntil[url] ?: 0L) > now) return@forEach
            if (!inFlight.add(url)) return@forEach
            Log.i("TS3X_BANNER", "Queued cid=$channelId channel=$channelName url=$url")
            scope.launch {
                try {
                    gate.withPermit {
                        val fallback = fallbackFile(appContext, url)
                        if (fallback.isFile && fallback.length() > 0L) {
                            localFallbacks[url] = fallback.absolutePath
                            completed.add(url)
                            return@withPermit
                        }
                        val imageLoader = appContext.imageLoader
                        var loaded = false
                        for (attempt in 1..2) {
                            Log.i("TS3X_BANNER", "Started cid=$channelId channel=$channelName attempt=$attempt url=$url")
                            // Do not let a stalled Steam CDN request consume a slot forever.
                            val result = kotlinx.coroutines.withTimeoutOrNull(FETCH_TIMEOUT_MS) {
                                runCatching {
                                    imageLoader.execute(
                                        ImageRequest.Builder(appContext)
                                            .data(url)
                                            // Warm a header-sized bitmap instead of decoding a
                                            // multi-megapixel original for every off-screen channel.
                                            .size(appContext.resources.displayMetrics.widthPixels.coerceIn(320, 1600),
                                                (96 * appContext.resources.displayMetrics.density).toInt().coerceIn(96, 384))
                                            .memoryCachePolicy(CachePolicy.ENABLED)
                                            .diskCachePolicy(CachePolicy.ENABLED)
                                            .build()
                                    )
                                }
                            }
                            if (result == null) {
                                Log.w("TS3X_BANNER", "Timeout cid=$channelId channel=$channelName attempt=$attempt after=${FETCH_TIMEOUT_MS}ms url=$url")
                            } else {
                                val error = result.exceptionOrNull()
                                    ?: (result.getOrNull() as? ErrorResult)?.throwable
                                if (error == null && result.getOrNull() is SuccessResult) {
                                    Log.i("TS3X_BANNER", "Loaded cid=$channelId channel=$channelName attempt=$attempt")
                                    completed.add(url)
                                    loaded = true
                                    break
                                }
                                Log.w("TS3X_BANNER", "Load failed cid=$channelId channel=$channelName attempt=$attempt url=$url cause=${error?.javaClass?.simpleName}: ${error?.message}", error)
                                val message = error?.message.orEmpty()
                                if (message.contains("404") || message.contains("403")) break
                            }
                            if (attempt < 2) delay(2_000L)
                        }
                        if (!loaded) {
                            // The Steam CDN may stall during HTTP/2 response body reads.
                            // Keep other banner requests on Coil; only failed URLs use HTTP/1.1.
                            loaded = try {
                                tryHttp1Fallback(appContext, channelId, channelName, url)
                            } catch (error: Exception) {
                                Log.w("TS3X_BANNER", "HTTP1 fallback failed cid=$channelId channel=$channelName cause=${error.javaClass.simpleName}: ${error.message}")
                                false
                            }
                            if (loaded) completed.add(url)
                        }
                        if (!loaded) {
                            failedUntil[url] = SystemClock.elapsedRealtime() + FAILURE_COOLDOWN_MS
                            Log.w("TS3X_BANNER", "Cooling down cid=$channelId channel=$channelName for=${FAILURE_COOLDOWN_MS}ms url=$url")
                        }
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    failedUntil[url] = SystemClock.elapsedRealtime() + FAILURE_COOLDOWN_MS
                    Log.e("TS3X_BANNER", "Prefetch crashed cid=$channelId channel=$channelName url=$url", error)
                } finally {
                    inFlight.remove(url)
                }
            }
        }
    }
}

private val DarkGreen = Color(0xFF071B12)
private val GreenSurface = Color(0xFF0E2A1D)
private val GreenSurface2 = Color(0xFF153828)
private val GreenAccent = Color(0xFF45C486)
private val GreenAccentSoft = Color(0xFF1D5B3E)
private val TextPrimary = Color(0xFFE7F5ED)
private val TextSecondary = Color(0xFFA8C8B8)

private const val InviteScheme = "ts3x"
private const val InviteHost = "server"

private data class SharedServerInvite(
    val host: String,
    val port: Int,
    val displayName: String = "",
    val password: String = ""
)

// Base64URL makes the invitation opaque to casual reading, NOT encrypted.
// Anyone possessing a link can decode its embedded server password.
private fun inviteLinkForProfile(profile: SavedServerProfile): String {
    val payload = org.json.JSONObject().apply {
        put("v", 1)
        put("host", profile.host)
        put("port", profile.port)
        put("name", profile.displayName)
        put("password", profile.password)
    }.toString()
    val encoded = Base64.encodeToString(
        payload.toByteArray(Charsets.UTF_8),
        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
    )
    return Uri.Builder()
        .scheme(InviteScheme)
        .authority(InviteHost)
        .appendPath("add")
        .appendQueryParameter("data", encoded)
        .build()
        .toString()
}

private fun parseSharedServerInvite(uri: Uri?): SharedServerInvite? {
    if (uri == null) return null
    val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
    val linkHost = uri.host?.lowercase(Locale.ROOT) ?: return null
    val path = uri.path?.trimEnd('/') ?: return null

    if (scheme == InviteScheme && linkHost == InviteHost && path == "/add") {
        val encoded = uri.getQueryParameter("data")
        if (encoded != null && uri.queryParameterNames == setOf("data")) {
            // Avoid unrestricted allocation for untrusted deep links.
            if (encoded.length !in 1..8192) return null
            return try {
                val bytes = Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
                val json = org.json.JSONObject(String(bytes, Charsets.UTF_8))
                if (json.optInt("v", -1) != 1) return null
                val host = json.optString("host").trim()
                val port = json.optInt("port", -1)
                val name = json.optString("name").trim()
                val password = json.optString("password")
                if (!isValidInviteHost(host) || port !in 1..65535 ||
                    name.length > 256 || password.length > 1024) return null
                SharedServerInvite(host, port, name, password)
            } catch (_: Exception) {
                null
            }
        }
    }

    // Official long TeamSpeak links contain only a public hostname and optional port.
    // Never accept clear-text credentials or legacy ts3x:// query parameters.
    if (scheme == "https" && linkHost in setOf("tmspk.gg", "www.tmspk.gg") &&
        path.startsWith("/s/")) {
        val serverHost = Uri.decode(path.removePrefix("/s/")).trim()
        if (!isValidInviteHost(serverHost)) return null
        val portValue = uri.getQueryParameter("port")
        val serverPort = if (portValue.isNullOrBlank()) 9987
            else portValue.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        return SharedServerInvite(host = serverHost, port = serverPort)
    }
    return null
}

private fun isValidInviteHost(host: String): Boolean =
    host.isNotBlank() && host.length <= 253 &&
        host.none { it.isWhitespace() || it in "/\\?#@" } &&
        !host.contains("..")

/**
 * Resolve TeamSpeak 6 short invite codes via the invite lookup API.
 * Resolve official tmspk.gg short codes to a server address via TeamSpeak's invite API.
 */
private suspend fun resolveTeamSpeakInvite(uri: Uri): SharedServerInvite? =
    withContext(Dispatchers.IO) {
        parseSharedServerInvite(uri)?.let { return@withContext it }

        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        val domain = uri.host?.lowercase(Locale.ROOT)
        val code = when {
            scheme == "https" && domain in setOf("tmspk.gg", "www.tmspk.gg") ->
                uri.pathSegments.singleOrNull()
            else -> null
        }?.takeIf { Regex("^[A-Za-z0-9_-]{4,96}$").matches(it) }
        if (code == null) {
            Log.w("TS3X_INVITE", "Unsupported invite URL or malformed short code")
            return@withContext null
        }

        val lookup = "https://invites.teamspeak.com/query/?id=" +
            java.net.URLEncoder.encode(code, "UTF-8")
        // The TeamSpeak invite service requires browser-navigation headers on some
        // Cloudflare routes. Use one ordinary HTTPS request with system DNS and
        // OkHttp's default protocol negotiation (no special DNS or protocol retries).
        val request = Request.Builder()
            .url(lookup)
            .get()
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/155.0.0.0 Safari/537.36")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
            .header("Accept-Language", "ru,en;q=0.9,en-US;q=0.8,de;q=0.6")
            .header("Cache-Control", "max-age=0")
            .header("Upgrade-Insecure-Requests", "1")
            .header("Sec-Fetch-Dest", "document")
            .header("Sec-Fetch-Mode", "navigate")
            .header("Sec-Fetch-Site", "none")
            .header("Sec-Fetch-User", "?1")
            .header("DNT", "1")
            .build()
        val client = OkHttpClient.Builder()
            .connectTimeout(7, TimeUnit.SECONDS)
            .readTimeout(7, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
        val response = try {
            client.newCall(request).execute().use { http ->
                if (!http.isSuccessful) {
                    Log.w("TS3X_INVITE", "Invite lookup failed: HTTP ${http.code}")
                    null
                } else {
                    http.body?.string()?.takeIf { it.isNotBlank() }
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w("TS3X_INVITE", "Invite lookup failed: ${error.javaClass.simpleName}")
            null
        }
        if (response == null) return@withContext null

        try {
            val json = org.json.JSONObject(response)
            if (json.optString("id") != code || json.optString("type") != "server") {
                Log.w("TS3X_INVITE", "Unexpected invite ID or type")
                return@withContext null
            }
            val expires = json.optLong("expires", 0L)
            if (expires > 0 && expires <= System.currentTimeMillis() / 1000L) {
                Log.w("TS3X_INVITE", "Invite has expired")
                return@withContext null
            }
            val address = json.optString("address").trim()
            val hostAndPort = parseInviteServerAddress(address)
            if (hostAndPort == null) {
                Log.w("TS3X_INVITE", "Invite API returned an invalid server address")
                return@withContext null
            }
            val name = json.optString("name").trim().take(128)
            Log.i("TS3X_INVITE", "Invite resolved to ${hostAndPort.first}:${hostAndPort.second}")
            SharedServerInvite(hostAndPort.first, hostAndPort.second, name)
        } catch (error: org.json.JSONException) {
            Log.w("TS3X_INVITE", "Malformed invite API JSON: ${error.message}")
            null
        }
    }

private fun parseInviteServerAddress(address: String): Pair<String, Int>? {
    if (address.isBlank()) return null
    val host: String
    val portText: String?
    if (address.startsWith("[")) {
        val end = address.indexOf(']')
        if (end < 0) return null
        host = address.substring(1, end)
        val rest = address.substring(end + 1)
        if (rest.isNotEmpty() && !rest.startsWith(":")) return null
        portText = rest.takeIf { it.isNotEmpty() }?.substring(1)
    } else {
        if (address.count { it == ':' } > 1) return null
        host = address.substringBefore(':')
        portText = if (':' in address) address.substringAfter(':') else null
    }
    if (!isValidInviteHost(host) || host.contains(':')) return null
    val port = if (portText == null) 9987 else
        portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
    return host to port
}

private fun parseTeamSpeakServerUri(uri: Uri): SharedServerInvite? {
    if (uri.scheme?.lowercase(java.util.Locale.ROOT) !in setOf("tsserver", "ts3server", "teamspeak")) return null
    val host = uri.host ?: return null
    if (!isValidInviteHost(host)) return null
    val port = uri.getQueryParameter("port")?.toIntOrNull()?.takeIf { it in 1..65535 } ?: 9987
    return SharedServerInvite(host, port)
}

private fun importSharedServerInvite(
    profileStore: ServerProfileStore,
    uri: Uri?
): SavedServerProfile? = importSharedServerInvite(profileStore, parseSharedServerInvite(uri))

private fun importSharedServerInvite(
    profileStore: ServerProfileStore,
    invite: SharedServerInvite?
): SavedServerProfile? {
    invite ?: return null

    val existing =
        profileStore.list()
            .firstOrNull {
                it.host.equals(invite.host, ignoreCase = true) &&
                    it.port == invite.port
            }

    val merged =
        if (existing != null) {
            existing.copy(
                name = existing.name.ifBlank { invite.displayName },
                password = invite.password.ifEmpty { existing.password }
            )
        } else {
            SavedServerProfile(
                name = invite.displayName,
                host = invite.host,
                port = invite.port,
                password = invite.password
            )
        }

    profileStore.upsert(merged)
    return merged
}

private fun shareServerProfile(
    context: Context,
    profile: SavedServerProfile
) {
    val invite = inviteLinkForProfile(profile)
    val intent =
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, profile.displayName)
            putExtra(Intent.EXTRA_TEXT, invite)
        }

    context.startActivity(
        Intent.createChooser(intent, "Share server")
    )
}

private fun serverGroupIconFile(
    context: android.content.Context,
    host: String,
    port: Int,
    iconId: Long
): File {
    return artworkFile(context, "group_icons/${serverIconCacheKey(host, port)}", "${iconId and 0xffffffffL}.bin")
}

/** Persist the server-group-to-icon lookup so disk-cached role icons can be
 * rendered before the first servergrouplist response on subsequent connects.
 * This is only a presentation cache; the server remains authoritative.
 */
private fun cachedServerGroupIcons(
    context: Context,
    host: String,
    port: Int
): Map<Long, Long> {
    val key = serverIconCacheKey(host, port)
    val raw = context.getSharedPreferences("ts3x_role_icon_metadata", Context.MODE_PRIVATE)
        .getString(key, null) ?: return emptyMap()
    return raw.split(';').mapNotNull { entry ->
        val parts = entry.split(':', limit = 2)
        if (parts.size != 2) null else {
            val groupId = parts[0].toLongOrNull()
            val iconId = parts[1].toLongOrNull()
            if (groupId == null || iconId == null) null else groupId to iconId
        }
    }.toMap()
}

private fun persistServerGroupIcons(
    context: Context,
    host: String,
    port: Int,
    groups: Map<Long, Long>
) {
    val key = serverIconCacheKey(host, port)
    val value = groups.entries.joinToString(";") { (groupId, iconId) ->
        "$groupId:$iconId"
    }
    context.getSharedPreferences("ts3x_role_icon_metadata", Context.MODE_PRIVATE)
        .edit().putString(key, value).apply()
}

private fun clientAvatarCacheFile(
    context: Context,
    serverHost: String,
    serverPort: Int,
    uid: String
): File {
    val identity = "${context.filesDir}/client_avatars/$serverHost:$serverPort:$uid"
    return artworkFiles.get(identity) ?: run {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$serverHost:$serverPort:$uid".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        artworkFile(context, "client_avatars", "$digest.bin").also { artworkFiles.put(identity, it) }
    }
}

private fun serverIconCacheKey(
    host: String,
    port: Int
): String {
    val input = "${host.trim().lowercase(Locale.ROOT)}:$port"
    return serverIconKeys.get(input) ?: MessageDigest.getInstance("MD5")
        .digest(input.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
        .also { serverIconKeys.put(input, it) }
}

private val artworkFiles = android.util.LruCache<String, File>(2048)
private val serverIconKeys = android.util.LruCache<String, String>(128)

/** Path lookup does no disk I/O; directories are created only by the background writer. */
private fun artworkFile(context: Context, directory: String, name: String): File {
    val key = "${context.filesDir}/$directory/$name"
    return artworkFiles.get(key) ?: File(key).also { artworkFiles.put(key, it) }
}

private fun writeArtwork(file: File, bytes: ByteArray) {
    check(bytes.isNotEmpty()) { "Empty artwork" }
    file.parentFile?.mkdirs()
    val atomic = android.util.AtomicFile(file)
    val output = atomic.startWrite()
    try {
        output.write(bytes)
        atomic.finishWrite(output)
    } catch (error: Throwable) {
        atomic.failWrite(output)
        throw error
    }
}

private fun serverIconVersions(context: Context) =
    context.getSharedPreferences("ts3x_server_icon_versions", Context.MODE_PRIVATE)

private fun serverIconCachePathOrNull(context: Context, host: String, port: Int): String? {
    val key = serverIconCacheKey(host, port)
    val version = serverIconVersions(context).getString(key, null)
    if (version == "0") return null
    val name = if (version == null) "$key.bin" else "${key}_$version.bin"
    return artworkFile(context, "server_icons", name)
        .takeIf { it.isFile && it.length() > 0L }?.absolutePath
}

/** Server icon IDs are content versions. Reuse unchanged artwork across sessions. */
private fun cacheCurrentServerIcon(context: Context, backend: Ts3jBackend, host: String, port: Int): Boolean {
    val id = backend.serverInfo().iconId?.and(0xffffffffL) ?: return false
    val key = serverIconCacheKey(host, port)
    val versions = serverIconVersions(context)
    val version = id.toString()
    val previousPath = serverIconCachePathOrNull(context, host, port)
    if (versions.getString(key, null) == version && (id == 0L || previousPath != null)) return false
    if (id == 0L) {
        versions.edit().putString(key, version).apply()
        previousPath?.let { File(it).delete() }
        return previousPath != null
    }
    val bytes = backend.downloadCurrentServerIcon().getOrNull() ?: return false
    if (bytes.isEmpty()) return false
    val file = artworkFile(context, "server_icons", "${key}_$version.bin")
    writeArtwork(file, bytes)
    versions.edit().putString(key, version).apply()
    if (previousPath != file.absolutePath) previousPath?.let { File(it).delete() }
    return true
}

data class IncomingInviteDelivery(val intent: Intent, val sequence: Long)

class MainActivity : ComponentActivity() {
    // Permission approval on first launch must never start recording or push-to-talk.
    private val startupPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* Audio starts only through an explicit connection/audio action. */ }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.localizedContext(newBase))
    }

    private val incomingIntentState = mutableStateOf<IncomingInviteDelivery?>(null)
    private var incomingIntentSequence = 0L

    private fun receiveInviteIntent(delivered: Intent, source: String) {
        val data = delivered.data
        Log.i("TS3X_INVITE", "Intent received source=$source action=${delivered.action} scheme=${data?.scheme} host=${data?.host} flags=${delivered.flags}")
        if (delivered.action != Intent.ACTION_VIEW || delivered.data == null) {
            Log.i("TS3X_INVITE", "Intent ignored: not an ACTION_VIEW deep link")
            return
        }
        incomingIntentSequence += 1L
        incomingIntentState.value = IncomingInviteDelivery(delivered, incomingIntentSequence)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ConnectionDiagnostics.initialize(applicationContext)
        ConnectionDiagnostics.event("activity.created", "restored=${savedInstanceState != null}")
        receiveInviteIntent(intent, "onCreate")
        setContent { TeamSpeakApp(incomingIntentState) }
        val permissionPrefs = getSharedPreferences("ts3x_permission_prompts", Context.MODE_PRIVATE)
        if (!permissionPrefs.getBoolean("startup_requested", false)) {
            val missing = buildList {
                add(Manifest.permission.RECORD_AUDIO)
                if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
            permissionPrefs.edit().putBoolean("startup_requested", true).apply()
            if (missing.isNotEmpty()) startupPermissions.launch(missing.toTypedArray())
        }
    }

    override fun onStart() {
        super.onStart()
        ConnectionDiagnostics.event("activity.foreground")
        // Re-evaluate while-in-use permission when returning from Android settings.
        if (VoiceSession.backend(applicationContext).connected) {
            runCatching { VoiceSessionService.start(this) }
                .onFailure { Log.w("TS3X_VOICE_SERVICE", "Foreground session resume failed", it) }
        }
    }

    override fun onStop() {
        ConnectionDiagnostics.event("activity.background")
        super.onStop()
    }

    override fun onDestroy() {
        ConnectionDiagnostics.event("activity.destroyed", "finishing=$isFinishing")
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receiveInviteIntent(intent, "onNewIntent")
    }
}


/** Keep action labels on one line while using the available button width.
 * The English label is no longer used as an artificial maximum for translations. */
@Composable
private fun FittedControlLabel(
    @StringRes id: Int,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.labelLarge,
    minFontSize: TextUnit = 9.sp,
    textAlign: TextAlign = TextAlign.Center
) {
    val text = stringResource(id)
    val measurer = rememberTextMeasurer()
    val density = androidx.compose.ui.platform.LocalDensity.current
    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = if (textAlign == TextAlign.Start) Alignment.CenterStart else Alignment.Center
    ) {
        val maxPx = with(density) { maxWidth.toPx() }.toInt()
        val base = if (style.fontSize.isSpecified) style.fontSize else 14.sp
        var size = base
        if (maxPx > 0) {
            while (size.value > minFontSize.value &&
                measurer.measure(text, style = style.copy(fontSize = size), maxLines = 1).size.width > maxPx
            ) {
                size = (size.value - 0.5f).coerceAtLeast(minFontSize.value).sp
            }
        }
        Text(
            text = text,
            style = style,
            fontSize = size,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            textAlign = textAlign
        )
    }
}

@Composable
private fun localizedLabel(en: String, de: String, ru: String): String =
    when (LocalConfiguration.current.locales[0].language) {
        "de" -> de
        "ru" -> ru
        else -> en
    }

private data class OpenChatConversation(
    val conversationKey: String,
    val title: String,
    val peerClientId: Int? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeamSpeakApp(incomingIntentState: State<IncomingInviteDelivery?>) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val backend = remember { VoiceSession.backend(context) }
    val profileStore = remember { ServerProfileStore(context) }
    val scope = rememberCoroutineScope()
    var openChatConversation by remember { mutableStateOf<OpenChatConversation?>(null) }
    var chatRevision by remember { mutableIntStateOf(0) }
    DisposableEffect(backend) {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        backend.setChatListener { handler.post { chatRevision++ } }
        onDispose {
            backend.setChatListener(null)
            backend.setOpenChat(null)
        }
    }

    fun openChannelChat(channel: BackendChannel) {
        val key = backend.channelChatKey(channel.id)
        backend.setOpenChat(key)
        openChatConversation = OpenChatConversation(key, channel.name)
        chatRevision++
    }
    fun openPrivateChat(client: BackendClient) {
        if (client.id == backend.ownClientId) return
        val key = backend.privateChatKey(client)
        backend.setOpenChat(key)
        openChatConversation = OpenChatConversation(key, client.nickname, client.id)
        chatRevision++
    }

    var serverProfiles by remember {
        mutableStateOf(profileStore.list())
    }
    var draggingServerId by remember { mutableStateOf<String?>(null) }
    var draggedDistance by remember { mutableFloatStateOf(0f) }
    var editingServer by remember { mutableStateOf<SavedServerProfile?>(null) }
    var serverEditorOpen by remember { mutableStateOf(false) }
    // This is a user-action event, not a dialog to restore on the next app launch.
    var monitoringBackgroundPrompt by remember { mutableStateOf(false) }
    var inviteLinkDialogOpen by remember { mutableStateOf(false) }
    var inviteLinkText by remember { mutableStateOf("") }
    var inviteLinkError by remember { mutableStateOf<String?>(null) }
    var deleteServer by remember { mutableStateOf<SavedServerProfile?>(null) }
    var currentProfileId by remember { mutableStateOf<String?>(null) }
    var serverIconGeneration by remember { mutableIntStateOf(0) }

    var monitoringIntervalMinutes by remember {
        mutableIntStateOf(
            backend.monitoringIntervalMinutes()
        )
    }

    // Prevent an automatic metadata probe from racing a Full connection.
    var metadataProbeProfileId by remember {
        mutableStateOf<String?>(null)
    }

    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("9987") }
    var nickname by remember { mutableStateOf(backend.savedNickname()) }
    var password by remember { mutableStateOf("") }

    var status by remember { mutableStateOf("Ready") }
    var connectionFailureDialog by remember { mutableStateOf<Pair<String, String>?>(null) }
    var connecting by remember { mutableStateOf(false) }
    var disconnecting by remember { mutableStateOf(false) }
    var joiningChannelId by remember {
        mutableStateOf<Long?>(null)
    }
    var connected by remember { mutableStateOf(backend.connected) }

    var channels by remember { mutableStateOf<List<BackendChannel>>(emptyList()) }
    var clients by remember { mutableStateOf<List<BackendClient>>(emptyList()) }

    var selectedChannelId by remember { mutableStateOf<Long?>(null) }
    var collapsedChannelIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var fullscreenInfo by remember { mutableStateOf<FullscreenInfoContent?>(null) }

    // Press feedback belongs to the entire connected-server bar.
    var serverBarPressed by remember { mutableStateOf(false) }

    // Used only to suppress a Disconnect click generated by releasing a
    // successful long press that started over the Disconnect child area.
    var lastServerInfoOpenedAt by remember { mutableLongStateOf(0L) }

    var pttPressed by remember { mutableStateOf(false) }
    val activeVoiceState = remember { mutableStateOf<Set<Int>>(emptySet()) }
    var activeVoiceClientIds by activeVoiceState
    var audioInputMode by remember {
        mutableStateOf(backend.audioInputMode())
    }
    var audioThreshold by remember {
        mutableFloatStateOf(backend.voiceActivationThreshold())
    }

    var showSettings by remember { mutableStateOf(false) }
    var settingsGeneration by remember { mutableIntStateOf(0) }

    val microphonePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            if (backend.connected) {
                runCatching { VoiceSessionService.start(context) }
                    .onFailure { Log.w("TS3X_VOICE_SERVICE", "Foreground session promotion failed", it) }
            }
            if (backend.isInputMuted()) {

                status = "Microphone is muted"
                return@rememberLauncherForActivityResult
            }

            val result = if (
                backend.audioInputMode() == AudioInputMode.ALWAYS_ON
            ) {
                backend.startAlwaysOn()
            } else {
                backend.startPushToTalk()
            }

            pttPressed =
                backend.audioInputMode() == AudioInputMode.PTT && result.isSuccess

            status = if (result.isSuccess) "Connected"
            else result.exceptionOrNull()?.message ?: "Could not start microphone"
        } else {
            status = "Microphone permission denied"
        }
    }

    val notificationPermissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            status = if (granted) {
                "Low function notifications enabled"
            } else {
                "Notification permission denied; background event alerts may be hidden"
            }
        }

    var passwordChannel by remember { mutableStateOf<BackendChannel?>(null) }
    var channelPassword by remember { mutableStateOf("") }

    var joinWarning by remember {
        mutableStateOf<JoinWarningDialogState?>(null)
    }
    var joinCooldownRemaining by remember {
        mutableIntStateOf(0)
    }

    LaunchedEffect(joinWarning) {
        val warning = joinWarning

        if (
            warning != null &&
            warning.cooldownSeconds > 0
        ) {
            joinCooldownRemaining =
                warning.cooldownSeconds

            while (
                joinWarning === warning &&
                joinCooldownRemaining > 0
            ) {
                delay(1_000L)

                if (
                    joinWarning === warning
                ) {
                    joinCooldownRemaining =
                        (
                            joinCooldownRemaining - 1
                        ).coerceAtLeast(0)
                }
            }
        } else {
            joinCooldownRemaining = 0
        }
    }

    // Preload all currently known channel banners into Coil's disk cache.
    // This also handles late metadata updates: the URL set is rebuilt when
    // the channel snapshot changes. HTTP work never runs on the UI thread.
    val parentChannelIds = remember(channels) { channels.mapTo(HashSet()) { it.parentId } }
    val bannerDescriptionKeys = remember(channels) { channels.map { Triple(it.id, it.description, it.bannerUrl) } }
    val bannerSources = remember(channels) {
        channels.mapNotNull { channel ->
            channelImageUrl(channel)?.let { url ->
                Triple(channel.id, channel.name, url)
            }
        }.distinctBy { it.third }
    }
    // Launch from a process-owned scope: refreshing channel metadata must not
    // cancel an in-flight HTTP fetch (LaunchedEffect's child jobs used to).
    LaunchedEffect(bannerSources) {
        ChannelBannerPrefetcher.enqueue(context.applicationContext, bannerSources)
    }

    // Some TS3 servers only supply the image in channel_description after
    // channelgetdescription. A URL-only Coil prefetch cannot discover these.
    // Fetch missing descriptions once per channel/connection, sequentially and
    // conservatively to avoid TeamSpeak server anti-flood limits.
    val requestedBannerDescriptions = remember { mutableSetOf<Long>() }
    LaunchedEffect(connected, currentProfileId) {
        requestedBannerDescriptions.clear()
    }
    LaunchedEffect(connected, currentProfileId, bannerDescriptionKeys) {
        if (!connected || disconnecting) return@LaunchedEffect
        val candidateIds = channels.asSequence()
            .filter { channelImageUrl(it) == null }
            .filter { it.id in parentChannelIds }
            .filter { !it.name.startsWith("[", ignoreCase = true) }
            .map { it.id }
            .filter { requestedBannerDescriptions.add(it) }
            .take(12)
            .toList()
        for (channelId in candidateIds) {
            if (!backend.connected || disconnecting) break
            val candidate = channels.firstOrNull { it.id == channelId }
            Log.i("TS3X_BANNER", "Description request cid=$channelId name=${candidate?.name} descriptionLength=${candidate?.description?.length ?: 0}")
            val result = withContext(Dispatchers.IO) {
                backend.channelDetails(channelId)
            }
            if (result.isSuccess) {
                val updated = result.getOrNull()
                Log.i("TS3X_BANNER", "Description received cid=$channelId url=${updated?.let(::channelImageUrl)} descriptionLength=${updated?.description?.length}")
                channels = backend.channels()
            } else {
                Log.w("TS3X_BANNER", "Description failed cid=$channelId: ${result.exceptionOrNull()?.message}")
            }
            // Do not run many TeamSpeak metadata commands in one burst.
            kotlinx.coroutines.delay(1_800L)
        }
    }

    LaunchedEffect(connected) {
        while (connected) {
            val snapshots = withContext(Dispatchers.IO) {
                Triple(
                    backend.channels(),
                    backend.clients(),
                    backend.connected
                )
            }

            channels = snapshots.first
            clients = snapshots.second


            if (!snapshots.third) {
                status = "Disconnected"
                connected = false
                FullFunctionRuntime.active = false

                // The local syncLowMonitoring helper is declared later in this
                // composable, so this early branch calls WorkManager directly.
                LowFunctionMonitorWorker.sync(context)

                break
            }

            delay(500)
        }
    }

    // Separate, short voice-activity refresh: don't prolong the TX ring with the
    // channel/client 500 ms polling cadence. Allow only a brief packet-jitter gap.
    LaunchedEffect(connected, audioInputMode, pttPressed, audioThreshold) {
        if (!connected) {
            activeVoiceClientIds = emptySet()
            return@LaunchedEffect
        }
        while (connected) {
            val remoteIds = backend.activeVoiceClientIds()
            val stats = backend.audioStats()
            val localIds = if (backend.ownClientId > 0 && !stats.inputMuted &&
                ((audioInputMode == AudioInputMode.PTT && pttPressed) ||
                    (audioInputMode == AudioInputMode.ALWAYS_ON && stats.micLevel > audioThreshold))) {
                setOf(backend.ownClientId)
            } else emptySet()
            val currentIds = remoteIds + localIds
            if (activeVoiceClientIds != currentIds) activeVoiceClientIds = currentIds
            delay(50L)
        }
    }

    // Role icons are prepared in the background, never downloaded from a row's UI.
    // Cache is persistent per server and the icon ID is the filename, so a changed
    // server-group icon automatically selects a new cache entry.
    var roleIconRevision by remember { mutableIntStateOf(0) }
    var groupIconIds by remember { mutableStateOf<Map<Long, Long>>(emptyMap()) }
    LaunchedEffect(connected, host, port) {
        groupIconIds = emptyMap()
        roleIconRevision++
        if (!connected) return@LaunchedEffect

        val serverPort = port.toIntOrNull() ?: 9987
        // Never associate role/icon metadata with a profile other than the active transport.
        if (backend.activeServerEndpoint()?.let { (h, p) ->
                h.equals(host, ignoreCase = true) && p == serverPort
            } != true) return@LaunchedEffect
        // Files are already persisted by icon ID. Restore the group lookup
        // synchronously before the first network refresh, avoiding the 5s flash.
        groupIconIds = cachedServerGroupIcons(context, host, serverPort)
        val attemptedAt = mutableMapOf<Long, Long>()
        var nextGroupRefreshAt = 0L
        var nextChannelRefreshAt = 0L
        // No artificial delay: role metadata and cache should be ready ASAP.
        // File transfers remain serialized inside Ts3jBackend.

        while (connected && backend.connected) {
            val now = SystemClock.elapsedRealtime()
            if (now >= nextGroupRefreshAt || groupIconIds.isEmpty()) {
                val groups = withContext(Dispatchers.IO) {
                    backend.fetchServerGroupIcons().getOrNull()
                }
                if (groups != null && groups.isNotEmpty()) {
                    if (groups != groupIconIds) {
                        groupIconIds = groups
                        persistServerGroupIcons(context, host, serverPort, groups)
                    }
                    nextGroupRefreshAt = now + 180_000L
                } else {
                    nextGroupRefreshAt = now + 30_000L
                }
            }

            if (now >= nextChannelRefreshAt) {
                withContext(Dispatchers.IO) { backend.refreshChannelGroupSummaries() }
                    .onFailure { if (backend.connected && !disconnecting) Log.w("TS3X_GROUPS", "channel role metadata refresh failed", it) }
                nextChannelRefreshAt = now + 180_000L
                roleIconRevision++
            }

            // Read a fresh client snapshot every iteration: joined users and changed
            // group memberships must not require reconnecting the app.
            val wanted = backend.clients()
                .asSequence()
                .filterNot { it.isServerQuery }
                .flatMap { it.serverGroupIds.asSequence() }
                .mapNotNull { groupIconIds[it] }
                .plus(backend.channelGroupIconIdsForClients().asSequence())
                .filter { it != 0L }
                .distinct()
                .take(32)
                .toList()

            for (iconId in wanted) {
                if (!connected || !backend.connected) break
                val cached = serverGroupIconFile(context, host, serverPort, iconId)
                if (withContext(Dispatchers.IO) { cached.isFile && cached.length() > 0L }) continue
                val lastAttempt = attemptedAt[iconId] ?: 0L
                if (lastAttempt != 0L && SystemClock.elapsedRealtime() - lastAttempt < 60_000L) continue
                attemptedAt[iconId] = SystemClock.elapsedRealtime()

                val saved = withContext(Dispatchers.IO) {
                    val bytes = backend.downloadServerGroupIcon(iconId).getOrNull()
                    if (bytes == null || bytes.isEmpty()) false else runCatching {
                        writeArtwork(cached, bytes)
                        true
                    }.getOrDefault(false)
                }
                if (saved) {
                    roleIconRevision++ // Make completed downloads visible to Compose.
                } else {
                    if (backend.connected && !disconnecting) Log.w("TS3X_ROLES", "Role icon download deferred/failed id=$iconId")
                }
                delay(350L)
            }
            delay(5_000L)
        }
    }

    val visibleAvatarClients = remember { mutableStateOf<Set<Int>>(emptySet()) }
    var avatarCacheRevision by remember { mutableIntStateOf(0) }
    // The old one-shot "attempted" set prevented avatars from updating until
    // reconnect. Keep a revision-aware disk cache with bounded periodic checks.
    LaunchedEffect(connected, host, port) {
        if (!connected) return@LaunchedEffect
        val serverPort = port.toIntOrNull() ?: 9987
        val metadata = context.getSharedPreferences("ts3x_client_avatar_versions", Context.MODE_PRIVATE)
        val lastAttempt = mutableMapOf<Int, Long>()
        val retryInterval = mutableMapOf<Int, Long>()
        val attemptedVersion = mutableMapOf<Int, Triple<String, String?, String?>>()
        while (connected && backend.connected) {
            val visible = visibleAvatarClients.value
            val scanTime = SystemClock.elapsedRealtime()
            val candidates = withContext(Dispatchers.IO) {
                backend.clients().filter { client ->
                    attemptedVersion[client.id] != Triple(client.uniqueId, client.avatarHash, client.accountAvatarUrl) ||
                        scanTime - (lastAttempt[client.id] ?: 0L) >= (retryInterval[client.id] ?: 90_000L)
                }.sortedWith(compareBy<BackendClient>(
                    { if (it.id in visible) 0 else 1 },
                    { if (!it.avatarHash.isNullOrBlank() && it.avatarHash != "0") 0 else 1 }
                )).take(4)
            }
            for (snapshot in candidates) {
                if (!connected || !backend.connected) break
                val fingerprint = Triple(snapshot.uniqueId, snapshot.avatarHash, snapshot.accountAvatarUrl)
                if (attemptedVersion[snapshot.id] != fingerprint) {
                    lastAttempt.remove(snapshot.id)
                    retryInterval.remove(snapshot.id)
                    attemptedVersion[snapshot.id] = fingerprint
                    val flagState = when {
                        snapshot.avatarHash == null -> "unknown"
                        com.morsen.ts3xclient.protocol.TeamSpeakAvatarMetadata.isAbsent(snapshot.avatarHash) -> "absent"
                        else -> "present"
                    }
                    if (flagState != "absent" || snapshot.accountAvatarUrl != null || snapshot.id in visible) {
                        Log.i("TS3X_AVATAR", "state clientId=${snapshot.id} flag=$flagState accountReady=${snapshot.accountAvatarUrl != null} accountKnown=${snapshot.accountAvatarKnown} uidReady=${snapshot.uniqueId.isNotBlank()}")
                    }
                }
                val now = SystemClock.elapsedRealtime()
                val cachedVersion = snapshot.uniqueId.takeIf { it.isNotBlank() }?.let {
                    metadata.getString(clientAvatarCacheFile(context, host, serverPort, it).name, null)
                }
                val unchangedCachedAvatar = com.morsen.ts3xclient.protocol.TeamSpeakAvatarMetadata.isContentHash(snapshot.avatarHash) &&
                    cachedVersion == snapshot.avatarHash &&
                    snapshot.uniqueId.isNotBlank() && withContext(Dispatchers.IO) {
                        clientAvatarCacheFile(context, host, serverPort, snapshot.uniqueId).let {
                            it.isFile && it.length() > 0L
                        }
                    }
                if (unchangedCachedAvatar) {
                    lastAttempt[snapshot.id] = now
                    continue
                }
                val changed = !snapshot.avatarHash.isNullOrBlank() && cachedVersion != snapshot.avatarHash
                val interval = retryInterval[snapshot.id] ?: if (changed) 4_000L else 90_000L
                if (now - (lastAttempt[snapshot.id] ?: -interval) < interval) continue
                lastAttempt[snapshot.id] = now
                // Some servers restrict clientinfo independently of avatar downloads.
                // Keep the UID-based file request available when metadata is denied.
                val metadataResult = withContext(Dispatchers.IO) {
                    backend.clientAvatarMetadata(snapshot.id)
                }
                val client = metadataResult.getOrNull() ?: snapshot.takeIf { it.uniqueId.isNotBlank() }
                if (snapshot.avatarHash == null || snapshot.uniqueId.isBlank()) delay(350L)
                if (client == null) {
                    retryInterval[snapshot.id] = ((retryInterval[snapshot.id] ?: 2_500L) * 2).coerceAtMost(60_000L)
                    continue
                }
                attemptedVersion[client.id] = Triple(client.uniqueId, client.avatarHash, client.accountAvatarUrl)
                val uid = client.uniqueId
                if (uid.isBlank()) continue
                val file = clientAvatarCacheFile(context, host, serverPort, uid)
                val cacheKey = file.name
                val currentVersion = client.avatarHash?.trim()
                val hasVersion = !currentVersion.isNullOrBlank()
                val noAvatar = com.morsen.ts3xclient.protocol.TeamSpeakAvatarMetadata.isAbsent(currentVersion)
                if (noAvatar) {
                    if (withContext(Dispatchers.IO) { file.exists() && file.delete() }) {
                        avatarCacheRevision++
                    }
                    metadata.edit().putString(cacheKey, currentVersion).apply()
                    retryInterval[client.id] = if (metadataResult.isSuccess) 600_000L else 60_000L
                    // Keep metadata-only probes within the same anti-flood budget as file requests.
                    delay(350L)
                    continue
                }
                val saved = withContext(Dispatchers.IO) {
                    val bytes = backend.downloadClientAvatar(uid).getOrNull()
                    currentCoroutineContext().ensureActive()
                    if (bytes == null || bytes.isEmpty()) false else runCatching {
                        writeArtwork(file, bytes)
                        true
                    }.getOrDefault(false)
                }
                if (saved) {
                    if (hasVersion) metadata.edit().putString(cacheKey, currentVersion).apply()
                    retryInterval.remove(client.id)
                    avatarCacheRevision++
                    Log.i("TS3X_AVATAR", "cache.saved clientId=${client.id} bytes=${file.length()}")
                } else {
                    retryInterval[client.id] = ((retryInterval[client.id] ?: 2_500L) * 2).coerceAtMost(60_000L)
                }
                // Avoid flooding the TeamSpeak file-transfer connection.
                delay(350L)
            }
            delay(if (candidates.isEmpty()) 1_000L else 500L)
        }
    }

    LaunchedEffect(connected) {
        if (!connected) {
            openChatConversation = null
            backend.setOpenChat(null)
        }
    }

    LaunchedEffect(connected) {
        while (connected) {
            delay(25_000L)

            val result = withContext(Dispatchers.IO) {
                backend.keepAlive()
            }

            if (!backend.connected) {
                connected = false
                pttPressed = false

                status = "Connection lost"
                FullFunctionRuntime.active = false
                LowFunctionMonitorWorker.sync(context)
                break
            }

            if (result.isFailure) {
                status = "Checking connection…"
            } else if (status == "Checking connection…") {
                status = "Connected"
            }
        }
    }

    // The voice connection is process-owned. Activity disposal must never disconnect it.
    LaunchedEffect(connected) {
        if (connected && backend.connected) {
            try {
                VoiceSessionService.start(context)
            } catch (e: Exception) {
                Log.e("TS3X_VOICE_SERVICE", "Foreground service start failed", e)
            }
        } else if (!backend.connected) {
            VoiceSessionService.stop(context)
        }
    }

    fun refreshSnapshots() {
        channels = backend.channels()
        clients = backend.clients()
    }

    fun performJoin(
        channel: BackendChannel,
        pw: String?
    ) {
        if (joiningChannelId != null) {
            return
        }

        if (
            backend.currentChannelId ==
                channel.id
        ) {
            selectedChannelId = channel.id
            status = "Already in ${channel.name}"
            return
        }

        joiningChannelId = channel.id

        scope.launch {
            try {
                status =
                    "Joining ${channel.name}…"

                val result =
                    withContext(
                        Dispatchers.IO
                    ) {
                        backend.joinChannel(
                            channel.id,
                            pw
                        )
                    }

                status = backend.status
                refreshSnapshots()

                if (
                    result.isSuccess &&
                    backend.currentChannelId ==
                        channel.id
                ) {
                    selectedChannelId =
                        channel.id
                } else if (
                    result.isFailure
                ) {
                    val message =
                        backend.status
                            .trim()
                            .ifBlank {
                                result
                                    .exceptionOrNull()
                                    ?.message
                                    ?.trim()
                                    .orEmpty()
                            }
                            .ifBlank {
                                "The channel switch failed."
                            }

                    val flooding =
                        message.contains(
                            "anti-flood",
                            ignoreCase = true
                        ) ||
                            result
                                .exceptionOrNull()
                                ?.let {
                                    error ->
                                    generateSequence(
                                        error
                                    ) {
                                        current ->
                                        current.cause
                                            ?.takeIf {
                                                cause ->
                                                cause !== current
                                            }
                                    }
                                        .take(12)
                                        .any {
                                            throwable ->
                                            throwable.message
                                                .orEmpty()
                                                .contains(
                                                    "client is flooding",
                                                    ignoreCase = true
                                                )
                                        }
                                } == true

                    val insufficient =
                        message.contains(
                            "insufficient permissions",
                            ignoreCase = true
                        )

                    val wrongPassword =
                        message.contains(
                            "incorrect channel password",
                            ignoreCase = true
                        )

                    joinWarning =
                        JoinWarningDialogState(
                            title =
                                when {
                                    flooding ->
                                        "Server anti-flood protection"

                                    insufficient ->
                                        "Insufficient permissions"

                                    wrongPassword ->
                                        "Channel password rejected"

                                    else ->
                                        "Could not switch channel"
                                },
                            message = message,
                            channel = channel,
                            password = pw,
                            cooldownSeconds =
                                if (flooding) {
                                    5
                                } else {
                                    0
                                },
                            canRetry =
                                flooding
                        )
                }
            } finally {
                joiningChannelId = null
            }
        }
    }

    fun selectChannelForPreview(channel: BackendChannel) {
        selectedChannelId = channel.id
        // Channel selection must never move the connected voice client.
        // Resolve channel metadata only when it is missing from the snapshot.
        if (channel.description.isBlank() || channelImageUrl(channel) == null) {
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    backend.channelDetails(channel.id)
                }
                if (result.isSuccess && selectedChannelId == channel.id) {
                    channels = backend.channels()
                } else if (result.isFailure) {
                    Log.d("TS3X_CHANNEL", "preview.details.unavailable cid=${channel.id}: ${result.exceptionOrNull()?.message}")
                }
            }
        }
    }

    fun selectAndJoin(
        channel: BackendChannel
    ) {
        selectedChannelId = channel.id

        val resolved =
            backend.channels()
                .firstOrNull {
                    it.id == channel.id
                }
                ?: channel

        // Channel-list data already includes password state. Do not issue
        // getChannelInfo immediately before clientmove; that extra command can
        // trigger the server anti-flood limiter.
        if (resolved.hasPassword) {
            channelPassword = ""
            passwordChannel = resolved
        } else {
            performJoin(
                resolved,
                null
            )
        }
    }

    fun moveServerProfile(sourceId: String, destinationId: String) {
        if (sourceId == destinationId) return
        val items = serverProfiles.toMutableList()
        val from = items.indexOfFirst { it.id == sourceId }
        val to = items.indexOfFirst { it.id == destinationId }
        if (from < 0 || to < 0) return
        val moved = items.removeAt(from)
        items.add(to, moved)
        serverProfiles = items
        profileStore.reorder(items.map { it.id })
    }

    fun refreshProfiles(): List<SavedServerProfile> {
        val profiles = profileStore.list()
        serverProfiles = profiles
        return profiles
    }

    fun ensureNotificationPermission() {
        if (
            Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(
                Manifest.permission.POST_NOTIFICATIONS
            )
        }
    }

    fun syncLowMonitoring(runNow: Boolean = false) {
        refreshProfiles()
        LowFunctionMonitorWorker.sync(context)

        if (runNow && !FullFunctionRuntime.active) {
            LowFunctionMonitorWorker.runNow(context)
        }
    }

    fun probeServerName(profile: SavedServerProfile) {
        // A manually chosen name is presentation-only and must not open a connection.
        if (profile.name.isNotBlank() || profile.serverName.isNotBlank()) {
            status = "Server saved: ${profile.displayName}"
            refreshProfiles()
            return
        }
        if (metadataProbeProfileId == profile.id) return
        metadataProbeProfileId = profile.id
        scope.launch {
            status = "Reading server name from ${profile.host}…"
            FullFunctionRuntime.active = true
            try {
                withContext(Dispatchers.IO) {
                    LowFunctionMonitorWorker.awaitIdle()
                    val probe = Ts3jBackend(context, enableAudio = false, metadataOnly = true)
                    try {
                        probe.connect(profile.host, profile.port, backend.savedNickname(),
                            profile.password, persistNickname = false).getOrThrow()
                        suspend fun publishName() {
                            probe.serverName()?.let { name ->
                                profileStore.updateServerName(profile.id, name)
                                withContext(Dispatchers.Main) { refreshProfiles() }
                            }
                        }
                        publishName()
                        // initserver usually contains both fields. Request missing values
                        // only; never subscribe to hundreds of users for a list entry.
                        val info = probe.serverInfo()
                        if (info.name.isBlank() || info.iconId == null) {
                            probe.refreshServerInfo()
                            publishName()
                        }
                        if (cacheCurrentServerIcon(context, probe, profile.host, profile.port)) {
                            withContext(Dispatchers.Main) { serverIconGeneration++ }
                        }
                    } finally {
                        probe.disconnect()
                    }
                }
                status = profileStore.get(profile.id)?.displayName
                    ?.let { "Server saved: $it" } ?: "Server saved"
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                Log.w("TS3X_SERVER_ICON", "Server metadata deferred", error)
                status = "Server saved: ${profile.displayName}"
            } finally {
                FullFunctionRuntime.active = backend.connected || connecting
                metadataProbeProfileId = null
                refreshProfiles()
                LowFunctionMonitorWorker.sync(context)
            }
        }
    }

    fun connectSavedServer(profile: SavedServerProfile) {
        if (
            connecting ||
            disconnecting ||
            metadataProbeProfileId == profile.id
        ) {
            return
        }

        currentProfileId = profile.id
        host = profile.host
        port = profile.port.toString()

        // Identity + nickname are GLOBAL application settings.
        nickname = backend.savedNickname()
        password = profile.password

        connecting = true
        connectionFailureDialog = null
        status = "Connecting to ${profile.displayName}…"

        // Prevent a periodic Low probe from starting while Full mode is active.
        FullFunctionRuntime.active = true

        scope.launch {
            val result = withContext(Dispatchers.IO) {
                LowFunctionMonitorWorker.awaitIdle()

                backend.connect(
                    host = profile.host,
                    port = profile.port,
                    nickname = backend.savedNickname(),
                    password = profile.password,
                    persistNickname = false
                )
            }

            connecting = false
            status = backend.status
            connected = result.isSuccess && backend.connected
            refreshSnapshots()

            if (connected) {
                collapsedChannelIds = backend.collapsedChannels(profile.id)
                audioInputMode = backend.audioInputMode()
                audioThreshold = backend.voiceActivationThreshold()


                // Public name is already available from the initserver
                // metadata captured during connect. Avoid another
                // servergetvariables/serverinfo pair here because it consumes
                // TeamSpeak anti-flood budget immediately before user actions.
                val publicName =
                    backend.serverInfo()
                        .name
                        .trim()
                        .takeIf {
                            it.isNotBlank()
                        }
                        ?: backend.serverName()
                            ?.trim()
                            ?.takeIf {
                                it.isNotBlank()
                            }

                publicName
                    ?.let {
                        serverName ->
                        profileStore
                            .updateServerName(
                                profile.id,
                                serverName
                            )
                        refreshProfiles()
                    }

                val iconSaved = withContext(Dispatchers.IO) {
                    cacheCurrentServerIcon(context, backend, profile.host, profile.port)
                }

                if (iconSaved) {
                    serverIconGeneration++
                }
            } else {
                FullFunctionRuntime.active = false
                status = backend.status
                val failure = result.exceptionOrNull()
                val causeText = generateSequence(failure) { it.cause }
                    .take(12)
                    .joinToString(" | ") { "${it.javaClass.simpleName}: ${it.message.orEmpty()}" }
                val diagnosticText = "$causeText | ${backend.status}"
                val identityRejected = diagnosticText.contains(
                    "could not validate client identity", ignoreCase = true
                ) || diagnosticText.contains(
                    "invalid client identity", ignoreCase = true
                )
                val passwordRejected = diagnosticText.contains(
                    "invalid server password", ignoreCase = true
                )
                // Show a modal only for an explicit server rejection. Generic timeouts
                // and connection errors still use the bottom connection status.
                connectionFailureDialog = when {
                    identityRejected -> "Identity rejected" to
                        "Increase your Identity Security Level in Settings and try again."
                    passwordRejected -> "Invalid server password" to
                        "Check the server password and try connecting again."
                    else -> null
                }
                Log.w("TS3X_CONN", "connection.failed identityRejected=$identityRejected passwordRejected=$passwordRejected details=$diagnosticText")
                syncLowMonitoring()
            }
        }
    }

    LaunchedEffect(Unit) {
        // The foreground service owns the socket across Activity recreation.
        // Compose remember state, including currentProfileId and host/port, does not
        // survive closing the Activity from recents. Restore it from the live socket.
        val activeEndpoint = backend.activeServerEndpoint()
        if (activeEndpoint != null) {
            val (activeHost, activePort) = activeEndpoint
            host = activeHost
            port = activePort.toString()
            currentProfileId = serverProfiles.firstOrNull { profile ->
                profile.host.equals(activeHost, ignoreCase = true) &&
                    profile.port == activePort
            }?.id
            connected = true
            status = backend.status
            channels = backend.channels()
            clients = backend.clients()
            Log.i("TS3X_SERVER_ICON", "Restored session host=$activeHost port=$activePort profile=$currentProfileId")

            withContext(Dispatchers.IO) {
                cacheCurrentServerIcon(context, backend, activeHost, activePort)
            }
            serverIconGeneration++

            FullFunctionRuntime.active = true
        } else {
            FullFunctionRuntime.active = false
        }
        monitoringIntervalMinutes = backend.monitoringIntervalMinutes()
        syncLowMonitoring()
    }

    val incomingDelivery = incomingIntentState.value

    // Each ACTION_VIEW delivery is an event, even if its URI is identical to the previous one.
    // The old action/dataString keys prevented LaunchedEffect from running on repeat opens.
    LaunchedEffect(incomingDelivery?.sequence) {
        val delivery = incomingDelivery ?: return@LaunchedEffect
        val data = delivery.intent.data ?: return@LaunchedEffect
        Log.i("TS3X_INVITE", "Handling invite delivery=${delivery.sequence} scheme=${data.scheme} host=${data.host}")

        val invite = resolveTeamSpeakInvite(data)
        if (invite == null) {
            status = "Unable to resolve invite link. Check whether the TeamSpeak invitation is valid and has not expired."
            Log.w("TS3X_INVITE", "Invite resolution failed delivery=${delivery.sequence}")
            return@LaunchedEffect
        }
        val imported = importSharedServerInvite(profileStore, invite)
        if (imported == null) {
            status = "Could not import this invite link."
            Log.w("TS3X_INVITE", "Invite import failed delivery=${delivery.sequence}")
            return@LaunchedEffect
        }
        currentProfileId = imported.id
        host = imported.host
        port = imported.port.toString()
        refreshProfiles()
        status = "Server imported: ${imported.displayName}"
        Log.i("TS3X_INVITE", "Invite imported delivery=${delivery.sequence} host=${imported.host} port=${imported.port}")
    }

    fun toggleChannelCollapsed(channelId: Long) {
        val updated = if (channelId in collapsedChannelIds) {
            collapsedChannelIds - channelId
        } else {
            collapsedChannelIds + channelId
        }

        collapsedChannelIds = updated
        currentProfileId?.let { backend.setCollapsedChannels(it, updated) }
    }

    fun toggleMicrophoneMute() {
        scope.launch {
            if (!backend.isInputMuted()) {
                pttPressed = false
                backend.stopPushToTalk()
            }

            val result = withContext(Dispatchers.IO) {
                backend.toggleInputMuted()
            }

            status = if (result.isSuccess) {
                if (result.getOrThrow()) "Microphone muted" else "Connected"
            } else {
                "Could not change microphone state"
            }
        }
    }

    fun beginPtt() {
        if (backend.audioInputMode() != AudioInputMode.PTT) return
        if (backend.isInputMuted()) {
            status = "Microphone is muted"
            return
        }

        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!granted) {
            microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        val result = backend.startPushToTalk()
        pttPressed = result.isSuccess

        if (result.isFailure) status = "Could not start microphone"
    }

    fun endPtt() {
        backend.stopPushToTalk()
        pttPressed = false

    }


    fun buildServerInfoContent(
        info: BackendServerInfo
    ): FullscreenInfoContent {
        val profile =
            serverProfiles.firstOrNull {
                it.id == currentProfileId
            }

        val publicName =
            info.name.ifBlank {
                profile?.serverName
                    ?.takeIf { it.isNotBlank() }
                    ?: profile?.displayName
                    ?: host
            }

        return FullscreenInfoContent(
            title = publicName,
            subtitle = "$host:$port",
            body = "",
            imageUrl = null,
            serverInfo = ServerInfoScreenData(
                publicName = publicName,
                savedLabel =
                    profile?.name
                        ?.takeIf {
                            it.isNotBlank() &&
                                it != publicName
                        }
                        .orEmpty(),
                address = "$host:$port",
                resolvedIp =
                    displayServerIp(
                        serverReportedIp =
                            info.serverIp,
                        connectedHost =
                            host
                    ),
                platform = info.platform,
                version = info.version,
                status =
                    info.status.ifBlank {
                        if (connected) {
                            "online"
                        } else {
                            ""
                        }
                    },
                createdEpochSeconds =
                    info.createdEpochSeconds,
                uptimeSeconds =
                    info.uptimeSeconds,
                clientsOnline =
                    info.clientsOnline
                        ?: clients.size,
                maxClients =
                    info.maxClients,
                channelsOnline =
                    info.channelsOnline
                        ?: channels.size,
                reservedSlots =
                    info.reservedSlots,
                passwordProtected =
                    info.passwordProtected,
                serverUid =
                    info.uniqueIdentifier,
                iconId =
                    info.iconId,
                welcomeMessage =
                    info.welcomeMessage,
                hostMessage =
                    info.hostMessage,
                bannerImageUrl =
                    chooseServerBannerImageUrl(
                        gfxUrl =
                            info.bannerImageUrl,
                        hostBannerUrl =
                            info.bannerLinkUrl
                    ),
                bannerRawGfxUrl =
                    info.bannerImageUrl,
                bannerLinkUrl =
                    info.bannerLinkUrl
                        .takeIf {
                            it.isNotBlank()
                        }
                        .orEmpty(),
                hostButtonImageUrl =
                    normalizeServerImageUrl(
                        info.hostButtonImageUrl
                    ),
                hostButtonLinkUrl =
                    info.hostButtonLinkUrl,
                hostButtonTooltip =
                    info.hostButtonTooltip,
                maxDownloadBytesPerSecond =
                    info.maxDownloadBytesPerSecond,
                maxUploadBytesPerSecond =
                    info.maxUploadBytesPerSecond,
                totalBytesDownloaded =
                    info.totalBytesDownloaded,
                totalBytesUploaded =
                    info.totalBytesUploaded
            )
        )
    }

    fun openServerInfo() {
        // Open immediately with the currently cached snapshot so the long-press
        // feels instant.
        fullscreenInfo =
            buildServerInfoContent(
                backend.serverInfo()
            )

        // Then refresh serverinfo over the active TS3 connection and replace the
        // visible snapshot if the dialog is still open.
        scope.launch {
            val refreshed =
                withContext(
                    Dispatchers.IO
                ) {
                    backend.refreshServerInfo()
                        .getOrNull()
                }

            if (refreshed != null) {
                // servergetvariables normally causes a notifyserverupdated event.
                // Rebuild once quickly and once again after a longer listener
                // window so request-only fields such as uptime are visible.
                delay(120L)

                currentProfileId?.let {
                    profileId ->
                    refreshed.name
                        .takeIf {
                            it.isNotBlank()
                        }
                        ?.let {
                            serverName ->
                            profileStore
                                .updateServerName(
                                    profileId,
                                    serverName
                                )
                            refreshProfiles()
                        }
                }

                if (
                    fullscreenInfo
                        ?.serverInfo != null
                ) {
                    fullscreenInfo =
                        buildServerInfoContent(
                            backend.serverInfo()
                        )
                }

                delay(500L)

                if (
                    fullscreenInfo
                        ?.serverInfo != null
                ) {
                    fullscreenInfo =
                        buildServerInfoContent(
                            backend.serverInfo()
                        )
                }
            }
        }
    }


    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = GreenAccent,
            onPrimary = DarkGreen,
            background = DarkGreen,
            onBackground = TextPrimary,
            surface = GreenSurface,
            onSurface = TextPrimary,
            surfaceVariant = GreenSurface2,
            onSurfaceVariant = TextSecondary,
            secondary = Color(0xFF84D6AC)
        )
    ) {
        Scaffold(
            containerColor = DarkGreen,
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = GreenSurface,
                        titleContentColor = TextPrimary
                    ),
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Image(
                                painter = painterResource(
                                    id = R.drawable.ts3x_app_icon
                                ),
                                contentDescription = "TS3X Client",
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop
                            )
                            Spacer(Modifier.width(9.dp))
                            Text(
                                "TS3X Client",
                                fontWeight = FontWeight.Bold
                            )
                        }
                    },
                    actions = {
                        if (!connected) {
                            IconButton(
                                onClick = {
                                    settingsGeneration++
                                    showSettings = true
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Settings,
                                    contentDescription = "Settings",
                                    tint = TextPrimary
                                )
                            }
                        }
                    }
                )
            },
            bottomBar = {
                if (connected) {
                    LiveAudioDock(
                        backend = backend,
                        mode = audioInputMode,
                        pttPressed = pttPressed,
                        threshold = audioThreshold,
                        nickname = nickname,
                        ownAvatarPath = backend.savedAvatarPath(),
                        onOpenSettings = {
                            settingsGeneration++
                            showSettings = true
                        },
                        onToggleMute = ::toggleMicrophoneMute,
                        onPttPress = ::beginPtt,
                        onPttRelease = ::endPtt
                    )
                }
            }
        ) { padding ->
            if (!connected) {
                Column(
                    Modifier
                        .padding(padding)
                        .fillMaxSize()
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top
                    ) {
                        Column(Modifier.weight(1f)) {
                            FittedControlLabel(
                                R.string.ui_053,
                                modifier = Modifier.fillMaxWidth(),
                                style = MaterialTheme.typography.headlineMedium.copy(
                                    fontWeight = FontWeight.Bold
                                ),
                                textAlign = TextAlign.Start,
                                minFontSize = 10.sp
                            )
                            Text(
                                stringResource(R.string.ui_client_nickname, backend.savedNickname()),
                                color = TextSecondary,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Column(
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    inviteLinkText = ""
                                    inviteLinkError = null
                                    inviteLinkDialogOpen = true
                                }
                            ) {
                                FittedControlLabel(R.string.ui_028)
                            }

                            Button(
                                onClick = {
                                    editingServer = null
                                    serverEditorOpen = true
                                }
                            ) {
                                FittedControlLabel(R.string.ui_001)
                            }
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = GreenSurface2
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            stringResource(R.string.ui_monitoring_explanation),
                            color = TextSecondary,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(10.dp)
                        )
                    }

                    Spacer(Modifier.height(10.dp))

                    if (serverProfiles.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    stringResource(R.string.ui_039),
                                    style = MaterialTheme.typography.titleLarge
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    stringResource(R.string.ui_002),
                                    color = TextSecondary
                                )
                            }
                        }
                    } else {
                        val serverListState = rememberLazyListState()
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            state = serverListState,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(
                                serverProfiles,
                                key = { it.id }
                            ) { profile ->
                                SavedServerRow(
                                    profile = profile,
                                    dragging = draggingServerId == profile.id,
                                    dragOffsetPx = if (draggingServerId == profile.id) draggedDistance else 0f,
                                    onDragStart = { draggingServerId = profile.id; draggedDistance = 0f },
                                    onDragEnd = { draggingServerId = null; draggedDistance = 0f },
                                    onDragMove = { delta ->
                                        if (draggingServerId == profile.id) {
                                            draggedDistance += delta
                                            val visibleItems = serverListState.layoutInfo.visibleItemsInfo
                                            val current = visibleItems.firstOrNull { it.key == profile.id }
                                            if (current != null) {
                                                val draggedCenter = current.offset + current.size / 2f + draggedDistance
                                                val target = visibleItems.firstOrNull { item ->
                                                    item.key != profile.id &&
                                                        draggedCenter >= item.offset &&
                                                        draggedCenter < item.offset + item.size
                                                }
                                                val targetId = target?.key as? String
                                                if (targetId != null) {
                                                    val shift = target.offset - current.offset
                                                    moveServerProfile(profile.id, targetId)
                                                    draggedDistance -= shift
                                                }
                                            }
                                        }
                                    },
                                    iconPath =
                                        remember(profile.host, profile.port, serverIconGeneration) {
                                            serverIconCachePathOrNull(
                                                context,
                                                profile.host,
                                                profile.port
                                            )
                                        },
                                    monitoringIntervalMinutes =
                                        monitoringIntervalMinutes,
                                    connecting =
                                        (
                                            connecting &&
                                            currentProfileId ==
                                                profile.id
                                        ) ||
                                        metadataProbeProfileId ==
                                            profile.id,
                                    onConnect = {
                                        connectSavedServer(profile)
                                    },
                                    onEdit = {
                                        editingServer = profile
                                        serverEditorOpen = true
                                    },
                                    onDelete = {
                                        deleteServer = profile
                                    }
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(6.dp))

                    if (status != "Ready") {
                        Text(
                            status,
                            color = if (
                                status.contains("error", ignoreCase = true) ||
                                status.contains("failed", ignoreCase = true)
                            ) MaterialTheme.colorScheme.error else TextSecondary,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                }
            } else {
                Column(
                    Modifier
                        .padding(padding)
                        .fillMaxSize()
                        .background(DarkGreen)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (serverBarPressed) {
                                    GreenAccentSoft
                                } else {
                                    GreenSurface
                                }
                            )
                            .pointerInput(
                                host,
                                port,
                                channels.size,
                                clients.size,
                                currentProfileId
                            ) {
                                awaitEachGesture {
                                    // Observe at the Initial pass. This lets the
                                    // parent bar see the gesture even over child
                                    // controls such as Disconnect.
                                    awaitFirstDown(
                                        requireUnconsumed = false,
                                        pass = PointerEventPass.Initial
                                    )

                                    serverBarPressed = true

                                    val longPressJob =
                                        scope.launch {
                                            delay(3_000L)

                                            lastServerInfoOpenedAt =
                                                SystemClock.uptimeMillis()

                                            openServerInfo()
                                        }

                                    try {
                                        var stillPressed = true

                                        while (stillPressed) {
                                            val event =
                                                awaitPointerEvent(
                                                    PointerEventPass.Initial
                                                )

                                            stillPressed =
                                                event.changes.any {
                                                    it.pressed
                                                }
                                        }
                                    } finally {
                                        longPressJob.cancel()
                                        serverBarPressed = false
                                    }
                                }
                            }
                    ) {
                        val activeProfile =
                            serverProfiles
                                .firstOrNull {
                                    it.id == currentProfileId
                                }

                        val activeIconPath =
                            remember(activeProfile?.host, activeProfile?.port, serverIconGeneration) {
                                activeProfile
                                    ?.let {
                                        serverIconCachePathOrNull(
                                            context,
                                            it.host,
                                            it.port
                                        )
                                    }
                            }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    horizontal = 12.dp,
                                    vertical = 8.dp
                                ),
                            verticalAlignment =
                                Alignment.CenterVertically
                        ) {
                            ServerIconBadge(
                                label =
                                    activeProfile?.displayName
                                        ?: backend.serverName()
                                        ?: "$host:$port",
                                iconPath = activeIconPath,
                                size = 42.dp
                            )

                            Spacer(Modifier.width(10.dp))

                            Column(Modifier.weight(1f)) {
                                Text(
                                    activeProfile?.displayName
                                        ?: backend.serverName()
                                        ?: "$host:$port",
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    "$host:$port",
                                    color = TextSecondary,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }

                            activeProfile?.let {
                                profile ->
                                IconButton(
                                    onClick = {
                                        shareServerProfile(
                                            context,
                                            profile
                                        )
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Share,
                                        contentDescription = "Share server"
                                    )
                                }
                            }

                            IconButton(
                                enabled = !disconnecting,
                                onClick = {
                                    val releaseAfterServerInfo =
                                        SystemClock.uptimeMillis() -
                                            lastServerInfoOpenedAt <
                                            1_000L

                                    if (releaseAfterServerInfo) {
                                        lastServerInfoOpenedAt = 0L
                                        return@IconButton
                                    }

                                    disconnecting = true
                                    pttPressed = false
                                    backend.stopPushToTalk()
                                    status = "Disconnecting…"

                                    scope.launch {
                                        val result =
                                            withContext(
                                                Dispatchers.IO
                                            ) {
                                                backend.disconnect()
                                            }

                                        disconnecting = false
                                        connected = false
                                        channels = emptyList()
                                        clients = emptyList()
                                        selectedChannelId = null
                                        collapsedChannelIds =
                                            emptySet()
                                        status =
                                            if (result.isSuccess) {
                                                "Disconnected"
                                            } else {
                                                "Disconnect failed"
                                            }
                                        currentProfileId = null
                                        // Prevent a pending WorkManager probe from appearing
                                        // as a second client immediately after Disconnect.
                                        FullFunctionRuntime.pauseMonitoringAfterDisconnect()
                                        FullFunctionRuntime.active =
                                            false

                                        // Never reconnect immediately after manual disconnect.
                                        // Keep only the configured periodic monitor schedule.
                                        syncLowMonitoring(runNow = false)
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                                    contentDescription =
                                        if (disconnecting) {
                                            "Leaving"
                                        } else {
                                            "Disconnect"
                                        }
                                )
                            }
                        }
                    }



                    val selected = channels.firstOrNull {
                        it.id == selectedChannelId
                    }

                    if (selected != null) {
                        ChannelDetailsCard(
                            channel = selected,
                            unreadCount = chatRevision.let { backend.unreadChatMessages(backend.channelChatKey(selected.id)) },
                            onOpenChat = { openChannelChat(selected) }
                        )
                    }

                    val rows = remember(channels, collapsedChannelIds) {
                        flattenChannels(channels, collapsedChannelIds)
                    }
                    val membersByChannel = remember(clients) { clients.groupBy { it.channelId } }
                    val channelListState = rememberLazyListState()
                    LaunchedEffect(channelListState) {
                        snapshotFlow {
                            channelListState.layoutInfo.visibleItemsInfo.mapNotNull { item ->
                                (item.key as? String)?.removePrefix("client:")?.toIntOrNull()
                            }.toSet()
                        }.collect { visibleAvatarClients.value = it }
                    }
                    LazyColumn(Modifier.fillMaxSize(), state = channelListState) {
                        rows.forEach { (channel, depth) ->
                            val members = membersByChannel[channel.id].orEmpty()
                            item(key = "channel:${channel.id}", contentType = "channel") {
                                val spacer = remember(channel.name) { parseSpacer(channel) }

                                if (spacer != null) {
                                    SpacerRow(
                                        channel = channel,
                                        spacer = spacer,
                                        depth = depth,
                                        onDoubleClick = { selectAndJoin(channel) },
                                        onOpen = {


                                            scope.launch {
                                                status =
                                                    "Reading spacer information…"

                                                val detailsResult =
                                                    withContext(
                                                        Dispatchers.IO
                                                    ) {
                                                        backend
                                                            .channelDetails(
                                                                channel.id
                                                            )
                                                    }

                                                val detailed =
                                                    detailsResult
                                                        .getOrNull()



                                                refreshSnapshots()

                                                if (
                                                    detailed == null
                                                ) {
                                                    fullscreenInfo =
                                                        FullscreenInfoContent(
                                                            title =
                                                                spacer.content
                                                                    .ifBlank {
                                                                        "Spacer information"
                                                                    },
                                                            subtitle =
                                                                channel.name,
                                                            body =
                                                                "[b]Could not load channel description[/b]\n\n" +
                                                                    (
                                                                        detailsResult
                                                                            .exceptionOrNull()
                                                                            ?.message
                                                                            ?: "Unknown TeamSpeak channelinfo error"
                                                                    ),
                                                            richBody =
                                                                true
                                                        )

                                                    status =
                                                        backend.status
                                                    return@launch
                                                }

                                                val topic =
                                                    detailed.topic

                                                val description =
                                                    detailed.description

                                                // [cspacer] channels are commonly used as
                                                // clickable information headers. Their
                                                // channel_description is the actual rich
                                                // information field, so render it first and
                                                // preserve TeamSpeak BBCode/newlines exactly.
                                                val body =
                                                    buildString {
                                                        if (
                                                            description
                                                                .isNotBlank()
                                                        ) {
                                                            append(
                                                                description
                                                            )
                                                        } else if (
                                                            topic.isNotBlank()
                                                        ) {
                                                            append(
                                                                topic
                                                            )
                                                        } else {
                                                            append(
                                                                "[i]This spacer has no channel description.[/i]"
                                                            )
                                                        }
                                                    }

                                                fullscreenInfo =
                                                    FullscreenInfoContent(
                                                        title =
                                                            spacer.content
                                                                .ifBlank {
                                                                    "Spacer information"
                                                                },
                                                        subtitle =
                                                            detailed.name,
                                                        body =
                                                            body,
                                                        imageUrl =
                                                            channelImageUrl(
                                                                detailed
                                                            ),
                                                        richBody =
                                                            true
                                                    )

                                                status =
                                                    "Connected"
                                            }
                                        }
                                    )
                                } else {
                                    ChannelRow(
                                        channel = channel,
                                        depth = depth,
                                        memberCount = members.size,
                                        isSelected = selectedChannelId == channel.id,
                                        isCurrent = backend.currentChannelId == channel.id,
                                        hasChildren = channel.id in parentChannelIds,
                                        isCollapsed = channel.id in collapsedChannelIds,
                                        onToggleCollapse = { toggleChannelCollapsed(channel.id) },
                                        onSelect = { selectChannelForPreview(channel) },
                                        onDoubleClick = { selectAndJoin(channel) }
                                    )
                                }
                            }
                            // Every occupant is an independently recycled item, including spacers.
                            items(members, key = { "client:${it.id}" }, contentType = { "client" }) { client ->
                                ChannelMemberRow(
                                    client = client,
                                    depth = depth,
                                    serverHost = host,
                                    serverPort = port.toIntOrNull() ?: 9987,
                                    avatarCacheRevision = avatarCacheRevision,
                                    groupIconIds = groupIconIds,
                                    roleIconRevision = roleIconRevision,
                                    backend = backend,
                                    activeVoiceState = activeVoiceState,
                                    onOpenChat = { openPrivateChat(client) },
                                    unreadCount = chatRevision.let {
                                        backend.unreadChatMessages(backend.privateChatKey(client))
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        openChatConversation?.let { chat ->
            Ts3ConversationDialog(
                chat = chat,
                backend = backend,
                revision = chatRevision,
                onClose = {
                    backend.setOpenChat(null)
                    openChatConversation = null
                    chatRevision++
                }
            )
        }

        connectionFailureDialog?.let { (dialogTitle, dialogMessage) ->
            AlertDialog(
                onDismissRequest = { connectionFailureDialog = null },
                title = { Text(dialogTitle) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text(dialogMessage)
                    }
                },
                confirmButton = {
                    TextButton(onClick = { connectionFailureDialog = null }) {
                        FittedControlLabel(R.string.ui_042)
                    }
                }
            )
        }

        fullscreenInfo?.let { FullscreenInfoDialog(it) { fullscreenInfo = null } }

        if (inviteLinkDialogOpen) {
            AlertDialog(
                onDismissRequest = {
                    inviteLinkDialogOpen = false
                    inviteLinkError = null
                },
                title = {
                    Text(stringResource(R.string.ui_004))
                },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            stringResource(R.string.ui_044),
                            color = TextSecondary,
                            style = MaterialTheme.typography.bodySmall
                        )

                        OutlinedTextField(
                            value = inviteLinkText,
                            onValueChange = {
                                inviteLinkText = it
                                inviteLinkError = null
                            },
                            label = {
                                Text("ts3x://server/add?…")
                            },
                            singleLine = false,
                            minLines = 2,
                            maxLines = 4,
                            isError = inviteLinkError != null,
                            supportingText = {
                                inviteLinkError?.let {
                                    Text(it)
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val normalized = inviteLinkText.trim()
                            val uri =
                                runCatching {
                                    Uri.parse(normalized)
                                }.getOrNull()

                            scope.launch {
                                val parsed = uri?.let { resolveTeamSpeakInvite(it) }
                                if (parsed == null) {
                                    inviteLinkError = "Unable to resolve invite. Check the link and ensure the TeamSpeak invitation has not expired."
                                } else {
                                    val imported = importSharedServerInvite(profileStore, parsed)
                                    if (imported == null) {
                                        inviteLinkError = "Could not import this invite link."
                                    } else {
                                        inviteLinkDialogOpen = false
                                        inviteLinkText = ""
                                        inviteLinkError = null
                                        currentProfileId = imported.id
                                        host = imported.host
                                        port = imported.port.toString()
                                        refreshProfiles()
                                        status = "Server imported: ${imported.displayName}"
                                        if (imported.name.isBlank() && imported.serverName.isBlank()) {
                                            probeServerName(imported)
                                        }
                                    }
                                }
                            }
                        }
                    ) {
                        FittedControlLabel(R.string.ui_003)
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            inviteLinkDialogOpen = false
                            inviteLinkError = null
                        }
                    ) {
                        FittedControlLabel(R.string.ui_009)
                    }
                }
            )
        }

        if (serverEditorOpen) {
            ServerProfileDialog(
                initial = editingServer,
                onDismiss = {
                    serverEditorOpen = false
                    editingServer = null
                },
                onSave = { profile ->
                    // Use persisted state: duplicate saves and edits of an enabled profile
                    // are not another user activation of monitoring.
                    val monitoringWasEnabled = profileStore.get(profile.id)?.monitorEnabled == true
                    val powerStatus = monitoringBackgroundStatus(context)
                    val offerBackgroundSetup = com.morsen.ts3xclient.protocol.TeamSpeakMonitoringPrompt.shouldOffer(
                        monitoringWasEnabled, profile.monitorEnabled,
                        powerStatus.batteryExempt, powerStatus.backgroundRestricted
                    )
                    profileStore.upsert(profile)
                    serverEditorOpen = false
                    editingServer = null
                    refreshProfiles()

                    if (profile.monitorEnabled) {
                        ensureNotificationPermission()
                    }
                    if (offerBackgroundSetup) monitoringBackgroundPrompt = true

                    syncLowMonitoring()

                    if (
                        profile.name.isBlank() &&
                        profile.serverName.isBlank()
                    ) {
                        probeServerName(profile)
                    } else {
                        status =
                            "Server saved: " +
                                profile.displayName
                    }
                }
            )
        }

        if (monitoringBackgroundPrompt) {
            MonitoringBackgroundDialog(onDismiss = { monitoringBackgroundPrompt = false })
        }

        deleteServer?.let { profile ->
            AlertDialog(
                onDismissRequest = { deleteServer = null },
                title = { Text(stringResource(R.string.ui_019)) },
                text = {
                    Text(
                        "${profile.displayName}\n${profile.host}:${profile.port}"
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            profileStore.delete(profile.id)
                            deleteServer = null
                            refreshProfiles()
                            syncLowMonitoring()
                            status = "Server profile deleted"
                        }
                    ) {
                        FittedControlLabel(R.string.ui_018)
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { deleteServer = null }
                    ) {
                        FittedControlLabel(R.string.ui_009)
                    }
                }
            )
        }

        passwordChannel?.let { channel ->
            AlertDialog(
                onDismissRequest = {
                    passwordChannel = null
                    channelPassword = ""
                },
                title = { Text(stringResource(R.string.ui_010)) },
                text = {
                    OutlinedTextField(
                        value = channelPassword,
                        onValueChange = { channelPassword = it },
                        label = { Text(channel.name) },
                        singleLine = true
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        val pw = channelPassword
                        passwordChannel = null
                        channelPassword = ""
                        performJoin(channel, pw)
                    }) {
                        FittedControlLabel(R.string.ui_029)
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        passwordChannel = null
                        channelPassword = ""
                    }) {
                        FittedControlLabel(R.string.ui_009)
                    }
                }
            )
        }

        joinWarning?.let {
            warning ->

            AlertDialog(
                onDismissRequest = {
                    joinWarning = null
                    joinCooldownRemaining = 0
                },
                title = {
                    Text(
                        warning.title
                    )
                },
                text = {
                    Column(
                        verticalArrangement =
                            Arrangement.spacedBy(
                                10.dp
                            )
                    ) {
                        Text(
                            warning.message
                        )

                        if (
                            warning.cooldownSeconds > 0
                        ) {
                            if (
                                joinCooldownRemaining > 0
                            ) {
                                Text(
                                    "Cooldown: ${joinCooldownRemaining}s",
                                    color =
                                        TextSecondary,
                                    style =
                                        MaterialTheme
                                            .typography
                                            .bodyMedium
                                )

                                LinearProgressIndicator(
                                    progress = {
                                        (
                                            warning.cooldownSeconds -
                                                joinCooldownRemaining
                                        ).toFloat() /
                                            warning.cooldownSeconds
                                                .toFloat()
                                    },
                                    modifier =
                                        Modifier.fillMaxWidth()
                                )
                            } else {
                                Text(
                                    stringResource(R.string.ui_014),
                                    color =
                                        TextSecondary,
                                    style =
                                        MaterialTheme
                                            .typography
                                            .bodyMedium
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    if (
                        warning.canRetry
                    ) {
                        TextButton(
                            enabled =
                                joinCooldownRemaining <= 0,
                            onClick = {
                                val retryChannel =
                                    warning.channel
                                val retryPassword =
                                    warning.password

                                joinWarning = null
                                joinCooldownRemaining = 0

                                performJoin(
                                    retryChannel,
                                    retryPassword
                                )
                            }
                        ) {
                            Text(
                                if (
                                    joinCooldownRemaining > 0
                                ) {
                                    "Try again (${joinCooldownRemaining}s)"
                                } else {
                                    "Try again"
                                }
                            )
                        }
                    } else {
                        TextButton(
                            onClick = {
                                joinWarning = null
                                joinCooldownRemaining = 0
                            }
                        ) {
                            FittedControlLabel(R.string.ui_042)
                        }
                    }
                },
                dismissButton = {
                    if (
                        warning.canRetry
                    ) {
                        TextButton(
                            onClick = {
                                joinWarning = null
                                joinCooldownRemaining = 0
                            }
                        ) {
                            FittedControlLabel(R.string.ui_009)
                        }
                    }
                }
            )
        }

        if (showSettings) {
            key(settingsGeneration) {
                SettingsDialog(
                    backend = backend,
                    connected = connected,
                    onDismiss = {
                        showSettings = false
                        syncLowMonitoring()
                    },
                    onSaved = { message ->
                        status = message
                        nickname =
                            backend.savedNickname()
                        audioInputMode =
                            backend.audioInputMode()
                        audioThreshold =
                            backend.voiceActivationThreshold()

                        monitoringIntervalMinutes =
                            backend.monitoringIntervalMinutes()

                        if (
                            connected && audioInputMode == AudioInputMode.ALWAYS_ON &&
                            ContextCompat.checkSelfPermission(
                                context, Manifest.permission.RECORD_AUDIO
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }

                        showSettings = false
                        syncLowMonitoring()
                    }
                )
            }
        }


    }
}




@Composable
private fun ServerIconBadge(
    label: String,
    iconPath: String?,
    size: Dp = 48.dp
) {
    val fallback =
        remember(label) {
            label
                .split(" ")
                .mapNotNull {
                    it.firstOrNull()?.uppercaseChar()
                }
                .take(2)
                .joinToString("")
                .ifBlank {
                    "TS"
                }
        }

    Surface(
        modifier = Modifier.size(size),
        shape = RoundedCornerShape((size.value / 3f).dp),
        color = GreenSurface2,
        tonalElevation = 0.dp
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            if (!iconPath.isNullOrBlank()) {
                AsyncImage(
                    model = File(iconPath),
                    contentDescription = "$label icon",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.Dns,
                    contentDescription = null,
                    tint = GreenAccent,
                    modifier = Modifier.size(size * 0.5f)
                )
            }
        }
    }
}

@Composable
private fun SavedServerRow(

    profile: SavedServerProfile,
    dragging: Boolean,
    dragOffsetPx: Float,
    onDragStart: () -> Unit,
    onDragMove: (Float) -> Unit,
    onDragEnd: () -> Unit,
    iconPath: String?,
    monitoringIntervalMinutes: Int,
    connecting: Boolean,
    onConnect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = GreenSurface
        ),
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(if (dragging) 1f else 0f)
            .graphicsLayer {
                translationY = dragOffsetPx
                scaleX = if (dragging) 1.025f else 1f
                scaleY = if (dragging) 1.025f else 1f
                shadowElevation = if (dragging) 12.dp.toPx() else 0f
            }
            .pointerInput(profile.id) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var lastY = down.position.y
                    var moved = false
                    val held = withTimeoutOrNull(3_000L) {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null || !change.pressed || change.isConsumed) break
                            if (kotlin.math.abs(change.position.y - lastY) > viewConfiguration.touchSlop) {
                                moved = true
                                break
                            }
                        }
                        false
                    }
                    if (held == null && !moved) {
                        onDragStart()
                        try {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val change = event.changes.firstOrNull { it.id == down.id }
                                if (change == null || !change.pressed) break
                                val delta = change.position.y - lastY
                                lastY = change.position.y
                                if (delta != 0f) onDragMove(delta)
                                change.consume()
                            }
                        } finally {
                            onDragEnd()
                        }
                    }
                }
            }
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                ServerIconBadge(
                    label = profile.displayName,
                    iconPath = iconPath,
                    size = 46.dp
                )

                Spacer(Modifier.width(10.dp))

                Column(Modifier.weight(1f)) {
                    Text(
                        profile.displayName,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        "${profile.host}:${profile.port}",
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Text(
                    if (profile.monitorEnabled) "● MON" else "○ MON",
                    color = if (profile.monitorEnabled) {
                        GreenAccent
                    } else {
                        TextSecondary
                    },
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelMedium
                )
            }

            Spacer(Modifier.height(5.dp))

            Text(
                if (!profile.monitorEnabled) {
                    stringResource(R.string.ui_monitoring_disabled)
                } else {
                    val period = when (monitoringIntervalMinutes) {
                        60 -> stringResource(R.string.ui_period_hour, 1)
                        120 -> stringResource(R.string.ui_period_hour, 2)
                        300 -> stringResource(R.string.ui_period_hour, 5)
                        720 -> stringResource(R.string.ui_period_hour, 12)
                        else -> stringResource(R.string.ui_period_min, monitoringIntervalMinutes)
                    }
                    val channels = if (profile.monitoredChannelIds.isEmpty())
                        stringResource(R.string.ui_all_channels)
                    else profile.monitoredChannelIds.sorted().joinToString(", ")
                    stringResource(R.string.ui_monitoring_schedule, period, channels)
                },
                color = TextSecondary,
                style = MaterialTheme.typography.labelSmall
            )

            Spacer(Modifier.height(9.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Button(
                    enabled = !connecting,
                    onClick = onConnect,
                    modifier = Modifier.weight(1f)
                ) {
                    if (connecting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(17.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    FittedControlLabel(if (connecting) R.string.ui_connecting else R.string.ui_connect)
                }

                OutlinedButton(onClick = onEdit) {
                    FittedControlLabel(R.string.ui_021)
                }

                TextButton(onClick = onDelete) {
                    FittedControlLabel(R.string.ui_018)
                }
            }
        }
    }
}

@Composable
private fun ServerProfileDialog(
    initial: SavedServerProfile?,
    onDismiss: () -> Unit,
    onSave: (SavedServerProfile) -> Unit
) {
    var serverNameOverride by remember {
        mutableStateOf(initial?.name.orEmpty())
    }

    var host by remember {
        mutableStateOf(initial?.host.orEmpty())
    }
    var port by remember {
        mutableStateOf((initial?.port ?: 9987).toString())
    }
    var password by remember {
        mutableStateOf(initial?.password.orEmpty())
    }
    var monitorEnabled by remember {
        mutableStateOf(initial?.monitorEnabled ?: false)
    }
    var monitorChannels by remember {
        mutableStateOf(
            initial?.monitoredChannelIds
                ?.sorted()
                ?.joinToString(",")
                .orEmpty()
        )
    }
    var validation by remember { mutableStateOf<String?>(null) }
    val embeddedAddress = runCatching {
        com.morsen.ts3xclient.protocol.TeamSpeakServerAddress.parse(host, port)
    }.getOrNull()?.takeIf { it.hasExplicitPort() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (initial == null) {
                    "Add TeamSpeak server"
                } else {
                    "Edit TeamSpeak server"
                }
            )
        },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 560.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    OutlinedTextField(
                        value = serverNameOverride,
                        onValueChange = { serverNameOverride = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.ui_055)) },
                        supportingText = {
                            Text(
                                "Manual value overrides Public name. Leave blank to " +
                                    "use the Public name reported by Server Info; host/IP " +
                                    "is used only if the server reports no name."
                            )
                        },
                        singleLine = true
                    )
                }

                item {
                    OutlinedTextField(
                        value = host,
                        onValueChange = { value ->
                            host = value
                            runCatching {
                                com.morsen.ts3xclient.protocol.TeamSpeakServerAddress.parse(value, port)
                            }.getOrNull()?.takeIf { it.hasExplicitPort() }?.let {
                                port = it.port.toString()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.ui_024)) },
                        supportingText = { Text(stringResource(R.string.ui_host_port_hint)) },
                        singleLine = true
                    )
                }

                item {
                    Text(
                        "Client nickname and identity are global and are configured " +
                            "through ⚙ Settings.",
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                item {
                    OutlinedTextField(
                        value = embeddedAddress?.port?.toString() ?: port,
                        onValueChange = {
                            port = it.filter(Char::isDigit).take(5)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.ui_062)) },
                        enabled = embeddedAddress == null,
                        supportingText = {
                            if (embeddedAddress != null) Text(stringResource(R.string.ui_port_from_host))
                        },
                        singleLine = true
                    )
                }

                item {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.ui_059)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation()
                    )
                }

                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                monitorEnabled = !monitorEnabled
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = monitorEnabled,
                            onCheckedChange = {
                                monitorEnabled = it
                            }
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(stringResource(R.string.ui_022))
                            Text(
                                stringResource(R.string.ui_008),
                                color = TextSecondary,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }

                if (monitorEnabled) {
                    item {
                        OutlinedTextField(
                            value = monitorChannels,
                            onValueChange = {
                                monitorChannels = it.filter { ch ->
                                    ch.isDigit() ||
                                        ch == ',' ||
                                        ch == ' ' ||
                                        ch == ';'
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.ui_033)) },
                            supportingText = {
                                Text(
                                    stringResource(R.string.ui_007)
                                )
                            },
                            minLines = 2,
                            maxLines = 3
                        )
                    }

                    item {
                        Text(
                            "The monitoring interval is configured globally in Settings. " +
                                "The client is visible on the server only for a few seconds " +
                                "during each check. Users who join and leave entirely between " +
                                "checks cannot be detected.",
                            color = MaterialTheme.colorScheme.tertiary,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                validation?.let {
                    item {
                        Text(
                            it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val addressResult = runCatching {
                        com.morsen.ts3xclient.protocol.TeamSpeakServerAddress.parse(host, port)
                    }
                    val address = addressResult.getOrNull()

                    val ids = monitorChannels
                        .split(',', ';', ' ')
                        .mapNotNull {
                            it.trim()
                                .takeIf { value -> value.isNotBlank() }
                                ?.toLongOrNull()
                        }
                        .filter { it > 0 }
                        .toSet()

                    when {
                        address == null ->
                            validation = addressResult.exceptionOrNull()?.message ?: "Invalid server address"

                        else -> onSave(
                            SavedServerProfile(
                                id = initial?.id
                                    ?: java.util.UUID.randomUUID().toString(),

                                name = serverNameOverride.trim(),

                                serverName = if (
                                    initial != null &&
                                    initial.host == address.host &&
                                    initial.port == address.port
                                ) {
                                    initial.serverName
                                } else {
                                    ""
                                },

                                host = address.host,
                                port = address.port,
                                password = password,
                                monitorEnabled = monitorEnabled,
                                monitoredChannelIds = ids
                            )
                        )
                    }
                }
            ) {
                FittedControlLabel(R.string.ui_052)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                FittedControlLabel(R.string.ui_009)
            }
        }
    )
}

@Composable
private fun SettingsDialog(
    backend: Ts3jBackend,
    connected: Boolean,
    onDismiss: () -> Unit,
    onSaved: (String) -> Unit
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var settingsSaving by remember { mutableStateOf(false) }
    var crashDumpGeneration by remember { mutableIntStateOf(0) }

    var nicknameValue by remember {
        mutableStateOf(backend.savedNickname())
    }

    var identityInfo by remember {
        mutableStateOf(backend.identityInfo())
    }
    var identityImport by remember { mutableStateOf("") }
    var identitySecurityLevel by remember {
        mutableStateOf(
            (identityInfo.securityLevel ?: 10).toString()
        )
    }
    var identityMessage by remember { mutableStateOf<String?>(null) }
    var confirmGenerate by remember { mutableStateOf(false) }
    var identityBusy by remember { mutableStateOf(false) }

    var avatarPath by remember {
        mutableStateOf(
            backend.savedAvatarPath()
        )
    }
    var avatarMessage by remember {
        mutableStateOf<String?>(null)
    }
    var avatarBusy by remember {
        mutableStateOf(false)
    }

    var pendingAvatarUri by remember {
        mutableStateOf<Uri?>(null)
    }
    var avatarCropZoom by remember { mutableFloatStateOf(1f) }
    // Translation is measured in viewport pixels; the backend receives normalized crop offsets.
    var avatarPanX by remember { mutableFloatStateOf(0f) }
    var avatarPanY by remember { mutableFloatStateOf(0f) }
    var avatarViewport by remember { mutableStateOf(IntSize.Zero) }
    val avatarContext = LocalContext.current
    val avatarDimensions = remember(pendingAvatarUri) {
        pendingAvatarUri?.let { selected ->
            runCatching {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                avatarContext.contentResolver.openInputStream(selected)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, options)
                }
                if (options.outWidth > 0 && options.outHeight > 0)
                    options.outWidth.toFloat() to options.outHeight.toFloat()
                else null
            }.getOrNull()
        }
    }
    fun avatarPanLimits(zoom: Float): Pair<Float, Float> {
        val dimensions = avatarDimensions ?: return 0f to 0f
        val viewport = avatarViewport.width.toFloat()
        if (viewport <= 0f) return 0f to 0f
        val (imageWidth, imageHeight) = dimensions
        val shortSide = min(imageWidth, imageHeight)
        return (((imageWidth / shortSide) * zoom - 1f) * viewport / 2f).coerceAtLeast(0f) to
            (((imageHeight / shortSide) * zoom - 1f) * viewport / 2f).coerceAtLeast(0f)
    }

    val avatarPicker =
        rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) {
            uri ->
            if (uri != null) {
                pendingAvatarUri = uri
                avatarCropZoom = 1f
                avatarPanX = 0f
                avatarPanY = 0f
                avatarViewport = IntSize.Zero
                avatarMessage = null
            }
        }

    val inputDevices = remember { backend.inputDevices() }
    val outputDevices = remember { backend.outputDevices() }

    var inputId by remember { mutableStateOf(backend.preferredInputDeviceId()) }
    var outputId by remember { mutableStateOf(backend.preferredOutputDeviceId()) }
    var audioMode by remember {
        mutableStateOf(backend.audioInputMode())
    }
    var voiceThreshold by remember {
        mutableFloatStateOf(
            backend.voiceActivationThreshold()
        )
    }

    var monitoringIntervalMinutes by remember {
        mutableIntStateOf(
            backend.monitoringIntervalMinutes()
        )
    }

    val initialProxy = remember {
        backend.proxySettings()
    }
    var proxyMode by remember { mutableStateOf(initialProxy.mode) }
    var proxyHost by remember { mutableStateOf(initialProxy.host) }
    var proxyPort by remember {
        mutableStateOf(
            if (initialProxy.port > 0) initialProxy.port.toString() else ""
        )
    }
    var proxyUser by remember { mutableStateOf(initialProxy.username) }
    var proxyPassword by remember { mutableStateOf(initialProxy.password) }

    pendingAvatarUri?.let {
        avatarUri ->

        // Full-screen editor: the crop area may shrink, but the actions always
        // occupy a dedicated row above the system navigation inset.
        Dialog(
            onDismissRequest = {
                if (!avatarBusy) pendingAvatarUri = null
            },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false
            )
        ) {
            Surface(
                color = DarkGreen,
                modifier = Modifier.fillMaxSize()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(horizontal = 12.dp, vertical = 12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(bottom = 48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        TextButton(enabled = !avatarBusy, onClick = {
                            avatarCropZoom = 1f
                            avatarPanX = 0f
                            avatarPanY = 0f
                        }) { FittedControlLabel(R.string.ui_050) }

                        Spacer(modifier = Modifier.weight(1f))

                        TextButton(
                            enabled = !avatarBusy,
                            onClick = {
                                pendingAvatarUri = null
                            }
                        ) {
                            FittedControlLabel(R.string.ui_009)
                        }

                        Button(
                            enabled = !avatarBusy,
                            onClick = {
                                avatarBusy = true

                                val finalZoom = avatarCropZoom
                                val (limitX, limitY) = avatarPanLimits(finalZoom)
                                val finalX = if (limitX > 0f) (-avatarPanX / limitX).coerceIn(-1f, 1f) else 0f
                                val finalY = if (limitY > 0f) (-avatarPanY / limitY).coerceIn(-1f, 1f) else 0f
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        backend.setAvatarFromUri(
                                            uri = avatarUri,
                                            zoom = finalZoom,
                                            offsetX = finalX,
                                            offsetY = finalY
                                        )
                                    }

                                    avatarBusy = false

                                    if (result.isSuccess) {
                                        avatarPath = backend.savedAvatarPath()
                                        pendingAvatarUri = null
                                        avatarMessage = if (connected) {
                                            "Avatar cropped, uploaded and applied."
                                        } else {
                                            "Avatar cropped and saved. It will be uploaded on the next connection."
                                        }
                                    } else {
                                        avatarMessage = "Avatar error: " + (
                                            result.exceptionOrNull()?.message ?: "unknown"
                                        )
                                    }
                                }
                            }
                        ) {
                            Text(if (avatarBusy) "Saving…" else "Use avatar")
                        }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = 108.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            stringResource(R.string.ui_017),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )

                        Text(
                            "Drag with one finger to position the photo. Pinch with two fingers to zoom. Output: 256×256.",
                            color = TextSecondary,
                            style = MaterialTheme.typography.bodyMedium
                        )

                        BoxWithConstraints(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            val viewportSide = if (maxWidth < maxHeight) maxWidth else maxHeight
                            Box(
                                modifier = Modifier
                                    .size(viewportSide)
                                    .onSizeChanged { size ->
                                        avatarViewport = size
                                        val (limitX, limitY) = avatarPanLimits(avatarCropZoom)
                                        avatarPanX = avatarPanX.coerceIn(-limitX, limitX)
                                        avatarPanY = avatarPanY.coerceIn(-limitY, limitY)
                                    }
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(GreenSurface2)
                                    .pointerInput(avatarDimensions, avatarViewport) {
                                        detectTransformGestures { centroid, pan, gestureZoom, _ ->
                                            val oldZoom = avatarCropZoom
                                            val newZoom = (oldZoom * gestureZoom).coerceIn(1f, 5f)
                                            val factor = newZoom / oldZoom
                                            val centerX = avatarViewport.width / 2f
                                            val centerY = avatarViewport.height / 2f
                                            val nextX = (avatarPanX - (centroid.x - centerX)) * factor +
                                                (centroid.x - centerX) + pan.x
                                            val nextY = (avatarPanY - (centroid.y - centerY)) * factor +
                                                (centroid.y - centerY) + pan.y
                                            avatarCropZoom = newZoom
                                            val (maxX, maxY) = avatarPanLimits(newZoom)
                                            avatarPanX = nextX.coerceIn(-maxX, maxX)
                                            avatarPanY = nextY.coerceIn(-maxY, maxY)
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                AsyncImage(
                                    model = avatarUri,
                                    contentDescription = "Avatar crop preview; drag to pan, pinch to zoom",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .graphicsLayer {
                                            scaleX = avatarCropZoom
                                            scaleY = avatarCropZoom
                                            translationX = avatarPanX
                                            translationY = avatarPanY
                                        }
                                )
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .border(
                                            1.dp,
                                            Color.White.copy(alpha = 0.55f),
                                            RoundedCornerShape(18.dp)
                                        )
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ui_080)) },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 560.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    val activity = context as? android.app.Activity
                    var languageMenu by remember { mutableStateOf(false) }
                    val selectedLanguage = remember { AppLanguage.languageChoice(context) }
                    Column {
                        Text(stringResource(R.string.ui_language), fontWeight = FontWeight.Bold)
                        Box {
                            OutlinedButton(onClick = { languageMenu = true }) {
                                Text(when (selectedLanguage) {
                                    "ru" -> "Русский"
                                    "de" -> "Deutsch"
                                    else -> "English"
                                })
                                Spacer(Modifier.width(8.dp))
                                Text("▾")
                            }
                            DropdownMenu(expanded = languageMenu, onDismissRequest = { languageMenu = false }) {
                                listOf(
                                    "en" to "English", "de" to "Deutsch", "ru" to "Русский"
                                ).forEach { (code, caption) ->
                                    DropdownMenuItem(
                                        text = { Text(caption) },
                                        onClick = {
                                            languageMenu = false
                                            if (code != selectedLanguage) {
                                                AppLanguage.saveLanguage(context, code)
                                                activity?.recreate()
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    Text(
                        stringResource(R.string.ui_025),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                item {
                    OutlinedTextField(
                        value = nicknameValue,
                        onValueChange = { nicknameValue = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.ui_036)) },
                        singleLine = true
                    )
                }

                item {
                    Card(
                        colors =
                            CardDefaults.cardColors(
                                containerColor =
                                    GreenSurface2
                            ),
                        modifier =
                            Modifier.fillMaxWidth()
                    ) {
                        Column(
                            Modifier.padding(12.dp),
                            verticalArrangement =
                                Arrangement.spacedBy(
                                    8.dp
                                )
                        ) {
                            Text(
                                stringResource(R.string.ui_006),
                                fontWeight =
                                    FontWeight.SemiBold
                            )

                            if (
                                !avatarPath
                                    .isNullOrBlank()
                            ) {
                                AsyncImage(
                                    model = avatarPath,
                                    contentDescription =
                                        "Selected avatar",
                                    contentScale =
                                        ContentScale.Crop,
                                    modifier =
                                        Modifier
                                            .size(72.dp)
                                            .clip(
                                                CircleShape
                                            )
                                )
                            } else {
                                Text(
                                    stringResource(R.string.ui_038),
                                    color =
                                        TextSecondary,
                                    style =
                                        MaterialTheme
                                            .typography
                                            .bodySmall
                                )
                            }

                            Row(
                                horizontalArrangement =
                                    Arrangement.spacedBy(
                                        8.dp
                                    )
                            ) {
                                OutlinedButton(
                                    enabled =
                                        !avatarBusy,
                                    onClick = {
                                        avatarPicker.launch(
                                            arrayOf(
                                                "image/*"
                                            )
                                        )
                                    }
                                ) {
                                    Text(
                                        if (
                                            avatarPath
                                                .isNullOrBlank()
                                        ) {
                                            "Choose avatar"
                                        } else {
                                            "Change avatar"
                                        }
                                    )
                                }

                                if (
                                    !avatarPath
                                        .isNullOrBlank()
                                ) {
                                    TextButton(
                                        enabled =
                                            !avatarBusy,
                                        onClick = {
                                            avatarBusy =
                                                true

                                            scope.launch {
                                                val result =
                                                    withContext(
                                                        Dispatchers.IO
                                                    ) {
                                                        backend
                                                            .clearAvatar()
                                                    }

                                                avatarBusy =
                                                    false

                                                if (
                                                    result.isSuccess
                                                ) {
                                                    avatarPath =
                                                        null
                                                    avatarMessage =
                                                        "Avatar removed"
                                                } else {
                                                    avatarMessage =
                                                        "Could not remove avatar"
                                                }
                                            }
                                        }
                                    ) {
                                        FittedControlLabel(R.string.ui_049)
                                    }
                                }
                            }

                            avatarMessage
                                ?.let {
                                    Text(
                                        it,
                                        color =
                                            TextSecondary,
                                        style =
                                            MaterialTheme
                                                .typography
                                                .bodySmall
                                    )
                                }
                        }
                    }
                }

                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = GreenSurface2
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                if (identityInfo.saved) {
                                    "Persistent identity: SAVED"
                                } else {
                                    "Persistent identity: NOT CREATED"
                                },
                                color = if (
                                    identityInfo.saved &&
                                    identityInfo.loadError == null
                                ) {
                                    GreenAccent
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                                fontWeight = FontWeight.Bold
                            )

                            Spacer(Modifier.height(6.dp))

                            Text(
                                "Client UID: " +
                                    identityInfo.clientUid.ifBlank {
                                        if (connected) "<not exposed>" else "<known after connect>"
                                    },
                                style = MaterialTheme.typography.bodySmall
                            )

                            Text(
                                "Security level: " +
                                    (identityInfo.securityLevel?.toString() ?: "<unknown>"),
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )

                            if (identityInfo.publicKey.isNotBlank()) {
                                Text(
                                    "Public key: ${identityInfo.publicKey.take(72)}" +
                                        if (identityInfo.publicKey.length > 72) "…" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                            }

                            identityInfo.loadError?.let {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "Identity load error: $it",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    stringResource(R.string.ui_061),
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }

                if (identityInfo.exportedIdentity.isNotBlank()) {
                    item {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    clipboard.setText(
                                        AnnotatedString(identityInfo.exportedIdentity)
                                    )
                                    identityMessage =
                                        "Identity export copied. Keep it private."
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                FittedControlLabel(R.string.ui_015)
                            }

                            if (identityInfo.backupAvailable) {
                                OutlinedButton(
                                    enabled = !connected && !identityBusy,
                                    onClick = {
                                        identityBusy = true
                                        scope.launch {
                                            val result = withContext(Dispatchers.IO) {
                                                backend.restoreIdentityBackup()
                                            }

                                            identityBusy = false
                                            if (result.isSuccess) {
                                                identityInfo = result.getOrThrow()
                                                identityMessage =
                                                    "Previous identity restored"
                                            } else {
                                                identityMessage =
                                                    result.exceptionOrNull()?.message
                                            }
                                        }
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    FittedControlLabel(R.string.ui_051)
                                }
                            }
                        }
                    }
                }

                item {
                    OutlinedTextField(
                        value = identityImport,
                        onValueChange = { identityImport = it },
                        enabled = !connected && !identityBusy,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.ui_027)) },
                        minLines = 2,
                        maxLines = 4
                    )
                }

                item {
                    Button(
                        enabled =
                            !connected &&
                            !identityBusy &&
                            identityImport.isNotBlank(),
                        onClick = {
                            identityBusy = true
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    backend.importIdentity(identityImport)
                                }

                                identityBusy = false
                                if (result.isSuccess) {
                                    identityInfo = result.getOrThrow()
                                    identityImport = ""
                                    confirmGenerate = false
                                    identityMessage =
                                        "Identity imported. Server permissions will follow this UID."
                                } else {
                                    identityMessage =
                                        result.exceptionOrNull()?.message
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.ui_026))
                    }
                }

                item {
                    OutlinedTextField(
                        value = identitySecurityLevel,
                        onValueChange = { value ->
                            identitySecurityLevel = value
                                .filter(Char::isDigit)
                                .take(2)
                        },
                        enabled = !connected && !identityBusy,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.ui_035)) },
                        singleLine = true
                    )
                }

                item {
                    Button(
                        enabled = !connected && !identityBusy,
                        onClick = {
                            if (!confirmGenerate) {
                                confirmGenerate = true
                                identityMessage =
                                    "Press Generate again to confirm. A new identity changes the TeamSpeak UID."
                            } else {
                                val level =
                                    identitySecurityLevel.toIntOrNull() ?: 10

                                identityBusy = true
                                scope.launch {
                                    val result = withContext(Dispatchers.Default) {
                                        backend.generateNewIdentity(level)
                                    }

                                    identityBusy = false
                                    confirmGenerate = false

                                    if (result.isSuccess) {
                                        identityInfo = result.getOrThrow()
                                        identityMessage =
                                            "New identity generated. Previous identity kept as backup."
                                    } else {
                                        identityMessage =
                                            result.exceptionOrNull()?.message
                                    }
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (confirmGenerate) {
                                Color(0xFFB23A48)
                            } else {
                                GreenSurface2
                            },
                            contentColor = TextPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (confirmGenerate) {
                                "CONFIRM: Generate NEW identity"
                            } else {
                                "Generate new identity / key"
                            }
                        )
                    }
                }

                item {
                    Text(
                        if (connected) {
                            "Disconnect before importing, generating, or restoring an identity."
                        } else {
                            "The identity export is a private credential. Anyone who obtains it can impersonate this TeamSpeak identity."
                        },
                        color = if (connected) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            TextSecondary
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                identityMessage?.let { message ->
                    item {
                        Text(
                            message,
                            color = if (
                                message.contains("error", ignoreCase = true)
                            ) {
                                MaterialTheme.colorScheme.error
                            } else {
                                GreenAccent
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                item {
                    HorizontalDivider()
                }

                item {
                    Text(
                        stringResource(R.string.ui_005),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                item {
                    AudioInputModePicker(
                        selected = audioMode,
                        onSelected = { audioMode = it }
                    )
                }

                if (audioMode == AudioInputMode.ALWAYS_ON) {
                    item {
                        Column {
                            Text(
                                "Voice activation threshold: ${(voiceThreshold * 100).toInt()}%",
                                color = TextPrimary
                            )
                            Slider(
                                value = voiceThreshold,
                                onValueChange = { voiceThreshold = it },
                                valueRange = 0.005f..0.50f
                            )
                            Text(
                                stringResource(R.string.ui_030),
                                color = TextSecondary,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                item {
                    DevicePicker(
                        label = "Microphone / input device",
                        selectedId = inputId,
                        options = inputDevices,
                        onSelected = { inputId = it }
                    )
                }

                item {
                    DevicePicker(
                        label = "Playback / communication device",
                        selectedId = outputId,
                        options = outputDevices,
                        onSelected = { outputId = it }
                    )
                }

                item {
                    Text(
                        if (audioMode == AudioInputMode.PTT) {
                            "Push-to-Talk opens the microphone only while Talk is held."
                        } else {
                            "AlwaysOn keeps capture active and transmits only above the selected threshold."
                        },
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                item {
                    HorizontalDivider()
                }

                item {
                    Text(
                        stringResource(R.string.ui_034),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                item {
                    MonitoringIntervalPicker(
                        selectedMinutes =
                            monitoringIntervalMinutes,
                        onSelected = {
                            monitoringIntervalMinutes = it
                        }
                    )
                }

                item {
                    Text(
                        "Controls how often Low function mode briefly connects " +
                            "to monitored servers and refreshes the client snapshot. " +
                            "Intervals below 15 minutes use delayed one-time work and " +
                            "remain best-effort: Android may delay background execution.",
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                item {
                    HorizontalDivider()
                }

                item {
                    Text(
                        stringResource(R.string.ui_045),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                item {
                    ProxyModePicker(
                        selected = proxyMode,
                        onSelected = { proxyMode = it }
                    )
                }

                if (proxyMode != ProxyMode.NONE) {
                    item {
                        OutlinedTextField(
                            value = proxyHost,
                            onValueChange = { proxyHost = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.ui_046)) },
                            singleLine = true
                        )
                    }

                    item {
                        OutlinedTextField(
                            value = proxyPort,
                            onValueChange = { value ->
                                proxyPort = value.filter(Char::isDigit)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.ui_048)) },
                            singleLine = true
                        )
                    }

                    item {
                        OutlinedTextField(
                            value = proxyUser,
                            onValueChange = { proxyUser = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.ui_064)) },
                            singleLine = true
                        )
                    }

                    item {
                        OutlinedTextField(
                            value = proxyPassword,
                            onValueChange = { proxyPassword = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.ui_043)) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation()
                        )
                    }

                    item {
                        Text(
                            when (proxyMode) {
                                ProxyMode.SOCKS5 ->
                                    "Saved, but TS3 transport is not proxied yet. " +
                                        "ts3j uses UDP and needs SOCKS5 UDP ASSOCIATE support."
                                ProxyMode.HTTPS ->
                                    "Saved, but ordinary HTTPS CONNECT cannot carry the " +
                                        "TeamSpeak UDP transport. A UDP-capable tunnel/MASQUE " +
                                        "or a separate relay layer is required."
                                ProxyMode.NONE -> ""
                            },
                            color = MaterialTheme.colorScheme.tertiary,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                item {
                    HorizontalDivider()
                }

                item {
                    Text(
                        stringResource(R.string.ui_016),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                item {
                    val dumpCount = run {
                        crashDumpGeneration
                        CrashDumpStore.count(context)
                    }
                    val latest = CrashDumpStore.latest(context)

                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = GreenSurface2
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                if (dumpCount == 0) {
                                    "No crash reports recorded"
                                } else {
                                    "$dumpCount crash report${if (dumpCount == 1) "" else "s"} stored"
                                },
                                color = if (dumpCount == 0) TextSecondary else TextPrimary
                            )

                            latest?.let {
                                Text(
                                    "Latest: ${it.name}",
                                    color = TextSecondary,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }

                            Text(
                                "Fatal Java/Kotlin crashes are saved locally before Android terminates the process. Reports contain version/device information and a stack trace.",
                                color = TextSecondary,
                                style = MaterialTheme.typography.bodySmall
                            )

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    enabled = dumpCount > 0,
                                    onClick = {
                                        val report = CrashDumpStore.exportBundle(context)
                                            ?: return@OutlinedButton
                                        val uri = FileProvider.getUriForFile(
                                            context,
                                            "${context.packageName}.files",
                                            report
                                        )
                                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_SUBJECT, "TS3X Client crash report")
                                            putExtra(Intent.EXTRA_STREAM, uri)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(
                                            Intent.createChooser(shareIntent, "Share crash report")
                                        )
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    FittedControlLabel(R.string.ui_060)
                                }

                                TextButton(
                                    enabled = dumpCount > 0,
                                    onClick = {
                                        CrashDumpStore.clear(context)
                                        crashDumpGeneration++
                                    }
                                ) {
                                    FittedControlLabel(R.string.ui_011)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !settingsSaving,
                onClick = {
                    if (settingsSaving) return@TextButton
                    settingsSaving = true

                    val requestedNickname = nicknameValue.trim()
                    val previousNickname = backend.savedNickname().trim()
                    val selectedInputId = inputId
                    val selectedOutputId = outputId
                    val selectedAudioMode = audioMode
                    val selectedThreshold = voiceThreshold
                    val selectedMonitoringInterval = monitoringIntervalMinutes
                    val selectedProxyMode = proxyMode
                    val selectedProxyHost = proxyHost.trim()
                    val selectedProxyPort = proxyPort.toIntOrNull() ?: 0
                    val selectedProxyUser = proxyUser
                    val selectedProxyPassword = proxyPassword

                    scope.launch {
                        val saveResult =
                            withContext(Dispatchers.IO) {
                                val nickResult =
                                    if (requestedNickname == previousNickname) {
                                        Result.success(previousNickname)
                                    } else {
                                        backend.setClientNickname(requestedNickname)
                                    }

                                val inputRoute =
                                    backend.setPreferredInputDevice(selectedInputId)
                                val outputRoute =
                                    backend.setPreferredOutputDevice(selectedOutputId)
                                val audioModeResult =
                                    backend.setAudioInputSettings(
                                        selectedAudioMode,
                                        selectedThreshold
                                    )

                                backend.setMonitoringIntervalMinutes(
                                    selectedMonitoringInterval
                                )

                                backend.setProxySettings(
                                    ProxySettings(
                                        mode = selectedProxyMode,
                                        host = selectedProxyHost,
                                        port = selectedProxyPort,
                                        username = selectedProxyUser,
                                        password = selectedProxyPassword
                                    )
                                )

                                Triple(
                                    nickResult,
                                    audioModeResult,
                                    outputRoute
                                )
                            }

                        settingsSaving = false

                        val nickResult = saveResult.first
                        val audioModeResult = saveResult.second
                        val route = saveResult.third

                        onSaved(
                            when {
                                nickResult.isFailure ->
                                    "Nickname error: " +
                                        (nickResult.exceptionOrNull()?.message ?: "unknown")

                                audioModeResult.isFailure ->
                                    "Audio mode could not be applied: " +
                                        (audioModeResult.exceptionOrNull()?.message ?: "unknown")

                                route.isFailure ->
                                    "Audio output could not be applied"

                                selectedProxyMode != ProxyMode.NONE ->
                                    "Identity/client/audio settings saved; proxy profile saved (transport inactive)"

                                else ->
                                    "Identity/client/audio settings saved"
                            }
                        )
                    }
                }
            ) {
                if (settingsSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.ui_054))
                } else {
                    FittedControlLabel(R.string.ui_052)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                FittedControlLabel(R.string.ui_009)
            }
        }
    )
}

@Composable
private fun DevicePicker(
    label: String,
    selectedId: Int?,
    options: List<AudioDeviceOption>,
    onSelected: (Int?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = options.firstOrNull { it.id == selectedId }
        ?: options.firstOrNull()
        ?: AudioDeviceOption(null, "System default")

    Box {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.Start
            ) {
                Text(
                    label,
                    color = TextSecondary,
                    style = MaterialTheme.typography.labelSmall
                )
                Text(
                    selected.label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text("▾")
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        onSelected(option.id)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun MonitoringIntervalPicker(
    selectedMinutes: Int,
    onSelected: (Int) -> Unit
) {
    val options = listOf(
        1 to "1 minute",
        2 to "2 minutes",
        5 to "5 minutes",
        10 to "10 minutes",
        15 to "15 minutes",
        30 to "30 minutes",
        60 to "1 hour",
        120 to "2 hours",
        300 to "5 hours",
        720 to "12 hours"
    )

    var expanded by remember {
        mutableStateOf(false)
    }

    val selectedLabel = options
        .firstOrNull {
            it.first == selectedMinutes
        }
        ?.second
        ?: "$selectedMinutes minutes"

    Box {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.Start
            ) {
                Text(
                    stringResource(R.string.ui_012),
                    color = TextSecondary,
                    style =
                        MaterialTheme.typography.labelSmall
                )
                Text(selectedLabel)
            }

            Text("▾")
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = {
                expanded = false
            }
        ) {
            options.forEach {
                (minutes, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onSelected(minutes)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun ProxyModePicker(
    selected: ProxyMode,
    onSelected: (ProxyMode) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.Start
            ) {
                Text(
                    stringResource(R.string.ui_047),
                    color = TextSecondary,
                    style = MaterialTheme.typography.labelSmall
                )
                Text(
                    when (selected) {
                        ProxyMode.NONE -> "None"
                        ProxyMode.SOCKS5 -> "SOCKS5"
                        ProxyMode.HTTPS -> "HTTPS"
                    }
                )
            }
            Text("▾")
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            ProxyMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = {
                        Text(
                            when (mode) {
                                ProxyMode.NONE -> "None"
                                ProxyMode.SOCKS5 -> "SOCKS5"
                                ProxyMode.HTTPS -> "HTTPS"
                            }
                        )
                    },
                    onClick = {
                        onSelected(mode)
                        expanded = false
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AudioInputModePicker(
    selected: AudioInputMode,
    onSelected: (AudioInputMode) -> Unit
) {
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier.fillMaxWidth()
    ) {
        AudioInputMode.entries.forEachIndexed { index, mode ->
            SegmentedButton(
                selected = selected == mode,
                onClick = { onSelected(mode) },
                shape = SegmentedButtonDefaults.itemShape(
                    index = index,
                    count = AudioInputMode.entries.size
                )
            ) {
                Text(
                    when (mode) {
                        AudioInputMode.PTT -> "Push-to-Talk"
                        AudioInputMode.ALWAYS_ON -> "AlwaysOn"
                    }
                )
            }
        }
    }
}

@Composable
private fun LiveAudioDock(
    backend: Ts3jBackend,
    mode: AudioInputMode,
    pttPressed: Boolean,
    threshold: Float,
    nickname: String,
    ownAvatarPath: String?,
    onOpenSettings: () -> Unit,
    onToggleMute: () -> Unit,
    onPttPress: () -> Unit,
    onPttRelease: () -> Unit
) {
    var stats by remember(backend) { mutableStateOf(backend.audioStats()) }
    var levels by remember(mode) { mutableStateOf(List(28) { 0f }) }
    LaunchedEffect(backend, mode) {
        while (true) {
            stats = backend.audioStats()
            if (mode == AudioInputMode.ALWAYS_ON) {
                levels = levels.drop(1) + if (stats.inputMuted) 0f else stats.micLevel.coerceIn(0f, 1f)
            }
            delay(85L)
        }
    }
    AudioDock(mode, stats, pttPressed, stats.inputMuted, levels, threshold, nickname,
        ownAvatarPath, onOpenSettings, onToggleMute, onPttPress, onPttRelease)
}

@Composable
private fun AudioDock(
    mode: AudioInputMode,
    audioStats: AudioStats,
    pttPressed: Boolean,
    micMuted: Boolean,
    levels: List<Float>,
    threshold: Float,
    nickname: String,
    ownAvatarPath: String?,
    onOpenSettings: () -> Unit,
    onToggleMute: () -> Unit,
    onPttPress: () -> Unit,
    onPttRelease: () -> Unit
) {
    val panel = Color(0xFF202127)
    val mutedRed = Color(0xFFDA6672)
    Surface(
        color = Color(0xFF131418),
        shadowElevation = 12.dp
    ) {
        Row(
            modifier = Modifier
                .navigationBarsPadding()
                .fillMaxWidth()
                .padding(horizontal = 7.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(panel)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(Modifier.size(39.dp).clip(CircleShape), contentAlignment = Alignment.Center) {
                if (ownAvatarPath != null) {
                    AsyncImage(
                        model = File(ownAvatarPath),
                        contentDescription = "My avatar",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        Modifier.fillMaxSize().background(Color(0xFF303B39)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(nickname.take(1).uppercase(), fontWeight = FontWeight.Bold)
                    }
                }

            }
            Spacer(Modifier.width(5.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    nickname.ifBlank { "TS3X Client" },
                    color = Color(0xFFF3F4F5),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    when {
                        micMuted -> "Microphone muted"
                        mode == AudioInputMode.PTT && pttPressed -> "Transmitting"
                        mode == AudioInputMode.PTT -> "Push to talk"
                        else -> "Voice activated"
                    },
                    color = Color(0xFFABB0B9),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1
                )
            }
            IconButton(onClick = onToggleMute, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = if (micMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                    contentDescription = if (micMuted) "Unmute" else "Mute",
                    tint = if (micMuted) mutedRed else Color(0xFFBBC0C9)
                )
            }
            if (mode == AudioInputMode.PTT) {
                Box(
                    modifier = Modifier.size(width = 66.dp, height = 36.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (pttPressed) GreenAccent else Color(0xFF36383F))
                        .pointerInput(micMuted, onPttPress, onPttRelease) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                if (!micMuted) onPttPress()
                                try { waitForUpOrCancellation() }
                                finally { if (!micMuted) onPttRelease() }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (pttPressed) "ON AIR" else "PTT",
                        color = if (pttPressed) DarkGreen else Color(0xFFEDF0F2),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            } else {
                Row(
                    modifier = Modifier.width(53.dp).height(27.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    levels.takeLast(9).forEach { raw ->
                        val value = if (micMuted) 0f else raw.coerceIn(0f, 1f)
                        Box(
                            Modifier.weight(1f)
                                .fillMaxHeight(value.coerceAtLeast(0.09f))
                                .clip(RoundedCornerShape(2.dp))
                                .background(if (value >= threshold) GreenAccent else GreenAccentSoft)
                        )
                    }
                }
            }
            IconButton(onClick = onOpenSettings, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Filled.Settings,
                    contentDescription = "Audio settings",
                    tint = Color(0xFFBBC0C9)
                )
            }
        }
    }
}

private data class JoinWarningDialogState(
    val title: String,
    val message: String,
    val channel: BackendChannel,
    val password: String?,
    val cooldownSeconds: Int = 0,
    val canRetry: Boolean = false
)

private data class FullscreenInfoContent(
    val title: String,
    val subtitle: String = "",
    val body: String = "",
    val imageUrl: String? = null,
    val serverInfo: ServerInfoScreenData? = null,
    val richBody: Boolean = false
)

private fun displayServerIp(
    serverReportedIp: String,
    connectedHost: String
): String {
    val reportedCandidates =
        serverReportedIp
            .split(
                ",",
                ";",
                " "
            )
            .map(
                String::trim
            )
            .filter(
                String::isNotBlank
            )
            .filterNot {
                value ->
                value == "0.0.0.0" ||
                    value == "::" ||
                    value == "[::]" ||
                    value == "::0" ||
                    value == "0:0:0:0:0:0:0:0"
            }

    if (reportedCandidates.isNotEmpty()) {
        return reportedCandidates
            .joinToString(", ")
    }

    // virtualserver_ip commonly contains the server's bind interfaces
    // (0.0.0.0 / ::), not the remote address used by the client. If the user
    // connected with a literal IP, show that instead. For DNS hostnames the
    // Address row already contains the actual endpoint and we avoid performing
    // DNS resolution on the Compose/UI thread.
    val host =
        connectedHost.trim()

    val looksIpv4 =
        Regex(
            """^\d{1,3}(?:\.\d{1,3}){3}$"""
        ).matches(host)

    val looksIpv6 =
        host.contains(":") &&
            !host.contains(".")

    return if (
        looksIpv4 ||
        looksIpv6
    ) {
        host
    } else {
        ""
    }
}

private data class ServerInfoScreenData(
    val publicName: String,
    val savedLabel: String = "",
    val address: String,
    val resolvedIp: String = "",
    val platform: String = "",
    val version: String = "",
    val status: String = "",
    val createdEpochSeconds: Long? = null,
    val uptimeSeconds: Long? = null,
    val clientsOnline: Int? = null,
    val maxClients: Int? = null,
    val channelsOnline: Int? = null,
    val reservedSlots: Int? = null,
    val passwordProtected: Boolean? = null,
    val serverUid: String = "",
    val iconId: Long? = null,
    val welcomeMessage: String = "",
    val hostMessage: String = "",
    val bannerImageUrl: String? = null,
    val bannerRawGfxUrl: String = "",
    val bannerLinkUrl: String = "",
    val hostButtonImageUrl: String? = null,
    val hostButtonLinkUrl: String = "",
    val hostButtonTooltip: String = "",
    val maxDownloadBytesPerSecond: Long? = null,
    val maxUploadBytesPerSecond: Long? = null,
    val totalBytesDownloaded: Long? = null,
    val totalBytesUploaded: Long? = null
)

private fun normalizeServerImageUrl(
    value: String
): String? {
    val trimmed = value.trim()

    if (trimmed.isBlank()) {
        return null
    }

    return when {
        trimmed.startsWith(
            "https://",
            ignoreCase = true
        ) -> trimmed

        trimmed.startsWith(
            "http://",
            ignoreCase = true
        ) -> trimmed

        trimmed.startsWith("//") ->
            "https:$trimmed"

        trimmed.startsWith(
            "www.",
            ignoreCase = true
        ) -> "https://$trimmed"

        else -> null
    }
}

private fun isHttpImageUrl(value: String): Boolean =
    normalizeServerImageUrl(value) != null

@Composable
private fun FullscreenInfoDialog(
    content: FullscreenInfoContent,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false
            )
    ) {
        Surface(
            color = DarkGreen,
            modifier = Modifier.fillMaxSize()
        ) {
            val serverInfo = content.serverInfo

            if (serverInfo != null) {
                ServerInfoScreen(
                    info = serverInfo,
                    onDismiss = onDismiss
                )
            } else {
                GenericFullscreenInfoScreen(
                    content = content,
                    onDismiss = onDismiss
                )
            }
        }
    }
}

@Composable
private fun GenericFullscreenInfoScreen(
    content: FullscreenInfoContent,
    onDismiss: () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(GreenSurface)
                .statusBarsPadding()
                .padding(
                    horizontal = 12.dp,
                    vertical = 8.dp
                ),
            verticalAlignment =
                Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    content.title,
                    fontWeight = FontWeight.Bold,
                    style =
                        MaterialTheme
                            .typography
                            .titleLarge
                )

                if (
                    content.subtitle
                        .isNotBlank()
                ) {
                    Text(
                        content.subtitle,
                        color = TextSecondary,
                        style =
                            MaterialTheme
                                .typography
                                .bodySmall
                    )
                }
            }

            TextButton(onClick = onDismiss) {
                FittedControlLabel(R.string.ui_013)
            }
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .navigationBarsPadding(),
            contentPadding =
                PaddingValues(
                    start = 16.dp,
                    top = 16.dp,
                    end = 16.dp,
                    bottom = 56.dp
                ),
            verticalArrangement =
                Arrangement.spacedBy(14.dp)
        ) {
            content.imageUrl
                ?.takeIf(::isHttpImageUrl)
                ?.let { image ->
                    item {
                        AsyncImage(
                            model = image,
                            contentDescription = null,
                            contentScale =
                                ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(
                                    min = 120.dp
                                )
                                .clip(
                                    RoundedCornerShape(
                                        10.dp
                                    )
                                )
                        )
                    }
                }

            if (content.body.isNotBlank()) {
                item {
                    if (content.richBody) {
                        Ts3BbCodeContent(
                            text = content.body,
                            modifier =
                                Modifier.fillMaxWidth()
                        )
                    } else {
                        Text(
                            content.body,
                            color = TextPrimary,
                            style =
                                MaterialTheme
                                    .typography
                                    .bodyLarge
                        )
                    }
                }
            }

            if (
                content.body.isBlank() &&
                content.imageUrl
                    .isNullOrBlank()
            ) {
                item {
                    Text(
                        stringResource(R.string.ui_037),
                        color = TextSecondary
                    )
                }
            }
        }
    }
}

@Composable
private fun ServerInfoScreen(
    info: ServerInfoScreenData,
    onDismiss: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(GreenSurface)
                .statusBarsPadding()
                .padding(
                    horizontal = 14.dp,
                    vertical = 10.dp
                ),
            verticalAlignment =
                Alignment.CenterVertically
        ) {
            info.hostButtonImageUrl
                ?.let { logo ->
                    AsyncImage(
                        model = logo,
                        contentDescription = null,
                        contentScale =
                            ContentScale.Crop,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(
                                RoundedCornerShape(
                                    9.dp
                                )
                            )
                    )

                    Spacer(
                        Modifier.width(10.dp)
                    )
                }

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    info.publicName,
                    fontWeight = FontWeight.Bold,
                    style =
                        MaterialTheme
                            .typography
                            .titleLarge
                )

                Text(
                    info.address,
                    color = TextSecondary,
                    style =
                        MaterialTheme
                            .typography
                            .bodySmall
                )
            }

            TextButton(onClick = onDismiss) {
                FittedControlLabel(R.string.ui_013)
            }
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(
                start = 14.dp,
                top = 12.dp,
                end = 14.dp,
                bottom = 48.dp
            ),
            verticalArrangement =
                Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    stringResource(R.string.ui_056),
                    color = TextSecondary,
                    style =
                        MaterialTheme
                            .typography
                            .labelMedium
                )

                Spacer(
                    Modifier.height(6.dp)
                )

                val banner =
                    info.bannerImageUrl

                if (banner != null) {
                    AsyncImage(
                        model = banner,
                        contentDescription =
                            "Server banner",
                        contentScale =
                            ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(
                                min = 120.dp,
                                max = 220.dp
                            )
                            .clip(
                                RoundedCornerShape(
                                    16.dp
                                )
                            )
                    )
                } else {
                    Card(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(92.dp),
                        colors =
                            CardDefaults
                                .cardColors(
                                    containerColor =
                                        GreenSurface2
                                )
                    ) {
                        Box(
                            modifier =
                                Modifier.fillMaxSize(),
                            contentAlignment =
                                Alignment.Center
                        ) {
                            val bannerProperty =
                                info.bannerRawGfxUrl
                                    .ifBlank {
                                        info.bannerLinkUrl
                                    }

                            if (
                                bannerProperty
                                    .isBlank()
                            ) {
                                Text(
                                    stringResource(R.string.ui_040),
                                    color =
                                        TextSecondary
                                )
                            } else {
                                Column(
                                    modifier =
                                        Modifier.padding(
                                            12.dp
                                        ),
                                    horizontalAlignment =
                                        Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        "Server supplied banner URL:",
                                        color =
                                            TextSecondary,
                                        style =
                                            MaterialTheme
                                                .typography
                                                .bodySmall
                                    )

                                    Text(
                                        bannerProperty,
                                        color =
                                            GreenAccent,
                                        style =
                                            MaterialTheme
                                                .typography
                                                .bodySmall
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                val serverMessages =
                    listOf(
                        sanitizeServerMessage(
                            info.hostMessage
                        ),
                        sanitizeServerMessage(
                            info.welcomeMessage
                        )
                    )
                        .filter {
                            it.isNotBlank()
                        }
                        .distinct()

                val serverText =
                    serverMessages
                        .joinToString(
                            separator =
                                if (
                                    serverMessages.size > 1
                                ) {
                                    "\n\n"
                                } else {
                                    ""
                                }
                        )

                Text(
                    stringResource(R.string.ui_058),
                    color = TextSecondary,
                    style =
                        MaterialTheme
                            .typography
                            .labelMedium
                )

                Spacer(
                    Modifier.height(6.dp)
                )

                Card(
                    modifier =
                        Modifier.fillMaxWidth(),
                    colors =
                        CardDefaults
                            .cardColors(
                                containerColor =
                                    GreenSurface2
                            )
                ) {
                    if (serverText.isBlank()) {
                        Text(
                            stringResource(R.string.ui_041),
                            color = TextSecondary,
                            style =
                                MaterialTheme
                                    .typography
                                    .bodyMedium,
                            modifier =
                                Modifier.padding(
                                    14.dp
                                )
                        )
                    } else {
                        Ts3BbCodeContent(
                            text = serverText,
                            modifier =
                                Modifier.padding(
                                    14.dp
                                )
                        )
                    }
                }
            }

            item {
                Row(
                    modifier =
                        Modifier.fillMaxWidth(),
                    horizontalArrangement =
                        Arrangement.spacedBy(
                            10.dp
                        )
                ) {
                    ServerInfoMetricCard(
                        title = "Users",
                        value =
                            formatServerClientCount(
                                info.clientsOnline,
                                info.maxClients
                            ),
                        modifier =
                            Modifier.weight(1f)
                    )

                    ServerInfoMetricCard(
                        title = "Channels",
                        value =
                            info.channelsOnline
                                ?.toString()
                                ?: "—",
                        modifier =
                            Modifier.weight(1f)
                    )
                }
            }

            item {
                Row(
                    modifier =
                        Modifier.fillMaxWidth(),
                    horizontalArrangement =
                        Arrangement.spacedBy(
                            10.dp
                        )
                ) {
                    ServerInfoMetricCard(
                        title = "Uptime",
                        value =
                            info.uptimeSeconds
                                ?.let(
                                    ::formatServerDuration
                                )
                                ?: "Not provided",
                        modifier =
                            Modifier.weight(1f)
                    )

                    ServerInfoMetricCard(
                        title = "Created",
                        value =
                            info.createdEpochSeconds
                                ?.let(
                                    ::formatServerDate
                                )
                                ?: "Not provided",
                        modifier =
                            Modifier.weight(1f)
                    )
                }
            }

            item {
                ServerInfoDetailsCard(info)
            }

            if (
                info.maxDownloadBytesPerSecond !=
                    null ||
                info.maxUploadBytesPerSecond !=
                    null ||
                info.totalBytesDownloaded !=
                    null ||
                info.totalBytesUploaded !=
                    null
            ) {
                item {
                    ServerInfoTransferCard(info)
                }
            }

            item {
                Column(
                    modifier =
                        Modifier.fillMaxWidth(),
                    horizontalAlignment =
                        Alignment.CenterHorizontally
                ) {
                    Text(
                        info.address,
                        color = TextSecondary,
                        style =
                            MaterialTheme
                                .typography
                                .bodySmall
                    )

                    val footer =
                        listOf(
                            info.version,
                            info.platform
                        )
                            .filter(
                                String::isNotBlank
                            )
                            .joinToString(" • ")

                    if (footer.isNotBlank()) {
                        Text(
                            footer,
                            color = TextSecondary,
                            style =
                                MaterialTheme
                                    .typography
                                    .bodySmall
                        )
                    }
                }

                Spacer(
                    Modifier.height(8.dp)
                )
            }
        }
    }
}

@Composable
private fun ServerInfoMetricCard(
    title: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors =
            CardDefaults.cardColors(
                containerColor =
                    GreenSurface2
            )
    ) {
        Column(
            modifier =
                Modifier.padding(13.dp)
        ) {
            Text(
                title,
                color = TextSecondary,
                style =
                    MaterialTheme
                        .typography
                        .labelMedium
            )

            Spacer(Modifier.height(3.dp))

            Text(
                value,
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                style =
                    MaterialTheme
                        .typography
                        .titleMedium
            )
        }
    }
}

@Composable
private fun ServerInfoDetailsCard(
    info: ServerInfoScreenData
) {
    val rows =
        buildList {
            add(
                "Public name" to
                    info.publicName
            )

            add(
                "Address" to
                    info.address
            )

            if (info.savedLabel.isNotBlank()) {
                add(
                    "Saved label" to
                        info.savedLabel
                )
            }

            if (info.resolvedIp.isNotBlank()) {
                add(
                    "Server IP" to
                        info.resolvedIp
                )
            }

            if (info.platform.isNotBlank()) {
                add(
                    "Operating system" to
                        info.platform
                )
            }

            if (info.version.isNotBlank()) {
                add(
                    "Server version" to
                        info.version
                )
            }

            if (info.status.isNotBlank()) {
                add(
                    "Status" to
                        info.status
                )
            }

            info.reservedSlots
                ?.let {
                    add(
                        "Reserved slots" to
                            it.toString()
                    )
                }

            info.passwordProtected
                ?.let {
                    add(
                        "Password" to
                            if (it) {
                                "Protected"
                            } else {
                                "Not required"
                            }
                    )
                }

            if (info.serverUid.isNotBlank()) {
                add(
                    "Server UID" to
                        info.serverUid
                )
            }

            info.iconId
                ?.takeIf { it != 0L }
                ?.let {
                    add(
                        "Server icon ID" to
                            it.toString()
                    )
                }
        }

    Card(
        colors =
            CardDefaults.cardColors(
                containerColor =
                    GreenSurface2
            ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement =
                Arrangement.spacedBy(8.dp)
        ) {
            Text(
                stringResource(R.string.ui_057),
                fontWeight = FontWeight.Bold,
                style =
                    MaterialTheme
                        .typography
                        .titleMedium
            )

            rows.forEach { (label, value) ->
                Row(
                    modifier =
                        Modifier.fillMaxWidth(),
                    verticalAlignment =
                        Alignment.Top
                ) {
                    Text(
                        label,
                        color = TextSecondary,
                        style =
                            MaterialTheme
                                .typography
                                .bodySmall,
                        modifier =
                            Modifier.weight(0.42f)
                    )

                    Text(
                        value,
                        color = TextPrimary,
                        style =
                            MaterialTheme
                                .typography
                                .bodySmall,
                        modifier =
                            Modifier.weight(0.58f)
                    )
                }
            }
        }
    }
}

@Composable
private fun ServerInfoTransferCard(
    info: ServerInfoScreenData
) {
    Card(
        colors =
            CardDefaults.cardColors(
                containerColor =
                    GreenSurface2
            ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement =
                Arrangement.spacedBy(6.dp)
        ) {
            Text(
                stringResource(R.string.ui_023),
                fontWeight = FontWeight.Bold
            )

            info.maxDownloadBytesPerSecond
                ?.let {
                    Text(
                        stringResource(R.string.ui_031) +
                            formatByteRate(it),
                        color = TextSecondary
                    )
                }

            info.maxUploadBytesPerSecond
                ?.let {
                    Text(
                        stringResource(R.string.ui_032) +
                            formatByteRate(it),
                        color = TextSecondary
                    )
                }

            info.totalBytesDownloaded
                ?.let {
                    Text(
                        stringResource(R.string.ui_020) +
                            formatByteCount(it),
                        color = TextSecondary
                    )
                }

            info.totalBytesUploaded
                ?.let {
                    Text(
                        stringResource(R.string.ui_063) +
                            formatByteCount(it),
                        color = TextSecondary
                    )
                }
        }
    }
}

private fun formatServerClientCount(
    online: Int?,
    maximum: Int?
): String =
    when {
        online != null &&
            maximum != null ->
            "$online / $maximum"

        online != null ->
            online.toString()

        maximum != null ->
            "— / $maximum"

        else -> "—"
    }

private fun formatServerDuration(
    seconds: Long
): String {
    var remaining =
        seconds.coerceAtLeast(0L)

    val days =
        remaining / 86_400L
    remaining %= 86_400L

    val hours =
        remaining / 3_600L
    remaining %= 3_600L

    val minutes =
        remaining / 60L

    return buildList {
        if (days > 0) {
            add("${days}d")
        }

        if (
            hours > 0 ||
            days > 0
        ) {
            add("${hours}h")
        }

        add("${minutes}m")
    }.joinToString(" ")
}

private fun formatServerDate(
    epochSeconds: Long
): String =
    runCatching {
        java.time.Instant
            .ofEpochSecond(epochSeconds)
            .atZone(
                java.time.ZoneId
                    .systemDefault()
            )
            .format(
                java.time.format
                    .DateTimeFormatter
                    .ofPattern(
                        "yyyy-MM-dd"
                    )
            )
    }.getOrElse {
        epochSeconds.toString()
    }

private fun formatByteRate(
    bytesPerSecond: Long
): String =
    if (bytesPerSecond < 0) {
        "Unlimited"
    } else {
        formatByteCount(
            bytesPerSecond
        ) + "/s"
    }

private fun formatByteCount(
    bytes: Long
): String {
    if (bytes < 0) return "Unlimited"

    val units = listOf(
        "B",
        "KiB",
        "MiB",
        "GiB",
        "TiB"
    )

    var value =
        bytes.toDouble()
    var unitIndex = 0

    while (
        value >= 1024.0 &&
        unitIndex <
            units.lastIndex
    ) {
        value /= 1024.0
        unitIndex++
    }

    return if (unitIndex == 0) {
        "${value.toLong()} ${units[unitIndex]}"
    } else {
        String.format(
            java.util.Locale.US,
            "%.1f %s",
            value,
            units[unitIndex]
        )
    }
}

/** Compact variable-width messaging bubbles with a long-press copy action. */
@Composable
private fun ConversationMessageBubble(message: BackendChatMessage) {
    val parsed = remember(message.text) { ChatMessageContentParser.parse(message.text) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val copiedLabel = localizedLabel("Copied", "Kopiert", "Скопировано")
    val bubbleMaxWidth = (LocalConfiguration.current.screenWidthDp * 0.75f).dp.coerceAtMost(460.dp)
    val imageUrl = parsed.directImageUrl
    val outgoing = message.outgoing
    var imageLoadFailed by remember(imageUrl) { mutableStateOf(false) }
    var showLargeImage by remember { mutableStateOf(false) }
    val copyText = parsed.cloudAttachment?.let { cloud ->
        buildString {
            append(cloud.fileName)
            if (parsed.body.isNotBlank()) append("\n").append(parsed.body)
        }
    } ?: message.text

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = bubbleMaxWidth)
                .pointerInput(copyText) {
                    detectTapGestures(onLongPress = {
                        clipboard.setText(AnnotatedString(copyText))
                        android.widget.Toast.makeText(
                            context,
                            copiedLabel,
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    })
                },
            color = if (outgoing) GreenAccentSoft else Color(0xFF18382B),
            shape = if (outgoing) RoundedCornerShape(
                topStart = 17.dp, topEnd = 17.dp, bottomStart = 17.dp, bottomEnd = 4.dp
            ) else RoundedCornerShape(
                topStart = 17.dp, topEnd = 17.dp, bottomStart = 4.dp, bottomEnd = 17.dp
            )
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                if (!outgoing) {
                    Text(
                        message.sender,
                        color = GreenAccent,
                        style = MaterialTheme.typography.labelMedium
                    )
                    Spacer(Modifier.height(3.dp))
                }
                parsed.cloudAttachment?.let { attachment ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (attachment.image) Icons.Filled.ChatImageIcon else Icons.Filled.InsertDriveFile,
                            contentDescription = null,
                            modifier = Modifier.size(34.dp),
                            tint = GreenAccent
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f, fill = false)) {
                            Text(
                                attachment.fileName,
                                color = TextPrimary,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            attachment.fileSize?.let { fileSize ->
                                Text(
                                    formatByteCount(fileSize),
                                    color = TextSecondary,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(5.dp))
                    Text(
                        localizedLabel(
                            "myTeamSpeak cloud attachment · preview requires cloud access",
                            "myTeamSpeak-Datei · Vorschau benötigt Cloud-Zugriff",
                            "Файл myTeamSpeak · для просмотра нужен доступ к облаку"
                        ),
                        color = TextSecondary,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                if (imageUrl != null) {
                    if (imageLoadFailed) {
                        Text(
                            localizedLabel("Image unavailable", "Bild nicht verfügbar", "Изображение недоступно"),
                            color = TextSecondary,
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(imageUrl)
                                .size(1024)
                                .memoryCachePolicy(CachePolicy.ENABLED)
                                .diskCachePolicy(CachePolicy.ENABLED)
                                .build(),
                            contentDescription = localizedLabel("Shared image", "Geteiltes Bild", "Изображение в чате"),
                            modifier = Modifier
                                .widthIn(max = bubbleMaxWidth - 24.dp)
                                .heightIn(min = 110.dp, max = 230.dp)
                                .clickable { showLargeImage = true },
                            contentScale = ContentScale.Fit,
                            onError = { imageLoadFailed = true }
                        )
                    }
                } else if (parsed.body.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        parsed.body,
                        color = TextPrimary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    remember(message.receivedAt) {
                        java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                            .format(java.util.Date(message.receivedAt))
                    },
                    modifier = Modifier.align(Alignment.End),
                    color = TextSecondary,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
    if (showLargeImage && imageUrl != null) {
        Dialog(onDismissRequest = { showLargeImage = false }) {
            Surface(color = GreenSurface2, shape = RoundedCornerShape(12.dp)) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = localizedLabel("Shared image", "Geteiltes Bild", "Изображение"),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 600.dp)
                        .clickable { showLargeImage = false },
                    contentScale = ContentScale.Fit
                )
            }
        }
    }
}

/** Persistent conversation view for connected TS3 server, channel, or online client. */
@Composable
private fun Ts3ConversationDialog(
    chat: OpenChatConversation,
    backend: Ts3jBackend,
    revision: Int,
    onClose: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val messages = remember(chat.conversationKey, revision) {
        backend.chatMessages(chat.conversationKey)
    }
    var draft by remember(chat.conversationKey) { mutableStateOf("") }
    var sending by remember(chat.conversationKey) { mutableStateOf(false) }
    var sendError by remember(chat.conversationKey) { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val maySend = backend.connected && when {
        chat.conversationKey.startsWith("channel:") ->
            backend.currentChannelId?.toString() == chat.conversationKey.removePrefix("channel:")
        chat.conversationKey.startsWith("private:") ->
            backend.privateChatRecipient(chat.conversationKey, chat.peerClientId) != null
        else -> false
    }
    LaunchedEffect(chat.conversationKey, messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }
    fun send() {
        if (sending || !maySend || draft.isBlank()) return
        val content = draft.trim()
        if (content.toByteArray(Charsets.UTF_8).size > 8192) {
            sendError = "Message exceeds 8192 bytes"
            return
        }
        sending = true
        sendError = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                backend.sendChatMessage(chat.conversationKey, content, chat.peerClientId)
            }
            sending = false
            result.onSuccess { draft = "" }
                .onFailure { sendError = it.message ?: "Message delivery failed" }
        }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.82f),
            shape = RoundedCornerShape(20.dp), color = GreenSurface2
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.ChatBubbleOutline, contentDescription = null, tint = GreenAccent)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(chat.title, color = TextPrimary,
                            style = MaterialTheme.typography.titleMedium, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                        Text(
                            if (chat.conversationKey.startsWith("channel:"))
                                localizedLabel("Channel messages", "Kanalnachrichten", "Сообщения канала")
                            else
                                localizedLabel("Private messages", "Private Nachrichten", "Личные сообщения"),
                            color = TextSecondary, style = MaterialTheme.typography.labelSmall
                        )
                    }
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, contentDescription =
                            localizedLabel("Close", "Schließen", "Закрыть"), tint = TextPrimary)
                    }
                }
                HorizontalDivider(color = Color(0xFF305344))
                if (messages.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(localizedLabel("No messages yet", "Noch keine Nachrichten", "Сообщений пока нет"),
                            color = TextSecondary)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        state = listState,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(messages, key = { it.id }) { message ->
                            ConversationMessageBubble(message)
                        }
                    }
                }
                if (!maySend) {
                    Text(
                        localizedLabel(
                            "Join this channel or wait for the recipient to reconnect to send messages.",
                            "Zum Senden diesem Kanal beitreten oder warten, bis der Empfänger wieder online ist.",
                            "Для отправки войдите в канал или дождитесь подключения собеседника."
                        ),
                        color = TextSecondary, style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }
                sendError?.let { error ->
                    Text(error, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                }
                Row(
                    Modifier.fillMaxWidth().padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = draft, onValueChange = { draft = it; sendError = null },
                        modifier = Modifier.weight(1f),
                        enabled = maySend && !sending,
                        placeholder = { Text(localizedLabel("Message", "Nachricht", "Сообщение")) },
                        maxLines = 4,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { send() })
                    )
                    Spacer(Modifier.width(6.dp))
                    IconButton(
                        enabled = maySend && draft.isNotBlank() && !sending,
                        onClick = { send() }
                    ) {
                        Icon(Icons.Filled.Send, contentDescription =
                            localizedLabel("Send", "Senden", "Отправить"), tint = GreenAccent)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelDetailsCard(
    channel: BackendChannel,
    unreadCount: Int,
    onOpenChat: () -> Unit
) {
    val image = channelImageUrl(channel)
    val description = stripTs3BbCode(channel.description)

    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = GreenSurface2)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 92.dp)
        ) {
            if (image != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(ChannelBannerPrefetcher.displayData(image))
                        .memoryCachePolicy(CachePolicy.ENABLED)
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alpha = 0.52f,
                    modifier = Modifier.matchParentSize()
                )
            }

            IconButton(
                onClick = onOpenChat,
                modifier = Modifier.align(Alignment.TopEnd).padding(2.dp)
            ) {
                BadgedBox(badge = {
                    if (unreadCount > 0) Badge { Text(if (unreadCount > 99) "99+" else unreadCount.toString()) }
                }) {
                    Icon(Icons.Filled.ChatBubbleOutline, contentDescription =
                        localizedLabel("Channel chat", "Kanalchat", "Чат канала"), tint = GreenAccent)
                }
            }
            Column(
                Modifier.padding(start = 14.dp, top = 14.dp, bottom = 14.dp, end = 62.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (channel.iconId != 0L) {
                        Text("◆ ", color = GreenAccent)
                    }
                    Text(
                        channel.name,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (channel.hasPassword) {
                        Spacer(Modifier.width(8.dp))
                        Text("🔒", style = MaterialTheme.typography.labelMedium)
                    }
                }

                if (channel.topic.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        channel.topic,
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                if (description.isNotBlank()) {
                    Spacer(Modifier.height(7.dp))
                    Text(
                        description,
                        color = TextPrimary,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 5,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun ChannelRow(
    channel: BackendChannel,
    depth: Int,
    memberCount: Int,
    isSelected: Boolean,
    isCurrent: Boolean,
    hasChildren: Boolean,
    isCollapsed: Boolean,
    onToggleCollapse: () -> Unit,
    onSelect: () -> Unit,
    onDoubleClick: () -> Unit
) {
    val backgroundImage = channelImageUrl(channel)

    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .pointerInput(channel.id) {
                detectTapGestures(
                    onTap = { onSelect() },
                    onDoubleTap = { onDoubleClick() }
                )
            }
    ) {
        if (backgroundImage != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(ChannelBannerPrefetcher.displayData(backgroundImage))
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = 0.34f,
                modifier = Modifier
                    .matchParentSize()
                    .heightIn(min = 54.dp)
            )
        }

        Column(
            Modifier
                .fillMaxWidth()
                .background(
                    when {
                        isCurrent -> GreenAccentSoft.copy(alpha = 0.72f)
                        isSelected -> GreenSurface2.copy(alpha = 0.82f)
                        else -> Color.Transparent
                    }
                )
                .padding(
                    start = (12 + depth * 18).dp,
                    end = 12.dp,
                    top = 10.dp,
                    bottom = 8.dp
                )
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .width(24.dp)
                        .then(
                            if (hasChildren) Modifier.clickable(onClick = onToggleCollapse)
                            else Modifier
                        ),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(
                        when {
                            !hasChildren -> ""
                            isCollapsed -> "▸"
                            else -> "▾"
                        },
                        color = GreenAccent,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (isCurrent) {
                    Text("● ", color = GreenAccent, style = MaterialTheme.typography.labelSmall)
                }

                if (channel.iconId != 0L) {
                    Text("◆ ", color = GreenAccent)
                }

                Text(
                    channel.name.ifBlank { "Channel ${channel.id}" },
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )

                if (channel.hasPassword) {
                    Text("🔒 ", style = MaterialTheme.typography.labelSmall)
                }

                if (memberCount > 0) {
                    Spacer(Modifier.width(6.dp))
                    Text("$memberCount", color = TextSecondary)
                }

                Spacer(Modifier.width(8.dp))
                Text("›", color = GreenAccent)
            }

            if (channel.topic.isNotBlank()) {
                Text(
                    channel.topic,
                    color = TextSecondary,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 22.dp, top = 2.dp)
                )
            }


        }
    }

    HorizontalDivider(color = Color(0xFF19392A))
}


private fun countryFlagEmoji(code: String): String {
    if (!code.matches(Regex("[A-Z]{2}"))) return ""
    return buildString {
        code.forEach { letter ->
            appendCodePoint(0x1F1E6 + (letter.code - 'A'.code))
        }
    }
}

@Composable
private fun ChannelMemberRow(
    client: BackendClient,
    depth: Int,
    serverHost: String,
    serverPort: Int,
    avatarCacheRevision: Int,
    groupIconIds: Map<Long, Long>,
    roleIconRevision: Int,
    backend: Ts3jBackend,
    activeVoiceState: State<Set<Int>>,
    onOpenChat: () -> Unit,
    unreadCount: Int
) {
    val transmitting by remember(client.id, activeVoiceState) {
        derivedStateOf { client.id in activeVoiceState.value }
    }
    Box(Modifier.padding(start = (12 + depth * 18).dp, end = 12.dp)) {
        TeamSpeakClientRow(client, serverHost, serverPort, avatarCacheRevision, groupIconIds,
            roleIconRevision, backend, transmitting, onOpenChat, unreadCount)
    }
}

@Composable
private fun TeamSpeakClientRow(
    client: BackendClient,
    serverHost: String,
    serverPort: Int,
    avatarCacheRevision: Int,
    groupIconIds: Map<Long, Long>,
    roleIconRevision: Int,
    backend: Ts3jBackend,
    isTransmitting: Boolean,
    onOpenChat: () -> Unit,
    unreadCount: Int
) {
    val context = LocalContext.current
    var menuExpanded by remember(client.id, serverHost, serverPort) { mutableStateOf(false) }
    val avatarFile = remember(serverHost, serverPort, client.uniqueId, client.avatarHash, avatarCacheRevision) {
        client.uniqueId.takeIf { it.isNotBlank() }
            ?.let { clientAvatarCacheFile(context, serverHost, serverPort, it) }
            ?.takeIf { it.isFile && it.length() > 0L }
    }
    var serverImageFailed by remember(avatarFile, avatarCacheRevision, client.avatarHash) { mutableStateOf(false) }
    var accountImageFailed by remember(client.accountAvatarUrl) { mutableStateOf(false) }
    var accountImageRetries by remember(client.accountAvatarUrl) { mutableIntStateOf(0) }
    LaunchedEffect(client.accountAvatarUrl, accountImageFailed) {
        if (accountImageFailed && accountImageRetries < 3) {
            delay(5_000L shl accountImageRetries)
            accountImageRetries++
            accountImageFailed = false
        }
    }
    val avatarSource = com.morsen.ts3xclient.protocol.TeamSpeakAvatarMetadata.source(
        client.avatarHash, avatarFile != null && !serverImageFailed,
        client.accountAvatarUrl.takeUnless { accountImageFailed }
    )
    val avatarData: Any? = when (avatarSource) {
        com.morsen.ts3xclient.protocol.TeamSpeakAvatarMetadata.Source.SERVER -> avatarFile
        com.morsen.ts3xclient.protocol.TeamSpeakAvatarMetadata.Source.ACCOUNT -> client.accountAvatarUrl
        else -> null
    }
    Box(modifier = Modifier.fillMaxWidth()) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable { menuExpanded = true }
            .padding(start = 24.dp, end = 4.dp, top = 6.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .border(
                    width = if (isTransmitting) 1.5.dp else 0.dp,
                    color = if (isTransmitting) GreenAccent else Color.Transparent,
                    shape = CircleShape
                )
                .padding(2.dp)
                .clip(CircleShape)
                .background(Color(0xFF253E35)),
            contentAlignment = Alignment.Center
        ) {
            when {
                avatarData != null -> {
                    AsyncImage(
                        model = remember(avatarData, avatarCacheRevision, client.avatarHash) {
                            ImageRequest.Builder(context)
                                .data(avatarData)
                                .memoryCacheKey(if (avatarData is File)
                                    "${avatarData.absolutePath}:${avatarData.lastModified()}:${avatarData.length()}:${client.avatarHash.orEmpty()}"
                                    else "myts-avatar:$avatarData")
                                .diskCachePolicy(if (avatarData is File) CachePolicy.DISABLED else CachePolicy.ENABLED)
                                .build()
                        },
                        contentDescription = "${client.nickname} avatar",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                        onError = { error ->
                            if (avatarSource == com.morsen.ts3xclient.protocol.TeamSpeakAvatarMetadata.Source.SERVER)
                                serverImageFailed = true else accountImageFailed = true
                            Log.w("TS3X_AVATAR", "render.failed clientId=${client.id} source=$avatarSource type=${error.result.throwable.javaClass.simpleName}")
                        }
                    )
                }
                client.isAway -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF17352D), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "AFK",
                            color = Color(0xFF6F7873),
                            fontWeight = FontWeight.Medium,
                            fontSize = 8.sp,
                            lineHeight = 8.sp,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            letterSpacing = 0.2.sp
                        )
                    }
                }
                else -> {
                    Text(
                        client.nickname.take(1).uppercase(),
                        color = GreenAccent,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
            if (client.isAway && avatarData != null) {
                // Keep the avatar visible; AFK is a status badge, not a replacement image.
                Text(
                    "AFK",
                    color = Color(0xFFE0E8E3),
                    fontSize = 6.sp,
                    lineHeight = 7.sp,
                    modifier = Modifier.align(Alignment.BottomCenter)
                        .background(Color(0xD917352D), RoundedCornerShape(2.dp))
                        .padding(horizontal = 2.dp)
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                client.nickname,
                color = TextPrimary,
                modifier = Modifier.weight(1f, fill = false),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium
            )
            if (client.isAway) {
                Spacer(Modifier.width(4.dp))
                val message = client.awayMessage.ifBlank { "Отошёл" }
                // Limit the visible payload to 15 Unicode code points, not UTF-16 units.
                val codePointCount = message.codePointCount(0, message.length)
                val shortMessage = if (codePointCount > 15) {
                    val end = message.offsetByCodePoints(0, 15)
                    message.substring(0, end) + "..."
                } else message
                val afkLabel = "[$shortMessage]"
                val afkColor = TextSecondary
                Text(
                    text = afkLabel,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    color = if (codePointCount > 15) Color.Unspecified else afkColor,
                    style = if (codePointCount > 15) {
                        MaterialTheme.typography.labelSmall.copy(
                            brush = Brush.horizontalGradient(
                                0.0f to afkColor,
                                0.60f to afkColor,
                                1.0f to afkColor.copy(alpha = 0.10f)
                            )
                        )
                    } else MaterialTheme.typography.labelSmall
                )
            }
        }
        if (client.inputMuted) {
            Icon(Icons.Filled.MicOff, "Microphone muted",
                modifier = Modifier.size(15.dp), tint = TextSecondary)
            Spacer(Modifier.width(6.dp))
        }
        val roleIcons = client.serverGroupIds.mapNotNull { groupIconIds[it] }
            .filter { it != 0L }.distinct().take(3)
        val flag = countryFlagEmoji(client.countryCode)
        val channelRole = backend.channelGroupBadge(client.channelGroupId)
        if (roleIcons.isNotEmpty() || flag.isNotEmpty() || channelRole != null) {
            ClientMetaBadge(
                serverHost = serverHost,
                serverPort = serverPort,
                roleIconIds = roleIcons,
                roleIconRevision = roleIconRevision,
                channelRole = channelRole,
                flag = flag
            )
        }
    }
    }
    if (menuExpanded) {
        Ts6ClientActionDialog(
            client = client,
            backend = backend,
            serverHost = serverHost,
            serverPort = serverPort,
            onClose = { menuExpanded = false },
            onOpenChat = onOpenChat,
            unreadCount = unreadCount
        )
    }

}

@Composable
private fun Ts6StatisticRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = TextSecondary, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        Text(value, color = Color.White, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Ts6ClientActionDialog(
    client: BackendClient,
    backend: Ts3jBackend,
    serverHost: String,
    serverPort: Int,
    onClose: () -> Unit,
    onOpenChat: () -> Unit,
    unreadCount: Int
) {
    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var page by remember(client.id) { mutableStateOf("main") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var connectionData by remember { mutableStateOf<Map<String, Any?>>(emptyMap()) }
    var connectionLoading by remember { mutableStateOf(false) }
    var channelGroups by remember { mutableStateOf<List<Ts3jBackend.ChannelGroupSummary>>(emptyList()) }
    var channelGroupsLoading by remember { mutableStateOf(false) }
    var activeChannelGroupId by remember(client.id) { mutableStateOf(client.channelGroupId) }
    var channelIconRevision by remember { mutableIntStateOf(0) }
    var serverGroups by remember { mutableStateOf<List<Ts3jBackend.ServerGroupSummary>>(emptyList()) }
    var serverGroupIconRevision by remember { mutableIntStateOf(0) }

    var assignedGroupIds by remember(client.id) { mutableStateOf(client.serverGroupIds.toSet()) }
    var volume by remember(client.id) { mutableFloatStateOf(backend.clientVolume(client.id)) }
    val isSelf = client.id == backend.ownClientId
    var awayEnabled by remember(client.id) { mutableStateOf(backend.isSelfAway()) }
    var awayText by remember(client.id) { mutableStateOf(backend.selfAwayMessage()) }
    var banMinutes by remember { mutableStateOf("60") }
    var reason by remember { mutableStateOf("") }
    val panelWidth = configuration.screenWidthDp.dp * 0.80f
    val panelHeight = configuration.screenHeightDp.dp * 0.60f

    // Poll only while this dialog's connection-info page is visible.
    // The next request never starts before the previous one has completed.
    LaunchedEffect(page, client.id) {
        if (page != "info") return@LaunchedEffect
        connectionLoading = true
        Log.i("TS3X_STATS", "ui.poll.start clientId=${client.id} intervalMs=1000")
        try {
            while (true) {
                val started = SystemClock.elapsedRealtime()
                Log.i("TS3X_STATS", "ui.request.launch clientId=${client.id}")
                try {
                    val result = withContext(Dispatchers.IO) {
                        backend.clientConnectionDetails(client.id)
                    }
                    connectionLoading = false
                    Log.i("TS3X_STATS", "ui.request.complete clientId=${client.id} success=${result.isSuccess} elapsedMs=${SystemClock.elapsedRealtime() - started} keys=${result.getOrNull()?.keys?.sorted()} errorType=${result.exceptionOrNull()?.javaClass?.simpleName}")
                    result.onSuccess { connectionData = it }
                        .onFailure { Log.w("TS3X_STATS", "ui.request.failed clientId=${client.id} reason=${it.message}") }
                    Log.i("TS3X_STATS", "ui.state.updated clientId=${client.id} page=$page loading=$connectionLoading keys=${connectionData.keys.sorted()}")
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    connectionLoading = false
                    Log.e("TS3X_STATS", "ui.request.exception clientId=${client.id}", error)
                }
                // Keep the start-to-start interval at >= 1000 ms, while preventing overlapping requests.
                delay((1000L - (SystemClock.elapsedRealtime() - started)).coerceAtLeast(1L))
            }
        } finally {
            Log.i("TS3X_STATS", "ui.poll.stop clientId=${client.id}")
        }
    }

    fun perform(label: String, action: () -> Result<Unit>) {
        if (busy) return
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { action() }
            busy = false
            message = result.exceptionOrNull()?.let { "$label: ${it.message ?: it.javaClass.simpleName}" }
                ?: "$label: OK"
            if (result.isSuccess) { confirm = ""; page = "main" }
        }
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.width(panelWidth).height(panelHeight),
            shape = RoundedCornerShape(14.dp),
            color = GreenSurface2,
            tonalElevation = 8.dp
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().background(DarkGreen).padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (page != "main") {
                        TextButton(onClick = { page = "main"; message = "" }) {
                            Icon(Icons.Filled.ArrowBack, contentDescription = null, tint = GreenAccent, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.ui_067), color = GreenAccent)
                        }
                    }
                    Text(
                        if (page == "main") client.nickname else when (page) {
                            "volume" -> localizedLabel("Adjust volume", "Lautstärke einstellen", "Регулировать громкость")
                            "info" -> localizedLabel("Connection information", "Verbindungsinformationen", "Информация о подключении")
                            "away" -> localizedLabel("Away status (AFK)", "Abwesenheitsstatus (AFK)", "Состояние отсутствия (AFK)")
                            "servergroups" -> localizedLabel("Server groups", "Servergruppen", "Группы сервера")
                            "channelgroups" -> localizedLabel("Channel groups", "Kanalgruppen", "Группы канала")
                            else -> localizedLabel("Confirmation", "Bestätigung", "Подтверждение")
                        },
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = Color.White
                    )
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, contentDescription = "Закрыть", tint = GreenAccent)
                    }
                }
                if (message.isNotBlank()) {
                    Text(message, color = GreenAccent, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    if (page == "main") {
                        if (isSelf) item {
                            Ts6ActionRow(Icons.Filled.Settings, localizedLabel("Toggle away status", "Abwesenheitsstatus umschalten", "Переключить состояние «Отошёл»"), true) {
                                awayEnabled = backend.isSelfAway()
                                awayText = backend.selfAwayMessage()
                                message = ""
                                page = "away"
                            }
                        }
                        if (!isSelf) item {
                            Ts6ActionRow(
                                Icons.Filled.ChatBubbleOutline,
                                localizedLabel("Messages", "Nachrichten", "Сообщения") +
                                    if (unreadCount > 0) " ($unreadCount)" else ""
                            ) { onClose(); onOpenChat() }
                        }
                        item { Ts6ActionRow(Icons.Filled.TouchApp, localizedLabel("Poke", "Anstupsen", "Толкнуть")) { perform("Poke") { backend.pokeClient(client.id) } } }
                        item { Ts6ActionRow(Icons.Filled.VolumeUp, localizedLabel("Adjust volume", "Lautstärke einstellen", "Регулировать громкость"), true) { page = "volume" } }
                        item {
                            Ts6ActionRow(Icons.Filled.Info, localizedLabel("Connection information", "Verbindungsinformationen", "Информация о подключении"), true) {
                                Log.i("TS3X_STATS", "ui.open clientId=${client.id} page=$page")
                                page = "info"; message = ""; connectionData = emptyMap(); connectionLoading = true
                            }
                        }
                        item { Text(stringResource(R.string.ui_075), color = TextSecondary, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 3.dp)) }
                        item { Ts6ActionRow(Icons.Filled.Groups, localizedLabel("Server groups", "Servergruppen", "Группы сервера"), true) {
                            page = "servergroups"
                            serverGroups = backend.serverGroupSummaries()
                            scope.launch {
                                // Refresh on demand; role catalog notifications can arrive asynchronously.
                                withContext(Dispatchers.IO) { backend.fetchServerGroupIcons() }
                                    .onFailure { Log.w("TS3X_ROLES", "Group metadata refresh failed", it) }
                                delay(350L)
                                serverGroups = backend.serverGroupSummaries()
                                val icons = serverGroups.map { it.iconId }.filter { it != 0L }.distinct()
                                for (iconId in icons) {
                                    val file = serverGroupIconFile(context, serverHost, serverPort, iconId)
                                    if (file.isFile && file.length() > 0L) continue
                                    val saved = withContext(Dispatchers.IO) {
                                        backend.downloadServerGroupIcon(iconId).getOrNull()?.let { bytes ->
                                            if (bytes.isEmpty()) false else runCatching {
                                                file.writeBytes(bytes)
                                                true
                                            }.getOrDefault(false)
                                        } ?: false
                                    }
                                    if (saved) serverGroupIconRevision++
                                }
                            }
                        } }
                        item { Ts6ActionRow(Icons.Filled.GroupWork, localizedLabel("Channel groups", "Kanalgruppen", "Группы канала"), true) {
                            page = "channelgroups"; message = ""
                            channelGroups = backend.channelGroupSummaries(client.id)
                            channelGroupsLoading = true
                            scope.launch {
                                withContext(Dispatchers.IO) { backend.refreshChannelGroupSummaries() }
                                    .onFailure { Log.w("TS3X_GROUPS", "channelgrouplist unavailable", it) }
                                // Allow asynchronous notifychannelgrouplist records to arrive.
                                kotlinx.coroutines.delay(350L)
                                channelGroups = backend.channelGroupSummaries(client.id)
                                channelGroupsLoading = false
                                val iconIds = channelGroups.map { it.iconId }.filter { it != 0L }.distinct()
                                for (iconId in iconIds) {
                                    val file = serverGroupIconFile(context, serverHost, serverPort, iconId)
                                    if (file.isFile && file.length() > 0L) continue
                                    val saved = withContext(Dispatchers.IO) {
                                        val bytes = backend.downloadServerGroupIcon(iconId).getOrNull()
                                        if (bytes == null || bytes.isEmpty()) false else runCatching {
                                            file.writeBytes(bytes)
                                            true
                                        }.getOrDefault(false)
                                    }
                                    if (saved) channelIconRevision++
                                }
                            }
                        } }
                        item { Text(stringResource(R.string.ui_066), color = TextSecondary, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 3.dp)) }
                        item { Ts6ActionRow(Icons.Filled.PersonRemove, localizedLabel("Kick from server", "Vom Server entfernen", "Кикнуть с сервера"), destructive = true) { confirm = "kick"; page = "confirm" } }
                        item { Ts6ActionRow(Icons.Filled.Block, localizedLabel("Ban from server", "Vom Server sperren", "Забанить на сервере"), destructive = true) { confirm = "ban"; page = "confirm" } }
                        item { Ts6ActionRow(Icons.Filled.HideImage, localizedLabel("Remove avatar", "Avatar entfernen", "Удалить аватар"), destructive = true) { confirm = "avatar"; page = "confirm" } }
                    } else if (page == "away" && isSelf) {
                        item {
                            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(stringResource(R.string.ui_070), color = Color.White, modifier = Modifier.weight(1f))
                                    Switch(checked = awayEnabled, onCheckedChange = { awayEnabled = it }, enabled = !busy)
                                }
                                Spacer(Modifier.height(12.dp))
                                OutlinedTextField(
                                    value = awayText,
                                    onValueChange = { if (it.length <= 1024) awayText = it },
                                    label = { Text(stringResource(R.string.ui_077)) },
                                    placeholder = { Text(stringResource(R.string.ui_068)) },
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = !busy,
                                    minLines = 2,
                                    maxLines = 4
                                )
                                Spacer(Modifier.height(12.dp))
                                Button(enabled = !busy, onClick = {
                                    if (busy) return@Button
                                    busy = true
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) {
                                            backend.setSelfAway(awayEnabled, awayText)
                                        }
                                        busy = false
                                        result.onSuccess {
                                            message = if (awayEnabled) "Статус «Отошёл» включён" else "Статус «Отошёл» выключен"
                                            page = "main"
                                        }.onFailure {
                                            message = "AFK: ${it.message ?: it.javaClass.simpleName}"
                                        }
                                    }
                                }) { FittedControlLabel(R.string.ui_073) }
                            }
                        }
                    } else if (page == "volume") {
                        item {
                            Column(Modifier.padding(16.dp)) {
                                Text("${(volume * 100).toInt()}%", color = Color.White)
                                Slider(value = volume, onValueChange = {
                                    volume = it
                                    backend.setClientVolume(client.id, it)
                                }, valueRange = 0f..2f)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("0%", color = TextSecondary)
                                    TextButton(onClick = {
                                        volume = 1f; backend.setClientVolume(client.id, 1f)
                                    }) { Text("100% (Reset)") }
                                    Text("200%", color = TextSecondary)
                                }
                            }
                        }
                    } else if (page == "info") {
                        if (connectionLoading) item { Text(stringResource(R.string.ui_072), color = GreenAccent, modifier = Modifier.padding(12.dp)) }
                        item { Ts6StatisticRow(localizedLabel("Packet loss (incoming)", "Paketverlust (eingehend)", "Потеря пакетов (входящие)"), connectionData["connection_packetloss_s2c_total"]?.toString() ?: localizedLabel("Unavailable", "Nicht verfügbar", "Нет данных")) }
                        item { Ts6StatisticRow(localizedLabel("Packet loss (outgoing)", "Paketverlust (ausgehend)", "Потеря пакетов (исходящие)"), connectionData["connection_packetloss_c2s_total"]?.toString() ?: localizedLabel("Unavailable", "Nicht verfügbar", "Нет данных")) }
                        item { Ts6StatisticRow(localizedLabel("Ping", "Ping", "Пинг"), connectionData["connection_ping"]?.toString() ?: localizedLabel("Unavailable", "Nicht verfügbar", "Нет данных")) }
                        item { Ts6StatisticRow(localizedLabel("Nickname", "Spitzname", "Никнейм"), connectionData["client_nickname"]?.toString() ?: localizedLabel("Unavailable", "Nicht verfügbar", "Нет данных")) }
                        item { Ts6StatisticRow(localizedLabel("Channel ID", "Kanal-ID", "ID канала"), connectionData["client_channel_id"]?.toString() ?: localizedLabel("Unavailable", "Nicht verfügbar", "Нет данных")) }
                        item { Ts6StatisticRow(localizedLabel("Idle time", "Inaktivitätszeit", "Время простоя"), connectionData["client_idle_time"]?.toString() ?: localizedLabel("Unavailable", "Nicht verfügbar", "Нет данных")) }
                        item { Ts6StatisticRow(localizedLabel("Connected time", "Verbindungsdauer", "Время подключения"), connectionData["client_connected_time"]?.toString() ?: localizedLabel("Unavailable", "Nicht verfügbar", "Нет данных")) }
                        item { Ts6StatisticRow(localizedLabel("Bytes received", "Bytes empfangen", "Получено байт"), connectionData["connection_bytes_received_total"]?.toString() ?: localizedLabel("Unavailable", "Nicht verfügbar", "Нет данных")) }
                        item { Ts6StatisticRow(localizedLabel("Bytes sent", "Bytes gesendet", "Отправлено байт"), connectionData["connection_bytes_sent_total"]?.toString() ?: localizedLabel("Unavailable", "Nicht verfügbar", "Нет данных")) }
                        item { Ts6StatisticRow(localizedLabel("IP address", "IP-Adresse", "IP-адрес"), connectionData["connection_client_ip"]?.toString() ?: localizedLabel("Access denied", "Zugriff verweigert", "Нет доступа")) }
                    } else if (page == "servergroups") {
                        if (serverGroups.isEmpty()) item {
                            Text(stringResource(R.string.ui_078), modifier = Modifier.padding(12.dp), color = TextSecondary)
                        }
                        items(serverGroups) { group ->
                            val member = group.id in assignedGroupIds
                            Row(modifier = Modifier.fillMaxWidth()
                                .clickable(enabled = !busy) {
                                    val shouldAdd = !member
                                    busy = true
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) {
                                            backend.setServerGroupForClient(client.id, group.id, shouldAdd)
                                        }
                                        busy = false
                                        result.onSuccess {
                                            assignedGroupIds = if (shouldAdd) assignedGroupIds + group.id
                                                else assignedGroupIds - group.id
                                            message = if (shouldAdd) "Группа назначена" else "Группа снята"
                                        }.onFailure { message = "Группа ${group.name}: ${it.message ?: "insufficient permissions"}" }
                                    }
                                }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                val iconFile = remember(group.id, group.iconId, serverHost, serverPort, serverGroupIconRevision) {
                                    group.iconId.takeIf { it != 0L }
                                        ?.let { serverGroupIconFile(context, serverHost, serverPort, it) }
                                        ?.takeIf { it.isFile && it.length() > 0L }
                                }
                                if (iconFile != null) {
                                    AsyncImage(model = iconFile, contentDescription = null,
                                        modifier = Modifier.size(20.dp))
                                } else {
                                    Icon(Icons.Filled.Groups, contentDescription = null,
                                        tint = GreenAccent, modifier = Modifier.size(20.dp))
                                }
                                Spacer(Modifier.width(12.dp))
                                Text(group.name, modifier = Modifier.weight(1f), color = Color.White)
                                Icon(if (member) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                                    contentDescription = if (member) "Назначена" else "Не назначена",
                                    tint = if (member) GreenAccent else TextSecondary,
                                    modifier = Modifier.size(20.dp))
                            }
                        }
                    } else if (page == "channelgroups") {
                        if (channelGroupsLoading && channelGroups.isEmpty()) item {
                            Text(stringResource(R.string.ui_065), modifier = Modifier.padding(12.dp), color = TextSecondary)
                        }
                        if (!channelGroupsLoading && channelGroups.isEmpty()) item {
                            Text(stringResource(R.string.ui_076), modifier = Modifier.padding(12.dp), color = TextSecondary)
                        }
                        items(channelGroups) { group ->
                            val member = group.id == activeChannelGroupId
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .clickable(enabled = !busy) {
                                        busy = true
                                        scope.launch {
                                            val result = withContext(Dispatchers.IO) {
                                                if (member) backend.removeChannelGroupFromClient(client.id)
                                                else backend.setChannelGroupForClient(client.id, group.id)
                                            }
                                            busy = false
                                            result.onSuccess {
                                                activeChannelGroupId = if (member) backend.defaultChannelGroupId() ?: 0L else group.id
                                                message = if (member) "Права канала сняты" else "Группа канала назначена"
                                            }.onFailure {
                                                message = "Группа ${group.name}: ${it.message ?: "insufficient permissions"}"
                                            }
                                        }
                                    }
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val iconFile = remember(group.iconId, channelIconRevision, serverHost, serverPort) {
                                    group.iconId.takeIf { it != 0L }
                                        ?.let { serverGroupIconFile(context, serverHost, serverPort, it) }
                                        ?.takeIf { it.isFile && it.length() > 0L }
                                }
                                if (iconFile != null) {
                                    AsyncImage(model = iconFile, contentDescription = null,
                                        modifier = Modifier.size(20.dp))
                                } else {
                                    // TS6-style built-in channel roles; custom server icons still take priority.
                                    val roleLetter = when (group.name.trim().lowercase(java.util.Locale.ROOT)) {
                                        "channel admin" -> "C"
                                        "operator" -> "O"
                                        "voice" -> "V"
                                        else -> null
                                    }
                                    if (roleLetter != null) {
                                        Box(Modifier.size(20.dp)) {
                                            Icon(Icons.Filled.Person, contentDescription = null,
                                                tint = Color(0xFF555C60), modifier = Modifier.size(17.dp).align(Alignment.CenterStart))
                                            Text(roleLetter, fontSize = 9.sp, lineHeight = 9.sp,
                                                fontWeight = FontWeight.Bold, color = Color(0xFFC52B36),
                                                modifier = Modifier.align(Alignment.BottomEnd))
                                        }
                                    } else {
                                        Icon(Icons.Filled.GroupWork, contentDescription = null,
                                            tint = GreenAccent, modifier = Modifier.size(20.dp))
                                    }
                                }
                                Spacer(Modifier.width(12.dp))
                                Text(group.name, modifier = Modifier.weight(1f), color = Color.White)
                                Icon(if (member) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                                    contentDescription = if (member) "Назначена" else "Не назначена",
                                    tint = if (member) GreenAccent else TextSecondary,
                                    modifier = Modifier.size(20.dp))
                            }
                        }
                    } else if (page == "confirm") {
                        item {
                            Text(when (confirm) {
                                "kick" -> "Кикнуть ${client.nickname} с сервера?"
                                "ban" -> "Забанить ${client.nickname} на сервере?"
                                else -> "Удалить аватар ${client.nickname}?"
                            }, modifier = Modifier.padding(16.dp), color = Color.White)
                        }
                        if (confirm == "ban") item {
                            OutlinedTextField(
                                value = banMinutes, onValueChange = { banMinutes = it },
                                label = { Text(stringResource(R.string.ui_079)) },
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                            )
                        }
                        if (confirm == "ban" || confirm == "kick") item {
                            OutlinedTextField(value = reason, onValueChange = { reason = it },
                                label = { Text(stringResource(R.string.ui_074)) },
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp))
                        }
                        item {
                            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = { page = "main"; confirm = "" }) { FittedControlLabel(R.string.ui_069) }
                                Button(enabled = !busy, onClick = {
                                    when (confirm) {
                                        "kick" -> perform("Kick") { backend.kickClientFromServer(client.id, reason.ifBlank { "Removed by moderator" }) }
                                        "ban" -> {
                                            val minutes = banMinutes.toIntOrNull()
                                            if (minutes == null || minutes < 0 || minutes > 5256000) {
                                                message = "Укажите корректный срок бана"
                                            } else perform("Ban") { backend.banClientFromServer(client.id, minutes * 60, reason) }
                                        }
                                        "avatar" -> perform("Remove avatar") { backend.removeClientAvatar(client.id) }
                                    }
                                }) { FittedControlLabel(R.string.ui_071) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Ts6ActionRow(
    icon: ImageVector,
    title: String,
    submenu: Boolean = false,
    destructive: Boolean = false,
    onClick: () -> Unit
) {
    val actionColor = if (destructive) Color(0xFFFF7777) else TextPrimary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (destructive) actionColor else GreenAccent,
            modifier = Modifier.size(21.dp)
        )
        Spacer(Modifier.width(14.dp))
        Text(
            title,
            color = actionColor,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium
        )
        if (submenu) {
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun ClientMetaBadge(
    serverHost: String,
    serverPort: Int,
    roleIconIds: List<Long>,
    roleIconRevision: Int,
    channelRole: Ts3jBackend.ChannelGroupSummary?,
    flag: String
) {
    val context = LocalContext.current
    val availableRoleIcons = remember(serverHost, serverPort, roleIconIds, roleIconRevision) {
        roleIconIds.mapNotNull { iconId ->
            val file = serverGroupIconFile(context, serverHost, serverPort, iconId)
            if (file.isFile && file.length() > 0L) iconId to file else null
        }
    }
    val channelIconFile = remember(serverHost, serverPort, channelRole, roleIconRevision) {
        channelRole?.iconId?.takeIf { it != 0L }
            ?.let { serverGroupIconFile(context, serverHost, serverPort, it) }
            ?.takeIf { it.isFile && it.length() > 0L }
    }
    if (availableRoleIcons.isEmpty() && flag.isEmpty() && channelRole == null) return

    // TS6-like single capsule: role icons | country flag.
    Surface(
        color = Color(0xFF24303A).copy(alpha = 0.96f),
        shape = RoundedCornerShape(7.dp),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            availableRoleIcons.forEach { (iconId, file) ->
                AsyncImage(
                    model = file,
                    contentDescription = "Server role icon $iconId",
                    modifier = Modifier.size(16.dp),
                    contentScale = ContentScale.Fit
                )
            }
            if (channelRole != null) {
                if (channelIconFile != null) {
                    AsyncImage(model = channelIconFile,
                        contentDescription = "Channel group ${channelRole.name}",
                        modifier = Modifier.size(16.dp), contentScale = ContentScale.Fit)
                } else {
                    val glyph = when (channelRole.name.trim().lowercase(java.util.Locale.ROOT)) {
                        "channel admin" -> "C"
                        "operator" -> "O"
                        "voice" -> "V"
                        else -> null
                    }
                    Box(modifier = Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Person, null, tint = Color(0xFF646C73),
                            modifier = Modifier.size(15.dp))
                        if (glyph != null) Text(glyph, color = Color(0xFFC52B36),
                            fontWeight = FontWeight.Bold, fontSize = 8.sp,
                            lineHeight = 8.sp, modifier = Modifier.align(Alignment.BottomEnd))
                    }
                }
            }
            if ((availableRoleIcons.isNotEmpty() || channelRole != null) && flag.isNotEmpty()) {
                Box(
                    modifier = Modifier.width(1.dp).height(14.dp)
                        .background(Color.White.copy(alpha = 0.16f))
                )
            }
            if (flag.isNotEmpty()) {
                Text(flag, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

private fun flattenChannels(
    channels: List<BackendChannel>,
    collapsed: Set<Long>
): List<Pair<BackendChannel, Int>> {
    val byId = channels.associateBy { it.id }
    val nodes = channels.map {
        com.morsen.ts3xclient.protocol.TeamSpeakChannelTree.Node(it.id, it.parentId, it.order, it.firstSeen)
    }
    return com.morsen.ts3xclient.protocol.TeamSpeakChannelTree.flatten(nodes, collapsed).mapNotNull {
        entry -> byId[entry.id]?.let { it to entry.depth }
    }
}

private enum class SpacerStyle {
    BLANK,
    LEFT,
    CENTER,
    RIGHT,
    REPEAT
}

private data class SpacerInfo(
    val style: SpacerStyle,
    val content: String
)

/**
 * TS3 spacer syntax:
 * [spacerX]       blank spacer
 * [lspacerX]Text  left-aligned label
 * [cspacerX]Text  centered label
 * [rspacerX]Text  right-aligned label
 * [*spacerX]_     repeat "_" across the row
 *
 * TeamSpeak treats spacers as normal channels at protocol level; this is purely
 * a client-side presentation rule.
 */
private val spacerNamePattern = Regex(
    """^\[(\*|l|c|r)?spacer[^\]]*\](.*)$""", RegexOption.IGNORE_CASE
)

private fun parseSpacer(channel: BackendChannel): SpacerInfo? {
    val match = spacerNamePattern.matchEntire(channel.name) ?: return null

    val marker = match.groupValues[1].lowercase()
    val payload = match.groupValues[2]

    return when (marker) {
        "*" -> SpacerInfo(
            SpacerStyle.REPEAT,
            payload.ifEmpty { "─" }
        )
        "c" -> SpacerInfo(SpacerStyle.CENTER, payload)
        "r" -> SpacerInfo(SpacerStyle.RIGHT, payload)
        "l" -> SpacerInfo(SpacerStyle.LEFT, payload)
        else -> if (payload.isBlank()) {
            SpacerInfo(SpacerStyle.BLANK, "")
        } else {
            SpacerInfo(SpacerStyle.LEFT, payload)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SpacerRow(
    channel: BackendChannel,
    spacer: SpacerInfo,
    depth: Int,
    onOpen: (() -> Unit)? = null,
    onDoubleClick: (() -> Unit)? = null
) {
    val clickableModifier = Modifier
        .fillMaxWidth()
        .then(
            if (onOpen != null) {
                Modifier.combinedClickable(onClick = onOpen, onDoubleClick = onDoubleClick)
            } else {
                Modifier
            }
        )

    when (spacer.style) {
        SpacerStyle.BLANK -> {
            Box(
                modifier = clickableModifier
                    .height(18.dp)
                    .padding(
                        start =
                            (8 + depth * 18).dp,
                        end = 8.dp
                    )
            ) {
                HorizontalDivider(
                    color =
                        TextSecondary.copy(
                            alpha = 0.12f
                        ),
                    modifier =
                        Modifier.align(
                            Alignment.Center
                        )
                )
            }
        }

        SpacerStyle.REPEAT -> {
            val token =
                spacer.content.ifEmpty { "─" }

            val repeated = buildString {
                while (length < 160) {
                    append(token)
                }
            }

            Text(
                text = repeated,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                modifier = clickableModifier
                    .padding(
                        start =
                            (8 + depth * 18).dp,
                        end = 8.dp,
                        top = 7.dp,
                        bottom = 7.dp
                    )
            )
        }

        SpacerStyle.LEFT,
        SpacerStyle.CENTER,
        SpacerStyle.RIGHT -> {
            val alignment = when (
                spacer.style
            ) {
                SpacerStyle.CENTER ->
                    androidx.compose.ui.text.style
                        .TextAlign.Center
                SpacerStyle.RIGHT ->
                    androidx.compose.ui.text.style
                        .TextAlign.End
                else ->
                    androidx.compose.ui.text.style
                        .TextAlign.Start
            }

            val visibleText =
                spacer.content.ifBlank {
                    if (
                        spacer.style ==
                        SpacerStyle.CENTER
                    ) {
                        "ⓘ"
                    } else {
                        " "
                    }
                } +
                    if (
                        spacer.style ==
                            SpacerStyle.CENTER &&
                        spacer.content
                            .isNotBlank()
                    ) {
                        "  ⓘ"
                    } else {
                        ""
                    }

            Text(
                text = visibleText,
                color = if (
                    spacer.content.isBlank()
                ) {
                    TextSecondary.copy(
                        alpha = 0.55f
                    )
                } else {
                    TextSecondary
                },
                fontWeight =
                    FontWeight.SemiBold,
                textAlign = alignment,
                modifier = clickableModifier
                    .heightIn(min = 34.dp)
                    .padding(
                        start =
                            (8 + depth * 18).dp,
                        end = 8.dp,
                        top = 7.dp,
                        bottom = 7.dp
                    )
            )
        }
    }
}


private val bbCodeImagePattern = Regex(
    """\[img(?:=[^\]]+)?\](https?://[^\[]+)\[/img\]""", RegexOption.IGNORE_CASE
)
private val htmlImagePattern = Regex(
    """<img[^>]+src=["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE
)

private fun channelImageUrl(channel: BackendChannel): String? {
    val banner = channel.bannerUrl.trim()
        .replace("&amp;", "&", ignoreCase = true)
        .replace("&#38;", "&")
        .replace("&quot;", "\"")
    if (banner.startsWith("http://", true) || banner.startsWith("https://", true)) return banner
    val description = channel.description
    if (description.isBlank()) return null
    val url = bbCodeImagePattern.find(description)?.groupValues?.getOrNull(1)
        ?: htmlImagePattern.find(description)?.groupValues?.getOrNull(1)
    return url?.trim()?.replace("&amp;", "&", ignoreCase = true)
}

private fun sanitizeServerMessage(
    raw: String
): String {
    var text =
        raw.replace(
            "\r\n",
            "\n"
        )
            .replace(
                "\r",
                "\n"
            )

    if (
        text
            .trimStart()
            .startsWith(
                "null,",
                ignoreCase = true
            )
    ) {
        val leadingWhitespace =
            text.takeWhile {
                it == ' ' ||
                    it == '\t' ||
                    it == '\n'
            }

        text =
            leadingWhitespace +
                text
                    .trimStart()
                    .substringAfter(",")
                    .trimStart(
                        ' ',
                        '\t'
                    )
    }

    text =
        text.replace(
            Regex(
                """(?i)^\s*virtualserver_hostmessage_mode\s*=\s*\d+[ \t]*"""
            ),
            ""
        )

    return text
}

private sealed interface Ts3BbBlock {
    data class TextBlock(
        val text: AnnotatedString,
        val align: TextAlign =
            TextAlign.Start
    ) : Ts3BbBlock

    data class ImageBlock(
        val imageUrl: String,
        val openUrl: String? = null,
        val align: TextAlign =
            TextAlign.Center
    ) : Ts3BbBlock

    data object HorizontalRule :
        Ts3BbBlock
}

@Composable
private fun Ts3BbCodeContent(
    text: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    fun openUrl(rawUrl: String) {
        val normalized =
            normalizeClickableTs3Url(
                rawUrl
            )

        if (normalized.isBlank()) {
            return
        }

        runCatching {
            val intent =
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse(normalized)
                )
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK
                    )

            context.startActivity(intent)
        }
    }

    val blocks =
        remember(text) {
            parseTs3BbBlocks(text)
        }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement =
            Arrangement.spacedBy(6.dp)
    ) {
        blocks.forEach { block ->
            when (block) {
                is Ts3BbBlock.TextBlock -> {
                    ClickableText(
                        text = block.text,
                        style =
                            MaterialTheme
                                .typography
                                .bodyMedium
                                .copy(
                                    color =
                                        TextPrimary,
                                    textAlign =
                                        block.align
                                ),
                        modifier =
                            Modifier.fillMaxWidth(),
                        onClick = { offset ->
                            block.text
                                .getStringAnnotations(
                                    tag = "URL",
                                    start = offset,
                                    end =
                                        (
                                            offset + 1
                                        ).coerceAtMost(
                                            block.text.length
                                        )
                                )
                                .firstOrNull()
                                ?.item
                                ?.let {
                                    normalizeClickableTs3Url(
                                        it
                                    )
                                }
                                ?.let(
                                    ::openUrl
                                )
                        }
                    )
                }

                is Ts3BbBlock.ImageBlock -> {
                    val image =
                        normalizeServerImageUrl(
                            block.imageUrl
                        )

                    val clickable =
                        block.openUrl
                            ?.let(
                                ::normalizeClickableTs3Url
                            )

                    if (image != null) {
                        Box(
                            modifier =
                                Modifier.fillMaxWidth(),
                            contentAlignment =
                                when (
                                    block.align
                                ) {
                                    TextAlign.Start ->
                                        Alignment.CenterStart
                                    TextAlign.End ->
                                        Alignment.CenterEnd
                                    else ->
                                        Alignment.Center
                                }
                        ) {
                            AsyncImage(
                                model = image,
                                contentDescription =
                                    "TeamSpeak image",
                                contentScale =
                                    ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(
                                        min = 48.dp,
                                        max = 260.dp
                                    )
                                    .clip(
                                        RoundedCornerShape(
                                            8.dp
                                        )
                                    )
                                    .then(
                                        if (
                                            clickable !=
                                            null
                                        ) {
                                            Modifier
                                                .clickable {
                                                    openUrl(
                                                        clickable
                                                    )
                                                }
                                        } else {
                                            Modifier
                                        }
                                    )
                            )
                        }
                    } else {
                        val label =
                            buildAnnotatedString {
                                pushStringAnnotation(
                                    tag = "URL",
                                    annotation =
                                        clickable
                                            ?: block.imageUrl
                                )
                                pushStyle(
                                    SpanStyle(
                                        color =
                                            GreenAccent,
                                        textDecoration =
                                            TextDecoration
                                                .Underline
                                    )
                                )
                                append(
                                    block.imageUrl
                                )
                                pop()
                                pop()
                            }

                        ClickableText(
                            text = label,
                            style =
                                MaterialTheme
                                    .typography
                                    .bodySmall
                                    .copy(
                                        textAlign =
                                            block.align
                                    ),
                            modifier =
                                Modifier.fillMaxWidth(),
                            onClick = {
                                openUrl(
                                    clickable
                                        ?: block.imageUrl
                                )
                            }
                        )
                    }
                }

                Ts3BbBlock.HorizontalRule -> {
                    HorizontalDivider(
                        color =
                            TextSecondary.copy(
                                alpha = 0.35f
                            )
                    )
                }
            }
        }
    }
}

private fun parseTs3BbBlocks(
    source: String
): List<Ts3BbBlock> {
    var text =
        source
            .replace(
                Regex(
                    """\[br\s*/?\]""",
                    RegexOption.IGNORE_CASE
                ),
                "\n"
            )

    // Numeric/alphabetic and bullet lists documented by TeamSpeak.
    val listRegex =
        Regex(
            """\[list(?:=([1aA]))?\]([\s\S]*?)\[/list\]""",
            RegexOption.IGNORE_CASE
        )

    text = listRegex.replace(text) { match ->
        val mode =
            match.groupValues
                .getOrNull(1)
                .orEmpty()

        val body =
            match.groupValues[2]

        val items =
            body.split(
                Regex(
                    """\[\*\]""",
                    RegexOption.IGNORE_CASE
                )
            )
                .dropWhile {
                    it.isBlank()
                }
                .map {
                    it.trim(
                        ' ',
                        '\t'
                    )
                }
                .filter {
                    it.isNotBlank()
                }

        items.mapIndexed {
            index,
            item ->
            val prefix =
                when (mode) {
                    "1" ->
                        "${index + 1}. "

                    "A" ->
                        "${('A'.code + index).toChar()}. "

                    "a" ->
                        "${('a'.code + index).toChar()}. "

                    else ->
                        "• "
                }

            prefix + item
        }.joinToString("\n")
    }

    val result =
        mutableListOf<Ts3BbBlock>()

    // Parse URL-wrapped images first so clicking an image follows the outer link.
    val specialRegex =
        Regex(
            """(?is)\[url(?:=([^\]]+))?\]\s*\[img(?:=[^\]]+)?\](.*?)\[/img\]\s*\[/url\]|\[img(?:=[^\]]+)?\](.*?)\[/img\]|\[hr\s*/?\]"""
        )

    var cursor = 0

    specialRegex.findAll(text)
        .forEach { match ->
            if (
                match.range.first >
                cursor
            ) {
                appendTs3TextBlocks(
                    text.substring(
                        cursor,
                        match.range.first
                    ),
                    result
                )
            }

            val raw =
                match.value

            if (
                raw.startsWith(
                    "[hr",
                    ignoreCase = true
                )
            ) {
                result +=
                    Ts3BbBlock.HorizontalRule
            } else {
                val wrappedTarget =
                    match.groupValues
                        .getOrNull(1)
                        .orEmpty()
                        .trim()
                        .trim(
                            '"',
                            '\''
                        )

                val wrappedImage =
                    match.groupValues
                        .getOrNull(2)
                        .orEmpty()
                        .trim()

                val plainImage =
                    match.groupValues
                        .getOrNull(3)
                        .orEmpty()
                        .trim()

                val imageUrl =
                    wrappedImage.ifBlank {
                        plainImage
                    }

                if (imageUrl.isNotBlank()) {
                    result +=
                        Ts3BbBlock.ImageBlock(
                            imageUrl =
                                imageUrl,
                            openUrl =
                                wrappedTarget
                                    .takeIf {
                                        it.isNotBlank()
                                    }
                        )
                }
            }

            cursor =
                match.range.last + 1
        }

    if (cursor < text.length) {
        appendTs3TextBlocks(
            text.substring(cursor),
            result
        )
    }

    return result.ifEmpty {
        listOf(
            Ts3BbBlock.TextBlock(
                AnnotatedString("")
            )
        )
    }
}

private fun appendTs3TextBlocks(
    source: String,
    output: MutableList<Ts3BbBlock>
) {
    // Alignment is a block-level TeamSpeak BBCode feature.
    val alignmentRegex =
        Regex(
            """(?is)\[(left|center|right)\](.*?)\[/\1\]"""
        )

    var cursor = 0

    alignmentRegex.findAll(source)
        .forEach { match ->
            if (
                match.range.first >
                cursor
            ) {
                val before =
                    source.substring(
                        cursor,
                        match.range.first
                    )

                appendTs3PlainTextBlock(
                    before,
                    TextAlign.Start,
                    output
                )
            }

            val align =
                when (
                    match.groupValues[1]
                        .lowercase()
                ) {
                    "center" ->
                        TextAlign.Center

                    "right" ->
                        TextAlign.End

                    else ->
                        TextAlign.Start
                }

            appendTs3PlainTextBlock(
                match.groupValues[2],
                align,
                output
            )

            cursor =
                match.range.last + 1
        }

    if (cursor < source.length) {
        appendTs3PlainTextBlock(
            source.substring(cursor),
            TextAlign.Start,
            output
        )
    }
}

private fun appendTs3PlainTextBlock(
    source: String,
    align: TextAlign,
    output: MutableList<Ts3BbBlock>
) {
    if (source.isEmpty()) {
        return
    }

    // Preserve whitespace exactly. In particular, do not trim leading/trailing
    // newline characters here: TeamSpeak welcome messages commonly use blank
    // lines around styled blocks, and those newlines are part of the message
    // layout rather than disposable padding.
    output +=
        Ts3BbBlock.TextBlock(
            text =
                parseTs3InlineBbCode(
                    source
                ),
            align = align
        )
}

private val TS3_AUTO_LINK_REGEX =
    Regex(
        """(?i)(?:https?://|www\.|ts3server://|mailto:)[^\s<>\[\]]+"""
    )

private val TS3_MARKDOWN_INLINE_REGEX =
    Regex(
        """(?s)(\*\*(.+?)\*\*|__(.+?)__|~~(.+?)~~|\*(?!\*)(.+?)(?<!\*)\*|_(?!_)(.+?)(?<!_)_)"""
    )

private fun AnnotatedString.Builder.appendTs3MarkdownText(
    raw: String
) {
    var cursor = 0

    TS3_MARKDOWN_INLINE_REGEX
        .findAll(raw)
        .forEach {
            match ->

            if (
                match.range.first >
                cursor
            ) {
                append(
                    raw.substring(
                        cursor,
                        match.range.first
                    )
                )
            }

            val token =
                match.value

            val styledText =
                when {
                    token.startsWith(
                        "**"
                    ) &&
                        token.endsWith(
                            "**"
                        ) ->
                        token.substring(
                            2,
                            token.length - 2
                        )

                    token.startsWith(
                        "__"
                    ) &&
                        token.endsWith(
                            "__"
                        ) ->
                        token.substring(
                            2,
                            token.length - 2
                        )

                    token.startsWith(
                        "~~"
                    ) &&
                        token.endsWith(
                            "~~"
                        ) ->
                        token.substring(
                            2,
                            token.length - 2
                        )

                    else ->
                        token.substring(
                            1,
                            token.length - 1
                        )
                }

            val style =
                when {
                    token.startsWith(
                        "**"
                    ) ||
                        token.startsWith(
                            "__"
                        ) ->
                        SpanStyle(
                            fontWeight =
                                FontWeight.Bold
                        )

                    token.startsWith(
                        "~~"
                    ) ->
                        SpanStyle(
                            textDecoration =
                                TextDecoration.LineThrough
                        )

                    else ->
                        SpanStyle(
                            fontStyle =
                                FontStyle.Italic
                        )
                }

            pushStyle(
                style
            )

            // Re-enter the URL-aware path so links inside styled text keep
            // their clickable annotation while inheriting this style.
            appendTs3AutoLinked(
                styledText
            )

            pop()

            cursor =
                match.range.last + 1
        }

    if (
        cursor <
        raw.length
    ) {
        append(
            raw.substring(
                cursor
            )
        )
    }
}

private fun AnnotatedString.Builder.appendTs3AutoLinked(
    raw: String
) {
    var cursor = 0

    TS3_AUTO_LINK_REGEX
        .findAll(raw)
        .forEach { match ->
            if (
                match.range.first >
                cursor
            ) {
                appendTs3MarkdownText(
                    raw.substring(
                        cursor,
                        match.range.first
                    )
                )
            }

            val visible =
                match.value
                    .trimEnd(
                        '.',
                        ',',
                        ';',
                        ':',
                        '!',
                        '?',
                        ')'
                    )

            val trailing =
                match.value
                    .substring(
                        visible.length
                    )

            val target =
                normalizeClickableTs3Url(
                    visible
                )

            pushStringAnnotation(
                tag = "URL",
                annotation = target
            )
            pushStyle(
                SpanStyle(
                    color = GreenAccent,
                    textDecoration =
                        TextDecoration.Underline
                )
            )
            append(visible)
            pop()
            pop()

            appendTs3MarkdownText(
                trailing
            )

            cursor =
                match.range.last + 1
        }

    if (cursor < raw.length) {
        appendTs3MarkdownText(
            raw.substring(cursor)
        )
    }
}

private fun parseTs3InlineBbCode(
    source: String
): AnnotatedString {
    // [url]https://...[/url] means the text itself is the target.
    val text =
        Regex(
            """(?is)\[url\]([^\[]+)\[/url\]"""
        ).replace(source) {
            match ->
            val target =
                match.groupValues[1]
                    .trim()

            "[url=$target]$target[/url]"
        }

    data class OpenTag(
        val name: String,
        val hasStyle: Boolean,
        val hasAnnotation: Boolean
    )

    val tagRegex =
        Regex(
            """\[(\/?)(b|i|u|s|color|url|size|font|noparse)(?:=([^\]]+))?\]""",
            RegexOption.IGNORE_CASE
        )

    return buildAnnotatedString {
        val stack =
            mutableListOf<OpenTag>()

        var cursor = 0

        tagRegex.findAll(text)
            .forEach { match ->
                if (
                    match.range.first >
                    cursor
                ) {
                    appendTs3AutoLinked(
                        text.substring(
                            cursor,
                            match.range.first
                        )
                    )
                }

                val closing =
                    match.groupValues[1] ==
                        "/"

                val name =
                    match.groupValues[2]
                        .lowercase()

                val argument =
                    match.groupValues
                        .getOrNull(3)
                        .orEmpty()
                        .trim()
                        .trim(
                            '"',
                            '\''
                        )

                if (!closing) {
                    var style:
                        SpanStyle? = null
                    var annotation:
                        String? = null

                    when (name) {
                        "b" ->
                            style =
                                SpanStyle(
                                    fontWeight =
                                        FontWeight.Bold
                                )

                        "i" ->
                            style =
                                SpanStyle(
                                    fontStyle =
                                        FontStyle.Italic
                                )

                        "u" ->
                            style =
                                SpanStyle(
                                    textDecoration =
                                        TextDecoration
                                            .Underline
                                )

                        "s" ->
                            style =
                                SpanStyle(
                                    textDecoration =
                                        TextDecoration
                                            .LineThrough
                                )

                        "color" ->
                            style =
                                parseTs3BbColor(
                                    argument
                                )?.let {
                                    SpanStyle(
                                        color = it
                                    )
                                }

                        "url" -> {
                            annotation =
                                normalizeClickableTs3Url(
                                    argument
                                )

                            style =
                                SpanStyle(
                                    color =
                                        GreenAccent,
                                    textDecoration =
                                        TextDecoration
                                            .Underline
                                )
                        }

                        "size" ->
                            style =
                                parseTs3BbSize(
                                    argument
                                )?.let {
                                    SpanStyle(
                                        fontSize = it.sp
                                    )
                                }

                        // TeamSpeak has historically accepted [font] in some
                        // text surfaces. Android cannot safely reproduce
                        // arbitrary server-specified font families, so consume
                        // the tag while preserving its content.
                        "font",
                        "noparse" -> Unit
                    }

                    if (annotation != null) {
                        pushStringAnnotation(
                            tag = "URL",
                            annotation =
                                annotation
                        )
                    }

                    if (style != null) {
                        pushStyle(style)
                    }

                    stack +=
                        OpenTag(
                            name = name,
                            hasStyle =
                                style != null,
                            hasAnnotation =
                                annotation != null
                        )
                } else {
                    val top =
                        stack.lastOrNull()

                    if (
                        top != null &&
                        top.name == name
                    ) {
                        stack.removeAt(
                            stack.lastIndex
                        )

                        if (top.hasStyle) {
                            pop()
                        }

                        if (
                            top.hasAnnotation
                        ) {
                            pop()
                        }
                    }
                }

                cursor =
                    match.range.last + 1
            }

        if (cursor < text.length) {
            appendTs3AutoLinked(
                text.substring(cursor)
            )
        }

        while (stack.isNotEmpty()) {
            val tag =
                stack.removeAt(
                    stack.lastIndex
                )

            if (tag.hasStyle) {
                pop()
            }

            if (tag.hasAnnotation) {
                pop()
            }
        }
    }
}

private fun parseTs3BbSize(
    value: String
): Float? {
    val normalized =
        value.trim()

    if (normalized.isBlank()) {
        return null
    }

    if (
        normalized.startsWith("+") ||
        normalized.startsWith("-")
    ) {
        val delta =
            normalized.toIntOrNull()
                ?: return null

        return (
            16f +
                delta.coerceIn(
                    -6,
                    12
                ) * 2f
            )
            .coerceIn(
                8f,
                40f
            )
    }

    val direct =
        normalized
            .removeSuffix("px")
            .removeSuffix("pt")
            .toFloatOrNull()
            ?: return null

    // TeamSpeak size values are not CSS pixels, but clamping to a readable
    // Compose text size preserves the intended relative emphasis.
    return direct.coerceIn(
        8f,
        48f
    )
}

private fun normalizeClickableTs3Url(
    raw: String
): String {
    val value =
        raw.trim()
            .trim(
                '"',
                '\''
            )

    if (value.isBlank()) {
        return value
    }

    return when {
        value.startsWith(
            "http://",
            ignoreCase = true
        ) ||
            value.startsWith(
                "https://",
                ignoreCase = true
            ) ||
            value.startsWith(
                "ts3server://",
                ignoreCase = true
            ) ||
            value.startsWith(
                "mailto:",
                ignoreCase = true
            ) ->
            value

        value.startsWith("//") ->
            "https:$value"

        else ->
            "https://$value"
    }
}

private fun chooseServerBannerImageUrl(
    gfxUrl: String,
    hostBannerUrl: String
): String? {
    normalizeServerImageUrl(
        gfxUrl
    )?.let {
        return it
    }

    // Some TeamSpeak server configurations expose the visible graphic through
    // VIRTUALSERVER_HOSTBANNER_URL. Accept it directly as the second source.
    // If it is not an image resource Coil simply fails that image request
    // without affecting the rest of ServerInfo.
    return normalizeServerImageUrl(
        hostBannerUrl
    )
}

private fun parseTs3BbColor(
    value: String
): Color? {
    val normalized =
        value.trim().lowercase()

    val named =
        mapOf(
            "black" to Color(0xFF000000),
            "white" to Color(0xFFFFFFFF),
            "red" to Color(0xFFFF3B30),
            "green" to Color(0xFF34C759),
            "blue" to Color(0xFF0A84FF),
            "yellow" to Color(0xFFFFD60A),
            "orange" to Color(0xFFFF9F0A),
            "purple" to Color(0xFFBF5AF2),
            "pink" to Color(0xFFFF375F),
            "cyan" to Color(0xFF64D2FF),
            "aqua" to Color(0xFF64D2FF),
            "lime" to Color(0xFF32D74B),
            "gray" to Color(0xFF8E8E93),
            "grey" to Color(0xFF8E8E93),
            "silver" to Color(0xFFC7C7CC),
            "maroon" to Color(0xFF800000),
            "navy" to Color(0xFF000080),
            "teal" to Color(0xFF008080),
            "olive" to Color(0xFF808000),
            "fuchsia" to Color(0xFFFF00FF)
        )

    named[normalized]
        ?.let {
            return it
        }

    val hex =
        normalized.removePrefix("#")

    return runCatching {
        when (hex.length) {
            3 -> {
                val r =
                    hex[0].digitToInt(16)
                val g =
                    hex[1].digitToInt(16)
                val blue =
                    hex[2].digitToInt(16)

                Color(
                    red =
                        (r * 17) / 255f,
                    green =
                        (g * 17) / 255f,
                    blue =
                        (blue * 17) / 255f
                )
            }

            6 ->
                Color(
                    0xFF000000L or
                        hex.toLong(16)
                )

            8 ->
                Color(
                    hex.toLong(16)
                )

            else -> null
        }
    }.getOrNull()
}

private fun stripTs3BbCode(text: String): String =
    text
        .replace(Regex("""\[/?[a-zA-Z]+(?:=[^\]]+)?\]"""), "")
        .replace(Regex("""<[^>]+>"""), "")
        .trim()
