package eu.torvian.chatbot.app.utils.transaction

import app.cash.sqldelight.SuspendingTransacter
import arrow.core.Either
import eu.torvian.chatbot.common.misc.transaction.TransactionMarker
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.common.misc.transaction.isInTransaction
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.uuid.ExperimentalUuidApi

/**
 * SQLDelight-based implementation of [TransactionScope] using coroutine-safe transactions.
 *
 * This implementation is designed for SQLDelight configurations where `generateAsync = true` is set,
 * which provides a [SuspendingTransacter] interface. It leverages the built-in suspending
 * `transactionWithResult` function for managing transactions.
 *
 * It ensures that only one transaction is created per logical use-case by checking for a
 * [TransactionMarker] in the coroutine context, preventing problematic nesting.
 *
 * If a transaction is already active, the block is executed directly within that existing context.
 * Otherwise, a new transaction is started on the specified [coroutineContext], and the
 * [TransactionMarker] is added to the context for subsequent checks.
 *
 * @param transacter The SQLDelight [SuspendingTransacter] (the generated Database class) to run transactions on.
 * @param coroutineContext The [CoroutineContext] on which to execute database operations.
 *                         **For SQLite, this must be a single-threaded dispatcher**
 *                         (e.g., `Dispatchers.IO.limitedParallelism(1)`) to ensure all database
 *                         access is serialized and prevent concurrency issues.
 */
class SqlDelightTransactionScope(
    private val transacter: SuspendingTransacter,
    private val coroutineContext: CoroutineContext
) : TransactionScope {

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun <T> transaction(block: suspend () -> T): T {
        return if (currentCoroutineContext().isInTransaction) {
            // Already in a transaction managed by this scope; avoid creating a new nested one.
            block()
        } else {
            // Not in a transaction; create a new one.
            // Add TransactionMarker to the new context and switch to the dedicated database dispatcher.
            withContext(coroutineContext + TransactionMarker()) {
                transacter.transactionWithResult {
                    val result = block() // Execute the suspending block within the transaction.

                    // If the result is Either.Left, we need to roll back the transaction.
                    // Calling rollback() with a value simultaneously flags the transaction for rollback
                    // AND sets the return value of the entire 'transactionWithResult' block.
                    if (result is Either.Left<*>) {
                        rollback(result)
                    }

                    // If we don't call rollback(), the result of the block is returned and the transaction is committed.
                    result
                }
            }
        }
    }

    override suspend fun <T> execute(block: suspend () -> T): T {
        return if (currentCoroutineContext().isInTransaction) {
            // Already in a transaction and on the correct dispatcher. Just run the block.
            block()
        } else {
            // Not in a transaction. Switch to the database dispatcher but do NOT start a transaction.
            withContext(coroutineContext) {
                block()
            }
        }
    }
}