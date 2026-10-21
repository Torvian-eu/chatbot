package eu.torvian.chatbot.worker.builtin.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies bounded retention, absolute offsets, dropped-content reporting and surrogate safety.
 */
class OutputRingBufferTest {

    @Test
    fun `append below capacity preserves text and offsets`() {
        val buffer = OutputRingBuffer(64)
        buffer.append("hello")

        assertEquals(5L, buffer.totalProduced)
        assertEquals(0L, buffer.oldestRetained)
        assertEquals(5, buffer.retainedBytes)

        val chunk = buffer.read(0, 100, 100)
        assertEquals("hello", chunk.text)
        assertEquals(5L, chunk.nextOffset)
        assertEquals(0L, chunk.droppedChars)
        assertFalse(chunk.hasMore)
    }

    @Test
    fun `append above capacity evicts oldest characters`() {
        val buffer = OutputRingBuffer(4)
        buffer.append("abcdef")

        assertEquals(6L, buffer.totalProduced)
        assertEquals(2L, buffer.oldestRetained)
        assertEquals(2L, buffer.oldestRetainedBytes)
        assertTrue(buffer.retainedBytes <= 4)

        val chunk = buffer.read(2, 100, 100)
        assertEquals("cdef", chunk.text)
        assertEquals(0L, chunk.droppedChars)
        assertFalse(chunk.hasMore)
    }

    @Test
    fun `reading from an evicted offset reports dropped characters and starts at oldest retained`() {
        val buffer = OutputRingBuffer(4)
        buffer.append("abcdef")

        val chunk = buffer.read(0, 100, 100)
        assertEquals("cdef", chunk.text)
        assertEquals(2L, chunk.droppedChars)
        assertEquals(6L, chunk.nextOffset)
        assertFalse(chunk.hasMore)
    }

    @Test
    fun `reading at total produced returns empty`() {
        val buffer = OutputRingBuffer(16)
        buffer.append("abc")

        val chunk = buffer.read(buffer.totalProduced, 100, 100)
        assertEquals("", chunk.text)
        assertEquals(3L, chunk.nextOffset)
        assertFalse(chunk.hasMore)
    }

    @Test
    fun `maxChars bounds the returned prefix`() {
        val buffer = OutputRingBuffer(16)
        buffer.append("abcdef")

        val chunk = buffer.read(0, 2, 100)
        assertEquals("ab", chunk.text)
        assertEquals(2L, chunk.nextOffset)
        assertTrue(chunk.hasMore)
    }

    @Test
    fun `maxBytes bounds the returned prefix`() {
        val buffer = OutputRingBuffer(16)
        buffer.append("éa")

        val chunk = buffer.read(0, 100, 2)
        assertEquals("é", chunk.text)
        assertEquals(1L, chunk.nextOffset)
        assertTrue(chunk.hasMore)
    }

    @Test
    fun `byte budget does not split a surrogate pair`() {
        val buffer = OutputRingBuffer(16)
        buffer.append("😀a")

        val chunk = buffer.read(0, 100, 3)
        assertEquals("", chunk.text)
        assertEquals(0L, chunk.nextOffset)
        assertTrue(chunk.hasMore)
    }

    @Test
    fun `character budget does not split a surrogate pair`() {
        val buffer = OutputRingBuffer(16)
        buffer.append("😀")

        val chunk = buffer.read(0, 1, 100)
        assertEquals("", chunk.text)
        assertEquals(0L, chunk.nextOffset)
        assertTrue(chunk.hasMore)
    }

    @Test
    fun `eviction keeps a surrogate pair together`() {
        val buffer = OutputRingBuffer(6)
        buffer.append("😀ab")

        // The pair is 4 bytes and "ab" is 2, so it still fits; adding one more byte evicts the pair.
        buffer.append("c")

        assertEquals("abc", buffer.read(buffer.oldestRetained, 100, 100).text)
        assertEquals(2L, buffer.oldestRetained)
        assertEquals(4L, buffer.oldestRetainedBytes)
    }

    @Test
    fun `empty append is a no-op`() {
        val buffer = OutputRingBuffer(8)
        buffer.append("")
        buffer.append("ab")

        assertEquals(2L, buffer.totalProduced)
        assertEquals(0L, buffer.oldestRetained)
    }
}
