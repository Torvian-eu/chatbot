Okay, here is the code for the first Pull Request, **PR 1: Common Module - Shared Data Models (DTOs)**, assigned to Alex for implementation and Eric and Mark for review.

This PR focuses solely on defining the necessary data classes (DTOs) in the `common` Gradle module. These models are marked with `@Serializable` so they can be used for JSON communication between the frontend (`app`) and backend (`server`) via Ktor, and also represent the structure of data persisted in the database (though the DAO layer will handle mapping to/from Exposed's format). They include the fields required for V1.1 features like message threading and session grouping.

All classes, interfaces, and public properties/methods have KDoc documentation explaining their purpose.

```kotlin
// file: common/src/main/kotlin/eu/torvian/chatbot/common/models/ChatSession.kt
package eu.torvian.chatbot.common.models

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * Represents a single chat session or conversation.
 * Used as a shared data model between frontend and backend API communication.
 * Corresponds to the `ChatSession` schema in the OpenAPI specification.
 *
 * @property id Unique identifier for the session (Database PK).
 * @property name The name or title of the session.
 * @property createdAt Timestamp when the session was created.
 * @property updatedAt Timestamp when the session was last updated (e.g., message added).
 * @property groupId Optional ID referencing a parent group (null if ungrouped). Supports session grouping (V1.1).
 * @property currentModelId Optional ID of the currently selected LLM model for this session.
 * @property currentSettingsId Optional ID of the currently selected settings profile for this session.
 * @property currentLeafMessageId The current leaf message ID in the session's active branch, used by the UI for displaying the correct thread branch (null only when no messages exist). Supports message threading UI (V1.1).
 * @property messages List of messages within this session (included when loading full details). Populated by the backend when retrieving a specific session.
 */
@Serializable
data class ChatSession(
    val id: Long,
    val name: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val groupId: Long?, // Reference to ChatGroup.id (V1.1 grouping)
    val currentModelId: Long?,
    val currentSettingsId: Long?,
    val currentLeafMessageId: Long?, // Used for threading UI state (V1.1 threading)
    val messages: List<ChatMessage> = emptyList() // Populated by getSessionDetails API call
)
```

```kotlin
// file: common/src/main/kotlin/eu/torvian/chatbot/common/models/ChatSessionSummary.kt
package eu.torvian.chatbot.common.models

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * Represents a summary of a chat session, typically used for listing sessions
 * in the UI without loading all message details.
 * Used as a shared data model between frontend and backend API communication.
 * Corresponds to the `ChatSessionSummary` schema in the OpenAPI specification.
 *
 * @property id Unique identifier for the session (Database PK).
 * @property name The name or title of the session.
 * @property createdAt Timestamp when the session was created.
 * @property updatedAt Timestamp when the session was last updated.
 * @property groupId Optional ID referencing a parent group session (null if ungrouped). Supports session grouping (V1.1).
 * @property groupName Optional name of the group (null if ungrouped). Included for convenience in lists (V1.1 grouping).
 */
@Serializable
data class ChatSessionSummary(
    val id: Long,
    val name: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val groupId: Long?, // Reference to ChatGroup.id (V1.1 grouping)
    val groupName: String? = null // Name of the referenced group (V1.1 grouping)
)
```

```kotlin
// file: common/src/main/kotlin/eu/torvian/chatbot/common/models/ChatMessage.kt
package eu.torvian.chatbot.common.models

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * Represents a single message within a chat session.
 *
 * Supports both user and assistant messages and includes threading information.
 * Uses a sealed class structure with a JSON class discriminator based on the 'role' field.
 * Used as a shared data model between frontend and backend API communication.
 * Corresponds to the `ChatMessage` schema and its subtypes (`UserMessage`, `AssistantMessage`)
 * in the OpenAPI specification.
 *
 * @property id Unique identifier for the message (Database PK).
 * @property sessionId ID of the session this message belongs to (Database FK).
 * @property role The role of the message sender (e.g., "user", "assistant"). Used as the discriminator.
 * @property content The content of the message.
 * @property createdAt Timestamp when the message was created.
 * @property updatedAt Timestamp when the message was last updated (e.g., edited).
 * @property parentMessageId Optional ID of the parent message. Null for root messages of threads. Supports message threading (V1.1).
 * @property childrenMessageIds List of child message IDs. Empty for leaf messages. Stored in DB, managed by Service/DAO (V1.1 threading).
 */
@Serializable
@JsonClassDiscriminator("role") // Discriminator for serialization/deserialization
sealed class ChatMessage {
    abstract val id: Long
    abstract val sessionId: Long
    abstract val role: Role
    abstract val content: String
    abstract val createdAt: Instant
    abstract val updatedAt: Instant
    abstract val parentMessageId: Long? // V1.1 Threading
    abstract val childrenMessageIds: List<Long> // V1.1 Threading

    /**
     * Represents a message sent by the user.
     * Corresponds to the `UserMessage` schema in the OpenAPI specification.
     */
    @Serializable
    data class UserMessage(
        override val id: Long,
        override val sessionId: Long,
        override val content: String,
        override val createdAt: Instant,
        override val updatedAt: Instant,
        override val parentMessageId: Long?, // V1.1 Threading
        override val childrenMessageIds: List<Long> = emptyList() // V1.1 Threading
    ) : ChatMessage() {
        override val role: Role = Role.USER
    }

    /**
     * Represents a message sent by the assistant (LLM).
     * Includes details about the model and settings used for generation.
     * Corresponds to the `AssistantMessage` schema in the OpenAPI specification.
     */
    @Serializable
    data class AssistantMessage(
        override val id: Long,
        override val sessionId: Long,
        override val content: String,
        override val createdAt: Instant,
        override val updatedAt: Instant,
        override val parentMessageId: Long?, // V1.1 Threading
        override val childrenMessageIds: List<Long> = emptyList(), // V1.1 Threading
        val modelId: Long?, // Model used
        val settingsId: Long? // Settings profile used
    ) : ChatMessage() {
        override val role: Role = Role.ASSISTANT
    }

    /**
     * Enum defining the roles of message senders.
     */
    enum class Role {
        USER, ASSISTANT
    }
}
```

```kotlin
// file: common/src/main/kotlin/eu/torvian/chatbot/common/models/ChatGroup.kt
package eu.torvian.chatbot.common.models

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * Represents a user-defined group for organizing chat sessions.
 * Used as a shared data model between frontend and backend API communication.
 * Supports chat session grouping (V1.1).
 * Corresponds to the `ChatGroup` schema in the OpenAPI specification.
 *
 * @property id Unique identifier for the group (Database PK).
 * @property name The name of the group.
 * @property createdAt Timestamp when the group was created.
 */
@Serializable
data class ChatGroup(
    val id: Long,
    val name: String,
    val createdAt: Instant // Assuming we track creation time for ordering/info
)
```

```kotlin
// file: common/src/main/kotlin/eu/torvian/chatbot/common/models/LLMModel.kt
package eu.torvian.chatbot.common.models

import kotlinx.serialization.Serializable

/**
 * Represents a configured LLM model endpoint.
 * Used as a shared data model between frontend and backend API communication.
 * Corresponds to the `LLMModel` schema in the OpenAPI specification.
 *
 * @property id Unique identifier for the model (Database PK).
 * @property name The display name of the model.
 * @property baseUrl The base URL for the LLM API endpoint (e.g., "https://api.openai.com").
 * @property apiKeyId Reference ID to the securely stored API key (null if not required or configured).
 *                   The raw API key is NOT exposed in this model for security. Supports secure key handling (V1.1).
 * @property type The type of LLM provider (e.g., "openai", "openrouter", "custom").
 */
@Serializable
data class LLMModel(
    val id: Long,
    val name: String,
    val baseUrl: String,
    val apiKeyId: String?, // References secure storage (V1.1 security)
    val type: String // e.g., "openai", "openrouter", "custom"
)
```

```kotlin
// file: common/src/main/kotlin/eu/torvian/chatbot/common/models/ModelSettings.kt
package eu.torvian.chatbot.common.models

import kotlinx.serialization.Serializable

/**
 * Represents a specific settings profile for an LLM model.
 * Allows configuring parameters like temperature, system message, etc.
 * Used as a shared data model between frontend and backend API communication.
 * Corresponds to the `ModelSettings` schema in the OpenAPI specification.
 *
 * @property id Unique identifier for the settings profile (Database PK).
 * @property modelId Foreign key to the associated [LLMModel].
 * @property name The display name of the settings profile (e.g., "Default", "Creative").
 * @property systemMessage The system message/prompt to include in the conversation context.
 * @property temperature Sampling temperature for text generation.
 * @property maxTokens Maximum number of tokens to generate in the response.
 * @property customParamsJson Arbitrary model-specific parameters stored as a JSON string.
 */
@Serializable
data class ModelSettings(
    val id: Long,
    val modelId: Long, // Foreign key to LLMModel.id
    val name: String,
    val systemMessage: String? = null,
    val temperature: Float? = null,
    val maxTokens: Int? = null,
    val customParamsJson: String? = null // Arbitrary JSON for extra params
)
```

```kotlin
// file: common/src/main/kotlin/eu/torvian/chatbot/common/models/CreateSessionRequest.kt
package eu.torvian.chatbot.common.models

import kotlinx.serialization.Serializable

/**
 * Request body for creating a new chat session.
 * Corresponds to the `CreateSessionRequest` schema in the OpenAPI specification.
 *
 * @property name Optional name for the new session.
 */
@Serializable
data class CreateSessionRequest(val name: String? = null) // name is optional
```

```kotlin
// file: common/src/main/kotlin/eu/torvian/chatbot/common/models/ProcessNewMessageRequest.kt
package eu.torvian.chatbot.common.models

import kotlinx.serialization.Serializable

/**
 * Request body for sending a new message to a chat session.
 * Corresponds to the `ProcessNewMessageRequest` schema in the OpenAPI specification.
 * Supports message threading (V1.1) by allowing a parent message ID.
 *
 * @property content The user's message content.
 * @property parentMessageId The ID of the message this is a reply to (null for initial messages or if replying to the root of a new thread branch). Supports message threading (V1.1).
 */
@Serializable
data class ProcessNewMessageRequest(
    val content: String,
    val parentMessageId: Long? = null // V1.1 Threading
)
```

```kotlin
// file: common/src/main/kotlin/eu/torvian/chatbot/common/models/UpdateSessionRequest.kt
package eu.torvian.chatbot.common.models

import kotlinx.serialization.Serializable

/**
 * Request body for updating chat session details.
 * Corresponds to the `UpdateSessionRequest` schema in the OpenAPI specification.
 * Allows updating fields like name, current model, and settings.
 * Group assignment uses a dedicated endpoint and request body.
 * Note: Fields are nullable to allow partial updates.
 *
 * @property name New name for the session (optional).
 * @property currentModelId New selected model ID for the session (optional).
 * @property currentSettingsId New selected settings ID for the session (optional).
 */
@Serializable
data class UpdateSessionRequest(
    val name: String? = null,
    val currentModelId: Long? = null,
    val currentSettingsId: Long? = null
)
```

```kotlin
// file: common/src/main/kotlin/eu/torvian/chatbot/common/models/AssignSessionToGroupRequest.kt
package eu.torvian.chatbot.common.models

import kotlinx.serialization.Serializable

/**
 * Request body for assigning a chat session to a group.
 * Corresponds to the `AssignSessionToGroupRequest` schema in the OpenAPI specification.
 * Supports session grouping (V1.1).
 *
 * @property groupId The ID of the group to assign the session to, or null to ungroup the session.
 */
@Serializable
data class AssignSessionToGroupRequest(
    val groupId: Long? // Nullable to support ungrouping
)
```

```kotlin
// file: common/src/main/kotlin/eu/torvian/chatbot/common/models/UpdateMessageRequest.kt
package eu.torvian.chatbot.common.models

import kotlinx.serialization.Serializable

/**
 * Request body for updating the content of an existing message.
 * Corresponds to the `UpdateMessageRequest` schema in the OpenAPI specification.
 * Supports message editing (V1.1).
 *
 * @property content The new text content for the message.
 */
@Serializable
data class UpdateMessageRequest(
    val content: String
)
```

```kotlin
// file: common/src/main/kotlin/eu/torvian/chatbot/common/models/AddModelRequest.kt
package eu.torvian.chatbot.common.models

import kotlinx.serialization.Serializable

/**
 * Request body for adding a new LLM model configuration.
 * Corresponds to the `AddModelRequest` schema in the OpenAPI specification.
 * Includes the raw API key which is handled securely by the backend. Supports model configuration (V1.1).
 *
 * @property name The display name of the model.
 * @property baseUrl The base URL for the LLM API endpoint.
 * @property type The type of LLM provider.
 * @property apiKey The raw API key provided by the user. Passed once for secure storage by the backend.
 */
@Serializable
data class AddModelRequest(
    val name: String,
    val baseUrl: String,
    val type: String,
    val apiKey: String? = null // Raw key, handled securely by backend
)
```

```kotlin
// file: common/src/main/kotlin/eu/torvian/chatbot/common/models/UpdateModelRequest.kt
package eu.torvian.chatbot.common.models

import kotlinx.serialization.Serializable

/**
 * Request body for updating an existing LLM model configuration.
 * Corresponds to the `UpdateModelRequest` schema in the OpenAPI specification.
 * Allows updating various model details and optionally providing a new API key.
 * Note: Fields are nullable to allow partial updates. Supports model configuration (V1.1).
 *
 * @property id The ID of the model being updated. Required.
 * @property name New display name (optional).
 * @property baseUrl New base URL (optional).
 * @property type New type (optional).
 * @property apiKey Provide a new raw API key string here to update the stored key (optional). Omit or send null/empty string to keep the existing key.
 */
@Serializable
data class UpdateModelRequest(
    val id: Long, // Model ID is required in the request body for clarity
    val name: String? = null,
    val baseUrl: String? = null,
    val type: String? = null,
    val apiKey: String? = null // New raw key, handled securely by backend if provided
)
```

```kotlin
// file: common/src/main/kotlin/eu/torvian/chatbot/common/models/ApiKeyStatusResponse.kt
package eu.torvian.chatbot.common.models

import kotlinx.serialization.Serializable

/**
 * Response body for checking API key configuration status for a model.
 * Corresponds to the `ApiKeyStatusResponse` schema in the OpenAPI specification.
 * Supports secure key handling UI (V1.1).
 *
 * @property isConfigured True if an API key is securely stored for this model, false otherwise.
 */
@Serializable
data class ApiKeyStatusResponse(
    val isConfigured: Boolean
)
```

```kotlin
// file: common/src/main/kotlin/eu/torvian/chatbot/common/models/AddModelSettingsRequest.kt
package eu.torvian.chatbot.common.models

import kotlinx.serialization.Serializable

/**
 * Request body for adding a new settings profile for an LLM model.
 * Corresponds to the `AddModelSettingsRequest` schema in the OpenAPI specification.
 * Note: The model ID is part of the URL path for this endpoint. Supports settings configuration (V1.1).
 *
 * @property name The display name of the settings profile.
 * @property systemMessage The system message/prompt (optional).
 * @property temperature Sampling temperature (optional).
 * @property maxTokens Maximum tokens (optional).
 * @property customParamsJson Arbitrary model-specific parameters as JSON string (optional).
 */
@Serializable
data class AddModelSettingsRequest(
    val name: String,
    val systemMessage: String? = null,
    val temperature: Float? = null,
    val maxTokens: Int? = null,
    val customParamsJson: String? = null
)
```

```kotlin
// file: common/src/main/kotlin/eu/torvian/chatbot/common/models/UpdateSettingsRequest.kt
package eu.torvian.chatbot.common.models

import kotlinx.serialization.Serializable

/**
 * Request body for updating an existing settings profile.
 * Corresponds to the `UpdateSettingsRequest` schema in the OpenAPI specification.
 * Allows updating parameters for a settings profile. Note: Fields are nullable for partial updates.
 * Supports settings configuration (V1.1).
 *
 * @property id The ID of the settings profile being updated. Required.
 * @property name New display name (optional).
 * @property systemMessage New system message (optional).
 * @property temperature New temperature (optional).
 * @property maxTokens New max tokens (optional).
 * @property customParamsJson New custom parameters as JSON string (optional).
 */
@Serializable
data class UpdateSettingsRequest(
    val id: Long, // Settings ID is required in the request body for clarity
    val name: String? = null,
    val systemMessage: String? = null,
    val temperature: Float? = null,
    val maxTokens: Int? = null,
    val customParamsJson: String? = null
)
```

```kotlin
// file: common/src/main/kotlin/eu/torvian/chatbot/common/models/CreateGroupRequest.kt
package eu.torvian.chatbot.common.models

import kotlinx.serialization.Serializable

/**
 * Request body for creating a new chat session group.
 * Corresponds to the `CreateGroupRequest` schema in the OpenAPI specification.
 * Supports session grouping (V1.1).
 *
 * @property name The name for the new group.
 */
@Serializable
data class CreateGroupRequest(
    val name: String
)
```

```kotlin
// file: common/src/main/kotlin/eu/torvian/chatbot/common/models/RenameGroupRequest.kt
package eu.torvian.chatbot.common.models

import kotlinx.serialization.Serializable

/**
 * Request body for renaming a chat session group.
 * Corresponds to the `RenameGroupRequest` schema in the OpenAPI specification.
 * Supports session grouping (V1.1).
 * Note: The group ID is part of the URL path for this endpoint.
 *
 * @property name The new name for the group.
 */
@Serializable
data class RenameGroupRequest(
    val name: String
)
```

This concludes the code for PR 1. Alex will create these files in the `common` module, add the necessary dependencies (kotlinx-serialization, kotlinx-datetime), and submit the PR for review.