package eu.torvian.chatbot.server.koin

import eu.torvian.chatbot.common.security.AESCryptoProvider
import eu.torvian.chatbot.common.security.CryptoProvider
import eu.torvian.chatbot.common.security.EncryptionService
import eu.torvian.chatbot.common.security.PasswordValidator
import eu.torvian.chatbot.server.config.AppConfiguration
import eu.torvian.chatbot.server.service.core.*
import eu.torvian.chatbot.server.service.core.impl.*
import eu.torvian.chatbot.server.service.email.LoggingMailService
import eu.torvian.chatbot.server.service.email.MailService
import eu.torvian.chatbot.server.service.email.SmtpMailService
import eu.torvian.chatbot.server.service.security.*
import eu.torvian.chatbot.server.service.security.authorizer.*
import eu.torvian.chatbot.server.service.setup.InitializationCoordinator
import eu.torvian.chatbot.server.service.setup.UserAccountInitializer
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * Dependency injection module for the application's domain services.
 *
 * Provides the CRUD services over sessions, messages, groups, models, providers, roles,
 * instructions, presets, projects, users and preferences, together with the security, authorization,
 * mail and initialization services.
 */
fun serviceModule() = module {
    single<SessionService> { SessionServiceImpl(get(), get(), get(), get(), get(), get()) }
    single<GroupService> { GroupServiceImpl(get(), get(), get(), get()) }
    single<LLMModelService> { LLMModelServiceImpl(get(), get(), get(), get(), get(), get(), get()) }
    single<ModelSettingsService> { ModelSettingsServiceImpl(get(), get(), get(), get(), get(), get(), get()) }
    single<LLMProviderService> { LLMProviderServiceImpl(get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    single<MessageService> { MessageServiceImpl(get(), get(), get()) }
    single<SearchService> { SearchServiceImpl(get()) }

    single<RoleService> { RoleServiceImpl(get(), get(), get()) }
    single<AgentRoleService> {
        AgentRoleServiceImpl(
            agentRoleDao = get(),
            agentRoleToolDao = get(),
            agentRoleSpawnableRoleDao = get(),
            agentRoleOwnershipDao = get(),
            agentRoleDisabledDao = get(),
            instructionDao = get(),
            instructionOwnershipDao = get(),
            agentRoleInstructionDao = get(),
            modelPresetDao = get(),
            settingsDao = get(),
            toolDefinitionDao = get(),
            json = get(),
            transactionScope = get(),
            projectDao = get(),
            sessionDao = get()
        )
    }
    single<InstructionService> {
        InstructionServiceImpl(
            instructionDao = get(),
            instructionOwnershipDao = get(),
            agentRoleInstructionDao = get(),
            transactionScope = get(),
            json = get()
        )
    }
    single<ModelPresetService> {
        ModelPresetServiceImpl(
            modelPresetDao = get(),
            modelPresetOwnershipDao = get(),
            modelDao = get(),
            settingsDao = get(),
            transactionScope = get()
        )
    }
    single<ProjectService> {
        ProjectServiceImpl(
            projectDao = get(),
            projectOwnershipDao = get(),
            projectAgentRoleDao = get(),
            agentRoleDao = get(),
            agentRoleToolDao = get(),
            agentRoleSpawnableRoleDao = get(),
            agentRoleOwnershipDao = get(),
            agentRoleDisabledDao = get(),
            agentRoleInstructionDao = get(),
            sessionDao = get(),
            agentRoleService = get(),
            transactionScope = get()
        )
    }

    single<UserGroupService> { UserGroupServiceImpl(get(), get(), get()) }
    single<UserPreferenceService> { UserPreferenceServiceImpl(get(), get(), get()) }

    single<CryptoProvider> { AESCryptoProvider(get()) }
    single<EncryptionService> { EncryptionService(get()) }
    single<CredentialManager> { DbEncryptedCredentialManager(get(), get()) }
    single<CertificateService> { DefaultCertificateService() }

    single<MailService> {
        val config = get<AppConfiguration>()
        when (config.email.provider.lowercase()) {
            "smtp" -> SmtpMailService(
                fromAddress = config.email.fromAddress,
                properties = config.email.properties
            )

            else -> LoggingMailService(
                fromAddress = config.email.fromAddress
            )
        }
    }

    single<SecurityNotificationService> {
        SecurityNotificationServiceImpl(
            mailService = get(),
            serverUrl = get<AppConfiguration>().serverUrl
        )
    }

    single<PasswordService> {
        BCryptPasswordService(PasswordValidator(get<AppConfiguration>().authPolicy.passwordConfig))
    }
    single<UserService> {
        UserServiceImpl(
            get(), get(), get(), get(), get(), get(), get(), get(), get()
        )
    }
    single<TokenService> {
        TokenServiceImpl(
            userService = get(),
            jwtConfig = get(),
            userSessionDao = get(),
            workerDao = get(),
            authorizationService = get(),
            transactionScope = get()
        )
    }
    single<DeviceTrustService> {
        DeviceTrustServiceImpl(
            userDao = get(),
            userTrustedDeviceDao = get(),
            userSessionDao = get(),
            securityAuditDao = get(),
            deviceVerificationTokenDao = get(),
            securityNotificationService = get(),
            transactionScope = get()
        )
    }
    single<SecurityAuditService> {
        SecurityAuditServiceImpl(
            securityAuditDao = get(),
            userTrustedDeviceDao = get(),
            userSessionDao = get(),
            transactionScope = get()
        )
    }
    single<AccountManagementService> {
        AccountManagementServiceImpl(
            userDao = get(),
            passwordService = get(),
            transactionScope = get()
        )
    }
    single<AuthenticationService> {
        AuthenticationServiceImpl(
            userService = get(),
            passwordService = get(),
            jwtConfig = get(),
            userSessionDao = get(),
            userTrustedDeviceDao = get(),
            userDeviceDao = get(),
            securityAuditDao = get(),
            userDao = get(),
            authorizationService = get(),
            transactionScope = get(),
            accountSecurityMode = get(),
            failedLoginAttemptDao = get(),
            authPolicy = get()
        )
    }
    single<WorkerService> { WorkerServiceImpl(get(), get(), get(), get()) }

    single<ResourceAuthorizer>(named(ResourceType.GROUP.key)) { GroupResourceAuthorizer(get()) }
    single<ResourceAuthorizer>(named(ResourceType.SESSION.key)) { SessionResourceAuthorizer(get()) }
    single<ResourceAuthorizer>(named(ResourceType.PROVIDER.key)) {
        ProviderResourceAuthorizer(get(), get(), get())
    }
    single<ResourceAuthorizer>(named(ResourceType.MODEL.key)) {
        ModelResourceAuthorizer(get(), get(), get())
    }
    single<ResourceAuthorizer>(named(ResourceType.SETTINGS.key)) {
        SettingsResourceAuthorizer(get(), get(), get())
    }

    single<AuthorizationService> {
        AuthorizationServiceImpl(
            getAll<ResourceAuthorizer>().associateBy { it.resourceType },
            get(),
            get(),
            get()
        )
    }

    single<UserAccountInitializer> { UserAccountInitializer(get(), get(), get()) }
    single<InitializationCoordinator> {
        InitializationCoordinator(
            listOf(
                get<UserAccountInitializer>(),
                get<OperatorToolDefinitionSeeder>(),
                get<ServerBuiltInToolDefinitionSeeder>()
            )
        )
    }
}
