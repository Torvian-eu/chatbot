### Arrow `Raise` Cheatsheet for Typed Error Handling

Arrow's `Raise` provides a highly efficient and readable way to handle errors without explicit wrappers like `Either` in every function signature. It allows for writing sequential, "happy path" code that can short-circuit on failure.

---

#### 1. Defining a Function that Can Fail

To create a function that can produce a typed error, define it with `Raise<MyErrorType>` as its receiver. The return type should only be the success type.

```kotlin
import arrow.core.raise.Raise
import arrow.core.raise.ensure

// Define your custom error types
object UserNotFound
data class InvalidInput(val reason: String)

// The function operates in the context of Raise<UserNotFound>
// It returns a User on success or raises a UserNotFound on failure.
fun Raise<UserNotFound>.findUser(id: Int): User {
    ensure(id > 0) { UserNotFound } // Invariant check
    return User(id) // Return success value directly
}
```

---

#### 2. Raising an Error

Inside a `Raise` context, use the `raise()` function to short-circuit the computation and return a failure. Helpers like `ensure` and `ensureNotNull` simplify validation checks.

```kotlin
fun Raise<InvalidInput>.validateUsername(name: String) {
    ensure(name.length >= 3) {
        InvalidInput("Username must be at least 3 characters long.")
    }
}
```

---

#### 3. Running a `Raise` Computation

A `Raise` computation is not executed until you use a "runner." The most common runner is `either { }`, which executes the block and captures the result in an `Either<ErrorType, SuccessType>`.

```kotlin
import arrow.core.Either
import arrow.core.raise.either

val successfulResult: Either<UserNotFound, User> = either {
    findUser(1) // Returns Either.Right(User(1))
}

val failureResult: Either<UserNotFound, User> = either {
    findUser(-1) // Returns Either.Left(UserNotFound)
}
```

---

#### 4. Composing Computations with `.bind()`

The `.bind()` extension function is the key to composition. When called on an `Either` within a `Raise` block, it unwraps the success value. If the `Either` is a `Left` (failure), it automatically calls `raise()` with the error and short-circuits.

```kotlin
fun findUsername(id: Int): Either<UserNotFound, String> = either { "Alice" }
fun connectToService(user: String): Either<ConnectionError, Unit> = either { Unit }

// The `Raise` block handles different error types.
val result: Either<Any, Unit> = either {
    val username = findUsername(1).bind() // Unwraps "Alice" or raises UserNotFound
    connectToService(username).bind()      // Unwraps Unit or raises ConnectionError
}
```

---

#### 5. Recovering from Errors with `recover`

You can handle a raised error and provide a fallback value or transition to a different error type using `recover`.

```kotlin
import arrow.core.raise.recover

object OtherError

suspend fun Raise<OtherError>.fetchWithRecovery(): User =
    // Try to execute fetchUser. If it raises UserNotFound, run the recovery block.
    recover({
        fetchUser(-1) // This function is defined with `Raise<UserNotFound>`
    }) { userNotFound: UserNotFound ->
        // We can't provide a fallback, so we raise a different error.
        raise(OtherError)
    }
```

---

#### 6. Converting Exceptions to Typed Errors with `catch`

To safely interact with code that throws exceptions, use `catch` to transform a `Throwable` into one of your typed logical failures.

```kotlin
import arrow.core.raise.catch

data class UserAlreadyExists(val username: String)

suspend fun Raise<UserAlreadyExists>.insertUser(username: String): Unit =
    catch({
        database.insert(username)
    }) { e: SQLException ->
        if (e.isUniqueViolation()) {
            raise(UserAlreadyExists(username))
        } else {
            throw e // Re-throw if it's an unexpected exception
        }
    }
```

---

#### 7. Transforming Errors with `withError`

**What it does:** Maps or transforms an error of one type into another.

**When to use it:** When you need to call a function that raises an error type different from what your current `Raise` context allows. This is essential for unifying error channels and preventing error type leakage across architectural boundaries (e.g., translating a detailed `ServiceError` into a more generic `ApiError`).

**Example:**
Imagine a service layer that can fail with a specific `ServiceError`, but your API layer should only expose a generic `ApiError`.

```kotlin
import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.either
import arrow.core.raise.withError

// Internal error type, specific to the service
sealed interface ServiceError {
    object UserNotFound : ServiceError
    object PermissionDenied : ServiceError
}

// Public error type for the API
sealed interface ApiError {
    data class General(val message: String) : ApiError
}

// A function in the service layer that can fail with a ServiceError
fun Raise<ServiceError>.getUserData(id: Int): String {
    if (id <= 0) raise(ServiceError.UserNotFound)
    return "User data for $id"
}

// The API layer function. It must raise an ApiError, not a ServiceError.
fun Raise<ApiError>.processRequest(id: Int): String =
    // Use withError to map any potential ServiceError into an ApiError
    withError({ serviceError: ServiceError ->
        when (serviceError) {
            is ServiceError.UserNotFound -> ApiError.General("The requested user could not be found.")
            is ServiceError.PermissionDenied -> ApiError.General("You are not authorized.")
        }
    }) {
        // Inside this block, you can safely call functions that raise ServiceError
        getUserData(id)
    }

// Running the composed function
val result1: Either<ApiError, String> = either { processRequest(123) }
println(result1) // Either.Right(User data for 123)

val result2: Either<ApiError, String> = either { processRequest(-1) }
println(result2) // Either.Left(General(message=The requested user could not be found.))
```