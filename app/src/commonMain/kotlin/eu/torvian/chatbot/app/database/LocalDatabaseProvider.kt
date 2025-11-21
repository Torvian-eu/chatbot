package eu.torvian.chatbot.app.database

import app.cash.sqldelight.adapter.primitive.IntColumnAdapter
import app.cash.sqldelight.db.SqlDriver

/**
 * Provides access to the local SQLDelight database.
 *
 * This class creates and manages the LocalDatabase instance using
 * a platform-specific DriverFactory.
 *
 * @param driverFactory The platform-specific factory for creating SqlDriver
 * @property database The LocalDatabase instance
 */
class LocalDatabaseProvider(driverFactory: DriverFactory) {
    companion object {
        /**
         * Creates a LocalDatabase instance from a given SqlDriver.
         *
         * @param driver The SqlDriver instance
         * @return A LocalDatabase instance
         */
        fun createDatabase(driver: SqlDriver): LocalDatabase {
            return LocalDatabase(
                driver = driver,
                EncryptedSecretTableAdapter = EncryptedSecretTable.Adapter(
                    keyVersionAdapter = IntColumnAdapter
                ),
                LocalMCPServerLocalTableAdapter = LocalMCPServerLocalTable.Adapter(
                    autoStopAfterInactivitySecondsAdapter = IntColumnAdapter
                )
            )
        }
    }

    /**
     * The LocalDatabase instance created from the platform-specific driver.
     */
    val database: LocalDatabase = createDatabase(
        driverFactory.createDriver()
    )
}
