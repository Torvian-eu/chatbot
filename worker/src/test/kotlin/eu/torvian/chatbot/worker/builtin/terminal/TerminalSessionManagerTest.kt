package eu.torvian.chatbot.worker.builtin.terminal

import eu.torvian.chatbot.worker.builtin.command.ChildEnvironmentProvider
import eu.torvian.chatbot.worker.builtin.command.CommandIsolationResolution
import eu.torvian.chatbot.worker.config.TerminalSessionsConfig
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Lifecycle, registry, limit and reaper tests for [TerminalSessionManager], using a fake process and
 * an injectable id generator so no PTY is required.
 */
class TerminalSessionManagerTest {

    private companion object {
        /** Mirrors the manager's bounded id-allocation attempt count. */
        const val MAX_ID_ATTEMPTS = 16
    }

    private val workspace: Path = createTempDirectory("terminal-manager-test")
    private val managers = mutableListOf<TerminalSessionManager>()

    /** Closes every manager created by a test so no reaper scope or reader thread leaks. */
    @AfterTest
    fun closeManagers() {
        managers.forEach { runCatching { it.close() } }
        workspace.toFile().deleteRecursively()
    }

    /**
     * @param factory Process factory used by the manager.
     * @param config Terminal-session configuration.
     * @param idGenerator Session-id seam.
     * @param clock Injectable monotonic clock.
     * @param shutdownGraceMillis Shared shutdown budget for bounded-close tests.
     * @param logger Logger seam used to pause `start` after the reader has drained a prompt.
     * @return A manager registered for cleanup.
     */
    private fun manager(
        factory: TerminalProcessFactory,
        config: TerminalSessionsConfig = testTerminalConfig(),
        idGenerator: () -> String = { "aB3xY9" },
        clock: () -> Long = System::nanoTime,
        shutdownGraceMillis: Long = 5_000L,
        logger: Logger = LogManager.getLogger(TerminalSessionManagerTest::class.java),
    ): TerminalSessionManager {
        val manager = TerminalSessionManager(
            config = config,
            resolution = CommandIsolationResolution.none(),
            childEnvironment = object : ChildEnvironmentProvider {
                override fun build(): Map<String, String> =
                    mapOf("PATH" to "/usr/bin", "HOME" to "/home/agent")
            },
            workspace = workspace,
            processFactory = factory,
            clock = clock,
            idGenerator = idGenerator,
            shutdownGraceMillis = shutdownGraceMillis,
            logger = logger,
        )
        managers += manager
        return manager
    }

    @Test
    fun `start uses the launcher-wrapped argv and provider environment`(): Unit = runBlocking {
        val process = FakeTerminalProcess()
        val factory = RecordingProcessFactory { process }
        val manager = manager(factory)

        val snapshot = manager.start("bash", listOf("-i"), null, 40, 20, 200).getOrNull()

        assertNotNull(snapshot)
        val spec = assertNotNull(factory.lastSpec)
        assertEquals(listOf("bash", "-i"), spec.argv)
        assertEquals("/usr/bin", spec.environment["PATH"])
        assertEquals("/home/agent", spec.environment["HOME"])
        assertEquals("xterm-256color", spec.environment["TERM"])
        assertEquals(workspace, spec.workingDirectory)
        assertEquals(80, spec.columns)
        assertEquals(24, spec.rows)
    }

    @Test
    fun `send writes input verbatim and returns pending output`(): Unit = runBlocking {
        val process = FakeTerminalProcess()
        val manager = manager(RecordingProcessFactory { process })
        val started = assertNotNull(manager.start("bash", emptyList(), null, 40, 20, 200).getOrNull())

        process.writeOutput("hello\n")
        Thread.sleep(30)
        val sent = assertNotNull(
            manager.send(started.sessionId, "echo hi\n", 40, 20, 200).getOrNull()
        )

        assertEquals("echo hi\n", process.writtenText())
        assertTrue(sent.output.contains("hello"), "unexpected output: ${sent.output}")
    }

    @Test
    fun `send to an exited process is rejected as not writable`(): Unit = runBlocking {
        val process = FakeTerminalProcess()
        val manager = manager(RecordingProcessFactory { process })
        val started = assertNotNull(manager.start("bash", emptyList(), null, 40, 20, 200).getOrNull())

        process.finish(7)
        Thread.sleep(40)
        val result = manager.send(started.sessionId, "x\n", 40, 20, 200)

        assertIs<TerminalSessionError.SessionNotWritable>(result.leftOrNull())
        assertEquals("", process.writtenText())
    }

