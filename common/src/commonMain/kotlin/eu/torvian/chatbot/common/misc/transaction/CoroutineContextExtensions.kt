package eu.torvian.chatbot.common.misc.transaction

import kotlin.coroutines.CoroutineContext

/**
 * Provides extension properties related to the coroutine context for transaction management utilities.
 */
val CoroutineContext.isInTransaction: Boolean
    /**
     * Checks whether a transaction managed by [TransactionScope] is active in the current coroutine context,
     * by checking for the presence of a [TransactionMarker].
     */
    get() = this[TransactionMarker] != null