package eu.torvian.chatbot.worker.main

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkerCliParserTest {

    @Test
    fun `parses config option`() {
        val options = WorkerCliParser.parse(arrayOf("--config=./custom-config.json"))

        assertEquals("./custom-config.json", options.configPathOverride)
        assertFalse(options.runOnce)
    }

    @Test
    fun `parses once flag`() {
        val options = WorkerCliParser.parse(arrayOf("--once"))

        assertEquals(null, options.configPathOverride)
        assertTrue(options.runOnce)
    }

    @Test
    fun `parses config and once together`() {
        val options = WorkerCliParser.parse(arrayOf("--config=./worker-config.json", "--once"))

        assertEquals("./worker-config.json", options.configPathOverride)
        assertTrue(options.runOnce)
    }

    @Test
    fun `ignores unknown flags without failing`() {
        val options = WorkerCliParser.parse(arrayOf("--unknown=42", "--no-op"))

        assertEquals(null, options.configPathOverride)
        assertFalse(options.runOnce)
    }
}

