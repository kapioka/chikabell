package com.chikabell.app

import com.chikabell.app.share.PngTextMetadataExtractor
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PngTextMetadataExtractorTest {
    @Test
    fun extractsBoundedTextChunkWithoutDecodingPixels() {
        val bytes = pngWithChunk("tEXt", "Comment\u000035.123, 139.456".toByteArray(Charsets.ISO_8859_1))

        val texts = PngTextMetadataExtractor.extract(bytes).toList()

        assertEquals(listOf("35.123, 139.456"), texts)
    }

    @Test
    fun ignoresNonPngAndCompressedInternationalText() {
        assertTrue(PngTextMetadataExtractor.extract("not png".toByteArray()).none())
        val compressed = "Comment\u0000\u0001\u0000\u0000\u0000ignored".toByteArray(Charsets.UTF_8)
        assertTrue(PngTextMetadataExtractor.extract(pngWithChunk("iTXt", compressed)).none())
    }

    private fun pngWithChunk(type: String, data: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        output.write(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))
        output.write(
            byteArrayOf(
                (data.size ushr 24).toByte(),
                (data.size ushr 16).toByte(),
                (data.size ushr 8).toByte(),
                data.size.toByte(),
            ),
        )
        output.write(type.toByteArray(Charsets.US_ASCII))
        output.write(data)
        output.write(ByteArray(4))
        return output.toByteArray()
    }
}
