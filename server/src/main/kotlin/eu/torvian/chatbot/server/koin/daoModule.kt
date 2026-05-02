package eu.torvian.chatbot.server.koin

import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.server.data.dao.*
import eu.torvian.chatbot.server.data.dao.exposed.*
import org.koin.dsl.module

/**
 * Dependency injection module for configuring the application's data access objects (DAOs).
 *
 * This module provides:
 * - Implementations of DAO interfaces using Exposed ORM.
 * - Dependencies required by the DAO implementations, such as [TransactionScope].
 */
fun daoModule() = module {
    // Core DAOs
    single<ApiSecretDao> { ApiSecretDaoExposed(get()) }
    single<GroupDao> { GroupDaoExposed(get()) }
    single<LLMProviderDao> { LLMProviderDaoExposed(get()) }
    single<SessionDao> { SessionDaoExposed(get(), get()) }
    single<MessageDao> { MessageDaoExposed(get()) }
    single<ModelDao> { ModelDaoExposed(get()) }
    single<SettingsDao> { SettingsDaoExposed(get()) }

    // User management DAOs
    single<UserDao> { UserDaoExposed(get()) }
    single<UserGroupDao> { UserGroupDaoExposed(get()) }
    single<UserSessionDao> { UserSessionDaoExposed(get()) }

    // Ownership DAOs
    single<SessionOwnershipDao> { SessionOwnershipDaoExposed(get()) }
    single<GroupOwnershipDao> { GroupOwnershipDaoExposed(get()) }
    single<ProviderOwnershipDao> { ProviderOwnershipDaoExposed(get()) }
    single<ModelOwnershipDao> { ModelOwnershipDaoExposed(get()) }
    single<SettingsOwnershipDao> { SettingsOwnershipDaoExposed(get()) }

    // Access DAOs
    single<ProviderAccessDao> { ProviderAccessDaoExposed(get()) }
    single<ModelAccessDao> { ModelAccessDaoExposed(get()) }
    single<SettingsAccessDao> { SettingsAccessDaoExposed(get()) }

    // Role and permission DAOs
    single<RoleDao> { RoleDaoExposed(get()) }
    single<PermissionDao> { PermissionDaoExposed(get()) }
    single<UserRoleAssignmentDao> { UserRoleAssignmentDaoExposed(get()) }
    single<RolePermissionDao> { RolePermissionDaoExposed(get()) }

    // Tool-related DAOs
    single<ToolDefinitionDao> { ToolDefinitionDaoExposed(get()) }
    single<ToolCallDao> { ToolCallDaoExposed(get()) }
    single<SessionToolConfigDao> { SessionToolConfigDaoExposed(get()) }
    single<UserToolApprovalPreferenceDao> { UserToolApprovalPreferenceDaoExposed(get()) }

    // MCP server DAOs
    single<LocalMCPServerDao> { LocalMCPServerDaoExposed(get()) }
    single<LocalMCPToolDefinitionDao> { LocalMCPToolDefinitionDaoExposed(get()) }

    // Worker DAOs
    single<WorkerDao> { WorkerDaoExposed(get()) }
}
