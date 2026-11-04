package com.morsen.ts3xclient

import android.content.Context
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.util.Log
import com.morsen.ts3xclient.protocol.ServerProtocolProfile
import com.morsen.ts3xclient.protocol.TeamSpeakFileTransfers
import com.morsen.ts3xclient.protocol.TeamSpeakClientLookup
import com.morsen.ts3xclient.protocol.TeamSpeakIdentity
import com.morsen.ts3xclient.protocol.TeamSpeakDisconnect
import com.morsen.ts3xclient.protocol.TeamSpeakSessionTasks
import com.morsen.ts3xclient.protocol.TeamSpeakCredentials
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.PrintStream
import java.io.File
import java.io.ByteArrayOutputStream
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

// Visible on the TeamSpeak server, but hidden inside the Android client.
private const val MOBILE_NICKNAME_MARKER = "📱︎ "

private fun localNickname(value: String): String =
    value.removePrefix(MOBILE_NICKNAME_MARKER)

private fun serverNickname(value: String): String =
    MOBILE_NICKNAME_MARKER + localNickname(value)

data class BackendChannel(
    val id: Long,
    val parentId: Long = 0L,
    val order: Long = 0L,
    val name: String = "",
    val topic: String = "",
    val description: String = "",
    val iconId: Long = 0L,
    val bannerUrl: String = "",
    val hasPassword: Boolean = false,
    val permanent: Boolean = false,
    val firstSeen: Long = 0L
)

data class BackendClient(
    val id: Int,
    val channelId: Long,
    val nickname: String,
    val inputMuted: Boolean = false,
    val uniqueId: String = "",
    val isServerQuery: Boolean = false,
    val countryCode: String = "",
    val serverGroupIds: List<Long> = emptyList(),
    // null means omitted metadata; an empty string or "0" means no avatar.
    val avatarHash: String? = null,
    // myTeamSpeak account artwork is independent of the server's avatar file/hash.
    val accountAvatarUrl: String? = null,
    val accountAvatarKnown: Boolean = false,
    val channelGroupId: Long = 0L,
    val databaseId: Long = 0L,
    val isAway: Boolean = false,
    val awayMessage: String = ""
)

data class BackendClientEvent(
    val eventName: String,
    val client: BackendClient
)

data class BackendServerInfo(
    val name: String = "",
    val uniqueIdentifier: String = "",
    val platform: String = "",
    val version: String = "",
    val status: String = "",
    val serverIp: String = "",
    val port: Int? = null,
    val createdEpochSeconds: Long? = null,
    val uptimeSeconds: Long? = null,
    val maxClients: Int? = null,
    val clientsOnline: Int? = null,
    val channelsOnline: Int? = null,
    val queryClientsOnline: Int? = null,
    val reservedSlots: Int? = null,
    val passwordProtected: Boolean? = null,
    val iconId: Long? = null,
    val welcomeMessage: String = "",
    val hostMessage: String = "",
    val bannerImageUrl: String = "",
    val bannerLinkUrl: String = "",
    val hostButtonImageUrl: String = "",
    val hostButtonLinkUrl: String = "",
    val hostButtonTooltip: String = "",
    val maxDownloadBytesPerSecond: Long? = null,
    val maxUploadBytesPerSecond: Long? = null,
    val totalBytesDownloaded: Long? = null,
    val totalBytesUploaded: Long? = null
)

data class IdentityInfo(
    val saved: Boolean,
    val clientUid: String = "",
    val publicKey: String = "",
    val securityLevel: Int? = null,
    val exportedIdentity: String = "",
    val loadError: String? = null,
    val backupAvailable: Boolean = false
)

