package com.garrietta.relay.data.transcription

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Decoded audio: 16-bit little-endian PCM, always mono. */
data class PcmAudio(
    val bytes: ByteArray,
    val sampleRate: Int,
)

/**
 * Decodes a compressed voice note (AMR-WB, AAC/M4A, Opus/Ogg, MP3…)
 * to raw mono PCM with the platform MediaCodec — no bundled decoder,
 * no new dependency. Two consumers (Garrett 2026-10-20, feature queue
 * 7/7):
 *  - the ON-DEVICE recognizer only accepts raw PCM through
 *    RecognizerIntent.EXTRA_AUDIO_SOURCE (it declares encoding / rate /
 *    channels itself), and
 *  - the GEMINI path wraps the same PCM as WAV when the note's own
 *    container isn't one Gemini accepts (AMR is not on its list).
 *
 * Blocking codec work — callers run it on a background dispatcher.
 */
object PcmAudioDecoder {

    private const val TIMEOUT_US = 10_000L

    /**
     * Decodes the first audio track of [audioFile] to mono PCM16.
     * Throws IllegalStateException with the plain reason on any failure
     * (no audio track, no decoder, codec error) — callers surface it
     * verbatim.
     */
    fun decodeToMonoPcm(audioFile: File): PcmAudio {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(audioFile.absolutePath)
            var trackIndex = -1
            var trackFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    trackIndex = i
                    trackFormat = f
                    break
                }
            }
            val format = trackFormat
                ?: throw IllegalStateException("this attachment has no audio track to transcribe")
            extractor.selectTrack(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)
                ?: throw IllegalStateException("this attachment's audio format is unknown")
            val codec = try {
                MediaCodec.createDecoderByType(mime)
            } catch (e: Exception) {
                throw IllegalStateException(
                    "no on-device decoder for $mime: ${e.message ?: e.javaClass.simpleName}",
                )
            }
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                return decodeLoop(codec, extractor, format)
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }

    private fun decodeLoop(
        codec: MediaCodec,
        extractor: MediaExtractor,
        inputFormat: MediaFormat,
    ): PcmAudio {
        var sampleRate = if (inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
            inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        } else 16_000
        var channels = if (inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
            inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        } else 1
        var floatPcm = false

        val out = ByteArrayOutputStream()
        val info = MediaCodec.BufferInfo()
        var inputEos = false
        var outputEos = false
        while (!outputEos) {
            if (!inputEos) {
                val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inIndex >= 0) {
                    val inBuffer = codec.getInputBuffer(inIndex)
                        ?: throw IllegalStateException("audio decoder returned no input buffer")
                    val size = extractor.readSampleData(inBuffer, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(
                            inIndex, 0, 0, 0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                        )
                        inputEos = true
                    } else {
                        codec.queueInputBuffer(
                            inIndex, 0, size, extractor.sampleTime, 0,
                        )
                        extractor.advance()
                    }
                }
            }
            val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            when {
                outIndex >= 0 -> {
                    if (info.size > 0) {
                        val outBuffer = codec.getOutputBuffer(outIndex)
                            ?: throw IllegalStateException("audio decoder returned no output buffer")
                        val chunk = ByteArray(info.size)
                        outBuffer.position(info.offset)
                        outBuffer.limit(info.offset + info.size)
                        outBuffer.get(chunk)
                        appendConverted(out, chunk, channels, floatPcm)
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputEos = true
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                }
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val newFormat = codec.outputFormat
                    if (newFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        sampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    }
                    if (newFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        channels = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    if (newFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        floatPcm = newFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) ==
                            android.media.AudioFormat.ENCODING_PCM_FLOAT
                    }
                }
            }
        }
        val pcm = out.toByteArray()
        if (pcm.isEmpty()) {
            throw IllegalStateException("the audio decoded to silence — nothing to transcribe")
        }
        return PcmAudio(pcm, sampleRate)
    }

    /**
     * Appends decoder output to [out], downmixing to mono: stereo
     * samples are averaged per frame. Non-16-bit output (float PCM from
     * some codecs) is converted sample-by-sample first.
     */
    private fun appendConverted(
        out: ByteArrayOutputStream,
        chunk: ByteArray,
        channels: Int,
        floatPcm: Boolean,
    ) {
        val pcm16 = if (floatPcm) {
            val floats = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val bytes = ByteArray(floats.remaining() * 2)
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            while (floats.hasRemaining()) {
                val v = (floats.get() * 32767f).toInt().coerceIn(-32768, 32767)
                bb.putShort(v.toShort())
            }
            bytes
        } else {
            chunk
        }
        if (channels <= 1) {
            out.write(pcm16)
            return
        }
        val frameBytes = 2 * channels
        val frames = pcm16.size / frameBytes
        val mono = ByteArray(frames * 2)
        val src = ByteBuffer.wrap(pcm16).order(ByteOrder.LITTLE_ENDIAN)
        val dst = ByteBuffer.wrap(mono).order(ByteOrder.LITTLE_ENDIAN)
        for (frame in 0 until frames) {
            var sum = 0
            for (ch in 0 until channels) {
                sum += src.getShort((frame * channels + ch) * 2).toInt()
            }
            dst.putShort(frame * 2, (sum / channels).toShort())
        }
        out.write(mono)
    }

    /**
     * Wraps raw PCM16 in a canonical 44-byte WAV header (the Gemini
     * path's fallback container when the note's own format — AMR — is
     * not one Gemini accepts). Pure byte work; unit-testable.
     */
    fun wavBytes(pcm: PcmAudio): ByteArray {
        val dataSize = pcm.bytes.size
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + dataSize)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16) // fmt chunk size
        header.putShort(1) // PCM
        header.putShort(1) // mono
        header.putInt(pcm.sampleRate)
        header.putInt(pcm.sampleRate * 2) // byte rate: rate * ch * bits/8
        header.putShort(2) // block align
        header.putShort(16) // bits per sample
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataSize)
        return header.array() + pcm.bytes
    }
}
