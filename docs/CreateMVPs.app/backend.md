# Backend Implementation Guide: General-Purpose Desktop Chatbot

**Version:** 1.0
**Date:** May 21, 2025

This document outlines the backend implementation plan for a general-purpose desktop chatbot application built with Kotlin, Compose for Desktop, Ktor, and SQLite, fulfilling the minimum requirements specified.

## 1. API Design

The backend will expose a set of REST endpoints via an embedded Ktor server within the desktop application. The Compose for Desktop frontend will interact with these endpoints using an HTTP client. This architecture keeps the frontend and backend loosely coupled while residing in the same process, facilitating a potential transition to a separate backend service later.

All requests and responses will primarily use JSON payloads.

**Base URL:** `http://localhost:{port}` (The port will be dynamically assigned or configurable)

### Endpoints:

*   **Chat Sessions:**
    *   `GET /api/v1/sessions`
        *   **Description:** Retrieve a list of all chat sessions.
        *   **Response:** `List<ChatSessionSummary>`
        ```json
        [
          { "id": "uuid1", "name": "My first chat", "createdAt": "...", "updatedAt": "..." },
          { "id": "uuid2", "name": "Kotlin questions", "createdAt": "...", "updatedAt": "..." }
        ]
        ```
    *   `POST /api/v1/sessions`
        *   **Description:** Create a new chat session.
        *   **Request Body:** Optional `{"name": "Initial Name"}`.
        *   **Response:** `ChatSession` object of the newly created session.
        ```json
        { "id": "new_uuid", "name": "New Session", "createdAt": "...", "updatedAt": "...", "messages": [] }
        ```
    *   `GET /api/v1/sessions/{id}`
        *   **Description:** Retrieve a specific chat session including all its messages and associated settings.
        *   **Parameters:** `{id}` (UUID of the session).
        *   **Response:** `ChatSession` object.
        ```json
        {
          "id": "session_uuid",
          "name": "Session Name",
          "createdAt": "...",
          "updatedAt": "...",
          "modelId": "model_uuid", // Currently selected model
          "settingsId": "settings_uuid", // Currently selected settings
          "messages": [
            { ...ChatMessage object... },
            { ...ChatMessage object... }
          ]
        }
        ```
    *   `PUT /api/v1/sessions/{id}`
        *   **Description:** Update a specific chat session (e.g., rename, change associated model/settings).
        *   **Parameters:** `{id}` (UUID of the session).
        *   **Request Body:** `{"name": "New Name", "modelId": "new_model_uuid", "settingsId": "new_settings_uuid"}` (fields are optional).
        *   **Response:** Updated `ChatSession` object.
    *   `DELETE /api/v1/sessions/{id}`
        *   **Description:** Delete a chat session and all its messages.
        *   **Parameters:** `{id}` (UUID of the session).
        *   **Response:** Success/Error status (e.g., 204 No Content on success).
    *   `PUT /api/v1/sessions/{id}/group`
        *   **Description:** Assign/Change the group ID for a session.
        *   **Parameters:** `{id}` (UUID of the session).
        *   **Request Body:** `{"groupId": "optional_group_uuid"}` (Use `null` or omit `groupId` to remove from group).
        *   **Response:** Updated `ChatSessionSummary` object.

*   **Chat Messages:**
    *   `POST /api/v1/sessions/{sessionId}/messages`
        *   **Description:** Add a new user message to a session and trigger an LLM response.
        *   **Parameters:** `{sessionId}` (UUID of the session).
        *   **Request Body:** `{"content": "User message text"}`
        *   **Response:** The newly created user message and the subsequent assistant message (as a list).
        ```json
        [
          { "id": "user_msg_uuid", "sessionId": "...", "role": "user", "content": "...", "createdAt": "...", "updatedAt": "...", "modelId": null, "settingsId": null, "sequence": 1 },
          { "id": "assistant_msg_uuid", "sessionId": "...", "role": "assistant", "content": "...", "createdAt": "...", "updatedAt": "...", "modelId": "...", "settingsId": "...", "sequence": 2 }
        ]
        ```
        *   *Note:* The LLM call happens asynchronously from the frontend's perspective after the user message is saved. The frontend might need to poll or use server-sent events (future enhancement) for the assistant message completion. For minimum requirements, a simple approach is to return *both* messages once the assistant response is fully received and saved.
    *   `PUT /api/v1/messages/{id}`
        *   **Description:** Edit the content of a specific message (user or assistant).
        *   **Parameters:** `{id}` (UUID of the message).
        *   **Request Body:** `{"content": "New message text"}`
        *   **Response:** Updated `ChatMessage` object.
    *   `DELETE /api/v1/messages/{id}`
        *   **Description:** Delete a specific message.
        *   **Parameters:** `{id}` (UUID of the message).
        *   **Response:** Success/Error status (e.g., 204 No Content).