    @Test
    fun `poll after close succeeds with the next chunk and CLOSED`(): Unit = runBlocking {
        val process = FakeTerminalProcess()
        val manager = manager(RecordingProcessFactory { process })
        val started = assertNotNull(manager.start("bash", emptyList(), null, 20, 20, 200).getOrNull())

        process.writeOutput("abcdef\n")
        Thread.sleep(30)
        val closed = assertNotNull(manager.close(started.sessionId, maxLines = 1, maxBytes = 1).getOrNull())
        assertEquals("a", closed.output)
        assertEquals(TerminalStatus.CLOSED, closed.status)

        val latePoll = assertNotNull(
            manager.poll(started.sessionId, 20, 10, 200).getOrNull()
        )
        assertEquals("bcdef\n", latePoll.output)
        assertEquals(TerminalStatus.CLOSED, latePoll.status)
        assertFalse(latePoll.truncated)
        assertEquals(0, latePoll.pendingBytes)

        // close is idempotent even after the tombstone was read.
        assertNotNull(manager.close(started.sessionId, 10, 200).getOrNull())
    }

    @Test
    fun `repeated polls on a closed tombstone drain the backlog then return an empty CLOSED success`(): Unit =
        runBlocking {
            val process = FakeTerminalProcess()
            val manager = manager(RecordingProcessFactory { process })
            val started = assertNotNull(manager.start("bash", emptyList(), null, 20, 20, 200).getOrNull())

            process.writeOutput("1\n2\n3\n4\n5\n6\n")
            Thread.sleep(30)
            val closed = assertNotNull(manager.close(started.sessionId, maxLines = 1, maxBytes = 200).getOrNull())
            assertEquals("1\n", closed.output)
            assertEquals(TerminalStatus.CLOSED, closed.status)

            // One line per poll: every chunk must continue where the previous one stopped.
            val chunks = (1..5).map {
                val polled = assertNotNull(manager.poll(started.sessionId, 20, 1, 200).getOrNull())
                assertEquals(TerminalStatus.CLOSED, polled.status)
                polled.output
            }
            assertEquals(listOf("2\n", "3\n", "4\n", "5\n", "6\n"), chunks)
            assertEquals("1\n2\n3\n4\n5\n6\n", closed.output + chunks.joinToString(""))

            val exhausted = assertNotNull(manager.poll(started.sessionId, 20, 10, 200).getOrNull())
            assertEquals(TerminalStatus.CLOSED, exhausted.status)
            assertEquals("", exhausted.output)
            assertFalse(exhausted.hasNewOutput)
            assertFalse(exhausted.truncated)
            assertEquals(0, exhausted.pendingBytes)
        }

    @Test
    fun `polling an exhausted tombstone returns without spending the wait budget`(): Unit = runBlocking {
        val process = FakeTerminalProcess()
        val manager = manager(RecordingProcessFactory { process })
        val started = assertNotNull(manager.start("bash", emptyList(), null, 20, 20, 200).getOrNull())

        process.writeOutput("only\n")
        Thread.sleep(30)
        assertNotNull(manager.close(started.sessionId, maxLines = 10, maxBytes = 200).getOrNull())

        // A closed session can never produce more output, so the settle loop must not run at all.
        val startedAt = System.nanoTime()
        val empty = assertNotNull(
            manager.poll(started.sessionId, waitMillis = 10_000, maxLines = 10, maxBytes = 200).getOrNull()
        )
        val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000

        assertEquals("", empty.output)
        assertEquals(TerminalStatus.CLOSED, empty.status)
        assertEquals(0, empty.pendingBytes)
        assertTrue(elapsedMillis < 1_000, "poll waited ${elapsedMillis}ms on an exhausted tombstone")
    }

    @Test
    fun `send to a closed session is still rejected as SessionClosed`(): Unit = runBlocking {
        val process = FakeTerminalProcess()
        val manager = manager(RecordingProcessFactory { process })
        val started = assertNotNull(manager.start("bash", emptyList(), null, 20, 20, 200).getOrNull())

        assertNotNull(manager.close(started.sessionId, maxLines = 10, maxBytes = 200).getOrNull())
        val result = manager.send(started.sessionId, "x\n", 40, 20, 200)

        val error = assertIs<TerminalSessionError.SessionClosed>(result.leftOrNull())
        assertEquals(TerminalCloseReason.REQUESTED, error.reason)
        assertEquals("", process.writtenText())
    }

