package com.garrietta.relay.rcs.proto

import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import java.io.ByteArrayOutputStream

/**
 * Minimal binary protobuf codec — clean-room implementation for Relay's RCS
 * transport. Covers exactly what the Messages-for-web protocol needs:
 * varints, length-delimited fields (bytes / strings / nested messages) and
 * repeated fields. Fixed32/fixed64 are skipped on decode for tolerance.
 *
 * Field numbers are protocol facts transcribed from the reference .proto
 * schemas (see each message mapper); all encode/decode logic below is original.
 */
sealed interface FieldVal {
    data class Varint(val v: Long) : FieldVal
    data class Bytes(val v: ByteArray) : FieldVal {
        override fun equals(other: Any?): Boolean = other is Bytes && v.contentEquals(other.v)
        override fun hashCode(): Int = v.contentHashCode()
    }

    data class Str(val v: String) : FieldVal
    data class Msg(val fields: Map<Int, FieldVal>) : FieldVal
}

object ProtoCodec {
    private const val WIRE_VARINT = 0
    private const val WIRE_FIXED64 = 1
    private const val WIRE_LEN = 2
    private const val WIRE_FIXED32 = 5

    /** Encodes fields; repeated values are expressed as a List. Field order is sorted. */
    fun encode(fields: Map<Int, FieldVal>): ByteArray {
        val out = ByteArrayOutputStream()
        val cos = CodedOutputStream.newInstance(out)
        for (n in fields.keys.sorted()) {
            val vs = fields[n]
            val list = if (vs is Rep) vs.items else listOf(vs!!)
            for (v in list) {
                when (v) {
                    is FieldVal.Varint -> {
                        cos.writeTag(n, WIRE_VARINT)
                        cos.writeInt64NoTag(v.v)
                    }
                    is FieldVal.Bytes -> {
                        cos.writeTag(n, WIRE_LEN)
                        cos.writeByteArrayNoTag(v.v)
                    }
                    is FieldVal.Str -> {
                        cos.writeTag(n, WIRE_LEN)
                        cos.writeStringNoTag(v.v)
                    }
                    is FieldVal.Msg -> {
                        cos.writeTag(n, WIRE_LEN)
                        val nested = encode(v.fields)
                        cos.writeUInt32NoTag(nested.size)
                        cos.writeRawBytes(nested)
                    }
                    is Rep -> error("nested Rep is not supported")
                }
            }
        }
        cos.flush()
        return out.toByteArray()
    }

    /** Decodes to field-number → occurrence list (repeated fields naturally repeat). */
    fun decode(bytes: ByteArray): Map<Int, List<FieldVal>> {
        val cis = CodedInputStream.newInstance(bytes)
        val out = mutableMapOf<Int, MutableList<FieldVal>>()
        while (!cis.isAtEnd) {
            val tag = cis.readTag()
            if (tag == 0) break
            val n = tag ushr 3
            when (tag and 0x7) {
                WIRE_VARINT -> out.getOrPut(n) { mutableListOf() }.add(FieldVal.Varint(cis.readInt64()))
                WIRE_LEN -> {
                    val data = cis.readByteArray()
                    out.getOrPut(n) { mutableListOf() }.add(FieldVal.Bytes(data))
                }
                WIRE_FIXED64 -> cis.readFixed64()
                WIRE_FIXED32 -> cis.readFixed32()
                else -> cis.skipField(tag)
            }
        }
        return out
    }

    /** Repeated-field wrapper for [encode]. */
    data class Rep(val items: List<FieldVal>)
}

/** Decode helpers: first occurrence of a field, typed. */
fun Map<Int, List<FieldVal>>.firstVarint(n: Int): Long? = (this[n]?.firstOrNull() as? FieldVal.Varint)?.v
fun Map<Int, List<FieldVal>>.firstBytes(n: Int): ByteArray? = (this[n]?.firstOrNull() as? FieldVal.Bytes)?.v
fun Map<Int, List<FieldVal>>.firstStr(n: Int): String? {
    val v = this[n]?.firstOrNull() ?: return null
    return when (v) {
        is FieldVal.Str -> v.v
        is FieldVal.Bytes -> v.v.toString(Charsets.UTF_8)
        else -> null
    }
}
fun Map<Int, List<FieldVal>>.firstMsg(n: Int): Map<Int, List<FieldVal>>? =
    (this[n]?.firstOrNull() as? FieldVal.Bytes)?.let { ProtoCodec.decode(it.v) }
