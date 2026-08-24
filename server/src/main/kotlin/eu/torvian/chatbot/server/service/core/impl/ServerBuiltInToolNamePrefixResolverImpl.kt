package eu.torvian.chatbot.server.service.core.impl

import eu.torvian.chatbot.common.models.api.me.PreferenceKeys
import eu.torvian.chatbot.server.data.dao.UserPreferenceDao
import eu.torvian.chatbot.server.service.core.ServerBuiltInToolNamePrefixResolver

/**
 * Default implementation of [ServerBuiltInToolNamePrefixResolver].
 *
 * Resolves the effective prefix from the user's global preference rows. The constructor takes the
 * fallback [defaultPrefix] (defaulting to the hardcoded constant) as the single seam for a future
 * configurable server default: the Koin binding currently omits the argument, and the future
 * config stage only swaps that binding to `get<AppConfiguration>().tools.builtInToolNamePrefix`
 * without touching any consumer.
 *
 * @property userPreferenceDao DAO used to read the user's global preference rows.
 * @property defaultPrefix Fallback prefix used when the user has no stored preference. Defaults to
 *            [ServerBuiltInToolNamePrefixResolver.DEFAULT_SERVER_BUILTIN_TOOL_NAME_PREFIX].
 */
class ServerBuiltInToolNamePrefixResolverImpl(
    private val userPreferenceDao: UserPreferenceDao,
    private val defaultPrefix: String =
        ServerBuiltInToolNamePrefixResolver.DEFAULT_SERVER_BUILTIN_TOOL_NAME_PREFIX,
) : ServerBuiltInToolNamePrefixResolver {

    override suspend fun resolvePrefix(userId: Long): String {
        val stored = userPreferenceDao.getPreferencesForUser(userId, null)
            .firstOrNull { it.prefKey == PreferenceKeys.SERVER_BUILTIN_TOOL_NAME_PREFIX }
            ?.prefValue
        return when {
            stored == null -> defaultPrefix
            // A blank stored value explicitly means "no prefix" (canonical names), mirroring the
            // worker semantics where blank is normalized to null.
            stored.isBlank() -> ""
            else -> stored
        }
    }
}