    @Test
    fun `close reports the remaining tail as truncated when its caps cut the final chunk`(): Unit = runBlocking {
        val process = FakeTerminalProcess()
        val manager = manager(RecordingProcessFactory { process })
        val started = assertNotNull(manager.start("bash", emptyList(), null, 20, 20, 200).getOrNull())

        process.writeOutput("1\n2\n3\n4\n")
        Thread.sleep(30)
        val closed = assertNotNull(manager.close(started.sessionId, maxLines = 2, maxBytes = 200).getOrNull())

        assertEquals("1\n2\n", closed.output)
        assertEquals(TerminalStatus.CLOSED, closed.status)
        assertTrue(closed.truncated, "close did not report the undelivered tail")
        assertTrue(closed.pendingBytes > 0, "close reported no pending bytes for an undelivered tail")
    }

    @Test
    fun `a closed tombstone drains a four-byte code point at maxBytes four`(): Unit = runBlocking {
        val process = FakeTerminalProcess()
        val manager = manager(RecordingProcessFactory { process })
        val started = assertNotNull(manager.start("bash", emptyList(), null, 20, 20, 200).getOrNull())

        process.writeOutput("\uD83D\uDE00")
        Thread.sleep(30)
        // One byte cannot hold the emoji, so close leaves it undelivered, exactly like the tool's
        // smallest formerly accepted maxBytes.
        val closed = assertNotNull(manager.close(started.sessionId, maxLines = 10, maxBytes = 1).getOrNull())
        assertEquals("", closed.output)
        assertEquals(4, closed.pendingBytes)

        val drained = assertNotNull(manager.poll(started.sessionId, 20, 10, 4).getOrNull())
        assertEquals("\uD83D\uDE00", drained.output)
        assertEquals(TerminalStatus.CLOSED, drained.status)
        assertEquals(0, drained.pendingBytes)
        assertFalse(drained.truncated)

        val exhausted = assertNotNull(manager.poll(started.sessionId, 20, 10, 4).getOrNull())
        assertEquals("", exhausted.output)
        assertEquals(TerminalStatus.CLOSED, exhausted.status)
        assertFalse(exhausted.hasNewOutput)
        assertFalse(exhausted.truncated)
    }

    @Test
    fun `live session limit is enforced`(): Unit = runBlocking {
        val factory = RecordingProcessFactory { FakeTerminalProcess() }
        var id = 0
        val manager = manager(
            factory,
            testTerminalConfig(maxSessions = 1),
            idGenerator = { "sess0${++id}" },
        )

        assertNotNull(manager.start("bash", emptyList(), null, 20, 10, 200).getOrNull())
        val second = manager.start("bash", emptyList(), null, 20, 10, 200)

        assertIs<TerminalSessionError.SessionLimitReached>(second.leftOrNull())
    }

    @Test
    fun `id collisions with a live session are retried`(): Unit = runBlocking {
        val ids = ArrayDeque(listOf("aaaaa1", "aaaaa1", "bbbbb2"))
        val manager = manager(
            RecordingProcessFactory { FakeTerminalProcess() },
            idGenerator = { ids.removeFirst() },
        )

        val first = assertNotNull(manager.start("bash", emptyList(), null, 20, 10, 200).getOrNull())
        val second = assertNotNull(manager.start("bash", emptyList(), null, 20, 10, 200).getOrNull())

        assertEquals("aaaaa1", first.sessionId)
        assertEquals("bbbbb2", second.sessionId)
    }

