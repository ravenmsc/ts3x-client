package com.morsen.ts3xclient

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import androidx.core.content.ContextCompat
import org.concentus.OpusApplication
import org.concentus.OpusDecoder
import org.concentus.OpusEncoder
import org.concentus.OpusSignal
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.max

data class AudioStats(
    val attached: Boolean,
    val transmitting: Boolean,
    val captureActive: Boolean,
    val inputMode: AudioInputMode,
    val inputMuted: Boolean,
    val playbackMuted: Boolean,
    val txPackets: Long,
    val rxPackets: Long,
    val micReadyCalls: Long,
    val micProvideCalls: Long,
    val activeSpeakers: Int,
    val micLevel: Float,
    val lastError: String?
)

/**
 * Android audio bridge for ts3j.
 *
 * Outgoing:
 * AudioRecord PCM16 mono / 48 kHz / 20 ms -> Concentus Opus -> ts3j Microphone
 *
 * Incoming:
 * ts3j voice Consumer -> Concentus Opus decoder per remote client -> AudioTrack
 *
 * ts3j's audio hooks are connected reflectively because the project is tracking
 * a JitPack snapshot and those interface package names are not stable API.
 */
data class AudioDeviceOption(
    val id: Int?,
    val label: String,
    val type: Int = 0
)

