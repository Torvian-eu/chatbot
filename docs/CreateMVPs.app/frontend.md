# Frontend Implementation Guide: General-Purpose Desktop Chatbot

## 1. Document Header

*   **Version:** 1.0
*   **Date:** May 21, 2025

## 2. Component Architecture

The frontend will be built using Jetpack Compose for Desktop, following a component-based architecture. The core components will interact with a shared state layer and communicate with the Ktor backend server component (running within the same application process) for data persistence and LLM interactions.

Here's a breakdown of the core components and their relationships:

*   **Root App (MainWindow):** The main container for the entire application UI. Manages the overall application state and navigation between top-level views (e.g., Chat View, Settings View).
*   **App State Holder (ViewModel/State Class):** A central class or set of classes responsible for holding and managing the application's state. This includes the list of chat sessions, the currently selected session, messages within the selected session, loading states, error states, user settings (like API key), model configuration, etc. All UI components will observe relevant parts of this state.
*   **Session List Panel:** A component displaying the list of available chat sessions.
    *   Observes the list of sessions from the App State Holder.
    *   Handles user interactions (clicking a session to select it, potentially right-click for grouping/editing).
    *   Triggers state updates in the App State Holder (e.g., `setSelectedSession(sessionId)`).
*   **Chat Area:** The main panel displaying messages for the currently selected session.
    *   Observes the selected session and its messages from the App State Holder.
    *   Displays individual `MessageItem` components.
    *   Handles scroll behavior.
*   **Message Item:** A component representing a single chat message (user or assistant).
    *   Receives message data (text, sender, ID, editing status) as parameters.
    *   Displays the message content.
    *   Includes UI elements for editing, removing, and copying the message.
    *   Triggers state updates in the App State Holder (e.g., `startEditingMessage(messageId)`, `deleteMessage(messageId)`, `copyMessage(messageId)`).
*   **Input Area:** The component where the user types new messages and selects the model.
    *   Holds the current input text (`MutableState`).
    *   Observes the list of available models from the App State Holder.
    *   Provides a model selection dropdown.
    *   Includes a "Send" button.
    *   Triggers state updates in the App State Holder (e.g., `sendMessage(sessionId, text, modelId)`).
*   **Settings Dialogs (API Key, Model Settings):** Separate dialogs or views for managing application-wide and model-specific settings.
    *   Observe/receive current settings from the App State Holder.
    *   Provide input fields and controls for editing settings.
    *   Triggers state updates in the App State Holder (e.g., `saveSettings(settingsData)` which will likely call the backend).
*   **API Client:** An interface and implementation responsible for communication with the Ktor backend server (running in the same app).
    *   Provides functions corresponding to backend endpoints (e.g., `getSessions()`, `getMessages(sessionId)`, `createMessage(...)`, `updateMessage(...)`, `deleteMessage(...)`, `getModels()`, `saveApiKey(...)`, `saveModelSettings(...)`).
    *   Used by the App State Holder to load and persist data.

**Interaction Flow:**

1.  User performs an action (e.g., clicks "Send", edits a message, selects a session).
2.  The relevant UI component (e.g., `InputArea`, `MessageItem`, `SessionListPanel`) calls a corresponding function on the App State Holder.
3.  The App State Holder updates its internal state. For actions requiring persistence or LLM interaction, it calls the `API Client`.
4.  The `API Client` sends a request to the Ktor backend.
5.  The Ktor backend interacts with the SQLite database or the external LLM API.
6.  The Ktor backend sends a response back to the `API Client`.
7.  The `API Client` provides the result to the App State Holder.
8.  The App State Holder updates its state based on the API response (e.g., adds a new message, updates an existing one, handles errors).
9.  Compose's reactivity system detects state changes in the App State Holder.
10. Relevant UI components observing the changed state are recomposed, updating the display.

This pattern (often resembling MVVM or MVI depending on implementation details) keeps UI components relatively simple and focused on presentation, while the App State Holder manages logic, data fetching, and state transitions.

```mermaid
graph TD
    A[Root App<br>(MainWindow)] --> B(App State Holder<br>/ ViewModel)
    B --> C[Session List Panel]
    B --> D[Chat Area]
    B --> E[Input Area]
    B --> F[Settings Dialogs]
    C -- User Events --> B
    D -- Observes State --> D_UI[Individual MessageItem<br>Components]
    D -- User Events<br>(Edit, Delete) --> B
    E -- Observes State --> B
    E -- User Events<br>(Send, Select Model) --> B
    F -- Observes State --> B
    F -- User Events<br>(Save Settings) --> B
    B -- Calls API --> G[API Client]
    G -- REST/Internal Calls --> H[Ktor Backend Server<br>(In-Process)]
    H -- Interacts With --> I[SQLite Database]
    H -- Calls External API --> J[LLM Service]
    H -- Responds To --> G
    G -- Provides Data/Status To --> B
```

## 3. State Management

State management is crucial for a reactive UI like Compose. We'll use Compose's built-in state capabilities (`mutableStateOf`, `remember`, `derivedStateOf`, `collectAsState`) combined with a central state holder class (analogous to a ViewModel in Android, or a simple class holding `MutableState` and functions). Kotlin Flows will be used for asynchronous data streams (e.g., messages arriving from the backend, potentially future real-time updates).

**Core State Elements:**

