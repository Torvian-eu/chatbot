package eu.torvian.chatbot.app.service.auth

import eu.torvian.chatbot.common.models.user.Permission
import eu.torvian.chatbot.common.models.user.User
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * A serializable container for the DEK metadata used in envelope encryption.
 * This is stored separately from the encrypted token data.
 *
 * @property wrappedDek Base64-encoded wrapped Data Encryption Key.
 * @property kekVersion Version of the Key Encryption Key used to wrap the DEK.
 */
@Serializable
internal data class DekMetadata(
    val wrappedDek: String,
    val kekVersion: Int
)

/**
 * A serializable container for encrypted token and user data.
 *
 * This class holds all authentication-related data that is serialized to JSON,
 * encrypted, and then persisted to storage (file system or browser localStorage).
 *
 * @property accessToken The JWT access token for API authentication.
 * @property refreshToken The refresh token for obtaining new access tokens.
 * @property expiresAt The expiration time of the access token as epoch seconds.
 * @property user The authenticated user data for optimistic authentication.
 * @property permissions The list of permissions granted to the user.
 * @property lastUsed The timestamp when this account was last actively used.
 */
@Serializable
internal data class TokenData(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
    val user: User,
    val permissions: List<Permission> = emptyList(),
    val lastUsed: Instant = Clock.System.now()
)

