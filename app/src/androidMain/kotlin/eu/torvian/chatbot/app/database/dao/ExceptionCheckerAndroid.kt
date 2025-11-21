package eu.torvian.chatbot.app.database.dao

import android.database.SQLException
import android.database.sqlite.SQLiteConstraintException

internal actual fun Throwable.isForeignKeyConstraintException(): Boolean {
    // On Android SQLiteConstraintException is thrown for constraint violations
    if (this is SQLiteConstraintException) return this.message?.contains("foreign key", ignoreCase = true) == true || this.message?.contains("FOREIGN KEY", ignoreCase = true) == true
    if (this is SQLException) return this.message?.contains("FOREIGN KEY", ignoreCase = true) == true || this.message?.contains("foreign key", ignoreCase = true) == true
    return false
}

