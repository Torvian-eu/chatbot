package eu.torvian.chatbot.common.models.api.credential

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Truth table for the shared credential-profile validation rules.
 */
class CredentialProfileRulesTest {

    /**
     * Ensures a well-formed askpass profile with the documented OpenSSH recipe is accepted.
     */
    @Test
    fun `askpass profile with the openssh recipe is accepted`() {
        val violations = CredentialProfileRules.validate(
            profile(
                channel = CredentialChannel.ASKPASS,
                askpassVariables = listOf("SSH_ASKPASS"),
                environmentVariables = mapOf(
                    "SSH_ASKPASS_REQUIRE" to "force",
                    "DISPLAY" to ":0"
                )
            )
        )

        assertEquals(emptyList(), violations)
    }

    /**
     * Ensures the askpass channel requires at least one variable name.
     */
    @Test
    fun `askpass channel requires askpass variables`() {
        val violations = CredentialProfileRules.validate(profile(channel = CredentialChannel.ASKPASS))

        assertTrue(violations.any { "askpassVariables" in it })
    }

    /**
     * Ensures askpass variable names are forbidden on the env and file channels.
     */
    @Test
    fun `askpass variables are rejected outside the askpass channel`() {
        listOf(
            profile(channel = CredentialChannel.ENV, envName = "TOKEN", askpassVariables = listOf("TOKEN2")),
            profile(
                channel = CredentialChannel.FILE,
                envName = "TOKEN_PATH",
                fileName = "token",
                askpassVariables = listOf("TOKEN2")
            )
        ).forEach { candidate ->
            val violations = CredentialProfileRules.validate(candidate)
            assertTrue(violations.any { "askpassVariables must be empty" in it }, "expected rejection: $violations")
        }
    }

    /**
     * Ensures the env channel requires a variable name and refuses a file name.
     */
    @Test
    fun `env channel requires envName and forbids fileName`() {
        val missing = CredentialProfileRules.validate(profile(channel = CredentialChannel.ENV))
        val withFile = CredentialProfileRules.validate(
            profile(channel = CredentialChannel.ENV, envName = "TOKEN", fileName = "token")
        )

        assertTrue(missing.any { "envName is required" in it })
        assertTrue(withFile.any { "fileName must be omitted" in it })
    }

    /**
     * Ensures the file channel requires a single safe path component.
     */
    @Test
    fun `file channel requires a single path component`() {
        val nested = CredentialProfileRules.validate(
            profile(
                channel = CredentialChannel.FILE,
                envName = "TOKEN_PATH",
                fileName = "../escape"
            )
        )

        assertNotNull(nested.firstOrNull { "fileName" in it })
    }

    /**
     * Ensures a file name that no filesystem could hold is refused where it is authored.
     *
     * A rejected name is a save-time message; accepted, it would only fail the helper's create call at
     * `attach`, after the operator had moved on.
     */
    @Test
    fun `file name length and control characters are refused`() {
        val tooLong = CredentialProfileRules.validate(
            profile(
                channel = CredentialChannel.FILE,
                envName = "TOKEN_PATH",
                fileName = "a".repeat(CredentialProfileRules.MAX_FILE_NAME_LENGTH + 1)
            )
        )
        val control = CredentialProfileRules.validate(
            profile(
                channel = CredentialChannel.FILE,
                envName = "TOKEN_PATH",
                fileName = "token\u0007name"
            )
        )
        val longest = CredentialProfileRules.validate(
            profile(
                channel = CredentialChannel.FILE,
                envName = "TOKEN_PATH",
                fileName = "a".repeat(CredentialProfileRules.MAX_FILE_NAME_LENGTH)
            )
        )

        assertTrue(tooLong.any { "fileName must be at most" in it })
        assertTrue(control.any { "fileName must not contain control characters" in it })
        assertEquals(emptyList(), longest)
    }

    /**
     * Ensures reserved names are refused in every profile environment surface.
     */
    @Test
    fun `reserved names are refused everywhere`() {
        val inMap = CredentialProfileRules.validate(
            profile(
                channel = CredentialChannel.ENV,
                envName = "TOKEN",
                environmentVariables = mapOf("HOME" to "/x")
            )
        )
        val inAskpass = CredentialProfileRules.validate(
            profile(channel = CredentialChannel.ASKPASS, askpassVariables = listOf("TORVIAN_ASKPASS_FILE"))
        )
        val asEnvName = CredentialProfileRules.validate(
            profile(channel = CredentialChannel.ENV, envName = "TERM")
        )

        assertTrue(inMap.any { "reserved" in it })
        assertTrue(inAskpass.any { "reserved" in it })
        assertTrue(asEnvName.any { "reserved" in it })
    }