*   `sessions: List<ChatSession>`: List of all chat sessions.
*   `selectedSessionId: Long?`: ID of the currently active session. Null if no session selected.
*   `messages: List<ChatMessage>`: Messages for the `selectedSession`. This should ideally be derived or loaded when `selectedSessionId` changes.
*   `availableModels: List<LanguageModel>`: List of models the user has configured.
*   `currentModelId: String?`: ID of the model selected for the next message. Defaults to a preferred model or the last used in the session.
*   `apiKey: String`: The user's OpenAI-compatible API key. Needs secure storage (handled by backend/OS key store, not frontend direct access). Frontend just needs to know if one is set and provide an input field.
*   `modelSettings: Map<String, ModelSettings>`: Map from Model ID to its specific settings (system message, parameters).
*   `isLoading: Boolean`: General loading indicator for API calls (fetching sessions, sending message, etc.).
*   `error: String?`: Stores the latest error message for display to the user.
*   `isEditingMessageId: Long?`: The ID of the message currently being edited, if any.

**State Holder (e.g., `ChatState` class):**

This class will hold the `MutableState` variables and functions that update the state. It will interact with the `ApiClient`.

```kotlin
// Data classes (simplify from backend/DB models for UI)
data class ChatSession(val id: Long, val name: String, val group: String?)
data class ChatMessage(
    val id: Long,
    val sessionId: Long,
    val role: String, // "user" or "assistant"
    val content: String,
    val timestamp: Long,
    val isEditing: Boolean = false // UI specific state
)
data class LanguageModel(
    val id: String,
    val name: String,
    // Other model capabilities/info needed by UI
)
data class ModelSettings(
    val systemMessage: String,
    // Other parameters...
)

// State Holder Class
class ChatState(private val api: ChatApi) { // api is injected

    var sessions = mutableStateListOf<ChatSession>()
        private set // State list changes trigger recomposition

    var selectedSessionId by mutableStateOf<Long?>(null)
        private set

    // Derived state or loaded state
    val messages: StateFlow<List<ChatMessage>> = selectedSessionId
        .asStateFlow() // Convert state var to flow
        .filterNotNull() // Only proceed when a session is selected
        .flatMapLatest { sessionId -> // Fetch messages for the latest selected ID
            // Use a Channel or StateFlow from API for message updates
            // This is a simplified example - real impl might use Flow or Channel
            api.getMessagesFlow(sessionId) // API returns a Flow of messages
        }
        .catch { e -> // Handle errors in the flow
            // Update error state
            _error.value = "Failed to load messages: ${e.message}"
            emit(emptyList()) // Emit empty list on error to avoid crashing
        }
        .stateIn(
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()), // Manage coroutine scope
            started = SharingStarted.WhileSubscribed(5000), // Start collecting when UI subscribes
            initialValue = emptyList()
        )

    var availableModels by mutableStateOf<List<LanguageModel>>(emptyList())
        private set

    var currentModelId by mutableStateOf<String?>(null)
        private set // Default to first model or saved preference

    var apiKeyStatus by mutableStateOf<String>("Not Configured") // e.g., "Configured", "Invalid", "Not Configured"
        private set

    var modelSettings by mutableStateOf<Map<String, ModelSettings>>(emptyMap())
        private set

    var isLoading by mutableStateOf(false)
        private set

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // --- Initialization ---
    init {
        // Launch coroutines to load initial data
        CoroutineScope(Dispatchers.IO).launch {
            loadInitialData()
        }
    }

    private suspend fun loadInitialData() {
        isLoading = true
        _error.value = null
        try {
            sessions.addAll(api.getSessions())
            availableModels = api.getModels()
            // Load API Key Status (don't load key itself)
            apiKeyStatus = if (api.isApiKeyConfigured()) "Configured" else "Not Configured"
            // Load default or last used model ID and settings
            if (availableModels.isNotEmpty()) {
                currentModelId = availableModels.first().id // Simple default
                modelSettings = api.getAllModelSettings()
            }
        } catch (e: Exception) {
            _error.value = "Failed to load initial data: ${e.message}"
            e.printStackTrace()
        } finally {
            isLoading = false
        }
    }

    // --- State Update Functions (triggered by UI events) ---

    fun selectSession(sessionId: Long) {
        if (selectedSessionId != sessionId) {
            selectedSessionId = sessionId
            // Messages flow automatically updates via flatMapLatest
            _error.value = null // Clear previous message errors
        }
    }

    fun createNewSession() {
        CoroutineScope(Dispatchers.IO).launch {
            isLoading = true
            _error.value = null
            try {
                val newSession = api.createSession()
                sessions.add(newSession)
                selectSession(newSession.id) // Auto-select new session
            } catch (e: Exception) {
                _error.value = "Failed to create new session: ${e.message}"
                e.printStackTrace()
            } finally {
                isLoading = false
            }
        }
    }

    fun sendMessage(text: String) {
        val currentSession = selectedSessionId ?: run {
            _error.value = "Cannot send message: No session selected."
            return // Can't send without a session
        }
        val modelToUse = currentModelId ?: run {
            _error.value = "Cannot send message: No model selected."
            return
        }
        if (text.isBlank()) return // Don't send empty messages

        CoroutineScope(Dispatchers.IO).launch {
            isLoading = true
            _error.value = null
            try {
                // API call to send message, backend handles LLM interaction and saving both user/assistant messages
                // The API response might contain the newly created user message + the AI response
                // Or, the API might return the user message immediately and push AI message via Flow/Channel
                // Let's assume the API adds messages to the Flow that 'messages' is observing
                api.sendMessage(currentSession, text, modelToUse)
                // UI will react when the messages flow updates
            } catch (e: Exception) {
                _error.value = "Failed to send message: ${e.message}"
                e.printStackTrace()
            } finally {
                isLoading = false
            }
        }
    }

    fun startEditingMessage(messageId: Long) {
        // Update UI-specific state directly or via a copy
        messages.value.find { it.id == messageId }?.let { message ->
             // Need to update the specific item in the list.
             // Since `messages` is a StateFlow from the API, we can't directly modify it here.
             // The API layer would need to provide a way to signal "editing" state, or
             // we manage a separate UI-state list derived from the API list.
             // A common pattern is to map the API data to UI models that include UI state like `isEditing`.
             // For simplicity here, assume we can find and flag it if messages was mutableStateListOf:
             /*
             val index = messages.indexOfFirst { it.id == messageId }
             if (index != -1) {
                 messages[index] = messages[index].copy(isEditing = true)
                 isEditingMessageId = messageId // Track which one is being edited globally if needed
             }
             */
             // A better approach with immutable StateFlow would be:
             isEditingMessageId = messageId // Simply track which ID is being edited
        }
    }

    fun stopEditingMessage() {
         isEditingMessageId = null
    }

    fun updateMessage(messageId: Long, newContent: String) {
        if (newContent.isBlank()) {
            _error.value = "Message content cannot be empty."
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            isLoading = true
            _error.value = null
            try {
                // API call to update message
                api.updateMessage(messageId, newContent)
                // UI will react when the messages flow updates (API pushes the change)
                stopEditingMessage() // Exit editing mode on success
            } catch (e: Exception) {
                _error.value = "Failed to update message: ${e.message}"
                e.printStackTrace()
            } finally {
                isLoading = false
            }
        }
    }

    fun deleteMessage(messageId: Long) {
         CoroutineScope(Dispatchers.IO).launch {
            isLoading = true
            _error.value = null
            try {
                // API call to delete message
                api.deleteMessage(messageId)
                // UI will react when the messages flow updates (API removes the message)
            } catch (e: Exception) {
                _error.value = "Failed to delete message: ${e.message}"
                e.printStackTrace()
            } finally {
                isLoading = false
            }
        }
    }

    fun copyMessageContent(messageId: Long) {
        // Find the message content
        messages.value.find { it.id == messageId }?.let { message ->
            // Use OS-specific clipboard functionality (Compose provides APIs)
            // See UI Design section or Code Examples for clipboard usage.
            // This typically doesn't require API interaction or state update, just OS call.
            println("Copying message ${message.id} content to clipboard...") // Placeholder
            // Example: ClipboardManager.setText(...)
        }
    }

     fun copySessionContent() {
        // Compile all messages in the current session into a single text block
        selectedSessionId?.let { sessionId ->
            val sessionMessages = messages.value // Assuming messages Flow holds the current session messages
            val fullText = sessionMessages.joinToString("\n") { "${it.role}: ${it.content}" }
            // Use OS-specific clipboard functionality
             println("Copying session ${sessionId} content to clipboard...") // Placeholder
            // Example: ClipboardManager.setText(...)
        }
    }

    fun updateApiKey(key: String) {
        CoroutineScope(Dispatchers.IO).launch {
             isLoading = true
             _error.value = null
             try {
                 api.saveApiKey(key)
                 apiKeyStatus = if (key.isNotBlank()) "Configured" else "Not Configured" // Simplified status logic
                 // Could add API call to validate key here
             } catch (e: Exception) {
                 _error.value = "Failed to save API key: ${e.message}"
                 apiKeyStatus = "Error"
                 e.printStackTrace()
             } finally {
                 isLoading = false
             }
        }
    }

     fun updateModelSettings(modelId: String, settings: ModelSettings) {
         CoroutineScope(Dispatchers.IO).launch {
             isLoading = true
             _error.value = null
             try {
                 api.saveModelSettings(modelId, settings)
                 // Update local state upon successful save
                 modelSettings = modelSettings.toMutableMap().apply { put(modelId, settings) }.toMap()
             } catch (e: Exception) {
                 _error.value = "Failed to save model settings for $modelId: ${e.message}"
                 e.printStackTrace()
             } finally {
                 isLoading = false
             }
         }
     }

    // ... add functions for grouping, etc. ...

}
```