*   **LLM Models & Settings:**
    *   `GET /api/v1/models`
        *   **Description:** Retrieve a list of all configured LLM models.
        *   **Response:** `List<LLMModel>` including their associated settings summaries.
        ```json
        [
          {
            "id": "model_uuid1",
            "name": "OpenAI GPT-4",
            "baseUrl": "https://api.openai.com/v1",
            "type": "openai",
            "settings": [ { "id": "s_uuid1", "name": "Default" }, { "id": "s_uuid2", "name": "Creative" } ]
          },
           {
            "id": "model_uuid2",
            "name": "My OpenRouter Model",
            "baseUrl": "https://openrouter.ai/api/v1",
            "type": "openrouter",
             "settings": [ { "id": "s_uuid3", "name": "Default" } ]
          }
        ]
        ```
    *   `POST /api/v1/models`
        *   **Description:** Add a new LLM model configuration. Requires at least a name and base URL. API key is optional initially but needed for calls.
        *   **Request Body:** `{"name": "New Model", "baseUrl": "...", "type": "openai", "apiKey": "sk-..."}` (apiKey handling discussed in Security).
        *   **Response:** The newly created `LLMModel` object (API key should NOT be returned).
    *   `PUT /api/v1/models/{id}`
        *   **Description:** Update an LLM model configuration.
        *   **Parameters:** `{id}` (UUID of the model).
        *   **Request Body:** `{"name": "Updated Name", "baseUrl": "...", "apiKey": "new_sk-..."}` (Partial updates are fine).
        *   **Response:** Updated `LLMModel` object (API key should NOT be returned).
    *   `DELETE /api/v1/models/{id}`
        *   **Description:** Delete an LLM model configuration and all its associated settings.
        *   **Parameters:** `{id}` (UUID of the model).
        *   **Response:** Success/Error status.
    *   `GET /api/v1/settings/{id}`
        *   **Description:** Retrieve details for a specific Model Settings configuration.
        *   **Parameters:** `{id}` (UUID of the settings).
        *   **Response:** `ModelSettings` object.
        ```json
        {
           "id": "settings_uuid",
           "modelId": "model_uuid",
           "name": "Default",
           "systemMessage": "You are a helpful assistant.",
           "temperature": 0.7,
           "maxTokens": 500,
           "customParams": { "stop": ["\n---"], "seed": 123 } // Store arbitrary JSON
        }
        ```
    *   `POST /api/v1/models/{modelId}/settings`
        *   **Description:** Add a new settings configuration for a specific model.
        *   **Parameters:** `{modelId}` (UUID of the model).
        *   **Request Body:** `{"name": "New Settings Name", "systemMessage": "...", "temperature": ..., "customParams": {...}}`.
        *   **Response:** The newly created `ModelSettings` object.
    *   `PUT /api/v1/settings/{id}`
        *   **Description:** Update a specific Model Settings configuration.
        *   **Parameters:** `{id}` (UUID of the settings).
        *   **Request Body:** `{"name": "Updated Name", "systemMessage": "...", "temperature": ..., "customParams": {...}}` (Partial updates).
        *   **Response:** Updated `ModelSettings` object.
    *   `DELETE /api/v1/settings/{id}`
        *   **Description:** Delete a specific Model Settings configuration.
        *   **Parameters:** `{id}` (UUID of the settings).
        *   **Response:** Success/Error status.

## 2. Data Models

