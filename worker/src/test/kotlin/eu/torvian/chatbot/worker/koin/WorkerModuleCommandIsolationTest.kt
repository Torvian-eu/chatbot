package eu.torvian.chatbot.worker.koin

import eu.torvian.chatbot.worker.builtin.BuiltInTool
import eu.torvian.chatbot.worker.builtin.command.CommandIsolationResolution
import eu.torvian.chatbot.worker.builtin.command.CommandLauncher
import eu.torvian.chatbot.worker.builtin.command.CommandTerminator
import eu.torvian.chatbot.worker.config.AppConfigDto
import eu.torvian.chatbot.worker.config.IdentityConfigDto
import eu.torvian.chatbot.worker.config.RuntimeConfig
import eu.torvian.chatbot.worker.config.RuntimeConfigDto
import eu.torvian.chatbot.worker.config.ServerConfigDto
import eu.torvian.chatbot.worker.config.SetupConfigDto
import eu.torvian.chatbot.worker.config.StorageConfigDto
import eu.torvian.chatbot.worker.config.WorkspaceConfigDto
import eu.torvian.chatbot.worker.config.toDomain
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.koin.core.Koin
import org.koin.dsl.koinApplication

/**
 * Verifies that the worker module derives its isolation-dependent registrations from one resolution.
 *
 * The registration is asserted rather than the tool's behaviour, because a wiring mistake here would
 * silently give the tool a launcher or a terminator that contradicts the resolved isolation state.
 */
class WorkerModuleCommandIsolationTest {

    /** Verifies that the launcher and terminator come from the resolution passed into the module. */
    @Test
    fun `module exposes the resolved launcher and terminator`() {
        withWorkerModule { _, resolution, koin ->
            assertSame(resolution.launcher, koin.get<CommandLauncher>())
            assertSame(resolution.terminator, koin.get<CommandTerminator>())
        }
    }

    /** Verifies that the built-in tool registry is built and contains the command tool. */
    @Test
    fun `tool registry contains the command tool`() {
        withWorkerModule { _, _, koin ->
            val tools = koin.get<Map<String, BuiltInTool>>()

            assertTrue(tools.containsKey("run_command"), "Unexpected registry keys: ${tools.keys}")
        }
    }

    /**
     * Builds the worker module from a real configuration and inspects it in an isolated Koin context.
     *
     * The configuration is assembled through the production DTO conversion, so configuration
     * semantics are not re-declared here, and the application is closed again to keep the test from
     * touching or depending on another test's Koin state.
     *
     * @param assertions Receives the assembled configuration, the resolution and the Koin context.
     */
    private fun withWorkerModule(assertions: (RuntimeConfig, CommandIsolationResolution, Koin) -> Unit) {
        val configDir = createTempDirectory("worker-module-command-isolation")
        try {
            val dto = AppConfigDto(
                setup = SetupConfigDto(required = false),
                worker = RuntimeConfigDto(
                    server = ServerConfigDto(baseUrl = "https://example.test/"),
                    identity = IdentityConfigDto(
                        uid = "worker-1",
                        displayName = "worker-one",
                        certificateFingerprint = "fingerprint-1",
                        certificatePem = "pem-1"
                    ),
                    storage = StorageConfigDto(
                        secretsJsonPath = "./secrets.json",
                        tokenFilePath = "./token.json"
                    ),
                    workspace = WorkspaceConfigDto(path = "./workspace")
                )
            )
            val configuration = dto.toDomain(configDir).getOrNull()
            assertTrue(configuration != null, "Test configuration did not assemble")

            val resolution = CommandIsolationResolution.none()
            val application = koinApplication {
                modules(workerModule(configuration.worker, configDir.resolve("token.json"), "pem-1", resolution))
            }
            try {
                assertions(configuration.worker, resolution, application.koin)
            } finally {
                application.close()
            }
        } finally {
            configDir.toFile().deleteRecursively()
        }
    }
}