**Integration with Compose:**

In your main Composable function (e.g., in `MainWindow`), you'll instantiate the `ChatState` and pass it down to relevant child components. UI components will observe the state variables using `.collectAsState()` for Flows or accessing `by mutableStateOf` variables directly within a Composable function.

```kotlin
fun main() = application {
    val chatApi = remember { RealChatApi(/* pass backend client */) } // Instantiate your API implementation
    val chatState = remember { ChatState(chatApi) } // Instantiate the state holder

    Window(onCloseRequest = ::exitApplication, title = "Chatbot") {
        AppLayout(chatState) // Pass state to the main layout Composable
    }
}

@Composable
fun AppLayout(chatState: ChatState) {
    val sessions by chatState.sessions.collectAsState() // Collect state list changes
    val selectedSessionId by chatState.selectedSessionId // Observe state var
    val messages by chatState.messages.collectAsState() // Collect flow state
    val availableModels by chatState.availableModels // Observe state var
    val currentModelId by chatState.currentModelId // Observe state var
    val isLoading by chatState.isLoading // Observe state var
    val error by chatState.error.collectAsState() // Collect flow state
    val isEditingMessageId by chatState.isEditingMessageId // Observe state var

    // UI structure using Column, Row, etc.
    Column(modifier = Modifier.fillMaxSize()) {
        if (isLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        if (error != null) {
            Text("Error: $error", color = Color.Red)
        }

        Row(modifier = Modifier.weight(1f)) {
            // Session List Panel
            SessionListPanel(
                sessions = sessions,
                selectedSessionId = selectedSessionId,
                onSessionSelected = { chatState.selectSession(it) },
                onCreateNewSession = { chatState.createNewSession() },
                modifier = Modifier.width(200.dp).fillMaxHeight()
            )

            // Chat Area
            ChatArea(
                messages = messages,
                selectedSessionId = selectedSessionId, // Pass session ID if needed by ChatArea logic
                isEditingMessageId = isEditingMessageId,
                onEditMessage = { id -> chatState.startEditingMessage(id) },
                onSaveMessage = { id, content -> chatState.updateMessage(id, content) },
                onCancelEdit = { chatState.stopEditingMessage() },
                onDeleteMessage = { id -> chatState.deleteMessage(id) },
                onCopyMessage = { id -> chatState.copyMessageContent(id) },
                onCopySession = { chatState.copySessionContent() },
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
        }

        // Input Area
        InputArea(
            availableModels = availableModels,
            selectedModelId = currentModelId,
            onModelSelected = { /* TODO: Add update function in ChatState */ },
            onSendMessage = { text -> chatState.sendMessage(text) },
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min) // Adjust height as needed
        )

        // Settings Button/Dialog Trigger (simplified)
        Button(onClick = { /* Show settings dialog */ }) { Text("Settings") }
        // If settings dialog is open:
        // SettingsDialog(chatState = chatState)
    }
}

// Placeholder Composables for different sections
@Composable fun SessionListPanel(...) { /* ... */ }
@Composable fun ChatArea(...) { /* ... */ }
@Composable fun MessageItem(...) { /* ... */ } // Used inside ChatArea
@Composable fun InputArea(...) { /* ... */ }
@Composable fun SettingsDialog(...) { /* ... */ }
```

