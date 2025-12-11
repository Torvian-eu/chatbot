package eu.torvian.chatbot.app.domain.contracts

/**
 * Utilities for combining two [DataState] instances that share the same error type.
 *
 * The resulting state follows precedence:
 *   Error > Loading > Idle > Success
 *
 * - If either side is Error, the first encountered Error is returned (left-hand takes precedence
 *   by how this function is used; callers may check both if a different precedence is needed).
 * - If either side is Loading, Loading is returned (unless an Error is present).
 * - If either side is Idle, Idle is returned (unless Error/Loading are present).
 * - Only when both sides are Success, [combine] will be invoked and a Success of the result
 *   will be returned.
 */
fun <E, A, B, R> DataState<E, A>.zipWith(
    other: DataState<E, B>,
    combine: (A, B) -> R
): DataState<E, R> = when {
    // Both success -> combine
    this is DataState.Success && other is DataState.Success -> DataState.Success(combine(this.data, other.data))

    // Error precedence: prefer `this` error if present, otherwise `other` error
    this is DataState.Error -> DataState.Error(this.error)
    other is DataState.Error -> DataState.Error(other.error)

    // Loading precedence
    this is DataState.Loading || other is DataState.Loading -> DataState.Loading

    // Idle precedence
    this is DataState.Idle || other is DataState.Idle -> DataState.Idle


    // Fallback
    else -> DataState.Idle
}

