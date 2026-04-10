package eu.torvian.chatbot.server.ktor.auth

import eu.torvian.chatbot.server.domain.security.UserContext
import eu.torvian.chatbot.server.domain.security.WorkerContext
import io.ktor.server.application.*
import io.ktor.server.auth.*

/**
 * Utility functions for extracting user context from authenticated requests.
 *
 * These functions provide a convenient way to extract user information from JWT tokens
 * in authenticated Ktor routes. They assume the request has been authenticated using
 * the JWT authentication scheme.
 */

/**
 * Extracts the user ID from the authenticated JWT token.
 *
 * @return The user ID from the token's subject claim
 * @throws IllegalStateException if the user ID is not found or invalid
 */
fun ApplicationCall.getUserId(): Long {
    val userContext = principal<UserContext>()
        ?: throw IllegalStateException("No principal found - ensure route is protected with authentication")

    return userContext.user.id
}

/**
 * Extracts the full user context from the authenticated JWT token.
 *
 * @return The [UserContext] containing user and session information
 * @throws IllegalStateException if the user context is not found
 */
fun ApplicationCall.getUserContext(): UserContext {
    val userContext = principal<UserContext>()
        ?: throw IllegalStateException("No principal found - ensure route is protected with authentication")

    return userContext
}

fun ApplicationCall.getWorkerContext(): WorkerContext {
    val workerContext = principal<WorkerContext>()
        ?: throw IllegalStateException("No worker principal found - ensure route is protected with worker authentication")

    return workerContext
}

fun ApplicationCall.getWorkerId(): Long = getWorkerContext().workerId

