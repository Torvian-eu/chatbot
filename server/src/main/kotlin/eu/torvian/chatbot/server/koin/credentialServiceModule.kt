package eu.torvian.chatbot.server.koin

import eu.torvian.chatbot.server.service.core.WorkerCredentialProfileService
import eu.torvian.chatbot.server.service.core.WorkerCredentialSecretService
import eu.torvian.chatbot.server.service.core.impl.WorkerCredentialProfileServiceImpl
import eu.torvian.chatbot.server.service.core.impl.WorkerCredentialSecretServiceImpl
import eu.torvian.chatbot.server.service.security.CredentialSecretEncryptionService
import eu.torvian.chatbot.server.service.security.DefaultCredentialSecretEncryptionService
import eu.torvian.chatbot.server.worker.credential.CredentialCommandDispatchService
import eu.torvian.chatbot.server.worker.credential.CredentialSyncService
import eu.torvian.chatbot.server.worker.credential.DefaultCredentialCommandDispatchService
import eu.torvian.chatbot.server.worker.credential.DefaultCredentialSyncService
import org.koin.dsl.module

/**
 * Dependency injection module for worker credential profiles and secrets.
 *
 * Provides the persistence services, the encryption service that wraps values to a worker
 * certificate, the best-effort command dispatch, and the sync service that owns the
 * persist-then-dispatch policy.
 */
fun credentialServiceModule() = module {
    single<CredentialSecretEncryptionService> { DefaultCredentialSecretEncryptionService(get()) }
    single<WorkerCredentialProfileService> {
        WorkerCredentialProfileServiceImpl(
            profileDao = get(),
            profileSignatureDao = get(),
            agentRoleLinkDao = get(),
            secretDao = get(),
            userDeviceDao = get(),
            workerDao = get(),
            agentRoleDao = get(),
            transactionScope = get(),
        )
    }
    single<WorkerCredentialSecretService> { WorkerCredentialSecretServiceImpl(get(), get(), get(), get()) }
    single<CredentialCommandDispatchService> { DefaultCredentialCommandDispatchService(get()) }
    single<CredentialSyncService> { DefaultCredentialSyncService(get(), get(), get()) }
}