    @Test
    fun `an id exhaustion failure releases the reserved slot`(): Unit = runBlocking {
        // The first start consumes one id; the second exhausts all attempts on the same id; the
        // third must then still find a free slot and a fresh id.
        val ids = ArrayDeque(List(MAX_ID_ATTEMPTS + 1) { "aaaaa1" } + "bbbbb2")
        val manager = manager(
            RecordingProcessFactory { FakeTerminalProcess() },
            testTerminalConfig(maxSessions = 2),
            idGenerator = { ids.removeFirst() },
        )

        val first = assertNotNull(manager.start("bash", emptyList(), null, 20, 10, 200).getOrNull())
        assertEquals("aaaaa1", first.sessionId)

        val second = manager.start("bash", emptyList(), null, 20, 10, 200)
        assertIs<TerminalSessionError.SpawnFailed>(second.leftOrNull())

        val third = assertNotNull(manager.start("bash", emptyList(), null, 20, 10, 200).getOrNull())
        assertEquals("bbbbb2", third.sessionId)
    }

    @Test
    fun `a spawn failure becomes SpawnFailed and does not leak a slot`(): Unit = runBlocking {
        var attempts = 0
        val manager = manager(
            RecordingProcessFactory {
                attempts++
                throw java.io.IOException("no pty")
            },
            testTerminalConfig(maxSessions = 1),
            idGenerator = { "uniq01" },
        )

        val first = manager.start("bash", emptyList(), null, 20, 10, 200)
        assertIs<TerminalSessionError.SpawnFailed>(first.leftOrNull())

        val second = manager.start("bash", emptyList(), null, 20, 10, 200)
        assertIs<TerminalSessionError.SpawnFailed>(second.leftOrNull())
        assertEquals(2, attempts)
    }

    @Test
    fun `the tombstone archive evicts the oldest closed session`(): Unit = runBlocking {
        val ids = ArrayDeque(listOf("aaaaa1", "bbbbb2"))
        val manager = manager(
            RecordingProcessFactory { FakeTerminalProcess() },
            testTerminalConfig(maxClosedSessions = 1),
            idGenerator = { ids.removeFirst() },
        )

        val first = assertNotNull(manager.start("bash", emptyList(), null, 20, 10, 200).getOrNull())
        manager.close(first.sessionId, 10, 200)
        val second = assertNotNull(manager.start("bash", emptyList(), null, 20, 10, 200).getOrNull())
        manager.close(second.sessionId, 10, 200)

        assertIs<TerminalSessionError.SessionNotFound>(manager.poll(first.sessionId, 20, 10, 200).leftOrNull())
        val retained = assertNotNull(manager.poll(second.sessionId, 20, 10, 200).getOrNull())
        assertEquals(TerminalStatus.CLOSED, retained.status)
    }

    @Test
    fun `unknown ids resolve to SessionNotFound`(): Unit = runBlocking {
        val manager = manager(RecordingProcessFactory { FakeTerminalProcess() })

        assertIs<TerminalSessionError.SessionNotFound>(manager.poll("zzzzz9", 20, 10, 200).leftOrNull())
    }

    @Test
    fun `the reaper closes an idle session and records the reason`(): Unit = runBlocking {
        val clock = MutableClock()
        val manager = manager(
            RecordingProcessFactory { FakeTerminalProcess() },
            clock = clock::invoke,
        )
        val started = assertNotNull(manager.start("bash", emptyList(), null, 20, 10, 200).getOrNull())

        clock.advanceMillis(61_000)
        manager.reapOnce()

        val polled = assertNotNull(manager.poll(started.sessionId, 20, 10, 200).getOrNull())
        assertEquals(TerminalStatus.CLOSED, polled.status)
        // The close reason stays observable through the write path, which still rejects a tombstone.
        val error = assertIs<TerminalSessionError.SessionClosed>(
            manager.send(started.sessionId, "x\n", 20, 10, 200).leftOrNull()
        )
        assertEquals(TerminalCloseReason.IDLE_TIMEOUT, error.reason)
    }

    @Test
    fun `the reaper closes a session that exceeds its absolute lifetime`(): Unit = runBlocking {
        val clock = MutableClock()
        val manager = manager(
            RecordingProcessFactory { FakeTerminalProcess() },
            testTerminalConfig(maxLifetimeSeconds = 10),
            clock = clock::invoke,
        )
        val started = assertNotNull(manager.start("bash", emptyList(), null, 20, 10, 200).getOrNull())

        // Keep the session active so only the lifetime rule can select it.
        clock.advanceMillis(9_000)
        manager.poll(started.sessionId, 1, 10, 200)
        clock.advanceMillis(2_000)
        manager.reapOnce()

        val polled = assertNotNull(manager.poll(started.sessionId, 20, 10, 200).getOrNull())
        assertEquals(TerminalStatus.CLOSED, polled.status)
        val error = assertIs<TerminalSessionError.SessionClosed>(
            manager.send(started.sessionId, "x\n", 20, 10, 200).leftOrNull()
        )
        assertEquals(TerminalCloseReason.MAX_LIFETIME, error.reason)
    }