Using SQLite as the database, we'll define tables and their schemas. We can use a Kotlin-friendly ORM or query builder like Exposed to interact with SQLite.

*   **`ChatSession` Table**
    *   `id` (TEXT, PRIMARY KEY, UUID)
    *   `name` (TEXT, NOT NULL)
    *   `createdAt` (INTEGER, NOT NULL, Unix timestamp)
    *   `updatedAt` (INTEGER, NOT NULL, Unix timestamp)
    *   `groupId` (TEXT, NULLABLE, Foreign Key referencing `ChatSession.id` for group parent, or a separate `ChatGroup` table if grouping structure becomes complex - using `ChatSession.id` is simpler for flat groups initially)
    *   `currentModelId` (TEXT, NULLABLE, Foreign Key referencing `LLMModel.id`) - The model selected for this session.
    *   `currentSettingsId` (TEXT, NULLABLE, Foreign Key referencing `ModelSettings.id`) - The settings selected for this session.

*   **`ChatMessage` Table**
    *   `id` (TEXT, PRIMARY KEY, UUID)
    *   `sessionId` (TEXT, NOT NULL, Foreign Key referencing `ChatSession.id`)
    *   `role` (TEXT, NOT NULL, ENUM-like: 'user', 'assistant', 'system' - though system messages are per-settings)
    *   `content` (TEXT, NOT NULL)
    *   `createdAt` (INTEGER, NOT NULL, Unix timestamp)
    *   `updatedAt` (INTEGER, NOT NULL, Unix timestamp)
    *   `sequence` (INTEGER, NOT NULL) - Used to maintain order within a session. Auto-increment or managed by application logic.
    *   `modelId` (TEXT, NULLABLE, Foreign Key referencing `LLMModel.id`) - Model used for THIS specific message (mainly assistant).
    *   `settingsId` (TEXT, NULLABLE, Foreign Key referencing `ModelSettings.id`) - Settings used for THIS specific message (mainly assistant).

*   **`LLMModel` Table**
    *   `id` (TEXT, PRIMARY KEY, UUID)
    *   `name` (TEXT, NOT NULL, UNIQUE)
    *   `baseUrl` (TEXT, NOT NULL)
    *   `apiKeyId` (TEXT, NULLABLE, Foreign Key referencing a secure storage identifier - See Security)
    *   `type` (TEXT, NOT NULL, e.g., 'openai', 'openrouter', 'custom')

*   **`ModelSettings` Table**
    *   `id` (TEXT, PRIMARY KEY, UUID)
    *   `modelId` (TEXT, NOT NULL, Foreign Key referencing `LLMModel.id`)
    *   `name` (TEXT, NOT NULL) - e.g., "Default", "Creative", "Strict"
    *   `systemMessage` (TEXT, NULLABLE)
    *   `temperature` (REAL, NULLABLE)
    *   `maxTokens` (INTEGER, NULLABLE)
    *   `customParamsJson` (TEXT, NULLABLE) - Store arbitrary JSON for other model parameters.

**Relationships:**
*   One `ChatSession` has many `ChatMessage`s.
*   One `LLMModel` can have many `ModelSettings`.
*   A `ChatSession` links to one `LLMModel` and one `ModelSettings` for default usage.
*   A `ChatMessage` (specifically assistant) links to the `LLMModel` and `ModelSettings` used to generate it.

## 3. Business Logic

The core business logic resides in service layer classes that orchestrate interactions between the API handlers, database access objects (DAOs), and the LLM client.

1.  **Database Initialization:** On application startup, initialize the SQLite database connection and run any necessary schema migrations (if using a library like Exposed, this is handled).
2.  **LLM Client Initialization:** Configure and initialize a Ktor HTTP client instance. This client will be used to make calls to external LLM APIs based on the `baseUrl` configured for the selected model.
3.  **Session Management:**
    *   Fetching sessions: Query `ChatSession` table, optionally joining for current model/settings names.
    *   Creating session: Insert into `ChatSession` with UUID, current timestamp.
    *   Loading session: Query `ChatSession` by ID, then query `ChatMessage`s for that session ID, ordered by `sequence`.
    *   Updating session: Update fields in `ChatSession` by ID.
    *   Deleting session: Delete from `ChatSession` by ID. Use foreign key constraints with `ON DELETE CASCADE` on `ChatMessage` to automatically delete associated messages, or manually delete messages first.
    *   Grouping: Update the `groupId` field in the `ChatSession` table.
