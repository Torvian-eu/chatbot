package eu.torvian.chatbot.app.database

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.async.coroutines.synchronous

/**
 * Android implementation of DriverFactory.
 *
 * Uses Android SQLite driver with foreign key constraints enabled.
 *
 * @property context The Android application context
 */
class DriverFactoryAndroid(private val context: Context) : DriverFactory {

    /**
     * Creates an Android SQLite driver.
     *
     * This method:
     * - Uses the Android-specific SQLite driver
     * - Enables foreign key constraints
     * - Applies the schema automatically
     *
     * @return A configured SqlDriver instance
     */
    override fun createDriver(): SqlDriver {
        return AndroidSqliteDriver(
            schema = LocalDatabase.Schema.synchronous(),
            context = context,
            name = "local.db",
            callback = object : AndroidSqliteDriver.Callback(LocalDatabase.Schema.synchronous()) {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    db.setForeignKeyConstraintsEnabled(true)  // Enable foreign key constraint enforcement
                }
            }
        )
    }
}

