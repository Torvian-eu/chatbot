package eu.torvian.chatbot.server.data.dao.exposed

import org.jetbrains.exposed.exceptions.ExposedSQLException

/**
 * Extension function to check if an [ExposedSQLException] represents a unique constraint violation.
 *
 * This check is database-dependent. Common checks include:
 * - Standard SQLSTATE '23505' (PostgreSQL, H2, etc.)
 * - SQLite specific message pattern
 * - Database-specific error codes (not included here, but could be added if needed)
 *
 * @return true if the exception indicates a unique constraint violation, false otherwise.
 */
fun ExposedSQLException.isUniqueConstraintViolation(): Boolean {
    // Check standard SQLSTATE for unique violation
    if (this.sqlState == "23505") {
        return true
    }

    // Check for SQLite specific error message pattern
    // ExposedSQLException often wraps the original SQLException, check both messages
    val messageCheck = this.message?.contains("UNIQUE constraint failed", ignoreCase = true) == true ||
            this.cause?.message?.contains("UNIQUE constraint failed", ignoreCase = true) == true

    if (messageCheck) {
        return true
    }

    // Add other database-specific checks here if necessary, e.g., based on error codes
    // For example, for MySQL:
    // if (this.errorCode == 1062) {
    //     return true
    // }

    return false // Not identified as a unique constraint violation by our checks
}

/**
 * Extension function to check if an [ExposedSQLException] represents a foreign key constraint violation.
 *
 * This check is database-dependent. Common checks include:
 * - Standard SQLSTATE '23503' (PostgreSQL, H2, etc.)
 * - SQLite specific message pattern
 * - Database-specific error codes (not included here, but could be added if needed)
 *
 * @return true if the exception indicates a foreign key constraint violation, false otherwise.
 */
fun ExposedSQLException.isForeignKeyViolation(): Boolean {
    // Check standard SQLSTATE for foreign key violation
    if (this.sqlState == "23503") {
        return true
    }

    // Check for SQLite specific error message pattern
    // ExposedSQLException often wraps the original SQLException, check both messages
    val messageCheck = this.message?.contains("FOREIGN KEY constraint failed", ignoreCase = true) == true ||
            this.cause?.message?.contains("FOREIGN KEY constraint failed", ignoreCase = true) == true

    if (messageCheck) {
        return true
    }

    // Add other database-specific checks here if necessary, e.g., based on error codes
    // For example, for MySQL:
    // if (this.errorCode == 1452) {
    //     return true
    // }

    return false // Not identified as a foreign key violation by our checks
}