4.  **Message Management:**
    *   Adding user message: Insert into `ChatMessage` with session ID, 'user' role, content, UUID, timestamp, and calculate the next `sequence` number for the session.
    *   Triggering Assistant Message:
        *   Retrieve the current `LLMModel` and `ModelSettings` for the session from the database. If not set, use a default model/settings.
        *   Fetch the relevant message history for the session from the database. A sliding window approach (e.g., last N messages or messages fitting within a token limit) is needed to handle long histories.
        *   Construct the payload for the LLM API call based on the model `type` (OpenAI compatible chat format: `messages` array with `role` and `content`, plus settings like `temperature`, `max_tokens`, `system` message).
        *   Use the Ktor HTTP client to make an asynchronous POST request to the model's `baseUrl` (e.g., `/v1/chat/completions`).
        *   Handle the API response. Extract the assistant's message content.
        *   Insert the assistant message into the `ChatMessage` table, similar to the user message, linking it to the model and settings used.
        *   Return the user and assistant messages to the frontend.
        *   Implement error handling for API failures (network issues, invalid key, model errors).
    *   Editing message: Update `content` and `updatedAt` fields in `ChatMessage` by ID.
    *   Deleting message: Delete from `ChatMessage` by ID. This might require re-sequencing subsequent messages or simply leaving gaps in the sequence. Leaving gaps is simpler for minimum requirements.
5.  **Model & Settings Management:**
    *   Fetching models: Query `LLMModel`, then query associated `ModelSettings` summaries. Handle sensitive API key storage (see Security).
    *   Adding model: Insert into `LLMModel`. Securely store the API key reference.
    *   Updating model: Update fields in `LLMModel`. Securely update/replace the API key reference if provided.
    *   Deleting model: Delete from `LLMModel`. Ensure associated `ModelSettings` and API key references are also deleted. Update any `ChatSession` or `ChatMessage` records that referenced this model/settings (set IDs to NULL or default).
    *   Fetching settings: Query `ModelSettings` by ID.
    *   Adding settings: Insert into `ModelSettings`, linking to the `modelId`.
    *   Updating settings: Update fields in `ModelSettings` by ID. Parse `customParamsJson` from/to structured data.
    *   Deleting settings: Delete from `ModelSettings` by ID. Update any referencing `ChatSession` or `ChatMessage` records.

## 4. Security

Given this is a desktop application where the user is the sole operator, traditional multi-user web security (authentication, authorization between users) is not the primary concern. The focus shifts to protecting sensitive data stored locally and securely interacting with external APIs.

1.  **API Key Storage:** **Critical.** OpenAI-compatible API keys are sensitive credentials. Storing them in plain text in the SQLite database is unacceptable.
    *   **Recommended Approach:** Use the operating system's credential manager.
        *   On Windows: Credential Manager API.
        *   Kotlin can potentially interface with native libraries or use Java libraries that wrap native APIs (e.g., `java.security.KeyStore` combined with OS features, or dedicated Kotlin/Java libraries for credential management).
    *   The `LLMModel` table should store a *reference* or *alias* to the credential in the OS credential manager, *not* the key itself (`apiKeyId` column).
    *   When the backend needs to make an LLM API call, it retrieves the `apiKeyId` from the `LLMModel` record, then uses this ID to fetch the actual key from the OS credential manager.
    *   Adding/Updating a model with an API key involves storing the key in the OS credential manager and saving the generated ID/alias in the database.
    *   Deleting a model involves deleting the corresponding credential from the OS credential manager.
2.  **Database File Security:** The SQLite database file (`chat.db`) contains chat history, which might be considered sensitive.
    *   By default, SQLite data is stored in a file in the user's profile directory. Its security relies on the OS's file permissions for the logged-in user.
    *   For higher security, the database file itself could be encrypted (e.g., using SQLCipher for SQLite). This requires a password or key to open the database. This adds complexity (user needs to provide a password on startup, or key derived from less secure system info). For the minimum requirements of a single-user desktop app, relying on OS file permissions is often considered sufficient, but encryption is a viable option for enhanced privacy.
