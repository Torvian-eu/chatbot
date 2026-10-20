package eu.torvian.chatbot.worker.config

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests direct assembly of nullable worker configuration DTOs into strict runtime configuration.
 */
class ConfigAssemblerTest {

    /**
     * Verifies that a fully populated DTO is converted to the strict domain model without losing values.
     */
    @Test
    fun `assembles complete dto into domain configuration`() {
        val configDir = createTempDirectory("config-assembler-test")
        val result = validDto().toDomain(configDir)

        assertTrue(result.isRight())
        val config = result.getOrNull()
        assertEquals(false, config?.setupRequired)
        assertEquals("https://example.test/api", config?.worker?.server?.baseUrl)
        assertEquals("worker-1", config?.worker?.identity?.uid)
        assertEquals("Worker One", config?.worker?.identity?.displayName)
        assertEquals("fingerprint-1", config?.worker?.identity?.certificateFingerprint)
        assertEquals("certificate-pem-1", config?.worker?.identity?.certificatePem)
        assertEquals("./secrets.json", config?.worker?.storage?.secretsJsonPath)
        assertEquals("./token.json", config?.worker?.storage?.tokenFilePath)
        assertEquals(30, config?.worker?.auth?.refreshSkewSeconds)
        // Workspace path should be resolved relative to configDir
        assertEquals(configDir.resolve("./workspace").normalize().toString(), config?.worker?.workspace?.path)

        val signer = config?.worker?.trustedSigners?.single()
        assertEquals("signer-1", signer?.signerId)
        assertContentEquals(byteArrayOf(1, 2, 3), signer?.publicKey)
        assertEquals(listOf("mcp:read"), signer?.permissions)
    }

    /**
     * Verifies nullable optional sections use runtime-safe defaults during assembly.
     */
    @Test
    fun `uses defaults for omitted optional configuration`() {
        val configDir = createTempDirectory("config-assembler-test")
        val result = validDto(
            setup = null,
            identity = IdentityConfigDto(
                uid = "worker-1",
                displayName = "   ",
                certificateFingerprint = "fingerprint-1",
                certificatePem = "certificate-pem-1"
            ),
            auth = null,
            trustedSigners = null
        ).toDomain(configDir)

        assertTrue(result.isRight())
        val config = result.getOrNull()
        assertEquals(true, config?.setupRequired)
        assertEquals("my-worker", config?.worker?.identity?.displayName)
        assertEquals(60, config?.worker?.auth?.refreshSkewSeconds)
        assertEquals(emptyList(), config?.worker?.trustedSigners)
    }

    /**
     * Verifies assembly fails with path-specific errors when mandatory DTO groups are absent.
     */
    @Test
    fun `rejects missing required config groups`() {
        assertInvalid(AppConfigDto(), "Missing required config group: worker")
        assertInvalid(validDto(server = null), "Missing required config group: worker.server")
        assertInvalid(validDto(identity = null), "Missing required config group: worker.identity")
        assertInvalid(validDto(storage = null), "Missing required config group: worker.storage")
    }

    /**
     * Verifies scalar validation catches invalid URLs, blank required strings, and negative timing values.
     */
    @Test
    fun `rejects invalid scalar configuration values`() {
        assertInvalid(validDto(server = ServerConfigDto(baseUrl = "ftp://example.test")), "scheme must be http or https")
        assertInvalid(
            validDto(storage = StorageConfigDto(secretsJsonPath = "./secrets.json", tokenFilePath = "")),
            "worker.storage.tokenFilePath must not be blank"
        )
        assertInvalid(validDto(auth = AuthConfigDto(refreshSkewSeconds = -1)), "worker.auth.refreshSkewSeconds must be >= 0")
    }

    /**
     * Verifies trusted signer validation fails before invalid trust-store entries reach runtime services.
     */
    @Test
    fun `rejects invalid trusted signer entries`() {
        assertInvalid(
            validDto(trustedSigners = listOf(TrustedSignerDto("", "AQID", emptyList()))),
            "worker.trustedSigners[0].signerId must not be blank"
        )
        assertInvalid(
            validDto(trustedSigners = listOf(TrustedSignerDto("signer-1", "not-base64!", emptyList()))),
            "worker.trustedSigners[0].publicKeyBase64 must be valid Base64"
        )
    }

