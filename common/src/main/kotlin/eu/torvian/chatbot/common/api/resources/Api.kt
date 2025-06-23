package eu.torvian.chatbot.common.api.resources

import io.ktor.resources.*

/**
 * Top-level resource for the API version prefix.
 * All other main resource groups will be parented by this.
 */
@Resource("/api/v1")
class Api

