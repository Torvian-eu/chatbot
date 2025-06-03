**PR 0: Initial Multi-Module Project Setup**
*   **Assignee:** Architect
*   **Description:** Set up the basic multi-module Gradle project structure (`common`, `server`, `app`) with initial build.gradle.kts files and settings.gradle.kts to include them.
*   **Files Included:**
*   `<project_root>/settings.gradle.kts`
*   `<project_root>/build.gradle.kts`
*   `common/build.gradle.kts`
*   `server/build.gradle.kts` (Initial version, adds `common` dependency)
*   `app/build.gradle.kts` (Initial version, adds `common` dependency)
*   **Completes (Partially):** E7.S5 (Modular structure and shared models)
*   **Dependencies:** None ( foundational )

---

**PR 1: Common Module - Shared Data Models (DTOs)**

*   **Assignee:** Alex
*   **Reviewer:** Eric, Mark
*   **Description:** Introduce the core data transfer objects (DTOs) that are shared between the `app` (frontend) and `server` (backend) modules. These models include fields to support V1.1 features like message threading (`parentMessageId`, `childrenMessageIds`) and session grouping (`groupId`).
*   **Stories Addressed:** Foundation for E1.S1, E1.S4, E1.S5, E2.S1, E2.S3, E2.S4, E6.S1, E6.S2, E6.S3, E6.S4, Epic 4, Epic 5.
*   **Key Files:**
  *   `common/src/main/kotlin/eu/torvian/chatbot/common/models/ChatSession.kt`
  *   `common/src/main/kotlin/eu/torvian/chatbot/common/models/ChatSessionSummary.kt`
  *   `common/src/main/kotlin/eu/torvian/chatbot/common/models/ChatMessage.kt` (includes sealed class and subclasses)
  *   `common/src/main/kotlin/eu/torvian/chatbot/common/models/ChatGroup.kt`
  *   `common/src/main/kotlin/eu/torvian/chatbot/common/models/LLMModel.kt`
  *   `common/src/main/kotlin/eu/torvian/chatbot/common/models/ModelSettings.kt`
  *   `common/src/main/kotlin/eu/torvian/chatbot/common/models/Request/Response DTOs based on OpenAPI spec (e.g., CreateSessionRequest, ProcessNewMessageRequest, etc.)`

---

**PR 2: Server Infrastructure - Database Schema & Transaction Scope**

