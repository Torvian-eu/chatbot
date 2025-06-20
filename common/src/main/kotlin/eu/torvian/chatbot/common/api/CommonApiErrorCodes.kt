package eu.torvian.chatbot.common.api

/**
 * Standard and commonly used API error codes.
 *
 * These codes are intended to provide semantic meaning to API errors, allowing clients
 * to handle specific error conditions programmatically, in addition to the HTTP status code.
 */
object CommonApiErrorCodes {
    // === 400 Bad Request ===

    /**
     * Indicates that a required field is missing from the request payload or parameters.
     *
     * Use when a mandatory piece of data was expected but not provided by the client.
     * Corresponds to HTTP Status Code 400 Bad Request.
     */
    val MISSING_FIELD = ApiErrorCode("missing-field", 400)

    /**
     * Indicates that a provided argument or parameter value is invalid, malformed,
     * or does not meet expected format or constraints (e.g., invalid string format,
     * incorrect data type, value violating basic business rules).
     *
     * Use for validation errors on specific input fields or parameters.
     * Corresponds to HTTP Status Code 400 Bad Request.
     */
    val INVALID_ARGUMENT = ApiErrorCode("invalid-argument", 400)

    /**
     * Indicates that a provided value is outside the allowed range (e.g., a number
     * is too large or too small, a date is out of expected bounds).
     *
     * This is a specific type of `INVALID_ARGUMENT`.
     * Corresponds to HTTP Status Code 400 Bad Request.
     */
    val OUT_OF_RANGE = ApiErrorCode("out-of-range", 400)

    /**
     * Indicates that the format of a specific field or value is invalid (e.g.,
     * an email address is not in the correct format, a date string cannot be parsed).
     *
     * This is a specific type of `INVALID_ARGUMENT`.
     * Corresponds to HTTP Status Code 400 Bad Request.
     */
    val INVALID_FORMAT = ApiErrorCode("invalid-format", 400)

    /**
     * Indicates that the resource or system is in an invalid state for the requested operation.
     * The operation would be valid under different circumstances, but the current state prevents it.
     *
     * Use for business logic errors related to state transitions or prerequisites.
     * Examples: Trying to update a resource that is marked as 'deleted', adding a message
     * to a session's message tree with an invalid parent ID that doesn't belong to the tree.
     * Corresponds to HTTP Status Code 400 Bad Request.
     */
    val INVALID_STATE = ApiErrorCode("invalid-state", 400)

    /**
     * Indicates that a precondition for the requested operation was not met.
     * This is similar to `INVALID_STATE` but often refers to conditions that must hold
     * true *before* the operation is attempted, rather than the inherent state of the resource.
     *
     * Use when specific conditions explicitly required by the API contract were not fulfilled.
     * Corresponds to HTTP Status Code 400 Bad Request.
     */
    val FAILED_PRECONDITION = ApiErrorCode("failed-precondition", 400)

    /**
     * Indicates that the request failed one or more validation checks.
     * This can be used as a general code when multiple validation errors occur
     * for different fields, and details provide specifics for each failure.
     *
     * Corresponds to HTTP Status Code 400 Bad Request.
     */
    val VALIDATION_FAILED = ApiErrorCode("validation-failed", 400)

    // === 401 Unauthorized ===

    /**
     * Indicates that the request requires user authentication, but the client
     * is either not authenticated at all or the provided authentication
     * details are invalid or insufficient.
     *
     * Corresponds to HTTP Status Code 401 Unauthorized.
     */
    val UNAUTHENTICATED = ApiErrorCode("unauthenticated", 401)

    /**
     * Indicates that the provided credentials (e.g., username/password, API key)
     * are incorrect or invalid for authentication.
     *
     * Corresponds to HTTP Status Code 401 Unauthorized.
     */
    val INVALID_CREDENTIALS = ApiErrorCode("invalid-credentials", 401)

    /**
     * Indicates that the authentication token provided is invalid, expired,
     * revoked, or malformed.
     *
     * Corresponds to HTTP Status Code 401 Unauthorized.
     */
    val INVALID_TOKEN = ApiErrorCode("invalid-token", 401)

    // === 403 Forbidden ===

    /**
     * Indicates that the server understood the request but refuses to authorize it.
     * The authenticated user does not have the necessary permissions to perform
     * the requested operation on the specified resource.
     *
     * Corresponds to HTTP Status Code 403 Forbidden.
     */
    val PERMISSION_DENIED = ApiErrorCode("permission-denied", 403)

    /**
     * Indicates that the requested feature or operation is currently disabled
     * in the system or for the requesting user/context.
     *
     * Corresponds to HTTP Status Code 403 Forbidden.
     */
    val FEATURE_DISABLED = ApiErrorCode("feature-disabled", 403)


    // === 404 Not Found ===

