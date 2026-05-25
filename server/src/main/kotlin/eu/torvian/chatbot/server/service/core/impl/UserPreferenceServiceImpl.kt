package eu.torvian.chatbot.server.service.core.impl

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.common.models.api.me.PreferenceDetailDTO
import eu.torvian.chatbot.common.models.api.me.UserPreferenceDTO
import eu.torvian.chatbot.common.models.user.PreferenceScope
import eu.torvian.chatbot.server.data.dao.UserDeviceDao
import eu.torvian.chatbot.server.data.dao.UserPreferenceDao
import eu.torvian.chatbot.server.service.core.UserPreferenceService
import eu.torvian.chatbot.server.service.core.error.preferences.PreferenceError
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * Exposed-backed implementation of [UserPreferenceService].
 *
 * Global preferences are always read before device-specific preferences so that device values
 * replace the shared defaults when the same key exists in both scopes.
 */
class UserPreferenceServiceImpl(
    private val userDeviceDao: UserDeviceDao,
    private val userPreferenceDao: UserPreferenceDao,
    private val transactionScope: TransactionScope
) : UserPreferenceService {

    companion object {
        private val logger: Logger = LogManager.getLogger(UserPreferenceServiceImpl::class.java)
    }

    override suspend fun getResolvedPreferences(
        userId: Long,
        clientDeviceId: String?
    ): Either<PreferenceError, Map<String, String>> = transactionScope.transaction {
        either {
            val internalDeviceId = clientDeviceId?.let { deviceId ->
                userDeviceDao.getDeviceByClientId(userId, deviceId)?.id
                    ?: raise(PreferenceError.DeviceNotRegistered(clientDeviceId))
            }

            logger.debug(
                "Resolving preferences for user {} (clientDeviceId={}, internalDeviceId={})",
                userId,
                clientDeviceId,
                internalDeviceId
            )

            val rows = userPreferenceDao.getPreferencesForUser(userId, internalDeviceId)

            // Global rows are sorted first so device-specific rows overwrite them when keys collide.
            rows.sortedBy { it.deviceId != null }
                .associate { preference -> preference.prefKey to preference.prefValue }
        }
    }

    override suspend fun getDetailedPreferences(
        userId: Long,
        clientDeviceId: String?
    ): Either<PreferenceError, Map<String, PreferenceDetailDTO>> = transactionScope.transaction {
        either {
            val internalDeviceId = clientDeviceId?.let { deviceId ->
                userDeviceDao.getDeviceByClientId(userId, deviceId)?.id
                    ?: raise(PreferenceError.DeviceNotRegistered(clientDeviceId))
            }

            logger.debug(
                "Getting detailed preferences for user {} (clientDeviceId={}, internalDeviceId={})",
                userId,
                clientDeviceId,
                internalDeviceId
            )

            val rows = userPreferenceDao.getPreferencesForUser(userId, internalDeviceId)

            // Separate global and device preferences, then group by key
            val globalPrefs = rows.filter { it.deviceId == null }.associate { it.prefKey to it.prefValue }
            val devicePrefs = rows.filter { it.deviceId != null }.associate { it.prefKey to it.prefValue }

            // Combine all unique keys
            (globalPrefs.keys + devicePrefs.keys).distinct().associateWith { key ->
                PreferenceDetailDTO(
                    globalValue = globalPrefs[key],
                    deviceValue = devicePrefs[key]
                )
            }
        }
    }

    override suspend fun updatePreference(
        userId: Long,
        clientDeviceId: String?,
        pathKey: String,
        request: UserPreferenceDTO
    ): Either<PreferenceError, Unit> = transactionScope.transaction {
        either {
            ensure(pathKey.isNotBlank()) {
                PreferenceError.InvalidInput("Preference key cannot be blank")
            }

            ensure(request.key == pathKey) {
                PreferenceError.InvalidInput("Preference key in the body must match the path parameter")
            }

            ensure(clientDeviceId != null || request.scope == PreferenceScope.GLOBAL) {
                PreferenceError.InvalidInput("Device-scoped preferences require a clientDeviceId")
            }

            val (internalDeviceId, scopedClientDeviceId) = when (request.scope) {
                PreferenceScope.GLOBAL -> null to null
                PreferenceScope.DEVICE -> {
                    val registeredDevice = clientDeviceId?.let { deviceId ->
                        userDeviceDao.getDeviceByClientId(userId, deviceId)
                    } ?: raise(PreferenceError.DeviceNotRegistered(clientDeviceId))

                    registeredDevice.id to clientDeviceId
                }
            }

            logger.debug(
                "Updating preference for user {} (scope={}, key={}, internalDeviceId={})",
                userId,
                request.scope,
                pathKey,
                internalDeviceId
            )

            userPreferenceDao.upsertPreference(userId, internalDeviceId, scopedClientDeviceId, pathKey, request.value)
        }
    }

    override suspend fun deletePreference(
        userId: Long,
        clientDeviceId: String?,
        pathKey: String,
        scope: PreferenceScope
    ): Either<PreferenceError, Unit> = transactionScope.transaction {
        either {
            ensure(pathKey.isNotBlank()) {
                PreferenceError.InvalidInput("Preference key cannot be blank")
            }

            ensure(clientDeviceId != null || scope == PreferenceScope.GLOBAL) {
                PreferenceError.InvalidInput("Device-scoped preferences require a clientDeviceId")
            }

            val internalDeviceId = when (scope) {
                PreferenceScope.GLOBAL -> null
                PreferenceScope.DEVICE -> {
                    clientDeviceId?.let { deviceId ->
                        userDeviceDao.getDeviceByClientId(userId, deviceId)?.id
                            ?: raise(PreferenceError.DeviceNotRegistered(clientDeviceId))
                    }
                }
            }

            logger.debug(
                "Deleting preference for user {} (scope={}, key={}, internalDeviceId={})",
                userId,
                scope,
                pathKey,
                internalDeviceId
            )

            userPreferenceDao.deletePreference(userId, internalDeviceId, pathKey)
        }
    }
}
