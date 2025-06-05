package eu.torvian.chatbot.server.testutils.data

import eu.torvian.chatbot.server.data.models.ApiSecretEntity
import eu.torvian.chatbot.server.domain.config.DatabaseConfig
import eu.torvian.chatbot.server.domain.security.EncryptedSecret
import eu.torvian.chatbot.server.domain.security.EncryptionConfig
import kotlinx.datetime.Instant

/**
 * Predefined domain objects and test data entries for use in tests.
 *
 * This object provides a consistent set of test data values that can be used directly
 * or as a base to `.copy()` from.
 *
 * These values are designed to be reused in test cases and passed into `TestDataSet` to simplify
 * test setup via the `TestDataManager`.
 */
object TestDefaults {

    val DEFAULT_INSTANT: Instant = Instant.parse("2023-01-01T12:00:00Z")
    val DEFAULT_INSTANT_MILLIS: Long = DEFAULT_INSTANT.toEpochMilliseconds()

    // --- Config ---

    fun getDefaultDatabaseConfig() = DatabaseConfig(
        vendor = "sqlite",
        type = "memory",
        filepath = "data",
        filename = null,
        user = null,
        password = null
    )

    val DEFAULT_ENCRYPTION_CONFIG = EncryptionConfig(
        masterKey = "G2CgJOQQtIC+yfz+LLoDp/osBLUVzW9JE9BrQA0dQFo=",
        keyVersion = 1
    )

    // --- Encrypted Secrets ---

    val encryptedSecret1 = EncryptedSecret(
        encryptedSecret = "encrypted_secret1",
        encryptedDEK = "encrypted_dek1",
        keyVersion = 1
    )

    // --- Test Dataset Entries (representing rows for insertion) ---

    val apiSecret1 = ApiSecretEntity(
        alias = "alias1",
        encryptedCredential = encryptedSecret1.encryptedSecret,
        wrappedDek = encryptedSecret1.encryptedDEK,
        keyVersion = encryptedSecret1.keyVersion,
        createdAt = DEFAULT_INSTANT_MILLIS,
        updatedAt = DEFAULT_INSTANT_MILLIS
    )

}