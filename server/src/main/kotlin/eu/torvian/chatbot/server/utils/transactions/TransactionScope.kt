package eu.torvian.chatbot.server.utils.transactions

import arrow.core.Either

/**
 * Represents an abstraction for managing database transactions.
 *
 * Allows the service layer to explicitly define transaction boundaries,
 * without embedding `transaction {}` blocks in repository or domain logic.
 * Ensures the use of coroutine-safe suspending transactions.
 */
interface TransactionScope {
    /**
     * Runs the given [block] of code inside a single transaction. If the block returns an [Either.Left],
     * the transaction is rolled back.
     *
     * If a transaction is already active in the current coroutine context (marked by [TransactionMarker]),
     * the block is run within that existing transaction context.
     * Otherwise, a new suspending transaction is started on the IO dispatcher.
     *
     * @param block The suspending code block to run inside the transaction.
     * @return The result of the block.
     */
    suspend fun <T> transaction(block: suspend () -> T): T
}