3.  **Input Validation:** Sanitize and validate user input received via the internal API endpoints to prevent potential issues, although the risk from a trusted frontend in the same process is low compared to external APIs.

## 5. Performance

Performance in a desktop app concerns responsiveness and efficient resource usage.

1.  **Asynchronous Operations:** Use Kotlin Coroutines extensively for any potentially blocking operations, especially:
    *   Database access: All database read/write operations should be performed on a background thread pool (e.g., using `Dispatchers.IO`) to avoid blocking the UI thread or the Ktor server's event loop.
    *   LLM API Calls: These are high-latency network calls. They *must* be non-blocking and run on appropriate dispatchers. Ktor client integrates well with coroutines.
2.  **Database Optimizations:**
    *   **Indexing:** Create indices on frequently queried columns, especially foreign keys:
        *   `ChatMessage.sessionId`
        *   `ChatMessage.sequence` (for ordering)
        *   `ModelSettings.modelId`
        *   `ChatSession.groupId`
    *   **Efficient Queries:**
        *   Fetch only necessary columns.
        *   Limit the number of messages retrieved for history context (sliding window). Avoid loading *all* messages for large sessions unless explicitly requested (e.g., for export).
        *   Use appropriate queries for operations like getting the next sequence number.
3.  **LLM Context Management:** Implement logic to manage the conversation history sent to the LLM API to stay within the model's token limits. A simple sliding window (sending the last N messages that fit) is a common approach. This involves calculating token counts (using a library like Tiktoken for OpenAI-like models).
4.  **Resource Management:** Properly close database connections and Ktor client resources when the application shuts down.
5.  **Ktor Server Configuration:** Tune Ktor's embedded server (e.g., Netty) settings if necessary for thread pool size, although default settings are often sufficient for internal communication in a desktop app.

## 6. Code Examples

These examples use hypothetical interfaces for database access (`SessionDao`, `MessageDao`, etc.) and LLM interaction (`LLMService`). We assume a database library like Exposed is configured.

**1. Database Setup (Simplified using Exposed concepts)**

```kotlin
// In your application entry point or a dedicated module setup
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File

object Database {
    fun connect() {
        // Use a file path in the user's application data directory
        val dbFile = File(System.getProperty("user.home"), ".mychatbot/chat.db")
        dbFile.parentFile.mkdirs() // Ensure directory exists

        Database.connect("jdbc:sqlite:${dbFile.absolutePath}", driver = "org.sqlite.JDBC")

        transaction {
            SchemaUtils.create(ChatSessions, ChatMessages, LLMModels, ModelSettings)
            // Add initial data if needed (e.g., a default model)
        }
    }
}

// Define your tables using Exposed DSL
object ChatSessions : Table("chat_sessions") {
    val id = uuid("id").clientDefault { java.util.UUID.randomUUID() }.uniqueIndex()
    val name = text("name")
    val createdAt = long("created_at").clientDefault { System.currentTimeMillis() }
    val updatedAt = long("updated_at").clientDefault { System.currentTimeMillis() }
    val groupId = uuid("group_id").nullable().references(ChatSessions.id).index() // Self-referencing for grouping
    val currentModelId = uuid("current_model_id").nullable().references(LLMModels.id).index()
    val currentSettingsId = uuid("current_settings_id").nullable().references(ModelSettings.id).index()

    override val primaryKey = PrimaryKey(id)
}

object ChatMessages : Table("chat_messages") {
    val id = uuid("id").clientDefault { java.util.UUID.randomUUID() }.uniqueIndex()
    val sessionId = uuid("session_id").references(ChatSessions.id, onDelete = ReferenceOption.CASCADE).index()
    val role = text("role") // "user", "assistant"
    val content = text("content")
    val createdAt = long("created_at").clientDefault { System.currentTimeMillis() }
    val updatedAt = long("updated_at").clientDefault { System.currentTimeMillis() }
    val sequence = integer("sequence").index() // Order within session
    val modelId = uuid("model_id").nullable().references(LLMModels.id, onDelete = ReferenceOption.SET_NULL).index()
    val settingsId = uuid("settings_id").nullable().references(ModelSettings.id, onDelete = ReferenceOption.SET_NULL).index()

    override val primaryKey = PrimaryKey(id)
}

object LLMModels : Table("llm_models") {
    val id = uuid("id").clientDefault { java.util.UUID.randomUUID() }.uniqueIndex()
    val name = text("name").uniqueIndex()
    val baseUrl = text("base_url")
    // Stores the ID/alias used to retrieve the API key from the OS credential manager
    val apiKeyId = text("api_key_id").nullable()
    val type = text("type") // e.g., "openai", "openrouter"

    override val primaryKey = PrimaryKey(id)
}

object ModelSettings : Table("model_settings") {
    val id = uuid("id").clientDefault { java.util.UUID.randomUUID() }.uniqueIndex()
    val modelId = uuid("model_id").references(LLMModels.id, onDelete = ReferenceOption.CASCADE).index()
    val name = text("name")
    val systemMessage = text("system_message").nullable()
    val temperature = real("temperature").nullable()
    val maxTokens = integer("max_tokens").nullable()
    val customParamsJson = text("custom_params_json").nullable() // Store JSON string

    override val primaryKey = PrimaryKey(id)
}

// In your Ktor server setup:
// embeddedServer(Netty, port = 8080) { // Or pick an available port
//    Database.connect()
//    // ... configure serialization, routing etc.
// }.start(wait = true)
```

