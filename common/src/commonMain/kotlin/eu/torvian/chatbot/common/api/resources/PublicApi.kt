package eu.torvian.chatbot.common.api.resources

import io.ktor.resources.Resource

/**
 * Top-level resource for the public API endpoints.
 * These endpoints do not require authentication.
 */
@Resource("/api/v1/public")
class PublicApi