class Ts3AudioEngine(
    context: Context,
    private val settings: AppSettingsStore
) {
    private val appContext = context.applicationContext

    private val outgoingPackets = ArrayBlockingQueue<ByteArray>(MAX_TX_QUEUE)
    private val transmitting = AtomicBoolean(false)
    private val captureActive = AtomicBoolean(false)
    private val alwaysOnRequested = AtomicBoolean(false)
    private val attached = AtomicBoolean(false)
    private val txPackets = AtomicLong(0)
    private val rxPackets = AtomicLong(0)
    private val micReadyCalls = AtomicLong(0)
    private val micProvideCalls = AtomicLong(0)

    @Volatile
    private var inputMuted = false

    @Volatile
    private var playbackMuted = false

    @Volatile
    private var inputMode: AudioInputMode = settings.audioInputMode()

    @Volatile
    private var voiceActivationThreshold: Float = settings.voiceActivationThreshold()

    @Volatile
    private var preferredInputDeviceId: Int? = settings.inputDeviceId()

    @Volatile
    private var preferredOutputDeviceId: Int? = settings.outputDeviceId()

    @Volatile
    private var micLevel = 0f

    @Volatile
    private var lastError: String? = null

    @Volatile
    private var captureThread: Thread? = null

    @Volatile
    private var microphoneProxy: Any? = null

    @Volatile
    private var voiceHandlerProxy: Any? = null

    @Volatile
    private var whisperHandlerProxy: Any? = null

    private val individualVolumes = ConcurrentHashMap<Int, Float>()

    fun clientVolume(id: Int): Float = individualVolumes[id] ?: 1f

    fun setClientVolume(id: Int, value: Float) {
        val volume = value.coerceIn(0f, 2f)
        individualVolumes[id] = volume
        playbackSessions[id]?.setIndividualVolume(volume)
    }

    private val playbackSessions = ConcurrentHashMap<Int, PlaybackSession>()
    // Last received Opus packet timestamp per remote speaker, in monotonic time.
    private val recentVoicePackets = ConcurrentHashMap<Int, Long>()

    fun activeVoiceClientIds(): Set<Int> {
        val now = android.os.SystemClock.elapsedRealtime()
        recentVoicePackets.entries.removeIf { now - it.value > 120L }
        return recentVoicePackets.entries.asSequence()
            .filter { now - it.value <= 100L }
            .map { it.key }.toSet()
    }


    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val encoder: OpusEncoder = OpusEncoder(
        SAMPLE_RATE,
        TX_CHANNELS,
        OpusApplication.OPUS_APPLICATION_VOIP
    ).also { opus: OpusEncoder ->
        opus.setBitrate(TX_BITRATE)
        opus.setComplexity(5)
        opus.setUseVBR(true)
        opus.setSignalType(OpusSignal.OPUS_SIGNAL_VOICE)
        opus.setUseInbandFEC(true)
        opus.setPacketLossPercent(10)
    }

    fun attach(socket: Any): Result<Unit> = runCatching {
        detach()

        micReadyCalls.set(0)
        micProvideCalls.set(0)
        txPackets.set(0)
        rxPackets.set(0)

        installMicrophone(socket)
        installVoiceConsumer(socket, "setVoiceHandler", whisper = false)
        installVoiceConsumer(socket, "setWhisperHandler", whisper = true)

        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        applyCommunicationRoute()

        attached.set(true)
        lastError = null

        if (inputMode == AudioInputMode.ALWAYS_ON) {
            alwaysOnRequested.set(true)
            if (
                ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED && !inputMuted
            ) {
                startAlwaysOn().getOrThrow()
            }
        }


        Unit
    }.onFailure {
        lastError = rootMessage(it)
        Log.e(TAG, "Audio attach failed: ${rootMessage(it)}", rootThrowable(it))
    }

    fun detach() {
        stopCapture(keepAlwaysOnRequest = false)
        alwaysOnRequested.set(inputMode == AudioInputMode.ALWAYS_ON)
        attached.set(false)

        playbackSessions.values.forEach { it.close() }
        playbackSessions.clear()
        recentVoicePackets.clear()

        microphoneProxy = null
        voiceHandlerProxy = null
        whisperHandlerProxy = null

        outgoingPackets.clear()
        micLevel = 0f

        runCatching {
            audioManager.mode = AudioManager.MODE_NORMAL
        }
    }

    fun startTransmit(): Result<Unit> = runCatching {
        check(inputMode == AudioInputMode.PTT) {
            "Push-to-Talk is disabled while AlwaysOn mode is selected"
        }
        checkAudioCaptureAvailable()

        if (!captureActive.compareAndSet(false, true)) {
            transmitting.set(true)
            return@runCatching Unit
        }

        outgoingPackets.clear()
        transmitting.set(true)
        startCaptureThread(AudioInputMode.PTT)
        Unit
    }.onFailure {
        transmitting.set(false)
        captureActive.set(false)
        lastError = rootMessage(it)
        Log.e(TAG, "PTT start failed: ${rootMessage(it)}", rootThrowable(it))
    }

    fun stopTransmit() {
        if (inputMode != AudioInputMode.PTT) return
        stopCapture(keepAlwaysOnRequest = true)
    }

    fun startAlwaysOn(): Result<Unit> = runCatching {
        check(inputMode == AudioInputMode.ALWAYS_ON) {
            "AlwaysOn mode is not selected"
        }
        alwaysOnRequested.set(true)
        checkAudioCaptureAvailable()

        if (!captureActive.compareAndSet(false, true)) {
            return@runCatching Unit
        }

        outgoingPackets.clear()
        transmitting.set(false)
        startCaptureThread(AudioInputMode.ALWAYS_ON)
        Unit
    }.onFailure {
        captureActive.set(false)
        transmitting.set(false)
        lastError = rootMessage(it)
        Log.e(TAG, "AlwaysOn start failed: ${rootMessage(it)}", rootThrowable(it))
    }

    fun stopAlwaysOn() {
        alwaysOnRequested.set(false)
        stopCapture(keepAlwaysOnRequest = false)
    }

    fun isAlwaysOnActive(): Boolean =
        inputMode == AudioInputMode.ALWAYS_ON && captureActive.get()

    fun audioInputMode(): AudioInputMode = inputMode

    fun voiceActivationThreshold(): Float = voiceActivationThreshold

    fun setAudioInputSettings(
        mode: AudioInputMode,
        threshold: Float
    ): Result<Unit> = runCatching {
        val normalized = threshold.coerceIn(0.005f, 0.50f)
        if (inputMode != mode) stopCapture(keepAlwaysOnRequest = false)

        inputMode = mode
        voiceActivationThreshold = normalized
        settings.setAudioInputSettings(mode, normalized)

        if (mode == AudioInputMode.ALWAYS_ON) {
            alwaysOnRequested.set(true)
            if (
                attached.get() && !inputMuted &&
                ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                startAlwaysOn().getOrThrow()
            }
        } else {
            alwaysOnRequested.set(false)
        }
        Unit
    }

    private fun checkAudioCaptureAvailable() {
        check(attached.get()) { "Audio bridge is not attached" }
        check(!inputMuted) { "Microphone is muted" }
        if (
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            error("RECORD_AUDIO permission is not granted")
        }
    }

    private fun startCaptureThread(mode: AudioInputMode) {
        val thread = Thread(
            { captureLoop(mode) },
            if (mode == AudioInputMode.ALWAYS_ON) "TS3-VoiceActivation" else "TS3-Microphone"
        ).apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        captureThread = thread
    }

    private fun stopCapture(keepAlwaysOnRequest: Boolean) {
        val wasSending = transmitting.getAndSet(false)
        captureActive.set(false)
        captureThread?.interrupt()
        captureThread = null
        outgoingPackets.clear()
        if (wasSending) offerPacket(ByteArray(0))
        micLevel = 0f
        if (!keepAlwaysOnRequest) alwaysOnRequested.set(false)
    }


    /**
     * Manual microphone mute.
     *
     * We intentionally keep ts3j Microphone.isMuted() false because its current
     * MicrophoneTask has a TODO in that branch and would not close an active
     * voice stream. Local mute is implemented by stopping AudioRecord and making
     * isReady() false; the backend separately publishes client_input_muted.
     */
    fun setInputMuted(muted: Boolean) {
        if (muted) {
            stopCapture(keepAlwaysOnRequest = true)
        }

        inputMuted = muted
        outgoingPackets.clear()
        micLevel = 0f

        if (
            !muted && inputMode == AudioInputMode.ALWAYS_ON &&
            alwaysOnRequested.get() && attached.get() &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startAlwaysOn()
        }


    }

    fun isInputMuted(): Boolean = inputMuted

    fun inputDevices(): List<AudioDeviceOption> {
        val physical = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .asSequence()
            .filter { it.isSource }
            .filter { isPhysicalInputType(it.type) }
            .distinctBy { "${it.type}:${it.productName.toString().trim().lowercase()}" }
            .map { AudioDeviceOption(it.id, deviceLabel(it), it.type) }
            .toList()

        return listOf(AudioDeviceOption(null, "System default")) + physical
    }

    fun outputDevices(): List<AudioDeviceOption> {
        val candidates = if (android.os.Build.VERSION.SDK_INT >= 31) {
            audioManager.availableCommunicationDevices
        } else {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        }

        val physical = candidates.asSequence()
            .filter { it.isSink }
            .filter { isPhysicalOutputType(it.type) }
            .distinctBy { "${it.type}:${it.productName.toString().trim().lowercase()}" }
            .map { AudioDeviceOption(it.id, deviceLabel(it), it.type) }
            .toList()

        return listOf(AudioDeviceOption(null, "System default")) + physical
    }

    private fun isPhysicalInputType(type: Int): Boolean =
        type == AudioDeviceInfo.TYPE_BUILTIN_MIC ||
            type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
            type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            type == AudioDeviceInfo.TYPE_USB_DEVICE ||
            type == AudioDeviceInfo.TYPE_USB_HEADSET ||
            type == AudioDeviceInfo.TYPE_USB_ACCESSORY ||
            (android.os.Build.VERSION.SDK_INT >= 31 && type == AudioDeviceInfo.TYPE_BLE_HEADSET)

    private fun isPhysicalOutputType(type: Int): Boolean =
        type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE ||
            type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER ||
            type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
            type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
            type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            type == AudioDeviceInfo.TYPE_USB_DEVICE ||
            type == AudioDeviceInfo.TYPE_USB_HEADSET ||
            type == AudioDeviceInfo.TYPE_USB_ACCESSORY ||
            type == AudioDeviceInfo.TYPE_HEARING_AID ||
            (android.os.Build.VERSION.SDK_INT >= 31 &&
                (type == AudioDeviceInfo.TYPE_BLE_HEADSET || type == AudioDeviceInfo.TYPE_BLE_SPEAKER))


    fun preferredInputDeviceId(): Int? = preferredInputDeviceId

    fun preferredOutputDeviceId(): Int? = preferredOutputDeviceId

    fun setPreferredInputDevice(id: Int?) {
        preferredInputDeviceId = id
        settings.setInputDeviceId(id)

    }

    fun setPreferredOutputDevice(id: Int?): Result<Unit> = runCatching {
        preferredOutputDeviceId = id
        settings.setOutputDeviceId(id)

        applyCommunicationRoute()

        playbackSessions.values.forEach { session ->
            session.applyPreferredDevice()
        }


        Unit
    }.onFailure {
        lastError = rootMessage(it)
        Log.e(TAG, "Output routing failed: ${rootMessage(it)}", rootThrowable(it))
    }

    fun setPlaybackMuted(muted: Boolean) {
        playbackMuted = muted

        playbackSessions.values.forEach { session ->
            session.setMuted(muted)
        }


    }

    fun stats(): AudioStats = AudioStats(
        attached = attached.get(),
        transmitting = transmitting.get(),
        captureActive = captureActive.get(),
        inputMode = inputMode,
        inputMuted = inputMuted,
        playbackMuted = playbackMuted,
        txPackets = txPackets.get(),
        rxPackets = rxPackets.get(),
        micReadyCalls = micReadyCalls.get(),
        micProvideCalls = micProvideCalls.get(),
        activeSpeakers = playbackSessions.size,
        micLevel = micLevel,
        lastError = lastError
    )

    private fun installMicrophone(socket: Any) {
        val setter = socket.javaClass.methods.firstOrNull {
            it.name == "setMicrophone" &&
                it.parameterTypes.size == 1
        } ?: error("ts3j setMicrophone(...) not found")

        val microphoneType = setter.parameterTypes[0]
        check(microphoneType.isInterface) {
            "ts3j microphone type is not an interface: ${microphoneType.name}"
        }

        val proxy = Proxy.newProxyInstance(
            microphoneType.classLoader,
            arrayOf(microphoneType)
        ) { instance, method, args ->
            when (method.name) {
                "toString" -> "AndroidTs3Microphone"
                "hashCode" -> System.identityHashCode(instance)
                "equals" -> instance === args?.firstOrNull()

                // Keep false: upstream ts3j's isMuted branch currently has a TODO.
                // Manual mute is represented by isReady=false + TS3 clientupdate.
                "isMuted" -> false

                "isReady" -> {
                    val calls = micReadyCalls.incrementAndGet()
                    val ready = !inputMuted &&
                        (transmitting.get() || outgoingPackets.isNotEmpty())

                    if (calls <= 5L || calls % 250L == 0L) {

                    }

                    ready
                }

                "getCodec" -> {
                    val constants = method.returnType.enumConstants
                        ?: error("Microphone.getCodec() does not return an enum")

                    constants.firstOrNull {
                        (it as? Enum<*>)?.name == "OPUS_VOICE"
                    } ?: constants.firstOrNull {
                        (it as? Enum<*>)?.name == "OPUS_MUSIC"
                    } ?: error(
                        "No OPUS codec in ${method.returnType.name}: " +
                            constants.joinToString()
                    )
                }

                "provide" -> {
                    val calls = micProvideCalls.incrementAndGet()
                    val packet = outgoingPackets.poll() ?: ByteArray(0)

                    if (packet.isNotEmpty()) {
                        val tx = txPackets.incrementAndGet()
                        if (tx <= 5L || tx % 100L == 0L) {

                        }
                    } else if (calls <= 5L || calls % 250L == 0L) {

                    }

                    packet
                }

                else -> defaultReturn(method.returnType)
            }
        }

        setter.invoke(socket, proxy)
        microphoneProxy = proxy


    }

    private fun installVoiceConsumer(
        socket: Any,
        setterName: String,
        whisper: Boolean
    ) {
        val setter = socket.javaClass.methods.firstOrNull {
            it.name == setterName &&
                it.parameterTypes.size == 1
        }

        if (setter == null) {
            Log.w(TAG, "$setterName(...) not found")
            return
        }

        val consumerType = setter.parameterTypes[0]
        check(consumerType.isInterface) {
            "$setterName parameter is not an interface: ${consumerType.name}"
        }

        val proxy = Proxy.newProxyInstance(
            consumerType.classLoader,
            arrayOf(consumerType)
        ) { instance, method, args ->
            when (method.name) {
                "toString" -> if (whisper) {
                    "AndroidTs3WhisperConsumer"
                } else {
                    "AndroidTs3VoiceConsumer"
                }

                "hashCode" -> System.identityHashCode(instance)
                "equals" -> instance === args?.firstOrNull()

                "accept" -> {
                    val packet = args?.firstOrNull()
                    if (packet != null) {
                        handleIncoming(packet, whisper)
                    }
                    null
                }

                else -> defaultReturn(method.returnType)
            }
        }

        setter.invoke(socket, proxy)

        if (whisper) {
            whisperHandlerProxy = proxy
        } else {
            voiceHandlerProxy = proxy
        }


    }

    @Suppress("MissingPermission")
    private fun captureLoop(mode: AudioInputMode) {
        var recorder: AudioRecord? = null
        var aec: AcousticEchoCanceler? = null
        var ns: NoiseSuppressor? = null

        try {
            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            check(minBuffer > 0) { "AudioRecord unsupported: minBuffer=$minBuffer" }
            val bufferBytes = max(minBuffer * 2, FRAME_SAMPLES * 2 * 4)

            recorder = createRecorder(MediaRecorder.AudioSource.VOICE_COMMUNICATION, bufferBytes)
                ?: createRecorder(MediaRecorder.AudioSource.MIC, bufferBytes)
                ?: error("Could not initialize AudioRecord at 48 kHz mono")

            if (AcousticEchoCanceler.isAvailable()) {
                aec = AcousticEchoCanceler.create(recorder.audioSessionId)
                aec?.enabled = true
            }
            if (NoiseSuppressor.isAvailable()) {
                ns = NoiseSuppressor.create(recorder.audioSessionId)
                ns?.enabled = true
            }

            val pcm = ShortArray(FRAME_SAMPLES)
            val encoded = ByteArray(MAX_OPUS_PACKET)
            var lastVoiceAt = 0L
            var voiceOpen = false
            recorder.startRecording()

            while (captureActive.get() && !Thread.currentThread().isInterrupted) {
                var offset = 0
                while (offset < FRAME_SAMPLES && captureActive.get() && !Thread.currentThread().isInterrupted) {
                    val read = recorder.read(pcm, offset, FRAME_SAMPLES - offset, AudioRecord.READ_BLOCKING)
                    if (read < 0) error("AudioRecord.read failed: $read")
                    offset += read
                }
                if (offset != FRAME_SAMPLES) break

                updateMicLevel(pcm)
                val shouldSend = when (mode) {
                    AudioInputMode.PTT -> true
                    AudioInputMode.ALWAYS_ON -> {
                        val now = System.currentTimeMillis()
                        if (micLevel >= voiceActivationThreshold) lastVoiceAt = now
                        lastVoiceAt > 0L && now - lastVoiceAt <= VOX_HANGOVER_MS
                    }
                }

                val activeVoice = shouldSend && !inputMuted

                if (!activeVoice) {
                    transmitting.set(false)
                    if (voiceOpen) {
                        outgoingPackets.clear()
                        offerPacket(ByteArray(0))
                        voiceOpen = false
                    }
                    continue
                }

                transmitting.set(true)
                voiceOpen = true

                val bytes: Int = synchronized(encoder) {
                    encoder.encode(pcm, 0, FRAME_SAMPLES, encoded, 0, encoded.size)
                }
                if (bytes > 0) offerPacket(encoded.copyOf(bytes))
            }
        } catch (t: Throwable) {
            lastError = rootMessage(t)
            Log.e(TAG, "Capture loop failed: ${rootMessage(t)}", rootThrowable(t))
        } finally {
            runCatching {
                if (recorder?.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder?.stop()
            }
            runCatching { recorder?.release() }
            runCatching { aec?.release() }
            runCatching { ns?.release() }
            transmitting.set(false)
            captureActive.set(false)
            micLevel = 0f
        }
    }


    @Suppress("MissingPermission")
    private fun createRecorder(
        source: Int,
        bufferBytes: Int
    ): AudioRecord? = runCatching {
        val recorder = AudioRecord(
            source,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferBytes
        )

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return@runCatching null
        }

        preferredInputDeviceId?.let { id ->
            val device = audioManager
                .getDevices(AudioManager.GET_DEVICES_INPUTS)
                .firstOrNull { it.id == id }

            if (device != null) {
                val applied = recorder.setPreferredDevice(device)

            }
        }

        recorder
    }.getOrNull()

    private fun applyCommunicationRoute() {
        val id = preferredOutputDeviceId

        if (android.os.Build.VERSION.SDK_INT >= 31) {
            if (id == null) {
                audioManager.clearCommunicationDevice()

            } else {
                val device = audioManager.availableCommunicationDevices
                    .firstOrNull { it.id == id }

                if (device != null) {
                    val applied = audioManager.setCommunicationDevice(device)

                } else {
                    Log.w(TAG, "Requested communication device id=$id not available")
                }
            }
        } else {
            val device = id?.let { selectedId ->
                audioManager
                    .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                    .firstOrNull { it.id == selectedId }
            }

            @Suppress("DEPRECATION")
            runCatching {
                audioManager.isSpeakerphoneOn =
                    device?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            }
        }
    }

    private fun findOutputDevice(): AudioDeviceInfo? {
        val id = preferredOutputDeviceId ?: return null

        return audioManager
            .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.id == id }
            ?: if (android.os.Build.VERSION.SDK_INT >= 31) {
                audioManager.availableCommunicationDevices
                    .firstOrNull { it.id == id }
            } else {
                null
            }
    }

    private fun deviceLabel(device: AudioDeviceInfo): String {
        val type = when (device.type) {
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Earpiece"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Speaker"
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in mic"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headphones"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth headset"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth audio"
            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB audio"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
            AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB accessory"
            AudioDeviceInfo.TYPE_TELEPHONY -> "Telephony"
            else -> "Audio device ${device.type}"
        }

        val product = device.productName?.toString()?.trim().orEmpty()

        return if (
            product.isNotBlank() &&
            !product.equals(type, ignoreCase = true)
        ) {
            "$type — $product"
        } else {
            type
        }
    }

    private fun offerPacket(packet: ByteArray) {
        if (!outgoingPackets.offer(packet)) {
            // Voice must stay real-time. Drop the oldest packet rather than
            // increasing latency without bound.
            outgoingPackets.poll()
            outgoingPackets.offer(packet)
            Log.w(TAG, "TX queue overflow; dropped oldest voice frame")
        }
    }

    private fun updateMicLevel(pcm: ShortArray) {
        var peak = 0

        for (sample in pcm) {
            peak = max(peak, abs(sample.toInt()))
        }

        micLevel = (peak / 32768f).coerceIn(0f, 1f)
    }

    private fun handleIncoming(packet: Any, whisper: Boolean) {
        try {
            val payload = extractByteArray(packet)

            if (payload == null) {
                Log.w(
                    TAG,
                    "Voice packet has no byte[] payload: ${describeObject(packet)}"
                )
                return
            }

            val clientId = extractInt(
                packet,
                "getClientId",
                "getClientID",
                "getSourceClientId",
                "getSourceClientID",
                "getSenderId",
                "getSenderID"
            ) ?: -1

            val codecName = extractEnumName(
                packet,
                "getCodec",
                "getCodecType"
            ) ?: "OPUS_VOICE"

            if (!codecName.contains("OPUS", ignoreCase = true)) {
                Log.w(
                    TAG,
                    "Unsupported incoming codec=$codecName client=$clientId"
                )
                return
            }

            if (payload.isEmpty()) {
                playbackSessions.remove(clientId)?.close()

                return
            }

            val channels = if (
                codecName.contains("MUSIC", ignoreCase = true)
            ) {
                2
            } else {
                1
            }

            val session = playbackSessions.compute(clientId) { _, existing ->
                if (existing == null || existing.channels != channels) {
                    existing?.close()
                    PlaybackSession(
                        clientId = clientId,
                        channels = channels,
                        muted = playbackMuted
                    )
                } else {
                    existing
                }
            } ?: return

            // A valid packet represents actual remote transmission, not merely
            // an enabled microphone. Refresh the ring while packets arrive.
            if (clientId > 0) {
                recentVoicePackets[clientId] = android.os.SystemClock.elapsedRealtime()
            }
            session.decodeAndPlay(payload)
            rxPackets.incrementAndGet()

            if (rxPackets.get() <= 5L || rxPackets.get() % 100L == 0L) {

            }
        } catch (t: Throwable) {
            lastError = rootMessage(t)
            Log.e(
                TAG,
                "Voice decode failed: ${rootMessage(t)}; packet=${describeObject(packet)}",
                rootThrowable(t)
            )
        }
    }

    /**
     * ts3j snapshot classes have changed names over time, so inspect all simple
     * zero-arg byte[] getters and then byte[] fields.
     */
    private fun extractByteArray(target: Any): ByteArray? {
        val preferredNames = listOf(
            "getData",
            "getPayload",
            "getVoiceData",
            "getAudioData",
            "getPacketData",
            "getCodecData"
        )

        preferredNames.forEach { name ->
            val value = runCatching {
                target.javaClass.methods.firstOrNull {
                    it.name.equals(name, ignoreCase = true) &&
                        it.parameterTypes.isEmpty() &&
                        it.returnType == ByteArray::class.java
                }?.invoke(target)
            }.getOrNull()

            if (value is ByteArray) {
                return value
            }
        }

        target.javaClass.methods.firstOrNull {
            it.parameterTypes.isEmpty() &&
                it.returnType == ByteArray::class.java
        }?.let { method ->
            val value = runCatching { method.invoke(target) }.getOrNull()
            if (value is ByteArray) return value
        }

        allFields(target.javaClass).firstOrNull {
            it.type == ByteArray::class.java
        }?.let { field ->
            field.isAccessible = true
            val value = runCatching { field.get(target) }.getOrNull()
            if (value is ByteArray) return value
        }

        return null
    }

    private fun extractInt(
        target: Any,
        vararg names: String
    ): Int? {
        names.forEach { name ->
            val value = runCatching {
                target.javaClass.methods.firstOrNull {
                    it.name.equals(name, ignoreCase = true) &&
                        it.parameterTypes.isEmpty()
                }?.invoke(target)
            }.getOrNull()

            if (value is Number) return value.toInt()
        }

        return null
    }

    private fun extractEnumName(
        target: Any,
        vararg names: String
    ): String? {
        names.forEach { name ->
            val value = runCatching {
                target.javaClass.methods.firstOrNull {
                    it.name.equals(name, ignoreCase = true) &&
                        it.parameterTypes.isEmpty()
                }?.invoke(target)
            }.getOrNull()

            if (value is Enum<*>) return value.name
            if (value != null) return value.toString()
        }

        return null
    }

    private fun describeObject(target: Any): String {
        val methods = target.javaClass.methods
            .filter { it.parameterTypes.isEmpty() }
            .map(Method::getName)
            .distinct()
            .sorted()
            .take(30)

        val fields = allFields(target.javaClass)
            .map { "${it.name}:${it.type.simpleName}" }
            .take(20)

        return "${target.javaClass.name} methods=$methods fields=$fields"
    }

    private fun allFields(type: Class<*>): List<Field> {
        val result = mutableListOf<Field>()
        var current: Class<*>? = type

        while (current != null && current != Any::class.java) {
            result += current.declaredFields
            current = current.superclass
        }

        return result
    }

    private fun defaultReturn(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        java.lang.Character.TYPE -> '\u0000'
        java.lang.Void.TYPE -> null
        else -> null
    }

    private fun rootThrowable(t: Throwable): Throwable {
        var current = t
        while (current.cause != null && current.cause !== current) {
            current = current.cause!!
        }
        return current
    }

    private fun rootMessage(t: Throwable): String {
        val root = rootThrowable(t)
        return root.message ?: root.toString()
    }

    private inner class PlaybackSession(
        val clientId: Int,
        val channels: Int,
        muted: Boolean
    ) {
        private val decoder: OpusDecoder = OpusDecoder(SAMPLE_RATE, channels)

        private val audioTrack: AudioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(
                        if (channels == 2) {
                            AudioFormat.CHANNEL_OUT_STEREO
                        } else {
                            AudioFormat.CHANNEL_OUT_MONO
                        }
                    )
                    .build()
            )
            .setBufferSizeInBytes(
                max(
                    AudioTrack.getMinBufferSize(
                        SAMPLE_RATE,
                        if (channels == 2) {
                            AudioFormat.CHANNEL_OUT_STEREO
                        } else {
                            AudioFormat.CHANNEL_OUT_MONO
                        },
                        AudioFormat.ENCODING_PCM_16BIT
                    ),
                    SAMPLE_RATE * channels
                )
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        @Volatile
        private var mutedNow = muted

        @Volatile
        private var individualVolume = clientVolume(clientId)

        @Volatile
        private var closed = false

        init {
            applyPreferredDevice()
            setMuted(muted)
            audioTrack.play()
        }

        fun applyPreferredDevice() {
            val device = findOutputDevice()
            val applied = runCatching {
                audioTrack.setPreferredDevice(device)
            }.getOrDefault(false)


        }

        @Synchronized
        fun decodeAndPlay(payload: ByteArray) {
            if (closed) return

            // 120 ms is the maximum Opus packet duration at 48 kHz.
            val pcm = ShortArray(MAX_DECODE_SAMPLES * channels)
            val samplesPerChannel: Int = decoder.decode(
                payload,
                0,
                payload.size,
                pcm,
                0,
                MAX_DECODE_SAMPLES,
                false
            )

            val shorts = samplesPerChannel * channels
            if (shorts > 0) {
                if (individualVolume > 1f && !mutedNow) {
                    val gain = individualVolume
                    for (i in 0 until shorts) {
                        pcm[i] = (pcm[i].toInt() * gain).toInt()
                            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                    }
                }
                audioTrack.write(
                    pcm,
                    0,
                    shorts,
                    AudioTrack.WRITE_BLOCKING
                )
            }
        }

        fun setMuted(muted: Boolean) {
            mutedNow = muted
            applyVolume()
        }

        fun setIndividualVolume(value: Float) {
            individualVolume = value
            applyVolume()
        }

        private fun applyVolume() {
            runCatching {
                audioTrack.setVolume(if (mutedNow) 0f else individualVolume.coerceAtMost(1f))
            }
        }

        @Synchronized
        fun close() {
            if (closed) return
            closed = true

            runCatching { audioTrack.pause() }
            runCatching { audioTrack.flush() }
            runCatching { audioTrack.stop() }
            runCatching { audioTrack.release() }
        }
    }

    companion object {
        private const val TAG = "TS3AUDIO"

        private const val SAMPLE_RATE = 48_000
        private const val TX_CHANNELS = 1
        private const val FRAME_SAMPLES = 960 // 20 ms @ 48 kHz
        private const val TX_BITRATE = 32_000
        private const val MAX_OPUS_PACKET = 1275
        private const val MAX_DECODE_SAMPLES = 5760 // 120 ms @ 48 kHz
        private const val MAX_TX_QUEUE = 12
        private const val VOX_HANGOVER_MS = 420L
    }
}
