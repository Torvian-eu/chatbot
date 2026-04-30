# Epic Stories for MVP (Minimum Viable Product) V1.2
As the senior software architect, I've reviewed the project documentation for the General-Purpose Desktop Chatbot. Based on the requirements (functional, non-functional, PRD) and technical plans (backend, frontend, tech stack, flow), here are the suggested Epic stories for the initial V1.2 release of the project. These Epics aim to capture the major areas of work required to deliver the minimum viable product, with an emphasis on refined LLM configuration, robust API key handling, and enhanced chat session management.

---
**1. Epic: Core Chat Interaction & Experience**
*   **Description:** As a user, I want to seamlessly send messages to a configured LLM and receive its responses within an active chat session, and manage how conversations are displayed in a threaded structure, so that I can have interactive conversations with AI and easily follow specific discussion lines.
*   **Key Features / High-Level Acceptance Criteria:**
    *   User can type and send a message via a dedicated input area in the UI.
    *   The sent user message immediately appears in the chat history view, positioned as a reply to the currently active leaf message (or as the first message if the session is empty).
    *   The application successfully constructs and sends the user's message (along with necessary context derived from the current thread, and the selected LLM Model and Settings Profile) to the configured LLM Provider API endpoint.
    *   A visual indicator (e.g., loading animation) is displayed while awaiting the LLM's response.
    *   The LLM's response is received, processed, and displayed as an assistant message in the chat history, following the user's message, with `modelId` and `settingsId` attached to the assistant message for traceability.
    *   All messages (user and assistant) are displayed in a threaded structure, with the UI showing **only one branch of the conversation tree at a time**.
    *   User can specifically reply to any existing message (not just the last one in the current branch) to create a new branch in the conversation, and the UI automatically switches to display this new branch.
    *   User can select the specific LLM Model and associated Settings Profile to be used for generating the *next* assistant response within an active session (links to Epic 4).
    *   Basic error handling for API communication issues (e.g., network error, LLM API error) is implemented, providing feedback to the user.
*   **Business Value:** Delivers the fundamental purpose of the application – enabling users to chat with LLMs – and significantly enhances usability by providing clear thread management and navigation.
---
**2. Epic: Robust Chat Session Management**
*   **Description:** As a user, I want to create, view, switch between, and have my chat sessions automatically saved and persisted, including their active threading context and selected configurations, so that I can manage multiple distinct conversations over time and seamlessly resume my work across application uses.
*   **Key Features / High-Level Acceptance Criteria:**
    *   User can initiate and create a new chat session.
    *   Chat sessions can be named by the user or receive a default name.
    *   All chat sessions, along with their complete message history (including thread relationships), currently selected LLM Model/Settings, and the ID of the current leaf message for the active branch, are persistently stored in the local SQLite database.
    *   Upon application restart, previously created chat sessions are listed and accessible, retaining their names, group assignments, and last active thread/configuration.
    *   User can select any session from the list to view its full message history and make it the active chat, with the UI displaying the previously active thread branch.
    *   User can rename existing chat sessions.
    *   User can delete entire chat sessions (including all associated messages and threads) with confirmation.
    *   User can copy the entire content of the **currently visible thread branch** of a chat session (all messages, formatted as plain text) to the system clipboard.
*   **Business Value:** Ensures data integrity and convenience, allowing users to maintain, revisit, and organize valuable conversation history, enhancing productivity and user trust.
---
**3. Epic: Advanced Message Control**
*   **Description:** As a user, I want to have fine-grained control over individual messages within a chat session, including editing content, deleting messages, and copying text, so that I can refine conversations, correct mistakes, and easily reuse specific pieces of information, regardless of their position in a thread.
*   **Key Features / High-Level Acceptance Criteria:**
    *   User can edit the text content of any previously sent user message within a session, including those within threads.
    *   User can edit the text content of any previously received assistant message within a session, including those within threads.
    *   Edited message content is updated in the UI and persistently stored in the database.
    *   User can delete individual messages (both user and assistant) from a chat session, with a confirmation prompt. Deleting a message recursively deletes all its child messages within the thread.
    *   Deleted messages are removed from the UI and the database.
    *   User can copy the raw text content of any single message (user or assistant) to the system clipboard.
