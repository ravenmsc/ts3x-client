package com.morsen.ts3xclient

import org.json.JSONObject

/**
 * Presentation-only decoder. TS3 carries text; TS6 may place a myTeamSpeak
 * cloud-file descriptor in that text. A file_id is NOT a downloadable URL.
 */
internal data class CloudChatAttachment(
    val fileName: String,
    val fileSize: Long?,
    val image: Boolean,
    val cloudId: String,
    val cloudUserId: String
)

internal data class ChatMessageContent(
    val body: String,
    val cloudAttachment: CloudChatAttachment? = null,
    val directImageUrl: String? = null
)

internal object ChatMessageContentParser {
    private val imageExtensions = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "avif")
    private val embeddedImage = Regex("^\\[img]([^\\[\\]]+)\\[/img]$", RegexOption.IGNORE_CASE)
    private val httpsImage = Regex("^https://[^\\s<>\\\"']+$", RegexOption.IGNORE_CASE)

    /** Image links are rendered only when they explicitly contain an HTTPS URL. */
    private fun directImageUrl(raw: String): String? {
        val text = raw.trim().let { embeddedImage.matchEntire(it)?.groupValues?.getOrNull(1) ?: it }
        if (!httpsImage.matches(text)) return null
        val path = text.substringBefore('?').substringBefore('#')
        val extension = path.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
        return text.takeIf { extension in imageExtensions && it.length <= 4096 }
    }

    fun parse(raw: String): ChatMessageContent {
        val trimmed = raw.trim()
        if (trimmed.startsWith('{') && trimmed.length <= 65536 && "\"msg_type\"" in trimmed) {
            val json = runCatching { JSONObject(trimmed) }.getOrNull()
            if (json?.optString("msg_type") == "ts.file.myts") {
                val fileId = json.optString("file_id").take(8192)
                val cloudUser = json.optString("chat_user_id").take(1024)
                if (fileId.isNotBlank() && cloudUser.isNotBlank()) {
                    val fileName = json.optString("file_name")
                        .ifBlank { "Attachment" }.take(255)
                    val size = json.optLong("file_size", -1L).takeIf { it >= 0 }
                    val ext = fileName.substringAfterLast('.', "")
                        .lowercase(java.util.Locale.ROOT)
                    val caption = json.optString("body")
                        .take(4096).takeUnless { it == fileName }.orEmpty()
                    // The JSON alone has no actual URL or myTeamSpeak auth.
                    // Never construct a guessed endpoint from file_id/chat_user_id.
                    return ChatMessageContent(
                        body = caption,
                        cloudAttachment = CloudChatAttachment(
                            fileName = fileName,
                            fileSize = size,
                            image = ext in imageExtensions,
                            cloudId = fileId,
                            cloudUserId = cloudUser
                        )
                    )
                }
            }
        }
        return ChatMessageContent(trimmed, directImageUrl = directImageUrl(trimmed))
    }
}
