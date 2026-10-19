package com.garrietta.relay.data.transport

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Magic-byte validation: corrupt-looking bytes must never pass as images. */
class MediaValidationTest {

    private fun bytes(vararg b: Int): ByteArray = b.map { it.toByte() }.toByteArray()

    @Test fun `jpeg passes`() {
        assertTrue(isValidImageBytes(bytes(0xFF, 0xD8, 0xFF, 0xE0, 0, 0, 0, 0, 0, 0, 0, 0)))
    }

    @Test fun `png passes`() {
        assertTrue(isValidImageBytes(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0, 0, 0, 0, 0, 0)))
    }

    @Test fun `gif passes`() {
        assertTrue(isValidImageBytes(bytes(0x47, 0x49, 0x46, 0x38, 0, 0, 0, 0, 0, 0, 0, 0)))
    }

    @Test fun `webp passes`() {
        assertTrue(
            isValidImageBytes(
                bytes(0x52, 0x49, 0x46, 0x46, 0, 0, 0, 0, 0x57, 0x45, 0x42, 0x50),
            ),
        )
    }

    @Test fun `heic ftyp passes`() {
        assertTrue(
            isValidImageBytes(
                bytes(0, 0, 0, 0x18, 0x66, 0x74, 0x79, 0x70, 0x6D, 0x69, 0x66, 0x31),
            ),
        )
    }

    @Test fun `random garbage fails`() {
        assertFalse(isValidImageBytes(bytes(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)))
    }

    @Test fun `too short fails`() {
        assertFalse(isValidImageBytes(bytes(0xFF, 0xD8, 0xFF)))
    }

    @Test fun `empty fails`() {
        assertFalse(isValidImageBytes(byteArrayOf()))
    }

    @Test fun `html error page fails`() {
        val html = "<html><body>error</body></html>!".toByteArray()
        assertFalse(isValidImageBytes(html))
    }
}