*   **Business Value:** Enhances user productivity and satisfaction by allowing direct manipulation and curation of chat content, making the application more flexible and forgiving.
---
**4. Epic: Comprehensive LLM Provider, Model & Settings Configuration**
*   **Description:** As a user, I want to configure and manage connections to various OpenAI-compatible LLM providers, define specific LLM models available from those providers, and create tailored settings profiles for different models, so that I can utilize my own API keys, choose preferred models, and fine-tune the AI's behavior to my specific needs.
*   **Key Features / High-Level Acceptance Criteria:**
    *   User can add new LLM **Provider** configurations, providing a name, description, base API URL, provider type (e.g., 'openai', 'openrouter', 'ollama'), and optionally the API key (handled by Epic 5).
    *   User can view a list of all configured LLM Providers, including their API key configuration status.
    *   User can update and delete LLM Provider configurations. Deletion requires that no LLM Models are linked to the provider.
    *   User can add new LLM **Model** configurations, linking them to an existing LLM Provider, and providing a model name, display name (optional), and active status.
    *   User can view a list of all configured LLM Models (showing associated Provider).
    *   User can update and delete LLM Model configurations. Deletion requires that no chat sessions are actively using the model.
    *   For each configured LLM Model, user can create, name, and manage multiple "settings profiles" (e.g., "Technical Assistant," "Creative Writer").
    *   Each settings profile allows editing of parameters such as the system message, temperature, max tokens, top\_p, frequency penalty, presence penalty, and other custom JSON parameters supported by the backend.
    *   User can view, update, and delete these settings profiles.
    *   User can select a default LLM Model and Settings Profile to be used for new chat sessions.
    *   Within an active chat session, user can change the LLM Model and/or Settings Profile to be applied for subsequent interactions in that session.
*   **Business Value:** Provides crucial flexibility and control, empowering users to work with their preferred LLM services and fine-tune interactions, a key requirement for power users and developers, while establishing a clear hierarchy for LLM access.
---
**5. Epic: Secure API Key Handling (Database-Backed Encryption)**
*   **Description:** As a user, I want to trust that my sensitive LLM API keys are stored and handled securely by the application using a robust, custom encryption method, so that I can use the application without risking unauthorized access to my LLM provider accounts and incurring unintended costs.
*   **Key Features / High-Level Acceptance Criteria:**
    *   User-provided API keys are *not* stored in plaintext within the SQLite database, application configuration files, or the operating system's credential manager.
    *   The application implements **custom envelope encryption** to encrypt API keys.
    *   Encrypted API keys, along with their associated wrapped Data Encryption Keys (DEKs) and Key Encryption Key (KEK) versions, are persistently stored in a dedicated `api_secrets` table within the SQLite database.
    *   The `LLMProvider` entity stores only a UUID reference ID (`apiKeyId`) to the encrypted secret in the `api_secrets` table, not the key itself.
    *   The backend retrieves the actual API key by its reference ID, unwraps the DEK, and decrypts the key *only when* an LLM API call is being made via its associated provider.
    *   When a user adds or updates an API key for an LLM Provider configuration, the key is securely encrypted and stored in the `api_secrets` table, and its reference is saved in the `LLMProvider` record.
    *   When an LLM Provider configuration (that has an associated API key) is deleted, the corresponding encrypted credential is also removed from the `api_secrets` table.
    *   API keys are never displayed in the UI after their initial input (e.g., they are masked or only a "configured" status is shown).
