package eu.torvian.chatbot.app.database.dao

import java.sql.SQLException

internal actual fun Throwable.isForeignKeyConstraintException(): Boolean {
    return this is SQLException && this.message?.contains("FOREIGN KEY constraint failed", ignoreCase = true) == true
}