    /**
     * Verifies that the command-isolation group resolves to the documented defaults when omitted.
     *
     * Isolation is opt-in, so an omitted switch leaves it off: the mechanism needs a helper and a
     * separate account that only a prepared host provides.
     */
    @Test
    fun `uses command isolation defaults when the group is omitted`() {
        val configDir = createTempDirectory("config-assembler-test")
        val result = validDto(commandIsolation = null).toDomain(configDir)

        assertTrue(result.isRight())
        val isolation = result.getOrNull()?.worker?.commandIsolation
        assertEquals(false, isolation?.enabled)
        assertEquals("agent", isolation?.user)
        assertEquals("/usr/local/bin/run-as-agent", isolation?.helperPath)
        assertEquals(listOf("PATH", "LANG", "LC_ALL"), isolation?.environment?.allowPassthrough)
        // The default set carries git's ownership exemption, so an isolated command can use a
        // repository that the worker account owns without any system-wide git configuration.
        assertEquals(
            mapOf(
                "GIT_CONFIG_COUNT" to "1",
                "GIT_CONFIG_KEY_0" to "safe.directory",
                "GIT_CONFIG_VALUE_0" to "*",
            ),
            isolation?.environment?.set,
        )
    }

    /**
     * Verifies that a configured environment value replaces the default set as a whole.
     */
    @Test
    fun `accepts a configured command isolation environment set`() {
        val configDir = createTempDirectory("config-assembler-test")
        val result = validDto(
            commandIsolation = CommandIsolationConfigDto(
                environment = CommandEnvironmentConfigDto(set = mapOf("NO_PROXY" to "localhost"))
            )
        ).toDomain(configDir)

        assertTrue(result.isRight())
        assertEquals(
            mapOf("NO_PROXY" to "localhost"),
            result.getOrNull()?.worker?.commandIsolation?.environment?.set
        )
    }

    /**
     * Verifies that an empty configured set removes the default entries instead of adding to them.
     */
    @Test
    fun `accepts an empty command isolation environment set`() {
        val configDir = createTempDirectory("config-assembler-test")
        val result = validDto(
            commandIsolation = CommandIsolationConfigDto(
                environment = CommandEnvironmentConfigDto(set = emptyMap())
            )
        ).toDomain(configDir)

        assertTrue(result.isRight())
        assertEquals(emptyMap(), result.getOrNull()?.worker?.commandIsolation?.environment?.set)
    }

    /**
     * Verifies that a configured environment value with an unparsable name is rejected.
     */
    @Test
    fun `rejects command isolation environment set entry that is not an environment variable name`() {
        assertInvalid(
            validDto(
                commandIsolation = CommandIsolationConfigDto(
                    environment = CommandEnvironmentConfigDto(set = mapOf("NO PROXY" to "localhost"))
                )
            ),
            "worker.commandIsolation.environment.set['NO PROXY'] must be an environment variable name"
        )
    }

    /**
     * Verifies that a configured environment value libc could not pass to a process is rejected.
     */
    @Test
    fun `rejects command isolation environment set value containing a NUL byte`() {
        assertInvalid(
            validDto(
                commandIsolation = CommandIsolationConfigDto(
                    environment = CommandEnvironmentConfigDto(set = mapOf("NO_PROXY" to "a\u0000b"))
                )
            ),
            "worker.commandIsolation.environment.set['NO_PROXY'] must not contain a NUL byte"
        )
    }

    /**
     * Verifies that a blank isolated account name is rejected before the worker attempts a probe.
     */
    @Test
    fun `rejects blank command isolation user`() {
        assertInvalid(
            validDto(commandIsolation = CommandIsolationConfigDto(user = "   ")),
            "worker.commandIsolation.user must not be blank"
        )
    }

    /**
     * Verifies that a helper path that the process launcher could not resolve on its own is rejected.
     */
    @Test
    fun `rejects relative command isolation helper path`() {
        assertInvalid(
            validDto(commandIsolation = CommandIsolationConfigDto(helperPath = "run-as-agent")),
            "worker.commandIsolation.helperPath must be an absolute path"
        )
    }

    /**
     * Verifies that an allowlist which would leave isolated children unable to find any program is rejected.
     */
    @Test
    fun `rejects command isolation allowlist without effective PATH`() {
        assertInvalid(
            validDto(
                commandIsolation = CommandIsolationConfigDto(
                    environment = CommandEnvironmentConfigDto(allowPassthrough = listOf("LANG"))
                )
            ),
            "worker.commandIsolation.environment.allowPassthrough must provide PATH"
        )
    }

    /**
     * Verifies that a blank allowlist entry is rejected with the offending position in the message.
     */
    @Test
    fun `rejects blank command isolation allowlist entry`() {
        assertInvalid(
            validDto(
                commandIsolation = CommandIsolationConfigDto(
                    environment = CommandEnvironmentConfigDto(allowPassthrough = listOf("PATH", " "))
                )
            ),
            "worker.commandIsolation.environment.allowPassthrough[1] must not be blank"
        )
    }

