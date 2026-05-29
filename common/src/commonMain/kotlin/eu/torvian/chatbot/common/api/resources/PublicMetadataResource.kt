package eu.torvian.chatbot.common.api.resources

import io.ktor.resources.*

/**
 * Resource for public metadata (no authentication required).
 */
@Resource("info")
class ServerInfo(val parent: PublicApi = PublicApi())