*   **Business Value:** Essential for building user trust and ensuring the security of sensitive user credentials, addressing a critical risk factor with a strong, self-contained encryption solution.
---
**6. Epic: Chat Session Organization & Navigation**
*   **Description:** As a user, I want to organize my chat sessions by grouping them, and easily manage these groups, so that I can effortlessly find, manage, and navigate related conversations, especially when dealing with a large number of sessions.
*   **Key Features / High-Level Acceptance Criteria:**
    *   User can create new, named chat session groups.
    *   User can view a list of all created session groups.
    *   User can rename existing session groups.
    *   User can delete session groups (sessions previously assigned to a deleted group become "ungrouped").
    *   User can assign a chat session to a specific group (or remove it from a group) via a menu or dialog.
    *   User can visually move a chat session into a group or the "ungrouped" section via drag-and-drop in the session list.
    *   The UI provides a mechanism to display sessions organized by their assigned group (e.g., in a tree view or sectioned list), with sessions also appearing in an "Ungrouped" section if not assigned.
    *   All grouping information for sessions and groups is persistently stored and reloaded across application launches.
*   **Business Value:** Improves application usability and scalability for users who accumulate many chat sessions, allowing for better organization and quicker access to relevant information.
---
**7. Epic: Application Core Framework & Windows 11 Integration**
*   **Description:** As the development team, we need a robust application foundation that launches correctly on Windows 11, initializes all backend components (like the embedded Ktor server and SQLite database), provides a basic, stable application window and structure, and adheres to a clear layered architecture with asynchronous operations, so that all other features can be built reliably, maintainably, and meet architectural goals.
*   **Key Features / High-Level Acceptance Criteria:**
    *   The application can be successfully installed and launched on a standard Windows 11 environment via a basic installer.
    *   A main application window is displayed upon launch, serving as the container for all UI elements.
    *   The embedded Ktor server (for internal API communication between frontend and backend logic) initializes correctly on application startup.
    *   The SQLite database connection is established, and the schema (tables, indices) is created or migrated as needed on startup. This schema must support:
        *   `ChatSession` (with `groupId`, `currentModelId`, `currentSettingsId`, `currentLeafMessageId` foreign keys, all nullable)
        *   `ChatMessage` (with `sessionId`, `parentMessageId` (self-referencing), `modelId`, `settingsId` foreign keys, plus `childrenMessageIds` stored as a serialized list)
        *   `ChatGroup`
        *   `LLMProvider` (with `apiKeyId` foreign key to `ApiSecrets` table)
        *   `LLMModel` (with `providerId` foreign key)
        *   `ModelSettings` (with `modelId` foreign key)
        *   `ApiSecrets` (for encrypted API keys and encryption metadata)
    *   Foreign key constraints with `ON DELETE CASCADE` (for session-messages, model-settings, provider-models) and `ON DELETE SET NULL` (for session-group, session-model, session-settings, session-leafMessage) are defined in the Exposed schema.
    *   Core application lifecycle events (e.g., startup, graceful shutdown with resource cleanup for Ktor, database connections) are handled properly.
    *   The project structure adheres to the specified technology stack (Kotlin, Compose for Desktop, Ktor, Exposed, SQLite) and a clear layered architecture.
    *   The architecture enforces a clear separation between UI (Compose), application/business logic (Kotlin services handling all threading, grouping, and config logic), data access layer (Exposed-based SQLite interaction), and external service communication (Ktor client for LLMs, custom Credential Manager for API keys). This design facilitates maintainability and potential future evolution.
    *   All I/O-bound and potentially blocking operations (database access, network calls, complex context building for threads, encryption/decryption) are performed asynchronously using Kotlin Coroutines to maintain UI responsiveness.
*   **Business Value:** Forms the essential technical backbone of the application, ensuring stability, platform compatibility, maintainability, and adherence to architectural principles. It's a prerequisite for developing all user-facing features and provides a robust foundation for future enhancements.
---
These Epics provide a solid framework for planning sprints and breaking down work into smaller, manageable user stories for the development team, reflecting the expanded scope of V1.2.