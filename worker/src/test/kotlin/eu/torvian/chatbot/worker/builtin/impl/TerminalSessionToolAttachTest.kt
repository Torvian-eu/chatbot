package eu.torvian.chatbot.worker.builtin.impl

import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.api.credential.CredentialChannel
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.BuiltInToolExecutionResult
import eu.torvian.chatbot.common.models.tool.BuiltInToolCatalog
import eu.torvian.chatbot.worker.builtin.BuiltInToolExecutionContext
import eu.torvian.chatbot.worker.builtin.BuiltInToolExecutionError
import eu.torvian.chatbot.worker.builtin.terminal.AttachTransition
import eu.torvian.chatbot.worker.builtin.terminal.TerminalSessionError
import eu.torvian.chatbot.worker.builtin.terminal.TerminalSessionManager
import eu.torvian.chatbot.worker.builtin.terminal.TerminalSnapshot
import eu.torvian.chatbot.worker.builtin.terminal.TerminalStatus
import eu.torvian.chatbot.worker.builtin.terminal.testTerminalConfig
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Validation and result-mapping tests for the `attach` and `detach` actions of [TerminalSessionTool].
 *
 * The manager is mocked, so these tests cover the contract the model sees: which arguments each action
 * accepts, which error code each failure maps to, and that no secret material ever appears in a result.
 */
class TerminalSessionToolAttachTest {

    /** Mocked manager used by every test. */
    private lateinit var manager: TerminalSessionManager

    /** Tool under test. */
    private lateinit var tool: TerminalSessionTool

    /** Creates the mocked manager and tool for each test. */
    @BeforeTest
    fun setUp() {
        manager = mockk()
        every { manager.config } returns testTerminalConfig()
        tool = TerminalSessionTool(manager)
    }

    /** Releases MockK state after each test. */
    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    /** Verifies that attach requires the credential name. */
    @Test
    fun `attach requires a credential`(): Unit = runBlocking {
        val result = tool.execute(
            input("attach") { put("session_id", SESSION_ID) },
            context(TEST_ROLE_ID),
        )

        assertInvalidInput(result)
        assertTrue(validationErrors(result).contains("credential"))
    }

    /** Verifies that a blank credential name is rejected before the manager is consulted. */
    @Test
    fun `attach rejects a blank credential name`(): Unit = runBlocking {
        val result = tool.execute(
            input("attach") {
                put("session_id", SESSION_ID)
                put("credential", " ")
            },
            context(TEST_ROLE_ID))

        assertInvalidInput(result)
        assertTrue(validationErrors(result).contains("credential"))
        coVerify(exactly = 0) { manager.attach(any(), any(), any(), any(), any(), any()) }
    }

    /** Verifies that attach accepts the documented arguments only. */
    @Test
    fun `attach rejects command arguments`(): Unit = runBlocking {
        val result = tool.execute(
            input("attach") {
                put("session_id", SESSION_ID)
                put("credential", "vps")
                put("command", "bash")
            },
            context(TEST_ROLE_ID))

        assertInvalidInput(result)
        assertTrue(validationErrors(result).contains("command"))
    }

    /** Verifies that no other action may carry a credential. */
    @Test
    fun `other actions reject the credential argument`(): Unit = runBlocking {
        listOf("poll", "detach", "close").forEach { action ->
            val result = tool.execute(
                input(action) {
                    put("session_id", SESSION_ID)
                    put("credential", "vps")
                },
                context(TEST_ROLE_ID))

            assertInvalidInput(result)
            assertTrue(
                validationErrors(result).contains("credential"),
                "Action '$action' accepted a credential",
            )
        }

        val startResult = tool.execute(
            input("start") {
                put("command", "bash")
                put("credential", "vps")
            },
            context(TEST_ROLE_ID))
        assertInvalidInput(startResult)
        assertTrue(validationErrors(startResult).contains("credential"))
    }

    /** Verifies that detach needs only the session handle. */
    @Test
    fun `detach requires a session handle`(): Unit = runBlocking {
        val result = tool.execute(input("detach"), context(TEST_ROLE_ID))

        assertInvalidInput(result)
        assertTrue(validationErrors(result).contains("session_id"))
    }