    @Test
    fun `overflow advances past the dropped region so output is not re-emitted`(): Unit = runBlocking {
        val process = FakeTerminalProcess()
        val manager = manager(
            RecordingProcessFactory { process },
            testTerminalConfig(maxOutputBytesPerSession = 16),
        )
        val started = assertNotNull(manager.start("bash", emptyList(), null, 20, 10, 200).getOrNull())

        // 21 bytes into a 16-byte buffer: the cursor at zero falls two windows behind.
        process.writeOutput("0123456789ABCDEFGHIJ\n")
        assertTrue(process.awaitOutputRead(2_000), "reader did not consume the overflowing output")
        Thread.sleep(40)

        val first = assertNotNull(manager.poll(started.sessionId, 40, 20, 200).getOrNull())
        assertTrue(first.output.contains("bytes of output were dropped"), "missing notice: ${first.output}")

        val second = assertNotNull(manager.poll(started.sessionId, 40, 20, 200).getOrNull())
        assertEquals("", second.output, "the retained window was re-emitted after the drop")
        assertEquals(
            1,
            Regex("bytes of output were dropped").findAll(first.output + second.output).count(),
        )
    }

    @Test
    fun `droppedBytes reports UTF-8 bytes rather than characters`(): Unit = runBlocking {
        val process = FakeTerminalProcess()
        val manager = manager(
            RecordingProcessFactory { process },
            testTerminalConfig(maxOutputBytesPerSession = 8),
        )
        val started = assertNotNull(manager.start("bash", emptyList(), null, 20, 10, 200).getOrNull())

        // 11 bytes; the buffer retains the last 7, so two two-byte characters (4 bytes) are dropped.
        process.writeOutput("ééééé\n")
        assertTrue(process.awaitOutputRead(2_000), "reader did not consume the output")
        Thread.sleep(40)

        val snapshot = assertNotNull(manager.poll(started.sessionId, 40, 20, 200).getOrNull())
        assertEquals(4L, snapshot.droppedBytes)
        assertTrue(snapshot.output.startsWith("[... 4 bytes of output were dropped"), snapshot.output)
    }

    @Test
    fun `shutdown force-kills a session whose action lock is still held at the deadline`(): Unit = runBlocking {
        val process = FakeTerminalProcess()
        val manager = manager(
            RecordingProcessFactory { process },
            testTerminalConfig(maxSessions = 1),
            shutdownGraceMillis = 300,
        )
        val started = assertNotNull(manager.start("bash", emptyList(), null, 20, 10, 200).getOrNull())

        // The write happens inside the action critical section, so a gated write proves the lock is
        // held when shutdown runs, without depending on scheduling or a sleep.
        process.blockInputWrites()
        val writeJob = async(Dispatchers.Default) {
            manager.send(started.sessionId, "blocked\n", 10_000, 10, 200)
        }
        assertTrue(process.awaitInputWrite(2_000), "send never reached the gated write")

        val startedAt = System.nanoTime()
        manager.close()
        val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000

        assertTrue(elapsedMillis < 2_000, "shutdown blocked for ${elapsedMillis}ms")
        assertTrue(elapsedMillis >= 250, "shutdown did not wait out its budget: ${elapsedMillis}ms")
        assertFalse(process.isAlive(), "the forced-kill fallback did not run")

        // The blocked action must complete instead of failing or wedging the mutex; its status
        // reports the teardown observed by the still-running send (CLOSED once the tombstone is
        // finalized, EXITED before that).
        val sent = assertNotNull(writeJob.await().getOrNull())
        assertTrue(
            sent.status == TerminalStatus.CLOSED || sent.status == TerminalStatus.EXITED,
            "unexpected status after shutdown: ${sent.status}",
        )

        // Shutdown released the live-session slot and cleared the registry.
        assertIs<TerminalSessionError.SessionNotFound>(manager.poll(started.sessionId, 20, 10, 200).leftOrNull())
    }

