package eu.torvian.chatbot.worker.config

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
        val result = validDto().toDomain()

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
        ).toDomain()

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
     * Asserts that converting the supplied DTO fails with a configuration validation error.
     *
     * @param dto Nullable DTO tree to convert.
     * @param expectedDescriptionFragment Text expected in the validation error description.
     */
    private fun assertInvalid(dto: AppConfigDto, expectedDescriptionFragment: String) {
        val result = dto.toDomain()

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
            trustedSigners = trustedSigners
        )
    )
}