**2. API Endpoint for Sending a Message (Illustrative Ktor Route)**

```kotlin
import io.ktor.server.routing.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

// Assuming these are dependency injected or accessible
// val sessionService: SessionService
// val messageService: MessageService
// val llmService: LLMService

fun Route.chatRoutes() {
    route("/api/v1/sessions/{sessionId}/messages") {
        post {
            val sessionId = call.parameters["sessionId"]?.let { UUID.fromString(it) }
                ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing session ID")
            val request = call.receive<SendMessageRequest>() // Data class { val content: String }

            try {
                // Use withContext(Dispatchers.IO) for DB operations
                val userMessage = withContext(Dispatchers.IO) {
                     messageService.addUserMessage(sessionId, request.content)
                }

                // This call handles fetching history, model, settings, calling LLM, saving assistant message
                val assistantMessage = llmService.generateResponse(sessionId)

                // Return both messages. In a real app, the assistant message might stream or arrive later.
                call.respond(HttpStatusCode.Created, listOf(userMessage, assistantMessage))

            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.NotFound, "Session not found")
            } catch (e: LLMServiceException) {
                 // Handle specific LLM errors (API key invalid, etc.)
                 call.respond(HttpStatusCode.InternalServerError, "LLM Error: ${e.message}")
            } catch (e: Exception) {
                // Log the error
                e.printStackTrace()
                call.respond(HttpStatusCode.InternalServerError, "An unexpected error occurred")
            }
        }
    }

    // Add routes for editing/deleting messages, sessions, models, settings similarly
}

// Data class for request body
@kotlinx.serialization.Serializable
data class SendMessageRequest(val content: String)
```

**3. Simplified LLM Service (Illustrative)**