*   **Assignee:** Alex
*   **Reviewer:** Eric
*   **Description:** Set up the SQLite database connection logic (E7.S4) and define the full V1.1 database schema using Exposed (E7.S4). This includes tables and foreign key constraints for sessions (with group ID), messages (with parent/children IDs), groups, models, and settings. Implement the `TransactionScope` utility pattern (based on Rogier's suggestion) using Exposed's `newSuspendedTransaction` for safe, coroutine-based database transactions.
*   **Stories Addressed:** E7.S4, TransactionScope pattern integration for E7.S6 cross-cutting.
*   **Key Files:**
  *   `server/src/main/kotlin/eu/torvian/chatbot/server/data/exposed/Database.kt`
  *   `server/src/main/kotlin/eu/torvian/chatbot/server/data/models/ChatSessions.kt`
  *   `server/src/main/kotlin/eu/torvian/chatbot/server/data/models/ChatMessages.kt` (includes threading columns/FKs)
  *   `server/src/main/kotlin/eu/torvian/chatbot/server/data/models/ChatGroups.kt`
  *   `server/src/main/kotlin/eu/torvian/chatbot/server/data/models/LLMModels.kt`
  *   `server/src/main/kotlin/eu/torvian/chatbot/server/data/models/ModelSettings.kt`
  *   `server/src/main/kotlin/eu/torvian/chatbot/server/utils/transactions/*.kt` (TransactionScope, ExposedTransactionScope, Marker, Extensions)

---

**PR 3: Server Infrastructure - Secure Credential Manager (E5.S1)**

*   **Assignee:** Alex
*   **Reviewer:** Eric
*   **Description:** Implement the secure storage mechanism for API keys using the Windows Credential Manager via JNA. This provides the `CredentialManager` interface and the `WinCredentialManager` implementation, wrapped in suspend functions.
*   **Stories Addressed:** E5.S1 (full implementation), E7.S6 (coroutines in external service).
*   **Key Files:**
  *   `server/src/main/kotlin/eu/torvian/chatbot/server/external/security/CredentialManager.kt`
  *   `server/src/main/kotlin/eu/torvian/chatbot/server/external/security/windows/WinCredentialManager.kt` (includes JNA integration)

---

**PR 4: Server Backend - Core DAOs (Session, Message, Group Basic CRUD)**

*   **Assignee:** Alex
*   **Reviewer:** Eric
*   **Description:** Implement the basic Create, Read (list/by ID), Delete (for Session/Group, Message basic delete stub) operations in the Exposed DAOs for Sessions, Messages, and Groups. These implementations should use suspend functions and expect to be called within a `TransactionScope`. Include mappings from Exposed `ResultRow` to common DTOs. Implement `addChildToMessage` in MessageDao.
*   **Stories Addressed:** E2.S1 (Session insert), E2.S3 (Session list), E2.S4 (Session by ID, Messages by Session ID), E6.S3 (Group insert), E6.S4 (Group list), Partial E3.S4 (Message delete placeholder), Partial E1.S4 (Message insert, addChildToMessage). Uses E7.S4 schema and E7.S6/TransactionScope.
*   **Key Files:**
  *   `server/src/main/kotlin/eu/torvian/chatbot/server/data/dao/SessionDao.kt` (interface)
  *   `server/src/main/kotlin/eu/torvian/chatbot/server/data/exposed/SessionDaoExposed.kt` (basic impl: getAll, getById, insert, basic update, delete - relying on CASCADE)
  *   `server/src/main/kotlin/eu/torvian/chatbot/server/data/dao/MessageDao.kt` (interface)
  *   `server/src/main/kotlin/eu/torvian/chatbot/server/data/exposed/MessageDaoExposed.kt` (basic impl: getBySessionId, insert, addChildToMessage, basic delete stub)
  *   `server/src/main/kotlin/eu.torvian/chatbot/server/data/dao/GroupDao.kt` (interface)
  *   `server/src/main/kotlin/eu/torvian/chatbot/server/data/exposed/GroupDaoExposed.kt` (basic impl: getAll, insert)

---

**PR 5: Server Backend - Core Services (E2, E6 Basics) & Ktor Routes**

*   **Assignee:** Alex
*   **Reviewer:** Eric, Maya
*   **Description:** Implement the backend Service layer methods corresponding to basic Session (E2.S1, E2.S3, E2.S4, E6.S1 assign), Group (E6.S3, E6.S4, E6.S6 delete logic), and Credential Status Check (E5.S4) features. These services orchestrate calls to the DAOs and use the `TransactionScope`. Set up the corresponding Ktor API routes (`/api/v1/sessions`, `/api/v1/sessions/{id}`, `/api/v1/sessions/{id}/group`, `/api/v1/groups`, `/api/v1/groups/{id}`, `/api/v1/models/{id}/apikey/status`) in `ApiRoutes.kt` to call these service methods, handling request/response serialization and basic error status codes.
*   **Stories Addressed:** E2.S1, E2.S3, E2.S4 backend, E6.S1 backend, E6.S3, E6.S4, E6.S6 backend, E5.S4 backend, E7.S3 (Ktor routing setup), E7.S6 (coroutines in services/routes). Depends on PRs 1, 2, 4.
*   **Key Files:**
  *   `server/src/main/kotlin/eu/torvian/chatbot/server/service/ChatService.kt` (updated interface with suspend)
  *   `server/src/main/kotlin/eu/torvian/chatbot/server/service/impl/ChatServiceImpl.kt` (implement E2.S1, E2.S3, E2.S4, E6.S1 assign, E5.S4, using TransactionScope)
  *   `server/src/main/kotlin/eu/torvian/chatbot/server/service/GroupService.kt` (updated interface with suspend)
  *   `server/src/main/kotlin/eu/torvian/chatbot/server/service/impl/GroupServiceImpl.kt` (implement E6.S3, E6.S4, E6.S6 delete logic using SessionDao.ungroupSessions and GroupDao)
  *   `server/src/main/kotlin/eu/torvian/chatbot/server/api/server/ApiRoutes.kt` (implement GET/POST /sessions, GET /sessions/{id}, PUT /sessions/{id}/group, GET/POST /groups, DELETE /groups/{id}, GET /models/{id}/apikey/status endpoints)
  *   `server/src/main/kotlin/eu/torvian/chatbot/server/external/llm/LLMApiClient.kt` (interface)
  *   `server/src/main/kotlin/eu/torvian/chatbot/server/external/llm/LLMApiClientKtor.kt` (stubbed implementation for S1)

---

**PR 6: App Infrastructure - DI, Main App Entry & Shutdown**

*   **Assignee:** Maya
*   **Reviewer:** Eric, Mark
*   **Description:** Set up the Koin Dependency Injection module (`appModule`) to wire all layers and components defined for Sprint 1 (Frontend Clients, Backend DAOs, Services, External Services, Database, TransactionScope, UI State holders). Implement the `main` application entry point (E7.S2) to start Koin, initialize the database, start the embedded Ktor server, launch the Compose window, and handle graceful shutdown (E7.S7). Include the `KtorHttpClientFactory`.
*   **Stories Addressed:** E7.S2, E7.S7, DI wiring (cross-cutting). Depends on PRs 2, 3, 4, 5 (for the classes being wired).
*   **Key Files:**
  *   `app/src/main/kotlin/eu/torvian/chatbot/app/App.kt` (main function, appModule DI definition, KtorHttpClientFactory)

---

**PR 7: App Frontend - Core API Clients (Chat & Group)**

*   **Assignee:** Maya
*   **Reviewer:** Alex, Eric
*   **Description:** Implement the Ktor-based concrete classes for the Frontend API Client interfaces (`KtorChatApiClient`, `KtorGroupApiClient`). Implement the suspend methods corresponding to the backend endpoints completed in PR 5, handling HTTP requests, status codes, and JSON serialization/deserialization using the common DTOs.
*   **Stories Addressed:** E2.S1, E2.S3, E2.S4 frontend client, E6.S3, E6.S4 frontend client, E6.S1 frontend client, E5.S4 frontend client, E7.S6 (coroutines in client). Depends on PRs 1 (DTOs) and 5 (API contract).
*   **Key Files:**
  *   `app/src/main/kotlin/eu/torvian/chatbot/app/api/client/ChatApi.kt` (interface, potentially refined suspend)
  *   `app/src/main/kotlin/eu/torvian/chatbot/app/api/client/KtorChatApiClient.kt` (implement getSessions, createSession, getSession, assignSessionToGroup, isApiKeyConfiguredForModel)
  *   `app/src/main/kotlin/eu/torvian/chatbot/app/api/client/GroupApi.kt` (interface, potentially refined suspend)
  *   `app/src/main/kotlin/eu/torvian/chatbot/app/api/client/KtorGroupApiClient.kt` (implement getGroups, createGroup, deleteGroup)

---

**PR 8: App Frontend - UI State Core (Session/Chat Loading & Creation)**

*   **Assignee:** Maya
*   **Reviewer:** Eric, Mark
*   **Description:** Implement the core UI state holders (`ChatState`, `SessionListState`). These classes will manage the data for the UI by calling the Frontend API Clients within injected `CoroutineScope`s. Implement logic to load session summaries/groups on startup (E2.S3, E6.S4 frontend state), handle creating a new session (E2.S1 frontend state), and load the full session details when a session is selected (E2.S4 frontend state). Include basic loading and error state management.
*   **Stories Addressed:** E2.S1, E2.S3, E2.S4 frontend state, E6.S4 frontend state, E1.S3 (loading state), E1.S6 (error state). Uses E7.S6 (coroutines in state). Depends on PR 7 (API clients).
*   **Key Files:**
  *   `app/src/main/kotlin/eu/torvian/chatbot/app/ui/state/ChatState.kt` (implement loadSession, initial state for messages/current session/loading/error)
  *   `app/src/main/kotlin/eu/torvian/chatbot/app/ui/state/SessionListState.kt` (implement loadSessionsAndGroups, createNewSession, initial state for sessions/groups/loading/error)

---

**PR 9: App Frontend - Basic UI Layout & Session List Display**

*   **Assignee:** Maya
*   **Reviewer:** Mark
*   **Description:** Create the basic structure for the main `AppLayout` composable (E7.S2 UI). Implement a placeholder UI for the session list panel (`SessionListPanel` or directly in `AppLayout`) that observes `SessionListState` and displays the session summaries and groups loaded (E2.S3 UI, E6.S2 basic display). Include temporary UI elements to trigger creating a new session (E2.S1 UI) and loading a selected session (E2.S4 UI). Include basic loading and error indicators (E1.S3, E1.S6 UI).
*   **Stories Addressed:** E7.S2 UI, E2.S1 UI, E2.S3 UI, E2.S4 UI trigger, E6.S2 basic UI, E1.S3 UI, E1.S6 UI. Depends on PR 8 (UI State).
*   **Key Files:**
  *   `app/src/main/kotlin/eu/torvian/chatbot/app/ui/AppLayout.kt` (main layout structure, observes state, includes placeholders and temporary elements)
  *   Potentially a new file `app/src/main/kotlin/eu/torvian/chatbot/app/ui/SessionListPanel.kt` (or its content directly in AppLayout for S1)

---

**PR 10: Server Backend - Message Processing & Threading Persistence (E1.S4 Stubbed)**

*   **Assignee:** Alex
*   **Reviewer:** Eric
*   **Description:** Implement the core backend logic in `ChatServiceImpl.processNewMessage`. This involves saving the user message with its parent ID, updating the parent's children list, building the context (simple stub for S1), calling the **stubbed** LLM client, saving the assistant message linked to the user message, updating the user message's children list, and updating the session's leaf message ID. Ensure all these steps happen atomically within a `TransactionScope`. Implement recursive delete logic in `MessageDaoExposed` (part of E3.S4 but needed for clean data).
*   **Stories Addressed:** E1.S1 backend, E1.S4 Sprint 1 Scope backend, E2.S2 persistence (threading updates), E3.S4 backend (recursive delete impl), E7.S6. Depends on PRs 2 (TransactionScope, Schema), 3 (CredentialManager interface), 4 (SessionDao.updateSession, MessageDao insert/addChild/removeChild/getById), 5 (Stubbed LLM client interface, Model/Settings DAOs/Services).
*   **Key Files:**
  *   `server/src/main/kotlin/eu/torvian/chatbot/server/service/impl/ChatServiceImpl.kt` (full `processNewMessage` impl, potentially update `updateSessionDetails` impl for leaf ID)
  *   `server/src/main/kotlin/eu/torvian/chatbot/server/data/exposed/MessageDaoExposed.kt` (implement `deleteMessage` with recursive logic, `removeChildFromMessage`, helper `getMessageById`)

---

**PR 11: App Frontend - Message Sending & Display (E1.S1, E1.S2, E1.S3, E1.S4 Frontend)**

*   **Assignee:** Maya
*   **Reviewer:** Alex, Mark
*   **Description:** Implement the frontend logic for sending messages. In `ChatState.sendMessage`, call the Frontend API Client's `sendMessage` and update the state with the returned messages (user and stubbed assistant). Implement the optimistic UI update (E1.S2) for the user message and the loading indicator (E1.S3 UI state/logic). Update the UI composables (`AppLayout`, `ChatArea` placeholder, `SimpleMessageInput`) to observe the message list and loading state and allow sending messages, passing the determined `parentMessageId` to `ChatState`.
*   **Stories Addressed:** E1.S1 frontend, E1.S2, E1.S3, E1.S4 frontend data display, E1.S6 basic UI error display. Depends on PRs 8 (ChatState) and 10 (backend API functionality).
*   **Key Files:**
  *   `app/src/main/kotlin/eu/torvian/chatbot/app/ui/state/ChatState.kt` (implement `sendMessage`, loading state, error state, update messages list state)
  *   `app/src/main/kotlin/eu/torvian/chatbot/app/ui/AppLayout.kt` (wire SimpleInput to ChatState)
  *   `app/src/main/kotlin/eu/torvian/chatbot/app/ui/SimpleMessageInput.kt` (or equivalent in `AppLayout`, implement input field, send button logic, disable on loading)
  *   Potentially a new file `app/src/main/kotlin/eu/torvian/chatbot/app/ui/ChatArea.kt` (or its content in AppLayout for S1, displaying the flat message list)

---

**PR 12: Feature Integration - Credential Status Check (E5.S4)**

*   **Assignee:** Maya (Frontend Lead, coordinating with Alex)
*   **Reviewer:** Alex, Eric
*   **Description:** Integrate the Credential Status Check feature end-to-end. This involves implementing the `isApiKeyConfiguredForModel` call in `KtorChatApiClient` (using the endpoint completed in PR 5), adding state and logic in a UI state holder (e.g., `SettingsState` or `ModelSettingsState`) to call this API, and adding UI elements (e.g., in a Settings screen placeholder) to display the status indicator (E5.S4 UI).
*   **Stories Addressed:** E5.S4 frontend + integration with backend. Depends on PRs 5 (backend endpoint) and 7 (Frontend API client).
*   **Key Files:**
  *   `app/src/main/kotlin/eu/torvian/chatbot/app/api/client/KtorChatApiClient.kt` (implement `isApiKeyConfiguredForModel`)
  *   `app/src/main/kotlin/eu/torvian/chatbot/app/ui/state/ModelSettingsState.kt` (New state holder needed for settings screen, calls API)
  *   `app/src/main/kotlin/eu/torvian/chatbot/app/ui/SettingsScreen.kt` (New UI file needed, displays status)

---

