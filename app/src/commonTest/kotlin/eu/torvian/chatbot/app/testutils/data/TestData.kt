package eu.torvian.chatbot.app.testutils.data

import arrow.core.Either
import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.core.ChatSession
import kotlin.time.Instant

// Helper to create Instant for tests (using a fixed time)
fun instant(epochSeconds: Long): Instant = Instant.fromEpochSeconds(epochSeconds)

// Helper to create UserMessage
fun userMessage(
    id: Long,
    sessionId: Long,
    content: String,
    createdAt: Instant = instant(id),
    updatedAt: Instant = instant(id),
    parentMessageId: Long? = null,
    childrenMessageIds: List<Long> = emptyList()
): ChatMessage.UserMessage = ChatMessage.UserMessage(
    id = id, sessionId = sessionId, content = content, createdAt = createdAt,
    updatedAt = updatedAt, parentMessageId = parentMessageId, childrenMessageIds = childrenMessageIds
)

// Helper to create AssistantMessage
fun assistantMessage(
    id: Long,
    sessionId: Long,
    content: String,
    createdAt: Instant = instant(id),
    updatedAt: Instant = instant(id),
    parentMessageId: Long? = null,
    childrenMessageIds: List<Long> = emptyList(),
    modelId: Long? = null,
    settingsId: Long? = null
): ChatMessage.AssistantMessage = ChatMessage.AssistantMessage(
    id = id, sessionId = sessionId, content = content, createdAt = createdAt,
    updatedAt = updatedAt, parentMessageId = parentMessageId, childrenMessageIds = childrenMessageIds,
    fileReferences = emptyList(), modelId = modelId, settingsId = settingsId
)

// Helper to create ChatSession
fun chatSession(
    id: Long,
    name: String,
    createdAt: Instant = instant(id),
    updatedAt: Instant = instant(id),
    groupId: Long? = null,
    currentModelId: Long? = null,
    currentSettingsId: Long? = null,
    currentLeafMessageId: Long? = null,
    messages: List<ChatMessage> = emptyList()
): ChatSession = ChatSession(
    id = id, name = name, createdAt = createdAt, updatedAt = updatedAt, groupId = groupId,
    currentModelId = currentModelId, currentSettingsId = currentSettingsId,
    currentLeafMessageId = currentLeafMessageId, messages = messages
)

// Helper to create a generic API error
fun genericApiError(statusCode: Int = 500, code: String = "internal-error", message: String = "Something went wrong"): ApiError {
    return ApiError(statusCode = statusCode, code = code, message = message)
}

// Helper for a "Not Found" error
fun notFoundError(): ApiError = apiError(CommonApiErrorCodes.NOT_FOUND, "Resource not found")

// Helper for an "Invalid Argument" error
fun invalidArgumentError(): ApiError = apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Invalid input")

// Helper for an "Invalid State" error
fun invalidStateError(): ApiError = apiError(CommonApiErrorCodes.INVALID_STATE, "Operation not allowed in current state")


// Example messages for threading tests:
// m1 -> m2 -> m3 (leaf)
//      -> m4 -> m5 (leaf)
// m6 -> m7 (leaf)
val m1 = userMessage(id = 1, sessionId = 100, content = "Hello", parentMessageId = null, childrenMessageIds = listOf(2, 4))
val m2 = assistantMessage(id = 2, sessionId = 100, content = "Hi user 1", parentMessageId = 1, childrenMessageIds = listOf(3))
val m3 = userMessage(id = 3, sessionId = 100, content = "How are you?", parentMessageId = 2, childrenMessageIds = emptyList()) // Leaf
val m4 = assistantMessage(id = 4, sessionId = 100, content = "Hi user 2", parentMessageId = 1, childrenMessageIds = listOf(5))
val m5 = userMessage(id = 5, sessionId = 100, content = "Tell me about cats", parentMessageId = 4, childrenMessageIds = emptyList()) // Leaf
val m6 = userMessage(id = 6, sessionId = 100, content = "New thread", parentMessageId = null, childrenMessageIds = listOf(7))
val m7 = assistantMessage(id = 7, sessionId = 100, content = "Okay, cats are...", parentMessageId = 6, childrenMessageIds = emptyList()) // Leaf

val allMockMessages = listOf(m1, m2, m3, m4, m5, m6, m7)

// Example Sessions
val mockSession1_empty = chatSession(id = 1, name = "Empty Session", currentLeafMessageId = null, messages = emptyList())
val mockSession2_threaded = chatSession(id = 2, name = "Threaded Session", messages = allMockMessages, currentLeafMessageId = m3.id) // Initially on branch m1->m2->m3
val mockSession3_singleMessage = chatSession(id = 3, name = "Single Message", messages = listOf(m1.copy(childrenMessageIds = emptyList())), currentLeafMessageId = m1.id)


// Helper to create Either.Right
fun <R> right(value: R): Either<ApiError, R> = Either.Right(value)

// Helper to create Either.Left
fun <L> left(error: L): Either<L, Nothing> = Either.Left(error)