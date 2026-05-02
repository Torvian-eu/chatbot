### `docs/arrow-typed-errors.md`
```md
# Arrow typed errors

Use modern Arrow typed errors from `arrow.core.raise`.

## Rules

- Public and internal APIs should usually return `Either<Error, A>`.
- Prefer `Raise<Error>` only for `private` helpers.
- Build `Either` values with `either { ... }`.
- Inside `either { ... }`, call `.bind()` on every `Either`.
- Use `raise(...)` / `ensure(...)` for logical failures.
- Use `catch(...)` to translate expected exceptions from foreign/side-effecting code into logical errors.
- One `Raise` scope = one error type. Convert mismatches with `withError(...)`.
- Use typed errors for logical/domain errors, not technical failures.
- Avoid branching on `Either` with `isLeft()` / `isRight()`.

## Imports

Use modern imports:
```kotlin
import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.catch
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.withError
```

Notes:
- `bind()` does not need an explicit import.
- `raise(...)` does not need an explicit import.

## Preferred style

Public/internal API:
```kotlin
fun foo(n: Int): Either<MyError, String> = either {
  ensure(n >= 0) { MyError.NegativeInput }
  val s = load(n).bind()
  val t = parse(s).bind()
  t.summary()
}
```

Private helper:
```kotlin
private fun Raise<MyError>.foo(n: Int): String {
  ensure(n >= 0) { MyError.NegativeInput }
  val s = load(n).bind()
  val t = parse(s).bind()
  return t.summary()
}
```

Error conversion:
```kotlin
val t = withError({ e -> e.toMyError() }) {
  parseOther(s).bind()
}
```

Exception translation:
```kotlin
private suspend fun Raise<CreateUserError>.insertUser(username: String): Long =
  catch({
    userQueries.insert(username)
  }) { e: SQLException ->
    if (e.isUniqueViolation()) raise(CreateUserError.UserAlreadyExists(username))
    else throw e
  }
```

## `catch(...)` guidance

- Use `catch` around foreign code that may throw.
- Catch only expected/recoverable exception types when possible.
- Convert expected exceptions into logical errors with `raise(...)`.
- Re-throw unexpected exceptions.
- Do not use `catch` to swallow technical failures into vague domain errors.

## Avoid

- Old `flatMap` / `map` chains
- Manual `Either.Left(...)` / `Either.Right(...)`
- `isLeft()` / `isRight()` branching
- Old Arrow imports
- Mixing error types in one scope without `withError(...)`
- Exposing `Raise<...>` in non-private APIs unless clearly justified
- Catching overly broad exceptions when a specific exception type is expected
```