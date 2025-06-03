package eu.torvian.chatbot.server.utils.transactions

import java.util.UUID
import kotlin.coroutines.CoroutineContext

/**
 * Coroutine context element used to mark that a transaction initiated by [TransactionScope] is active
 * in the current coroutine context.
 *
 * When present in the [CoroutineContext], it signals that the current coroutine
 * is already inside a transaction managed by this scope, allowing the implementation
 * to avoid creating redundant or problematic nested transactions.
 *
 * This marker is internal to the application's transaction management utility.
 *
 * @property id Optional unique ID to trace the transaction context (useful for logging/debugging).
 */
data class TransactionMarker(val id: UUID = UUID.randomUUID()) : CoroutineContext.Element {
    override val key: CoroutineContext.Key<*> = Key
    companion object Key : CoroutineContext.Key<TransactionMarker>
}