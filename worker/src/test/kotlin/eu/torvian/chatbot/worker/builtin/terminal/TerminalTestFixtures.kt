package eu.torvian.chatbot.worker.builtin.terminal

import eu.torvian.chatbot.worker.config.TerminalSessionsConfig
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.stream.Stream

/**
 * In-memory [Process] used instead of a PTY in session and manager tests.
 *
 * Output written with [writeOutput] is what the session reader consumes; bytes a caller sends to
 * [getOutputStream] are recorded and read back with [writtenText]. Ending the process closes the
 * output writer so the reader observes EOF. Like the PTY master it stands in for, closing either
 * stream obtained from this process ends the reader and discards bytes that were written but not
 * yet read.
 *
 * @param initialExitCode Exit status reported once the process ends.
 */
internal class FakeTerminalProcess(private val initialExitCode: Int = 0) : Process() {
    // java.lang.Object is needed for wait/notify; Kotlin's Any cannot be waited on.
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val lock = Object()
    private val childOutput = PipedOutputStream()
    private val childInput = PipedInputStream(childOutput, 64 * 1024)
    private val received = ByteArrayOutputStream()
    private val firstOutputRead = CountDownLatch(1)
    private val inputWriteEntered = CountDownLatch(1)
    private val inputWriteRelease = CountDownLatch(1)
    private val readEntered = CountDownLatch(1)
    private val readRelease = CountDownLatch(1)
    private val outputClosed = CountDownLatch(1)
    @Volatile
    private var blockInputWrites = false
    @Volatile
    private var gateReads = false
    private var alive = true
    private var exitStatus = initialExitCode

    override fun getOutputStream(): OutputStream = object : OutputStream() {
        override fun write(b: Int) {
            awaitInputWriteGate()
            synchronized(received) { received.write(b) }
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            awaitInputWriteGate()
            synchronized(received) { received.write(b, off, len) }
        }

        // Production backs both streams with one PTY master, so closing either one ends the reader
        // and discards whatever the child wrote but nobody read yet. Mirroring that here is what
        // lets a test tell a drain-first teardown from a close-first one.
        override fun close() {
            inputWriteRelease.countDown()
            runCatching { childInput.close() }
            // Signalled only once the unread tail is unrecoverable, so a test that waits for it can
            // never release the reader before the truncation happened.
            outputClosed.countDown()
        }

        /**
         * Blocks a gated write until the test releases it or the stream is closed.
         */
        private fun awaitInputWriteGate() {
            if (!blockInputWrites) return
            inputWriteEntered.countDown()
            inputWriteRelease.await(10, TimeUnit.SECONDS)
        }
    }

    override fun getInputStream(): InputStream = object : FilterInputStream(childInput) {
        override fun read(): Int = super.read().also { if (it >= 0) firstOutputRead.countDown() }

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            awaitReadGate()
            return super.read(bytes, offset, length).also { if (it > 0) firstOutputRead.countDown() }
        }