    /**
     * Ensures neither environment surface may redefine a key the deployment owns.
     *
     * `environmentVariables` is merged after the deployment's map, so an entry there would win for the
     * profile's child; both surfaces are therefore held to the same rule.
     */
    @Test
    fun `deployment environment keys are refused on both surfaces`() {
        val asEnvName = CredentialProfileRules.validate(
            fields = profile(channel = CredentialChannel.ENV, envName = "GIT_CONFIG_COUNT"),
            deploymentEnvironmentKeys = setOf("GIT_CONFIG_COUNT")
        )
        val inEnvironment = CredentialProfileRules.validate(
            fields = profile(
                channel = CredentialChannel.ENV,
                envName = "TOKEN",
                environmentVariables = mapOf("GIT_CONFIG_COUNT" to "0")
            ),
            deploymentEnvironmentKeys = setOf("GIT_CONFIG_COUNT")
        )
        val unaffected = CredentialProfileRules.validate(
            fields = profile(
                channel = CredentialChannel.ENV,
                envName = "TOKEN",
                environmentVariables = mapOf("OWN_TOKEN_OPTION" to "1")
            ),
            deploymentEnvironmentKeys = setOf("GIT_CONFIG_COUNT")
        )

        assertTrue(asEnvName.any { "collides" in it })
        assertTrue(inEnvironment.any { "environmentVariables 'GIT_CONFIG_COUNT' collides" in it })
        assertEquals(emptyList(), unaffected)
    }

    /**
     * Ensures one variable has exactly one author across the two profile surfaces.
     */
    @Test
    fun `envName and askpass names must not repeat inside the profile environment map`() {
        val repeatedInMap = CredentialProfileRules.validate(
            profile(
                channel = CredentialChannel.ENV,
                envName = "TOKEN",
                environmentVariables = mapOf("TOKEN" to "x")
            )
        )
        val repeatedInAskpass = CredentialProfileRules.validate(
            profile(
                channel = CredentialChannel.ASKPASS,
                askpassVariables = listOf("SSH_ASKPASS"),
                environmentVariables = mapOf("SSH_ASKPASS" to "/tmp/x")
            )
        )

        assertTrue(repeatedInMap.any { "environmentVariables" in it })
        assertTrue(repeatedInAskpass.any { "environmentVariables" in it })
    }

    /**
     * Ensures the environment entry cap and value length are enforced.
     */
    @Test
    fun `environment entry limits are enforced`() {
        val tooMany = (0..CredentialProfileRules.MAX_ENVIRONMENT_ENTRIES).associate { index ->
            "VAR_$index" to "v"
        }
        val violations = CredentialProfileRules.validate(
            profile(channel = CredentialChannel.ENV, envName = "TOKEN", environmentVariables = tooMany)
        )
        val longValue = CredentialProfileRules.validate(
            profile(
                channel = CredentialChannel.ENV,
                envName = "TOKEN",
                environmentVariables = mapOf(
                    "LONG" to "x".repeat(CredentialProfileRules.MAX_ENVIRONMENT_VALUE_LENGTH + 1)
                )
            )
        )

        assertTrue(violations.any { "at most" in it })
        assertTrue(longValue.any { "at most" in it })
    }

    /**
     * Ensures a command is a single executable and never a shell line.
     */
    @Test
    fun `command must be a single executable`() {
        val violations = CredentialProfileRules.validate(
            profile(channel = CredentialChannel.ENV, envName = "TOKEN", command = "git status")
        )

        assertTrue(violations.any { "command must be" in it })
    }

    /**
     * Ensures per-name validation is callable on its own by the settings UI.
     */
    @Test
    fun `variable name validation reports reserved and malformed names`() {
        assertNull(CredentialProfileRules.validateVariableName("GH_TOKEN", "field"))
        assertNotNull(CredentialProfileRules.validateVariableName("1BAD", "field"))
        assertNotNull(CredentialProfileRules.validateVariableName("HOME", "field"))
    }

    /**
     * Ensures a reference authored on its own is held to the rule a profile's `secretRef` follows.
     */
    @Test
    fun `reference validation matches the profile rule`() {
        assertNull(CredentialProfileRules.validateReference("vps_password"))
        assertNotNull(CredentialProfileRules.validateReference(""))
        assertNotNull(CredentialProfileRules.validateReference("a".repeat(CredentialProfileRules.MAX_NAME_LENGTH + 1)))
        val controlCharacter = assertNotNull(CredentialProfileRules.validateReference("vps\npassword"))
        assertTrue("control characters" in controlCharacter)
    }

    /**
     * Builds a profile whose only invalid parts are the ones a case changes.
     *
     * @param channel Delivery channel under test.
     * @param command Executable the profile starts.
     * @param envName Variable receiving the value or path.
     * @param fileName File name for the file channel.
     * @param askpassVariables Names receiving the askpass script path.
     * @param environmentVariables Supplementary non-secret variables.
     * @return Profile fields built from the arguments.
     */
    private fun profile(
        channel: CredentialChannel,
        command: String = "/bin/sh",
        envName: String? = null,
        fileName: String? = null,
        askpassVariables: List<String> = emptyList(),
        environmentVariables: Map<String, String> = emptyMap(),
    ): CredentialProfileFields = CredentialProfileFields(
        workerId = 1L,
        name = "profile",
        secretRef = "secret",
        command = command,
        args = listOf("-c", "true"),
        channel = channel,
        envName = envName,
        fileName = fileName,
        environmentVariables = environmentVariables,
        askpassVariables = askpassVariables,
    )
}
