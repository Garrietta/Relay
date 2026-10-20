package com.garrietta.relay.data.repo

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Per-conversation chat THEME (Garrett 2026-10-20, feature queue 3/7 —
 * from the Google Messages most-requested list: per-chat wallpaper +
 * bubble colors, their "Chat themes" rollout).
 *
 * In Relay a theme is LOCAL PRESENTATION ONLY: a background gradient +
 * a bubble colorway for one conversation, rendered entirely on-device.
 * Nothing here touches the wire, the paired inbox, or Google Messages —
 * which is why the details page's "chat theme" row (skipped in #119
 * "for want of a backend") needed no backend at all.
 *
 * Persistence mirrors [CategoryStore] / [MuteStore]: app-private
 * SharedPreferences + a StateFlow the UI collects. Keys are conversation
 * keys in the [CategoryStore.conversationKey] scheme
 * (rcsConversationId ?: "tel:$threadId") — the same key the mute uses,
 * so one conversation has exactly one identity across details, chat,
 * and notifications.
 *
 * The option CATALOG (ids → names/seeds) lives in the UI theme layer
 * (ui/theme/ChatThemeOptions.kt); this store persists only the chosen
 * option ids. An id that no longer resolves renders as Default, so a
 * retired option can never break a chat.
 */
data class ChatTheme(
    /** Background option id; null = Default (today's flat surface). */
    val backgroundId: String? = null,
    /** Bubble colorway option id; null = Default (scheme colors). */
    val colorwayId: String? = null,
) {
    val isDefault: Boolean get() = backgroundId == null && colorwayId == null
}

class ChatThemeStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _themes = MutableStateFlow(loadThemes(prefs))
    /** conversationKey → theme. Default conversations are ABSENT. */
    val themes: StateFlow<Map<String, ChatTheme>> = _themes.asStateFlow()

    fun themeFor(conversationKey: String): ChatTheme =
        _themes.value[conversationKey] ?: ChatTheme()

    /** Sets (or clears, with null) this conversation's background. */
    fun setBackground(conversationKey: String, backgroundId: String?) =
        update(conversationKey) { it.copy(backgroundId = backgroundId) }

    /** Sets (or clears, with null) this conversation's bubble colorway. */
    fun setColorway(conversationKey: String, colorwayId: String?) =
        update(conversationKey) { it.copy(colorwayId = colorwayId) }

    /** Clears BOTH choices — the conversation returns to today's look. */
    fun reset(conversationKey: String) {
        if (conversationKey !in _themes.value) return
        _themes.value = _themes.value - conversationKey
        save(prefs, _themes.value)
    }

    private fun update(conversationKey: String, change: (ChatTheme) -> ChatTheme) {
        val next = change(themeFor(conversationKey))
        // A theme that became fully default is dropped from the map so
        // the persisted form stays minimal and `themes` means "chats
        // with a real choice".
        _themes.value = if (next.isDefault) {
            _themes.value - conversationKey
        } else {
            _themes.value + (conversationKey to next)
        }
        save(prefs, _themes.value)
    }

    companion object {
        private const val PREFS = "relay_chat_themes"
        private const val KEY_BACKGROUNDS = "backgrounds_json"
        private const val KEY_COLORWAYS = "colorways_json"

        // ---- Pure (unit-tested) serialization ----
        // Two flat conversationKey → optionId maps, one JSON object
        // each, hand-rolled exactly like CategoryStore's assignments
        // codec (org.json is a stub in plain JVM unit tests).

        private fun escape(s: String): String = buildString {
            for (c in s) when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }

        private class JsonReader(private val s: String) {
            var i = 0
            fun ws() {
                while (i < s.length && s[i].isWhitespace()) i++
            }

            fun atEnd(): Boolean {
                ws()
                return i >= s.length
            }

            fun expect(c: Char): Boolean {
                ws()
                if (i < s.length && s[i] == c) {
                    i++
                    return true
                }
                return false
            }

            fun str(): String? {
                ws()
                if (i >= s.length || s[i] != '"') return null
                i++
                val sb = StringBuilder()
                while (i < s.length) {
                    val c = s[i++]
                    when {
                        c == '"' -> return sb.toString()
                        c == '\\' && i < s.length -> when (val e = s[i++]) {
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                val hex = s.substring(i, minOf(i + 4, s.length))
                                if (hex.length == 4) {
                                    sb.append(hex.toIntOrNull(16)?.toChar() ?: '?')
                                    i += 4
                                }
                            }
                            else -> sb.append(e)
                        }
                        else -> sb.append(c)
                    }
                }
                return null // unterminated string
            }

            /** Reads a flat {"k":"v",...} object; null when malformed. */
            fun flatObject(): Map<String, String>? {
                if (!expect('{')) return null
                val m = mutableMapOf<String, String>()
                if (expect('}')) return m
                while (true) {
                    val k = str() ?: return null
                    if (!expect(':')) return null
                    val v = str() ?: return null
                    m[k] = v
                    if (expect('}')) return m
                    if (!expect(',')) return null
                }
            }
        }

        fun serializeIds(map: Map<String, String>): String =
            map.entries.joinToString(",", "{", "}") {
                "\"${escape(it.key)}\":\"${escape(it.value)}\""
            }

        fun deserializeIds(json: String): Map<String, String> {
            return try {
                val r = JsonReader(json)
                val m = r.flatObject() ?: return emptyMap()
                if (!r.atEnd()) return emptyMap()
                // Blank keys/ids are meaningless — drop them.
                m.filterKeys { it.isNotBlank() }.filterValues { it.isNotBlank() }
            } catch (_: Exception) {
                emptyMap()
            }
        }

        private fun loadThemes(prefs: SharedPreferences): Map<String, ChatTheme> {
            val backgrounds = deserializeIds(
                prefs.getString(KEY_BACKGROUNDS, "{}") ?: "{}",
            )
            val colorways = deserializeIds(
                prefs.getString(KEY_COLORWAYS, "{}") ?: "{}",
            )
            val out = mutableMapOf<String, ChatTheme>()
            for ((key, id) in backgrounds) {
                out[key] = ChatTheme(backgroundId = id)
            }
            for ((key, id) in colorways) {
                out[key] = (out[key] ?: ChatTheme()).copy(colorwayId = id)
            }
            return out
        }

        private fun save(prefs: SharedPreferences, themes: Map<String, ChatTheme>) {
            val backgrounds = themes.mapNotNull { (key, theme) ->
                theme.backgroundId?.let { key to it }
            }.toMap()
            val colorways = themes.mapNotNull { (key, theme) ->
                theme.colorwayId?.let { key to it }
            }.toMap()
            prefs.edit()
                .putString(KEY_BACKGROUNDS, serializeIds(backgrounds))
                .putString(KEY_COLORWAYS, serializeIds(colorways))
                .apply()
        }
    }
}
