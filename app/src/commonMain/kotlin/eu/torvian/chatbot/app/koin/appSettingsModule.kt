package eu.torvian.chatbot.app.koin

import eu.torvian.chatbot.app.repository.*
import eu.torvian.chatbot.app.service.auth.*
import eu.torvian.chatbot.app.service.clipboard.ClipboardService
import eu.torvian.chatbot.app.service.misc.EventBus
import eu.torvian.chatbot.app.viewmodel.*
import eu.torvian.chatbot.app.viewmodel.admin.UserGroupManagementViewModel
import eu.torvian.chatbot.app.viewmodel.admin.UserManagementViewModel
import eu.torvian.chatbot.app.viewmodel.auth.*
import eu.torvian.chatbot.app.viewmodel.chat.*
import eu.torvian.chatbot.app.viewmodel.chat.state.ChatState
import eu.torvian.chatbot.app.viewmodel.common.CoroutineScopeProvider
import eu.torvian.chatbot.app.viewmodel.common.NotificationService
import eu.torvian.chatbot.app.viewmodel.sessionstatus.SessionTurnStatusRegistry
import eu.torvian.chatbot.app.viewmodel.settings.*
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.core.parameter.parametersOf
import org.koin.dsl.module

/**
 * Koin module declaring the ViewModels behind every screen: authentication and account screens,
 * chat, session list, configuration of models, providers, instructions, roles, presets, projects,
 * tools and workers, user and group administration, preferences, notifications and search.
 *
 * @return A Koin module with the application's ViewModels.
 */
