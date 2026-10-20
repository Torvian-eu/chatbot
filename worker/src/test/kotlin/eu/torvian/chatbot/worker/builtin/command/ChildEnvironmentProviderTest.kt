package eu.torvian.chatbot.worker.builtin.command

import eu.torvian.chatbot.worker.config.CommandEnvironmentConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import org.apache.logging.log4j.LogManager

/**
 * Verifies the environment handed to built-in-tool children for both isolation states.
 *
 * Every case passes an explicit parent map so the tests never read the real process environment.
 */
class ChildEnvironmentProviderTest {

    /** Parent environment used by the tests, containing both allowlisted and non-allowlisted names. */
    private val parent = mapOf(
        "PATH" to "/usr/bin",
        "LANG" to "en_US.UTF-8",
        "SECRET_TOKEN" to "super-secret",
        "HOME" to "/home/torvian",
        "USER" to "torvian",
        "LOGNAME" to "torvian",
    )

    /** Verifies that only allowlisted names reach the child. */
    @Test
    fun `allowlist forwards only the listed names`() {
        val provider = AllowlistChildEnvironmentProvider(
            config = CommandEnvironmentConfig(listOf("PATH", "LANG"), set = emptyMap()),
            isolatedUser = "agent",
            isolatedHome = "/home/agent",
            parent = parent,
        )

        val environment = provider.build()

        assertEquals("/usr/bin", environment["PATH"])
        assertEquals("en_US.UTF-8", environment["LANG"])
        assertNull(environment["SECRET_TOKEN"])
    }

    /** Verifies that allowlisted names absent from the worker are omitted instead of set empty. */
    @Test
    fun `allowlist omits names missing from the worker environment`() {
        val provider = AllowlistChildEnvironmentProvider(
            config = CommandEnvironmentConfig(listOf("PATH", "HTTP_PROXY"), set = emptyMap()),
            isolatedUser = "agent",
            isolatedHome = "/home/agent",
            parent = parent,
        )

        val environment = provider.build()

        assertEquals(setOf("PATH", "HOME", "USER", "LOGNAME"), environment.keys)
    }

    /** Verifies that the wildcard entry forwards the entire worker environment. */
    @Test
    fun `wildcard allowlist forwards the whole environment`() {
        val provider = AllowlistChildEnvironmentProvider(
            config = CommandEnvironmentConfig(listOf("*"), set = emptyMap()),
            isolatedUser = "agent",
            isolatedHome = "/home/agent",
            parent = parent,
        )

        val environment = provider.build()

        assertEquals("super-secret", environment["SECRET_TOKEN"])
        assertEquals("/usr/bin", environment["PATH"])
    }

    /** Verifies that identity variables describe the isolated account, not the worker account. */
    @Test
    fun `identity variables describe the isolated account`() {
        val provider = AllowlistChildEnvironmentProvider(
            config = CommandEnvironmentConfig(listOf("PATH", "HOME", "USER", "LOGNAME"), set = emptyMap()),
            isolatedUser = "agent",
            isolatedHome = "/home/agent",
            parent = parent,
        )

        val environment = provider.build()

        assertEquals("/home/agent", environment["HOME"])
        assertEquals("agent", environment["USER"])
        assertEquals("agent", environment["LOGNAME"])
    }

    /** Verifies that the child's home comes from the account database rather than the account name. */
    @Test
    fun `identity home is the resolved account home`() {
        val provider = AllowlistChildEnvironmentProvider(
            config = CommandEnvironmentConfig(listOf("PATH"), set = emptyMap()),
            isolatedUser = "agent",
            isolatedHome = "/srv/agent-home",
            parent = parent,
        )

        assertEquals("/srv/agent-home", provider.build()["HOME"])
    }

    /** Verifies that a configured value reaches the child even when the worker does not define it. */
    @Test
    fun `configured values reach the child`() {
        val provider = AllowlistChildEnvironmentProvider(
            config = CommandEnvironmentConfig(
                allowPassthrough = listOf("PATH"),
                set = mapOf("GIT_CONFIG_COUNT" to "1", "GIT_CONFIG_VALUE_0" to "*"),
            ),
            isolatedUser = "agent",
            isolatedHome = "/home/agent",
            parent = parent,
        )

        val environment = provider.build()

        assertEquals("1", environment["GIT_CONFIG_COUNT"])
        assertEquals("*", environment["GIT_CONFIG_VALUE_0"])
    }

    /** Verifies that a configured value replaces one the worker environment also provides. */
    @Test
    fun `configured values override a forwarded value`() {
        val provider = AllowlistChildEnvironmentProvider(
            config = CommandEnvironmentConfig(
                allowPassthrough = listOf("PATH", "NPM_CONFIG_CACHE"),
                set = mapOf("NPM_CONFIG_CACHE" to "/home/agent/.npm"),
            ),
            isolatedUser = "agent",
            isolatedHome = "/home/agent",
            parent = parent + ("NPM_CONFIG_CACHE" to "/app/cache/npm"),
        )

        assertEquals("/home/agent/.npm", provider.build()["NPM_CONFIG_CACHE"])
    }

    /**
     * Verifies that a configured value cannot redirect the child away from its own account.
     *
     * The identity variables always describe the isolated account, so a configured `HOME` is ignored
     * rather than pointing the child at a directory that account does not own.
     */
    @Test
    fun `identity variables win over configured values`() {
        val provider = AllowlistChildEnvironmentProvider(
            config = CommandEnvironmentConfig(
                allowPassthrough = listOf("PATH"),
                set = mapOf("HOME" to "/home/torvian", "USER" to "torvian", "LOGNAME" to "torvian"),
            ),
            isolatedUser = "agent",
            isolatedHome = "/home/agent",
            parent = parent,
        )

        val environment = provider.build()

        assertEquals("/home/agent", environment["HOME"])
        assertEquals("agent", environment["USER"])
        assertEquals("agent", environment["LOGNAME"])
    }

    /** Verifies that the inherit provider returns the worker environment verbatim. */
    @Test
    fun `inherit provider returns the parent environment unchanged`() {
        assertEquals(parent, InheritChildEnvironmentProvider(parent).build())
    }

    /** Verifies that the selector follows the resolved isolation state rather than configuration. */
    @Test
    fun `selector follows the resolved isolation state`() {
        val config = CommandEnvironmentConfig(listOf("PATH"), set = emptyMap())

        val isolated = childEnvironmentProvider(
            config,
            CommandIsolationResolution.isolated(
                helperPath = "/helper",
                user = "agent",
                home = "/home/agent",
                logger = LogManager.getLogger(ChildEnvironmentProviderTest::class.java),
            ),
        )
        val unisolated = childEnvironmentProvider(config, CommandIsolationResolution.none())

        assertIs<AllowlistChildEnvironmentProvider>(isolated)
        assertIs<InheritChildEnvironmentProvider>(unisolated)
    }
}
