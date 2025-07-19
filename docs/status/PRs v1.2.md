Okay, Mark, here is a breakdown of the remaining work into smaller, focused Pull Requests (PRs), assigned to the respective team members (Alex - Backend, Maya - Frontend, Eric - Architect/Cross-cutting).

This breakdown attempts to follow a logical flow, starting with core backend logic, then wiring the API, setting up the frontend infrastructure, implementing frontend state logic, and finally building the UI components. Dependencies are noted where clear.

**Phase 1: Core Backend Logic & Data Access (Alex)**

These PRs focus on completing the business logic and DAO interactions in the `server` module.

1.  **PR: Implement Core Chat Service Logic (E1.S4)**
*   **Assignee:** Alex
*   **Focus:** Implement `ChatService.processNewMessage`. This involves:
    *   Saving the User message.
    *   Updating the parent message's `childrenMessageIds`.
    *   Building the LLM context based on the thread branch up to the parent.
    *   Calling the `LLMApiClient`.
    *   Saving the Assistant message (with correct parent ID).
    *   Updating the User message's `childrenMessageIds`.
    *   Ensuring all DB operations are within a transaction and are asynchronous (E7.S6).
*   **Dependencies:** Basic `MessageDao` methods (add, get, update children). Basic `SessionDao` method (get session with current model/settings IDs).
*   **Tests:** Add unit tests for `MessageServiceImpl.processNewMessage`.

2.  **PR: Implement Session & Message Management Services (E2.S1/S5/S6, E3.S3/S4)**
*   **Assignee:** Alex
*   **Focus:** Implement `SessionService` and remaining `MessageService` methods:
    *   `createSession` (E2.S1)
    *   `getSessionDetails` (returns session with all messages, E2.S4 prerequisite)
    *   `updateSessionName` (E2.S5)
    *   `deleteSession` (E2.S6, ensure messages are deleted via cascade or manual DAO call)
    *   `updateMessageContent` (E3.S3)
    *   `deleteMessage` (E3.S4, implement recursive child deletion logic at Service/DAO level).
    *   Ensure all DB operations are within transactions and are asynchronous (E7.S6).
*   **Dependencies:** `SessionDao`, `MessageDao` implementations.
*   **Tests:** Add unit tests for `SessionServiceImpl` and remaining `MessageServiceImpl` methods.

3.  **PR: Implement Group Management Services (E6.S1/S3/S5/S6)**
*   **Assignee:** Alex
*   **Focus:** Implement `GroupService` methods:
    *   `createGroup` (E6.S3)
    *   `getAllGroups` (E6.S4 prerequisite)
    *   `renameGroup` (E6.S5)
    *   `deleteGroup` (E6.S6, ensure sessions are ungrouped).
    *   Implement `SessionService.updateSessionGroup` (E6.S1).
    *   Ensure all DB operations are within transactions and are asynchronous (E7.S6).
*   **Dependencies:** `GroupDao`, `SessionDao` methods.
*   **Tests:** Add unit tests for `GroupServiceImpl`. Add test for `SessionServiceImpl.updateSessionGroup`.

