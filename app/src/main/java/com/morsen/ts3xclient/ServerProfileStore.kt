package com.morsen.ts3xclient

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class SavedServerProfile(
    val id: String = UUID.randomUUID().toString(),
    /**
     * Optional user override. If non-empty, it always wins over the name
     * reported by the TeamSpeak server.
     */
    val name: String = "",
    /**
     * Cached server-reported VIRTUALSERVER_NAME.
     */
    val serverName: String = "",
    val host: String,
    val port: Int = 9987,
    val password: String = "",
    val monitorEnabled: Boolean = false,
    val monitoredChannelIds: Set<Long> = emptySet()
) {
    val displayName: String
        get() = name.trim().ifBlank {
            serverName.trim().ifBlank { host }
        }

    fun monitorDescription(intervalMinutes: Int): String {
        if (!monitorEnabled) {
            return "Online monitoring disabled"
        }

        val period = formatMonitorPeriod(intervalMinutes)

        val channels = if (monitoredChannelIds.isEmpty()) {
            "all channels"
        } else {
            monitoredChannelIds
                .sorted()
                .joinToString(", ")
        }

        return "Check Online every $period: $channels"
    }

    private fun formatMonitorPeriod(minutes: Int): String =
        when (minutes) {
            1 -> "1 min"
            in 2..59 -> "$minutes min"
            60 -> "1 hour"
            120 -> "2 hours"
            300 -> "5 hours"
            720 -> "12 hours"
            else -> if (minutes % 60 == 0) {
                "${minutes / 60} hours"
            } else {
                "$minutes min"
            }
        }
}

class ServerProfileStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "teamspeak_saved_servers",
        Context.MODE_PRIVATE
    )

    @Synchronized
    fun list(): List<SavedServerProfile> {
        val raw = prefs.getString(KEY_PROFILES, null) ?: return emptyList()

        return runCatching {
            val array = JSONArray(raw)

            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)

                    val ids = linkedSetOf<Long>()
                    val channels = obj.optJSONArray("monitorChannels")

                    if (channels != null) {
                        for (j in 0 until channels.length()) {
                            channels.optLong(j)
                                .takeIf { it > 0L }
                                ?.let(ids::add)
                        }
                    }

                    val host = obj.optString("host").trim()
                    if (host.isBlank()) continue

                    add(
                        SavedServerProfile(
                            id = obj.optString("id").ifBlank {
                                UUID.randomUUID().toString()
                            },

                            name = obj.optString("nameOverride"),
                            serverName = obj.optString("serverName").ifBlank {
                                // Migration from the previous single-name model.
                                obj.optString("name")
                            },

                            host = host,
                            port = obj.optInt("port", 9987),
                            password = obj.optString("password"),
                            monitorEnabled = obj.optBoolean(
                                "monitorEnabled",
                                false
                            ),
                            monitoredChannelIds = ids
                        )
                    )
                }
            }
        }.getOrElse {
            emptyList()
        }
    }

    @Synchronized
    fun get(id: String): SavedServerProfile? =
        list().firstOrNull { it.id == id }

    @Synchronized
    fun upsert(profile: SavedServerProfile) {
        val items = list().toMutableList()
        val index = items.indexOfFirst { it.id == profile.id }

        if (index >= 0) {
            items[index] = profile
        } else {
            items += profile
        }

        save(items)
    }

    @Synchronized
    fun updateServerName(
        id: String,
        serverName: String
    ) {
        val normalized = serverName.trim()
        if (normalized.isBlank()) return

        val items = list().toMutableList()
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return

        if (items[index].serverName == normalized) return

        items[index] = items[index].copy(serverName = normalized)
        save(items)
    }

    @Synchronized
    fun reorder(orderedIds: List<String>) {
        val profiles = list()
        val priorities = orderedIds.withIndex().associate { it.value to it.index }
        save(profiles.sortedWith(compareBy { priorities[it.id] ?: Int.MAX_VALUE }))
    }

    @Synchronized
    fun delete(id: String) {
        save(list().filterNot { it.id == id })
    }

    private fun save(items: List<SavedServerProfile>) {
        val array = JSONArray()

        items.forEach { profile ->
            array.put(
                JSONObject().apply {
                    put("id", profile.id)
                    put("nameOverride", profile.name)
                    put("serverName", profile.serverName)
                    put("name", profile.serverName)
                    put("host", profile.host)
                    put("port", profile.port)
                    put("password", profile.password)
                    put("monitorEnabled", profile.monitorEnabled)

                    put(
                        "monitorChannels",
                        JSONArray().apply {
                            profile.monitoredChannelIds
                                .sorted()
                                .forEach { put(it) }
                        }
                    )
                }
            )
        }

        prefs.edit()
            .putString(KEY_PROFILES, array.toString())
            .commit()
    }

    companion object {
        private const val KEY_PROFILES = "profiles_json"
    }
}
