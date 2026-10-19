package com.garrietta.relay.data.repo

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * User-defined inbox categories ("All" is implicit and always first).
 *
 * Garrett's 2026-10-19 directive: a music-app-style segmented pill bar at
 * the TOP of the inbox (where the search bar was), horizontally scrollable,
 * no cap on count. "All" shows every conversation; each custom category
 * shows only the chats assigned to it. A chat belongs to at most ONE
 * category (or none → visible only under All). Categories are created,
 * renamed, deleted and REORDERED in Settings; chat assignment happens
 * per-category in Settings.
 *
 * Persistence: SharedPreferences + JSON (no new dependency). The pure
 * serialize/deserialize helpers are unit-tested (see CategoryStoreTest);
 * the Android-backed class itself is thin.
 *
 * Conversation keys: [Conversation.categoryKey] =
 * rcsConversationId ?: "tel:$threadId" (stable across restarts for both
 * RCS and Telephony rows).
 */
data class ChatCategory(
    val id: String,
    val name: String,
)

class CategoryStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _categories = MutableStateFlow(loadCategories(prefs))
    /** Ordered custom categories. Empty = no pill bar shown at all. */
    val categories: StateFlow<List<ChatCategory>> = _categories.asStateFlow()

    private val _assignments = MutableStateFlow(loadAssignments(prefs))
    /** conversationKey → categoryId. */
    val assignments: StateFlow<Map<String, String>> = _assignments.asStateFlow()

    private val _selectedId = MutableStateFlow(prefs.getString(KEY_SELECTED, null))
    /** Null = "All". */
    val selectedCategoryId: StateFlow<String?> = _selectedId.asStateFlow()

    /** Adds a category; returns its id. Blank names are rejected with null. */
    fun addCategory(name: String): String? {
        val clean = name.trim()
        if (clean.isEmpty()) return null
        val cat = ChatCategory(UUID.randomUUID().toString(), clean)
        _categories.value = _categories.value + cat
        saveCategories(prefs, _categories.value)
        return cat.id
    }

    fun renameCategory(id: String, name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        _categories.value = _categories.value.map {
            if (it.id == id) it.copy(name = clean) else it
        }
        saveCategories(prefs, _categories.value)
    }

    fun deleteCategory(id: String) {
        _categories.value = _categories.value.filterNot { it.id == id }
        saveCategories(prefs, _categories.value)
        // Assignments to a deleted category evaporate (chats return to All-only).
        _assignments.value = _assignments.value.filterValues { it != id }
        saveAssignments(prefs, _assignments.value)
        if (_selectedId.value == id) selectCategory(null)
    }

    /** Moves a category by [delta] positions (-1 = up, +1 = down). */
    fun moveCategory(id: String, delta: Int) {
        val list = _categories.value.toMutableList()
        val from = list.indexOfFirst { it.id == id }
        if (from < 0) return
        val to = (from + delta).coerceIn(0, list.size - 1)
        if (to == from) return
        val cat = list.removeAt(from)
        list.add(to, cat)
        _categories.value = list
        saveCategories(prefs, list)
    }

    /** Assigns a chat to a category; null unassigns (chat shows under All only). */
    fun assignChat(conversationKey: String, categoryId: String?) {
        val valid = categoryId?.takeIf { id -> _categories.value.any { it.id == id } }
        _assignments.value = if (valid == null) {
            _assignments.value - conversationKey
        } else {
            _assignments.value + (conversationKey to valid)
        }
        saveAssignments(prefs, _assignments.value)
    }

    fun categoryFor(conversationKey: String): String? =
        _assignments.value[conversationKey]
            ?.takeIf { id -> _categories.value.any { it.id == id } }

    /** Null = "All". Unknown ids are ignored (stay on current selection). */
    fun selectCategory(id: String?) {
        if (id != null && _categories.value.none { it.id == id }) return
        _selectedId.value = id
        prefs.edit().putString(KEY_SELECTED, id).apply()
    }

    companion object {
        private const val PREFS = "relay_categories"
        private const val KEY_CATEGORIES = "categories_json"
        private const val KEY_ASSIGNMENTS = "assignments_json"
        private const val KEY_SELECTED = "selected_id"

        /** Stable key for a conversation across restarts. */
        fun conversationKey(rcsConversationId: String?, threadId: Long): String =
            rcsConversationId ?: "tel:$threadId"

        // ---- Pure (unit-tested) serialization ----
        // Hand-rolled JSON codec: org.json is an Android-framework stub in
        // plain JVM unit tests and throws "Stub!" there. The shapes are
        // simple (flat string objects / arrays of them), so a tiny
        // recursive-descent reader is enough and keeps the tests honest.

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

        fun serializeCategories(cats: List<ChatCategory>): String =
            cats.joinToString(",", "[", "]") {
                "{\"id\":\"${escape(it.id)}\",\"name\":\"${escape(it.name)}\"}"
            }

        fun deserializeCategories(json: String): List<ChatCategory> {
            return try {
                val r = JsonReader(json)
                if (!r.expect('[')) return emptyList()
                val out = mutableListOf<ChatCategory>()
                var first = true
                while (true) {
                    r.ws()
                    if (r.expect(']')) break
                    if (!first && !r.expect(',')) return emptyList()
                    val obj = r.flatObject() ?: return emptyList()
                    first = false
                    val id = obj["id"].orEmpty()
                    val name = obj["name"].orEmpty()
                    if (id.isNotBlank() && name.isNotBlank()) {
                        out.add(ChatCategory(id, name))
                    }
                }
                if (!r.atEnd()) return emptyList()
                out
            } catch (_: Exception) {
                emptyList()
            }
        }

        fun serializeAssignments(map: Map<String, String>): String =
            map.entries.joinToString(",", "{", "}") {
                "\"${escape(it.key)}\":\"${escape(it.value)}\""
            }

        fun deserializeAssignments(json: String): Map<String, String> {
            return try {
                val r = JsonReader(json)
                val m = r.flatObject() ?: return emptyMap()
                if (!r.atEnd()) return emptyMap()
                m
            } catch (_: Exception) {
                emptyMap()
            }
        }

        private fun loadCategories(prefs: SharedPreferences): List<ChatCategory> =
            deserializeCategories(prefs.getString(KEY_CATEGORIES, "[]") ?: "[]")

        private fun saveCategories(prefs: SharedPreferences, cats: List<ChatCategory>) {
            prefs.edit().putString(KEY_CATEGORIES, serializeCategories(cats)).apply()
        }

        private fun loadAssignments(prefs: SharedPreferences): Map<String, String> =
            deserializeAssignments(prefs.getString(KEY_ASSIGNMENTS, "{}") ?: "{}")

        private fun saveAssignments(prefs: SharedPreferences, map: Map<String, String>) {
            prefs.edit().putString(KEY_ASSIGNMENTS, serializeAssignments(map)).apply()
        }
    }
}
