package eu.torvian.chatbot.server.koin

import eu.torvian.chatbot.common.models.llm.LLMProviderType
import eu.torvian.chatbot.server.service.core.*
import eu.torvian.chatbot.server.service.core.agent.DefaultSystemPromptComposer
import eu.torvian.chatbot.server.service.core.agent.SystemPromptComposer
import eu.torvian.chatbot.server.service.core.chat.compaction.ApproximateChatInputTokenCounter
import eu.torvian.chatbot.server.service.core.chat.compaction.ChatInputTokenCounter
import eu.torvian.chatbot.server.service.core.chat.compaction.AuxiliaryCompactionConfigResolver
import eu.torvian.chatbot.server.service.core.chat.compaction.AuxiliaryCompactionSummarizer
import eu.torvian.chatbot.server.service.core.chat.compaction.CompactionPreferenceService
import eu.torvian.chatbot.server.service.core.chat.compaction.ConversationCompactionService
import eu.torvian.chatbot.server.service.core.chat.compaction.ConversationManualCompactionService
import eu.torvian.chatbot.server.service.core.chat.compaction.DefaultAuxiliaryCompactionConfigResolver
import eu.torvian.chatbot.server.service.core.chat.compaction.DefaultCompactionPreferenceService
import eu.torvian.chatbot.server.service.core.chat.compaction.DefaultConversationCompactionService
import eu.torvian.chatbot.server.service.core.chat.compaction.DefaultConversationManualCompactionService
import eu.torvian.chatbot.server.service.core.chat.compaction.DefaultEffectiveCompactionConfigResolver
import eu.torvian.chatbot.server.service.core.chat.compaction.EffectiveCompactionConfigResolver
import eu.torvian.chatbot.server.service.core.chat.content.DefaultFileReferenceContentBuilder
import eu.torvian.chatbot.server.service.core.chat.content.DefaultToolResultContentBuilder
import eu.torvian.chatbot.server.service.core.chat.content.FileReferenceContentBuilder
import eu.torvian.chatbot.server.service.core.chat.content.ToolResultContentBuilder
import eu.torvian.chatbot.server.service.core.chat.context.ChatContextBuilder
import eu.torvian.chatbot.server.service.core.chat.context.DefaultChatContextBuilder
import eu.torvian.chatbot.server.service.core.chat.persistence.ConversationTurnPersistence
import eu.torvian.chatbot.server.service.core.chat.persistence.DefaultConversationTurnPersistence
import eu.torvian.chatbot.server.service.core.chat.preparation.ConversationTurnPreparationService
import eu.torvian.chatbot.server.service.core.chat.preparation.DefaultConversationTurnPreparationService
import eu.torvian.chatbot.server.service.core.chat.turn.ConversationTurnOrchestrator
import eu.torvian.chatbot.server.service.core.chat.turn.DefaultConversationTurnOrchestrator
import eu.torvian.chatbot.server.service.core.impl.*
import eu.torvian.chatbot.server.service.llm.ChatCompletionStrategyResolver
import eu.torvian.chatbot.server.service.llm.DefaultReasoningCapabilityRecorder
import eu.torvian.chatbot.server.service.llm.ReasoningCapabilityRecorder
import eu.torvian.chatbot.server.service.llm.strategy.OllamaChatStrategy
import eu.torvian.chatbot.server.service.llm.strategy.OpenAIChatStrategy
import eu.torvian.chatbot.server.service.llm.strategy.ResponsesStrategy
import org.koin.dsl.module

/**
 * Dependency injection module for the chat turn pipeline.
 *
 * Provides the per-provider completion strategies and their dialect resolver, the content builders
 * and context assembler used to build a turn, the turn preparation, persistence and orchestration
 * services, and the automatic and manual conversation compaction services.
 */
fun chatServiceModule() = module {
    // --- Chat completion strategies and the shared dialect resolver ---
    // Bound here (rather than in mainModule) so the input token counter, the compaction resolver and
    // the test container resolve the identical dialect-selection rule as the HTTP client.
    single<OpenAIChatStrategy> { OpenAIChatStrategy(get()) }
    single<OllamaChatStrategy> { OllamaChatStrategy(get()) }
    single<ResponsesStrategy> { ResponsesStrategy(get()) }
    single<ChatCompletionStrategyResolver> {
        ChatCompletionStrategyResolver(
            strategies = mapOf(
                LLMProviderType.OPENAI to get<OpenAIChatStrategy>(),
                LLMProviderType.OPENROUTER to get<OpenAIChatStrategy>(),
                LLMProviderType.OLLAMA to get<OllamaChatStrategy>(),
            ),
            responsesStrategy = get<ResponsesStrategy>()
        )
    }

    single<FileReferenceContentBuilder> { DefaultFileReferenceContentBuilder() }
    single<ToolResultContentBuilder> { DefaultToolResultContentBuilder() }
    single<ChatContextBuilder> { DefaultChatContextBuilder(get(), get()) }
    single<ConversationTurnPersistence> { DefaultConversationTurnPersistence(get(), get(), get(), get()) }
    single<ReasoningCapabilityRecorder> { DefaultReasoningCapabilityRecorder(get()) }
    single<ConversationTurnPreparationService> {
        DefaultConversationTurnPreparationService(
            messageDao = get(),
            sessionDao = get(),
            toolService = get(),
            llmModelService = get(),
            modelSettingsService = get(),
            llmProviderService = get(),
            credentialManager = get(),
            agentRoleService = get(),
            effectiveCompactionConfigResolver = get(),
            systemPromptComposer = get(),
            transactionScope = get()
        )
    }
    single<ConversationTurnOrchestrator> {
        DefaultConversationTurnOrchestrator(get(), get(), get(), get(), get(), get(), get())
    }
    // --- Automated conversation compaction ---
    single<ChatInputTokenCounter> {
        ApproximateChatInputTokenCounter(strategyResolver = get(), json = get())
    }
    single<AuxiliaryCompactionConfigResolver> {
        DefaultAuxiliaryCompactionConfigResolver(
            llmModelService = get(),
            modelSettingsService = get(),
            llmProviderService = get(),
            credentialManager = get()
        )
    }
    single<EffectiveCompactionConfigResolver> {
        DefaultEffectiveCompactionConfigResolver(
            modelPresetDao = get(),
            userPreferenceDao = get(),
            json = get()
        )
    }
    single<CompactionPreferenceService> {
        DefaultCompactionPreferenceService(
            json = get(),
            userPreferenceDao = get(),
            authorizationService = get(),
            modelSettingsService = get(),
            transactionScope = get()
        )
    }
    single { AuxiliaryCompactionSummarizer(llmApiClient = get()) }
    single<ConversationCompactionService> {
        DefaultConversationCompactionService(
            chunkDao = get(),
            auxiliaryConfigResolver = get(),
            tokenCounter = get(),
            summarizer = get()
        )
    }
    single<ConversationManualCompactionService> {
        DefaultConversationManualCompactionService(
            preparationService = get(),
            conversationTurnPersistence = get(),
            chatContextBuilder = get(),
            chunkDao = get(),
            compactionService = get()
        )
    }

    single<ChatService> { ChatServiceImpl(get(), get()) }

    single<SystemPromptComposer> { DefaultSystemPromptComposer() }
}
