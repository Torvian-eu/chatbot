package eu.torvian.chatbot.server.testutils.data

import eu.torvian.chatbot.common.models.core.ChatGroup
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.llm.*
import eu.torvian.chatbot.common.models.user.UserStatus
import eu.torvian.chatbot.common.security.EncryptedSecret
import eu.torvian.chatbot.common.security.EncryptionConfig
import eu.torvian.chatbot.server.data.entities.*
import eu.torvian.chatbot.server.domain.config.DatabaseConfig
import eu.torvian.chatbot.server.service.llm.RawChatMessage
import kotlinx.serialization.json.Json
import java.util.*
import kotlin.time.Instant

/**
 * Predefined domain objects and test data entries for use in tests.
 *
 * This object provides a consistent set of test data values that can be used directly
 * or as a base to `.copy()` from.
 *
 * These values are designed to be reused in test cases and passed into `TestDataSet` to simplify
 * test setup via the `TestDataManager`.
 */
object TestDefaults {

    val DEFAULT_INSTANT: Instant = Instant.parse("2023-01-01T12:00:00Z")
    val DEFAULT_INSTANT_MILLIS: Long = DEFAULT_INSTANT.toEpochMilliseconds()

    // --- Config ---

    fun getDefaultDatabaseConfig() = DatabaseConfig(
        vendor = "sqlite",
        type = "memory",
        filepath = "data/test-${UUID.randomUUID()}.db",
        user = null,
        password = null
    )

    val DEFAULT_ENCRYPTION_CONFIG = EncryptionConfig(
        masterKeys = mapOf(1 to "G2CgJOQQtIC+yfz+LLoDp/osBLUVzW9JE9BrQA0dQFo="),
        keyVersion = 1
    )

    // --- Encrypted Secrets ---

    val encryptedSecret1 = EncryptedSecret(
        encryptedSecret = "ZyJpXUmHp3Yinui9+R2U3YfyLMf8haI57+OqMiTtj4g=", // "sk-new-key"
        encryptedDEK = "QbC54oQAzGbJz0wafIaiKcdxX3NZBNGBtohSNz+rpgJwIk8uAlqN6UASfDPqQGNw9P29hIWDT/7hcpgZdnDBQg==",
        keyVersion = 1
    )

    // --- Test Dataset Entries (representing rows for insertion) ---

    val apiSecret1 = ApiSecretEntity(
        alias = "openai-key",
        encryptedCredential = encryptedSecret1.encryptedSecret,
        wrappedDek = encryptedSecret1.encryptedDEK,
        keyVersion = encryptedSecret1.keyVersion,
        createdAt = DEFAULT_INSTANT_MILLIS,
        updatedAt = DEFAULT_INSTANT_MILLIS
    )

    val chatGroup1 = ChatGroup(
        id = 1L,
        name = "Test Group 1",
        createdAt = DEFAULT_INSTANT
    )

    val chatGroup2 = ChatGroup(
        id = 2L,
        name = "Test Group 2",
        createdAt = DEFAULT_INSTANT
    )

    val llmProvider1 = LLMProvider(
        id = 1L,
        apiKeyId = "openai-key",
        name = "OpenAI Production",
        description = "OpenAI API for production use",
        baseUrl = "https://api.openai.com/v1",
        type = LLMProviderType.OPENAI
    )

    val llmProvider2 = LLMProvider(
        id = 2L,
        apiKeyId = "anthropic-key",
        name = "Anthropic Production",
        description = "Anthropic API for production use",
        baseUrl = "https://api.anthropic.com/v1",
        type = LLMProviderType.ANTHROPIC
    )

    val llmModel1 = LLMModel(
        id = 1L,
        name = "gpt-4",
        providerId = llmProvider1.id,
        active = true,
        displayName = "GPT-4",
        type = LLMModelType.CHAT
    )

    val llmModel2 = LLMModel(
        id = 2L,
        name = "claude-3-sonnet-20240229",
        providerId = llmProvider2.id,
        active = true,
        displayName = "Claude 3 Sonnet",
        type = LLMModelType.CHAT
    )

    val modelSettings1 = ChatModelSettings(
        id = 1L,
        modelId = llmModel1.id,
        name = "Default GPT-4 Settings",
        systemMessage = "You are a helpful assistant.",
        temperature = 0.7f,
        maxTokens = 1000,
        customParams = Json.decodeFromString("""{"frequency_penalty": 0.1}""")
    )

    val modelSettings2 = ChatModelSettings(
        id = 2L,
        modelId = llmModel2.id,
        name = "Default Claude-3 Settings",
        systemMessage = "You are Claude, a helpful AI assistant.",
        temperature = 0.8f,
        maxTokens = 2000,
        customParams = null
    )