    @Test
    fun `concurrent actions from a multi-threaded dispatcher stay serialized`(): Unit = runBlocking {
        repeat(8) {
            val process = FakeTerminalProcess()
            val manager = manager(
                RecordingProcessFactory { process },
                testTerminalConfig(maxSessions = 1),
            )
            val started = assertNotNull(manager.start("bash", emptyList(), null, 40, 20, 200).getOrNull())

            // Each action hops to the IO dispatcher while holding the action lock, so a thread-owned
            // lock would be released from a different thread and strand the section.
            val first = async(Dispatchers.Default) {
                manager.send(started.sessionId, "AAAA\n", 60, 20, 200)
            }
            val second = async(Dispatchers.Default) {
                manager.send(started.sessionId, "BBBB\n", 60, 20, 200)
            }
            val results = listOf(first.await(), second.await())

            assertTrue(results.all { it.getOrNull() != null }, "an action failed: $results")
            val written = process.writtenText()
            assertTrue(
                written == "AAAA\nBBBB\n" || written == "BBBB\nAAAA\n",
                "actions interleaved their input: $written",
            )
            assertNotNull(manager.poll(started.sessionId, 40, 20, 200).getOrNull())
            manager.close(started.sessionId, 20, 200)
        }
    }

    @Test
    fun `a cancelled start releases its slot and terminates the registered session`(): Unit = runBlocking {
        val process = FakeTerminalProcess()
        val registered = CountDownLatch(1)
        val logger = mockk<Logger>(relaxed = true)
        every { logger.info(any<String>(), any<Any>(), any<Any>()) } answers {
            if (firstArg<String>().contains("started")) registered.countDown()
        }
        val ids = ArrayDeque(listOf("aaaaa1", "bbbbb2"))
        val manager = manager(
            RecordingProcessFactory { process },
            testTerminalConfig(maxSessions = 1),
            idGenerator = { ids.removeFirst() },
            logger = logger,
        )

        val startJob = launch(Dispatchers.Default) {
            // The long wait keeps the coroutine inside the settle loop, which is where cancellation
            // reaches it after registration.
            manager.start("bash", emptyList(), null, 120_000, 20, 200)
        }
        assertTrue(registered.await(2, TimeUnit.SECONDS), "start never registered its session")
        startJob.cancelAndJoin()

        assertFalse(process.isAlive(), "the cancelled session's process was left running")
        val tombstone = assertIs<TerminalSessionError.SessionClosed>(
            manager.send("aaaaa1", "x\n", 20, 10, 200).leftOrNull()
        )
        assertEquals(TerminalCloseReason.CANCELLED, tombstone.reason)

        // maxSessions is 1, so this only succeeds if the cancelled start released its slot.
        val second = assertNotNull(manager.start("bash", emptyList(), null, 40, 20, 200).getOrNull())
        assertEquals("bbbbb2", second.sessionId)
    }

    @Test
    fun `start does not wait the full deadline when the prompt was drained before the baseline`(): Unit = runBlocking {
        val process = FakeTerminalProcess().also { it.writeOutput("prompt$ ") }
        val logger = mockk<Logger>(relaxed = true)
        every { logger.info(any<String>(), any<Any>(), any<Any>()) } answers {
            if (firstArg<String>().contains("started")) {
                // Emulate the reader winning the race: the prompt is already in the buffer before
                // start samples its baseline.
                assertTrue(process.awaitOutputRead(2_000))
                Thread.sleep(40)
            }
        }
        val manager = manager(RecordingProcessFactory { process }, logger = logger)

        val startedAt = System.nanoTime()
        val snapshot = assertNotNull(
            manager.start("bash", emptyList(), null, waitMillis = 500, maxLines = 20, maxBytes = 200).getOrNull()
        )
        val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000

        assertTrue(snapshot.output.contains("prompt"), snapshot.output)
        assertTrue(elapsedMillis < 300, "start waited ${elapsedMillis}ms for an already-drained prompt")
    }

    @Test
    fun `manager close is idempotent and terminates live sessions`(): Unit = runBlocking {
        val process = FakeTerminalProcess()
        val manager = TerminalSessionManager(
            config = testTerminalConfig(),
            resolution = CommandIsolationResolution.none(),
            childEnvironment = object : ChildEnvironmentProvider {
                override fun build(): Map<String, String> = mapOf("PATH" to "/usr/bin")
            },
            workspace = workspace,
            processFactory = RecordingProcessFactory { process },
            logger = LogManager.getLogger(TerminalSessionManagerTest::class.java),
        )

        assertNotNull(manager.start("bash", emptyList(), null, 20, 10, 200).getOrNull())
        manager.close()
        manager.close()

        assertTrue(!process.isAlive())
    }

