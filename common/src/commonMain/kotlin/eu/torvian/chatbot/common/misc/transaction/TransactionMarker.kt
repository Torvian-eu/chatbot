package eu.torvian.chatbot.common.misc.transaction

import kotlin.uuid.Uuid
import kotlin.coroutines.CoroutineContext
import kotlin.uuid.ExperimentalUuidApi

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
@OptIn(ExperimentalUuidApi::class)
data class TransactionMarker(val id: Uuid = Uuid.random()) : CoroutineContext.Element {
    override val key: CoroutineContext.Key<*> = Key
    companion object Key : CoroutineContext.Key<TransactionMarker>
}