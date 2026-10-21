package eu.torvian.chatbot.worker.builtin.terminal

import eu.torvian.chatbot.worker.builtin.command.AllowlistChildEnvironmentProvider
import eu.torvian.chatbot.worker.builtin.command.ChildEnvironmentProvider
import eu.torvian.chatbot.worker.builtin.command.CommandIsolationResolution
import eu.torvian.chatbot.worker.config.CommandEnvironmentConfig
import kotlinx.coroutines.runBlocking
import org.apache.logging.log4j.LogManager
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Real-PTY tests for the production factory.
 *
 * The tests skip when the host cannot create a PTY. The isolated gate additionally requires
 * `CHATBOT_TERMINAL_ISOLATION_GATE=true`, the setuid helper, and the `agent` account; it is the
 * regression check for the helper accepting an already-leader PTY child.
 */
class Pty4jTerminalSessionIntegrationTest {

    private val workspace: Path = createTempDirectory("terminal-pty-test")
    private val listeners = mutableListOf<TerminalSessionManager>()

    /** Closes every manager and removes the temporary workspace. */
    @AfterTest
    fun tearDown() {
        listeners.forEach { runCatching { it.close() } }
        workspace.toFile().deleteRecursively()
    }

    @Test
    fun `a real PTY shell runs a command and reports the workspace`(): Unit = runBlocking {
        val manager = managerOrSkip(CommandIsolationResolution.none(), childEnvironment())
        val started = assertNotNull(manager.start("sh", emptyList(), null, 500, 100, 8000).getOrNull())

        val sent = manager.send(started.sessionId, "echo hello\n", 500, 100, 8000).getOrNull()
        val output = buildString {
            sent?.output?.let(::append)
            append(pollUntil(manager, started.sessionId, "hello"))
        }
        assertTrue(output.contains("hello"), "unexpected output: $output")

        val pwdSent = manager.send(started.sessionId, "pwd\n", 500, 100, 8000).getOrNull()
        val pwd = buildString {
            pwdSent?.output?.let(::append)
            append(pollUntil(manager, started.sessionId, workspace.fileName.toString()))
        }
        assertTrue(pwd.contains(workspace.toAbsolutePath().toString()), "unexpected pwd output: $pwd")

        manager.close(started.sessionId, 100, 8000)
    }

    @Test
    fun `an isolated PTY runs as agent without a setpgid failure`(): Unit = runBlocking {
        val resolution = isolatedResolutionOrSkip()
        val manager = managerOrSkip(resolution, isolatedEnvironment())
        // bash is the interactive mode the tool documents; sh is enough for the account assertion.
        val started = assertNotNull(manager.start("bash", emptyList(), null, 800, 100, 8000).getOrNull())

        val sent = manager.send(started.sessionId, "id -un\n", 800, 100, 8000).getOrNull()
        val output = buildString {
            sent?.output?.let(::append)
            append(pollUntil(manager, started.sessionId, "agent"))
        }

        assertTrue(output.contains("agent"), "unexpected account output: $output")
        assertTrue(
            !output.contains("run-as-agent: setpgid"),
            "the image still ships the old helper: $output",
        )
        manager.close(started.sessionId, 100, 8000)
    }

    /**
     * Polls until [needle] appears or a bounded number of attempts is exhausted.
     *
     * @param manager Live manager.
     * @param sessionId Session to poll.
     * @param needle Text to wait for.
     * @return Concatenated output seen so far.
     */
    private suspend fun pollUntil(
        manager: TerminalSessionManager,
        sessionId: String,
        needle: String,
    ): String {
        val seen = StringBuilder()
        repeat(20) {
            val snapshot = manager.poll(sessionId, 300, 200, 16000).getOrNull()
            if (snapshot != null) {
                seen.append(snapshot.output)
                if (seen.contains(needle)) return seen.toString()
            }
        }
        return seen.toString()
    }

    /**
     * @param resolution Isolation state for the manager.
     * @param environment Child environment provider.
     * @return A manager, or skip the test when pty4j cannot start a PTY.
     */
    private fun managerOrSkip(
        resolution: CommandIsolationResolution,
        environment: ChildEnvironmentProvider,
    ): TerminalSessionManager {
        val probe = Pty4jTerminalProcessFactory()
        try {
            probe.start(
                TerminalProcessSpec(
                    argv = listOf("true"),
                    environment = mapOf("PATH" to "/usr/local/bin:/usr/bin:/bin"),
                    workingDirectory = workspace,
                    columns = 80,
                    rows = 24,
                )
            ).destroyForcibly()
        } catch (e: Throwable) {
            if (e is IOException || e is UnsatisfiedLinkError) {
                assumeTrue(false, "PTY unavailable: ${e.message}")
            } else {
                throw e
            }
        }
        val manager = TerminalSessionManager(
            config = testTerminalConfig(defaultWaitMillis = 500, quietMillis = 100),
            resolution = resolution,
            childEnvironment = environment,
            workspace = workspace,
            logger = LogManager.getLogger(Pty4jTerminalSessionIntegrationTest::class.java),
        )
        listeners += manager
        return manager
    }

    /**
     * @return The isolated resolution, or skip when the in-image gate is not armed.
     */
    private fun isolatedResolutionOrSkip(): CommandIsolationResolution {
        assumeTrue(
            System.getenv("CHATBOT_TERMINAL_ISOLATION_GATE") == "true",
            "CHATBOT_TERMINAL_ISOLATION_GATE is not set",
        )
        val helper = Path.of("/usr/local/bin/run-as-agent")
        val agentHome = Path.of("/home/agent")
        assumeTrue(Files.isExecutable(helper), "setuid helper is unavailable")
        assumeTrue(Files.isDirectory(agentHome), "agent home is unavailable")
        return CommandIsolationResolution.isolated(
            helperPath = helper.toString(),
            user = "agent",
            home = agentHome.toString(),
            logger = LogManager.getLogger(Pty4jTerminalSessionIntegrationTest::class.java),
        )
    }

    /** @return An inherited-like environment without the worker's credentials. */
    private fun childEnvironment(): ChildEnvironmentProvider = object : ChildEnvironmentProvider {
        override fun build(): Map<String, String> = mapOf(
            "PATH" to "/usr/local/bin:/usr/bin:/bin",
            "LANG" to "C.UTF-8",
        )
    }

    /** @return The allowlist environment used by the isolated gate. */
    private fun isolatedEnvironment(): ChildEnvironmentProvider =
        AllowlistChildEnvironmentProvider(
            config = CommandEnvironmentConfig(
                allowPassthrough = listOf("PATH", "LANG"),
                set = emptyMap(),
            ),
            isolatedUser = "agent",
            isolatedHome = "/home/agent",
        )
}
