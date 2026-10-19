package com.garrietta.relay.data.repo

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
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

        fun serializeCategories(cats: List<ChatCategory>): String =
            JSONArray().apply {
                cats.forEach { put(JSONObject().put("id", it.id).put("name", it.name)) }
            }.toString()

        fun deserializeCategories(json: String): List<ChatCategory> =
            runCatching {
                val arr = JSONArray(json)
                List(arr.length()) { i ->
                    val o = arr.getJSONObject(i)
                    ChatCategory(o.getString("id"), o.getString("name"))
                }.filter { it.id.isNotBlank() && it.name.isNotBlank() }
            }.getOrDefault(emptyList())

        fun serializeAssignments(map: Map<String, String>): String =
            JSONObject(map as Map<*, *>).toString()

        fun deserializeAssignments(json: String): Map<String, String> =
            runCatching {
                val o = JSONObject(json)
                o.keys().asSequence().associateWith { o.getString(it) }
                    .filterValues { it.isNotBlank() }
            }.getOrDefault(emptyMap())

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
