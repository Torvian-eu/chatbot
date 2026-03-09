package eu.torvian.chatbot.server.utils.transactions

import arrow.core.Either
import eu.torvian.chatbot.common.misc.transaction.TransactionMarker
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import kotlin.uuid.ExperimentalUuidApi

/**
 * Exposed-based implementation of [TransactionScope] using coroutine-safe transactions.
 *
 * Ensures only one transaction is created per logical use-case initiating the transaction boundary,
 * and prevents problematic nesting by checking for a [TransactionMarker] in the coroutine context.
 *
 * If a transaction is already active (marked by [TransactionMarker]), the block is executed directly
 * within that context. Otherwise, a new [suspendTransaction] is started on the IO dispatcher,
 * and the [TransactionMarker] is added to the context for subsequent checks within the transaction.
 *
 * @param db The target [Database] to run the transaction on.
 */
class ExposedTransactionScope(private val db: Database) : TransactionScope {
    @OptIn(ExperimentalUuidApi::class)
    override suspend fun <T> transaction(block: suspend () -> T): T {
        return if (currentCoroutineContext()[TransactionMarker] != null) {
            // Already in a transaction managed by this scope; avoid creating a new nested one.
            // The existing TransactionMarker in context confirms we are in a suspend transaction initiated by THIS scope.
            block()
        } else {
            // Not in a transaction managed by this scope; create a new one.
            // Add TransactionMarker to the new context.
            withContext(Dispatchers.IO + TransactionMarker()) {
                // suspendTransaction handles setting up the Exposed transaction in this CoroutineContext
                suspendTransaction(db = db) {
                    val result = block() // Execute the suspending block within the transaction

                    // If the result is Either.Left, rollback the transaction
                    if (result is Either.Left<*>) {
                        rollback()
                    }
                    result
                }
            }
        }
    }

    /**
     * For Exposed, all DSL operations must run within a transaction context.
     * Therefore, this function behaves identically to [transaction] to ensure safety.
     * It ensures the block is executed within a transaction, creating one if necessary.
     */
    override suspend fun <T> execute(block: suspend () -> T): T {
        return transaction(block)
    }
}