    @Test
    fun `a session whose process exited is reported EXITED only after the reader drains its tail`(): Unit =
        runBlocking {
            val process = FakeTerminalProcess()
            // Gate the reader before the session exists, so the process can end while the bytes it
            // wrote are provably still unread; no scheduling assumption is involved.
            process.blockReads()
            val manager = manager(RecordingProcessFactory { process })
            val started = assertNotNull(manager.start("bash", emptyList(), null, 20, 20, 400).getOrNull())
            assertTrue(process.awaitReadBlocked(2_000), "the reader never reached the read gate")

            process.writeOutput("tail line\n")
            process.finish(3)

            val undrained = assertNotNull(manager.poll(started.sessionId, 20, 20, 400).getOrNull())
            assertNotEquals(
                TerminalStatus.EXITED,
                undrained.status,
                "the session was reported ended before the reader drained its tail",
            )
            assertEquals("", undrained.output)

            process.releaseReads()
            val drained = assertNotNull(manager.poll(started.sessionId, 500, 20, 400).getOrNull())
            assertEquals(TerminalStatus.EXITED, drained.status)
            assertEquals("tail line\n", drained.output)
            assertEquals(3, drained.exitCode)
        }

    @Test
    fun `an exit is reported after the drain grace even when the reader never drains`(): Unit = runBlocking {
        val clock = MutableClock()
        val process = FakeTerminalProcess()
        process.blockReads()
        val manager = manager(RecordingProcessFactory { process }, clock = { clock() })
        val started = assertNotNull(manager.start("bash", emptyList(), null, 20, 20, 400).getOrNull())
        assertTrue(process.awaitReadBlocked(2_000), "the reader never reached the read gate")

        process.writeOutput("stuck\n")
        process.finish(0)

        val undrained = assertNotNull(manager.poll(started.sessionId, 20, 20, 400).getOrNull())
        assertNotEquals(TerminalStatus.EXITED, undrained.status)

        // The drain grace is measured on the injected clock, so a frozen clock holds the session
        // open and advancing past the grace must report the exit instead of blocking the caller.
        clock.advanceMillis(5_000)
        val expired = assertNotNull(manager.poll(started.sessionId, 20, 20, 400).getOrNull())
        assertEquals(TerminalStatus.EXITED, expired.status)
        assertEquals(0, expired.exitCode)

        process.releaseReads()
    }

    @Test
    fun `close returns the output the child wrote before it exited`(): Unit = runBlocking {
        // Larger than one reader buffer, so the tail only survives if teardown lets the reader drain
        // instead of closing the master underneath it.
        val payload = "y".repeat(32 * 1024) + "\n"
        val process = FakeTerminalProcess()
        // Gate the reader before the session exists, so the payload is provably still unread when the
        // close starts; no scheduling assumption is involved.
        process.blockReads()
        val manager = manager(
            RecordingProcessFactory { process },
            testTerminalConfig(maxOutputBytesPerSession = 128 * 1024),
        )
        val started = assertNotNull(manager.start("bash", emptyList(), null, 20, 10, 200).getOrNull())
        assertTrue(process.awaitReadBlocked(2_000), "the reader never reached the read gate")

        process.writeOutput(payload)
        process.finish(0)

        val closing = async(Dispatchers.Default) {
            manager.close(started.sessionId, maxLines = Int.MAX_VALUE, maxBytes = Int.MAX_VALUE)
        }
        // A close-first teardown ends the reader right away and the 32 KiB tail is gone; a drain-first
        // teardown can only close the master after the join, which waits for this gate. Releasing the
        // gate after the close has had its chance therefore separates the two orders deterministically.
        val closedBeforeRelease = process.awaitOutputStreamClosed(300)
        process.releaseReads()

        val closed = assertNotNull(closing.await().getOrNull())

        assertEquals(TerminalStatus.CLOSED, closed.status)
        assertEquals(
            payload,
            closed.output,
            "the tail was lost (master closed before the reader drained: $closedBeforeRelease)",
        )
    }
}
