package com.morsen.ts3xclient

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Local history for channel and private conversations on TS3 servers; NOT the myTeamSpeak cloud service. */
data class BackendChatMessage(
    val id: Long,
    val sender: String,
    val text: String,
    val outgoing: Boolean,
    val receivedAt: Long
)

internal class ChatHistoryStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "ts3x_server_chat.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                server_key TEXT NOT NULL,
                conversation_key TEXT NOT NULL,
                sender TEXT NOT NULL,
                message TEXT NOT NULL,
                outgoing INTEGER NOT NULL,
                received_at INTEGER NOT NULL,
                unread INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX chat_messages_idx ON messages(server_key, conversation_key, id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    // Minimize SQLite queries during Compose recomposition of large channel trees.
    private val unreadCache = mutableMapOf<Pair<String, String>, Int>()

    @Synchronized
    fun add(server: String, conversation: String, sender: String, text: String, outgoing: Boolean): BackendChatMessage {
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("server_key", server)
            put("conversation_key", conversation)
            put("sender", sender.take(256))
            put("message", text)
            put("outgoing", if (outgoing) 1 else 0)
            put("received_at", now)
            put("unread", if (outgoing) 0 else 1)
        }
        val db = writableDatabase
        val id = db.insertOrThrow("messages", null, values)
        // Retain a bounded history, separately per server + conversation.
        db.execSQL(
            """DELETE FROM messages WHERE server_key=? AND conversation_key=? AND id NOT IN
                (SELECT id FROM messages WHERE server_key=? AND conversation_key=? ORDER BY id DESC LIMIT 500)""",
            arrayOf(server, conversation, server, conversation)
        )
        if (!outgoing) unreadCache.remove(server to conversation)
        return BackendChatMessage(id, sender.take(256), text, outgoing, now)
    }

    @Synchronized
    fun list(server: String, conversation: String): List<BackendChatMessage> {
        val result = mutableListOf<BackendChatMessage>()
        readableDatabase.query(
            "messages", arrayOf("id", "sender", "message", "outgoing", "received_at"),
            "server_key=? AND conversation_key=?", arrayOf(server, conversation),
            null, null, "id DESC", "500"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result.add(BackendChatMessage(cursor.getLong(0), cursor.getString(1),
                    cursor.getString(2), cursor.getInt(3) == 1, cursor.getLong(4)))
            }
        }
        return result.asReversed()
    }

    @Synchronized
    fun unread(server: String, conversation: String): Int {
        val cacheKey = server to conversation
        unreadCache[cacheKey]?.let { return it }
        return readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM messages WHERE server_key=? AND conversation_key=? AND unread=1",
            arrayOf(server, conversation)
        ).use { cursor ->
            (if (cursor.moveToFirst()) cursor.getInt(0) else 0).also {
                unreadCache[cacheKey] = it
            }
        }
    }

    @Synchronized
    fun markRead(server: String, conversation: String) {
        writableDatabase.execSQL(
            "UPDATE messages SET unread=0 WHERE server_key=? AND conversation_key=?",
            arrayOf(server, conversation)
        )
        unreadCache[server to conversation] = 0
    }
}