class Ts3jBackend(
    context: Context,
    private val enableAudio: Boolean = true,
    private val metadataOnly: Boolean = false
) {
    private val appContext = context.applicationContext
    private val diagnosticReady = ConnectionDiagnostics.initialize(appContext)
    private val sessionTasks = TeamSpeakSessionTasks()
    private data class RawEventAccessors(val map: java.lang.reflect.Method?, val getters: List<java.lang.reflect.Method>)
    private val eventAccessors = ConcurrentHashMap<Class<*>, RawEventAccessors>()
    private data class AvatarMetadataStamp(val uid: String, val flag: String?, val accountUrl: String?)
    private val checkedAvatarMetadata = ConcurrentHashMap<Int, AvatarMetadataStamp>()
    private fun avatarMetadataStamp(client: BackendClient) =
        AvatarMetadataStamp(client.uniqueId, client.avatarHash, client.accountAvatarUrl)
    private val lastSuccessfulProbeMs = AtomicLong(android.os.SystemClock.elapsedRealtime())
    private val prefs = appContext.getSharedPreferences("ts3j_identity", Context.MODE_PRIVATE)
    private val chatHistory = ChatHistoryStore(appContext)
    private val chatSessionId = java.util.UUID.randomUUID().toString()
    @Volatile private var chatListener: ((String) -> Unit)? = null
    @Volatile private var openChatKey: String? = null

    /** Chat callbacks may arrive off the UI thread. Store before notifying Compose. */
    fun setChatListener(listener: ((String) -> Unit)?) { chatListener = listener }
    fun setOpenChat(key: String?) {
        openChatKey = key
        if (key != null && connected) chatHistory.markRead(chatServerKey(), key)
    }
    private fun chatServerKey(): String {
        // The persisted identity is stable throughout a connection. Avoid using an
        // asynchronously discovered UID, which could change the history key mid-session.
        val identity = prefs.getString("identity_export", null)?.let { value ->
                java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.toByteArray(Charsets.UTF_8))
                    .take(12).joinToString("") { "%02x".format(it) }
            } ?: observedClientUid?.takeIf { it.isNotBlank() } ?: "local"
        return "${connectedHost.trim().lowercase(java.util.Locale.ROOT)}:$connectedPort/$identity"
    }
    fun chatMessages(key: String): List<BackendChatMessage> =
        if (connected) chatHistory.list(chatServerKey(), key) else emptyList()
    fun unreadChatMessages(key: String): Int =
        if (connected) chatHistory.unread(chatServerKey(), key) else 0
    fun privateChatKey(client: BackendClient): String =
        "private:" + client.uniqueId.takeIf { it.isNotBlank() }
            .orEmpty().ifBlank { "$chatSessionId:${client.id}" }
    fun channelChatKey(id: Long): String = "channel:$id"
    fun privateChatRecipient(key: String, rememberedId: Int? = null): Int? {
        if (!key.startsWith("private:")) return null
        val online = clientCache.values.firstOrNull { privateChatKey(it) == key }
        return online?.id ?: rememberedId?.takeIf { id ->
            clientCache[id]?.let { privateChatKey(it) == key } == true
        }
    }

    /** TS3 channel/private messages are delivered only to online clients, not via myTeamSpeak. */
    fun sendChatMessage(key: String, message: String, targetClientId: Int? = null): Result<Unit> = runCatching {
        check(connected) { "Not connected to a TeamSpeak server" }
        val text = message.trim()
        check(text.isNotEmpty()) { "Message is empty" }
        check(text.toByteArray(Charsets.UTF_8).size <= 8192) { "Message exceeds 8192 UTF-8 bytes" }
        val socket = socketRef.get() ?: error("No active TeamSpeak socket")
        // Call ts3j's public client API: it selects the actual virtual-server ID
        // and applies TeamSpeak command escaping / server acknowledgement rules.
        when {
            key.startsWith("channel:") -> {
                val id = key.removePrefix("channel:").toLongOrNull() ?: error("Invalid channel")
                check(id == currentChannelId) { "Join the channel to send a message" }
                check(id in 1..Int.MAX_VALUE.toLong()) { "Invalid channel ID" }
                socket.javaClass.getMethod("sendChannelMessage", Int::class.javaPrimitiveType, String::class.java)
                    .invoke(socket, id.toInt(), text)
            }
            key.startsWith("private:") -> {
                val id = privateChatRecipient(key, targetClientId)
                    ?: error("Recipient is offline")
                socket.javaClass.getMethod("sendPrivateMessage", Int::class.javaPrimitiveType, String::class.java)
                    .invoke(socket, id, text)
            }
            else -> error("Unsupported conversation")
        }
        val myName = clientCache[ownClientId]?.nickname ?: "You"
        chatHistory.add(chatServerKey(), key, myName, text, true)
        chatListener?.invoke(key)
        Unit
    }.onFailure {
        Log.w("TS3X_CHAT", "Send failed: ${it.javaClass.simpleName}: ${it.cause?.message ?: it.message}")
    }

    private fun onChatEvent(event: Any, raw: Map<String, Any?>) {
        if (!connected) return
        val mode = numberProperty(raw, "targetmode", "gettargetmode")?.toInt()
            ?: return
        if (mode !in 1..2) return // Ignore server-wide text messages (targetmode=3)
        val senderId = numberProperty(raw, "invokerid", "getinvokerid")?.toInt() ?: -1
        if (senderId == ownClientId && ownClientId >= 0) return // local echo is already stored
        val text = stringProperty(raw, "msg", "message", "getmessage", "getmsg")
            ?.takeIf { it.isNotBlank() } ?: return
        val senderClient = clientCache[senderId]
        val senderUid = stringProperty(raw, "invokeruid", "getinvokeruid")
            ?.takeIf { it.isNotBlank() } ?: senderClient?.uniqueId.orEmpty()
        val sender = stringProperty(raw, "invokername", "getinvokername")
            ?.takeIf { it.isNotBlank() }?.let(::localNickname)
            ?: senderClient?.nickname ?: "TeamSpeak"
        val key = when (mode) {
            1 -> "private:" + senderUid.ifBlank { "$chatSessionId:$senderId" }
            2 -> "channel:" + (numberProperty(raw, "target", "gettarget")
                ?.takeIf { it > 0L } ?: currentChannelId ?: return)
            else -> return
        }
        chatHistory.add(chatServerKey(), key, sender, text, false)
        if (openChatKey == key) chatHistory.markRead(chatServerKey(), key)
        else if (mode == 1) runCatching {
            val attachment = ChatMessageContentParser.parse(text).cloudAttachment
            val notificationText = attachment?.let { "📎 ${it.fileName}" } ?: text
            ChatNotifications.show(appContext, sender, notificationText)
        }.onFailure { Log.w("TS3X_CHAT", "Chat notification failed", it) }
        chatListener?.invoke(key)
        Log.d("TS3X_CHAT", "Received message mode=$mode senderId=$senderId")
    }

    private val settingsStore = AppSettingsStore(appContext)
    private val audioEngine = Ts3AudioEngine(appContext, settingsStore)
    private val socketRef = AtomicReference<Any?>(null)
    private val channelSequence = AtomicLong(0L)
    private val healthFailureCount = AtomicInteger(0)
    private val serverProtocolRef = AtomicReference(ServerProtocolProfile.unknown())
    private val fileTransfersRef = AtomicReference<TeamSpeakFileTransfers?>(null)
    private val clientLookupRef = AtomicReference<TeamSpeakClientLookup?>(null)
    @Volatile private var disconnectNotice = java.util.concurrent.CountDownLatch(1)
    private val pendingConnectionInfo = AtomicReference<CompletableFuture<Map<String, Any?>>?>(null)
    private val connectionInfoCache = ConcurrentHashMap<Int, Map<String, Any?>>()
    private val receivedServerGroupIcons = ConcurrentHashMap<Long, Long>()
    private val receivedServerGroupNames = ConcurrentHashMap<Long, String>()
    private val receivedServerGroupTypes = ConcurrentHashMap<Long, Int>()
    private val receivedChannelGroupNames = ConcurrentHashMap<Long, String>()
    private val receivedChannelGroupTypes = ConcurrentHashMap<Long, Int>()
    private val receivedChannelGroupIcons = ConcurrentHashMap<Long, Long>()

    /** TeamSpeak group IDs are scoped to a virtual server, never to this backend instance. */
    private fun clearSessionGroupMetadata() {
        receivedServerGroupIcons.clear()
        receivedServerGroupNames.clear()
        receivedServerGroupTypes.clear()
        receivedChannelGroupNames.clear()
        receivedChannelGroupTypes.clear()
        receivedChannelGroupIcons.clear()
        Log.i("TS3X_ROLES", "Cleared session-local server/channel group metadata")
    }


    private val joinCommandLock = Any()

    @Volatile
    private var lastJoinRequestChannelId: Long = -1L

    @Volatile
    private var lastJoinRequestAtMs: Long = 0L

    @Volatile
    private var connectedHost: String = ""

    @Volatile
    private var connectedPort: Int = 9987

    /** Endpoint of the process-owned connection, used to restore the UI after Activity recreation. */
    fun activeServerEndpoint(): Pair<String, Int>? =
        if (connected && connectedHost.isNotBlank()) connectedHost to connectedPort else null

    private val channelCache = ConcurrentHashMap<Long, BackendChannel>()
    private val clientCache = ConcurrentHashMap<Int, BackendClient>()

    private val channelDescriptionGeneration =
        ConcurrentHashMap<
            Long,
            Long
        >()


    private val eventLogLock = Any()
    private val recentEvents = ArrayDeque<String>()

    @Volatile
    private var clientEventListener: ((BackendClientEvent) -> Unit)? = null
    @Volatile private var incomingPokeListener: ((String, String) -> Unit)? = null

    fun setIncomingPokeListener(listener: ((String, String) -> Unit)?) {
        incomingPokeListener = listener
    }

    @Volatile var status: String = "Backend idle"
        private set

    @Volatile var connected: Boolean = false
        private set

    @Volatile var ownClientId: Int = -1
        private set

    @Volatile private var selfAwayState = false
    @Volatile private var selfAwayMessage = ""

    fun isSelfAway(): Boolean = selfAwayState
    fun selfAwayMessage(): String = selfAwayMessage

    /** Updates the own client away flag and message via the TS3 clientupdate command. */
    fun setSelfAway(away: Boolean, awayMessage: String): Result<Unit> = runCatching {
        val socket = socketRef.get() ?: error("TeamSpeak is not connected")
        check(connected && ownClientId >= 0) { "TeamSpeak is not connected" }
        val normalized = awayMessage.trim().take(1024)
        executeClientUpdateExact(socket, mapOf(
            "client_away" to if (away) "1" else "0",
            "client_away_message" to if (away) normalized else ""
        ))
        selfAwayState = away
        selfAwayMessage = normalized
        clientCache[ownClientId]?.let { previous ->
            val updated = previous.copy(isAway = away, awayMessage = if (away) normalized else "")
            clientCache[ownClientId] = updated
            clientEventListener?.invoke(BackendClientEvent("selfaway", updated))
        }
        Log.i("TS3X_AFK", "self.away.updated away=$away messageLength=${normalized.length}")
        Unit
    }.onFailure { Log.e("TS3X_AFK", "self.away.failed type=${it.javaClass.simpleName}", it) }

    @Volatile var currentChannelId: Long? = null
        private set

    @Volatile
    private var observedClientUid: String? = null

    @Volatile
    private var observedServerInfo = BackendServerInfo()

    private val socketClassName =
        "com.github.manevolent.ts3j.protocol.socket.client.LocalTeamspeakClientSocket"

    private val identityClassName =
        "com.github.manevolent.ts3j.identity.LocalIdentity"

    @Synchronized
    fun connect(
        host: String,
        port: Int,
        nickname: String,
        password: String,
        persistNickname: Boolean = true
    ): Result<Unit> =
        runCatching {
            sessionTasks.stop()
            val connectStarted = android.os.SystemClock.elapsedRealtime()
            fun phase(name: String) = ConnectionDiagnostics.event("connect.phase",
                "name=$name elapsedMs=${android.os.SystemClock.elapsedRealtime() - connectStarted}")
            ConnectionDiagnostics.event("session.connect.begin", "port=$port previousConnected=$connected")
            ConnectionDiagnostics.network(appContext, "connect")
            // If a stale local socket exists, use the protocol-level disconnect first.
            if (socketRef.get() != null) {
                disconnect()
            }

            clearSessionGroupMetadata()
            channelCache.clear()
            clientCache.clear()
            checkedAvatarMetadata.clear()
            channelDescriptionGeneration.clear()
            channelSequence.set(0L)
            clearEventLog()
            ownClientId = -1
            currentChannelId = null
            observedClientUid = null
            observedServerInfo = BackendServerInfo()
            serverProtocolRef.set(ServerProtocolProfile.unknown())
            disconnectNotice = java.util.concurrent.CountDownLatch(1)
            fileTransfersRef.getAndSet(null)?.close()
            clientLookupRef.getAndSet(null)?.close()
            healthFailureCount.set(0)

            status = "Loading ts3j…"
            val socketClass = Class.forName(socketClassName)
            val identityClass = Class.forName(identityClassName)

            val socket = socketClass.getDeclaredConstructor().newInstance()
            socketRef.set(socket)
            socket.javaClass.getMethod("setHandshakeObserver", java.util.function.Consumer::class.java)
                .invoke(socket, java.util.function.Consumer<String> { stage ->
                    ConnectionDiagnostics.event("handshake.progress", stage)
                })
            socket.javaClass.getMethod("setExceptionHandler", java.util.function.Consumer::class.java)
                .invoke(socket, java.util.function.Consumer<Throwable> { error ->
                    ConnectionDiagnostics.event("protocol.error",
                        "type=${rootClass(error)} message=${rootMessage(error).take(250)}")
                    error.printStackTrace()
                })
            val commandSender = TeamSpeakFileTransfers.CommandSender { command, arguments ->
                check(socketRef.get() === socket) { "TeamSpeak session has changed" }
                executeParameterizedCommand(socket, command, arguments, emptySet())
            }
            clientLookupRef.set(TeamSpeakClientLookup(commandSender))

            // ts3j's built-in InitServerHandler consumes the initserver command
            // internally and only keeps aclid/acn/virtualserver_id. It does not
            // forward the full initserver payload to TS3Listener. Install a
            // transparent wrapper before connect() so we can capture the actual
            // virtualserver_* properties (created/banner/platform/version/etc.)
            // while still delegating to the original ts3j handler.
            installInitServerTap(socket)

            // Initial channellist/client-enter-view packets arrive during connect().
            installEventListener(socket)

            if (enableAudio) {
                audioEngine.attach(socket).onFailure {
                    recordEvent("AUDIO attach warning: ${it.message}")
                }
            }

            phase("identity.begin")
            status = "Loading TeamSpeak identity…"
            val identity = loadOrCreateIdentity(identityClass)

            socket.javaClass.getMethod(
                "setIdentity",
                identity.javaClass
            ).invoke(socket, identity)

            val effectiveNickname = localNickname(nickname.trim())
                .takeIf { it.isNotBlank() }
                ?: localNickname(settingsStore.nickname())

            if (persistNickname) {
                settingsStore.setNickname(effectiveNickname)
            }
            invokeIfPresent(socket, "setNickname", serverNickname(effectiveNickname))

            connectedHost = host.trim()
            connectedPort = port

            phase("identity.end")
            status = "Resolving $host…"
            phase("resolve.begin")
            val resolver = AndroidTeamSpeakResolver(appContext)
            val resolvedAddress = resolver.resolve(host, port)
            val candidates = resolvedAddress.endpoints.take(4)
            var endpoint = candidates.first()
            phase("resolve.end")
            ConnectionDiagnostics.event("address.resolved",
                "method=${resolvedAddress.method} requestedPort=$port resolvedPort=${endpoint.port} " +
                    "candidatePorts=${candidates.map { it.port }}")

            val connect = socket.javaClass.getMethod("connect", InetSocketAddress::class.java,
                String::class.java, Long::class.javaPrimitiveType, Long::class.javaPrimitiveType)
            val handshakeDeadline = android.os.SystemClock.elapsedRealtime() + 10_000L
            phase("handshake.begin")
            try {
                for ((index, candidate) in candidates.withIndex()) {
                    endpoint = candidate
                    val remaining = handshakeDeadline - android.os.SystemClock.elapsedRealtime()
                    check(remaining > 0) { "TeamSpeak connection deadline exceeded" }
                    val hasNext = index < candidates.lastIndex
                    val initialReplyTimeout = if (hasNext) minOf(2_500L, remaining) else remaining
                    status = "Connecting to ${candidate.address.hostAddress}:${candidate.port}…"
                    ConnectionDiagnostics.event("connect.attempt",
                        "index=${index + 1} total=${candidates.size} port=${candidate.port} budgetMs=$remaining")
                    try {
                        connectWithIdentityRejectionDetection(socket) {
                            connect.invoke(socket, candidate, TeamSpeakCredentials.serverPassword(password),
                                remaining, initialReplyTimeout)
                        }
                        break
                    } catch (error: Throwable) {
                        val unanswered = socket.javaClass.getMethod("wasLastConnectUnanswered").invoke(socket) == true
                        val transportFailure = generateSequence(error) { it.cause }.take(12).any {
                            it is java.util.concurrent.TimeoutException || it is java.io.IOException
                        }
                        ConnectionDiagnostics.event("connect.attempt.failed",
                            "index=${index + 1} unanswered=$unanswered transportFailure=$transportFailure")
                        if (!hasNext || !unanswered || !transportFailure) throw error
                    }
                }
            } catch (error: Throwable) {
                resolver.invalidate(host, port)
                throw error
            }
            resolver.rememberSuccessful(host, port, resolvedAddress, endpoint)
            // Empty-IP file notifications must use the server that actually answered.
            fileTransfersRef.set(TeamSpeakFileTransfers(commandSender, endpoint.address.hostAddress))

            phase("handshake.end")
            check(transportConnected(socket)) { "TeamSpeak transport disconnected during handshake" }
            selfAwayState = false
            selfAwayMessage = ""
            ownClientId = invokeNoArgNumber(socket, "getClientId")?.toInt() ?: -1
            currentChannelId = clientCache[ownClientId]?.channelId
            observedClientUid = clientCache[ownClientId]
                ?.uniqueId
                ?.takeIf { it.isNotBlank() }

            // Only the authenticated transport is required to enter the existing UI.
            // Large subscriptions and avatar uploads continue independently.
            check(transportConnected(socket)) { "TeamSpeak transport disconnected before ready" }
            connected = true
            status = "Connected"
            healthFailureCount.set(0)
            lastSuccessfulProbeMs.set(android.os.SystemClock.elapsedRealtime())
            sessionTasks.start(object : TeamSpeakSessionTasks.Session {
                override fun isConnected(): Boolean = socketRef.get() === socket && transportConnected(socket)
                override fun requiresSubscription(): Boolean = !metadataOnly
                override fun subscribe() {
                    detectServerProtocol(socket)
                    val started = android.os.SystemClock.elapsedRealtime()
                    ConnectionDiagnostics.event("subscription.begin", "clients=${clientCache.size}")
                    socket.javaClass.getMethod("subscribeAll").invoke(socket)
                    ConnectionDiagnostics.event("subscription.complete",
                        "elapsedMs=${android.os.SystemClock.elapsedRealtime() - started} clients=${clientCache.size}")
                }
                override fun publishAvatar() {
                    if (savedAvatarPath() != null) publishSavedAvatar(socket).getOrThrow()
                }
            }, { error ->
                recordEvent("Session metadata warning: ${rootMessage(error)}")
                ConnectionDiagnostics.event("subscription.warning", "error=${rootClass(error)} transport=${socketTransportState()}")
            }, {
                markConnectionLost("Connection lost", socket)
            })
            // Monitoring probes must count the complete subscribed view, while
            // interactive voice sessions can use that view as it arrives.
            if (!enableAudio && !metadataOnly) check(sessionTasks.awaitInitialization(45_000L)) {
                "TeamSpeak initial snapshot did not complete"
            }
            check(transportConnected(socket)) { "TeamSpeak transport disconnected before ready" }
            phase("ready")
            ConnectionDiagnostics.event("session.connected",
                "clientId=$ownClientId transport=${socketTransportState()} " +
                    "elapsedMs=${android.os.SystemClock.elapsedRealtime() - connectStarted}")

            Unit
        }.onFailure {
            sessionTasks.stop()
            connected = false
            fileTransfersRef.getAndSet(null)?.close()
            clientLookupRef.getAndSet(null)?.close()
            status = "${rootClass(it)}: ${rootMessage(it)}"
            recordEvent("CONNECT ERROR: $status")

            // Do not leave a half-created UDP socket behind.
            audioEngine.detach()

            socketRef.getAndSet(null)?.let { socket ->
                runCatching { socket.javaClass.getMethod("close").invoke(socket) }
            }
        }

    /**
     * ts3j reports some early server handshake rejections only on System.err,
     * then the caller receives a generic connection timeout. Observe only the
     * exact server identity/password rejection while connect() is executing, forward all
     * stderr output unchanged and restore the original stream in finally.
     * Never infer an identity error from a timeout alone.
     */
    private fun connectWithIdentityRejectionDetection(socket: Any, connectAction: () -> Unit) {
        val original = System.err
        val rejection = AtomicReference<String?>(null)
        // ts3j's NetworkHandler calls Throwable.printStackTrace(), which writes
        // to PrintStream in chunks; overriding println(String) alone misses it.
        // Forward bytes unchanged and only retain a bounded tail for matching.
        val tail = StringBuilder()
        val observedOutput = object : java.io.OutputStream() {
            @Synchronized
            private fun inspect(bytes: ByteArray, offset: Int, length: Int) {
                if (rejection.get() != null || length <= 0) return
                tail.append(String(bytes, offset, length, Charsets.UTF_8))
                if (tail.length > 2048) tail.delete(0, tail.length - 2048)
                val value = tail.toString()
                val reason = when {
                    value.contains("could not validate client identity", ignoreCase = true) ||
                        value.contains("invalid client identity", ignoreCase = true) ->
                        "could not validate client identity"
                    value.contains("invalid server password", ignoreCase = true) ->
                        "invalid server password"
                    value.contains("client version is too old", ignoreCase = true) ||
                        value.contains("client version too old", ignoreCase = true) ->
                        "client version is too old for this server"
                    else -> null
                }
                if (reason != null && rejection.compareAndSet(null, reason)) {
                    Log.w("TS3X_CONN", "protocol.server.rejected reason=$reason")
                }
            }

            override fun write(value: Int) {
                original.write(value)
                inspect(byteArrayOf(value.toByte()), 0, 1)
            }

            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                original.write(bytes, offset, length)
                inspect(bytes, offset, length)
            }

            override fun flush() { original.flush() }
        }
        val observer = PrintStream(observedOutput, true)
        val installed = runCatching { System.setErr(observer); System.err === observer }
            .getOrDefault(false)
        Log.i("TS3X_CONN", "protocol.identity.observer installed=$installed")
        try {
            // Reflection's connect() blocks for up to 10 seconds even when the network
            // handler has already printed a definitive server refusal. Observe that
            // refusal while connect() runs, then close the socket to abort the handshake.
            val done = java.util.concurrent.CountDownLatch(1)
            val connectError = AtomicReference<Throwable?>(null)
            val worker = Thread({
                try {
                    connectAction()
                } catch (error: Throwable) {
                    connectError.set(error)
                } finally {
                    done.countDown()
                }
            }, "TS3X-ConnectHandshake").apply { isDaemon = true }
            worker.start()

            var rejectionReason: String? = null
            while (true) {
                rejectionReason = rejection.get()
                if (rejectionReason != null) break
                if (done.await(25L, TimeUnit.MILLISECONDS)) {
                    rejectionReason = rejection.get()
                    break
                }
            }

            if (rejectionReason != null) {
                Log.w("TS3X_CONN", "protocol.server.rejection.fast_abort reason=$rejectionReason")
                // close() is deliberately used instead of disconnect(): the handshake
                // never reached CONNECTED, so a graceful clientdisconnect would block.
                runCatching { socket.javaClass.getMethod("close").invoke(socket) }
                    .onFailure { Log.w("TS3X_CONN", "protocol.server.rejection.close_failed", it) }
                // Give the connect worker a short window to leave the socket's state
                // machine before the outer failure path clears session resources.
                done.await(350L, TimeUnit.MILLISECONDS)
                throw IllegalStateException("Server rejected connection: $rejectionReason", connectError.get())
            }
            connectError.get()?.let { throw it }
        } finally {
            if (installed && System.err === observer) System.setErr(original)
            observer.flush()
            // Never close observer: it delegates to the Android runtime's stderr.
        }
    }

    /**
     * Graceful TeamSpeak disconnect.
     *
     * Give the remote leave a short bounded window, then close locally even if
     * the server does not send its final notification. Pending metadata is cancelled.
     */
    @Synchronized
    fun disconnect(): Result<Unit> {
        sessionTasks.stop()
        ConnectionDiagnostics.event("session.disconnect.request", "connected=$connected transport=${socketTransportState()}")
        connected = false
        fileTransfersRef.getAndSet(null)?.close()
        clientLookupRef.getAndSet(null)?.close()
        val socket = socketRef.get() ?: run {
            connected = false
            clearSessionGroupMetadata()
            status = "Disconnected"
            return Result.success(Unit)
        }

        status = "Disconnecting…"

        val closed = runCatching {
            TeamSpeakDisconnect.close(object : TeamSpeakDisconnect.Transport {
                override fun isConnected(): Boolean = transportConnected(socket)
                override fun requestDisconnect() {
                    socket.javaClass.getMethod("disconnect", String::class.java)
                        .invoke(socket, "Android client disconnected")
                    disconnectNotice.await(250, TimeUnit.MILLISECONDS)
                }
                override fun close() {
                    socket.javaClass.getMethod("close").invoke(socket)
                }
            }, 1_500L)
        }
        audioEngine.detach()
        openChatKey = null

        socketRef.compareAndSet(socket, null)
        connected = false
        ownClientId = -1
        currentChannelId = null
        observedClientUid = null
        channelCache.clear()
        clientCache.clear()
        clearSessionGroupMetadata()
        connectionInfoCache.clear()

        ConnectionDiagnostics.event("session.disconnect.complete",
            "localClosed=${closed.isSuccess} graceful=${closed.getOrDefault(false)}")
        status = if (closed.isSuccess) "Disconnected" else "Disconnect cleanup failed: ${rootMessage(closed.exceptionOrNull()!!)}"
        return closed.map { Unit }
    }

    // Pure local snapshots: no clientlist/channellist command is sent here.
    fun channels(): List<BackendChannel> = channelCache.values.toList()

    fun activeVoiceClientIds(): Set<Int> = audioEngine.activeVoiceClientIds()

    fun clientVolume(id: Int): Float = audioEngine.clientVolume(id)
    fun setClientVolume(id: Int, value: Float) = audioEngine.setClientVolume(id, value)

    private fun clientCommand(name: String, args: Map<String, String>): Result<Unit> = runCatching {
        check(connected) { "Not connected" }
        val current = socketRef.get() ?: error("No active TeamSpeak socket")
        executeParameterizedCommand(current, name, args, emptySet())
        Unit
    }

    fun pokeClient(id: Int, message: String = "Poke"): Result<Unit> =
        clientCommand("clientpoke", mapOf("clid" to id.toString(), "msg" to message))

    fun kickClientFromServer(id: Int, reason: String = "Removed by moderator"): Result<Unit> =
        clientCommand("clientkick", mapOf("clid" to id.toString(), "reasonid" to "5", "reasonmsg" to reason))

    fun banClientFromServer(id: Int, seconds: Int, reason: String): Result<Unit> =
        clientCommand("banclient", mapOf("clid" to id.toString(), "time" to seconds.toString(), "banreason" to reason))

    fun removeClientAvatar(id: Int): Result<Unit> = runCatching {
        check(connected) { "Not connected" }
        val socket = socketRef.get() ?: error("No active TeamSpeak socket")
        val uid = clientCache[id]?.uniqueId?.takeIf { it.isNotBlank() }
            ?: clientLookupRef.get()?.clientUid(id) ?: error("Client UID lookup unavailable")
        // clientdelavatar is not a full-client command. Both generations accept
        // ftdeletefile against the virtual avatar path and enforce moderator rights.
        executeParameterizedCommand(socket, "ftdeletefile", mapOf("cid" to "0", "cpw" to "",
            "name" to TeamSpeakIdentity.avatarDeletePath(uid)), emptySet())
        check(socketRef.get() === socket && connected) { "TeamSpeak session has changed" }
        clientCache.computeIfPresent(id) { _, previous -> previous.copy(avatarHash = "") }
        Unit
    }

    // Client group membership is sent via clientlist/notifycliententerview and
    // remains accessible even when the server denies the clientinfo command.
    fun clientChannelGroup(id: Int): Result<String> = runCatching {
        val client = clientCache[id] ?: error("Участник не найден")
        if (client.channelGroupId == 0L) "Группа не передана сервером"
        else client.channelGroupId.toString()
    }

    data class ChannelGroupSummary(val id: Long, val name: String, val iconId: Long = 0L)

    fun channelGroupSummaries(clientId: Int): List<ChannelGroupSummary> {
        val currentGroup = clientCache[clientId]?.channelGroupId ?: 0L
        val groups = receivedChannelGroupNames.entries.asSequence()
            .filter { (id, name) ->
                id != defaultChannelGroupId() && receivedChannelGroupTypes[id] != 0 &&
                    name.trim().lowercase(java.util.Locale.ROOT) != "guest"
            }
            .map { (id, name) -> ChannelGroupSummary(id, name, receivedChannelGroupIcons[id] ?: 0L) }
            .sortedBy { it.id }.toMutableList()
        if (currentGroup > 0L && currentGroup != defaultChannelGroupId() &&
            receivedChannelGroupNames[currentGroup]?.trim()?.equals("Guest", ignoreCase = true) != true &&
            groups.none { it.id == currentGroup }) {
            groups.add(ChannelGroupSummary(currentGroup, "Группа #$currentGroup"))
        }
        return groups
    }

    /** Prefer an assignable regular Guest group (type=1), never a template (type=0). */
    fun defaultChannelGroupId(): Long? {
        // The authoritative default can have a custom/localized name on TS3 or TS6.
        serverProtocolRef.get().defaultChannelGroupId.takeIf { it > 0L }?.let { return it }
        return receivedChannelGroupNames.entries
        .filter { (_, name) -> name.trim().equals("Guest", ignoreCase = true) }
        .sortedWith(compareBy<Map.Entry<Long, String>> {
            when (receivedChannelGroupTypes[it.key]) {
                1 -> 0 // Regular group, accepted by setclientchannelgroup.
                null -> 1 // Older servers may omit type.
                else -> 2 // Templates / query groups are not normally assignable.
            }
        }.thenBy { it.key })
        .firstOrNull { receivedChannelGroupTypes[it.key] == 1 || receivedChannelGroupTypes[it.key] == null }
        ?.key
    }

    fun channelGroupBadge(groupId: Long): ChannelGroupSummary? {
        if (groupId <= 0L || groupId == defaultChannelGroupId()) return null
        val name = receivedChannelGroupNames[groupId] ?: return null
        if (name.trim().equals("Guest", ignoreCase = true)) return null
        return ChannelGroupSummary(groupId, name, receivedChannelGroupIcons[groupId] ?: 0L)
    }

    fun channelGroupIconIdsForClients(): List<Long> = clients()
        .mapNotNull { channelGroupBadge(it.channelGroupId)?.iconId }
        .filter { it != 0L }.distinct().take(32)

    fun removeChannelGroupFromClient(clientId: Int): Result<Unit> {
        val guestGroupId = defaultChannelGroupId()
            ?: return Result.failure(IllegalStateException("Не найдена назначаемая Guest-группа. Проверьте список групп канала."))
        Log.i("TS3X_GROUPS", "channelgroup.revoke target=$clientId guestId=$guestGroupId guestType=${receivedChannelGroupTypes[guestGroupId]}")
        return setChannelGroupForClient(clientId, guestGroupId)
    }

    fun refreshChannelGroupSummaries(): Result<Unit> = runCatching {
        val socket = socketRef.get() ?: error("Нет подключения")
        // Channel groups are announced as notifychannelgrouplist on full-client
        // connections; executeParameterizedCommand also handles direct payloads.
        val result = executeParameterizedCommand(socket, "channelgrouplist", emptyMap(), emptySet())
        fun receive(props: Map<String, Any?>) {
            val id = numberProperty(props, "cgid", "getcgid", "channel_group_id") ?: return
            stringProperty(props, "name", "getname")?.takeIf { it.isNotBlank() }?.let {
                receivedChannelGroupNames[id] = it
            }
            numberProperty(props, "type", "gettype")?.let { receivedChannelGroupTypes[id] = it.toInt() }
            numberProperty(props, "iconid", "icon_id", "geticonid")?.let {
                receivedChannelGroupIcons[id] = it and 0xffffffffL
            }
        }
        receive(result)
        Log.i("TS3X_GROUPS", "channelgrouplist responseKeys=${result.keys} cached=${receivedChannelGroupNames.size}")
        Unit
    }

    fun setChannelGroupForClient(clientId: Int, groupId: Long): Result<Unit> = runCatching {
        check(connected) { "Нет подключения к серверу" }
        val client = clientCache[clientId] ?: error("Участник не найден")
        check(client.channelId > 0L) { "Сервер не передал ID канала" }
        check(groupId > 0L) { "Некорректная группа канала" }
        val socket = socketRef.get() ?: error("Нет соединения TeamSpeak")
        val dbid = resolveClientDatabaseId(socket, client)
        Log.i("TS3X_GROUPS", "channelgroup.request target=$clientId dbid=${client.databaseId} cid=${client.channelId} groupId=$groupId")
        executeParameterizedCommand(socket, "setclientchannelgroup", mapOf(
            "cgid" to groupId.toString(),
            "cid" to client.channelId.toString(),
            "cldbid" to dbid.toString()
        ), emptySet())
        clientCache.computeIfPresent(clientId) { _, old -> old.copy(channelGroupId = groupId) }
        Log.i("TS3X_GROUPS", "channelgroup.assigned clientId=$clientId channelId=${client.channelId} groupId=$groupId")
        Unit
    }

    data class ServerGroupSummary(val id: Long, val name: String, val iconId: Long)

    // TeamSpeak built-in templates / query identities are not user-manageable roles.
    private val excludedBuiltInGroups = setOf(
        "guest", "normal", "server admin", "admin server query", "guest server query"
    )

    fun serverGroupSummaries(): List<ServerGroupSummary> =
        (receivedServerGroupIcons.keys + receivedServerGroupNames.keys).distinct()
            .asSequence()
            .filter { id ->
                val name = receivedServerGroupNames[id]?.trim()?.lowercase(java.util.Locale.ROOT)
                val type = receivedServerGroupTypes[id]
                // type 1 is the regular TeamSpeak server group type; type 0 and 2
                // represent templates / server query groups.
                (type == null || type == 1) &&
                    id != serverProtocolRef.get().defaultServerGroupId &&
                    name !in excludedBuiltInGroups &&
                    !(type == null && id in 1L..5L)
            }
            .sorted()
            .map { id -> ServerGroupSummary(
                id, receivedServerGroupNames[id] ?: "Группа #$id",
                receivedServerGroupIcons[id] ?: 0L
            ) }
            .toList()

    fun setServerGroupForClient(clientId: Int, groupId: Long, add: Boolean): Result<Unit> = runCatching {
        check(connected) { "Нет подключения к серверу" }
        val client = clientCache[clientId] ?: error("Участник не найден")
        val socket = socketRef.get() ?: error("Нет соединения TeamSpeak")
        val dbid = resolveClientDatabaseId(socket, client)
        executeParameterizedCommand(socket,
            if (add) "servergroupaddclient" else "servergroupdelclient",
            mapOf("sgid" to groupId.toString(), "cldbid" to dbid.toString()), emptySet())
        // Reflect successful assignment immediately; normal TS3 notifications
        // will reconcile state when the server broadcasts an update.
        clientCache.computeIfPresent(clientId) { _, old ->
            old.copy(serverGroupIds = if (add) (old.serverGroupIds + groupId).distinct()
                else old.serverGroupIds.filterNot { it == groupId })
        }
        Unit
    }

    fun listServerGroupsForClient(id: Int): List<Long> =
        clients().firstOrNull { it.id == id }?.serverGroupIds.orEmpty()

    /** DBIDs and live clids are different in both server generations. */
    private fun resolveClientDatabaseId(socket: Any, client: BackendClient): Long {
        if (client.databaseId > 0L) return client.databaseId
        fun dbidFrom(values: Map<String, Any?>): Long? = numberProperty(
            values, "cldbid", "client_database_id", "client_databaseid", "getclientdatabaseid"
        )?.takeIf { it > 0L }
        val info = runCatching {
            dbidFrom(executeParameterizedCommand(socket, "clientinfo",
                mapOf("clid" to client.id.toString()), setOf("client_database_id", "cldbid")))
        }
        val dbid = info.getOrNull() ?: clientLookupRef.get()?.let { lookup ->
            val uid = client.uniqueId.takeIf { it.isNotBlank() } ?: lookup.clientUid(client.id)
            lookup.databaseId(uid)
        } ?: error("Сервер не передал DBID участника. Проверьте права client info. " +
            info.exceptionOrNull()?.let(::rootMessage).orEmpty())
        check(socketRef.get() === socket && connected) { "TeamSpeak session has changed" }
        clientCache.computeIfPresent(client.id) { _, previous -> previous.copy(databaseId = dbid) }
        return dbid
    }

    /**
     * The full TS3 client protocol does not support the ServerQuery-only
     * clientgetconnectioninfo command. Collect what the connected client can
     * actually observe; never turn one unavailable property into a failed page.
     */
    fun clientConnectionDetails(id: Int): Result<Map<String, Any?>> = runCatching {
        Log.i("TS3X_STATS", "backend.request.begin clientId=$id connected=$connected own=$ownClientId")
        check(connected) { "Нет подключения" }
        val socket = socketRef.get() ?: error("Нет транспорта TeamSpeak")
        val values = linkedMapOf<String, Any?>()
        val cached = clientCache[id]
        if (cached != null) {
            values["client_nickname"] = localNickname(cached.nickname)
            values["client_channel_id"] = cached.channelId
            if (cached.databaseId > 0) values["client_database_id"] = cached.databaseId
            if (cached.uniqueId.isNotBlank()) values["client_unique_identifier"] = cached.uniqueId
            Log.i("TS3X_STATS", "backend.cache.read clientId=$id keys=${values.keys.sorted()}")
        }

        // Optional client-protocol command: availability depends on server and
        // ts3j revision. A refusal is normal, not a reason to blank the dialog.
        Log.i("TS3X_STATS", "backend.command.start name=clientinfo clientId=$id")
        val remote = runCatching {
            executeParameterizedCommand(
                socket, "clientinfo", mapOf("clid" to id.toString()),
                setOf("client_idle_time", "client_connected_time", "client_database_id")
            )
        }.onFailure { error ->
            val cause = (error as? InvocationTargetException)?.targetException ?: error
            Log.w("TS3X_STATS", "backend.command.unavailable name=clientinfo clientId=$id type=${cause.javaClass.simpleName} reason=${cause.message}")
        }.getOrNull()
        if (remote != null) {
            val normalized = normalizeConnectionInfo(remote)
            values.putAll(normalized)
            Log.i("TS3X_STATS", "backend.command.done clientId=$id keys=${normalized.keys.sorted()}")
        }

        // Full-client protocol: getconnectioninfo clid=N -> notifyconnectioninfo.
        // Unlike the ServerQuery-only clientgetconnectioninfo, this works with
        // the client protocol and can request other users' statistics.
        val connectionStats = runCatching { requestProtocolConnectionInfo(socket, id) }
            .onFailure { Log.w("TS3X_STATS", "protocol.connectioninfo.unavailable clientId=$id reason=${rootMessage(it)}") }
            .getOrNull()
        if (connectionStats != null) {
            values.putAll(connectionStats)
            Log.i("TS3X_STATS", "backend.connectioninfo.keys clientId=$id keys=${connectionStats.keys.sorted()}")
        }

        if (id == ownClientId) {
            // Local ts3j client info and socket properties may expose ping,
            // traffic counters, and time values without any server command.
            val ownInfo = runCatching {
                val method = socket.javaClass.methods.firstOrNull {
                    it.name == "getClientInfo" && it.parameterTypes.size == 1 &&
                        (it.parameterTypes[0] == Int::class.javaPrimitiveType ||
                            it.parameterTypes[0] == Int::class.javaObjectType)
                }
                method?.invoke(socket, id)
            }.onFailure {
                Log.w("TS3X_STATS", "backend.local.clientInfo.unavailable type=${it.javaClass.simpleName}")
            }.getOrNull()
            if (ownInfo != null) {
                val local = normalizeConnectionInfo(readRawMap(ownInfo))
                values.putAll(local)
                Log.i("TS3X_STATS", "backend.local.clientInfo keys=${local.keys.sorted()}")
            }
            val socketData = linkedMapOf<String, Any?>()
            // Do not exhaustively introspect the socket: only known lightweight
            // getters, excluding methods that trigger network operations.
            val getters = mapOf(
                "connection_ping" to listOf("getPing", "getPingTime", "getAveragePing"),
                "connection_bytes_sent_total" to listOf("getBytesSent", "getBytesSentTotal"),
                "connection_bytes_received_total" to listOf("getBytesReceived", "getBytesReceivedTotal"),
                "connection_packetloss_total" to listOf("getPacketLoss", "getPacketloss")
            )
            getters.forEach { (key, names) ->
                names.firstNotNullOfOrNull { name ->
                    runCatching {
                        socket.javaClass.methods.firstOrNull {
                            it.name == name && it.parameterTypes.isEmpty()
                        }?.invoke(socket)
                    }.getOrNull()
                }?.let { value ->
                    if (value is Number) socketData[key] = value
                }
            }
            values.putAll(socketData)
            Log.i("TS3X_STATS", "backend.local.socket keys=${socketData.keys.sorted()}")
        }
        Log.i("TS3X_STATS", "backend.request.complete clientId=$id keys=${values.keys.sorted()}")
        values
    }

    private val connectionInfoQueryLock = Any()

    private fun requestProtocolConnectionInfo(socket: Any, id: Int): Map<String, Any?> =
        synchronized(connectionInfoQueryLock) {
            val pending = CompletableFuture<Map<String, Any?>>()
            pendingConnectionInfo.set(pending)
            try {
                Log.i("TS3X_STATS", "protocol.connectioninfo.send command=getconnectioninfo clientId=$id")
                // Commands on the full-client socket may yield an empty immediate
                // acknowledgement; the payload is delivered via TS3Listener.
                val immediate = runCatching {
                    executeParameterizedCommand(socket, "getconnectioninfo", mapOf("clid" to id.toString()), emptySet())
                }.onFailure {
                    Log.w("TS3X_STATS", "protocol.connectioninfo.command clientId=$id reason=${rootMessage(it)}")
                }.getOrNull().orEmpty()
                val hasMetrics = immediate.keys.any { it.startsWith("connection_") }
                val event = if (hasMetrics) immediate else runCatching {
                    pending.get(1800L, TimeUnit.MILLISECONDS)
                }.onFailure {
                    Log.w("TS3X_STATS", "protocol.connectioninfo.timeout clientId=$id type=${it.javaClass.simpleName}")
                }.getOrNull().orEmpty()
                val combined = normalizeConnectionInfo(immediate + event).toMutableMap()
                val aliases = mapOf(
                    "connection_client2server_packetloss_total" to "connection_packetloss_c2s_total",
                    "connection_server2client_packetloss_total" to "connection_packetloss_s2c_total",
                    "connection_packetloss_client2server_total" to "connection_packetloss_c2s_total",
                    "connection_packetloss_server2client_total" to "connection_packetloss_s2c_total"
                )
                aliases.forEach { (from, to) ->
                    if (combined[to] == null && combined[from] != null) combined[to] = combined[from]
                }
                Log.i("TS3X_STATS", "protocol.connectioninfo.result clientId=$id keys=${combined.keys.sorted()}")
                combined
            } finally {
                pendingConnectionInfo.compareAndSet(pending, null)
            }
        }

    private fun normalizeConnectionInfo(input: Map<String, Any?>): Map<String, Any?> {
        val result = linkedMapOf<String, Any?>()
        input.forEach { (key, value) ->
            val normalized = key.lowercase(java.util.Locale.ROOT).removePrefix("get")
            if (value != null) result[normalized] = value
            val alias = when (normalized) {
                "ping", "averageping", "pingtime" -> "connection_ping"
                "bytessenttotal", "bytes_sent_total" -> "connection_bytes_sent_total"
                "bytesreceivedtotal", "bytes_received_total" -> "connection_bytes_received_total"
                "packetloss", "packetloss_total" -> "connection_packetloss_total"
                "idletime", "idle_time" -> "client_idle_time"
                "connectedtime", "connected_time", "connection_connected_time" -> "client_connected_time"
                else -> null
            }
            if (alias != null && value != null) result[alias] = value
        }
        return result
    }

    fun clients(): List<BackendClient> =
        clientCache.values
            .asSequence()
            .filterNot { it.isServerQuery }
            .filterNot { it.nickname.equals("serveradmin", ignoreCase = true) }
            .sortedBy { it.id }
            .toList()

    fun setClientEventListener(
        listener: ((BackendClientEvent) -> Unit)?
    ) {
        clientEventListener = listener
    }

    fun serverName(): String? =
        observedServerInfo.name.trim().takeIf { it.isNotBlank() }

    /** The frontend keeps the existing BackendServerInfo contract. */
    fun serverProtocolProfile(): ServerProtocolProfile = serverProtocolRef.get()

    private fun detectServerProtocol(socket: Any) {
        if (socketRef.get() !== socket) return
        if (serverProtocolRef.get().generation == ServerProtocolProfile.Generation.UNKNOWN) {
            runCatching {
                val response = executeParameterizedCommand(socket, "version", emptyMap(),
                    setOf("version", "build", "platform"))
                check(socketRef.get() === socket && connected) { "TeamSpeak session has changed" }
                val profile = serverProtocolRef.updateAndGet { it.mergeVersionResponse(response) }
                if (profile.version.isNotBlank()) observedServerInfo = observedServerInfo.copy(
                    version = profile.version, platform = profile.platform
                )
            }.onFailure { recordEvent("Server version unavailable: ${rootMessage(it)}") }
        }
        // Unknown/custom version strings are retained, never guessed from the hostname
        // or another client's version; the shared client protocol remains usable.
        ConnectionDiagnostics.event("session.protocol", serverProtocolRef.get().toString())
    }

    fun serverInfo(): BackendServerInfo {
        val snapshot = observedServerInfo

        return if (connected) {
            snapshot.copy(
                clientsOnline = clients().size,
                channelsOnline = channels().size
            )
        } else {
            snapshot
        }
    }


    fun refreshServerInfo(): Result<BackendServerInfo> =
        runCatching {
            val socket =
                socketRef.get()
                    ?: error("Not connected")

            check(connected) {
                "Not connected"
            }

            // Static server properties (name/platform/version/created,
            // host-message/banner, icon, etc.) arrive in the full-client
            // "initserver" command. ts3j processes that internally, but not
            // every revision exposes initserver through TS3Listener.
            //
            // Harvest the socket's already-populated server/property caches
            // before requesting the request-only values.
            harvestServerStateFromSocket(socket)
            harvestTypedVirtualServerInfo(socket)
            harvestVirtualServerPropertiesByEnum(socket)

            // Correct full-client request. TeamSpeak clients use
            // "servergetvariables" for request-only virtual-server variables.
            // "serverinfo" is a ServerQuery-style command and was the root cause
            // of the empty ServerInfo screen in previous builds.
            val requestResult =
                runCatching {
                    executeSimpleCommand(
                        socket = socket,
                        commandName =
                            "servergetvariables"
                    )
                }

            requestResult
                .getOrNull()
                ?.takeIf {
                    it.isNotEmpty()
                }
                ?.let {
                    captureServerMetadata(
                        eventName =
                            "servergetvariables",
                        properties = it
                    )


                }

            if (requestResult.isFailure) {
                Log.w(
                    TAG,
                    "servergetvariables failed: " +
                        rootMessage(
                            requestResult
                                .exceptionOrNull()
                                ?: error(
                                    "servergetvariables failed"
                                )
                        )
                )
            }

            // The full-client request commonly causes a notifyserverupdated
            // event. Give the listener a short window to merge it.
            Thread.sleep(260L)

            // Request-only values may also have been copied by ts3j into an
            // internal variable cache rather than returned as command payload.
            harvestServerStateFromSocket(socket)
            harvestTypedVirtualServerInfo(socket)
            harvestVirtualServerPropertiesByEnum(socket)

            // Merge a classic serverinfo response as a SECONDARY source on
            // every refresh. In full-client mode servergetvariables remains the
            // primary request, but serverinfo is useful on ts3j/server variants
            // that expose static fields (notably virtualserver_created and host
            // branding properties) only through this command response.
            //
            // Existing non-empty values are preserved by captureServerMetadata,
            // so this cannot overwrite a good servergetvariables/initserver value
            // with an absent fallback field.
            runCatching {
                executeSimpleCommand(
                    socket = socket,
                    commandName = "serverinfo"
                )
            }
                .onFailure {

                }
                .getOrNull()
                ?.takeIf {
                    it.isNotEmpty()
                }
                ?.let {
                    captureServerMetadata(
                        eventName =
                            "serverinfo-merge",
                        properties = it
                    )
                }

            // One final harvest catches state ts3j updated while processing the
            // command responses above.
            harvestServerStateFromSocket(socket)
            harvestTypedVirtualServerInfo(socket)
            harvestVirtualServerPropertiesByEnum(socket)

            serverInfo()
        }

    /**
     * Application-level liveness probe. A command-level TeamSpeak error still
     * proves that the server answered; only transport/time-out failures count
     * against the stale-connection watchdog.
     */
    /** Read-only reflection avoids any dependency on ts3j internal socket types. */
    private fun transportConnected(socket: Any): Boolean = runCatching {
        socket.javaClass.getMethod("isConnected").invoke(socket) == true
    }.getOrDefault(false)

    private fun socketTransportState(): String {
        val socket = socketRef.get() ?: return "absent"
        val connectedValue = runCatching {
            socket.javaClass.methods.firstOrNull { it.name == "isConnected" && it.parameterCount == 0 }
                ?.invoke(socket)?.toString()
        }.getOrNull() ?: "unknown"
        return "present/isConnected=$connectedValue"
    }

    fun keepAlive(): Result<Unit> {
        if (!connected) {
            return Result.failure(IOException("Not connected"))
        }

        val socket = socketRef.get()
            ?: return Result.failure(IOException("Socket unavailable"))

        return runCatching {
            val isConnectedMethod = socket.javaClass.methods.firstOrNull {
                it.name == "isConnected" && it.parameterTypes.isEmpty()
            }

            if ((isConnectedMethod?.invoke(socket) as? Boolean) == false) {
                throw IOException("ts3j transport reports disconnected")
            }

            // The UDP transport already sends periodic PINGs. Do not queue
            // clientinfo behind a potentially large channelsubscribeall response.

            healthFailureCount.set(0)
            lastSuccessfulProbeMs.set(android.os.SystemClock.elapsedRealtime())
            ConnectionDiagnostics.event("keepalive.ok", "transport=${socketTransportState()}")
            Unit
        }.recoverCatching { error ->
            val root = rootThrowable(error)
            val rootName = root.javaClass.simpleName

            if (rootName.contains("CommandException", ignoreCase = true)) {
                healthFailureCount.set(0)
                return@recoverCatching Unit
            }

            val failures = healthFailureCount.incrementAndGet()
            val message = rootMessage(error)

            val sinceSuccess = android.os.SystemClock.elapsedRealtime() - lastSuccessfulProbeMs.get()
            ConnectionDiagnostics.event("keepalive.failed", "count=$failures errorType=${root.javaClass.simpleName} " +
                "transport=${socketTransportState()} lastOkAgeMs=$sinceSuccess")
            ConnectionDiagnostics.network(appContext, "keepalive-failure")
            Log.w(TAG, "Keepalive failure $failures: $message")

            if (
                failures >= 2 ||
                message.contains("not connected", ignoreCase = true) ||
                message.contains("disconnected", ignoreCase = true)
            ) {
                markConnectionLost("Connection lost")
            }

            throw root
        }
    }

    /**
     * Loads detailed information for one visible channel.
     * Failure is non-fatal: the existing event-cache entry remains usable.
     */
    fun channelDetails(
        channelId: Long
    ): Result<BackendChannel> =
        runCatching {
            val socket =
                socketRef.get()
                    ?: error(
                        "Not connected"
                    )

            val existing =
                channelCache[
                    channelId
                ]
                    ?: error(
                        "Channel $channelId is not in the current view"
                    )

            val beforeGeneration =
                channelDescriptionGeneration[
                    channelId
                ] ?: 0L



            // Full TeamSpeak clients do not obtain CHANNEL_DESCRIPTION through
            // channelinfo. The protocol command matching
            // ts3client_requestChannelDescription() is channelgetdescription.
            // The server answers asynchronously with:
            //
            // notifychanneledited cid=<id> channel_description=...
            //
            // Our normal TS3Listener path receives that event and mergeChannel()
            // updates channelCache.
            executeParameterizedCommand(
                socket =
                    socket,
                commandName =
                    "channelgetdescription",
                properties =
                    linkedMapOf(
                        "cid" to
                            channelId.toString()
                    ),
                expectedKeys =
                    emptySet()
            )

            val deadline =
                android.os.SystemClock
                    .elapsedRealtime() +
                    1_500L

            var current =
                channelCache[
                    channelId
                ] ?: existing

            while (
                android.os.SystemClock
                    .elapsedRealtime() <
                    deadline
            ) {
                val generation =
                    channelDescriptionGeneration[
                        channelId
                    ] ?: 0L

                current =
                    channelCache[
                        channelId
                    ] ?: current

                if (
                    generation >
                    beforeGeneration
                ) {


                    return@runCatching current
                }

                Thread.sleep(
                    25L
                )
            }

            // If the description had already been cached from an earlier
            // request, returning it is still useful even if this server did
            // not emit another notification.
            if (
                current.description
                    .isNotBlank()
            ) {


                return@runCatching current
            }

            error(
                "TeamSpeak did not return a channel description"
            )
        }.onFailure {
            Log.e(
                TAG,
                "channelDetails($channelId) failed: " +
                    rootMessage(it),
                rootThrowable(it)
            )

            recordEvent(
                "channelgetdescription($channelId) warning: " +
                    rootMessage(it)
            )
        }


    private fun readTypedChannelProperties(
        channelInfo: Any
    ): Map<String, Any?> {
        val propertyClass =
            runCatching {
                Class.forName(
                    "com.github.manevolent.ts3j.api.ChannelProperty"
                )
            }.getOrNull()
                ?: return emptyMap()

        val constants =
            propertyClass.enumConstants
                ?: return emptyMap()

        val accessor =
            channelInfo.javaClass.methods
                .firstOrNull {
                    method ->
                    method.name == "get" &&
                        method.parameterTypes.size == 1 &&
                        method.parameterTypes[0]
                            .isAssignableFrom(
                                propertyClass
                            )
                }
                ?: return emptyMap()

        val propertyGetName =
            propertyClass.methods
                .firstOrNull {
                    method ->
                    method.name == "getName" &&
                        method.parameterTypes.isEmpty()
                }

        val result =
            linkedMapOf<String, Any?>()

        constants.forEach {
            constant ->
            val key =
                runCatching {
                    propertyGetName
                        ?.invoke(constant)
                        ?.toString()
                }.getOrNull()
                    ?.trim()
                    ?.lowercase()
                    ?.takeIf {
                        it.isNotBlank()
                    }
                    ?: (
                        constant as? Enum<*>
                    )
                        ?.name
                        ?.lowercase()
                    ?: return@forEach

            val value =
                runCatching {
                    accessor.invoke(
                        channelInfo,
                        constant
                    )
                }.getOrNull()

            if (
                value != null &&
                value.toString() != "-1"
            ) {
                result[key] =
                    value
            }
        }

        return result
    }

    fun joinChannel(
        channelId: Long,
        password: String? = null
    ): Result<Unit> =
        runCatching {
            synchronized(joinCommandLock) {
                if (channelId == currentChannelId) {
                    status =
                        "Already in " +
                            (
                                channelCache[channelId]?.name
                                    ?: "channel $channelId"
                            )
                    return@synchronized
                }

                val now =
                    android.os.SystemClock.elapsedRealtime()

                if (
                    channelId == lastJoinRequestChannelId &&
                    now - lastJoinRequestAtMs < 900L
                ) {

                    return@synchronized
                }

                lastJoinRequestChannelId = channelId
                lastJoinRequestAtMs = now

                val socket =
                    socketRef.get()
                        ?: error("Not connected")

                val method =
                    socket.javaClass.getMethod(
                        "joinChannel",
                        Int::class.javaPrimitiveType,
                        String::class.java
                    )

                fun invokeMove() {


                    method.invoke(
                        socket,
                        channelId.toInt(),
                        password
                    )
                }

                try {
                    invokeMove()
                } catch (first: Throwable) {
                    if (isClientFloodingError(first)) {
                        Log.w(
                            TAG,
                            "joinChannel cid=$channelId blocked by server anti-flood"
                        )
                    }

                    // Do not retry automatically. Surface the rejection to the
                    // UI so the user sees the cooldown dialog and no additional
                    // command points are generated while the server is blocking
                    // the client.
                    throw first
                }

                currentChannelId = channelId

                if (ownClientId >= 0) {
                    clientCache.computeIfPresent(
                        ownClientId
                    ) {
                        _,
                        client ->
                        client.copy(
                            channelId = channelId
                        )
                    }
                }

                status =
                    "Joined " +
                        (
                            channelCache[channelId]?.name
                                ?: "channel $channelId"
                        )


            }

            Unit
        }.onFailure {
            status =
                describeJoinFailure(
                    channelId = channelId,
                    error = it
                )

            recordEvent(status)

            Log.e(
                TAG,
                "joinChannel failed cid=$channelId: " +
                    rootMessage(it),
                rootThrowable(it)
            )
        }

    private fun isClientFloodingError(
        error: Throwable
    ): Boolean =
        generateSequence(error) {
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

    private fun describeJoinFailure(
        channelId: Long,
        error: Throwable
    ): String {
        val channelName =
            channelCache[channelId]
                ?.name
                ?.takeIf { it.isNotBlank() }
                ?: "channel $channelId"

        val causes =
            generateSequence(error) {
                current ->
                current.cause
                    ?.takeIf {
                        cause ->
                        cause !== current
                    }
            }
                .take(12)
                .toList()

        val combined =
            causes.joinToString(" | ") {
                "${it.javaClass.name}: ${it.message.orEmpty()}"
            }.lowercase()

        val errorId =
            causes.firstNotNullOfOrNull {
                throwable ->
                listOf(
                    "getId",
                    "getErrorId",
                    "getErrorCode",
                    "getCode"
                ).firstNotNullOfOrNull {
                    getterName ->
                    throwable.javaClass.methods
                        .firstOrNull {
                            method ->
                            method.name.equals(
                                getterName,
                                ignoreCase = true
                            ) &&
                                method.parameterTypes.isEmpty()
                        }
                        ?.let {
                            method ->
                            runCatching {
                                method.invoke(throwable)
                            }
                                .getOrNull()
                                ?.toString()
                                ?.toIntOrNull()
                        }
                }
            }

        val extra =
            causes.firstNotNullOfOrNull {
                throwable ->
                listOf(
                    "getExtraMessage",
                    "getExtra",
                    "getMissingPermission",
                    "getPermission"
                ).firstNotNullOfOrNull {
                    getterName ->
                    throwable.javaClass.methods
                        .firstOrNull {
                            method ->
                            method.name.equals(
                                getterName,
                                ignoreCase = true
                            ) &&
                                method.parameterTypes.isEmpty()
                        }
                        ?.let {
                            method ->
                            runCatching {
                                method.invoke(throwable)
                            }
                                .getOrNull()
                                ?.toString()
                                ?.trim()
                                ?.takeIf { it.isNotBlank() }
                        }
                }
            }

        val insufficient =
            errorId == 2568 ||
                combined.contains("insufficient client permissions") ||
                combined.contains("insufficient permissions") ||
                combined.contains("permission denied") ||
                combined.contains("not enough permissions")

        if (insufficient) {
            return buildString {
                append("Insufficient permissions to join ")
                append(channelName)

                if (!extra.isNullOrBlank()) {
                    append(": ")
                    append(extra)
                } else if (errorId != null) {
                    append(" (TeamSpeak error ")
                    append(errorId)
                    append(")")
                }
            }
        }

        if (
            combined.contains(
                "client is flooding"
            )
        ) {
            return "Server anti-flood protection temporarily blocked the channel switch. Please wait a moment and try again."
        }

        val wrongPassword =
            combined.contains("invalid password") ||
                combined.contains("channel password")

        if (wrongPassword) {
            return "Incorrect channel password for $channelName"
        }

        return "Could not join $channelName: ${rootMessage(error)}"
    }

    fun startPushToTalk(): Result<Unit> =
        if (enableAudio) {
            audioEngine.startTransmit()
        } else {
            Result.failure(
                IllegalStateException("Audio is disabled in Low function mode")
            )
        }

    fun stopPushToTalk() {
        audioEngine.stopTransmit()
    }

    fun setPlaybackMuted(muted: Boolean) {
        audioEngine.setPlaybackMuted(muted)
    }

    fun audioStats(): AudioStats =
        audioEngine.stats()

    fun audioInputMode(): AudioInputMode = audioEngine.audioInputMode()

    fun voiceActivationThreshold(): Float = audioEngine.voiceActivationThreshold()

    fun setAudioInputSettings(mode: AudioInputMode, threshold: Float): Result<Unit> =
        audioEngine.setAudioInputSettings(mode, threshold)

    fun startAlwaysOn(): Result<Unit> = audioEngine.startAlwaysOn()

    fun stopAlwaysOn() { audioEngine.stopAlwaysOn() }

    fun isAlwaysOnActive(): Boolean = audioEngine.isAlwaysOnActive()

    fun collapsedChannels(serverKey: String): Set<Long> =
        settingsStore.collapsedChannels(serverKey)

    fun setCollapsedChannels(serverKey: String, channelIds: Set<Long>) {
        settingsStore.setCollapsedChannels(serverKey, channelIds)
    }

    /**
     * Set manual TeamSpeak microphone mute.
     *
     * Local AudioRecord state and the server-visible client_input_muted property
     * are treated as one transaction. If the server update fails, local state is
     * rolled back so the UI cannot get stuck in a false Muted/Unmuted state.
     */
    fun setInputMuted(muted: Boolean): Result<Boolean> {
        val previous = audioEngine.isInputMuted()

        if (previous == muted) {
            status = if (muted) "Microphone muted" else "Microphone unmuted"
            return Result.success(muted)
        }

        return runCatching {
            // Apply locally first. Muting immediately releases AudioRecord;
            // unmuting immediately allows the next PTT burst to create it again.
            audioEngine.setInputMuted(muted)

            val socket = socketRef.get()
            if (socket != null && connected) {
                executeClientUpdateExact(
                    socket,
                    mapOf("client_input_muted" to if (muted) "1" else "0")
                )
            }

            if (ownClientId >= 0) {
                clientCache.computeIfPresent(ownClientId) { _, client ->
                    client.copy(inputMuted = muted)
                }
            }

            status = if (muted) "Microphone muted" else "Microphone unmuted"

            muted
        }.onFailure { error ->
            // Keep local capture state consistent with the last confirmed state.
            audioEngine.setInputMuted(previous)

            if (ownClientId >= 0) {
                clientCache.computeIfPresent(ownClientId) { _, client ->
                    client.copy(inputMuted = previous)
                }
            }

            status = "Mute error: ${rootMessage(error)}"
            recordEvent(status)
            Log.e(TAG, status, rootThrowable(error))
        }
    }

    fun toggleInputMuted(): Result<Boolean> =
        setInputMuted(!audioEngine.isInputMuted())

    fun isInputMuted(): Boolean =
        audioEngine.isInputMuted()


    fun inputDevices(): List<AudioDeviceOption> =
        audioEngine.inputDevices()

    fun outputDevices(): List<AudioDeviceOption> =
        audioEngine.outputDevices()

    fun preferredInputDeviceId(): Int? =
        audioEngine.preferredInputDeviceId()

    fun preferredOutputDeviceId(): Int? =
        audioEngine.preferredOutputDeviceId()

    fun setPreferredInputDevice(id: Int?) {
        audioEngine.setPreferredInputDevice(id)
    }

    fun setPreferredOutputDevice(id: Int?): Result<Unit> =
        audioEngine.setPreferredOutputDevice(id)

    fun monitoringIntervalMinutes(): Int =
        settingsStore.monitoringIntervalMinutes()

    fun setMonitoringIntervalMinutes(minutes: Int) {
        settingsStore.setMonitoringIntervalMinutes(minutes)
    }

    fun proxySettings(): ProxySettings =
        settingsStore.proxySettings()

    fun setProxySettings(settings: ProxySettings) {
        settingsStore.setProxySettings(settings)
    }

    /**
     * Current ts3j has no public mute setter, but its own setNickname()
     * demonstrates that self-properties are published through clientupdate.
     */
    /**
     * Build clientupdate exactly like ts3j's own LocalTeamspeakClientSocket
     * setters: SingleCommand + CommandSingleParameter + executeCommand().complete().
     *
     * This avoids a string parse path and makes boolean 1/0 updates symmetrical.
     */
    private fun executeClientUpdateExact(
        socket: Any,
        properties: Map<String, String>
    ) {
        val roleClass = Class.forName(
            "com.github.manevolent.ts3j.protocol.ProtocolRole"
        )

        val roleConstants = roleClass.enumConstants
            ?: error("ts3j ProtocolRole does not expose enum constants")

        val clientRole: Any = roleConstants.firstOrNull { constant ->
            (constant as? Enum<*>)?.name == "CLIENT"
        } ?: error("ts3j ProtocolRole.CLIENT not found")

        val commandClass = Class.forName(
            "com.github.manevolent.ts3j.command.SingleCommand"
        )

        val parameterInterface = Class.forName(
            "com.github.manevolent.ts3j.command.parameter.CommandParameter"
        )

        val parameterClass = Class.forName(
            "com.github.manevolent.ts3j.command.parameter.CommandSingleParameter"
        )

        // Use the (String, ProtocolRole, List<CommandParameter>) constructor.
        val constructor = commandClass.constructors.firstOrNull { ctor ->
            val types = ctor.parameterTypes
            types.size == 3 &&
                types[0] == String::class.java &&
                types[1] == roleClass &&
                java.util.List::class.java.isAssignableFrom(types[2])
        } ?: error("ts3j SingleCommand(String, ProtocolRole, List) constructor not found")

        val command = constructor.newInstance(
            "clientupdate",
            clientRole,
            emptyList<Any>()
        )

        val parameterConstructor = parameterClass.getConstructor(
            String::class.java,
            String::class.java
        )

        val addMethod = commandClass.getMethod(
            "add",
            parameterInterface
        )

        properties.forEach { (key, value) ->
            val parameter = parameterConstructor.newInstance(key, value)
            addMethod.invoke(command, parameter)
        }

        val execute = socket.javaClass.methods.firstOrNull { method ->
            method.name == "executeCommand" &&
                method.parameterTypes.size == 1 &&
                method.parameterTypes[0].isAssignableFrom(command.javaClass)
        } ?: error("ts3j executeCommand(Command) not found")

        val response = execute.invoke(socket, command)
            ?: error("ts3j clientupdate returned no response")

        val complete = response.javaClass.methods.firstOrNull {
            it.name == "complete" && it.parameterTypes.isEmpty()
        } ?: error("ts3j CommandResponse.complete() not found")

        complete.invoke(response)


    }


    fun savedAvatarPath(): String? =
        settingsStore.avatarPath()
            .takeIf {
                it.isNotBlank() &&
                    File(it).isFile
            }

    fun savedAvatarHash(): String =
        settingsStore.avatarHash()

    fun setAvatarFromUri(
        uri: Uri,
        zoom: Float = 1f,
        offsetX: Float = 0f,
        offsetY: Float = 0f
    ): Result<String> =
        runCatching {
            val bitmap =
                decodeAvatarBitmap(
                    uri
                )

            val cropped =
                cropAvatarBitmap(
                    bitmap = bitmap,
                    zoom =
                        zoom.coerceIn(
                            1f,
                            3f
                        ),
                    offsetX =
                        offsetX.coerceIn(
                            -1f,
                            1f
                        ),
                    offsetY =
                        offsetY.coerceIn(
                            -1f,
                            1f
                        )
                )

            val encoded =
                ByteArrayOutputStream()
                    .use {
                        output ->
                        check(
                            cropped.compress(
                                Bitmap.CompressFormat.JPEG,
                                90,
                                output
                            )
                        ) {
                            "Could not encode cropped avatar"
                        }

                        output.toByteArray()
                    }

            if (
                cropped !== bitmap &&
                !cropped.isRecycled
            ) {
                cropped.recycle()
            }

            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }

            check(
                encoded.isNotEmpty()
            ) {
                "Cropped avatar is empty"
            }

            // TeamSpeak servers commonly enforce i_client_max_avatar_filesize.
            // A 256x256 JPEG normally remains comfortably below the default
            // limits while preserving enough quality for client display.
            check(
                encoded.size <=
                    512 * 1024
            ) {
                "Cropped avatar is larger than 512 KB"
            }

            val avatarDir =
                File(
                    appContext.filesDir,
                    "profile"
                ).apply {
                    mkdirs()
                }

            avatarDir
                .listFiles()
                ?.filter {
                    it.name.startsWith(
                        "avatar."
                    )
                }
                ?.forEach {
                    runCatching {
                        it.delete()
                    }
                }

            val file =
                File(
                    avatarDir,
                    "avatar.jpg"
                )

            file.writeBytes(
                encoded
            )

            val hash =
                md5Hex(
                    encoded
                )

            settingsStore.setAvatar(
                path =
                    file.absolutePath,
                hash =
                    hash
            )



            if (connected) {
                publishSavedAvatar()
                    .getOrThrow()
            }

            file.absolutePath
        }.onFailure {
            status =
                "Avatar error: " +
                    rootMessage(it)
        }

    private fun decodeAvatarBitmap(
        uri: Uri
    ): Bitmap {
        val resolver =
            appContext.contentResolver

        return if (
            android.os.Build.VERSION.SDK_INT >=
                android.os.Build.VERSION_CODES.P
        ) {
            val source =
                ImageDecoder.createSource(
                    resolver,
                    uri
                )

            ImageDecoder.decodeBitmap(
                source
            ) {
                decoder,
                _,
                _ ->
                decoder.isMutableRequired =
                    false
            }
        } else {
            resolver.openInputStream(
                uri
            )
                ?.use {
                    stream ->
                    BitmapFactory.decodeStream(
                        stream
                    )
                }
                ?: error(
                    "Could not decode selected image"
                )
        }
    }

    private fun cropAvatarBitmap(
        bitmap: Bitmap,
        zoom: Float,
        offsetX: Float,
        offsetY: Float
    ): Bitmap {
        val width =
            bitmap.width
        val height =
            bitmap.height

        check(
            width > 0 &&
                height > 0
        ) {
            "Invalid avatar dimensions"
        }

        val baseSide =
            minOf(
                width,
                height
            ).toFloat()

        val cropSide =
            (
                baseSide /
                    zoom
            )
                .toInt()
                .coerceAtLeast(1)

        val maxLeft =
            (
                width -
                    cropSide
            )
                .coerceAtLeast(0)

        val maxTop =
            (
                height -
                    cropSide
            )
                .coerceAtLeast(0)

        val centerLeft =
            maxLeft /
                2f
        val centerTop =
            maxTop /
                2f

        val left =
            (
                centerLeft +
                    offsetX *
                    centerLeft
            )
                .toInt()
                .coerceIn(
                    0,
                    maxLeft
                )

        val top =
            (
                centerTop +
                    offsetY *
                    centerTop
            )
                .toInt()
                .coerceIn(
                    0,
                    maxTop
                )

        val cropped =
            Bitmap.createBitmap(
                bitmap,
                left,
                top,
                cropSide,
                cropSide
            )

        return Bitmap.createScaledBitmap(
            cropped,
            256,
            256,
            true
        ).also {
            scaled ->
            if (
                scaled !== cropped &&
                !cropped.isRecycled
            ) {
                cropped.recycle()
            }
        }
    }

    private fun md5Hex(
        bytes: ByteArray
    ): String =
        MessageDigest
            .getInstance(
                "MD5"
            )
            .digest(bytes)
            .joinToString("") {
                "%02x".format(
                    it.toInt() and
                        0xff
                )
            }

    fun clearAvatar(): Result<Unit> =
        runCatching {
            savedAvatarPath()
                ?.let {
                    path ->
                    runCatching {
                        File(path).delete()
                    }
                }

            settingsStore.clearAvatar()

            if (connected) {
                val socket = socketRef.get() ?: error("Not connected")
                executeClientUpdateExact(
                    socket = socket,
                    properties =
                        mapOf(
                            "client_flag_avatar" to
                                ""
                        )
                )
                updateOwnAvatarMetadata(socket, "")
            }

            Unit
        }.onFailure {
            status =
                "Avatar error: " +
                    rootMessage(it)
        }

    fun publishSavedAvatar(): Result<Unit> = publishSavedAvatar(socketRef.get())

    private fun publishSavedAvatar(expectedSocket: Any?): Result<Unit> = runCatching {
        val socket = expectedSocket ?: error("Not connected")
        val transfers = fileTransfersRef.get() ?: error("File transfer session unavailable")
        check(socketRef.get() === socket && connected) { "TeamSpeak session has changed" }
        val path = savedAvatarPath() ?: error("No avatar selected")
        val bytes = File(path).readBytes()
        val hash = com.morsen.ts3xclient.protocol.TeamSpeakAvatarPublication.publish(transfers, bytes) { uploadedHash ->
            check(socketRef.get() === socket && connected) { "TeamSpeak session has changed" }
            executeClientUpdateExact(socket, mapOf("client_flag_avatar" to uploadedHash))
        }
        updateOwnAvatarMetadata(socket, hash)
        ConnectionDiagnostics.event("avatar.published", "bytes=${bytes.size}")
        Unit
    }.onFailure {
        val message = "Avatar upload error: ${rootMessage(it)}"
        Log.e(TAG, message, rootThrowable(it))
        recordEvent(message)
    }

    private fun updateOwnAvatarMetadata(expectedSocket: Any?, hash: String) {
        check(expectedSocket != null && socketRef.get() === expectedSocket && connected) {
            "TeamSpeak session has changed"
        }
        // Servers need not echo clientupdate to its sender. Refresh our own row too.
        val updated = clientCache.computeIfPresent(ownClientId) { _, previous -> previous.copy(avatarHash = hash) }
        if (updated != null) {
            checkedAvatarMetadata.remove(updated.id)
            runCatching { clientEventListener?.invoke(BackendClientEvent("selfavatar", updated)) }
                .onFailure { recordEvent("client listener warning: ${rootMessage(it)}") }
        }
    }

    fun fetchServerGroupIcons(): Result<Map<Long, Long>> = runCatching {
        Log.i("TS3X_ROLES", "Request servergrouplist")
        val socket = socketRef.get() ?: error("Not connected")
        val roleClass =
            Class.forName(
                "com.github.manevolent.ts3j.protocol.ProtocolRole"
            )

        val clientRole =
            roleClass.enumConstants
                ?.firstOrNull {
                    (
                        it as? Enum<*>
                    )?.name ==
                        "CLIENT"
                }
                ?: error(
                    "ts3j ProtocolRole.CLIENT not found"
                )

        val commandClass =
            Class.forName(
                "com.github.manevolent.ts3j.command.SingleCommand"
            )

        val constructor = commandClass.constructors.firstOrNull { ctor ->
            val types = ctor.parameterTypes
            types.size == 3 && types[0] == String::class.java &&
                types[1] == roleClass && java.util.List::class.java.isAssignableFrom(types[2])
        } ?: error("ts3j SingleCommand constructor not found")
        val command = constructor.newInstance("servergrouplist", clientRole, emptyList<Any>())
        Log.i("TS3X_ROLES", "Command instance=${command.javaClass.name}")

        val execute =
            socket.javaClass.methods
                .firstOrNull {
                    method ->
                    method.name ==
                        "executeCommand" &&
                        method.parameterTypes.size ==
                            1 &&
                        method.parameterTypes[0]
                            .isAssignableFrom(
                                command.javaClass
                            )
                }
                ?: error(
                    "ts3j executeCommand(Command) not found"
                )

        val response =
            execute.invoke(
                socket,
                command
            )
                ?: error(
                    "ts3j servergrouplist returned no response"
                )

        val commandResponseClass =
            Class.forName(
                "com.github.manevolent.ts3j.command.response.CommandResponse"
            )

        check(
            commandResponseClass
                .isInstance(
                    response
                )
        ) {
            "ts3j executeCommand returned unexpected response type: " +
                response.javaClass.name
        }

        // ClientCommandResponse is a private inner implementation class.
        // Reflecting get() from response.javaClass can fail Android access
        // checks even though get() is public. Invoke the method through the
        // public CommandResponse interface instead.
        val payload =
            commandResponseClass
                .getMethod(
                    "get"
                )
                .invoke(
                    response
                )
        Log.i("TS3X_ROLES", "Response type=${payload?.javaClass?.name ?: "null"} count=${(payload as? Collection<*>)?.size ?: -1}")

        val groups = linkedMapOf<Long, Long>()
        fun parse(item: Any?) {
            when (item) {
                null -> Unit
                is Iterable<*> -> item.forEach { parse(it) }
                is Array<*> -> item.forEach { parse(it) }
                else -> {
                    val props = extractSingleCommandParameters(item).ifEmpty { readRawMap(item) }
                    Log.i("TS3X_ROLES", "Group entry class=${item.javaClass.name} keys=${props.keys.joinToString(",").take(350)}")
                    val groupId = numberProperty(props, "sgid", "getsgid", "getservergroupid")
                    val iconId = numberProperty(props, "iconid", "icon_id", "geticonid")
                    Log.i("TS3X_ROLES", "Parsed group sgid=${groupId ?: "missing"} iconid=${iconId ?: "missing"}")
                    if (groupId != null && iconId != null) groups[groupId] = iconId and 0xffffffffL
                }
            }
        }
        parse(payload)
        // Some TeamSpeak full-client servers deliver metadata via a notify event.
        // Also retain the values already seen by the listener while processing this command.
        receivedServerGroupIcons.putAll(groups)
        // The full-client protocol may deliver group records as separate notify events.
        // Give the event dispatcher a short window to finish collecting them.
        if (receivedServerGroupIcons.isEmpty()) {
            Thread.sleep(800L)
        }
        val resolved = receivedServerGroupIcons.toMap()
        Log.i("TS3X_ROLES", "Resolved groups count=${resolved.size} ids=${resolved.entries.take(30).joinToString { "${it.key}:${it.value}" }}")
        if (resolved.isEmpty()) {
            Log.w(TAG, "Server group list returned no icon metadata")
        }
        resolved
    }.onFailure {
        if (connected) Log.e("TS3X_ROLES", "Server group list failure: ${rootMessage(it)}", it)
        if (connected) Log.w(TAG, "Server group icon list unavailable: ${rootMessage(it)}")
    }

    fun downloadServerGroupIcon(iconId: Long): Result<ByteArray> = runCatching {
        require(iconId != 0L)
        Log.i("TS3X_ROLES", "Download request /icon_${iconId and 0xffffffffL}")
        downloadRemoteFile("/icon_${iconId and 0xffffffffL}").also {
            Log.i("TS3X_ROLES", "Download success iconid=$iconId bytes=${it.size}")
        }
    }.onFailure {
        if (connected) Log.e("TS3X_ROLES", "Download failure iconid=$iconId: ${rootMessage(it)}", it)
    }

    fun downloadCurrentServerIcon(): Result<ByteArray> =
        runCatching {
            val rawIconId = serverInfo().iconId
                ?.takeIf { it != 0L }
                ?: error("Server icon is unavailable")
            downloadRemoteFile("/icon_${normalizeTeamSpeakIconId(rawIconId)}")
        }.onFailure {
            Log.w(TAG, "Server icon download failed: ${rootMessage(it)}")
        }

    /** Initial presence events can omit the avatar flag (especially for clients without an avatar). */
    fun clientAvatarMetadata(clientId: Int): Result<BackendClient> = runCatching {
        val previous = clientCache[clientId] ?: error("Client is no longer on the server")
        val flag = previous.avatarHash
        fun validUid(uid: String) = runCatching { TeamSpeakIdentity.avatarFileName(uid) }.isSuccess
        if ((flag != null && (!com.morsen.ts3xclient.protocol.TeamSpeakAvatarMetadata.isAbsent(flag) || previous.accountAvatarKnown)
                && validUid(previous.uniqueId)) || previous.accountAvatarUrl != null ||
            checkedAvatarMetadata[clientId] == avatarMetadataStamp(previous)) {
            return@runCatching previous
        }
        val socket = socketRef.get() ?: error("Not connected")
        check(connected) { "Not connected" }
        val properties = executeParameterizedCommand(socket, "clientinfo",
            mapOf("clid" to clientId.toString()), setOf("client_nickname", "client_unique_identifier"))
        check(properties.keys.any { it.startsWith("client_") }) { "Client avatar metadata unavailable" }
        // Servers may filter this property even from clientinfo. Only an explicit
        // empty/zero flag proves absence; otherwise retain the UID-based probe.
        val currentFlag = com.morsen.ts3xclient.protocol.TeamSpeakAvatarMetadata.mergeFlag(properties, previous.avatarHash)
        val accountUrl = com.morsen.ts3xclient.protocol.TeamSpeakAvatarMetadata
            .mergeAccountAvatarUrl(properties, previous.accountAvatarUrl)
        var uid = stringProperty(properties, "client_unique_identifier")
            ?.takeIf { it.isNotBlank() } ?: previous.uniqueId
        if (!validUid(uid) && !com.morsen.ts3xclient.protocol.TeamSpeakAvatarMetadata.isAbsent(currentFlag)) {
            uid = clientLookupRef.get()?.clientUid(clientId) ?: error("Client UID lookup unavailable")
        }
        check(socketRef.get() === socket && connected) { "TeamSpeak session has changed" }
        val resolved = clientCache.computeIfPresent(clientId) { _, latest ->
            if (validUid(latest.uniqueId) && validUid(uid) && latest.uniqueId != uid) latest
            else latest.copy(
                uniqueId = latest.uniqueId.takeIf { validUid(it) } ?: uid,
                // A newer notification wins over an in-flight metadata request.
                avatarHash = if (latest.avatarHash != previous.avatarHash) latest.avatarHash else currentFlag,
                accountAvatarUrl = if (latest.accountAvatarUrl != previous.accountAvatarUrl)
                    latest.accountAvatarUrl else accountUrl,
                accountAvatarKnown = latest.accountAvatarKnown ||
                    com.morsen.ts3xclient.protocol.TeamSpeakAvatarMetadata.hasAccountAvatarProperty(properties)
            )
        } ?: error("Client is no longer on the server")
        if (resolved.uniqueId == uid) checkedAvatarMetadata[clientId] = avatarMetadataStamp(resolved)
        resolved
    }

    fun downloadClientAvatar(clientUid: String): Result<ByteArray> =
        runCatching {
            val socket = socketRef.get() ?: error("Not connected")
            val remoteName = avatarRemoteFileName(clientUid)
            val bytes = downloadRemoteFile("/$remoteName")
            check(socketRef.get() === socket && connected) { "TeamSpeak session has changed" }
            require(bytes.isNotEmpty() && bytes.size <= 512 * 1024) {
                "Avatar has invalid size"
            }
            // Validate the header without allocating a full-size bitmap for every client.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) {
                "Avatar is not a recognized image"
            }
            bytes
        }.onFailure {
            if (connected) {
                val reason = rootMessage(it).takeIf { message ->
                    message.startsWith("TeamSpeak file transfer failed (status ") || message.startsWith("Avatar ")
                }.orEmpty()
                Log.w("TS3X_AVATAR", "download.failed type=${rootClass(it)} reason=$reason")
            }
        }

    private fun downloadRemoteFile(remotePath: String): ByteArray {
        check(connected) { "Not connected" }
        val transfers = fileTransfersRef.get() ?: error("File transfer session unavailable")
        val bytes = transfers.downloadSystemFile(remotePath, 512 * 1024)
        require(bytes.isNotEmpty()) { "Downloaded file is empty" }
        return bytes
    }

    private fun normalizeTeamSpeakIconId(
        iconId: Long
    ): Long =
        iconId and 0xffffffffL

    private fun avatarRemoteFileName(clientUid: String): String =
        TeamSpeakIdentity.avatarFileName(clientUid)

    private fun executeParameterizedCommand(
        socket: Any,
        commandName: String,
        properties: Map<String, String>,
        expectedKeys: Set<String>
    ): Map<String, Any?> {
        val roleClass =
            Class.forName(
                "com.github.manevolent.ts3j.protocol.ProtocolRole"
            )

        val clientRole =
            roleClass.enumConstants
                ?.firstOrNull {
                    (
                        it as? Enum<*>
                    )?.name ==
                        "CLIENT"
                }
                ?: error(
                    "ts3j ProtocolRole.CLIENT not found"
                )

        val commandClass =
            Class.forName(
                "com.github.manevolent.ts3j.command.SingleCommand"
            )

        val parameterInterface =
            Class.forName(
                "com.github.manevolent.ts3j.command.parameter.CommandParameter"
            )

        val parameterClass =
            Class.forName(
                "com.github.manevolent.ts3j.command.parameter.CommandSingleParameter"
            )

        val constructor =
            commandClass.constructors
                .firstOrNull {
                    ctor ->
                    val types =
                        ctor.parameterTypes

                    types.size == 3 &&
                        types[0] ==
                            String::class.java &&
                        types[1] ==
                            roleClass &&
                        java.util.List::class.java
                            .isAssignableFrom(
                                types[2]
                            )
                }
                ?: error(
                    "ts3j SingleCommand constructor not found"
                )

        val command =
            constructor.newInstance(
                commandName,
                clientRole,
                emptyList<Any>()
            )

        val parameterConstructor =
            parameterClass.getConstructor(
                String::class.java,
                String::class.java
            )

        val addMethod =
            commandClass.getMethod(
                "add",
                parameterInterface
            )

        properties.forEach {
            (key, value) ->
            addMethod.invoke(
                command,
                parameterConstructor
                    .newInstance(
                        key,
                        value
                    )
            )
        }

        val execute =
            socket.javaClass.methods
                .firstOrNull {
                    method ->
                    method.name ==
                        "executeCommand" &&
                        method.parameterTypes.size ==
                            1 &&
                        method.parameterTypes[0]
                            .isAssignableFrom(
                                command.javaClass
                            )
                }
                ?: error(
                    "ts3j executeCommand(Command) not found"
                )

        if (commandName == "clientgetconnectioninfo") {
            Log.i("TS3X_STATS", "protocol.execute.start command=$commandName parameters=${properties.keys.sorted()}")
        }
        val response =
            execute.invoke(
                socket,
                command
            )
                ?: error(
                    "ts3j $commandName returned no response"
                )

        if (commandName == "clientgetconnectioninfo") {
            Log.i("TS3X_STATS", "protocol.response.arrived command=$commandName responseType=${response.javaClass.name}")
        }
        val commandResponseClass =
            Class.forName(
                "com.github.manevolent.ts3j.command.response.CommandResponse"
            )

        check(
            commandResponseClass
                .isInstance(
                    response
                )
        ) {
            "ts3j executeCommand returned unexpected response type: " +
                response.javaClass.name
        }

        // ClientCommandResponse is a private inner implementation class.
        // Reflecting get() from response.javaClass can fail Android access
        // checks even though get() is public. Invoke the method through the
        // public CommandResponse interface instead.
        val payload =
            commandResponseClass
                .getMethod(
                    "get"
                )
                .invoke(
                    response
                )
        if (commandName == "clientgetconnectioninfo") {
            val count = when (payload) {
                is Collection<*> -> payload.size
                is Array<*> -> payload.size
                null -> 0
                else -> 1
            }
            Log.i("TS3X_STATS", "protocol.payload command=$commandName type=${payload?.javaClass?.name ?: "null"} count=$count")
        }

        val result =
            linkedMapOf<String, Any?>()

        fun extractPayloadCommands(
            target: Any?
        ) {
            when (target) {
                null ->
                    Unit

                is Iterable<*> ->
                    target.forEach {
                        item ->
                        extractPayloadCommands(
                            item
                        )
                    }

                is Array<*> ->
                    target.forEach {
                        item ->
                        extractPayloadCommands(
                            item
                        )
                    }

                else -> {
                    if (
                        target.javaClass.name ==
                            "com.github.manevolent.ts3j.command.SingleCommand"
                    ) {
                        val parameters =
                            extractSingleCommandParameters(
                                target
                            )



                        parameters.forEach {
                            (key, value) ->
                            result[
                                key.lowercase()
                            ] =
                                value
                        }
                    }
                }
            }
        }

        extractPayloadCommands(
            payload
        )

        // Keep a bounded generic fallback for future ts3j response shapes, but
        // never prefer wrapper helper members over real SingleCommand values.
        if (result.isEmpty()) {
            val visited =
                java.util.Collections
                    .newSetFromMap(
                        java.util.IdentityHashMap<
                            Any,
                            Boolean
                        >()
                    )

            collectCommandProperties(
                target =
                    payload,
                result =
                    result,
                visited =
                    visited,
                depth =
                    0
            )
        }

        val rawCandidates =
            listOfNotNull(
                payload?.toString(),
                response.toString()
            )

        expectedKeys.forEach {
            key ->
            if (
                result[key]
                    ?.toString()
                    ?.isNotBlank() !=
                    true
            ) {
                rawCandidates
                    .firstNotNullOfOrNull {
                        raw ->
                        extractSimpleProtocolProperty(
                            raw,
                            key
                        )
                    }
                    ?.let {
                        value ->
                        result[key] =
                            value
                    }
            }
        }



        if (
            expectedKeys.isNotEmpty() &&
            expectedKeys.none {
                key ->
                result[key]
                    ?.toString()
                    ?.isNotBlank() ==
                    true
            }
        ) {
            Log.w(
                TAG,
                "$commandName response contained none of the expected protocol keys; " +
                    "responseClass=${response.javaClass.name} " +
                    "payloadClass=${payload?.javaClass?.name ?: "<null>"}"
            )
        }

        return result
    }

    private fun extractSimpleProtocolProperty(
        raw: String,
        key: String
    ): String? {
        val match =
            Regex(
                """(?i)(?:^|[\s,\{\[\(])""" +
                    Regex.escape(key) +
                    """\s*(?:=|:)\s*([^\s,\}\]\)]+)"""
            )
                .find(raw)
                ?: return null

        return decodeTs3CommandString(
            match.groupValues[1]
                .trim()
                .trim(
                    '"',
                    '\''
                )
        )
            .takeIf {
                it.isNotBlank()
            }
    }


    fun savedNickname(): String =
        localNickname(settingsStore.nickname())

    fun setClientNickname(value: String): Result<String> =
        runCatching {
            val normalized = localNickname(value.trim()).takeIf { it.isNotBlank() }
                ?: error("Nickname cannot be empty")

            val socket = socketRef.get()
            if (connected && socket != null) {
                invokeIfPresent(socket, "setNickname", serverNickname(normalized))
                    ?: error("ts3j setNickname(String) not found")
            }

            settingsStore.setNickname(normalized)

            if (ownClientId >= 0) {
                clientCache.computeIfPresent(ownClientId) { _, client ->
                    client.copy(nickname = normalized)
                }
            }


            normalized
        }.onFailure {
            status = "Nickname error: ${rootMessage(it)}"
            Log.e(TAG, status, rootThrowable(it))
        }

    /**
     * Inspect the currently persisted TeamSpeak identity without changing it.
     * The exported identity is a credential and must be treated as secret.
     */
    fun identityInfo(): IdentityInfo {
        val exported = prefs.getString(KEY_IDENTITY, null)
        val backup = !prefs.getString(KEY_IDENTITY_BACKUP, null).isNullOrBlank()

        if (exported.isNullOrBlank()) {
            return IdentityInfo(
                saved = false,
                clientUid = observedClientUid.orEmpty(),
                backupAvailable = backup
            )
        }

        return runCatching {
            val identityClass = Class.forName(identityClassName)
            val identity = readIdentityExport(identityClass, exported)
            describeIdentity(identity, exported, backup)
        }.getOrElse { error ->
            IdentityInfo(
                saved = true,
                clientUid = observedClientUid.orEmpty(),
                exportedIdentity = exported,
                loadError = rootMessage(error),
                backupAvailable = backup
            )
        }
    }

    fun exportIdentity(): String? =
        prefs.getString(KEY_IDENTITY, null)?.takeIf { it.isNotBlank() }

    fun importIdentity(exported: String): Result<IdentityInfo> =
        runCatching {
            check(!connected) {
                "Disconnect before changing TeamSpeak identity"
            }

            val trimmed = exported.trim()
            check(trimmed.isNotBlank()) {
                "Identity export is empty"
            }

            val identityClass = Class.forName(identityClassName)
            val identity = readIdentityExport(identityClass, trimmed)

            val canonical = exportIdentityObject(identity)
            verifyIdentityRoundTrip(identityClass, canonical)

            persistIdentity(canonical, backupCurrent = true)
            status = "Identity imported and saved"

            describeIdentity(identity, canonical, backupAvailable = true)
        }.onFailure {
            status = "Identity import error: ${rootMessage(it)}"
            Log.e(TAG, status, rootThrowable(it))
        }

    fun generateNewIdentity(securityLevel: Int): Result<IdentityInfo> =
        runCatching {
            check(!connected) {
                "Disconnect before generating a new TeamSpeak identity"
            }

            val level = securityLevel.coerceIn(0, 30)
            val identityClass = Class.forName(identityClassName)
            val generate = identityClass.getMethod(
                "generateNew",
                Int::class.javaPrimitiveType
            )

            status = "Generating TeamSpeak identity…"
            val identity: Any = generate.invoke(null, level)
                ?: error("LocalIdentity.generateNew() returned null")
            val exported = exportIdentityObject(identity)

            verifyIdentityRoundTrip(identityClass, exported)
            persistIdentity(exported, backupCurrent = true)

            status = "New identity generated and saved"

            describeIdentity(identity, exported, backupAvailable = true)
        }.onFailure {
            status = "Identity generation error: ${rootMessage(it)}"
            Log.e(TAG, status, rootThrowable(it))
        }

    fun restoreIdentityBackup(): Result<IdentityInfo> =
        runCatching {
            check(!connected) {
                "Disconnect before restoring TeamSpeak identity"
            }

            val backup = prefs.getString(KEY_IDENTITY_BACKUP, null)
                ?.takeIf { it.isNotBlank() }
                ?: error("No identity backup is available")

            val identityClass = Class.forName(identityClassName)
            val identity = readIdentityExport(identityClass, backup)
            val canonical = exportIdentityObject(identity)
            verifyIdentityRoundTrip(identityClass, canonical)

            val current = prefs.getString(KEY_IDENTITY, null)
            val editor = prefs.edit()
                .putString(KEY_IDENTITY, canonical)

            if (!current.isNullOrBlank() && current != canonical) {
                editor.putString(KEY_IDENTITY_BACKUP, current)
            }

            check(editor.commit()) {
                "Could not persist restored identity"
            }

            status = "Identity backup restored"

            describeIdentity(
                identity,
                canonical,
                backupAvailable = !current.isNullOrBlank()
            )
        }.onFailure {
            status = "Identity restore error: ${rootMessage(it)}"
            Log.e(TAG, status, rootThrowable(it))
        }


    fun diagnostics(): String {
        val socket = socketRef.get()
        val savedIdentity = prefs.getString(KEY_IDENTITY, null)
        val events = synchronized(eventLogLock) { recentEvents.toList() }

        return buildString {
            appendLine("Backend: ts3j")
            appendLine("Server protocol: ${serverProtocolRef.get()}")
            appendLine("System files: TeamSpeak TCP (avatars/icons, cid=0)")
            appendLine("TS3X Client Beta")
            appendLine("Connected: $connected")
            appendLine("Status: $status")
            val identity = identityInfo()
            appendLine("Identity saved: ${identity.saved}")
            appendLine("Identity UID: ${identity.clientUid.ifBlank { "<unknown>" }}")
            appendLine(
                "Identity public key: " +
                    identity.publicKey.take(80).ifBlank { "<unknown>" }
            )
            appendLine("Identity security level: ${identity.securityLevel ?: "<unknown>"}")
            appendLine("Identity load error: ${identity.loadError ?: "<none>"}")
            appendLine("Own client id: $ownClientId")
            appendLine("Current channel id: ${currentChannelId ?: "<unknown>"}")
            appendLine("Cached channels: ${channelCache.size}")
            appendLine("Cached clients: ${clientCache.size}")
            appendLine("Socket: ${socket?.javaClass?.name ?: "<none>"}")
            appendLine(
                "Server name: ${serverName() ?: "<not received>"}"
            )
            val audio = audioEngine.stats()
            appendLine(
                "Audio: attached=${audio.attached} tx=${audio.transmitting} " +
                    "inputMuted=${audio.inputMuted} outputMuted=${audio.playbackMuted} " +
                    "txPackets=${audio.txPackets} " +
                    "rxPackets=${audio.rxPackets} readyCalls=${audio.micReadyCalls} " +
                    "provideCalls=${audio.micProvideCalls} speakers=${audio.activeSpeakers}"
            )
            appendLine("Audio error: ${audio.lastError ?: "<none>"}")
            appendLine()
            appendLine("Channels:")
            channelCache.values.sortedBy { it.id }.forEach {
                appendLine(
                    "#${it.id} pid=${it.parentId} order=${it.order} " +
                        "name=${it.name} icon=${it.iconId} pw=${it.hasPassword} permanent=${it.permanent} firstSeen=${it.firstSeen}"
                )
            }
            appendLine()
            appendLine("Recent warnings/errors:")
            if (events.isEmpty()) {
                appendLine("<none>")
            } else {
                events.forEach { appendLine(it) }
            }
        }
    }

    /**
     * Read a ts3j SingleCommand through its actual public API.
     *
     * SingleCommand exposes getParameters(): Iterable<CommandParameter>. It does
     * not expose toMap(). Each CommandParameter exposes its protocol name and,
     * depending on its concrete subtype/revision, a value through getValue(),
     * getRawValue(), getStringValue(), or a backing value field.
     */
    private fun extractSingleCommandParameters(
        command: Any
    ): Map<String, Any?> {
        val result =
            linkedMapOf<String, Any?>()

        val getParameters =
            command.javaClass.methods
                .firstOrNull {
                    it.name ==
                        "getParameters" &&
                        it.parameterTypes
                            .isEmpty()
                }
                ?: return result

        val iterable =
            runCatching {
                getParameters.invoke(
                    command
                )
            }.getOrNull()

        val parameters =
            when (iterable) {
                is Iterable<*> ->
                    iterable

                is Array<*> ->
                    iterable.asIterable()

                else ->
                    emptyList<Any?>()
            }

        parameters.forEach {
            parameter ->
            if (parameter == null) {
                return@forEach
            }

            val name =
                invokeAnyGetter(
                    parameter,
                    "getName",
                    "name"
                )
                    ?.toString()
                    ?.trim()
                    ?.lowercase()
                    ?.takeIf {
                        it.isNotBlank()
                    }
                    ?: return@forEach

            var value:
                Any? = null

            val preferredGetters =
                listOf(
                    "getValue",
                    "getRawValue",
                    "getStringValue",
                    "value"
                )

            preferredGetters.forEach {
                getterName ->
                if (value != null) {
                    return@forEach
                }

                val getter =
                    parameter.javaClass.methods
                        .firstOrNull {
                            it.name.equals(
                                getterName,
                                ignoreCase =
                                    true
                            ) &&
                                it.parameterTypes
                                    .isEmpty()
                        }

                if (getter != null) {
                    value =
                        runCatching {
                            getter.invoke(
                                parameter
                            )
                        }.getOrNull()
                }
            }

            if (value == null) {
                val valueField =
                    parameter.javaClass
                        .declaredFields
                        .firstOrNull {
                            it.name.equals(
                                "value",
                                ignoreCase =
                                    true
                            )
                        }

                if (valueField != null) {
                    value =
                        runCatching {
                            valueField
                                .isAccessible =
                                true

                            valueField.get(
                                parameter
                            )
                        }.getOrNull()
                }
            }

            // Some CommandParameter implementations wrap a scalar one level
            // deeper. Reuse the existing bounded scalar unwrapping.
            val scalar =
                unwrapCommandScalar(
                    value
                )
                    ?: value

            if (scalar != null) {
                result[name] =
                    scalar
            } else {
                // A flag-only parameter still has semantic presence. Preserve
                // an empty string instead of dropping its key.
                result.putIfAbsent(
                    name,
                    ""
                )
            }

            if (
                name.startsWith(
                    "virtualserver_"
                )
            ) {

            }
        }

        return result
    }

    private fun installInitServerTap(
        socket: Any
    ) {
        val field =
            socket.javaClass
                .declaredFields
                .firstOrNull {
                    it.name ==
                        "namedProcessors" &&
                        java.util.Map::class.java
                            .isAssignableFrom(
                                it.type
                            )
                }
                ?: run {
                    Log.w(
                        TAG,
                        "ts3j namedProcessors field not found; initserver tap unavailable"
                    )
                    return
                }

        val processorMap =
            runCatching {
                field.isAccessible = true

                @Suppress(
                    "UNCHECKED_CAST"
                )
                field.get(socket) as?
                    MutableMap<
                        String,
                        Any
                    >
            }.getOrNull()
                ?: run {
                    Log.w(
                        TAG,
                        "ts3j namedProcessors map unavailable"
                    )
                    return
                }

        val original =
            processorMap["initserver"]
                ?: run {
                    Log.w(
                        TAG,
                        "ts3j initserver processor not found"
                    )
                    return
                }

        val processorType =
            runCatching {
                Class.forName(
                    "com.github.manevolent.ts3j.command.CommandProcessor"
                )
            }.getOrNull()
                ?: original.javaClass.interfaces
                    .firstOrNull {
                        it.simpleName ==
                            "CommandProcessor"
                    }
                ?: run {
                    Log.w(
                        TAG,
                        "ts3j CommandProcessor type not found"
                    )
                    return
                }

        if (!processorType.isInterface) {
            Log.w(
                TAG,
                "ts3j CommandProcessor is not an interface"
            )
            return
        }

        val tap =
            Proxy.newProxyInstance(
                processorType.classLoader,
                arrayOf(
                    processorType
                )
            ) {
                proxy,
                method,
                args ->

                when (method.name) {
                    "toString" ->
                        "TS3XInitServerTap"

                    "hashCode" ->
                        System.identityHashCode(
                            proxy
                        )

                    "equals" ->
                        proxy ===
                            args
                                ?.firstOrNull()

                    "process" -> {
                        val command =
                            args
                                ?.getOrNull(1)

                        if (command != null && socketRef.get() === socket) {
                            runCatching {
                                val merged =
                                    extractSingleCommandParameters(
                                        command
                                    )
                                        .toMutableMap()

                                // Keep raw parsing only as a secondary path.
                                extractServerInfoPropertiesFromRaw(
                                    command.toString()
                                ).forEach {
                                    (key, value) ->
                                    merged.putIfAbsent(
                                        key.lowercase(),
                                        value
                                    )
                                }





                                captureServerMetadata(
                                    eventName =
                                        "initserver",
                                    properties =
                                        merged
                                )
                            }.onFailure {
                                Log.w(
                                    TAG,
                                    "initserver capture failed: " +
                                        rootMessage(it)
                                )
                            }
                        }

                        // Preserve stock ts3j connection behaviour.
                        method.invoke(
                            original,
                            *(args ?: emptyArray())
                        )
                    }

                    else ->
                        method.invoke(
                            original,
                            *(args ?: emptyArray())
                        )
                }
            }

        processorMap[
            "initserver"
        ] = tap


    }

    private fun installEventListener(socket: Any) {
        val addListener = socket.javaClass.methods.firstOrNull { method ->
            method.name == "addListener" &&
                method.parameterTypes.size == 1 &&
                method.parameterTypes[0].isInterface
        } ?: error("ts3j addListener(TS3Listener) method not found")

        val listenerType = addListener.parameterTypes[0]

        val listener = Proxy.newProxyInstance(
            listenerType.classLoader,
            arrayOf(listenerType)
        ) { proxy, method, args ->
            when (method.name) {
                "toString" -> "TS3XListenerProxy"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> {
                    if (socketRef.get() === socket && !args.isNullOrEmpty() && args[0] != null) {
                        runCatching {
                            handleEvent(method.name, args[0])
                        }.onFailure {
                            recordEvent(
                                "${method.name}: handler warning: ${rootMessage(it)}"
                            )
                        }
                    }
                    null
                }
            }
        }

        addListener.invoke(socket, listener)
    }

    private fun handleEvent(callbackName: String, event: Any) {
        val raw = readRawMap(event)
        val eventTypes =
            "${callbackName.lowercase()} ${event.javaClass.simpleName.lowercase()}"

        val protocolCommand =
            stringProperty(
                raw,
                "getcommand",
                "command"
            )
                ?.trim()
                ?.lowercase()
        val combined = "$eventTypes ${protocolCommand.orEmpty()}"
        if (protocolCommand == "notifyclientuidfromclid" || protocolCommand == "notifyclientdbidfromuid") {
            clientLookupRef.get()?.onNotification(protocolCommand, raw)
            return
        }

        // TS3 full-client connection statistics may arrive asynchronously rather
        // than in executeCommand's immediate acknowledgement.
        if (protocolCommand == "notifyclientconnectioninfo" ||
            protocolCommand == "notifyconnectioninfo" ||
            "clientconnectioninfo" in combined || "connectioninfo" in combined) {
            val statistics = readRawMap(event).toMutableMap()
            val rawParams = invokeAnyGetter(event, "getCommand", "command")
            if (rawParams != null && rawParams.javaClass.name.endsWith("SingleCommand")) {
                statistics.putAll(extractSingleCommandParameters(rawParams))
            }
            Log.i("TS3X_STATS", "notification keys=${statistics.keys.sorted()}")
            pendingConnectionInfo.get()?.complete(statistics)
        }

        if (callbackName.equals("onTextMessage", ignoreCase = true) ||
            protocolCommand == "notifytextmessage" || "textmessageevent" in combined) {
            onChatEvent(event, raw)
            return
        }

        // Incoming TeamSpeak clientpoke notification. Its event is delivered via
        // TS3Listener independently of the UI Activity, including in background.
        if ("poke" in combined || protocolCommand == "notifyclientpoke") {
            val sender = stringProperty(
                raw, "invokername", "getinvokername", "getinvokernickname",
                "nickname", "getnickname"
            )?.takeIf { it.isNotBlank() }?.let(::localNickname) ?: "TeamSpeak user"
            val message = stringProperty(
                raw, "msg", "message", "getmessage", "getmsg"
            )?.takeIf { it.isNotBlank() } ?: "Poke"
            incomingPokeListener?.invoke(sender, message)
            Log.i("TS3X_POKE", "Incoming poke from $sender")
        }

        // Both generations return transfer starts asynchronously. The shared
        // transport matches clientftfid so a late event cannot start another file.
        if (protocolCommand != null) fileTransfersRef.get()?.onNotification(protocolCommand, raw)

        if (protocolCommand == "notifychannelgrouplist" ||
            "channelgrouplist" in combined) {
            val cgid = numberProperty(raw, "cgid", "getcgid", "channel_group_id")
            if (cgid != null) {
                stringProperty(raw, "name", "getname")?.takeIf { it.isNotBlank() }?.let {
                    receivedChannelGroupNames[cgid] = it
                }
                numberProperty(raw, "type", "gettype")?.let { receivedChannelGroupTypes[cgid] = it.toInt() }
                numberProperty(raw, "iconid", "icon_id", "geticonid")?.let {
                    receivedChannelGroupIcons[cgid] = it and 0xffffffffL
                }
                Log.i("TS3X_GROUPS", "channelgroup event id=$cgid name=${receivedChannelGroupNames[cgid]} icon=${receivedChannelGroupIcons[cgid]}")
            }
        }

        if (protocolCommand == "notifyservergrouplist" ||
            "servergrouplist" in combined) {
            val sgid = numberProperty(raw, "sgid", "getsgid", "getservergroupid")
            val icon = numberProperty(raw, "iconid", "icon_id", "geticonid")
            val groupType = numberProperty(raw, "type", "gettype", "grouptype")?.toInt()
            Log.i("TS3X_ROLES", "Notify group event=$protocolCommand keys=${raw.keys.joinToString(",").take(350)} sgid=${sgid ?: "missing"} iconid=${icon ?: "missing"}")
            if (sgid != null) {
                if (icon != null) receivedServerGroupIcons[sgid] = icon and 0xffffffffL
                if (groupType != null) receivedServerGroupTypes[sgid] = groupType
                stringProperty(raw, "name", "getname")?.takeIf { it.isNotBlank() }?.let {
                    receivedServerGroupNames[sgid] = it
                }
            }
        }

        captureServerMetadata(combined, raw)

        // ts3j event wrappers differ between revisions. Some expose only a
        // subset through getMap()/typed getters but keep the original TeamSpeak
        // command in toString(). Extract exact protocol keys from that raw text
        // as an independent path.
        val eventRawProperties =
            extractServerInfoPropertiesFromRaw(
                event.toString()
            )

        if (eventRawProperties.isNotEmpty()) {


            captureServerMetadata(
                eventName =
                    "$combined-raw",
                properties =
                    eventRawProperties
            )
        }

        if ("channellist" in combined || "clientmov" in combined) {

        }

        if (applyGroupMembershipEvent(combined, raw)) return

        when {
            callbackName.equals("onDisconnected", ignoreCase = true) -> {
                sessionTasks.stop()
                connected = false
                fileTransfersRef.getAndSet(null)?.close()
                clientLookupRef.getAndSet(null)?.close()
                currentChannelId = null
                status = "Disconnected by server"
                disconnectNotice.countDown()
            }

            "client" in combined -> processClientEvent(combined, raw)
            "channel" in combined -> processChannelEvent(combined, raw)
        }
    }

    /** TS3 and TS6 deliver role deltas separately from the full client snapshot. */
    private fun applyGroupMembershipEvent(event: String, properties: Map<String, Any?>): Boolean {
        val addServerGroup = "servergroupclientadded" in event
        val removeServerGroup = "servergroupclientdeleted" in event
        val channelGroupChanged = "clientchannelgroupchanged" in event
        if (!addServerGroup && !removeServerGroup && !channelGroupChanged) return false
        val clientId = numberProperty(properties, "clid", "getclientid")?.toInt()?.takeIf { it > 0 }
        val dbid = numberProperty(properties, "cldbid", "client_database_id")?.takeIf { it > 0 }
        val targetIds = if (clientId != null) listOf(clientId) else clientCache.values
            .filter { dbid != null && it.databaseId == dbid }.map { it.id }
        val groupId = if (channelGroupChanged) {
            numberProperty(properties, "cgid", "getchannelgroupid")
        } else numberProperty(properties, "sgid", "getservergroupid")
        if (groupId == null || groupId <= 0) return true
        targetIds.forEach { id ->
            val updated = clientCache.computeIfPresent(id) { _, previous ->
                when {
                    channelGroupChanged -> previous.copy(channelGroupId = groupId)
                    addServerGroup -> previous.copy(serverGroupIds = (previous.serverGroupIds + groupId).distinct())
                    else -> previous.copy(serverGroupIds = previous.serverGroupIds.filterNot { it == groupId })
                }
            }
            if (updated != null) runCatching { clientEventListener?.invoke(BackendClientEvent(event, updated)) }
        }
        return true
    }

    /**
     * Explicit ts3j API path.
     *
     * Current ts3j exposes:
     *   com.github.manevolent.ts3j.api.VirtualServerInfo
     *   com.github.manevolent.ts3j.api.VirtualServerProperty
     *
     * Earlier revisions of this Android client tried to infer these values from
     * generic wrapper maps. That works for many fields but is unreliable for
     * CREATED and the host-banner properties. This method finds the typed
     * VirtualServerInfo object and asks it for specific VirtualServerProperty
     * enum values through any property accessor exposed by the loaded revision.
     */
    private fun harvestVirtualServerPropertiesByEnum(
        socket: Any
    ) {
        val infoClass =
            runCatching {
                Class.forName(
                    "com.github.manevolent.ts3j.api.VirtualServerInfo"
                )
            }.getOrNull()
                ?: return

        val propertyClass =
            runCatching {
                Class.forName(
                    "com.github.manevolent.ts3j.api.VirtualServerProperty"
                )
            }.getOrNull()
                ?: return

        val infoObjects =
            linkedSetOf<Any>()

        val visited =
            java.util.Collections
                .newSetFromMap(
                    java.util.IdentityHashMap<
                        Any,
                        Boolean
                    >()
                )

        fun findInfoObjects(
            target: Any?,
            depth: Int
        ) {
            if (
                target == null ||
                depth > 5
            ) {
                return
            }

            when (target) {
                is CharSequence,
                is Number,
                is Boolean,
                is Enum<*> -> return
            }

            if (!visited.add(target)) {
                return
            }

            if (
                infoClass.isInstance(
                    target
                )
            ) {
                infoObjects += target
                return
            }

            if (target is Map<*, *>) {
                target.values
                    .take(128)
                    .forEach {
                        findInfoObjects(
                            it,
                            depth + 1
                        )
                    }
                return
            }

            if (target is Iterable<*>) {
                target.take(128)
                    .forEach {
                        findInfoObjects(
                            it,
                            depth + 1
                        )
                    }
                return
            }

            if (target.javaClass.isArray) {
                val length =
                    java.lang.reflect.Array
                        .getLength(target)
                        .coerceAtMost(128)

                for (
                    index in 0 until length
                ) {
                    findInfoObjects(
                        java.lang.reflect.Array
                            .get(
                                target,
                                index
                            ),
                        depth + 1
                    )
                }
                return
            }

            target.javaClass
                .declaredFields
                .asSequence()
                .filterNot {
                    java.lang.reflect.Modifier
                        .isStatic(it.modifiers)
                }
                .filter { field ->
                    val n =
                        field.name.lowercase()

                    infoClass.isAssignableFrom(
                        field.type
                    ) ||
                        n.contains("server") ||
                        n.contains("virtual") ||
                        n.contains("state") ||
                        n.contains("info") ||
                        n.contains("variable") ||
                        n.contains("propert")
                }
                .take(96)
                .forEach { field ->
                    val nested =
                        runCatching {
                            field.isAccessible =
                                true
                            field.get(target)
                        }.getOrNull()

                    findInfoObjects(
                        nested,
                        depth + 1
                    )
                }

            target.javaClass.methods
                .asSequence()
                .filter {
                    it.parameterTypes
                        .isEmpty()
                }
                .filterNot {
                    it.name == "getClass"
                }
                .filter { method ->
                    val n =
                        method.name.lowercase()

                    infoClass.isAssignableFrom(
                        method.returnType
                    ) ||
                        n.contains("server") ||
                        n.contains("virtual") ||
                        n.contains("state") ||
                        n.contains("info")
                }
                .take(80)
                .forEach { method ->
                    val nested =
                        runCatching {
                            method.invoke(target)
                        }.getOrNull()

                    if (
                        nested !== target
                    ) {
                        findInfoObjects(
                            nested,
                            depth + 1
                        )
                    }
                }
        }

        findInfoObjects(
            socket,
            0
        )

        if (infoObjects.isEmpty()) {

            return
        }

        val enumConstants =
            propertyClass.enumConstants
                ?.filterIsInstance<Enum<*>>()
                .orEmpty()

        if (enumConstants.isEmpty()) {
            return
        }

        fun protocolPropertyName(
            constant: Enum<*>
        ): String {
            val getName =
                constant.javaClass.methods
                    .firstOrNull {
                        it.name == "getName" &&
                            it.parameterTypes
                                .isEmpty()
                    }

            return runCatching {
                getName
                    ?.invoke(constant)
                    ?.toString()
            }
                .getOrNull()
                ?.trim()
                ?.lowercase()
                ?.takeIf {
                    it.isNotBlank()
                }
                ?: constant.name
                    .lowercase()
        }

        fun enumFor(
            wanted: String
        ): Enum<*>? {
            val protocolKey =
                wanted
                    .trim()
                    .lowercase()

            return enumConstants
                .firstOrNull {
                    constant ->
                    protocolPropertyName(
                        constant
                    ) == protocolKey
                }
                ?: enumConstants
                    .firstOrNull {
                        constant ->
                        val name =
                            constant.name
                                .lowercase()

                        name ==
                            protocolKey ||
                            "virtualserver_$name" ==
                                protocolKey
                    }
        }

        val requested =
            linkedMapOf(
                "virtualserver_name" to
                    "virtualserver_name",
                "virtualserver_unique_identifier" to
                    "virtualserver_unique_identifier",
                "virtualserver_platform" to
                    "virtualserver_platform",
                "virtualserver_version" to
                    "virtualserver_version",
                "virtualserver_status" to
                    "virtualserver_status",
                "virtualserver_created" to
                    "virtualserver_created",
                "virtualserver_uptime" to
                    "virtualserver_uptime",
                "virtualserver_maxclients" to
                    "virtualserver_maxclients",
                "virtualserver_clientsonline" to
                    "virtualserver_clientsonline",
                "virtualserver_channelsonline" to
                    "virtualserver_channelsonline",
                "virtualserver_welcomemessage" to
                    "virtualserver_welcomemessage",
                "virtualserver_hostmessage" to
                    "virtualserver_hostmessage",
                "virtualserver_hostbanner_url" to
                    "virtualserver_hostbanner_url",
                "virtualserver_hostbanner_gfx_url" to
                    "virtualserver_hostbanner_gfx_url",
                "virtualserver_hostbutton_url" to
                    "virtualserver_hostbutton_url",
                "virtualserver_hostbutton_gfx_url" to
                    "virtualserver_hostbutton_gfx_url",
                "virtualserver_hostbutton_tooltip" to
                    "virtualserver_hostbutton_tooltip",
                "virtualserver_icon_id" to
                    "virtualserver_icon_id",
                "virtualserver_flag_password" to
                    "virtualserver_flag_password",
                "virtualserver_reserved_slots" to
                    "virtualserver_reserved_slots"
            )

        infoObjects.forEach { info ->
            val result =
                linkedMapOf<String, Any?>()

            // Wrapper implementations frequently expose get(Property) /
            // getString(Property) / getLong(Property). Try every one-argument
            // accessor taking VirtualServerProperty and keep the first useful
            // scalar for each requested property.
            val accessors =
                info.javaClass.methods
                    .asSequence()
                    .filter {
                        it.parameterTypes.size == 1 &&
                            it.parameterTypes[0]
                                .isAssignableFrom(
                                    propertyClass
                                )
                    }
                    .filter {
                        val n =
                            it.name.lowercase()

                        n == "get" ||
                            n.startsWith("get") ||
                            n.contains("property") ||
                            n.contains("value")
                    }
                    .toList()

            requested.forEach {
                (outputKey, enumName) ->

                val property =
                    enumFor(enumName)
                        ?: return@forEach

                val preferredNames =
                    when (outputKey) {
                        "virtualserver_created",
                        "virtualserver_uptime",
                        "virtualserver_icon_id" ->
                            listOf(
                                "getLong",
                                "get"
                            )

                        "virtualserver_maxclients",
                        "virtualserver_clientsonline",
                        "virtualserver_channelsonline",
                        "virtualserver_reserved_slots" ->
                            listOf(
                                "getInt",
                                "getLong",
                                "get"
                            )

                        "virtualserver_flag_password" ->
                            listOf(
                                "getBoolean",
                                "getInt",
                                "get"
                            )

                        else ->
                            listOf(
                                "get",
                                "getString",
                                "getValue"
                            )
                    }

                val orderedAccessors =
                    accessors.sortedBy {
                        accessor ->
                        val index =
                            preferredNames
                                .indexOfFirst {
                                    accessor.name
                                        .equals(
                                            it,
                                            ignoreCase =
                                                true
                                        )
                                }

                        if (index >= 0) {
                            index
                        } else {
                            100
                        }
                    }

                for (
                    accessor in
                    orderedAccessors
                ) {
                    val raw =
                        runCatching {
                            accessor.invoke(
                                info,
                                property
                            )
                        }.getOrNull()
                            ?: continue

                    val scalar =
                        unwrapCommandScalar(
                            raw
                        )
                            ?: when (raw) {
                                is Number,
                                is Boolean,
                                is CharSequence,
                                is java.util.Date,
                                is java.time.Instant ->
                                    raw

                                else -> null
                            }

                    val stringValue =
                        scalar
                            ?.toString()
                            ?.trim()
                            .orEmpty()

                    val unusableDefault =
                        stringValue.isBlank() ||
                            (
                                outputKey !=
                                    "virtualserver_flag_password" &&
                                    stringValue ==
                                        "-1"
                            )

                    if (!unusableDefault) {
                        result[outputKey] =
                            scalar
                        break
                    }
                }
            }

            // Also inspect a typed map if Wrapper exposes one.
            readRawMap(info).forEach {
                (key, value) ->
                result.putIfAbsent(
                    key,
                    value
                )
            }

            if (result.isNotEmpty()) {


                captureServerMetadata(
                    eventName =
                        "VirtualServerProperty",
                    properties = result
                )
            }
        }
    }

    /**
     * Prefer ts3j's typed VirtualServerInfo/VirtualServer object whenever the
     * loaded revision exposes one. The project contains VirtualServerInfo and
     * VirtualServerProperty API types, but method names differ between revisions,
     * so this remains reflection-compatible with the current -SNAPSHOT setup.
     */
    private fun harvestTypedVirtualServerInfo(
        socket: Any
    ) {
        val methodNames = listOf(
            "getServerInfo",
            "getVirtualServerInfo",
            "getVirtualServer",
            "getServerVariables"
        )

        methodNames.forEach { methodName ->
            val method =
                socket.javaClass.methods
                    .firstOrNull {
                        it.name.equals(
                            methodName,
                            ignoreCase = true
                        ) &&
                            it.parameterTypes.isEmpty()
                    }
                    ?: return@forEach

            val info =
                runCatching {
                    method.invoke(socket)
                }.getOrNull()
                    ?: return@forEach

            if (info === socket) {
                return@forEach
            }

            val className =
                info.javaClass.name.lowercase()

            if (
                !className.contains("server") &&
                !className.contains("virtual")
            ) {
                return@forEach
            }

            val properties =
                linkedMapOf<String, Any?>()

            fun put(
                key: String,
                vararg getterNames: String
            ) {
                val value =
                    invokeAnyGetter(
                        info,
                        *getterNames
                    )
                        ?: return

                properties[key] = value
            }

            put(
                "virtualserver_name",
                "getName",
                "getVirtualServerName"
            )
            put(
                "virtualserver_unique_identifier",
                "getUniqueIdentifier",
                "getUid",
                "getUID"
            )
            put(
                "virtualserver_platform",
                "getPlatform",
                "getServerPlatform"
            )
            put(
                "virtualserver_version",
                "getVersion",
                "getServerVersion"
            )
            put(
                "virtualserver_status",
                "getStatus",
                "getServerStatus"
            )
            put(
                "virtualserver_ip",
                "getIp",
                "getIP",
                "getHost",
                "getAddress"
            )
            put(
                "virtualserver_port",
                "getPort"
            )
            put(
                "virtualserver_created",
                "getCreated",
                "getCreationTime",
                "getCreatedAt"
            )
            put(
                "virtualserver_uptime",
                "getUptime",
                "getUpTime"
            )
            put(
                "virtualserver_maxclients",
                "getMaxClients"
            )
            put(
                "virtualserver_clientsonline",
                "getClientsOnline",
                "getOnlineClients"
            )
            put(
                "virtualserver_channelsonline",
                "getChannelsOnline",
                "getOnlineChannels"
            )
            put(
                "virtualserver_reserved_slots",
                "getReservedSlots"
            )
            put(
                "virtualserver_flag_password",
                "isPasswordProtected",
                "getPasswordProtected",
                "hasPassword"
            )
            put(
                "virtualserver_icon_id",
                "getIconId",
                "getIconID"
            )
            put(
                "virtualserver_welcomemessage",
                "getWelcomeMessage",
                "getWelcomemessage"
            )
            put(
                "virtualserver_hostmessage",
                "getHostMessage",
                "getHostmessage"
            )
            put(
                "virtualserver_hostbanner_gfx_url",
                "getHostBannerGfxUrl",
                "getHostBannerGFXUrl",
                "getHostbannerGfxUrl"
            )
            put(
                "virtualserver_hostbanner_url",
                "getHostBannerUrl",
                "getHostbannerUrl"
            )
            put(
                "virtualserver_hostbutton_gfx_url",
                "getHostButtonGfxUrl",
                "getHostButtonGFXUrl"
            )
            put(
                "virtualserver_hostbutton_url",
                "getHostButtonUrl"
            )
            put(
                "virtualserver_hostbutton_tooltip",
                "getHostButtonTooltip"
            )

            // Merge the Wrapper map too; VirtualServerInfo normally inherits a
            // property-wrapper API and this captures enum-backed values.
            readRawMap(info).forEach {
                (key, value) ->
                properties.putIfAbsent(
                    key,
                    value
                )
            }

            if (properties.isNotEmpty()) {


                captureServerMetadata(
                    eventName =
                        "typed-${info.javaClass.simpleName}",
                    properties = properties
                )
            }

            // One useful typed object is enough; don't invoke several methods
            // that may each execute a network request.
            if (
                properties.values.any {
                    it != null &&
                        it.toString().isNotBlank()
                }
            ) {
                return
            }
        }
    }

    /**
     * Extract server properties that ts3j already parsed from initserver /
     * notifyserverupdated into its internal state.
     *
     * This is intentionally bounded and property-oriented: it does not walk the
     * whole object graph blindly. Map keys such as VIRTUALSERVER_NAME or
     * virtualserver_name are normalized and only server-like fields/containers
     * are traversed.
     */
    private fun harvestServerStateFromSocket(
        socket: Any
    ) {
        val properties =
            linkedMapOf<String, Any?>()

        val visited =
            java.util.Collections
                .newSetFromMap(
                    java.util.IdentityHashMap<
                        Any,
                        Boolean
                    >()
                )

        collectServerStateProperties(
            target = socket,
            result = properties,
            visited = visited,
            depth = 0,
            pathHint = "socket"
        )

        if (properties.isNotEmpty()) {


            captureServerMetadata(
                eventName =
                    "ts3j-internal-server-state",
                properties = properties
            )
        }
    }

    private fun collectServerStateProperties(
        target: Any?,
        result: MutableMap<String, Any?>,
        visited: MutableSet<Any>,
        depth: Int,
        pathHint: String
    ) {
        if (
            target == null ||
            depth > 5
        ) {
            return
        }

        when (target) {
            is CharSequence,
            is Number,
            is Boolean,
            is Enum<*> -> return
        }

        if (!visited.add(target)) {
            return
        }

        if (target is Map<*, *>) {
            target.forEach {
                (rawKey, rawValue) ->

                val key =
                    normalizeServerPropertyKey(
                        rawKey
                    )

                if (
                    key != null &&
                    isInterestingServerProperty(
                        key
                    )
                ) {
                    result[key] =
                        unwrapCommandScalar(
                            rawValue
                        ) ?: rawValue
                }

                if (
                    rawValue != null &&
                    shouldTraverseServerState(
                        hint =
                            key
                                ?: rawKey
                                    ?.toString()
                                    .orEmpty(),
                        value = rawValue
                    )
                ) {
                    collectServerStateProperties(
                        target = rawValue,
                        result = result,
                        visited = visited,
                        depth = depth + 1,
                        pathHint =
                            "$pathHint.${key ?: "map"}"
                    )
                }
            }

            return
        }

        if (target is Iterable<*>) {
            target.take(128).forEach {
                collectServerStateProperties(
                    target = it,
                    result = result,
                    visited = visited,
                    depth = depth + 1,
                    pathHint = "$pathHint[]"
                )
            }
            return
        }

        if (target.javaClass.isArray) {
            val length =
                java.lang.reflect.Array
                    .getLength(target)
                    .coerceAtMost(128)

            for (index in 0 until length) {
                collectServerStateProperties(
                    target =
                        java.lang.reflect.Array
                            .get(
                                target,
                                index
                            ),
                    result = result,
                    visited = visited,
                    depth = depth + 1,
                    pathHint =
                        "$pathHint[$index]"
                )
            }

            return
        }

        // Typed getters can expose direct cached values.
        target.javaClass.methods
            .asSequence()
            .filter {
                it.parameterTypes.isEmpty()
            }
            .filterNot {
                it.name == "getClass"
            }
            .take(220)
            .forEach { method ->
                val propertyKey =
                    normalizeServerGetterKey(
                        method.name
                    )

                val methodName =
                    method.name.lowercase()

                val relevantGetter =
                    propertyKey != null &&
                        isInterestingServerProperty(
                            propertyKey
                        )

                val relevantContainer =
                    methodName.contains(
                        "server"
                    ) ||
                        methodName.contains(
                            "virtual"
                        ) ||
                        methodName.contains(
                            "variable"
                        ) ||
                        methodName.contains(
                            "propert"
                        ) ||
                        methodName.contains(
                            "state"
                        ) ||
                        methodName.contains(
                            "option"
                        )

                if (
                    !relevantGetter &&
                    !relevantContainer
                ) {
                    return@forEach
                }

                val value =
                    runCatching {
                        method.invoke(target)
                    }.getOrNull()
                    ?: return@forEach

                if (relevantGetter) {
                    result[propertyKey!!] =
                        unwrapCommandScalar(
                            value
                        ) ?: value
                }

                if (
                    relevantContainer &&
                    shouldTraverseServerState(
                        method.name,
                        value
                    )
                ) {
                    collectServerStateProperties(
                        target = value,
                        result = result,
                        visited = visited,
                        depth = depth + 1,
                        pathHint =
                            "$pathHint.${method.name}"
                    )
                }
            }

        // Important ts3j state is often private. Inspect only fields whose names
        // suggest server/property/variable state.
        target.javaClass
            .declaredFields
            .asSequence()
            .filterNot {
                java.lang.reflect.Modifier
                    .isStatic(it.modifiers)
            }
            .filter { field ->
                val n =
                    field.name.lowercase()

                n.contains("server") ||
                    n.contains("virtual") ||
                    n.contains("variable") ||
                    n.contains("propert") ||
                    n.contains("state") ||
                    n.contains("option") ||
                    n.contains("info")
            }
            .take(80)
            .forEach { field ->
                val value =
                    runCatching {
                        field.isAccessible = true
                        field.get(target)
                    }.getOrNull()
                    ?: return@forEach

                val fieldKey =
                    normalizeServerPropertyKey(
                        field.name
                    )

                if (
                    fieldKey != null &&
                    isInterestingServerProperty(
                        fieldKey
                    )
                ) {
                    result[fieldKey] =
                        unwrapCommandScalar(
                            value
                        ) ?: value
                }

                if (
                    shouldTraverseServerState(
                        field.name,
                        value
                    )
                ) {
                    collectServerStateProperties(
                        target = value,
                        result = result,
                        visited = visited,
                        depth = depth + 1,
                        pathHint =
                            "$pathHint.${field.name}"
                    )
                }
            }
    }

    private fun shouldTraverseServerState(
        hint: String,
        value: Any
    ): Boolean {
        if (
            value is String ||
            value is Number ||
            value is Boolean ||
            value is Enum<*>
        ) {
            return false
        }

        val normalized =
            hint.lowercase()

        return value is Map<*, *> ||
            value is Iterable<*> ||
            value.javaClass.isArray ||
            normalized.contains("server") ||
            normalized.contains("virtual") ||
            normalized.contains("variable") ||
            normalized.contains("propert") ||
            normalized.contains("state") ||
            normalized.contains("option") ||
            normalized.contains("info")
    }

    private fun normalizeServerPropertyKey(
        rawKey: Any?
    ): String? {
        if (rawKey == null) return null

        val raw =
            when (rawKey) {
                is Enum<*> ->
                    rawKey.name

                else ->
                    rawKey.toString()
            }
                .trim()
                .lowercase()
                .replace('-', '_')

        if (raw.isBlank()) return null

        return when {
            raw.startsWith(
                "virtualserver_"
            ) -> raw

            raw.startsWith(
                "virtualserver"
            ) -> {
                val suffix =
                    raw.removePrefix(
                        "virtualserver"
                    )
                        .trimStart('_')

                "virtualserver_$suffix"
            }

            else -> raw
        }
    }

    private fun normalizeServerGetterKey(
        getterName: String
    ): String? {
        val raw =
            getterName.trim()

        if (
            !raw.startsWith(
                "get",
                ignoreCase = true
            ) &&
            !raw.startsWith(
                "is",
                ignoreCase = true
            )
        ) {
            return null
        }

        val withoutPrefix =
            if (
                raw.startsWith(
                    "get",
                    ignoreCase = true
                )
            ) {
                raw.substring(3)
            } else {
                raw.substring(2)
            }

        if (withoutPrefix.isBlank()) {
            return null
        }

        val snake =
            withoutPrefix
                .replace(
                    Regex(
                        "([a-z0-9])([A-Z])"
                    ),
                    "$1_$2"
                )
                .lowercase()

        return normalizeServerPropertyKey(
            snake
        )
    }

    private fun isInterestingServerProperty(
        key: String
    ): Boolean {
        val normalized =
            key.lowercase()

        return normalized in
            SERVER_INFO_PROPERTY_KEYS ||
            normalized.startsWith(
                "virtualserver_"
            )
    }


    private fun executeSimpleCommand(
        socket: Any,
        commandName: String
    ): Map<String, Any?> {
        val roleClass = Class.forName(
            "com.github.manevolent.ts3j.protocol.ProtocolRole"
        )

        val roleConstants = roleClass.enumConstants
            ?: error("ts3j ProtocolRole constants unavailable")

        val clientRole: Any = roleConstants.firstOrNull {
            (it as? Enum<*>)?.name == "CLIENT"
        } ?: error("ts3j ProtocolRole.CLIENT not found")

        val commandClass = Class.forName(
            "com.github.manevolent.ts3j.command.SingleCommand"
        )

        val command: Any = run {
            val twoArg = commandClass.constructors.firstOrNull { ctor ->
                val types = ctor.parameterTypes
                types.size == 2 &&
                    types[0] == String::class.java &&
                    types[1] == roleClass
            }

            if (twoArg != null) {
                return@run twoArg.newInstance(
                    commandName,
                    clientRole
                ) ?: error("Could not create $commandName command")
            }

            val threeArg = commandClass.constructors.firstOrNull { ctor ->
                val types = ctor.parameterTypes
                types.size == 3 &&
                    types[0] == String::class.java &&
                    types[1] == roleClass &&
                    java.util.List::class.java.isAssignableFrom(types[2])
            }

            if (threeArg != null) {
                return@run threeArg.newInstance(
                    commandName,
                    clientRole,
                    emptyList<Any>()
                ) ?: error("Could not create $commandName command")
            }

            val parse = commandClass.methods.firstOrNull { method ->
                method.name == "parse" &&
                    java.lang.reflect.Modifier.isStatic(method.modifiers) &&
                    method.parameterTypes.size == 2 &&
                    method.parameterTypes[0] == roleClass &&
                    method.parameterTypes[1] == String::class.java
            } ?: error("No compatible ts3j SingleCommand factory found")

            parse.invoke(
                null,
                clientRole,
                commandName
            ) ?: error("Could not parse $commandName command")
        }

        val execute = socket.javaClass.methods.firstOrNull { method ->
            method.name == "executeCommand" &&
                method.parameterTypes.size == 1 &&
                method.parameterTypes[0].isAssignableFrom(command.javaClass)
        } ?: error("ts3j executeCommand(Command) not found")

        val response = execute.invoke(socket, command)
            ?: error("ts3j $commandName returned no response")

        val payload = response.javaClass.methods.firstOrNull {
            it.name == "get" &&
                it.parameterTypes.isEmpty()
        }?.let { getter ->
            runCatching {
                getter.invoke(response)
            }.getOrNull()
        }

        val result = linkedMapOf<String, Any?>()
        val visited = java.util.Collections.newSetFromMap(
            java.util.IdentityHashMap<Any, Boolean>()
        )

        collectCommandProperties(
            target = payload ?: response,
            result = result,
            visited = visited,
            depth = 0
        )

        if (result.isEmpty() && payload !== response) {
            collectCommandProperties(
                target = response,
                result = result,
                visited = visited,
                depth = 0
            )
        }

        // Some ts3j -SNAPSHOT revisions wrap the command parameters in
        // objects that do not expose a map. For serverinfo, inspect the raw
        // response representation only for explicit TeamSpeak keys.
        if (
            commandName.equals(
                "serverinfo",
                ignoreCase = true
            ) ||
            commandName.equals(
                "servergetvariables",
                ignoreCase = true
            )
        ) {
            val rawCandidates = listOf(
                payload?.toString(),
                response.toString()
            )

            SERVER_INFO_RAW_KEYS
                .forEach { key ->
                if (
                    result[key]
                        ?.toString()
                        ?.isNotBlank() != true
                ) {
                    rawCandidates
                        .firstNotNullOfOrNull {
                            extractTs3CommandProperty(
                                raw = it,
                                key = key
                            )
                        }
                        ?.let { extracted ->
                            result[key] = extracted
                        }
                }
            }
        }



        return result
    }

    private fun extractServerInfoPropertiesFromRaw(
        raw: String?
    ): Map<String, Any?> {
        if (raw.isNullOrBlank()) {
            return emptyMap()
        }

        val normalizedRaw =
            raw
                .replace("\r", " ")
                .replace("\n", " ")

        // Only TeamSpeak virtual-server protocol keys form boundaries here.
        // BBCode such as [size=20] and [color=Red] must remain message content.
        val tokenRegex =
            Regex(
                """(?i)(?:^|[\s,\{\(\[])""" +
                    """(virtualserver_[a-z0-9_]+)""" +
                    """\s*(?:=|:)\s*"""
            )

        val matches =
            tokenRegex
                .findAll(normalizedRaw)
                .toList()

        if (matches.isEmpty()) {
            return emptyMap()
        }

        val result =
            linkedMapOf<String, Any?>()

        matches.forEachIndexed { index, match ->
            val key =
                match.groupValues[1]
                    .lowercase()

            if (key !in SERVER_INFO_RAW_KEYS) {
                return@forEachIndexed
            }

            val valueStart =
                match.range.last + 1

            val valueEnd =
                matches
                    .getOrNull(index + 1)
                    ?.range
                    ?.first
                    ?: normalizedRaw.length

            if (valueStart >= valueEnd) {
                return@forEachIndexed
            }

            var encoded =
                normalizedRaw
                    .substring(
                        valueStart,
                        valueEnd
                    )
                    .trim()
                    .trimStart(
                        ' ',
                        '\t',
                        ',',
                        '=',
                        ':'
                    )
                    .trimEnd(
                        ' ',
                        '\t',
                        ','
                    )

            if (
                encoded.length >= 2 &&
                (
                    (
                        encoded.first() == '"' &&
                        encoded.last() == '"'
                    ) ||
                    (
                        encoded.first() == '\'' &&
                        encoded.last() == '\''
                    )
                )
            ) {
                encoded =
                    encoded.substring(
                        1,
                        encoded.length - 1
                    )
            }

            val decoded =
                decodeTs3CommandString(encoded)
                    .trim()
                    .takeIf {
                        it.isNotBlank() &&
                            !it.equals(
                                "null",
                                ignoreCase = true
                            )
                    }

            if (decoded != null) {
                result[key] = decoded
            }
        }

        return result
    }

    private fun extractTs3CommandProperty(
        raw: String?,
        key: String
    ): String? {
        val properties =
            extractServerInfoPropertiesFromRaw(
                raw
            )

        return properties[
            key.lowercase()
        ]
            ?.toString()
            ?.takeIf {
                it.isNotBlank()
            }
    }

    private fun decodeTs3CommandString(
        value: String
    ): String {
        val output = StringBuilder(value.length)
        var index = 0

        while (index < value.length) {
            val ch = value[index]

            if (
                ch == '\\' &&
                index + 1 < value.length
            ) {
                when (val next = value[index + 1]) {
                    's' -> output.append(' ')
                    'p' -> output.append('|')
                    '/' -> output.append('/')
                    '\\' -> output.append('\\')
                    'n' -> output.append('\n')
                    'r' -> output.append('\r')
                    't' -> output.append('\t')
                    else -> {
                        output.append(next)
                    }
                }

                index += 2
                continue
            }

            output.append(ch)
            index++
        }

        return output.toString()
    }

    private fun collectCommandProperties(
        target: Any?,
        result: MutableMap<String, Any?>,
        visited: MutableSet<Any>,
        depth: Int
    ) {
        if (target == null || depth > 6) return

        when (target) {
            is String,
            is Number,
            is Boolean,
            is Enum<*> -> return
        }

        if (!visited.add(target)) return

        if (target is Map<*, *>) {
            target.forEach { (key, value) ->
                if (key != null) {
                    val normalized =
                        unwrapCommandScalar(
                            value
                        )

                    result[
                        key.toString()
                            .lowercase()
                    ] =
                        normalized ?: value
                }

                if (
                    value != null &&
                    unwrapCommandScalar(
                        value
                    ) == null
                ) {
                    collectCommandProperties(
                        value,
                        result,
                        visited,
                        depth + 1
                    )
                }
            }
            return
        }

        if (target is Iterable<*>) {
            target.forEach {
                collectCommandProperties(
                    it,
                    result,
                    visited,
                    depth + 1
                )
            }
            return
        }

        if (target.javaClass.isArray) {
            val length =
                java.lang.reflect.Array.getLength(target)

            for (index in 0 until length) {
                collectCommandProperties(
                    java.lang.reflect.Array.get(
                        target,
                        index
                    ),
                    result,
                    visited,
                    depth + 1
                )
            }
            return
        }

        val parameterName = invokeStringGetter(
            target,
            "getName",
            "getKey",
            "getParameterName"
        )

        val parameterValue = invokeAnyGetter(
            target,
            "getValue",
            "getParameterValue"
        )

        if (
            !parameterName.isNullOrBlank() &&
            parameterValue != null
        ) {
            result[
                parameterName.lowercase()
            ] =
                unwrapCommandScalar(
                    parameterValue
                ) ?: parameterValue
        }

        listOf(
            "getMap",
            "toMap",
            "asMap",
            "getParameters",
            "getParameterList",
            "getCommands",
            "getCommand",
            "getResults",
            "getResult",
            "getResponse",
            "getResponses",
            "getData",
            "getPayload"
        ).forEach { methodName ->
            invokeAnyGetter(
                target,
                methodName
            )?.let { nested ->
                collectCommandProperties(
                    nested,
                    result,
                    visited,
                    depth + 1
                )
            }
        }

        readRawMap(target).forEach {
            (name, simpleValue) ->
            result.putIfAbsent(
                name,
                unwrapCommandScalar(
                    simpleValue
                ) ?: simpleValue
            )
        }

        // Some ts3j command revisions keep parameters in a private map/list
        // without a public getMap(). Inspect only fields that plausibly contain
        // command data and keep recursion bounded.
        target.javaClass
            .declaredFields
            .asSequence()
            .filterNot {
                java.lang.reflect.Modifier
                    .isStatic(it.modifiers)
            }
            .filter {
                val n =
                    it.name.lowercase()

                n.contains("param") ||
                    n.contains("map") ||
                    n.contains("value") ||
                    n.contains("data") ||
                    n.contains("command") ||
                    n.contains("result") ||
                    n.contains("response")
            }
            .take(24)
            .forEach { field ->
                val nested =
                    runCatching {
                        field.isAccessible = true
                        field.get(target)
                    }.getOrNull()

                if (
                    nested != null &&
                    nested !== target
                ) {
                    collectCommandProperties(
                        nested,
                        result,
                        visited,
                        depth + 1
                    )
                }
            }
    }

    private fun unwrapCommandScalar(
        value: Any?,
        depth: Int = 0
    ): String? {
        if (
            value == null ||
            depth > 4
        ) {
            return null
        }

        when (value) {
            is CharSequence ->
                return value.toString()

            is Number,
            is Boolean,
            is Enum<*> ->
                return value.toString()
        }

        if (value is Map<*, *>) {
            listOf(
                "value",
                "rawvalue",
                "data"
            ).forEach { expected ->
                value.entries
                    .firstOrNull {
                        it.key
                            ?.toString()
                            ?.equals(
                                expected,
                                ignoreCase = true
                            ) == true
                    }
                    ?.value
                    ?.let { nested ->
                        unwrapCommandScalar(
                            nested,
                            depth + 1
                        )
                    }
                    ?.let {
                        return it
                    }
            }
        }

        listOf(
            "getValue",
            "getStringValue",
            "getRawValue",
            "value"
        ).forEach { methodName ->
            val method =
                value.javaClass.methods
                    .firstOrNull {
                        it.name.equals(
                            methodName,
                            ignoreCase = true
                        ) &&
                            it.parameterTypes
                                .isEmpty()
                    }
                    ?: return@forEach

            val nested =
                runCatching {
                    method.invoke(value)
                }.getOrNull()

            if (
                nested != null &&
                nested !== value
            ) {
                unwrapCommandScalar(
                    nested,
                    depth + 1
                )?.let {
                    return it
                }
            }
        }

        value.javaClass
            .declaredFields
            .firstOrNull {
                !java.lang.reflect.Modifier
                    .isStatic(it.modifiers) &&
                    it.name.equals(
                        "value",
                        ignoreCase = true
                    )
            }
            ?.let { field ->
                val nested =
                    runCatching {
                        field.isAccessible = true
                        field.get(value)
                    }.getOrNull()

                if (
                    nested != null &&
                    nested !== value
                ) {
                    unwrapCommandScalar(
                        nested,
                        depth + 1
                    )?.let {
                        return it
                    }
                }
            }

        return null
    }

    private fun invokeAnyGetter(
        target: Any,
        vararg names: String
    ): Any? {
        names.forEach { name ->
            val method = target.javaClass.methods
                .firstOrNull {
                    it.name.equals(
                        name,
                        ignoreCase = true
                    ) &&
                        it.parameterTypes.isEmpty()
                }
                ?: return@forEach

            return runCatching {
                method.invoke(target)
            }.getOrNull()
        }

        return null
    }

    private fun invokeStringGetter(
        target: Any,
        vararg names: String
    ): String? =
        invokeAnyGetter(
            target,
            *names
        )
            ?.toString()
            ?.trim()
            ?.takeIf { it.isNotBlank() }


    @Synchronized
    private fun markConnectionLost(reason: String, expectedSocket: Any? = null) {
        if (expectedSocket != null && socketRef.get() !== expectedSocket) return
        if (!connected && socketRef.get() == null) return
        sessionTasks.stop()
        ConnectionDiagnostics.event("session.lost", "reason=$reason transport=${socketTransportState()} " +
            "lastOkAgeMs=${android.os.SystemClock.elapsedRealtime() - lastSuccessfulProbeMs.get()} " +
            "transportIdleMs=${socketRef.get()?.let { invokeNoArgNumber(it, "getLastResponseAgeMillis") }}")
        ConnectionDiagnostics.network(appContext, "disconnect")
        connected = false
        fileTransfersRef.getAndSet(null)?.close()
        clientLookupRef.getAndSet(null)?.close()
        status = reason
        currentChannelId = null
        healthFailureCount.set(0)

        audioEngine.detach()

        socketRef.getAndSet(null)?.let { socket ->
            runCatching {
                socket.javaClass.getMethod("close").invoke(socket)
            }
        }

        Log.w(TAG, reason)
    }

    private fun commandLongValue(
        value: Any?
    ): Long? {
        if (value == null) return null

        when (value) {
            is Byte,
            is Short,
            is Int,
            is Long ->
                return (value as Number)
                    .toLong()

            is Float,
            is Double ->
                return (value as Number)
                    .toLong()

            is java.util.Date ->
                return value.time / 1000L

            is java.time.Instant ->
                return value.epochSecond
        }

        val scalar =
            unwrapCommandScalar(value)
                ?: value.toString()

        val direct =
            scalar
                .trim()
                .toLongOrNull()

        if (direct != null) {
            return direct
        }

        // Some typed wrappers expose an Instant/Date nested under getValue().
        invokeAnyGetter(
            value,
            "getEpochSecond",
            "getTime",
            "toEpochSecond"
        )?.let {
            nested ->
            if (nested !== value) {
                commandLongValue(
                    nested
                )?.let {
                    return it
                }
            }
        }

        return null
    }

    private fun captureServerMetadata(
        eventName: String,
        properties: Map<String, Any?>
    ) {
        // initserver may include the connecting client's version as well. Only
        // explicit virtualserver fields feed server detection and capability data.
        val protocolFields = properties.filterKeys { it.startsWith("virtualserver_") }
            .mapValues { (_, value) -> value?.let(::unwrapCommandScalar) ?: value }
        serverProtocolRef.updateAndGet { it.mergeServerProperties(protocolFields) }
        fun value(vararg keys: String): String? =
            keys.firstNotNullOfOrNull { key ->
                val rawValue =
                    properties[key]
                        ?.let(
                            ::unwrapCommandScalar
                        )
                        ?.trim()
                        ?.takeIf {
                            it.isNotBlank()
                        }
                        ?: properties[key]
                            ?.toString()
                            ?.trim()
                            ?.takeIf {
                                it.isNotBlank()
                            }

                rawValue
                    ?.takeUnless {
                        it.equals(
                            "null",
                            ignoreCase = true
                        )
                    }
            }

        fun textValue(vararg keys: String): String? =
            keys.firstNotNullOfOrNull { key ->
                val rawValue =
                    properties[key]
                        ?.let(
                            ::unwrapCommandScalar
                        )
                        ?.toString()
                        ?: properties[key]
                            ?.toString()

                rawValue
                    ?.takeIf {
                        it.isNotBlank()
                    }
                    ?.takeUnless {
                        it.trim()
                            .equals(
                                "null",
                                ignoreCase = true
                            )
                    }
            }

        fun preserveTextStructure(
            fieldName: String,
            previousValue: String,
            incomingValue: String?
        ): String {
            if (incomingValue == null) {
                return previousValue
            }

            if (previousValue.isBlank()) {
                return incomingValue
            }

            val previousNewlines =
                previousValue.count {
                    it == '\n'
                }

            val incomingNewlines =
                incomingValue.count {
                    it == '\n'
                }

            if (
                incomingNewlines <
                previousNewlines
            ) {


                return previousValue
            }

            return incomingValue
        }

        fun longValue(vararg keys: String): Long? =
            keys.firstNotNullOfOrNull { key ->
                properties[key]
                    ?.let(
                        ::commandLongValue
                    )
                    ?: value(key)
                        ?.toLongOrNull()
            }

        fun intValue(vararg keys: String): Int? =
            value(*keys)
                ?.toIntOrNull()

        fun boolValue(vararg keys: String): Boolean? =
            value(*keys)
                ?.let { raw ->
                    when (
                        raw.trim().lowercase()
                    ) {
                        "1", "true", "yes" -> true
                        "0", "false", "no" -> false
                        else -> null
                    }
                }

        val previous = observedServerInfo

        // Deliberately only accept explicit virtualserver-name keys. Generic
        // name/getName fields can describe command wrappers or client roles.
        val name = value(
            "virtualserver_name",
            "virtualservername",
            "virtual_server_name",
            "getvirtualservername"
        )

        observedServerInfo = previous.copy(
            name =
                name ?: previous.name,

            uniqueIdentifier =
                value(
                    "virtualserver_unique_identifier",
                    "virtualserveruniqueidentifier",
                    "getvirtualserveruniqueidentifier"
                )
                    ?: previous.uniqueIdentifier,

            platform =
                value(
                    "virtualserver_platform",
                    "virtualserverplatform",
                    "server_platform",
                    "serverplatform",
                    "getvirtualserverplatform",
                    "getserverplatform"
                )
                    ?: previous.platform,

            version =
                value(
                    "virtualserver_version",
                    "virtualserverversion",
                    "server_version",
                    "serverversion",
                    "getvirtualserverversion",
                    "getserverversion"
                )
                    ?: previous.version,

            status =
                value(
                    "virtualserver_status",
                    "virtualserverstatus",
                    "getvirtualserverstatus"
                )
                    ?: previous.status,

            serverIp =
                value(
                    "virtualserver_ip",
                    "virtualserverip",
                    "getvirtualserverip"
                )
                    ?: previous.serverIp,

            port =
                intValue(
                    "virtualserver_port",
                    "virtualserverport",
                    "getvirtualserverport"
                )
                    ?: previous.port,

            createdEpochSeconds =
                longValue(
                    "virtualserver_created",
                    "virtualservercreated",
                    "virtual_server_created",
                    "server_created",
                    "servercreated",
                    "getvirtualservercreated",
                    "getservercreated",
                    "getcreated",
                    "created"
                )
                    ?.let { raw ->
                        // Accept either Unix seconds or milliseconds from typed
                        // Java wrappers.
                        if (
                            raw >
                            10_000_000_000L
                        ) {
                            raw / 1000L
                        } else {
                            raw
                        }
                    }
                    ?.takeIf {
                        // TeamSpeak exposes VIRTUALSERVER_CREATED as a Unix
                        // timestamp. Keep every positive timestamp up to a
                        // conservative far-future bound.
                        it in
                            1L..
                            7258118400L
                    }
                    ?: previous.createdEpochSeconds,

            uptimeSeconds =
                longValue(
                    "virtualserver_uptime",
                    "virtualserveruptime",
                    "server_uptime",
                    "serveruptime",
                    "getvirtualserveruptime",
                    "getserveruptime"
                )
                    ?: previous.uptimeSeconds,

            maxClients =
                intValue(
                    "virtualserver_maxclients",
                    "virtualservermaxclients",
                    "getvirtualservermaxclients"
                )
                    ?: previous.maxClients,

            clientsOnline =
                intValue(
                    "virtualserver_clientsonline",
                    "virtualserverclientsonline",
                    "getvirtualserverclientsonline"
                )
                    ?: previous.clientsOnline,

            channelsOnline =
                intValue(
                    "virtualserver_channelsonline",
                    "virtualserverchannelsonline",
                    "getvirtualserverchannelsonline"
                )
                    ?: previous.channelsOnline,

            queryClientsOnline =
                intValue(
                    "virtualserver_queryclientsonline",
                    "virtualserverqueryclientsonline",
                    "getvirtualserverqueryclientsonline"
                )
                    ?: previous.queryClientsOnline,

            reservedSlots =
                intValue(
                    "virtualserver_reserved_slots",
                    "virtualserverreservedslots",
                    "getvirtualserverreservedslots"
                )
                    ?: previous.reservedSlots,

            passwordProtected =
                boolValue(
                    "virtualserver_flag_password",
                    "virtualserverflagpassword",
                    "getvirtualserverflagpassword"
                )
                    ?: previous.passwordProtected,

            iconId =
                longValue(
                    "virtualserver_icon_id",
                    "virtualservericonid",
                    "getvirtualservericonid"
                )
                    ?: previous.iconId,

            welcomeMessage =
                preserveTextStructure(
                    fieldName =
                        "welcomeMessage",
                    previousValue =
                        previous.welcomeMessage,
                    incomingValue =
                        textValue(
                            "virtualserver_welcomemessage",
                            "virtualserver_welcome_message",
                            "virtualserverwelcomemessage",
                            "welcomemessage",
                            "welcome_message",
                            "getwelcomemessage",
                            "getvirtualserverwelcomemessage"
                        )
                ),

            hostMessage =
                preserveTextStructure(
                    fieldName =
                        "hostMessage",
                    previousValue =
                        previous.hostMessage,
                    incomingValue =
                        textValue(
                            "virtualserver_hostmessage",
                            "virtualserver_host_message",
                            "virtualserverhostmessage",
                            "hostmessage",
                            "host_message",
                            "gethostmessage",
                            "getvirtualserverhostmessage"
                        )
                ),

            bannerImageUrl =
                value(
                    "virtualserver_hostbanner_gfx_url",
                    "virtualserver_hostbanner_gfxurl",
                    "virtual_server_hostbanner_gfx_url",
                    "virtualserverhostbannergfxurl",
                    "hostbanner_gfx_url",
                    "hostbanner_gfxurl",
                    "hostbannergfxurl",
                    "gethostbannergfxurl",
                    "gethostbannergfxurlstring",
                    "getvirtualserverhostbannergfxurl"
                )
                    ?: previous.bannerImageUrl,

            bannerLinkUrl =
                value(
                    "virtualserver_hostbanner_url",
                    "virtualserverhostbannerurl",
                    "hostbanner_url",
                    "hostbannerurl",
                    "gethostbannerurl",
                    "getvirtualserverhostbannerurl"
                )
                    ?: previous.bannerLinkUrl,

            hostButtonImageUrl =
                value(
                    "virtualserver_hostbutton_gfx_url",
                    "virtualserverhostbuttongfxurl",
                    "gethostbuttongfxurl",
                    "getvirtualserverhostbuttongfxurl"
                )
                    ?: previous.hostButtonImageUrl,

            hostButtonLinkUrl =
                value(
                    "virtualserver_hostbutton_url",
                    "virtualserverhostbuttonurl",
                    "gethostbuttonurl",
                    "getvirtualserverhostbuttonurl"
                )
                    ?: previous.hostButtonLinkUrl,

            hostButtonTooltip =
                value(
                    "virtualserver_hostbutton_tooltip",
                    "virtualserverhostbuttontooltip",
                    "gethostbuttontooltip",
                    "getvirtualserverhostbuttontooltip"
                )
                    ?: previous.hostButtonTooltip,

            maxDownloadBytesPerSecond =
                longValue(
                    "virtualserver_max_download_total_bandwidth",
                    "virtualservermaxdownloadtotalbandwidth"
                )
                    ?: previous.maxDownloadBytesPerSecond,

            maxUploadBytesPerSecond =
                longValue(
                    "virtualserver_max_upload_total_bandwidth",
                    "virtualservermaxuploadtotalbandwidth"
                )
                    ?: previous.maxUploadBytesPerSecond,

            totalBytesDownloaded =
                longValue(
                    "virtualserver_total_bytes_downloaded",
                    "virtualservertotalbytesdownloaded"
                )
                    ?: previous.totalBytesDownloaded,

            totalBytesUploaded =
                longValue(
                    "virtualserver_total_bytes_uploaded",
                    "virtualservertotalbytesuploaded"
                )
                    ?: previous.totalBytesUploaded
        )
    }


    /**
     * Wrapper.getMap() is the important part here. ChannelListEvent itself exposes
     * almost no getters, while the raw map contains channel_name, pid, order, etc.
     */
    private fun readRawMap(target: Any): Map<String, Any?> {
        val result = linkedMapOf<String, Any?>()

        val accessors = eventAccessors.computeIfAbsent(target.javaClass) { type ->
            val methods = type.methods.toList()
            RawEventAccessors(
                methods.firstOrNull { it.name == "getMap" && it.parameterTypes.isEmpty() },
                methods.filter { it.parameterTypes.isEmpty() &&
                    (it.name.startsWith("get") || it.name.startsWith("is")) &&
                    it.name != "getClass" && it.name != "getMap" }.take(100)
            )
        }
        runCatching {
            val raw = accessors.map?.invoke(target)
            if (raw is Map<*, *>) raw.forEach { (key, value) ->
                if (key != null) result[key.toString().lowercase()] = value
            }
        }
        // Cache reflection metadata once per event class, preserving getter aliases.
        accessors.getters
            .forEach { method ->
                val value = runCatching { method.invoke(target) }.getOrNull()
                if (
                    value == null ||
                    value is Number ||
                    value is String ||
                    value is Boolean ||
                    value.javaClass.isEnum
                ) {
                    result[method.name.lowercase()] = value
                }
            }

        return result
    }

    private fun processChannelEvent(
        eventName: String,
        p: Map<String, Any?>
    ) {
        val id = numberProperty(
            p, "cid", "getchannelid", "getid"
        ) ?: return

        val isDelete =
            "deleted" in eventName ||
            "delete" in eventName ||
            "delchannel" in eventName

        if (isDelete) {
            channelCache.remove(id)
            clientCache.entries.removeIf { it.value.channelId == id }
            return
        }

        val merged = mergeChannel(p + mapOf("cid" to id.toString()))

        if (
            p.containsKey(
                "channel_description"
            ) ||
            p.containsKey(
                "getdescription"
            )
        ) {
            val generation =
                System.nanoTime()

            channelDescriptionGeneration[
                id
            ] =
                generation


        }

        if (
            "moved" in eventName ||
            p.containsKey("pid") ||
            p.containsKey("cpid") ||
            p.containsKey("getparentchannelid") ||
            p.containsKey("getchannelparentid")
        ) {

        }
    }

    private fun mergeChannel(p: Map<String, Any?>): BackendChannel {
        val id = numberProperty(p, "cid", "getchannelid", "getid")
            ?: error("Channel id missing")

        val previous = channelCache[id] ?: BackendChannel(
            id = id,
            name = "Channel $id",
            firstSeen = channelSequence.incrementAndGet()
        )

        fun text(key: String, vararg aliases: String): String {
            val all = arrayOf(key, *aliases)
            val present = all.firstOrNull { p.containsKey(it.lowercase()) }
            return if (present != null) {
                stringProperty(p, present).orEmpty()
            } else {
                when (key) {
                    "channel_name" -> previous.name
                    "channel_topic" -> previous.topic
                    "channel_description" -> previous.description
                    "channel_banner_gfx_url" -> previous.bannerUrl
                    else -> ""
                }
            }
        }

        fun number(key: String, fallback: Long, vararg aliases: String): Long {
            val all = arrayOf(key, *aliases)
            return numberProperty(p, *all) ?: fallback
        }

        fun bool(key: String, fallback: Boolean): Boolean {
            if (!p.containsKey(key.lowercase())) return fallback
            return booleanProperty(p, key) ?: fallback
        }

        val updated = previous.copy(
            // ChannelListEvent uses PID. ChannelMovedEvent / channel-create
            // notifications use CPID and expose getChannelParentId().
            parentId = number(
                "pid",
                previous.parentId,
                "cpid",
                "getparentchannelid",
                "getchannelparentid",
                "getparentid"
            ),
            order = number(
                "channel_order",
                previous.order,
                "getchannelorder",
                "getorder"
            ),
            name = text("channel_name", "getname").ifBlank { previous.name },
            topic = text("channel_topic", "gettopic"),
            description = text("channel_description", "getdescription"),
            iconId = number("channel_icon_id", previous.iconId, "geticonid"),
            bannerUrl = text(
                "channel_banner_gfx_url",
                "channel_banner_url",
                "getbannergfxurl",
                "getbannerurl"
            ).replace("&amp;", "&").ifBlank { previous.bannerUrl },
            hasPassword = bool("channel_flag_password", previous.hasPassword),
            permanent = bool("channel_flag_permanent", previous.permanent),
            firstSeen = previous.firstSeen
        )

        channelCache[id] = updated
        return updated
    }

    private fun processClientEvent(
        eventName: String,
        p: Map<String, Any?>
    ) {
        val id = numberProperty(
            p, "clid", "getclientid", "getid"
        )?.toInt() ?: return

        val isLeave =
            "leave" in eventName ||
            "kickfromserver" in eventName ||
            "clientdisconnect" in eventName

        if (isLeave) {
            val leaving = clientCache.remove(id)
            checkedAvatarMetadata.remove(id)

            if (leaving != null && !leaving.isServerQuery) {
                runCatching {
                    clientEventListener?.invoke(
                        BackendClientEvent(
                            eventName = eventName,
                            client = leaving
                        )
                    )
                }
            }

            return
        }

        val previous = clientCache[id]

        val channelId = numberProperty(
            p,
            "ctid",
            "cid",
            "gettargetchannelid",
            "getclienttargetid",
            "getchannelid",
            "getnewchannelid"
        ) ?: previous?.channelId ?: 0L

        val nickname = stringProperty(
            p,
            "client_nickname",
            "getclientnickname",
            "getnickname",
            "getname"
        )?.takeIf { it.isNotBlank() }
            ?.let(::localNickname)
            ?: previous?.nickname
            ?: "Client $id"

        val inputMuted = booleanProperty(
            p,
            "client_input_muted",
            "getinputmuted",
            "getclientinputmuted"
        ) ?: previous?.inputMuted ?: false

        val uniqueId = stringProperty(
            p,
            "client_unique_identifier",
            "getclientuniqueidentifier",
            "getuniqueclientidentifier",
            "getuniqueidentifier",
            "getuid"
        )?.takeIf { it.isNotBlank() }
            ?: previous?.uniqueId
            ?: ""

        val clientType = numberProperty(
            p,
            "client_type",
            "getclienttype",
            "gettype"
        )?.toInt()

        val isServerQuery =
            clientType == 1 ||
            previous?.isServerQuery == true ||
            nickname.equals("serveradmin", ignoreCase = true)

        val countryCode = stringProperty(
            p, "client_country", "client_country_code", "getcountry"
        )?.trim()?.uppercase(java.util.Locale.ROOT)
            ?.takeIf { it.matches(Regex("[A-Z]{2}")) }
            ?: previous?.countryCode.orEmpty()

        val groupIds = stringProperty(
            p, "client_servergroups", "client_server_groups", "getservergroups"
        )?.split(",")?.mapNotNull { it.trim().toLongOrNull() }
            ?: previous?.serverGroupIds.orEmpty()

        val channelGroupId = numberProperty(
            p, "client_channel_group_id", "client_channel_group", "getchannelgroupid", "cgid"
        ) ?: previous?.channelGroupId ?: 0L

        val avatarHash = com.morsen.ts3xclient.protocol.TeamSpeakAvatarMetadata.mergeFlag(p, previous?.avatarHash)
        val accountAvatarUrl = com.morsen.ts3xclient.protocol.TeamSpeakAvatarMetadata
            .mergeAccountAvatarUrl(p, previous?.accountAvatarUrl)

        val databaseId = numberProperty(
            p, "client_database_id", "client_databaseid", "cldbid",
            "getclientdatabaseid", "getdatabaseid"
        )?.takeIf { it > 0L } ?: previous?.databaseId ?: 0L

        if (previous != null && (previous.serverGroupIds != groupIds || previous.countryCode != countryCode)) {
            Log.i("TS3X_ROLES", "Client id=$id groups=${groupIds.joinToString(",")} country=$countryCode")
        }
        // ClientList and ClientUpdated notifications expose AFK independently of mute state.
        // Preserve the last known values when an incremental event omits these properties.
        val isAway = booleanProperty(
            p, "client_away", "getclientaway", "getaway", "isaway"
        ) ?: previous?.isAway ?: false
        val awayMessage = stringProperty(
            p, "client_away_message", "getclientawaymessage", "getawaymessage"
        ) ?: previous?.awayMessage.orEmpty()
        if (previous != null && (isAway != previous.isAway || awayMessage != previous.awayMessage)) {
            Log.i("TS3X_AFK", "remote.state clientId=$id away=$isAway messageLength=${awayMessage.length}")
        }
        val updatedClient = BackendClient(
            id = id,
            channelId = channelId,
            nickname = nickname,
            inputMuted = inputMuted,
            uniqueId = uniqueId,
            isServerQuery = isServerQuery,
            countryCode = countryCode,
            serverGroupIds = groupIds,
            avatarHash = avatarHash,
            accountAvatarUrl = accountAvatarUrl,
            accountAvatarKnown = previous?.accountAvatarKnown == true ||
                com.morsen.ts3xclient.protocol.TeamSpeakAvatarMetadata.hasAccountAvatarProperty(p),
            channelGroupId = channelGroupId,
            databaseId = databaseId,
            isAway = isAway,
            awayMessage = awayMessage
        )

        clientCache[id] = updatedClient

        if (!isServerQuery) {
            runCatching {
                clientEventListener?.invoke(
                    BackendClientEvent(
                        eventName = eventName,
                        client = updatedClient
                    )
                )
            }.onFailure {
                recordEvent(
                    "client listener warning: ${rootMessage(it)}"
                )
            }
        }

        if (id == ownClientId) {
            selfAwayState = isAway
            selfAwayMessage = awayMessage
            currentChannelId = channelId
            if (uniqueId.isNotBlank()) {
                observedClientUid = uniqueId
            }
        }
    }

    private fun numberProperty(
        properties: Map<String, Any?>,
        vararg names: String
    ): Long? {
        names.forEach { rawName ->
            val name = rawName.lowercase()
            val value = properties[name]
            when (value) {
                is Number -> return value.toLong()
                is String -> value.toLongOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun stringProperty(
        properties: Map<String, Any?>,
        vararg names: String
    ): String? {
        names.forEach { rawName ->
            val value = properties[rawName.lowercase()]
            if (value != null) return value.toString()
        }
        return null
    }

    private fun booleanProperty(
        properties: Map<String, Any?>,
        vararg names: String
    ): Boolean? {
        names.forEach { rawName ->
            when (val value = properties[rawName.lowercase()]) {
                is Boolean -> return value
                is Number -> return value.toInt() != 0
                is String -> return value == "1" || value.equals("true", true)
            }
        }
        return null
    }

    private fun formatEvent(
        callbackName: String,
        event: Any,
        properties: Map<String, Any?>
    ): String {
        val usefulKeys = listOf(
            "virtualserver_name", "server_name",
            "virtualserver_welcomemessage", "virtualserver_hostmessage",
            "virtualserver_hostbanner_gfx_url", "virtualserver_hostbanner_url",
            "cid", "pid", "cpid", "channel_order", "channel_name",
            "channel_topic", "channel_description", "channel_icon_id",
            "channel_banner_gfx_url", "clid", "ctid", "client_nickname", "client_input_muted", "client_unique_identifier", "client_type",
            "reasonid", "reasonmsg"
        )

        val useful = usefulKeys.mapNotNull { key ->
            properties[key]?.let { "$key=$it" }
        }.joinToString(", ")

        return if (useful.isBlank()) {
            "$callbackName(${event.javaClass.simpleName})"
        } else {
            "$callbackName(${event.javaClass.simpleName}): $useful"
        }
    }

    /**
     * Load the persisted identity. A corrupt/unreadable identity is NEVER
     * silently deleted, because doing so would generate a new TeamSpeak UID and
     * lose server-side permissions tied to the old identity.
     */
    private fun loadOrCreateIdentity(identityClass: Class<*>): Any {
        val saved = prefs.getString(KEY_IDENTITY, null)

        if (!saved.isNullOrBlank()) {
            return runCatching {
                readIdentityExport(identityClass, saved)
            }.getOrElse { error ->
                error(
                    "Stored TeamSpeak identity could not be loaded. " +
                        "It was preserved; use Settings > Identity to import, " +
                        "restore backup, or explicitly generate a new identity. " +
                        "Cause: ${rootMessage(error)}"
                )
            }
        }

        // First launch only: create one identity, verify that its export can be
        // loaded again, and synchronously persist it BEFORE connecting.
        status = "Generating first TeamSpeak identity…"

        val generate = identityClass.getMethod(
            "generateNew",
            Int::class.javaPrimitiveType
        )

        val identity: Any = generate.invoke(
            null,
            DEFAULT_IDENTITY_SECURITY_LEVEL
        ) ?: error("LocalIdentity.generateNew() returned null")

        val exported = exportIdentityObject(identity)

        verifyIdentityRoundTrip(identityClass, exported)
        persistIdentity(exported, backupCurrent = false)


        return identity
    }

    private fun readIdentityExport(
        identityClass: Class<*>,
        exported: String
    ): Any {
        val readMethod = identityClass.getMethod(
            "read",
            java.io.InputStream::class.java
        )

        val identity: Any = readMethod.invoke(
            null,
            ByteArrayInputStream(
                exported.toByteArray(StandardCharsets.UTF_8)
            )
        ) ?: error("LocalIdentity.read() returned null")

        return identity
    }

    private fun exportIdentityObject(identity: Any): String {
        val export = identity.javaClass.getMethod("export")
        return (export.invoke(identity) as? String)
            ?.takeIf { it.isNotBlank() }
            ?: error("LocalIdentity.export() returned no data")
    }

    private fun verifyIdentityRoundTrip(
        identityClass: Class<*>,
        exported: String
    ) {
        val restored = readIdentityExport(identityClass, exported)
        val restoredExport = exportIdentityObject(restored)

        check(restoredExport.isNotBlank()) {
            "Identity round-trip verification failed"
        }

        val originalPublic = identityString(
            restored,
            "getPublicKeyString",
            "getPublicKey"
        )

        check(
            originalPublic == null || originalPublic.isNotBlank()
        ) {
            "Identity public key verification failed"
        }
    }

    private fun persistIdentity(
        exported: String,
        backupCurrent: Boolean
    ) {
        val current = prefs.getString(KEY_IDENTITY, null)
        val editor = prefs.edit()

        if (
            backupCurrent &&
            !current.isNullOrBlank() &&
            current != exported
        ) {
            editor.putString(KEY_IDENTITY_BACKUP, current)
        }

        editor.putString(KEY_IDENTITY, exported)

        check(editor.commit()) {
            "Could not persist TeamSpeak identity"
        }
    }

    private fun describeIdentity(
        identity: Any,
        exported: String,
        backupAvailable: Boolean
    ): IdentityInfo {
        val publicKey = identityString(
            identity,
            "getPublicKeyString",
            "getPublicKey"
        ).orEmpty()

        val localUid = identityString(
            identity,
            "getUid",
            "getUID",
            "getClientUid",
            "getClientUID",
            "getUniqueIdentifier"
        )?.takeIf { it.isNotBlank() }
            ?: observedClientUid.orEmpty()
        val uid = if (connected) observedClientUid?.takeIf { it.isNotBlank() } ?: localUid else localUid

        val security = identityNumber(
            identity,
            "getSecurityLevel",
            "getLevel"
        )?.toInt()

        return IdentityInfo(
            saved = true,
            clientUid = uid,
            publicKey = publicKey,
            securityLevel = security,
            exportedIdentity = exported,
            loadError = null,
            backupAvailable = backupAvailable
        )
    }

    private fun identityString(
        target: Any,
        vararg names: String
    ): String? {
        names.forEach { name ->
            val method = target.javaClass.methods.firstOrNull {
                it.name.equals(name, ignoreCase = true) &&
                    it.parameterTypes.isEmpty()
            } ?: return@forEach

            val value = runCatching { method.invoke(target) }.getOrNull()
                ?: return@forEach

            val text =
                when (value) {
                    is String ->
                        value

                    else -> {
                        // ts3j Identity.getUid() returns a Uid object. Its inherited
                        // Object.toString() produces "...Uid@xxxx", not the Base64
                        // TeamSpeak unique identifier required by avatar file naming.
                        val toBase64 =
                            value.javaClass.methods.firstOrNull {
                                it.name.equals(
                                    "toBase64",
                                    ignoreCase = true
                                ) &&
                                    it.parameterTypes.isEmpty() &&
                                    it.returnType == String::class.java
                            }

                        if (toBase64 != null) {
                            runCatching {
                                toBase64.invoke(value) as? String
                            }.getOrNull()
                                ?: value.toString()
                        } else {
                            value.toString()
                        }
                    }
                }
                    .trim()

            if (text.isNotBlank()) {
                return text
            }
        }

        return null
    }

    private fun identityNumber(
        target: Any,
        vararg names: String
    ): Number? {
        names.forEach { name ->
            val method = target.javaClass.methods.firstOrNull {
                it.name.equals(name, ignoreCase = true) &&
                    it.parameterTypes.isEmpty()
            } ?: return@forEach

            val value = runCatching { method.invoke(target) }.getOrNull()
            if (value is Number) {
                return value
            }
        }

        return null
    }


    private fun invokeIfPresent(
        target: Any,
        name: String,
        vararg args: Any?
    ): Any? {
        val method = target.javaClass.methods.firstOrNull {
            it.name == name &&
                it.parameterTypes.size == args.size
        } ?: return null

        return method.invoke(target, *args)
    }

    private fun invokeNoArgNumber(target: Any, name: String): Number? {
        val method = target.javaClass.methods.firstOrNull {
            it.name == name && it.parameterTypes.isEmpty()
        } ?: return null
        return method.invoke(target) as? Number
    }

    private fun recordEvent(message: String) {
        synchronized(eventLogLock) {
            if (recentEvents.size >= MAX_EVENT_LOG) {
                recentEvents.removeFirst()
            }
            recentEvents.addLast(message)
        }
    }

    private fun clearEventLog() {
        synchronized(eventLogLock) {
            recentEvents.clear()
        }
    }

    private fun rootThrowable(t: Throwable): Throwable {
        var current = t
        while (
            current is InvocationTargetException &&
            current.cause != null
        ) {
            current = current.cause!!
        }
        while (current.cause != null && current.cause !== current) {
            current = current.cause!!
        }
        return current
    }

    private fun rootMessage(t: Throwable): String {
        val root = rootThrowable(t)
        return root.message ?: root.toString()
    }

    private fun rootClass(t: Throwable): String =
        rootThrowable(t).javaClass.simpleName

    companion object {
        private const val TAG = "TS3X"

        private val SERVER_INFO_RAW_KEYS =
            listOf(
                "virtualserver_name",
                "virtualserver_unique_identifier",
                "virtualserver_platform",
                "virtualserver_version",
                "virtualserver_status",
                "virtualserver_ip",
                "virtualserver_port",
                "virtualserver_created",
                "virtualserver_uptime",
                "virtualserver_maxclients",
                "virtualserver_clientsonline",
                "virtualserver_channelsonline",
                "virtualserver_queryclientsonline",
                "virtualserver_reserved_slots",
                "virtualserver_flag_password",
                "virtualserver_icon_id",
                "virtualserver_welcomemessage",
                "virtualserver_hostmessage",
                "virtualserver_hostbanner_gfx_url",
                "virtualserver_hostbanner_url",
                "virtualserver_hostbutton_gfx_url",
                "virtualserver_hostbutton_url",
                "virtualserver_hostbutton_tooltip",
                "virtualserver_max_download_total_bandwidth",
                "virtualserver_max_upload_total_bandwidth",
                "virtualserver_total_bytes_downloaded",
                "virtualserver_total_bytes_uploaded"
            )

        private val SERVER_INFO_PROPERTY_KEYS =
            setOf(
                "virtualserver_unique_identifier",
                "virtualserver_name",
                "virtualserver_welcomemessage",
                "virtualserver_platform",
                "virtualserver_version",
                "virtualserver_maxclients",
                "virtualserver_clientsonline",
                "virtualserver_channelsonline",
                "virtualserver_created",
                "virtualserver_uptime",
                "virtualserver_status",
                "virtualserver_hostmessage",
                "virtualserver_hostmessage_mode",
                "virtualserver_flag_password",
                "virtualserver_hostbanner_url",
                "virtualserver_hostbanner_gfx_url",
                "virtualserver_hostbanner_gfx_interval",
                "virtualserver_hostbanner_mode",
                "virtualserver_hostbutton_tooltip",
                "virtualserver_hostbutton_url",
                "virtualserver_hostbutton_gfx_url",
                "virtualserver_icon_id",
                "virtualserver_reserved_slots",
                "virtualserver_ip",
                "virtualserver_port",
                "virtualserver_total_bytes_downloaded",
                "virtualserver_total_bytes_uploaded",
                "virtualserver_max_download_total_bandwidth",
                "virtualserver_max_upload_total_bandwidth"
            )
        private const val KEY_IDENTITY = "identity_export"
        private const val KEY_IDENTITY_BACKUP = "identity_export_backup"
        private const val DEFAULT_IDENTITY_SECURITY_LEVEL = 10
        private const val MAX_EVENT_LOG = 80
    }
}