## 4. UI Design

The UI should follow a standard desktop application layout with a sidebar for navigation (session list) and a main content area (chat view).

*   **Layout:** Use Compose's layout primitives like `Row`, `Column`, and `Box`. `weight` modifiers are essential for distributing space.
    *   Main Window: `Column` for the top-level layout (progress bar/error, main content area, input area, footer/settings button).
    *   Main Content Area: `Row` dividing the Session List (fixed/min width) and the Chat Area (flexible width using `weight(1f)`).
    *   Chat Area: `Column` for the message list and potentially a "Copy Session" button/indicator. The message list itself will likely use `LazyColumn` for performance with many messages.
    *   Message Item: `Row` or `Column` depending on layout (e.g., avatar on left, message bubble on right). Needs space for edit/delete/copy buttons, potentially on hover or in an overflow menu.
    *   Input Area: `Row` containing model selection dropdown, text input field, and send button.

*   **Key UI Elements:**
    *   **Session List:** `LazyColumn` of clickable session items. Each item shows the session name/group (maybe first message snippet). Indicate the selected session visually. Button to create a new session.
    *   **Chat Message List:** `LazyColumn` of `MessageItem` composables. Messages should be clearly differentiated (user vs. assistant, background color, alignment).
    *   **Message Item (Interactive):**
        *   Display text content.
        *   When editing: Replace text display with a `TextField` pre-populated with content, and add "Save" and "Cancel" buttons.
        *   Always show (or show on hover/context menu): "Edit", "Delete", "Copy" actions. Use icons for these.
    *   **Input Area:** `TextField` for message input. A `DropdownMenu` or similar for model selection populated from `availableModels`. A `Button` to send.
    *   **Settings Screens/Dialogs:** Forms with `TextField`s for API Key, `TextField`s/other inputs for Model Settings (System Message, parameters). Buttons to "Save" and "Cancel".
    *   **Copy Functionality:** Dedicated icons/buttons on individual messages and a button/option to copy the entire session transcript. Use Compose's `ClipboardManager` to interact with the OS clipboard.

*   **User Interactions:**
    *   Click session in the list -> Select session, load messages.
    *   Type in input -> Update input text state.
    *   Select model dropdown -> Update `currentModelId` state.
    *   Click "Send" -> Call `chatState.sendMessage`. Clear input field.
    *   Click "Edit" on message -> Call `chatState.startEditingMessage`. Message item transforms to edit mode.
    *   Edit text, click "Save" -> Call `chatState.updateMessage`. Message item reverts to display mode.
    *   Click "Cancel" edit -> Call `chatState.stopEditingMessage`. Message item reverts, discarding changes.
    *   Click "Delete" on message -> Call `chatState.deleteMessage`. Message is removed (backend removes it, UI observes state change).
    *   Click "Copy" on message -> Call `chatState.copyMessageContent`.
    *   Click "Copy Session" -> Call `chatState.copySessionContent`.
    *   Click "Settings" button -> Show settings dialog.
    *   In Settings: Type in fields, click "Save" -> Call `chatState.updateApiKey` or `updateModelSettings`.

**Clipboard Implementation Note:**

Compose for Desktop provides access to the system clipboard. You can get the `ClipboardManager` instance via `LocalClipboardManager.current` inside a Composable.

```kotlin
@OptIn(ExperimentalComposeUiApi::class) // Required for LocalClipboardManager
fun copyTextToClipboard(context: ClipboardManager, text: String) {
    context.setText(AnnotatedString(text))
}
```

