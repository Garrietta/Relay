package com.garrietta.relay.data.repo

import android.content.Context
import android.content.SharedPreferences
import com.garrietta.relay.data.model.Message
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One stored voice-note transcript (Garrett 2026-10-20, feature queue
 * 7/7): the text plus which provider produced it, so the bubble can
 * label it ("Transcript · Gemini" / "Transcript · On-device").
 */
data class TranscriptEntry(
    val text: String,
    val provider: TranscriptionProvider,
)

/**
 * The stable store key for a message's transcript. RCS messages key on
 * their server id (field 1 — survives every reload and cache round
 * trip). Anything else keys on thread + local id + timestamp: for RCS
 * rows the local id is itself the server id's hash, and for Telephony
 * rows the provider id is stable on-device, so both halves are stable.
 */
fun transcriptKeyFor(message: Message): String =
    message.serverId?.takeIf { it.isNotBlank() }?.let { "srv:$it" }
        ?: "local:${message.threadId}:${message.id}:${message.date}"

/**
 * Voice-note transcripts (Garrett 2026-10-20, feature queue 7/7).
 *
 * A DEDICATED store rather than a field on the chat-history cache: the
 * history cache is an LRU that evicts and gets version-bumped whenever
 * a rendering rule changes (v1 merge, v2 direction guard, v3 today) —
 * transcripts are user-earned content and must survive all of that,
 * keyed by message, independent of which conversation rendering served
 * the message.
 *
 * Persistence mirrors [ChatThemeStore]: app-private SharedPreferences +
 * a StateFlow, with two parallel hand-rolled flat JSON maps (texts and
 * provider names) — org.json is a stub in plain JVM unit tests. The map
 * is capped ([MAX_ENTRIES], oldest evicted) so the prefs file stays
 * bounded no matter how many voice notes accumulate.
 */
class TranscriptStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _transcripts = MutableStateFlow(loadTranscripts(prefs))
    /** messageKey (see [transcriptKeyFor]) → transcript. */
    val transcripts: StateFlow<Map<String, TranscriptEntry>> = _transcripts.asStateFlow()

    fun transcriptFor(messageKey: String): TranscriptEntry? =
        _transcripts.value[messageKey]

    /** Stores (or replaces) a transcript; persists immediately. */
    fun putTranscript(messageKey: String, text: String, provider: TranscriptionProvider) {
        if (text.isBlank()) return
        // LinkedHashMap order = insertion order: re-putting an existing
        // key keeps its original slot; brand-new keys append, and the
        // cap evicts from the front (oldest first).
        val next = LinkedHashMap(_transcripts.value)
        next[messageKey] = TranscriptEntry(text, provider)
        while (next.size > MAX_ENTRIES) {
            next.remove(next.keys.first())
        }
        _transcripts.value = next
        save(prefs, next)
    }

    /** Drops a transcript (the bubble returns to its untranscribed state). */
    fun removeTranscript(messageKey: String) {
        if (messageKey !in _transcripts.value) return
        _transcripts.value = _transcripts.value - messageKey
        save(prefs, _transcripts.value)
    }

    companion object {
        private const val PREFS = "relay_transcripts"
        private const val KEY_TEXTS = "texts_json"
        private const val KEY_PROVIDERS = "providers_json"

        /** Bound on stored transcripts; the oldest are evicted past this. */
        const val MAX_ENTRIES = 200

        // ---- Pure (unit-tested) serialization ----
        // Two flat messageKey → value maps, one JSON object each,
        // hand-rolled exactly like ChatThemeStore's codec.

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

        fun serializeStrings(map: Map<String, String>): String =
            map.entries.joinToString(",", "{", "}") {
                "\"${escape(it.key)}\":\"${escape(it.value)}\""
            }

        fun deserializeStrings(json: String): Map<String, String> {
            return try {
                val r = JsonReader(json)
                val m = r.flatObject() ?: return emptyMap()
                if (!r.atEnd()) return emptyMap()
                m.filterKeys { it.isNotBlank() }.filterValues { it.isNotBlank() }
            } catch (_: Exception) {
                emptyMap()
            }
        }

        private fun loadTranscripts(prefs: SharedPreferences): Map<String, TranscriptEntry> {
            val texts = deserializeStrings(prefs.getString(KEY_TEXTS, "{}") ?: "{}")
            val providers = deserializeStrings(prefs.getString(KEY_PROVIDERS, "{}") ?: "{}")
            val out = LinkedHashMap<String, TranscriptEntry>()
            for ((key, text) in texts) {
                out[key] = TranscriptEntry(
                    text = text,
                    provider = transcriptionProviderFromName(
                        providers[key], TranscriptionProvider.GEMINI,
                    ),
                )
            }
            return out
        }

        private fun save(prefs: SharedPreferences, transcripts: Map<String, TranscriptEntry>) {
            val texts = transcripts.mapValues { it.value.text }
            val providers = transcripts.mapValues { it.value.provider.name }
            prefs.edit()
                .putString(KEY_TEXTS, serializeStrings(texts))
                .putString(KEY_PROVIDERS, serializeStrings(providers))
                .apply()
        }
    }
}
