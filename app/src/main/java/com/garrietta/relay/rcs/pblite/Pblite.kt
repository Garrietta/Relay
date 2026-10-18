package com.garrietta.relay.rcs.pblite

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import java.util.Base64

/**
 * pblite codec — clean-room implementation for Relay's RCS transport.
 *
 * pblite serializes a protobuf message as a JSON array where the element at
 * index (fieldNumber - 1) holds that field's value. Absent fields are encoded
 * as `null`; trailing nulls are trimmed. Nested messages are nested arrays,
 * repeated fields are arrays of values, bytes are base64 strings, enums are
 * numbers.
 *
 * This core is schema-agnostic: it maps between field-number maps and JSON.
 * Per-message typed readers/writers live next to the message models and decide
 * how each field is interpreted (e.g. whether a nested array is a sub-message
 * or a repeated field).
 */
sealed interface PValue {
    data class Str(val v: String) : PValue
    data class Num(val v: Long) : PValue
    data class Bool(val v: Boolean) : PValue
    data class Bytes(val v: ByteArray) : PValue {
        override fun equals(other: Any?): Boolean = other is Bytes && v.contentEquals(other.v)
        override fun hashCode(): Int = v.contentHashCode()
        override fun toString(): String = "Bytes(${v.size})"
    }

    /** Neutral array node: a nested JSON array. Interpret via [asMsg]/[asList]. */
    data class Arr(val elements: List<PValue>) : PValue

    /** Explicit null (only significant inside repeated fields; top-level nulls are dropped). */
    data object PNull : PValue
}

/** Interprets an array node as a nested message: 1-based field numbers, nulls dropped. */
fun PValue.Arr.asMsg(): Map<Int, PValue> =
    elements.mapIndexedNotNull { i, e -> if (e is PValue.PNull) null else (i + 1) to e }.toMap()

/** Interprets an array node as a repeated field. */
fun PValue.Arr.asList(): List<PValue> = elements

object Pblite {
    private val json = Json { encodeDefaults = false }
    private val b64enc: Base64.Encoder = Base64.getEncoder()
    private val b64dec: Base64.Decoder = Base64.getDecoder()

    fun encodeBase64(bytes: ByteArray): String = b64enc.encodeToString(bytes)
    fun decodeBase64(s: String): ByteArray = b64dec.decode(s)

    /** Encodes a field-number → value map to a pblite JSON array string. */
    fun encode(fields: Map<Int, PValue>): String {
        if (fields.isEmpty()) return "[]"
        val size = fields.keys.max()
        val arr = buildJsonArray {
            for (i in 1..size) {
                add(toJson(fields[i] ?: PValue.PNull))
            }
        }
        // Trim trailing nulls (pblite collapses them).
        val trimmed = arr.dropLastWhile { it is JsonNull }
        return JsonArray(trimmed).toString()
    }

    /** Decodes a top-level pblite JSON array to its element list (nulls kept as PNull). */
    fun decodeAsList(text: String): List<PValue> {
        val arr = json.parseToJsonElement(text) as? JsonArray
            ?: throw IllegalArgumentException("pblite: top-level value is not a JSON array")
        return arr.map { fromJson(it) ?: PValue.PNull }
    }

    /** Decodes a pblite JSON array string to a field-number → value map (nulls dropped). */
    fun decode(text: String): Map<Int, PValue> {
        val arr = json.parseToJsonElement(text) as? JsonArray
            ?: throw IllegalArgumentException("pblite: top-level value is not a JSON array")
        return arr.mapIndexedNotNull { i, e ->
            val v = fromJson(e) ?: return@mapIndexedNotNull null
            (i + 1) to v
        }.toMap()
    }

    private fun toJson(v: PValue): kotlinx.serialization.json.JsonElement = when (v) {
        is PValue.Str -> JsonPrimitive(v.v)
        is PValue.Num -> JsonPrimitive(v.v)
        is PValue.Bool -> JsonPrimitive(v.v)
        is PValue.Bytes -> JsonPrimitive(b64enc.encodeToString(v.v))
        is PValue.Arr -> buildJsonArray { v.elements.forEach { add(toJson(it)) } }
        is PValue.PNull -> JsonNull
    }

    private fun fromJson(e: kotlinx.serialization.json.JsonElement): PValue? = when (e) {
        is JsonNull -> null
        is JsonPrimitive -> when {
            e.isString -> PValue.Str(e.content)
            e.booleanOrNull != null -> PValue.Bool(e.boolean)
            else -> PValue.Num(e.long)
        }
        is JsonArray -> PValue.Arr(e.map { fromJson(it) ?: PValue.PNull })
        else -> throw IllegalArgumentException("pblite: unsupported JSON element $e")
    }
}

/** Typed field readers for message mappers. */
fun Map<Int, PValue>.str(n: Int): String? = (this[n] as? PValue.Str)?.v
fun Map<Int, PValue>.num(n: Int): Long? = (this[n] as? PValue.Num)?.v
fun Map<Int, PValue>.bool(n: Int): Boolean? = (this[n] as? PValue.Bool)?.v
fun Map<Int, PValue>.bytes(n: Int): ByteArray? = when (val v = this[n]) {
    is PValue.Bytes -> v.v
    is PValue.Str -> runCatching { Pblite.decodeBase64(v.v) }.getOrNull()
    else -> null
}
fun Map<Int, PValue>.msg(n: Int): Map<Int, PValue>? = (this[n] as? PValue.Arr)?.asMsg()
fun Map<Int, PValue>.rep(n: Int): List<PValue> = (this[n] as? PValue.Arr)?.asList().orEmpty()