You would call this helper function from your Composable event handler (e.g., button `onClick`) after getting the `ClipboardManager` instance and fetching the text content from your state/data model.

## 5. API Integration

The frontend will communicate with the Ktor backend server running within the same process. This communication can initially be direct Kotlin function calls to backend service classes, or it can be set up from the start using an in-process Ktor Client making HTTP requests to the Ktor server. The latter approach makes it easier to separate the frontend and backend into distinct processes (or even microservices) later.

**Recommended Approach:** Use an in-process Ktor HTTP Client.

1.  **Define API Interface:** Create a Kotlin interface defining the contract between frontend and backend. This helps decouple.

    ```kotlin
    interface ChatApi {
        suspend fun getSessions(): List<ChatSession> // Uses frontend data class
        suspend fun createSession(): ChatSession
        fun getMessagesFlow(sessionId: Long): Flow<List<ChatMessage>> // Stream messages
        suspend fun sendMessage(sessionId: Long, text: String, modelId: String) // Backend handles user+AI message creation
        suspend fun updateMessage(messageId: Long, newContent: String): ChatMessage
        suspend fun deleteMessage(messageId: Long)
        suspend fun getModels(): List<LanguageModel>
        suspend fun saveApiKey(key: String)
        suspend fun isApiKeyConfigured(): Boolean
        suspend fun getAllModelSettings(): Map<String, ModelSettings>
        suspend fun saveModelSettings(modelId: String, settings: ModelSettings)
        // suspend fun importSessions(json: String): List<ChatSession> // For optional feature
        // suspend fun exportSessions(sessionIds: List<Long>): String // For optional feature (JSON)
    }
    ```

2.  **Implement API Client:** Create an implementation of the `ChatApi` interface that uses Ktor Client to make HTTP requests to the *local* Ktor server instance.

    ```kotlin
    import io.ktor.client.*
    import io.ktor.client.engine.cio.* // Or other suitable engine like OkHttp
    import io.ktor.client.plugins.contentnegotiation.*
    import io.ktor.client.request.*
    import io.ktor.client.statement.*
    import io.ktor.http.*
    import io.ktor.serialization.kotlinx.json.*
    import kotlinx.coroutines.flow.Flow
    import kotlinx.serialization.json.Json

    // Data Transfer Objects (DTOs) for API communication
    // These might be slightly different from UI data classes if needed, but can be the same initially
    import com.your_app_package.backend.data.SessionDto // Example backend DTOs
    import com.your_app_package.backend.data.MessageDto
    import com.your_app_package.backend.data.ModelDto
    import com.your_app_package.backend.data.ModelSettingsDto

    class RealChatApi(private val httpClient: HttpClient) : ChatApi {

        // Assume backend server is running on localhost:8080 (or configured port)
        private val baseUrl = "http://localhost:8080/api/v1" // Example base URL

        override suspend fun getSessions(): List<ChatSession> {
            val sessionDtos: List<SessionDto> = httpClient.get("$baseUrl/sessions").body()
            return sessionDtos.map { it.toFrontendModel() } // Map DTO to frontend model
        }

        override suspend fun createSession(): ChatSession {
            val sessionDto: SessionDto = httpClient.post("$baseUrl/sessions").body()
            return sessionDto.toFrontendModel()
        }

        override fun getMessagesFlow(sessionId: Long): Flow<List<ChatMessage>> {
             // This is complex. Real-time updates often use WebSockets or Server-Sent Events (SSE).
             // For a simple polling approach (less efficient but easier initially):
             // Use a timer or Flow interval to periodically call httpClient.get("$baseUrl/sessions/$sessionId/messages").body()
             // and return that as a Flow.
             // A proper implementation would involve the backend using a Channel or Flow
             // to push updates to this client instance. This is an advanced topic for this guide.
             // Placeholder: Simulate fetching static data (not reactive updates)
             return flow {
                 while(true) { // Simple polling loop (replace with proper reactive flow)
                     val messageDtos: List<MessageDto> = httpClient.get("$baseUrl/sessions/$sessionId/messages").body()
                     emit(messageDtos.map { it.toFrontendModel() })
                     kotlinx.coroutines.delay(1000) // Poll every 1 second (BAD for production)
                 }
             }.distinctUntilChanged() // Only emit if data changes
        }

        override suspend fun sendMessage(sessionId: Long, text: String, modelId: String) {
            // Backend expects a request body, e.g., { "sessionId": ..., "text": ..., "modelId": ... }
            httpClient.post("$baseUrl/sessions/$sessionId/messages") {
                contentType(ContentType.Application.Json)
                setBody(mapOf("text" to text, "modelId" to modelId)) // Example body
            }
            // The AI response will ideally be pushed back via the messages flow
        }

        override suspend fun updateMessage(messageId: Long, newContent: String): ChatMessage {
             val messageDto: MessageDto = httpClient.put("$baseUrl/messages/$messageId") {
                 contentType(ContentType.Application.Json)
                 setBody(mapOf("content" to newContent)) // Example body
             }.body()
             return messageDto.toFrontendModel()
        }

        override suspend fun deleteMessage(messageId: Long) {
             httpClient.delete("$baseUrl/messages/$messageId")
        }

        override suspend fun getModels(): List<LanguageModel> {
            val modelDtos: List<ModelDto> = httpClient.get("$baseUrl/models").body()
            return modelDtos.map { it.toFrontendModel() }
        }

         override suspend fun saveApiKey(key: String) {
            httpClient.post("$baseUrl/settings/apiKey") {
                contentType(ContentType.Application.Json)
                setBody(mapOf("apiKey" to key))
            }
        }

        override suspend fun isApiKeyConfigured(): Boolean {
             val response: HttpResponse = httpClient.get("$baseUrl/settings/apiKey/status")
             return response.bodyAsText().toBoolean() // Assuming backend returns "true" or "false"
        }

        override suspend fun getAllModelSettings(): Map<String, ModelSettings> {
             val settingsDtos: List<ModelSettingsDto> = httpClient.get("$baseUrl/settings/models").body()
             return settingsDtos.associate { it.modelId to it.toFrontendModel() }
        }

         override suspend fun saveModelSettings(modelId: String, settings: ModelSettings) {
             httpClient.post("$baseUrl/settings/models/$modelId") {
                 contentType(ContentType.Application.Json)
                 setBody(settings.toBackendDto()) // Map frontend model to backend DTO
             }
         }

        // Helper mapping functions (Frontend Model <-> Backend DTO)
        private fun SessionDto.toFrontendModel() = ChatSession(id, name, group)
        private fun MessageDto.toFrontendModel() = ChatMessage(id, sessionId, role, content, timestamp)
        private fun ModelDto.toFrontendModel() = LanguageModel(id, name)
        private fun ModelSettingsDto.toFrontendModel() = ModelSettings(systemMessage) // Add other fields
        private fun ModelSettings.toBackendDto() = ModelSettingsDto(systemMessage = systemMessage) // Add other fields

    }

    // Ktor Client Setup
    fun createHttpClient(): HttpClient {
        return HttpClient(CIO) { // Use CIO engine for desktop
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true // Be lenient with JSON fields
                    prettyPrint = true
                })
            }
            // Add other necessary configurations (timeouts, retries, logging)
            // install(Logging) { level = LogLevel.INFO }
        }
    }
    ```

