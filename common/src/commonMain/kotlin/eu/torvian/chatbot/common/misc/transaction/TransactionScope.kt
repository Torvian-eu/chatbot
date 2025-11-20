package eu.torvian.chatbot.common.misc.transaction

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
     * Runs the given [block] of code inside a single, explicit transaction.
     *
     * If a transaction is already active, the block runs within that existing context.
     * Otherwise, a new transaction is started. If the block returns an [Either.Left],
     * the transaction is automatically rolled back.
     *
     * This should be used for service-level operations or DAO methods that perform
     * multiple database writes that must be atomic together.
     *
     * @param block The suspending code block to run inside the transaction.
     * @return The result of the block.
     */
    suspend fun <T> transaction(block: suspend () -> T): T

    /**
     * Executes the given [block] of code on the appropriate database dispatcher, joining
     * an existing transaction if one is active in the coroutine context.
     *
     * Unlike [transaction], this function **will not create a new transaction** if one is not
     * already present. It only ensures execution on the correct dispatcher.
     *
     * This is ideal for single, atomic database operations (e.g., a single SELECT, UPDATE, or DELETE)
     * in a DAO, as it avoids the overhead of creating a transaction for a statement
     * that is already atomic by itself.
     *
     * @param block The suspending code block to execute.
     * @return The result of the block.
     */
    suspend fun <T> execute(block: suspend () -> T): T
}