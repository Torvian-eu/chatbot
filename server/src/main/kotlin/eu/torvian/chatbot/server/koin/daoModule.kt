package eu.torvian.chatbot.server.koin

import eu.torvian.chatbot.server.data.dao.*
import eu.torvian.chatbot.server.data.dao.exposed.ApiSecretDaoExposed
import eu.torvian.chatbot.server.data.dao.exposed.GroupDaoExposed
import eu.torvian.chatbot.server.data.dao.exposed.GroupOwnershipDaoExposed
import eu.torvian.chatbot.server.data.dao.exposed.LLMProviderDaoExposed
import eu.torvian.chatbot.server.data.dao.exposed.MessageDaoExposed
import eu.torvian.chatbot.server.data.dao.exposed.ModelDaoExposed
import eu.torvian.chatbot.server.data.dao.exposed.SessionDaoExposed
import eu.torvian.chatbot.server.data.dao.exposed.SessionOwnershipDaoExposed
import eu.torvian.chatbot.server.data.dao.exposed.SettingsDaoExposed
import eu.torvian.chatbot.server.data.dao.exposed.UserDaoExposed
import eu.torvian.chatbot.server.data.dao.exposed.UserGroupDaoExposed
import eu.torvian.chatbot.server.data.dao.exposed.UserSessionDaoExposed
import eu.torvian.chatbot.server.data.dao.exposed.RoleDaoExposed
import eu.torvian.chatbot.server.data.dao.exposed.PermissionDaoExposed
import eu.torvian.chatbot.server.data.dao.exposed.UserRoleAssignmentDaoExposed
import eu.torvian.chatbot.server.data.dao.exposed.RolePermissionDaoExposed
import eu.torvian.chatbot.server.utils.transactions.TransactionScope
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

    // Role and permission DAOs
    single<RoleDao> { RoleDaoExposed(get()) }
    single<PermissionDao> { PermissionDaoExposed(get()) }
    single<UserRoleAssignmentDao> { UserRoleAssignmentDaoExposed(get()) }
    single<RolePermissionDao> { RolePermissionDaoExposed(get()) }
}