    /**
     * Verifies that an allowlist entry libc could not parse as a single variable name is rejected.
     */
    @Test
    fun `rejects command isolation allowlist entry that is not an environment variable name`() {
        assertInvalid(
            validDto(
                commandIsolation = CommandIsolationConfigDto(
                    environment = CommandEnvironmentConfigDto(allowPassthrough = listOf("PATH", "PATH=/usr/bin"))
                )
            ),
            "worker.commandIsolation.environment.allowPassthrough[1] must be an environment variable name or \"*\""
        )
    }

    /**
     * Verifies that the wildcard allowlist is accepted without naming PATH explicitly.
     */
    @Test
    fun `accepts wildcard command isolation allowlist without PATH`() {
        val configDir = createTempDirectory("config-assembler-test")
        val result = validDto(
            commandIsolation = CommandIsolationConfigDto(
                environment = CommandEnvironmentConfigDto(allowPassthrough = listOf("*"))
            )
        ).toDomain(configDir)

        assertTrue(result.isRight())
        assertEquals(
            listOf("*"),
            result.getOrNull()?.worker?.commandIsolation?.environment?.allowPassthrough
        )
    }

    /**
     * Verifies that an explicit `true` is honoured, which is how a deployment opts in.
     */
    @Test
    fun `accepts enabled command isolation`() {
        val configDir = createTempDirectory("config-assembler-test")
        val result = validDto(commandIsolation = CommandIsolationConfigDto(enabled = true)).toDomain(configDir)

        assertTrue(result.isRight())
        assertEquals(true, result.getOrNull()?.worker?.commandIsolation?.enabled)
    }

    /**
     * Verifies that disabling isolation is accepted while the group is still validated.
     */
    @Test
    fun `accepts disabled command isolation`() {
        val configDir = createTempDirectory("config-assembler-test")
        val result = validDto(commandIsolation = CommandIsolationConfigDto(enabled = false)).toDomain(configDir)

        assertTrue(result.isRight())
        assertEquals(false, result.getOrNull()?.worker?.commandIsolation?.enabled)
    }

    /**
     * Asserts that converting the supplied DTO fails with a configuration validation error.
     *
     * @param dto Nullable DTO tree to convert.
     * @param expectedDescriptionFragment Text expected in the validation error description.
     */
    private fun assertInvalid(dto: AppConfigDto, expectedDescriptionFragment: String) {
        val configDir = createTempDirectory("config-assembler-test")
        val result = dto.toDomain(configDir)

        assertTrue(result.isLeft())
        val invalid = checkNotNull(result.swap().getOrNull() as? WorkerConfigError.ConfigInvalid) {
            "Expected ConfigInvalid, but conversion returned $result"
        }
        assertTrue(
            invalid.description.contains(expectedDescriptionFragment),
            "Expected '$expectedDescriptionFragment' in '${invalid.description}'"
        )
    }

    /**
     * Creates a valid app configuration DTO that individual tests can selectively invalidate.
     *
     * @param setup Optional setup section for the root config.
     * @param server Server DTO to place under the worker section.
     * @param identity Identity DTO to place under the worker section.
     * @param storage Storage DTO to place under the worker section.
     * @param auth Auth DTO to place under the worker section.
     * @param commandIsolation Command-isolation DTO to place under the worker section.
     * @param trustedSigners Trusted signer DTOs to place under the worker section.
     * @return Root DTO ready to pass to [AppConfigDto.toDomain].
     */
    private fun validDto(
        setup: SetupConfigDto? = SetupConfigDto(required = false),
        server: ServerConfigDto? = ServerConfigDto(baseUrl = "https://example.test/api"),
        identity: IdentityConfigDto? = IdentityConfigDto(
            uid = "worker-1",
            displayName = "Worker One",
            certificateFingerprint = "fingerprint-1",
            certificatePem = "certificate-pem-1"
        ),
        storage: StorageConfigDto? = StorageConfigDto(
            secretsJsonPath = "./secrets.json",
            tokenFilePath = "./token.json"
        ),
        auth: AuthConfigDto? = AuthConfigDto(refreshSkewSeconds = 30),
        commandIsolation: CommandIsolationConfigDto? = CommandIsolationConfigDto(),
        trustedSigners: List<TrustedSignerDto>? = listOf(
            TrustedSignerDto(
                signerId = "signer-1",
                publicKeyBase64 = "AQID", // Base64 for byte array [1, 2, 3]
                permissions = listOf("mcp:read")
            )
        )
    ): AppConfigDto = AppConfigDto(
        setup = setup,
        worker = RuntimeConfigDto(
            server = server,
            identity = identity,
            storage = storage,
            auth = auth,
            workspace = WorkspaceConfigDto(path = "./workspace"),
            builtInTools = BuiltInToolsConfigDto(enabled = listOf("*"), defaultCommandTimeoutSeconds = 600),
            commandIsolation = commandIsolation,
            trustedSigners = trustedSigners
        )
    )
}