    /**
     * Indicates that the requested resource could not be found on the server.
     * This typically occurs when an ID or path in the URL refers to a resource
     * that does not exist.
     *
     * Corresponds to HTTP Status Code 404 Not Found.
     */
    val NOT_FOUND = ApiErrorCode("not-found", 404)

    // === 405 Method Not Allowed ===

    /**
     * Indicates that the HTTP method used in the request is not supported
     * for the requested resource or endpoint.
     *
     * Corresponds to HTTP Status Code 405 Method Not Allowed.
     */
    val METHOD_NOT_ALLOWED = ApiErrorCode("method-not-allowed", 405)


    // === 409 Conflict ===

    /**
     * Indicates that the request could not be completed due to a conflict with
     * the current state of the target resource. This often happens with `POST`
     * requests when trying to create a resource that already exists with
     * a unique identifier (like a name).
     *
     * Corresponds to HTTP Status Code 409 Conflict.
     */
    val ALREADY_EXISTS = ApiErrorCode("already-exists", 409)

    /**
     * Indicates that the request was aborted due to a concurrency issue, such
     * as a write conflict or transaction failure. The client might be able
     * to retry the request.
     *
     * Corresponds to HTTP Status Code 409 Conflict.
     */
    val ABORTED = ApiErrorCode("aborted", 409)

    /**
     * A general conflict error code for situations that don't fit more specific
     * conflict types like `ALREADY_EXISTS` or `RESOURCE_IN_USE`.
     *
     * Corresponds to HTTP Status Code 409 Conflict.
     */
    val CONFLICT = ApiErrorCode("conflict", 409)

    /**
     * Indicates that the resource cannot be modified or deleted because it is
     * currently referenced or in use by another resource or process.
     *
     * Example: Trying to delete a provider that still has models linked to it.
     * Corresponds to HTTP Status Code 409 Conflict.
     */
    val RESOURCE_IN_USE = ApiErrorCode("resource-in-use", 409)


    // === 415 Unsupported Media Type ===

    /**
     * Indicates that the server refuses to accept the request because the payload
     * format (specified by the `Content-Type` header) is not supported by the server.
     *
     * Corresponds to HTTP Status Code 415 Unsupported Media Type.
     */
    val UNSUPPORTED_MEDIA_TYPE = ApiErrorCode("unsupported-media-type", 415)


    // === 429 Too Many Requests ===

    /**
     * Indicates that the request cannot be fulfilled because the resource quota
     * or limit for the client or user has been exhausted.
     *
     * Corresponds to HTTP Status Code 429 Too Many Requests.
     */
    val RESOURCE_EXHAUSTED = ApiErrorCode("resource-exhausted", 429)

    /**
     * Indicates that the client has sent too many requests in a given amount
     * of time ("rate limiting"). The client should typically wait before retrying.
     *
     * Corresponds to HTTP Status Code 429 Too Many Requests.
     */
    val RATE_LIMIT = ApiErrorCode("rate-limit", 429)


    // === 500 Internal Server Error ===

    /**
     * A generic code indicating that an unexpected internal error occurred on the server.
     * This should be used for errors that are not due to client input or state,
     * and for which a more specific code is not available.
     *
     * Corresponds to HTTP Status Code 500 Internal Server Error.
     */
    val INTERNAL = ApiErrorCode("internal", 500)

    /**
     * Indicates that irrecoverable data loss or corruption occurred while the
     * server was handling the request.
     *
     * Corresponds to HTTP Status Code 500 Internal Server Error.
     */
    val DATA_LOSS = ApiErrorCode("data-loss", 500)


    // === 501 Not Implemented ===

    /**
     * Indicates that the server does not support the functionality required to
     * fulfill the request. This can mean the HTTP method is not implemented
     * for the endpoint, or a requested feature is not available.
     *
     * Corresponds to HTTP Status Code 501 Not Implemented.
     */
    val UNIMPLEMENTED = ApiErrorCode("unimplemented", 501)


    // === 503 Service Unavailable ===

    /**
     * Indicates that the server is currently unable to handle the request,
     * usually because it is overloaded or down for maintenance.
     * The client can typically retry the request later.
     *
     * Corresponds to HTTP Status Code 503 Service Unavailable.
     */
    val UNAVAILABLE = ApiErrorCode("unavailable", 503)

    /**
     * Indicates that the request should be retried later. This can be used
     * in conjunction with `UNAVAILABLE` or when a specific temporary condition
     * prevents immediate processing.
     *
     * Corresponds to HTTP Status Code 503 Service Unavailable.
     */
    val RETRY_LATER = ApiErrorCode("retry-later", 503)


    // === 504 Gateway Timeout ===

    /**
     * Indicates that the server, while acting as a gateway or proxy, did not
     * receive a timely response from an upstream server it needed to access
     * to complete the request.
     *
     * Corresponds to HTTP Status Code 504 Gateway Timeout.
     */
    val DEADLINE_EXCEEDED = ApiErrorCode("deadline-exceeded", 504)
}