3.  **Instantiate in Main:** Create the `HttpClient` and the `RealChatApi` instance in your `main` or application entry point and pass it to your `ChatState`.

    ```kotlin
    fun main() = application {
        // Assuming your Ktor server setup is also within this process and started before the UI
        val httpClient = remember { createHttpClient() }
        val chatApi = remember { RealChatApi(httpClient) }
        val chatState = remember { ChatState(chatApi) }

        Window(onCloseRequest = ::exitApplication, title = "Chatbot") {
            AppLayout(chatState)
        }
    }
    ```

**Error Handling:** The `ChatApi` implementation should catch exceptions from HTTP calls and propagate them (e.g., throw them, or ideally, the `ChatState` should handle them by updating an `error` state variable). Coroutine `catch` blocks in the state holder are crucial for handling errors from Flows.

## 6. Testing Approach

A layered testing approach is recommended to ensure different parts of the frontend are robust.

*   **Unit Tests:**
    *   **State Holder (`ChatState`) Logic:** Test that functions in the `ChatState` correctly update state variables based on inputs and API responses. Mock the `ChatApi` dependency using libraries like MockK. Verify state changes and that the correct API calls are made with expected parameters. Use `runTest` from `kotlinx-coroutines-test` for testing coroutines.
    *   **API Client (`RealChatApi`) Logic:** Test that the Ktor Client implementation correctly constructs HTTP requests (URLs, methods, headers, body) for different API calls. Mock the `HttpClient` responses to simulate backend success and failure scenarios.
    *   **Helper Functions:** Any pure functions used for data transformation, UI logic helpers, etc.

*   **Integration Tests:**
    *   **UI Components with State Holder:** Test how Composables react to changes in the `ChatState`. Use Compose UI testing (`compose.ui.test`) to launch components and simulate user interactions (clicks, text input). Observe how the UI recomposes and displays data fetched from a *mocked* `ChatState` or a real `ChatState` instance injected with mocked dependencies (like the API).
    *   **API Client with Backend (Optional but Recommended):** If possible, write tests that spin up a test instance of your Ktor backend server (within the test process) and use the `RealChatApi` to interact with it. This tests the *actual* HTTP communication layer.

*   **End-to-End (E2E) Tests (Future/Advanced):**
    *   Test full user flows (e.g., create session -> send message -> edit message -> delete message). Requires a running application instance (frontend + backend + database).
    *   Compose UI testing might support some level of E2E, but dedicated tools might be needed for complex desktop scenarios.

**Mocking:** Use MockK for mocking Kotlin classes and interfaces (`MockK` or `coMockK` for suspend functions/coroutines).

**Example Unit Test Sketch (Mocking `ChatApi`):**

