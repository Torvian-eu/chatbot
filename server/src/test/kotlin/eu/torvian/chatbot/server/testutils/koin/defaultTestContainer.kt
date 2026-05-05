package eu.torvian.chatbot.server.testutils.koin

import eu.torvian.chatbot.common.misc.di.KoinDIContainer
import eu.torvian.chatbot.server.domain.config.IpSecurityMode
import eu.torvian.chatbot.server.koin.daoModule
import eu.torvian.chatbot.server.koin.miscModule
import eu.torvian.chatbot.server.koin.serviceModule
import org.koin.dsl.koinApplication

/**
 * Creates a default test dependency injection container using Koin.
 *
 * This method initializes a `KoinDIContainer` with a predefined set of modules,
 * including configuration, database, repositories, services, miscellaneous components,
 * and specific test setup modules. These modules collectively provide the necessary
 * dependencies for the application or test environment.
 *
 * The returned container enables dependency injection through the Koin framework
 * and supports resolving and managing lifecycle-aware components.
 *
 * @param ipSecurityMode The IP security mode to bind into the container for feature tests.
 * @return An instance of `KoinDIContainer` configured with the default test modules.
 */
fun defaultTestContainer(ipSecurityMode: IpSecurityMode = IpSecurityMode.DISABLED) = KoinDIContainer(
    koinApplication {
        // Uncomment for debugging:
        // printLogger(Level.DEBUG)
        modules(
            defaultTestConfigModule(ipSecurityMode),
            testDatabaseModule(),
            daoModule(),
            serviceModule(),
            miscModule(),
            testSetupModule()
        )
    }
)