    /** Verifies that a successful attach reports the attachment without any secret material. */
    @Test
    fun `attach details report the profile and channel`(): Unit = runBlocking {
        coEvery { manager.attach(SESSION_ID, "vps", any(), any(), any(), any()) } returns
            snapshot(status = TerminalStatus.ATTACHED, transition = AttachTransition.ATTACHED).right()

        val result = tool.execute(
            input("attach") {
                put("session_id", SESSION_ID)
                put("credential", "vps")
            },
            context(TEST_ROLE_ID))

        assertFalse(result.isError)
        val details = assertNotNull(result.details).jsonObject
        assertEquals(TerminalStatus.ATTACHED.name, details.getValue("status").jsonPrimitive.content)
        assertTrue(details.getValue("attached").jsonPrimitive.content.toBoolean())
        assertEquals("vps", details.getValue("attachProfile").jsonPrimitive.content)
        assertEquals("askpass", details.getValue("attachChannel").jsonPrimitive.content)
        assertEquals("ATTACHED", details.getValue("transition").jsonPrimitive.content)
        assertEquals(JsonNull, details.getValue("attachExitCode"))
        assertFalse(result.output.orEmpty().contains("secret"))
        assertFalse(result.output.orEmpty().contains(REFERENCE))
    }

    /** Verifies that a profile exit is reported through the transition and its own exit code. */
    @Test
    fun `profile exit details report the attach exit code`(): Unit = runBlocking {
        coEvery { manager.poll(SESSION_ID, any(), any(), any()) } returns
            snapshot(
                status = TerminalStatus.QUIET,
                transition = AttachTransition.PROFILE_EXITED,
                attachExitCode = 7,
            ).right()

        val result = tool.execute(
            input("poll") { put("session_id", SESSION_ID) },
            context(TEST_ROLE_ID))

        assertFalse(result.isError)
        val details = assertNotNull(result.details).jsonObject
        assertEquals("PROFILE_EXITED", details.getValue("transition").jsonPrimitive.content)
        assertEquals(7, details.getValue("attachExitCode").jsonPrimitive.content.toInt())
        assertFalse(details.getValue("attached").jsonPrimitive.content.toBoolean())
        assertNull(details["attachProfile"]?.takeIf { it != JsonNull })
    }

    /** Verifies that the attach call states the attachment in its output, where the model can read it. */
    @Test
    fun `attach output states the attached profile and channel`(): Unit = runBlocking {
        coEvery { manager.attach(SESSION_ID, "vps", any(), any(), any(), any()) } returns
            snapshot(status = TerminalStatus.ATTACHED, transition = AttachTransition.ATTACHED).right()

        val result = tool.execute(
            input("attach") {
                put("session_id", SESSION_ID)
                put("credential", "vps")
            },
            context(TEST_ROLE_ID))

        assertEquals(
            "status: ATTACHED\n" +
                "session_id: $SESSION_ID\n" +
                "attached: true (profile vps, channel askpass; profile attached on this call)\n" +
                "--- output ---\n" +
                "connected\n",
            result.output,
        )
    }

    /**
     * Verifies that a poll that observes the profile exit states the detached session, the profile's
     * exit code and why the session went back to its shell.
     */
    @Test
    fun `profile exit output states the reason and exit code`(): Unit = runBlocking {
        coEvery { manager.poll(SESSION_ID, any(), any(), any()) } returns
            snapshot(
                status = TerminalStatus.QUIET,
                transition = AttachTransition.PROFILE_EXITED,
                attachExitCode = 7,
            ).right()

        val result = tool.execute(input("poll") { put("session_id", SESSION_ID) }, context(TEST_ROLE_ID))

        assertEquals(
            "status: QUIET\n" +
                "session_id: $SESSION_ID\n" +
                "attached: false (profile exited on its own with exit code 7; input now reaches the shell)\n" +
                "--- output ---\n" +
                "connected\n",
            result.output,
        )
    }