        /**
         * Holds the session reader until [releaseReads] or the safety timeout.
         */
        private fun awaitReadGate() {
            if (!gateReads) return
            readEntered.countDown()
            readRelease.await(10, TimeUnit.SECONDS)
        }
    }

    override fun getErrorStream(): InputStream = InputStream.nullInputStream()

    // A real ProcessHandle would describe the test JVM; an inert handle keeps the production
    // terminator's tracking pass harmless while destroyForcibly() still ends this fake process.
    override fun toHandle(): ProcessHandle = INERT_HANDLE

    override fun waitFor(): Int {
        synchronized(lock) {
            while (alive) lock.wait()
            return exitStatus
        }
    }

    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
        synchronized(lock) {
            if (alive) lock.wait(unit.toMillis(timeout))
            return !alive
        }
    }

    override fun exitValue(): Int = synchronized(lock) {
        check(!alive) { "Process has not exited" }
        exitStatus
    }

    override fun destroy() {
        finish(143)
    }

    override fun destroyForcibly(): Process {
        finish(137)
        return this
    }

    override fun isAlive(): Boolean = synchronized(lock) { alive }

    /**
     * Emits bytes that the session reader will consume.
     *
     * @param text Output to append.
     */
    fun writeOutput(text: String) {
        childOutput.write(text.toByteArray(Charsets.UTF_8))
        childOutput.flush()
    }

    /**
     * Ends the process and unblocks the reader with EOF.
     *
     * @param code Exit status to report.
     */
    fun finish(code: Int = initialExitCode) {
        synchronized(lock) {
            if (alive) {
                alive = false
                exitStatus = code
            }
            lock.notifyAll()
        }
        runCatching { childOutput.close() }
    }

    /** @return Bytes written to the process's input stream. */
    fun writtenText(): String = synchronized(received) { received.toString(Charsets.UTF_8) }

    /**
     * Makes the next input write block until [releaseInputWrites] or stream close.
     *
     * Gives tests a deterministic way to hold a session's action critical section: the write happens
     * inside it.
     */
    fun blockInputWrites() {
        blockInputWrites = true
    }

    /**
     * Waits until a gated input write has entered the process.
     *
     * @param timeoutMillis Maximum wait in milliseconds.
     * @return True when a gated write was observed before the timeout.
     */
    fun awaitInputWrite(timeoutMillis: Long): Boolean =
        inputWriteEntered.await(timeoutMillis, TimeUnit.MILLISECONDS)

    /**
     * Holds every subsequent read until [releaseReads].
     *
     * Lets a test end the process while the bytes it wrote are still unread, so the drain order is
     * observable without timing assumptions.
     */
    fun blockReads() {
        gateReads = true
    }

    /**
     * Waits until the session reader is parked on the read gate.
     *
     * @param timeoutMillis Maximum wait in milliseconds.
     * @return True when the reader reached the gate before the timeout.
     */
    fun awaitReadBlocked(timeoutMillis: Long): Boolean =
        readEntered.await(timeoutMillis, TimeUnit.MILLISECONDS)

    /** Releases every read held by [blockReads]. */
    fun releaseReads() {
        gateReads = false
        readRelease.countDown()
    }

    /** Releases a gated input write. */
    fun releaseInputWrites() {
        inputWriteRelease.countDown()
    }

    /**
     * Waits until the master's output stream has been closed.
     *
     * Closing it ends the reader in this fake, so a test can release a gated reader only after the
     * close path has had its chance to discard the unread tail.
     *
     * @param timeoutMillis Maximum wait in milliseconds.
     * @return True when the stream was closed before the timeout.
     */
    fun awaitOutputStreamClosed(timeoutMillis: Long): Boolean =
        outputClosed.await(timeoutMillis, TimeUnit.MILLISECONDS)

    /**
     * Waits until the session reader has consumed at least one output byte.
     *
     * @param timeoutMillis Maximum wait in milliseconds.
     * @return True when output was observed before the timeout.
     */
    fun awaitOutputRead(timeoutMillis: Long): Boolean =
        firstOutputRead.await(timeoutMillis, TimeUnit.MILLISECONDS)

    private companion object {
        /** Handle that represents a dead process with no children. */
        val INERT_HANDLE: ProcessHandle = object : ProcessHandle {
            override fun pid(): Long = -1L

            override fun info(): ProcessHandle.Info = object : ProcessHandle.Info {
                override fun command(): Optional<String> = Optional.empty()
                override fun commandLine(): Optional<String> = Optional.empty()
                override fun arguments(): Optional<Array<String>> = Optional.empty()
                override fun startInstant(): Optional<java.time.Instant> = Optional.empty()
                override fun totalCpuDuration(): Optional<java.time.Duration> = Optional.empty()
                override fun user(): Optional<String> = Optional.empty()
            }

            override fun isAlive(): Boolean = false

            override fun supportsNormalTermination(): Boolean = false

            override fun destroy(): Boolean = false

            override fun destroyForcibly(): Boolean = false

            override fun parent(): Optional<ProcessHandle> = Optional.empty()

            override fun children(): Stream<ProcessHandle> = Stream.empty()

            override fun descendants(): Stream<ProcessHandle> = Stream.empty()

            override fun onExit(): CompletableFuture<ProcessHandle> =
                CompletableFuture.completedFuture(this)

            override fun compareTo(other: ProcessHandle): Int = 0
        }
    }
}

/**
 * Factory that records the spec and returns a test process.
 *
 * @property processProvider Builds the process for each spec.
 */
internal class RecordingProcessFactory(
    private val processProvider: (TerminalProcessSpec) -> Process,
) : TerminalProcessFactory {
    /** Last spec received, or null before the first start. */
    var lastSpec: TerminalProcessSpec? = null
        private set

    override fun start(spec: TerminalProcessSpec): Process {
        lastSpec = spec
        return processProvider(spec)
    }
}

/**
 * Mutable monotonic clock for deterministic timing assertions.
 *
 * @property nanos Current value in nanoseconds.
 */
internal class MutableClock(var nanos: Long = 1_000_000_000L) {
    /** Advances the clock by [millis] milliseconds. */
    fun advanceMillis(millis: Long) {
        nanos += TimeUnit.MILLISECONDS.toNanos(millis)
    }

    /** @return The current nanosecond value. */
    operator fun invoke(): Long = nanos
}

/**
 * @return A small but valid terminal-session configuration for tests.
 */
internal fun testTerminalConfig(
    maxSessions: Int = 4,
    maxClosedSessions: Int = 4,
    maxOutputBytesPerSession: Int = 4096,
    quietMillis: Long = 20,
    defaultWaitMillis: Long = 150,
    maxLifetimeSeconds: Long = 3600,
    reaperIntervalSeconds: Long = 1,
    closedSessionRetentionSeconds: Long = 3600,
): TerminalSessionsConfig = TerminalSessionsConfig(
    defaultIdleTimeoutSeconds = 60,
    maxIdleTimeoutSeconds = 120,
    maxLifetimeSeconds = maxLifetimeSeconds,
    maxSessions = maxSessions,
    maxClosedSessions = maxClosedSessions,
    maxOutputBytesPerSession = maxOutputBytesPerSession,
    quietMillis = quietMillis,
    defaultWaitMillis = defaultWaitMillis,
    maxWaitMillis = 1000,
    reaperIntervalSeconds = reaperIntervalSeconds,
    closedSessionRetentionSeconds = closedSessionRetentionSeconds,
    columns = 80,
    rows = 24,
    tuiEscapeRatioThreshold = 0.15,
)
