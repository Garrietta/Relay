package com.garrietta.relay.rcs.proto

import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import java.io.ByteArrayOutputStream

/**
 * Minimal binary protobuf codec — clean-room implementation for Relay's RCS
 * transport. Covers exactly what the Messages-for-web protocol needs:
 * varints, length-delimited fields (bytes / strings / nested messages),
 * repeated fields, and fixed32/fixed64 (the wire forms of float/double —
 * location coordinates travel as protobuf doubles).
 *
 * Field numbers are protocol facts transcribed from the reference .proto
 * schemas (see each message mapper); all encode/decode logic below is original.
 */
sealed interface FieldVal {
    data class Varint(val v: Long) : FieldVal

    /** Raw bits of a fixed64 field (a protobuf double via [Double.fromBits]). */
    data class Fixed64(val v: Long) : FieldVal

    /** Raw bits of a fixed32 field (a protobuf float via [Float.fromBits]). */
    data class Fixed32(val v: Int) : FieldVal
    data class Bytes(val v: ByteArray) : FieldVal {
        override fun equals(other: Any?): Boolean = other is Bytes && v.contentEquals(other.v)
        override fun hashCode(): Int = v.contentHashCode()
    }

    data class Str(val v: String) : FieldVal
    data class Msg(val fields: Map<Int, FieldVal>) : FieldVal

    /** Repeated-field wrapper for [encode] (also a FieldVal so maps stay uniform). */
    data class Rep(val items: List<FieldVal>) : FieldVal
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
            val list = if (vs is FieldVal.Rep) vs.items else listOf(vs!!)
            for (v in list) {
                when (v) {
                    is FieldVal.Varint -> {
                        cos.writeTag(n, WIRE_VARINT)
                        cos.writeInt64NoTag(v.v)
                    }
                    is FieldVal.Fixed64 -> {
                        cos.writeTag(n, WIRE_FIXED64)
                        cos.writeFixed64NoTag(v.v)
                    }
                    is FieldVal.Fixed32 -> {
                        cos.writeTag(n, WIRE_FIXED32)
                        cos.writeFixed32NoTag(v.v)
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
                    is FieldVal.Rep -> error("nested Rep is not supported")
                }
            }
        }
        cos.flush()
        return out.toByteArray()
    }

    /** Decodes to field-number → occurrence list (repeated fields naturally repeat). */
    fun decode(bytes: ByteArray): Map<Int, List<FieldVal>> {        val cis = CodedInputStream.newInstance(bytes)
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
                WIRE_FIXED64 -> out.getOrPut(n) { mutableListOf() }
                    .add(FieldVal.Fixed64(cis.readFixed64()))
                WIRE_FIXED32 -> out.getOrPut(n) { mutableListOf() }
                    .add(FieldVal.Fixed32(cis.readFixed32()))
                else -> cis.skipField(tag)
            }
        }
        return out
    }
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

/** Double (fixed64) at field [n]. */
fun Map<Int, List<FieldVal>>.firstDouble(n: Int): Double? =
    (this[n]?.firstOrNull() as? FieldVal.Fixed64)?.v?.let(Double::fromBits)

/** Float (fixed32) at field [n]. */
fun Map<Int, List<FieldVal>>.firstFloat(n: Int): Float? =
    (this[n]?.firstOrNull() as? FieldVal.Fixed32)?.v?.let(Float::fromBits)