    /** Verifies that an explicit detach states the detached session without an exit code. */
    @Test
    fun `detach output states the detached session without an exit code`(): Unit = runBlocking {
        coEvery { manager.detach(SESSION_ID, any(), any(), any()) } returns
            snapshot(status = TerminalStatus.QUIET, transition = AttachTransition.DETACHED).right()

        val result = tool.execute(
            input("detach") { put("session_id", SESSION_ID) },
            context(TEST_ROLE_ID),
        )

        assertEquals(
            "status: QUIET\n" +
                "session_id: $SESSION_ID\n" +
                "attached: false (profile detached on this call; input now reaches the shell)\n" +
                "--- output ---\n" +
                "connected\n",
            result.output,
        )
        assertFalse(result.output.orEmpty().contains("exit code"))
    }

    /** Verifies that a read taken while still attached reports the live attachment. */
    @Test
    fun `poll while attached reports the live attachment`(): Unit = runBlocking {
        coEvery { manager.poll(SESSION_ID, any(), any(), any()) } returns
            snapshot(status = TerminalStatus.ATTACHED, attached = true).right()

        val result = tool.execute(input("poll") { put("session_id", SESSION_ID) }, context(TEST_ROLE_ID))

        assertEquals(
            "status: ATTACHED\n" +
                "session_id: $SESSION_ID\n" +
                "attached: true (profile vps, channel askpass)\n" +
                "--- output ---\n" +
                "connected\n",
            result.output,
        )
    }

    /**
     * Verifies that a session that never attaches keeps the exact output a reader parsed before attach
     * state was reported in the header.
     */
    @Test
    fun `session that never attaches keeps the plain output`(): Unit = runBlocking {
        coEvery { manager.poll(SESSION_ID, any(), any(), any()) } returns
            snapshot(status = TerminalStatus.QUIET, attached = false).right()

        val result = tool.execute(input("poll") { put("session_id", SESSION_ID) }, context(TEST_ROLE_ID))

        assertEquals(
            "status: QUIET\n" +
                "session_id: $SESSION_ID\n" +
                "--- output ---\n" +
                "connected\n",
            result.output,
        )
    }

    /** Verifies that an unavailable credential account is a permission problem, not a bad argument. */
    @Test
    fun `missing credential account maps to permission denied`(): Unit = runBlocking {
        coEvery { manager.attach(SESSION_ID, "vps", any(), any(), any(), any()) } returns
            TerminalSessionError.CredentialSupportUnavailable(
                "worker.commandIsolation.enabled is off"
            ).left()

        val result = tool.execute(
            input("attach") {
                put("session_id", SESSION_ID)
                put("credential", "vps")
            },
            context(TEST_ROLE_ID))

        assertTrue(result.isError)
        assertEquals(BuiltInToolExecutionError.PERMISSION_DENIED, result.errorCode)
    }

    /** Verifies that an unusable secret maps to the credential-specific error code. */
    @Test
    fun `undecryptable secret maps to credential unavailable`(): Unit = runBlocking {
        coEvery { manager.attach(SESSION_ID, "vps", any(), any(), any(), any()) } returns
            TerminalSessionError.CredentialUnavailable("vps", "secret '$REFERENCE' could not be used").left()

        val result = tool.execute(
            input("attach") {
                put("session_id", SESSION_ID)
                put("credential", "vps")
            },
            context(TEST_ROLE_ID))

        assertTrue(result.isError)
        assertEquals(BuiltInToolExecutionError.CREDENTIAL_UNAVAILABLE, result.errorCode)
        // The profile and reference may be named; a value never is.
        val message = assertNotNull(result.errorMessage)
        assertTrue(message.contains("vps"))
        assertFalse(message.contains("hunter2"))
    }

    /** Verifies that an unknown profile is an input error listing the assigned names. */
    @Test
    fun `unknown profile maps to invalid input with the assigned names`(): Unit = runBlocking {
        coEvery { manager.attach(SESSION_ID, "missing", any(), any(), any(), any()) } returns
            TerminalSessionError.UnknownProfile("missing", listOf("github", "vps")).left()

        val result = tool.execute(
            input("attach") {
                put("session_id", SESSION_ID)
                put("credential", "missing")
            },
            context(TEST_ROLE_ID))

        assertInvalidInput(result)
        val details = validationErrors(result)
        assertTrue(details.contains("missing"))
        assertTrue(details.contains("github"))
        assertTrue(details.contains("vps"))
    }