fun appSettingsModule(): Module = module {
    // Provide ViewModels, injecting the required dependencies
    viewModel {
        val scopeProvider = get<CoroutineScopeProvider>()
        val normalScope = scopeProvider.createNormalScope()
        val backgroundScope = scopeProvider.createBackgroundScope()
        val chatState = get<ChatState> { parametersOf(backgroundScope) }

        ChatViewModel(
            state = chatState,
            loadSessionUC = get { parametersOf(chatState, backgroundScope) },
            sendMessageUC = get { parametersOf(chatState) },
            replyUC = get { parametersOf(chatState) },
            editMessageUC = get { parametersOf(chatState) },
            deleteMessageUC = get { parametersOf(chatState) },
            insertMessageUC = get { parametersOf(chatState) },
            switchBranchUC = get { parametersOf(chatState) },
            compactConversationUC = get { parametersOf(chatState, normalScope) },
            selectAgentRoleUC = get { parametersOf(chatState) },
            loadAgentRolesUC = get { parametersOf(chatState) },
            selectProjectUC = get { parametersOf(chatState) },
            loadProjectsUC = get { parametersOf(chatState) },
            updateInputUC = get { parametersOf(chatState) },
            copyToClipboardUC = get { parametersOf(chatState) },
            fileReferenceUC = get { parametersOf(chatState, normalScope) },
            navigationState = get(),
            normalScope = normalScope,
            backgroundScope = backgroundScope,
        )
    }
    viewModel {
        val scopeProvider = get<CoroutineScopeProvider>()
        val normalScope = scopeProvider.createNormalScope()
        AuthEntryViewModel(
            get<AuthRepository>(),
            get<NotificationService>(),
            normalScope,
            get<AuthValidationService>()
        )
    }
    viewModel {
        val scopeProvider = get<CoroutineScopeProvider>()
        val normalScope = scopeProvider.createNormalScope()
        SessionViewModel(
            get<AuthRepository>(),
            get<NotificationService>(),
            normalScope
        )
    }
    viewModel {
        val scopeProvider = get<CoroutineScopeProvider>()
        val normalScope = scopeProvider.createNormalScope()
        AccountManagementViewModel(
            get<AuthRepository>(),
            get<NotificationService>(),
            normalScope
        )
    }
    viewModel {
        val scopeProvider = get<CoroutineScopeProvider>()
        val normalScope = scopeProvider.createNormalScope()
        SecurityAuditViewModel(
            get<AuthRepository>(),
            get<NotificationService>(),
            get<ClipboardService>(),
            normalScope
        )
    }
    viewModel {
        val scopeProvider = get<CoroutineScopeProvider>()
        val normalScope = scopeProvider.createNormalScope()
        UserProfileViewModel(
            get<AuthRepository>(),
            get<NotificationService>(),
            get<AuthValidationService>(),
            normalScope
        )
    }
    viewModel {
        SessionListViewModel(
            get<SessionRepository>(),
            get<GroupRepository>(),
            get<EventBus>(),
            get<SessionSelectionController>(),
            get(),
            get<SessionTurnStatusRegistry>()
        )
    }
    viewModel {
        ProviderConfigViewModel(
            get<ProviderRepository>(),
            get<UserGroupRepository>(),
            get<NotificationService>()
        )
    }
    viewModel {
        ModelConfigViewModel(
            get<ModelRepository>(),
            get<ProviderRepository>(),
            get<UserGroupRepository>(),
            get<NotificationService>()
        )
    }
    viewModel {
        ModelSettingsViewModel(
            get<ModelSettingsRepository>(),
            get<ModelRepository>(),
            get<UserGroupRepository>(),
            get<NotificationService>()
        )
    }
    viewModel {
        InstructionsViewModel(
            instructionRepository = get(),
            agentRoleRepository = get(),
            projectRepository = get(),
            notificationService = get()
        )
    }
    viewModel {
        AgentRolesViewModel(
            agentRoleRepository = get(),
            instructionRepository = get(),
            modelPresetRepository = get(),
            modelRepository = get(),
            modelSettingsRepository = get(),
            toolRepository = get(),
            projectRepository = get(),
            workerRepository = get(),
            mcpServerRepository = get(),
            notificationService = get()
        )
    }
    viewModel {
        ModelPresetsViewModel(
            modelPresetRepository = get(),
            modelRepository = get(),
            modelSettingsRepository = get(),
            notificationService = get()
        )
    }
    viewModel {
        ProjectsViewModel(
            projectRepository = get(),
            agentRoleRepository = get(),
            instructionRepository = get(),
            notificationService = get()
        )
    }
    viewModel {
        val scopeProvider = get<CoroutineScopeProvider>()
        val normalScope = scopeProvider.createNormalScope()
        UserManagementViewModel(
            get<UserRepository>(),
            get<RoleRepository>(),
            get<NotificationService>(),
            get<AuthValidationService>(),
            normalScope
        )
    }
    viewModel {
        UserGroupManagementViewModel(
            get<UserGroupRepository>(),
            get<UserRepository>(),
            get<NotificationService>()
        )
    }
    viewModel {
        LocalMCPServerViewModel(
            serverManager = get(),
            mcpToolRepository = get(),
            toolRepository = get(),
            workerRepository = get(),
            notificationService = get()
        )
    }
    viewModel {
        WorkersViewModel(
            workerRepository = get(),
            notificationService = get()
        )
    }
    viewModel {
        BuiltInToolsViewModel(
            workerRepository = get(),
            builtInToolRepository = get(),
            toolRepository = get(),
            notificationService = get()
        )
    }
    viewModel {
        OperatorToolsViewModel(
            operatorToolRepository = get(),
            toolRepository = get(),
            notificationService = get()
        )
    }
    viewModel {
        ServerBuiltInToolsViewModel(
            serverBuiltInToolRepository = get(),
            toolRepository = get(),
            userPreferenceRepository = get(),
            notificationService = get()
        )
    }
    viewModel {
        PreferencesViewModel(
            userPreferenceRepository = get(),
            notificationService = get()
        )
    }
    viewModel {
        E2EASecurityViewModel(
            deviceIdentityService = get(),
            clipboardService = get(),
            notificationService = get()
        )
    }
    viewModel {
        ConversationCompactionViewModel(
            userPreferenceRepository = get(),
            modelRepository = get(),
            modelSettingsRepository = get(),
            notificationService = get()
        )
    }
    viewModel {
        NotificationsViewModel(
            userPreferenceRepository = get(),
            osNotifications = get(),
            notificationService = get()
        )
    }
    viewModel { AppViewModel(get(), get(), get(), get()) }
    viewModel { AboutViewModel() }
    viewModel {
        CrossSessionSearchViewModel(
            searchRepository = get(),
            searchNavigationCoordinator = get(),
            notificationService = get()
        )
    }
}