```kotlin
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ChatStateTest {

    private val chatApi: ChatApi = mockk() // Mock the API interface
    private lateinit var chatState: ChatState
    private val testDispatcher = UnconfinedTestDispatcher() // Use test dispatcher for coroutines

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher) // Set test dispatcher for Main

        // Define default mock behavior for API calls ChatState makes on init
        coEvery { chatApi.getSessions() } returns emptyList()
        coEvery { chatApi.getModels() } returns emptyList()
        coEvery { chatApi.isApiKeyConfigured() } returns false
        coEvery { chatApi.getAllModelSettings() } returns emptyMap()
        // Mock the messages flow to emit an empty list initially
        coEvery { chatApi.getMessagesFlow(any()) } returns MutableStateFlow(emptyList())


        chatState = ChatState(chatApi) // Create instance with mocked API
    }

    @Test
    fun testInitialLoadSetsLoadingState() = runTest {
        // Arrange: Mock API calls to delay slightly
        coEvery { chatApi.getSessions() } coAnswers { kotlinx.coroutines.delay(100); emptyList() }

        // Act: ChatState loads automatically on init (or trigger a reload function)
        // Due to UnconfinedTestDispatcher, init coroutine runs immediately

        // Assert: Loading state is true during the async operation
        assertTrue(chatState.isLoading)

        // Let coroutine complete
        kotlinx.coroutines.delay(200)

        // Assert: Loading state is false after completion
        assertEquals(false, chatState.isLoading)
        assertNull(chatState.error.value) // No error expected in this case
    }

    @Test
    fun testSelectSessionUpdatesSelectedIdAndLoadsMessages() = runTest {
        // Arrange: Mock sessions and messages flow
        val sessions = listOf(ChatSession(1, "Session 1", null), ChatSession(2, "Session 2", null))
        val session2Messages = listOf(ChatMessage(101, 2, "user", "Hello", 1000L))
        val messagesFlow = MutableStateFlow(emptyList<ChatMessage>()) // Initial empty flow
        coEvery { chatApi.getSessions() } returns sessions
        coEvery { chatApi.getMessagesFlow(1) } returns MutableStateFlow(emptyList()) // Mock flow for session 1
        coEvery { chatApi.getMessagesFlow(2) } returns messagesFlow // Mock flow for session 2

        // Re-initialize state after setting up mocks
        chatState = ChatState(chatApi)
        kotlinx.coroutines.delay(10) // Allow init coroutine to finish

        assertEquals(0, chatState.sessions.size) // Should load sessions on init
        // Need to trigger init loading here or in setUp for this test state
        // Let's assume init loads sessions correctly before test logic
        // A better pattern might be to have an explicit `load()` function

        // Manual setup for test:
        chatState.sessions.addAll(sessions)
        assertEquals(2, chatState.sessions.size)
        assertNull(chatState.selectedSessionId)
        assertEquals(0, chatState.messages.value.size)

        // Act: Select session 2
        chatState.selectSession(2)
        kotlinx.coroutines.delay(10) // Allow state flow flatMapLatest to process

        // Assert: selectedSessionId is updated
        assertEquals(2, chatState.selectedSessionId)

        // Assert: messages are loaded (pushed from the mocked flow)
        messagesFlow.value = session2Messages // Simulate API pushing messages
        kotlinx.coroutines.delay(10) // Allow messages flow collector to run
        assertEquals(1, chatState.messages.value.size)
        assertEquals("Hello", chatState.messages.value.first().content)
    }

     @Test
    fun testSendMessageCallsApiAndClearsError() = runTest {
        // Arrange: Mock API call to send message
        val sessionId = 1L
        val messageText = "Test message"
        val modelId = "gpt-4"
        chatState.selectedSessionId = sessionId // Manually set state for the test
        chatState.currentModelId = modelId // Manually set state

        coEvery { chatApi.sendMessage(sessionId, messageText, modelId) } answers { /* Simulate success */ }

        chatState._error.value = "Some previous error" // Set an error to ensure it's cleared

        // Act: Send message
        chatState.sendMessage(messageText)
        kotlinx.coroutines.delay(10) // Allow coroutine to launch/run

        // Assert: API call was made
        io.mockk.coVerify(exactly = 1) { chatApi.sendMessage(sessionId, messageText, modelId) }

        // Assert: Error is cleared
        assertNull(chatState.error.value)
        assertEquals(false, chatState.isLoading) // Should be false after successful call
    }


    // Add more tests for updateMessage, deleteMessage, saveApiKey, saveModelSettings, error handling, etc.
}
```

## 7. Code Examples

Here are basic Compose code examples for some key components. These are simplified and would need integration with the `ChatState` and handling various states (loading, error, editing).

**a) Basic Message Item Composable**

```kotlin
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp

@Composable
fun MessageItem(
    message: ChatMessage,
    isEditing: Boolean,
    onEditClicked: (Long) -> Unit,
    onDeleteClicked: (Long) -> Unit,
    onCopyClicked: (Long) -> Unit,
    onSaveClicked: (Long, String) -> Unit,
    onCancelEdit: () -> Unit
) {
    val isUser = message.role == "user"
    val bubbleColor = if (isUser) MaterialTheme.colors.primaryVariant else Color(0xFFE0E0E0) // Light grey for assistant
    val bubbleAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    val textColor = if (isUser) Color.White else Color.Black // White text on primary, black on grey

    var editedContent by remember { mutableStateOf(TextFieldValue(message.content)) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top // Align bubbles at top
    ) {
        // Optional: Avatar or role indicator here

        Column(
            modifier = Modifier
                .widthIn(max = 0.8f.dp) // Limit bubble width
                .clip(RoundedCornerShape(8.dp))
                .background(bubbleColor)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            if (isEditing) {
                TextField(
                    value = editedContent,
                    onValueChange = { editedContent = it },
                    modifier = Modifier.fillMaxWidth(),
                    colors = TextFieldDefaults.textFieldColors(
                        backgroundColor = Color.Transparent, // Make it blend with bubble
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = textColor
                    ),
                    textStyle = LocalTextStyle.current.copy(color = textColor)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onCancelEdit) { Text("Cancel", color = textColor) }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { onSaveClicked(message.id, editedContent.text) }) { Text("Save", color = textColor) }
                }
            } else {
                Text(
                    text = message.content,
                    color = textColor
                )
            }
        }

        // Action icons (simplified, could be a context menu or hover actions)
         if (!isEditing) {
            Row(modifier = Modifier.align(Alignment.CenterVertically).padding(horizontal = 4.dp)) {
                 IconButton(onClick = { onCopyClicked(message.id) }, modifier = Modifier.size(24.dp)) {
                     Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = LocalContentColor.current.copy(alpha = LocalContentAlpha.current))
                 }
                 IconButton(onClick = { onEditClicked(message.id) }, modifier = Modifier.size(24.dp)) {
                     Icon(Icons.Default.Edit, contentDescription = "Edit", tint = LocalContentColor.current.copy(alpha = LocalContentAlpha.current))
                 }
                 IconButton(onClick = { onDeleteClicked(message.id) }, modifier = Modifier.size(24.dp)) {
                     Icon(Icons.Default.Delete, contentDescription = "Delete", tint = LocalContentColor.current.copy(alpha = LocalContentAlpha.current))
                 }
            }
         }
    }
}
```