    /** Verifies that state mistakes are input errors with guidance rather than execution failures. */
    @Test
    fun `state errors map to invalid input`(): Unit = runBlocking {
        coEvery { manager.attach(SESSION_ID, "vps", any(), any(), any(), any()) } returns
            TerminalSessionError.AlreadyAttached(SESSION_ID, "vps").left()
        coEvery { manager.attach(SESSION_ID, "other", any(), any(), any(), any()) } returns
            TerminalSessionError.NotQuiet(SESSION_ID).left()
        coEvery { manager.detach(SESSION_ID, any(), any(), any()) } returns
            TerminalSessionError.NotAttached(SESSION_ID).left()

        assertInvalidInput(
            tool.execute(
                input("attach") {
                    put("session_id", SESSION_ID)
                    put("credential", "vps")
                },
                context(TEST_ROLE_ID),
            )
        )
        assertInvalidInput(
            tool.execute(
                input("attach") {
                    put("session_id", SESSION_ID)
                    put("credential", "other")
                },
                context(TEST_ROLE_ID),
            )
        )
        assertInvalidInput(
            tool.execute(
                input("detach") { put("session_id", SESSION_ID) },
                context(TEST_ROLE_ID),
            )
        )
    }

    /** Verifies that a failed spawn is an execution failure. */
    @Test
    fun `spawn failure maps to execution failed`(): Unit = runBlocking {
        coEvery { manager.attach(SESSION_ID, "vps", any(), any(), any(), any()) } returns
            TerminalSessionError.SpawnFailed("test").left()

        val result = tool.execute(
            input("attach") {
                put("session_id", SESSION_ID)
                put("credential", "vps")
            },
            context(TEST_ROLE_ID))

        assertTrue(result.isError)
        assertEquals(BuiltInToolExecutionError.EXECUTION_FAILED, result.errorCode)
    }

    /** Verifies that an unlinked agent role is a permission problem naming the profile. */
    @Test
    fun `unauthorized profile maps to permission denied naming the profile`(): Unit = runBlocking {
        coEvery { manager.attach(SESSION_ID, "vps", any(), any(), any(), any()) } returns
            TerminalSessionError.ProfileNotAuthorized("vps", "profile 'vps' is not linked to agent role 9")
                .left()

        val result = tool.execute(
            input("attach") {
                put("session_id", SESSION_ID)
                put("credential", "vps")
            },
            context(TEST_ROLE_ID),
        )

        assertTrue(result.isError)
        assertEquals(BuiltInToolExecutionError.PERMISSION_DENIED, result.errorCode)
        val message = assertNotNull(result.errorMessage)
        assertTrue(message.contains("vps"))
    }

    /** Verifies that the acting role reaches the manager unchanged. */
    @Test
    fun `attach forwards the acting agent role to the manager`(): Unit = runBlocking {
        coEvery { manager.attach(SESSION_ID, "vps", any(), any(), any(), any()) } returns
            snapshot(status = TerminalStatus.ATTACHED, transition = AttachTransition.ATTACHED).right()

        tool.execute(
            input("attach") {
                put("session_id", SESSION_ID)
                put("credential", "vps")
            },
            context(TEST_ROLE_ID),
        )

        coVerify(exactly = 1) { manager.attach(SESSION_ID, "vps", any(), any(), any(), TEST_ROLE_ID) }
    }

    /**
     * Verifies that a call without an agent role serves the non-credential actions but refuses
     * `attach`, so a caller that cannot supply a role fails closed instead of attaching an unowned
     * profile.
     */
    @Test
    fun `a call without an agent role refuses attach but serves other actions`(): Unit = runBlocking {
        coEvery { manager.poll(SESSION_ID, any(), any(), any()) } returns
            snapshot(status = TerminalStatus.QUIET, attached = false).right()
        coEvery { manager.attach(SESSION_ID, "vps", any(), any(), any(), null) } returns
            TerminalSessionError.ProfileNotAuthorized(
                "vps",
                "the execution authorization carries no agent role",
            ).left()

        val poll = tool.execute(input("poll") { put("session_id", SESSION_ID) }, context())
        assertFalse(poll.isError)

        val attach = tool.execute(
            input("attach") {
                put("session_id", SESSION_ID)
                put("credential", "vps")
            },
            context(),
        )
        assertTrue(attach.isError)
        assertEquals(BuiltInToolExecutionError.PERMISSION_DENIED, attach.errorCode)
        // A context without a role hands the manager null, which is what makes attach fail closed.
        coVerify(exactly = 1) { manager.attach(SESSION_ID, "vps", any(), any(), any(), null) }
    }