4.  **PR: Implement Config Services (Providers, Models, Settings) (E4.S1/S3/S4/S5/S6)**
*   **Assignee:** Alex
*   **Focus:** Implement `LLMProviderService`, `LLMModelService`, `ModelSettingsService` CRUD methods (add, get, update, delete for each).
*   **Dependencies:** `ProviderDao`, `ModelDao`, `SettingsDao` implementations. Secure credential management PR (see Eric's).
*   **Tests:** Add unit tests for these service implementations.

**Phase 2: Core Application Infrastructure & API Wiring (Eric)**

These PRs focus on foundational setup and connecting the frontend and backend modules via the internal API.

5.  **PR: Implement SQLite Database Initialization (E7.S4)**
*   **Assignee:** Eric
*   **Focus:** Implement database connection and Exposed schema initialization logic in `ServerMain.kt`.
    *   Configure Exposed to use SQLite.
    *   Ensure `SchemaUtils.create` or migration runs on startup.
    *   Define all Exposed tables (`ChatSessionTable`, `ChatMessageTable`, `ChatGroupTable`, `LLMProviderTable`, `LLMModelTable`, `ModelSettingsTable`, `ApiSecretTable`) with correct column types, nullable fields, indexes, and foreign key constraints (especially `ChatMessage.sessionId` ON DELETE CASCADE, `ChatSession.groupId`, `ChatMessage.parentMessageId`, `ModelSettings.modelId`, `LLMModel.providerId`). Store `childrenMessageIds` appropriately (e.g. JSON/string list).
    *   Ensure DB operations are asynchronous (E7.S6).
*   **Dependencies:** Exposed library, database drivers. Agreement on how `childrenMessageIds` is stored.
*   **Tests:** Add basic integration tests for database schema creation.

6.  **PR: Implement Secure Credential Manager (E5.S1)**
*   **Assignee:** Eric
*   **Focus:** Implement `DbEncryptedCredentialManager` using custom envelope encryption.
    *   Implement `EncryptionService` and `CryptoProvider` with KEK/DEK logic.
    *   Implement `ApiSecretDao` for the `ApiSecretTable`.
    *   Implement `storeCredential`, `getCredential`, `deleteCredential` methods.
    *   Ensure operations are asynchronous (E7.S6).
*   **Dependencies:** Encryption library (if any besides standard Kotlin Crypto). `ApiSecretTable` schema from PR 5.
*   **Tests:** Add unit tests for `DbEncryptedCredentialManager` covering encrypt, decrypt, store, retrieve, delete, handling non-existent aliases.

7.  **PR: Implement Ktor Server Startup & Base Frontend Setup (E7.S3)**
*   **Assignee:** Eric
*   **Focus:** Implement application entry point in `AppMain.kt`.
    *   Configure and start embedded Ktor server (Netty) from the `server` module.
    *   Implement logic to bind to `localhost` and find a port (e.g., try 8080, then dynamic). Communicate the port to the `app` module.
    *   Set up base Koin DI for the `app` module, including configuring the `HttpClient` using the determined port.
    *   Configure Ktor server features (ContentNegotiation for JSON).
*   **Dependencies:** Ktor server/client libraries, Koin.
*   **Tests:** Basic app startup test ensuring the server starts and the client can be configured.

8.  **PR: Wire Backend API - Core Chat & Session (E1.S*, E2.S*, E3.S*)**
*   **Assignee:** Eric (or Alex, collaborating with Eric on API contract)
*   **Focus:** Implement Ktor routing in `server/ktor/routes` for core chat and session endpoints.
    *   Wire `/api/v1/sessions` (GET, POST) to `SessionService`.
    *   Wire `/api/v1/sessions/{sessionId}` (GET, DELETE) to `SessionService`.
    *   Wire `/api/v1/sessions/{sessionId}/messages` (POST) to `ChatService.processNewMessage`.
    *   Wire `/api/v1/messages/{messageId}` (DELETE) to `ChatService.deleteMessage`.
    *   Wire `/api/v1/messages/{messageId}/content` (PUT) to `ChatService.updateMessageContent`.
    *   Implement Ktor `Resources` plugin handlers using the `common` resource classes.
    *   Implement `ApiError` serialization and return appropriate HTTP status codes and `ApiError` bodies for expected errors (e.g., 400, 404, 409, 500 with specific `ApiErrorCode`).
    *   Ensure route handlers use appropriate dispatchers for calling suspend service methods.
*   **Dependencies:** PRs 1, 2, 3. `common` module DTOs and Resources.
*   **Tests:** Add Ktor integration tests for these API endpoints using `testApplication`.

9.  **PR: Wire Backend API - Session & Group Management (E2.S*, E6.S*)**
*   **Assignee:** Eric (or Alex)
*   **Focus:** Implement Ktor routing for remaining session and group management endpoints.
    *   Wire `/api/v1/sessions/{sessionId}/name` (PUT) to `SessionService.updateSessionName`.
    *   Wire `/api/v1/sessions/{sessionId}/group` (PUT) to `SessionService.updateSessionGroup`.
    *   Wire `/api/v1/sessions/{sessionId}/leafMessage` (PUT) to `SessionService.updateSessionLeafMessage`.
    *   Wire `/api/v1/groups` (GET, POST) to `GroupService`.
    *   Wire `/api/v1/groups/{groupId}` (PUT, DELETE) to `GroupService`.
    *   Ensure `ApiError` handling.
*   **Dependencies:** PRs 3, 4. `common` module DTOs and Resources.
*   **Tests:** Add Ktor integration tests for these API endpoints.

10. **PR: Wire Backend API - Config (Providers, Models, Settings) (E4.S*, E5.S*)**
*   **Assignee:** Eric (or Alex)
*   **Focus:** Implement Ktor routing for config endpoints.
    *   Wire `/api/v1/providers` (GET, POST), `/api/v1/providers/{providerId}` (GET, PUT, DELETE), `/api/v1/providers/{providerId}/credential` (PUT), `/api/v1/providers/{providerId}/models` (GET).
    *   Wire `/api/v1/models` (GET, POST), `/api/v1/models/{modelId}` (GET, PUT, DELETE), `/api/v1/models/{modelId}/apikey/status` (GET).
    *   Wire `/api/v1/settings/{settingsId}` (GET, PUT, DELETE), `/api/v1/models/{modelId}/settings` (GET, POST).
    *   Ensure `ApiError` handling, especially for specific codes like `RESOURCE_IN_USE`.
*   **Dependencies:** PR 4, PR 5b, PR 5a (via services). `common` module DTOs and Resources.
*   **Tests:** Add Ktor integration tests for these API endpoints.

**Phase 3: Frontend API Client Implementation (Maya)**

These PRs focus on building the client-side code in the `app` module to call the backend API.

11. **PR: Implement Frontend HttpClient & Either Conversion (E7.S3, E1.S6)**
*   **Assignee:** Maya
*   **Focus:** Implement the concrete `KtorHttpClient` setup in `app/api/client`, configured with the port from PR 7 and JSON serialization.
    *   Create the `ApiException` class.
    *   Create a utility function or base class for API calls that wrap Ktor's `client.get(...)`/`post(...)` etc. calls in a `try/catch`.
    *   Inside the catch block, check for `HttpResponseException`, deserialize the response body into `ApiError`, and return `Either.Left(apiError)`.
    *   On success, return `Either.Right(result)`. Handle other exceptions (network errors, deserialization errors) by wrapping them in a generic `ApiError` (e.g., `INTERNAL`, `EXTERNAL_SERVICE_ERROR` if appropriate) and returning `Either.Left`.
*   **Dependencies:** PR 7. Ktor client libraries, `kotlinx.serialization`, Arrow Core.
*   **Tests:** Add unit tests/integration tests for the API client helper/base logic to ensure it correctly produces `Either.Left` or `Either.Right` based on responses (can mock Ktor responses for this).

12. **PR: Implement Frontend Chat & Session API Clients (E1.S*, E2.S*)**
*   **Assignee:** Maya
*   **Focus:** Implement `KtorChatApiClient` and `KtorSessionApiClient` classes in `app/api/client`.
    *   Implement all methods defined in the `ChatApi` and `SessionApi` interfaces.
    *   Use the `KtorHttpClient` base setup from PR 11 to make the actual HTTP calls, leveraging Ktor's `resources` feature with the `common` module resource classes.
    *   Ensure all methods are `suspend` functions and return `Either<ApiError, T>`.
*   **Dependencies:** PR 11. `ChatApi`, `SessionApi` interfaces. `common` module DTOs and Resources. PRs 8, 9 (Backend APIs must be wired for these to work end-to-end).
*   **Tests:** Add integration tests calling the actual backend endpoints via these clients.

13. **PR: Implement Frontend Group & Config API Clients (E4.S*, E5.S*, E6.S*)**
*   **Assignee:** Maya
*   **Focus:** Implement `KtorGroupApiClient`, `KtorProviderApiClient`, `KtorModelApiClient`, `KtorSettingsApiClient` classes in `app/api/client`.
    *   Implement all methods defined in their respective interfaces.
    *   Use the `KtorHttpClient` base setup from PR 11 and Ktor `resources`.
    *   Ensure all methods are `suspend` and return `Either<ApiError, T>`.
*   **Dependencies:** PR 11. Corresponding interfaces. `common` module DTOs and Resources. PRs 9, 10 (Backend APIs must be wired).
*   **Tests:** Add integration tests calling the actual backend endpoints via these clients.

**Phase 4: Frontend UI State (ViewModels) (Maya)**

These PRs focus on implementing the ViewModel logic using the KMP ViewModel pattern.

14. **PR: Implement UiState and Base Chat/Session ViewModels (E1.S*, E2.S*, E6.S*)**
*   **Assignee:** Maya
*   **Focus:**
    *   Define the `UiState<E, T>` sealed class (if not done).
    *   Create `ChatViewModel` and `SessionListViewModel` classes inheriting from `ViewModel`.
    *   Define core `StateFlow`s (`_sessionState` for `ChatViewModel`, `_listState` for `SessionListViewModel`, `_selectedSessionId`).
    *   Implement `ChatViewModel.loadSession` and `ChatViewModel.sendMessage` using `viewModelScope.launch` and calling `ChatApi`/`SessionApi`, folding over the `Either` results to update `_sessionState`.
    *   Implement `SessionListViewModel.loadSessionsAndGroups` using `parZip` and folding over results to update `_listState`.
    *   Implement `SessionListViewModel.selectSession`.
    *   Implement `buildThreadBranch` function.
    *   Implement `SessionListData` data class with `groupedSessions` derived property.
    *   Ensure Koin modules in `app` provide API client implementations, ready for injection into ViewModels via `viewModel { ... }`.
*   **Dependencies:** PRs 11, 12, 13 (Frontend clients). `UiState` definition. Arrow Core/Fx, KMP ViewModel, StateFlow. PRs 1, 3 (Backend services/API needed for these to work).
*   **Tests:** Unit tests for ViewModel logic using `runTest` (testing state transitions based on mocked API client results).

15. **PR: Implement Chat Actions ViewModel Logic (E1.S*, E3.S*, E4.S*)**
*   **Assignee:** Maya
*   **Focus:** Implement remaining action methods in `ChatViewModel`:
    *   `startReplyTo`, `cancelReply`, `updateInput`
    *   `startEditing`, `saveEditing`, `cancelEditing` (E3.S1/S2/S3)
    *   `deleteMessage` (E3.S4)
    *   `switchBranchToMessage` (E1.S5)
    *   `selectModel`, `selectSettings` (E4.S7)
    *   Ensure calls use `viewModelScope.launch` and handle `Either` results.
*   **Dependencies:** PR 14. PR 12 (Chat/Session clients). PRs 3, 8 (Backend message/session updates).
*   **Tests:** Unit tests for these ViewModel methods.

16. **PR: Implement Session/Group Actions ViewModel Logic (E2.S*, E6.S*)**
*   **Assignee:** Maya
*   **Focus:** Implement remaining action methods in `SessionListViewModel`:
    *   `createNewSession` (E2.S1)
    *   `renameSession` (E2.S5)
    *   `deleteSession` (E2.S6)
    *   `assignSessionToGroup` (E6.S1/S7)
    *   `startCreatingNewGroup`, `cancelCreatingNewGroup`, `updateNewGroupNameInput`, `createNewGroup` (E6.S3)
    *   `startRenamingGroup`, `cancelRenamingGroup`, `updateEditingGroupNameInput`, `saveRenamedGroup` (E6.S5)
    *   `deleteGroup` (E6.S6)
    *   Ensure calls use `viewModelScope.launch` and handle `Either` results, updating `_listState`.
*   **Dependencies:** PR 14. PR 12, 13 (Session/Group clients). PRs 3, 4, 9 (Backend session/group updates).
*   **Tests:** Unit tests for these ViewModel methods.

17. **PR: Implement Config ViewModels (E4.S*, E5.S*)**
*   **Assignee:** Maya
*   **Focus:** Create and implement ViewModels for managing LLM Providers, Models, and Settings, using the same ViewModel/StateFlow/UiState/Either pattern.
    *   Need ViewModels like `ProviderConfigViewModel`, `ModelConfigViewModel`, `SettingsConfigViewModel`.
    *   Implement loading lists (`getAllProviders`, `getModelsByProviderId`, `getSettingsByModelId`).
    *   Implement CRUD actions (add, edit, delete).
    *   Implement API key status check (`getModelApiKeyStatus`, E5.S4).
*   **Dependencies:** PR 13. PR 13 (Config clients). PRs 4, 5b, 5a, 10 (Backend config services/API).
*   **Tests:** Unit tests for these ViewModels.

**Phase 5: Frontend UI Components (Maya)**

These PRs focus on building the actual visual elements in Compose for Desktop, consuming the ViewModels.

18. **PR: Implement Base App Layout & ViewModel Integration (E7.S2)**
*   **Assignee:** Maya
*   **Focus:**
    *   Implement the top-level `AppLayout` Composable in `app/ui`.
    *   Set up the basic navigation structure (e.g., showing Session List and Chat Area side-by-side).
    *   Retrieve `SessionListViewModel` and `ChatViewModel` instances using `viewModel { ... }` factory function within the appropriate screen-level Composables.
    *   Pass these ViewModels or their relevant states/actions down to child UI components.
*   **Dependencies:** PR 14, PR 17 (ViewModels). Basic Compose Desktop setup.

19. **PR: Implement Session List UI (E2.S3, E6.S2, E6.S*)**
*   **Assignee:** Maya
*   **Focus:** Implement the `SessionListPanel` Composable.
    *   Consume `SessionListViewModel.listState` (UiState<ApiError, SessionListData>) and display the grouped sessions. Handle Loading/Error/Idle states in the UI.
    *   Display group headers and session summaries under the correct groups.
    *   Implement click handling to trigger `sessionListViewModel.selectSession`.
    *   Implement UI for creating, renaming, deleting groups/sessions, triggering ViewModel actions (`createNewGroup`, `renameGroup`, `deleteGroup`, `createNewSession`, `renameSession`, `deleteSession`).
    *   Implement UI for assigning sessions to groups (e.g., context menu or dialog triggering `assignSessionToGroup`).
*   **Dependencies:** PR 18. PR 16 (SessionListViewModel ViewModel).

20. **PR: Implement Chat Area UI (Message Display) (E1.S*)**
*   **Assignee:** Maya
*   **Focus:** Implement the main chat message display area Composable.
    *   Consume `ChatViewModel.sessionState` and `ChatViewModel.displayedMessages` (StateFlows).
    *   Display loading/error states based on `sessionState`.
    *   Render the messages from `displayedMessages`, visually styling user vs. assistant messages.
    *   Implement basic message layout.
*   **Dependencies:** PR 18. PR 15 (ChatViewModel ViewModel).

21. **PR: Implement Input Area UI (E1.S*, E1.S7)**
*   **Assignee:** Maya
*   **Focus:** Implement the message input area Composable.
    *   Consume `ChatViewModel.inputContent`.
    *   Implement text input field and update `chatViewModel.updateInput`.
    *   Implement Send button, trigger `chatViewModel.sendMessage`. Disable when input is blank.
    *   Display the UI indicating which message is being replied to (`chatViewModel.replyTargetMessage`). Implement cancel reply UI (`chatViewModel.cancelReply`).
*   **Dependencies:** PR 18. PR 15 (ChatViewModel ViewModel).

22. **PR: Implement Message Actions UI (Edit, Delete, Reply, Copy) (E3.S*)**
*   **Assignee:** Maya
*   **Focus:** Add UI elements (e.g., icons on hover, context menu) to message items to trigger actions.
    *   Implement "Reply" action triggering `chatViewModel.startReplyTo`.
    *   Implement "Delete" action triggering `chatViewModel.deleteMessage` (with confirmation dialog).
    *   Implement "Edit" action triggering `chatViewModel.startEditing`.
    *   Implement inline editing UI when `chatViewModel.editingMessage` is set (input field, Save/Cancel buttons triggering `chatViewModel.saveEditing`/`cancelEditing`).
*   **Dependencies:** PR 20. PR 15 (ChatViewModel ViewModel actions). Clipboard logic PR.

23. **PR: Implement Thread Branch Navigation UI (E1.S5)**
*   **Assignee:** Maya
*   **Focus:** Implement visual indicators on messages that have children not currently displayed.
    *   Implement the UI mechanism (e.g., clicking the indicator, a dedicated button) to trigger `chatViewModel.switchBranchToMessage`.
*   **Dependencies:** PR 20. PR 15 (ChatViewModel ViewModel).

24. **PR: Implement Settings Screen UI (E4.S*, E5.S*)**
*   **Assignee:** Maya
*   **Focus:** Implement the UI for the Settings screen (accessed via navigation).
    *   Consume Config ViewModels (PR 17).
    *   Display lists of Providers, Models, Settings. Handle loading/error.
    *   Implement forms for adding/editing Providers, Models, Settings.
    *   Integrate API key input (masked) and status display (E5.S4).
    *   Implement actions for saving/deleting config items.
*   **Dependencies:** PR 18 (Navigation setup). PR 17 (Config ViewModels).

**Phase 6: Polish & Cross-cutting (Eric, Maya)**

Final touches and broader application concerns.

25. **PR: Implement Copy to Clipboard (E2.S7, E3.S5)**
*   **Assignee:** Maya
*   **Focus:** Implement the actual logic using Compose Desktop's `ClipboardManager` to copy message content (raw text) or the formatted visible thread branch.
*   **Dependencies:** PR 22 (Message Actions UI). PR 19 (Session List UI). Compose Desktop Clipboard API.

26. **PR: Implement Graceful Application Shutdown (E7.S7)**
*   **Assignee:** Eric
*   **Focus:** Implement logic in `AppMain.kt` to handle window close/OS termination signals.
    *   Properly stop the embedded Ktor server.
    *   Properly close the SQLite database connection.
    *   Dispose of other resources (like Ktor HttpClient).
    *   Ensure ViewModelScopes are cancelled (handled by KMP ViewModel integration).
*   **Dependencies:** PR 7 (Server startup). PR 5 (DB setup).

27. **PR: Implement Windows 11 Installer Packaging (E7.S1)**
*   **Assignee:** Eric
*   **Focus:** Configure the `app` module's Gradle build script (`build.gradle.kts`) to use Compose Desktop's packaging tasks to generate a Windows installer (.exe).
*   **Dependencies:** Gradle, Compose Desktop packaging tools.

This detailed breakdown should provide a clear roadmap for the remaining work, allowing tasks to be assigned and tackled incrementally via pull requests. Remember that these are suggested groupings and can be adjusted based on team capacity and specific challenges encountered during implementation. Good luck!