**b) Input Area with Model Selection**

```kotlin
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties

@Composable
fun InputArea(
    availableModels: List<LanguageModel>,
    selectedModelId: String?,
    onModelSelected: (String) -> Unit,
    onSendMessage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var inputText by remember { mutableStateOf("") }
    var modelDropdownExpanded by remember { mutableStateOf(false) }

    val selectedModel = availableModels.find { it.id == selectedModelId }

    Row(
        modifier = modifier
            .padding(8.dp)
            .fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Model Selection Dropdown
        Box(modifier = Modifier.width(150.dp).padding(end = 8.dp)) {
            Button(onClick = { modelDropdownExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(selectedModel?.name ?: "Select Model")
            }
            DropdownMenu(
                expanded = modelDropdownExpanded,
                onDismissRequest = { modelDropdownExpanded = false },
                properties = PopupProperties(focusable = true)
            ) {
                availableModels.forEach { model ->
                    DropdownMenuItem(onClick = {
                        onModelSelected(model.id)
                        modelDropdownExpanded = false
                    }) {
                        Text(model.name)
                    }
                }
            }
        }


        // Text Input Field
        OutlinedTextField(
            value = inputText,
            onValueChange = { inputText = it },
            label = { Text("Enter message...") },
            modifier = Modifier.weight(1f).fillMaxHeight(), // Takes remaining width
            singleLine = false, // Allow multi-line input
            colors = TextFieldDefaults.outlinedTextFieldColors(
                // Customize colors if needed
            )
            // TODO: Add keyboard action for sending on Enter key
        )

        Spacer(modifier = Modifier.width(8.dp))

        // Send Button
        Button(
            onClick = {
                if (inputText.isNotBlank()) {
                    onSendMessage(inputText)
                    inputText = "" // Clear input after sending
                }
            },
            enabled = inputText.isNotBlank() && selectedModelId != null, // Enable only if text and model are present
            modifier = Modifier.align(Alignment.Bottom) // Align button to bottom of the row
        ) {
             Icon(Icons.Default.Send, contentDescription = "Send")
             Text("Send") // Optional text on button
        }
    }
}
```

**c) Basic Session List Panel**

```kotlin
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun SessionListPanel(
    sessions: List<ChatSession>,
    selectedSessionId: Long?,
    onSessionSelected: (Long) -> Unit,
    onCreateNewSession: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.padding(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Sessions", style = MaterialTheme.typography.h6)
            IconButton(onClick = onCreateNewSession) {
                Icon(Icons.Default.Add, contentDescription = "Create New Session")
            }
        }
        Divider()
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(sessions, key = { it.id }) { session ->
                SessionListItem(
                    session = session,
                    isSelected = session.id == selectedSessionId,
                    onSessionClicked = { onSessionSelected(it.id) }
                    // TODO: Add context menu / right-click handling for grouping, editing
                )
            }
        }
        Divider()
        // Future place for filters, sorting etc.
        Spacer(modifier = Modifier.height(4.dp))
         Text("Sessions: ${sessions.size}", style = MaterialTheme.typography.caption)
    }
}

@Composable
fun SessionListItem(
    session: ChatSession,
    isSelected: Boolean,
    onSessionClicked: () -> Unit,
    modifier: Modifier = Modifier
) {
    val backgroundColor = if (isSelected) MaterialTheme.colors.primary.copy(alpha = 0.2f) else Color.Transparent
    val textColor = if (isSelected) MaterialTheme.colors.primary else LocalContentColor.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onSessionClicked() }
            .background(backgroundColor)
            .padding(8.dp)
    ) {
        Text(
            text = session.name.ifBlank { "New Session ${session.id}" }, // Use default name if empty
            style = MaterialTheme.typography.subtitle1,
            color = textColor
        )
        if (!session.group.isNullOrBlank()) {
             Text(
                 text = session.group,
                 style = MaterialTheme.typography.caption,
                 color = textColor.copy(alpha = 0.7f)
            )
        }
        // Optional: display first message snippet
        // Text("...", style = MaterialTheme.typography.body2, maxLines = 1)
    }
}
```

These examples demonstrate how Compose components can be built, how they receive data, and how they trigger events (which the parent components/State Holder handle). The `ChatArea` would contain the `LazyColumn` displaying `MessageItem`s, observing the `messages` state from the `ChatState`. Remember to add necessary imports and dependencies (Compose, Material, Ktor Client, Coroutines, Flow, Serialization, MockK for testing).