```kotlin
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.util.UUID

// Assuming DAOs and CredentialManager are available
// val sessionDao: SessionDao
// val modelDao: LLMModelDao
// val settingsDao: ModelSettingsDao
// val messageDao: ChatMessageDao
// val credentialManager: CredentialManager // Interface for OS credential access

class LLMService {

    private val httpClient = HttpClient {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true })
        }
        // Further configuration like timeouts, logging etc.
    }

    suspend fun generateResponse(sessionId: UUID): ChatMessage {
        // Fetch session, model, settings, and history from DB (async)
        val session = withContext(Dispatchers.IO) { sessionDao.getById(sessionId) } ?: throw IllegalArgumentException("Session not found")
        val model = withContext(Dispatchers.IO) { modelDao.getById(session.currentModelId ?: throw LLMServiceException("Model not selected for session")) }
        val settings = withContext(Dispatchers.IO) { settingsDao.getById(session.currentSettingsId ?: throw LLMServiceException("Settings not selected for session")) }
        val history = withContext(Dispatchers.IO) { messageDao.getMessagesBySessionId(sessionId, limit = 20) } // Example: last 20 messages

        // --- Context Management ---
        // Build messages list for LLM API call
        // Need a token counting logic here to fit history within model context limits
        val apiMessages = mutableListOf<ApiMessage>()
        settings.systemMessage?.let { apiMessages.add(ApiMessage("system", it)) }
        // Add history messages, applying sliding window if needed
        // Example: Simple last N messages
        history.sortedBy { it.sequence }.forEach { msg ->
            apiMessages.add(ApiMessage(msg.role, msg.content))
        }
        // --- End Context Management ---

        // Retrieve API Key securely
        val apiKey = model.apiKeyId?.let { credentialManager.getCredential(it) } ?: throw LLMServiceException("API key not configured for model: ${model.name}")

        // Build LLM API request payload (OpenAI Chat Completions format)
        val apiRequest = OpenAIChatCompletionRequest(
            model = "gpt-3.5-turbo", // Replace with actual model name from settings or model capabilities
            messages = apiMessages,
            temperature = settings.temperature,
            max_tokens = settings.maxTokens,
            // Add other params from settings.customParamsJson
        )

        // Make the API call (async)
        val apiResponse = try {
            httpClient.post("${model.baseUrl}/chat/completions") {
                contentType(ContentType.Application.Json)
                bearerAuth(apiKey) // Use Bearer token auth for most OpenAI-compatible APIs
                setBody(apiRequest)
                // Configure timeouts etc.
            }
        } catch (e: Exception) {
             throw LLMServiceException("API call failed: ${e.message}", e)
        }

        if (!apiResponse.status.isSuccess()) {
             val errorBody = apiResponse.bodyAsText()
             throw LLMServiceException("API returned error: ${apiResponse.status.value} - $errorBody")
        }

        val completion = apiResponse.body<OpenAIChatCompletionResponse>()
        val assistantContent = completion.choices.firstOrNull()?.message?.content
            ?: throw LLMServiceException("LLM response missing content")

        // Save assistant message to DB (async)
        val assistantMessage = withContext(Dispatchers.IO) {
            messageDao.addAssistantMessage(sessionId, assistantContent, model.id, settings.id)
        }

        return assistantMessage
    }
}

// Data classes for LLM API interaction (simplified)
@kotlinx.serialization.Serializable
data class ApiMessage(val role: String, val content: String)

@kotlinx.serialization.Serializable
data class OpenAIChatCompletionRequest(
    val model: String, // e.g., "gpt-4", "gpt-3.5-turbo"
    val messages: List<ApiMessage>,
    val temperature: Float? = null,
    @kotlinx.serialization.SerialName("max_tokens") val maxTokens: Int? = null,
    // Add other parameters supported by the API
)

@kotlinx.serialization.Serializable
data class OpenAIChatCompletionResponse(
    val id: String,
    val choices: List<Choice>,
    val usage: Usage? = null
)

@kotlinx.serialization.Serializable
data class Choice(
    val message: ApiMessage,
    @kotlinx.serialization.SerialName("finish_reason") val finishReason: String
)

@kotlinx.serialization.Serializable
data class Usage(
    @kotlinx.serialization.SerialName("prompt_tokens") val promptTokens: Int,
    @kotlinx.serialization.SerialName("completion_tokens") val completionTokens: Int,
    @kotlinx.serialization.SerialName("total_tokens") val totalTokens: Int
)

class LLMServiceException(message: String, cause: Throwable? = null) : Exception(message, cause)

// Hypothetical CredentialManager interface
interface CredentialManager {
    fun storeCredential(alias: String, credential: String): String // Stores and returns the actual alias used
    fun getCredential(alias: String): String?
    fun deleteCredential(alias: String): Boolean
}

// Note: Implementing CredentialManager requires platform-specific code (JNA/JNI or calling command line tools)
// or using a library that provides this abstraction.
```

These examples provide a basic structure. A real implementation would involve more detailed data classes, more robust error handling, DAO classes wrapping the database operations, and careful implementation of the `CredentialManager` for platform security.

