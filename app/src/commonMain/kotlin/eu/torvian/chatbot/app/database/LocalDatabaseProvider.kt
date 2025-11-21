package eu.torvian.chatbot.app.database

/**
 * Provides access to the local SQLDelight database.
 *
 * This class creates and manages the LocalDatabase instance using
 * a platform-specific DriverFactory.
 *
 * @property database The LocalDatabase instance
 */
class LocalDatabaseProvider(driverFactory: DriverFactory) {
    /**
     * The LocalDatabase instance created from the platform-specific driver.
     */
    val database: LocalDatabase = LocalDatabase(driverFactory.createDriver())
}