    /** Verifies that the catalog advertises the new actions and the attach-only property. */
    @Test
    fun `catalog advertises attach and detach`() {
        val schema = BuiltInToolCatalog.specFor("terminal_session")!!.inputSchema

        assertEquals(
            setOf("start", "send", "poll", "attach", "detach", "close"),
            schema.getValue("properties").jsonObject.getValue("action")
                .jsonObject.getValue("enum")
                .let { enum ->
                    (enum as kotlinx.serialization.json.JsonArray).map { it.jsonPrimitive.content }.toSet()
                },
        )
        assertTrue(schema.getValue("properties").jsonObject.containsKey("credential"))
        assertEquals(listOf("action"), schema.getValue("required").let { required ->
            (required as kotlinx.serialization.json.JsonArray).map { it.jsonPrimitive.content }
        })
    }

    /**
     * @param action Action value to place in the input.
     * @param extra Additional fields.
     * @return Tool input object.
     */
    private fun input(action: String, extra: JsonObjectBuilder.() -> Unit = {}): JsonObject = buildJsonObject {
        put("action", action)
        extra()
    }

    /**
     * @param result Tool result to inspect.
     * @return Validation messages collected from `errorDetails`.
     */
    private fun validationErrors(result: BuiltInToolExecutionResult): String {
        assertTrue(result.isError)
        assertEquals(BuiltInToolExecutionError.INVALID_INPUT, result.errorCode)
        return assertNotNull(result.errorDetails)
    }

    /**
     * @param result Tool result expected to be an input error.
     */
    private fun assertInvalidInput(result: BuiltInToolExecutionResult) {
        validationErrors(result)
    }

    /**
     * @param status Status to report.
     * @param transition Target change to report.
     * @param attachExitCode Profile exit code to report.
     * @param attached Whether a profile is the active target.
     * @param profile Name of the active profile, or null when none is attached.
     * @param channel Channel of the active profile, or null when none is attached.
     * @return Snapshot for the mocked manager.
     */
    private fun snapshot(
        status: TerminalStatus,
        transition: AttachTransition = AttachTransition.NONE,
        attachExitCode: Int? = null,
        attached: Boolean =
            transition != AttachTransition.PROFILE_EXITED && transition != AttachTransition.DETACHED,
        profile: String? = if (attached) "vps" else null,
        channel: CredentialChannel? = if (attached) CredentialChannel.ASKPASS else null,
    ): TerminalSnapshot = TerminalSnapshot(
        sessionId = SESSION_ID,
        status = status,
        output = "connected\n",
        exitCode = null,
        hasNewOutput = true,
        quietForMillis = 42,
        truncated = false,
        pendingBytes = 0,
        droppedBytes = 0,
        bufferBytes = 10,
        idleTimeoutSeconds = 60,
        tuiDetected = false,
        attached = attached,
        attachProfile = profile,
        attachChannel = channel,
        attachExitCode = attachExitCode,
        transition = transition,
    )

    /**
     * @param agentRoleId Acting agent role, or null for a call that carries none.
     * @return Execution context using a temporary workspace.
     */
    private fun context(agentRoleId: Long? = null): BuiltInToolExecutionContext = testExecutionContext(
        workspace = java.nio.file.Path.of("/tmp"),
        defaultCommandTimeoutSeconds = 10,
        defaultSearchTimeoutSeconds = 5,
        agentRoleId = agentRoleId,
    )

    private companion object {
        /** Session handle accepted by the documented pattern. */
        const val SESSION_ID = "aB3xY9"

        /** Secret reference a profile would use; its value must never appear in a result. */
        const val REFERENCE = "vps_password"

        /** Agent role the tests act as. */
        const val TEST_ROLE_ID = 31L
    }
}