    val chatSession1 = ChatSessionEntity(
        id = 1L,
        name = "First Chat Session",
        createdAt = DEFAULT_INSTANT,
        updatedAt = DEFAULT_INSTANT,
        groupId = chatGroup1.id,
        currentModelId = llmModel1.id,
        currentSettingsId = modelSettings1.id
    )

    val chatSession2 = ChatSessionEntity(
        id = 2L,
        name = "Second Chat Session",
        createdAt = DEFAULT_INSTANT,
        updatedAt = DEFAULT_INSTANT,
        groupId = chatGroup2.id,
        currentModelId = llmModel2.id,
        currentSettingsId = modelSettings2.id
    )

    val sessionCurrentLeaf1 = SessionCurrentLeafEntity(
        sessionId = chatSession1.id,
        messageId = 2L
    )

    val sessionCurrentLeaf2 = SessionCurrentLeafEntity(
        sessionId = chatSession2.id,
        messageId = 4L
    )

    val chatMessage1 = ChatMessage.UserMessage(
        id = 1L,
        sessionId = chatSession1.id,
        content = "Hello, what can you help me with today?",
        createdAt = DEFAULT_INSTANT,
        updatedAt = DEFAULT_INSTANT,
        parentMessageId = null,
        childrenMessageIds = listOf(2L)
    )

    val chatMessage2 = ChatMessage.AssistantMessage(
        id = 2L,
        sessionId = chatSession1.id,
        content = "I'm here to help with your questions and tasks. What would you like to know about?",
        createdAt = DEFAULT_INSTANT,
        updatedAt = DEFAULT_INSTANT,
        parentMessageId = 1L,
        childrenMessageIds = emptyList(),
        fileReferences = emptyList(),
        modelId = llmModel1.id,
        settingsId = modelSettings1.id
    )

    val chatMessage3 = ChatMessage.UserMessage(
        id = 3L,
        sessionId = chatSession2.id,
        content = "Can you explain how machine learning works?",
        createdAt = DEFAULT_INSTANT,
        updatedAt = DEFAULT_INSTANT,
        parentMessageId = null,
        childrenMessageIds = listOf(4L),
    )

    val chatMessage4 = ChatMessage.AssistantMessage(
        id = 4L,
        sessionId = chatSession2.id,
        content = "Machine learning is a branch of artificial intelligence...",
        createdAt = DEFAULT_INSTANT,
        updatedAt = DEFAULT_INSTANT,
        parentMessageId = 3L,
        childrenMessageIds = emptyList(),
        fileReferences = emptyList(),
        modelId = llmModel2.id,
        settingsId = modelSettings2.id
    )

    // --- User Data ---

    val user1 = UserEntity(
        id = 1L,
        username = "testuser1",
        passwordHash = "hashedpassword1",
        email = "test1@example.com",
        status = UserStatus.ACTIVE,
        createdAt = Instant.fromEpochMilliseconds(DEFAULT_INSTANT_MILLIS),
        updatedAt = Instant.fromEpochMilliseconds(DEFAULT_INSTANT_MILLIS),
        lastLogin = null
    )

    val user2 = UserEntity(
        id = 2L,
        username = "testuser2",
        passwordHash = "hashedpassword2",
        email = "test2@example.com",
        status = UserStatus.ACTIVE,
        createdAt = Instant.fromEpochMilliseconds(DEFAULT_INSTANT_MILLIS),
        updatedAt = Instant.fromEpochMilliseconds(DEFAULT_INSTANT_MILLIS),
        lastLogin = null
    )

    val userSession1 = UserSessionEntity(
        id = 1L,
        userId = user1.id,
        expiresAt = Instant.fromEpochMilliseconds(System.currentTimeMillis() + (24 * 60 * 60 * 1000)), // 24 hours from now
        createdAt = Instant.fromEpochMilliseconds(DEFAULT_INSTANT_MILLIS),
        lastAccessed = Instant.fromEpochMilliseconds(DEFAULT_INSTANT_MILLIS),
        ipAddress = "127.0.0.1"
    )

    val userGroup1 = UserGroupEntity(
        id = 1L,
        name = "Test User Group 1",
        description = "Test User Group 1"
    )

    val role1 = RoleEntity(
        id = 1L,
        name = "Test Role 1",
        description = "Test Role 1"
    )

    // --- RawChatMessage Test Data ---

    val rawChatMessage1 = RawChatMessage.User(
        content = "Hello, what can you help me with today?"
    )

    val rawChatMessage2 = RawChatMessage.Assistant(
        content = "I'm here to help with your questions and tasks. What would you like to know about?"
    )

    val rawChatMessages = listOf(rawChatMessage1, rawChatMessage2)
}