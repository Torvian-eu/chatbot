package eu.torvian.chatbot.worker.builtin.command

import java.io.IOException
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.apache.logging.log4j.Logger

/**
 * Verifies the relay delivery primitive that spawns the helper in its signal mode.
 *
 * A stub executable stands in for the helper, so the argv contract, the exit-code interpretation,
 * the empty environment and the WARN behaviour are asserted without a setuid binary. The suite is
 * Linux-only because it relies on an executable shell script.
 */
class HelperProcessGroupSignallerTest {

    /**
     * Records the argv request and answers with a fixed stub executable.
     *
     * @property stubPath Executable the relay must actually spawn.
     */
    private class StubLauncher(private val stubPath: Path) : CommandLauncher {
        /** Commands passed to [wrap], in call order. */
        val requestedCommands = mutableListOf<String>()

        /** Arguments passed to [wrap], in call order. */
        val requestedArgs = mutableListOf<List<String>>()

        override fun wrap(command: String, args: List<String>): List<String> {
            requestedCommands += command
            requestedArgs += args
            return listOf(stubPath.toString(), command) + args
        }
    }

    /** Verifies that a helper reporting success is treated as a delivered signal and logs nothing. */
    @Test
    fun `successful relay reports delivery without logging`() {
        if (isWindows) return
        val dir = createTempDirectory("helper-signal-relay")
        try {
            val stub = writeStub(dir, "#!/bin/sh\nexit 0\n")
            val warnings = mutableListOf<String>()
            val signaller = HelperProcessGroupSignaller(StubLauncher(stub), recordingLogger(warnings))

            assertTrue(signaller.signal(4242, CommandSignal.TERM, 500_000_000L))

            assertTrue(warnings.isEmpty(), "Nothing must be logged: $warnings")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /** Verifies that a refused delivery is reported at WARN with the signal and the group id. */
    @Test
    fun `failed relay returns false and warns with signal and group id`() {
        if (isWindows) return
        val dir = createTempDirectory("helper-signal-relay")
        try {
            val stub = writeStub(dir, "#!/bin/sh\necho 'run-as-agent: kill: Operation not permitted' >&2\nexit 11\n")
            val warnings = mutableListOf<String>()
            val signaller = HelperProcessGroupSignaller(StubLauncher(stub), recordingLogger(warnings))

            assertFalse(signaller.signal(4242, CommandSignal.KILL, 500_000_000L))

            assertTrue(
                warnings.any { it.contains("KILL") && it.contains("4242") },
                "WARN did not name the signal and the group: $warnings"
            )
            assertTrue(
                warnings.any { it.contains("Operation not permitted") },
                "WARN did not carry the helper's own reason: $warnings"
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /** Verifies that a relay which cannot be started is reported instead of thrown. */
    @Test
    fun `missing helper returns false without throwing`() {
        if (isWindows) return
        val dir = createTempDirectory("helper-signal-relay")
        try {
            val missing = dir.resolve("run-as-agent")
            val warnings = mutableListOf<String>()
            val signaller = HelperProcessGroupSignaller(StubLauncher(missing), recordingLogger(warnings))

            assertFalse(signaller.signal(4242, CommandSignal.TERM, 500_000_000L))

            assertTrue(warnings.isNotEmpty(), "A relay that cannot be started must be reported")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /** Verifies that a relay outliving its bound is abandoned and reported. */
    @Test
    fun `relay that does not exit within its bound returns false`() {
        if (isWindows) return
        val dir = createTempDirectory("helper-signal-relay")
        try {
            val stub = writeStub(dir, "#!/bin/sh\nsleep 30\n")
            val warnings = mutableListOf<String>()
            val signaller = HelperProcessGroupSignaller(StubLauncher(stub), recordingLogger(warnings))

            assertFalse(signaller.signal(4242, CommandSignal.KILL, 200_000_000L))

            assertTrue(
                warnings.any { it.contains("did not exit") },
                "A relay that outlives its bound must be reported: $warnings"
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /** Verifies the helper contract the worker depends on: the signal mode argv shape. */
    @Test
    fun `signal mode argv is requested through the launcher`() {
        if (isWindows) return
        val dir = createTempDirectory("helper-signal-relay")
        try {
            val stub = writeStub(dir, "#!/bin/sh\nexit 0\n")
            val launcher = StubLauncher(stub)
            val signaller = HelperProcessGroupSignaller(launcher, recordingLogger(mutableListOf()))

            signaller.signal(4242, CommandSignal.KILL, 500_000_000L)

            assertEquals(listOf("--signal"), launcher.requestedCommands, "Unexpected mode")
            assertEquals(listOf(listOf("KILL", "4242")), launcher.requestedArgs, "Unexpected signal argv")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /** Verifies that the relay inherits nothing of the worker environment. */
    @Test
    fun `relay runs with an empty environment`() {
        if (isWindows) return
        val dir = createTempDirectory("helper-signal-relay")
        try {
            val captured = dir.resolve("environment.txt")
            // The write is gated on the signal mode so the warm-up spawn of the stub, which starts
            // with the worker environment on purpose, cannot race with the relay over this file.
            val stub = writeStub(
                dir,
                "#!/bin/sh\nif [ \"\$1\" = '--signal' ]; then env > '$captured'; fi\nexit 0\n"
            )
            val signaller = HelperProcessGroupSignaller(StubLauncher(stub), recordingLogger(mutableListOf()))

            assertTrue(signaller.signal(4242, CommandSignal.TERM, 500_000_000L))

            // A POSIX shell exports its own bookkeeping variables even from an empty environment, so
            // those names are excluded; every name the worker itself carries must be absent, which is
            // exactly the /proc/<pid>/environ oracle the empty environment exists to close.
            val observed = captured.toFile().readText()
            val workerNames = System.getenv().keys - SHELL_BOOKKEEPING
            assertTrue(workerNames.isNotEmpty(), "The test host exposes no environment to check against")
            val leaked = workerNames.filter { name -> observed.contains("$name=") }
            assertTrue(leaked.isEmpty(), "Relay inherited worker environment variables: $leaked")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * Writes an executable stub script and waits until the kernel accepts executing it.
     *
     * A process forked while the stub was being written briefly holds its write descriptor, which
     * makes an immediate exec of that file fail with `ETXTBSY`; the window closes as soon as that
     * fork completes, so the stub is spawned once here before an assertion relies on it.
     *
     * @param dir Directory that receives the stub.
     * @param content Full script content, including the shebang.
     * @return Path of the executable stub.
     */
    private fun writeStub(dir: Path, content: String): Path {
        val stub = dir.resolve("stub-run-as-agent")
        stub.writeText(content)
        assertTrue(stub.toFile().setExecutable(true), "Could not mark the stub executable")
        awaitExecutable(stub)
        return stub
    }

    /**
     * Retries until executing [stub] is no longer refused as busy.
     *
     * @param stub Executable file that must be runnable.
     */
    private fun awaitExecutable(stub: Path) {
        repeat(50) {
            try {
                val warmUp = ProcessBuilder(stub.toString()).apply { redirectErrorStream(true) }.start()
                warmUp.destroyForcibly()
                return
            } catch (e: IOException) {
                if (!e.message.orEmpty().contains("Text file busy")) throw e
                Thread.sleep(10)
            }
        }
        throw AssertionError("Stub $stub could not be executed")
    }

    /**
     * Builds a logger that records the messages it receives with their parameters rendered.
     *
     * A reflective proxy is used instead of a mock because Log4j resolves a format call to a
     * fixed-arity overload, so a mock would have to reproduce one stub per arity and would fail
     * silently on the next call shape. Only WARN is ever emitted by the code under test.
     *
     * @param warnings Mutable list receiving the rendered messages.
     * @return Logger recording WARN calls.
     */
    private fun recordingLogger(warnings: MutableList<String>): Logger {
        val handler = InvocationHandler { _, method: Method, arguments: Array<Any?>? ->
            if (method.name == "warn" && !arguments.isNullOrEmpty()) {
                val message = arguments[0].toString()
                // A varargs overload arrives with its parameters packed into one array element.
                val parameters = arguments.drop(1).flatMap { argument ->
                    if (argument is Array<*>) argument.toList() else listOf(argument)
                }
                warnings.add(render(message, parameters))
            }
            // The code under test only calls WARN; a defensive default keeps a primitive-returning
            // call from failing as a null unbox.
            if (method.returnType == Boolean::class.javaPrimitiveType) false else null
        }
        return Proxy.newProxyInstance(
            Logger::class.java.classLoader,
            arrayOf(Logger::class.java),
            handler,
        ) as Logger
    }

    /**
     * Substitutes Log4j placeholders in [message] with [parameters], in order.
     *
     * @param message Message template as passed to the logger.
     * @param parameters Values passed with the template.
     * @return The message an operator would read, placeholders replaced.
     */
    private fun render(message: String, parameters: List<Any?>): String {
        var rendered = message
        parameters.forEach { parameter -> rendered = rendered.replaceFirst("{}", parameter.toString()) }
        return rendered
    }

    private companion object {
        /** Whether the current test host cannot run an executable shell script. */
        val isWindows: Boolean = System.getProperty("os.name").contains("windows", ignoreCase = true)

        /** Environment names a POSIX shell exports on its own, even with an empty environment. */
        val SHELL_BOOKKEEPING: Set<String> = setOf("PWD", "OLDPWD", "SHLVL", "_")
    }
}
