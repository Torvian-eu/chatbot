# AIChat Desktop App - User Stories (V1.2)

## Epic 1: Core Chat Interaction & Experience

### E1.S1 - Send Message via Main Input (Reply to Leaf)
*   **Description:** As a user, I want to send a message by typing into the main input area and clicking 'Send' or pressing Enter, so I can add my message to the end of the currently displayed conversation branch.
*   **Estimate:** M
*   **Acceptance Criteria:**
    *   Main input field and "Send" button are present and functional.
    *   Enter key or "Send" button triggers message sending via backend API (`processNewMessage`).
    *   Backend call includes `sessionId` and `parentMessageId` (ID of the current leaf message, or null if session is empty).
    *   Input field clears after sending.
    *   Send action is disabled if input is empty/whitespace.

### E1.S2 - Display User Message Added to Current Branch (Instantly)
*   **Description:** As a user, I want my message, sent via the main input field, to appear instantly as the next message in the currently displayed branch, so I see my contribution without delay.
*   **Estimate:** S
*   **Acceptance Criteria:**
    *   User message visualization appears immediately in the UI state, positioned after its parent.
    *   Message displays content and user role.
    *   (Cross-cutting E2.S2): User message, including `parentMessageId`, is persisted by the backend.

### E1.S3 - Show LLM Response Loading State
*   **Description:** As a user, I want to see a visual indicator while the application is waiting for the LLM's response after sending a message (either top-level or a reply), so that I know my request is being processed.
*   **Estimate:** S
*   **Acceptance Criteria:**
    *   Loading indicator is visible after user sends message and backend begins `processNewMessage`.
    *   Indicator disappears upon receiving and displaying the assistant message (E1.S4) or an error (E1.S6).

### E1.S4 - Get LLM Response and Display Assistant Message in Thread
*   **Description:** As a user, I want the application to send my message (with context) to the configured LLM via the backend, receive its response, and display it in the chat history, linked to the user message it's a reply to, so I can see the AI's reply.
*   **Estimate:** L
*   **Acceptance Criteria:**
    *   Backend gathers user message, relevant thread history, session's `currentModelId`, and `currentSettingsId`.
    *   Backend retrieves `LLMModel` and `LLMProvider` details using the session/model configuration (as selected via E4.S7).
    *   (Dependency E5.S2): Backend retrieves API key securely using `LLMProvider.apiKeyId`.
    *   Backend constructs LLM API request based on context, `ModelSettings` (from `currentSettingsId`), and `LLMProviderType` (from `LLMProvider`).
    *   Backend calls LLM Provider `baseUrl` using Ktor client and API key.
    *   Backend receives and parses LLM response.
    *   Extracted assistant message content, `modelId` (from session's `currentModelId`), and `settingsId` (from session's `currentSettingsId`) are saved to DB.
    *   Backend returns newly saved user and assistant messages to frontend.
    *   Frontend UI displays messages, correctly positioned in the thread.
    *   Assistant message displays content and assistant role.
    *   (Cross-cutting E2.S2): Assistant message, including `parentMessageId`, `modelId`, `settingsId`, is persisted; parent's `childrenMessageIds` is updated.
    *   (Cross-cutting E7.S6): All I/O and processing steps are asynchronous via Coroutines.

### E1.S5 - Display Messages in Threaded Structure
*   **Description:** As a user, I want messages within a session to be displayed showing only one branch of the conversation tree at a time, with a mechanism to switch between branches, so that I can easily follow specific lines of conversation without visual clutter from other threads.
*   **Estimate:** M
*   **Acceptance Criteria:**
    *   UI displays messages sequentially based on the active thread branch, identified by the session's `currentLeafMessageId`.
    *   Mechanism to indicate messages with untracked children is present.
    *   Mechanism to switch to displaying a different branch (updating `currentLeafMessageId`) is available.
    *   UI State manages thread tree structure from flat message list (E2.S4) and controls display based on `currentLeafMessageId`.
    *   Newly loaded sessions display the branch containing `currentLeafMessageId`.
    *   (Cross-cutting E7.S6): UI state updates and rendering are asynchronous.

### E1.S6 - Handle Basic LLM API Errors and Display to User
*   **Description:** As a user, I want to be clearly notified if the application encounters an error when trying to get a response from the LLM (for a top-level message or a reply), so that I understand why the assistant message didn't appear.
*   **Estimate:** M
*   **Acceptance Criteria:**
    *   Backend catches Ktor client failures during LLM call (E1.S4).
    *   User-friendly error message (potentially `ApiError`) is returned to frontend.
    *   Frontend UI displays the error (e.g., notification, error bar).
    *   Application does not crash.
    *   Loading indicator (E1.S3) is dismissed.

### E1.S7 - Reply to Specific Message & Create New Branch
*   **Description:** As a user, I want to specifically reply to any existing message (not just the last one in the current branch) to create a new branch in the conversation, so I can fork discussions or respond to earlier points.
*   **Estimate:** L
*   **Acceptance Criteria:**
    *   "Reply" action available on individual messages (excluding active branch leaf).
    *   Clicking "Reply" updates UI context to show which message is being replied to.
    *   Sending via main input in this context uses the selected message's ID as `parentMessageId`.
    *   New user message appears as a child of the target message in the chat history.
    *   UI automatically switches to display the new branch, setting session's `currentLeafMessageId`.
    *   Received LLM response appears as child of the new user message in the new branch.
    *   (Cross-cutting E2.S2): User and assistant messages (with parentId) persisted; parent's `childrenMessageIds` updated.
    *   (Cross-cutting E7.S6): Sending and display updates are asynchronous.

## Epic 2: Robust Chat Session Management

### E2.S1 - Create New Chat Session
*   **Description:** As a user, I want to click a button to start a brand new, empty conversation, so that I can begin a fresh topic with no previous messages or threads.
*   **Estimate:** S
*   **Acceptance Criteria:**
    *   "New Chat" button/action is present.
    *   Clicking triggers backend `createSession` service.
    *   Backend creates new `ChatSession` record (unique ID, timestamps, default name, null `currentModelId`, `currentSettingsId`, `currentLeafMessageId`).
    *   Backend returns new `ChatSession` object.
    *   Frontend adds session to list, selects it as active, clears chat area.
    *   (Cross-cutting E2.S2): New session persisted.

### E2.S2 - Data Persistence (Cross-cutting Requirement)
*   **Description:** As a user, I want all changes to my application data (sessions, messages, providers, models, settings, groups, including message thread relationships and session grouping) to be automatically saved to the database as I make them via UI actions, so that my data is retained between application uses and is safe from unexpected closures.
*   **Acceptance Criteria:** Relevant backend services use Exposed DAOs within transactions for Inserts/Updates/Deletes for: sessions (including leaf, model, settings, group IDs), messages (content, parentId, childrenIds), providers (including apiKeyId), models (name, providerId, active, displayName), settings, groups, and api secrets.
*   (Cross-cutting E7.S6): Database operations are asynchronous via Coroutines.

### E2.S3 - Load and Display Session List on Startup
*   **Description:** As a user, when I open the application, I want to see a list of all my previously saved chat sessions, so that I can choose which conversation to resume.
*   **Estimate:** M
*   **Accept Criteria:**
    *   Frontend calls backend `getAllSessions` on launch.
    *   Backend queries `ChatSession` records, joining `ChatGroup` for names.
    *   Backend returns list of `ChatSessionSummary` objects.
    *   Frontend displays list in a panel, showing session name and group.
    *   List is ordered logically (e.g., by `updatedAt`).
    *   (Cross-cutting E7.S6): Database query and mapping are asynchronous.

### E2.S4 - Select Session and Load Messages
*   **Description:** As a user, I want to click on an item in the session list to make it the active conversation and load all its messages, so that I can continue chatting in that specific context and see the full conversation history, including threads.
*   **Estimate:** M
*   **Acceptance Criteria:**
    *   Clicking session item triggers backend `getSessionDetails` with session ID.
    *   Backend queries specific `ChatSession` and all `ChatMessage` records for that session.
    *   (Dependency E7.S4): Database schema supports efficient loading and thread/config links.
    *   Backend returns `ChatSession` object with complete flat message list, config IDs, and `currentLeafMessageId`.
    *   Frontend updates state to show session as active, loads messages, organizes into threads, and displays the branch indicated by `currentLeafMessageId`.
    *   (Cross-cutting E7.S6): Database query and mapping are asynchronous.

### E2.S5 - Rename a Chat Session
*   **Description:** As a user, I want to be able to change the name of a chat session after it's created, so that I can give it a more descriptive title based on the conversation content.
*   **Estimate:** S
*   **Acceptance Criteria:**
    *   UI mechanism allows initiating rename.
    *   Editable input field shows current name.
    *   Saving triggers backend `updateSessionName` with session ID and new name.
    *   Backend updates `ChatSession` name in DB.
    *   Frontend updates session list name.
    *   (Cross-cutting E2.S2): Renaming is persisted.
    *   (Cross-cutting E7.S6): Database update is asynchronous.

### E2.S6 - Delete a Chat Session with Confirmation
*   **Description:** As a user, I want to remove a chat session and all its associated messages, so that I can clean up unwanted conversations from my list.
*   **Estimate:** M
*   **Acceptance Criteria:**
    *   UI mechanism allows initiating delete.
    *   Confirmation dialog warns about data loss.
    *   Confirming triggers backend `deleteSession` with session ID.
    *   Backend deletes `ChatSession`.
    *   (Dependency E7.S4): Associated `ChatMessage` records (including all threads) are deleted via `ON DELETE CASCADE`.
    *   Frontend removes session from list.
    *   If active, UI navigates to empty state or default session.
    *   (Cross-cutting E2.S2): Deletion is persisted.
    *   (Cross-cutting E7.S6): Database delete is asynchronous.

### E2.S7 - Copy Visible Thread Branch to Clipboard
*   **Description:** As a user, I want to copy the text content of the currently visible thread branch in the chat history to my system clipboard, so that I can easily paste or share that specific line of conversation.
*   **Estimate:** M
*   **Acceptance Criteria:**
    *   UI action available to trigger copy.
    *   Frontend retrieves messages for the currently visible branch from its state (no backend call needed).
    *   Frontend formats messages into a single text string (e.g., "User: ...\nAssistant: ...").
    *   Formatted text placed on system clipboard.
    *   Brief visual confirmation shown.
    *   (Dependency E1.S5): Relies on UI state correctly identifying the visible branch based on `currentLeafMessageId`.

## Epic 3: Advanced Message Control

### E3.S1 - Initiate Editing User Message
*   **Description:** As a user, I want to click on a user message to start editing its content, so I can correct mistakes or refine my prompts, regardless of whether it's a top-level message or part of a thread.
*   **Estimate:** M
*   **Acceptance Criteria:**
    *   "Edit" action accessible on user messages.
    *   Clicking "Edit" replaces message text with editable input field pre-filled with content.
    *   "Save" and "Cancel" actions appear.

### E3.S2 - Initiate Editing Assistant Message
*   **Description:** As a user, I want to click on an assistant message to start editing its content, so I can adjust AI responses to better suit my needs (e.g., fix formatting, shorten), regardless of whether it's a top-level message or part of a thread.
*   **Estimate:** M
*   **Acceptance Criteria:**
    *   "Edit" action accessible on assistant messages.
    *   Clicking "Edit" displays editable input state, same as E3.S1.

### E3.S3 - Save Edited Message Content
*   **Description:** As a user, when I finish editing a message, I want to save my changes, so that the updated message is permanently recorded, while preserving its position and relationships within its thread.
*   **Estimate:** M
*   **Acceptance Criteria:**
    *   Clicking "Save" triggers backend `updateMessageContent` with message ID and new content.
    *   Backend updates `ChatMessage.content` and `updatedAt` in DB.
    *   Backend returns updated `ChatMessage` object.
    *   Frontend updates message in display list, maintaining its thread position.
    *   UI item reverts from editing state to display mode.
    *   (Cross-cutting E2.S2): Updated message is persisted.
    *   (Cross-cutting E7.S6): Database update is asynchronous.

### E3.S4 - Delete a Specific Message
*   **Description:** As a user, I want to permanently remove an individual message (user or assistant) from a chat session, managing its impact on any replies (children), so I can prune the conversation history or remove irrelevant parts.
*   **Estimate:** M
*   **Acceptance Criteria:**
    *   "Delete" action accessible on messages.
    *   Confirmation dialog is shown.
    *   Confirming triggers backend `deleteMessage` with message ID.
    *   Backend deletes message and recursively deletes its children.
    *   Frontend removes message from display list, updates thread structure.
    *   (Cross-cutting E2.S2): Deletion (including children) is persisted.
    *   (Cross-cutting E7.S6): Database delete and updates are asynchronous.

### E3.S5 - Copy Single Message Content to Clipboard
*   **Description:** As a user, I want to copy the raw text content of a single message to my system clipboard, so I can easily use specific parts of a conversation elsewhere.
*   **Estimate:** S
*   **Acceptance Criteria:**
    *   "Copy" action accessible on messages.
    *   Clicking copies message content to system clipboard.
    *   Brief visual confirmation shown.

## Epic 4: Comprehensive LLM & Settings Configuration

### E4.S1 - Add New LLM Model Configuration
*   **Description:** As a user, I want to define a new specific LLM model configuration linked to an existing provider, including its name, provider, activity status, and display name.
*   **Estimate:** M
*   **Acceptance Criteria:**
    *   Settings section for providers/models is available (E4.S2 or E4.S6).
    *   Action to add a new model is present (e.g., button).
    *   A form is displayed allowing input for Model Name (text), Provider (dropdown/selector based on configured providers from E4.S6), Active status (checkbox), and Display Name (optional text).
    *   Saving the form triggers a call via the frontend client API to the backend `addModel` service.
    *   Backend service uses Exposed DAO to create a new `LLMModel` record (unique ID, name, `providerId`, `active`, `displayName`).
    *   Backend returns the newly created `LLMModel` object.
    *   Frontend updates the list of configured models (E4.S2) to include the new model.
    *   (Cross-cutting E2.S2): The new model record is persisted.
    *   (Cross-cutting E7.S6): Database insert is asynchronous.

### E4.S2 - View Configured LLM Models List
*   **Description:** As a user, I want to see a list of all the LLM model configurations I have defined, so I can manage my available models across providers.
*   **Estimate:** M
*   **Acceptance Criteria:**
    *   Dedicated settings screen accessible.
    *   Section displays a list of all configured `LLMModel` records.
    *   The list is populated by calling the backend `getAllModels` service.
    *   Each list item shows the model name, associated provider name (via join/lookup using `providerId`), and active status.
    *   Actions (e.g., buttons, context menus) are available to select, edit (E4.S3), or delete (E4.S4) models from the list.
    *   (Cross-cutting E7.S6): Backend query and mapping are asynchronous.

### E4.S3 - Update LLM Model Configuration Details
*   **Description:** As a user, I want to modify the name, provider association, active status, or display name of an existing LLM model configuration.
*   **Estimate:** M
*   **Acceptance Criteria:**
    *   Selecting a model from the list (E4.S2) displays its editable details in a form (Model Name, Provider selector, Active checkbox, Display Name).
    *   Saving changes in the form triggers a call via the frontend client API to the backend `updateModel` service with the model ID and changed fields.
    *   Backend service uses Exposed DAO to update the corresponding `LLMModel` record.
    *   Backend returns confirmation or the updated `LLMModel` object.
    *   Frontend updates the model list (E4.S2) and the details form to reflect saved changes.
    *   (Cross-cutting E2.S2): Updated model record is persisted.
    *   (Cross-cutting E7.S6): Database update is asynchronous.

### E4.S4 - Delete LLM Model Configuration
*   **Description:** As a user, I want to remove an LLM model configuration I no longer plan to use.
*   **Estimate:** M
*   **Acceptance Criteria:**
    *   UI action to delete a model configuration is available.
    *   Confirmation dialog is shown.
    *   Confirming deletion triggers a call via the frontend client API to the backend `deleteModel` service with the model ID.
    *   Backend service checks if any sessions (`ChatSession.currentModelId`) reference this model. If so, deletion fails (user notified).
    *   If no sessions linked, backend uses Exposed DAO to delete the `LLMModel` record.
    *   (Dependency E7.S4): All `ModelSettings` records associated with this model are automatically deleted due to `ON DELETE CASCADE`.
    *   Frontend UI removes the deleted model from the list (E4.S2).
    *   (Cross-cutting E2.S2): Deletion is persisted.
    *   (Cross-cutting E7.S6): Database delete is asynchronous.

### E4.S5 - Manage Model Settings Profiles List
*   **Description:** As a user, for a specific LLM model (from E4.S2), I want to create, name, and manage a list of different interaction settings profiles (like "Default", "Creative", "Strict"), so I can easily apply preferred behaviors for specific tasks using that model.
*   **Estimate:** M
*   **Acceptance Criteria:**
    *   UI section/list for settings profiles accessible for a selected `LLMModel`.
    *   List displays associated `ModelSettings` profiles (populated by `getSettingsByModelId`).
    *   Actions available to add (triggers E4.S6), rename, and delete (triggers backend `deleteSettings`).
    *   Adding new profile triggers backend `addModelSettings` linked to the `modelId`.
    *   (Cross-cutting E2.S2): Changes to settings profiles (add/rename/delete) are persisted.
    *   (Cross-cutting E7.S6): Backend DB operations are asynchronous.

### E4.S6 - Edit Model Settings Parameters
*   **Description:** As a user, within a selected settings profile (for a specific model), I want to customize parameters like the system message, temperature, max tokens, and other model-specific options, so I can fine-tune the LLM's behavior for that profile.
*   **Estimate:** L
*   **Acceptance Criteria:**
    *   Selecting settings profile (E4.S5) shows editable form (Name, System Message, Temperature, Max Tokens, Top P, Frequency Penalty, Presence Penalty, Custom Params JSON).
    *   Saving triggers backend `updateSettings` with settings ID and updated values.
    *   Input validation performed (UI/backend).
    *   Backend updates `ModelSettings` record.
    *   Frontend updates displayed settings details and list.
    *   (Cross-cutting E2.S2): Updated settings persisted.
    *   (Cross-cutting E7.S6): Database update is asynchronous.

### E4.S7 - Select Model & Settings Profile for Session
*   **Description:** As a user, within an active chat session, I want to easily choose which configured LLM Model and specific settings profile will be used for the next assistant response, allowing me to change the AI's approach mid-conversation.
*   **Estimate:** M
*   **Acceptance Criteria:**
    *   UI element (e.g., dropdown) shows currently selected model/settings for the session.
    *   Clicking element displays list of configured models (from E4.S2) and their settings profiles (from E4.S5).
    *   Selecting a different model/settings updates UI element.
    *   Frontend calls backend `updateSessionDetails` to update `currentModelId` and `currentSettingsId` for the `ChatSession`.
    *   (Dependency E1.S4): Backend LLM logic uses these IDs to retrieve model/settings before calling LLM.
    *   (Cross-cutting E2.S2): Updated session record is persisted.
    *   (Cross-cutting E7.S6): Database update is asynchronous.

### E4.S8 - Add New LLM Provider Configuration
*   **Description:** As a user, I want to define a new LLM provider connection by providing its name, base URL, type, description, and optionally the API key, so the application can connect to it.
*   **Estimate:** M
*   **Acceptance Criteria:**
    *   Settings section for providers/models is available (E4.S6 or other settings entry point).
    *   Action to add new provider is present.
    *   Form is displayed allowing input for Provider Name (text), Description (text), Base URL (text), Type (`LLMProviderType`), and API Key (text input).
    *   Saving the form triggers a call via the frontend client API to the backend `addProvider` service.
    *   Backend service uses the Exposed DAO to create a new `LLMProvider` record (unique ID, name, description, base URL, type).
    *   (Dependency E5.S1): If an API Key is provided in the form, backend securely stores it and saves the reference ID (`apiKeyId`) in the `LLMProvider` record.
    *   Backend returns the newly created `LLMProvider` object (excluding the raw API key).
    *   Frontend updates the list of configured providers (E4.S9) to include the new provider.
    *   (Cross-cutting E2.S2): New provider and credential reference (if any) persisted.
    *   (Cross-cutting E7.S6): Database insert and key storage are asynchronous.

### E4.S9 - View Configured LLM Providers List
*   **Description:** As a user, I want to see a list of all the LLM provider connections I have configured in a settings section, so I can manage my available endpoints.
*   **Estimate:** M
*   **Acceptance Criteria:**
    *   Dedicated settings screen accessible.
    *   Section displays a list of all configured `LLMProvider` records.
    *   The list is populated by calling the backend `getAllProviders` service.
    *   Each list item shows the provider name and type.
    *   Each list item indicates the API key configuration status (E5.S4).
    *   Actions (e.g., buttons, context menus) are available to select, view details/edit (E4.S10), or delete (E4.S11) providers.
    *   (Cross-cutting E7.S6): Backend query and mapping are asynchronous.

### E4.S10 - Update LLM Provider Configuration Details
*   **Description:** As a user, I want to modify the name, description, base URL, or type of an existing LLM provider configuration.
*   **Estimate:** M
*   **Acceptance Criteria:**
    *   Selecting provider from list (E4.S9) shows editable form (Name, Description, Base URL, Type). API Key is managed separately (E4.S12).
    *   Saving changes in the form triggers a call via the frontend client API to the backend `updateProvider` service with provider ID and changes.
    *   Backend updates `LLMProvider` record.
    *   Frontend updates provider list and details form.
    *   (Cross-cutting E2.S2): Updated provider persisted.
    *   (Cross-cutting E7.S6): Database update is asynchronous.

### E4.S11 - Delete LLM Provider Configuration
*   **Description:** As a user, I want to remove an LLM provider configuration I no longer plan to use.
*   **Estimate:** M
*   **Acceptance Criteria:**
    *   UI action to delete provider is available.
    *   Confirmation dialog is shown.
    *   Confirming triggers backend `deleteProvider` service with provider ID.
    *   Backend service checks if any `LLMModel` records (`LLMModel.providerId`) reference this provider. If so, deletion fails (user notified).
    *   If no models linked, backend retrieves provider `apiKeyId`.
    *   (Dependency E5.S3): If `apiKeyId` exists, backend calls `CredentialManager` to delete credential.
    *   Backend deletes `LLMProvider` record.
    *   Frontend removes provider from list (E4.S9).
    *   (Cross-cutting E2.S2): Deletion (provider record, credential removal) is persisted.
    *   (Cross-cutting E7.S6): Database delete and key deletion are asynchronous.

### E4.S12 - Update LLM Provider Credential
*   **Description:** As a user, I want to update or remove the API key credential associated with an LLM provider, so I can manage access keys securely.
*   **Estimate:** S
*   **Acceptance Criteria:**
    *   UI action/input available for managing API key credential for a provider (shows status from E5.S4, not the key).
    *   Entering/removing key and saving triggers backend `updateProviderCredential` with provider ID and new key string (or null).
    *   Backend uses E5.S1 to store new key (E5.S3 to delete old if needed), updates `LLMProvider.apiKeyId`.
    *   Frontend updates API key status display (E5.S4).
    *   (Cross-cutting E2.S2): Updated `apiKeyId` and secure storage changes are persisted.
    *   (Cross-cutting E7.S6): Key storage/deletion are asynchronous.

## Epic 5: Secure API Key Handling

### E5.S1 - Implement Secure API Key Storage (Database-Backed Encryption)
*   **Description:** As a developer, I need to implement the core logic for securely storing and managing sensitive API keys by utilizing custom envelope encryption and storing the resulting encrypted data in a dedicated database table (`api_secrets`), so that user keys are protected locally without relying on OS-specific credential managers.
*   **Estimate:** L
*   **Acceptance Criteria:**
    *   `CredentialManager` interface implemented by `DbEncryptedCredentialManager`.
    *   Implementation uses custom envelope encryption (`EncryptionService`, `CryptoProvider`, `EncryptionConfig`).
    *   Uses `ApiSecretDao` for persistence to `api_secrets` table (E7.S4).
    *   `api_secrets` table stores encrypted key, wrapped DEK, KEK version, identified by UUID `alias`.
    *   `storeCredential` encrypts key, generates alias, saves data via DAO, returns alias.
    *   `getCredential` retrieves data by alias, unwraps DEK, decrypts key, returns raw key string.
    *   `deleteCredential` deletes record by alias via DAO.
    *   `LLMProvider` schema (E7.S4) includes nullable `apiKeyId` (String, stores alias), not raw key. `LLMModel` schema does not store `apiKeyId`.
    *   Backend provider management (E4.S8, E4.S12) uses `CredentialManager` and stores alias in `LLMProvider`.
    *   Raw keys only held in memory when actively used.
    *   (Cross-cutting E2.S2): DB operations on `api_secrets` are persisted.
    *   (Cross-cutting E7.S6): DB interactions and crypto operations are asynchronous.

### E5.S2 - Securely Retrieve API Key for LLM API Calls
*   **Description:** As the application backend, I need to use the secure storage mechanism (E5.S1) to retrieve the actual API key for the selected LLM's Provider just before making an external LLM API call, ensuring the key is not sitting in memory unnecessarily.
*   **Estimate:** S
*   **Acceptance Criteria:**
    *   Backend LLM logic (E1.S4 orchestration) identifies session's `currentModelId`.
    *   Retrieves `LLMModel` and its `providerId`, then the `LLMProvider` and its `apiKeyId`.
    *   If `apiKeyId` exists, uses `CredentialManager.getCredential` to retrieve key.
    *   Retrieved key passed to `LLMApiClient` for request authentication.
    *   Retrieved key is not logged or stored in DB.
    *   (Cross-cutting E7.S6): Key retrieval is asynchronous.

### E5.S3 - Securely Delete API Key on Provider Removal
*   **Description:** As the application backend, when a user deletes an LLM provider configuration, I need to ensure the associated API key is also removed from the secure storage, so that sensitive data is cleaned up.
*   **Estimate:** S
*   **Acceptance Criteria:**
    *   Backend provider deletion logic (E4.S11) identifies `LLMProvider.apiKeyId` for the deleted provider.
    *   If `apiKeyId` exists, calls `CredentialManager.deleteCredential`.
    *   (Cross-cutting E7.S6): Key deletion is asynchronous.

### E5.S4 - Indicate API Key Configuration Status in UI
*   **Description:** As a user, I want to see if an API key has been configured for a specific provider in the settings UI without the key being displayed, so I know if that provider (and its models) is ready for use.
*   **Estimate:** S
*   **Acceptance Criteria:**
    *   Backend service (`LLMProviderService`) provides a method (`isApiKeyConfiguredForProvider`) checking for non-null `apiKeyId` on a provider.
    *   Provider management UI (E4.S9, E4.S10/E4.S12) displays status (e.g., "Key Configured") based on this check.
    *   Raw API key string is never displayed after initial input.
    *   (Cross-cutting E7.S6): Backend check is asynchronous.

## Epic 6: Chat Session Organization & Navigation

### E6.S1 - Assign Session to a Group via Menu/Dialog
*   **Description:** As a user, I want to assign a chat session to a defined group, or remove it from a group, by selecting from a list of available groups using a menu or dialog in the session list UI, so I can categorize my conversations.
*   **Estimate:** M
*   **Acceptance Criteria:**
    *   UI mechanism available in session list (E2.S3) for sessions.
    *   Mechanism displays list of existing groups (E6.S4) and "Ungrouped".
    *   Selecting a group (or "Ungrouped") triggers backend `updateSessionGroup` with session ID and selected Group ID (or null).
    *   Backend updates `ChatSession.groupId` in DB.
    *   Frontend updates session list display (E6.S2) for that session.
    *   (Cross-cutting E2.S2): Change is persisted.
    *   (Cross-cutting E7.S6): Database update is asynchronous.

### E6.S2 - View Sessions Organized by Group
*   **Description:** As a user, I want the main list of chat sessions to be organized visually by the group they are assigned to, so I can easily browse my categorized conversations within a group hierarchy.
*   **Estimate:** M
*   **Acceptance Criteria:**
    *   Session list UI (E2.S3) retrieves sessions including `groupId` and `groupName`.
    *   UI also retrieves defined groups list (E6.S4).
    *   UI displays sessions grouped visually under their group name (or "Ungrouped").
    *   Sessions ordered within groups by `updatedAt`.
    *   Grouping applied on load (E2.S3) and updates automatically (E6.S1, E6.S5, E6.S6, E6.S7).
    *   (Cross-cutting E7.S6): Backend queries/mapping for sessions/groups are asynchronous.

### E6.S3 - Add New Session Group
*   **Description:** As a user, I want to create a new, named group to categorize my chat sessions, so I can define my organization structure.
*   **Estimate:** S
*   **Acceptance Criteria:**
    *   UI section/dialog for group management available.
    *   Action to add new group ("Add Group") present.
    *   Input field/form to enter new group name appears.
    *   Saving triggers backend `createGroup` with name.
    *   Backend inserts new `ChatGroup` record.
    *   Backend returns new `ChatGroup`.
    *   Frontend updates list of defined groups (E6.S4) and session list UI (E6.S2) to show new group.
    *   (Cross-cutting E2.S2): New group persisted.
    *   (Cross-cutting E7.S6): Database insert is asynchronous.

### E6.S4 - View List of Session Groups
*   **Description:** As a user, I want to see a list of all the session groups I have created, so I can understand and manage my organization structure. This list should be visible in the session list UI to support organizing.
*   **Estimate:** S
*   **Acceptance Criteria:**
    *   Dedicated UI section/panel for groups accessible.
    *   List displays all `ChatGroup` records.
    *   List populated by backend `getAllGroups` on loading group UI/session list area.
    *   Each item shows group name.
    *   Actions available to rename (E6.S5) or delete (E6.S6).
    *   (Cross-cutting E7.S6): Backend query/mapping are asynchronous.

### E6.S5 - Rename a Session Group
*   **Description:** As a user, I want to change the name of an existing session group, so I can correct typos or refine its title.
*   **Estimate:** S
*   **Acceptance Criteria:**
    *   UI mechanism allows initiating rename for a group.
    *   Editable input field shows current name.
    *   Saving triggers backend `renameGroup` with Group ID and new name.
    *   Backend updates `ChatGroup.name` in DB.
    *   Backend returns updated `ChatGroup`.
    *   Frontend updates group list (E6.S4) and session list (E6.S2).
    *   (Cross-cutting E2.S2): Renaming is persisted.
    *   (Cross-cutting E7.S6): Database update is asynchronous.

### E6.S6 - Delete a Session Group
*   **Description:** As a user, I want to remove a session group that I no longer need. Sessions in the group become ungrouped.
*   **Estimate:** M
*   **Accept Criteria:**
    *   UI action to delete group available.
    *   Confirmation dialog warns sessions will become ungrouped.
    *   Confirming triggers backend `deleteGroup` service with Group ID.
    *   Backend deletes `ChatGroup` record.
    *   Backend updates all `ChatSession` records referencing the deleted group, setting `groupId` to null (in same transaction).
    *   Frontend removes group from list (E6.S4).
    *   Frontend updates session list (E6.S2) to show affected sessions as "Ungrouped".
    *   (Cross-cutting E2.S2): Deletion (group, session updates) is persisted.
    *   (Cross-cutting E7.S6): Database delete/updates are asynchronous.

### E6.S7 - Drag and Drop Session to Group
*   **Description:** As a user, I want to visually move a chat session into a specific group, or into the ungrouped section, by dragging its item in the session list and dropping it onto the target group or section header, so I can quickly organize my sessions.
*   **Estimate:** M
*   **Acceptance Criteria:**
    *   Session items in list (E2.S3) are draggable.
    *   Visual feedback during drag.
    *   Group items (E6.S4) and "Ungrouped" section are valid drop targets.
    *   Visual feedback when hovering over target.
    *   Dropping triggers backend `updateSessionGroup` with dragged session ID and target Group ID (or null).
    *   Frontend updates session list display (E6.S2).
    *   (Cross-cutting E2.S2): Change is persisted.
    *   (Cross-cutting E7.S6): Backend database update is asynchronous.

## Epic 7: Application Core Framework & Windows 11 Integration

### E7.S1 - Create Basic Windows 11 Installer
*   **Description:** As a potential user, I want to easily install the application on my Windows 11 computer, so I can start using it without complex manual setup.
*   **Estimate:** M
*   **Acceptance Criteria:**
    *   Gradle build includes task for Windows installer (`.exe`).
    *   Installer successfully installs app files (app, server, JVM).
    *   Installed app launches via shortcut/Start Menu.

### E7.S2 - Initialize Main Application Window
*   **Description:** As a user, when I launch the application, a primary application window should appear, providing the container for all UI elements.
*   **Estimate:** S
*   **Acceptance Criteria:**
    *   App entry point uses Compose `application` and `Window`.
    *   Window has a title.
    *   Basic layout structure is present for UI components.
    *   UI state holders (`ChatState` etc.) and dependencies (Frontend API Clients) are initialized (preferably via DI).

### E7.S3 - Initialize Embedded Ktor Server
*   **Description:** As a developer, I need the Ktor HTTP server from the server module to start automatically within the application process on launch, configured to handle API endpoints and use the backend services, so the frontend can communicate with the backend logic via HTTP requests.
*   **Estimate:** L
*   **Acceptance Criteria:**
    *   App startup sequence configures and starts Ktor server (from `server` module).
    *   Server binds to `localhost` on a configured/dynamic port.
    *   Ktor features (Content Negotiation, StatusPages) are configured.
    *   API routing (`/api/v1/...`) defined, mapping requests to backend services (`ChatService`, etc.).
    *   Backend services injected into routing handlers.
    *   Server starts without errors.
    *   (Cross-cutting E7.S6): Server setup/handlers manage asynchronous operations with Coroutines.

### E7.S4 - Initialize SQLite Database & Exposed Schema
*   **Description:** As a developer, I need the application to connect to the SQLite database file on startup and ensure all necessary tables, indexes, and foreign key constraints are created or updated using Exposed, so that data persistence works reliably, including support for message threading, session grouping, and provider/model configurations.
*   **Estimate:** L
*   **Acceptance Criteria:**
    *   Exposed DB connection logic implemented.
    *   DB file (`chat.db`) created if missing.
    *   Exposed Table objects (`ChatSessions`, `ChatMessages`, `ChatGroups`, `LLMProviders`, `LLMModels`, `ModelSettings`, `ApiSecrets`) defined matching common models.
    *   Schema includes columns for:
        *   `ChatMessage`: `sessionId`, `parentMessageId` (nullable), `childrenMessageIds` (serialized).
        *   `ChatSession`: `groupId` (nullable), `currentModelId` (nullable), `currentSettingsId` (nullable), `currentLeafMessageId` (nullable).
        *   `LLMModel`: `providerId`, `active`, `displayName` (nullable).
        *   `LLMProvider`: `name`, `description`, `baseUrl`, `type` (String), `apiKeyId` (String, nullable).
        *   `ApiSecrets`: `alias` (PK), `encryptedCredential`, `wrappedDek`, `kekVersion`.
    *   Schema creation/migration runs on startup.
    *   FK constraints defined in Exposed for relationships (Session-Messages, Session-Group, Session-Model, Session-Settings, Session-LeafMessage, Model-Provider, Settings-Model, Provider-ApiSecret). `ON DELETE CASCADE` where appropriate (Session-Messages, Model-Provider, Settings-Model). `ON DELETE SET NULL` where appropriate (Session-Group, Session-Model, Session-Settings, Session-LeafMessage). ApiSecret handled via CredentialManager logic.
    *   Indices defined for queried columns (`ChatMessage.sessionId`, `ChatMessage.parentMessageId`, `ModelSettings.modelId`, `ChatSession.groupId`, `LLMModel.providerId`).
    *   DB connection established.
    *   (Cross-cutting E7.S6): DB connection/schema ops are asynchronous if blocking.

### E7.S5 - Implement Layered Architecture (Cross-cutting Requirement)
*   **Description:** As a developer, I will structure the codebase with clear boundaries between the UI (`app.ui`), Frontend API Client (`app.api.client`), Embedded Server (`server.api.server`), Application Logic (`server.service`), Data Access (`server.data`), and External Services (`server.external`) across the defined Gradle modules (`common`, `app`, `server`). I will ensure dependencies flow correctly and interfaces are used to decouple components, allowing for potential future extraction or modularity. This architecture now explicitly includes layers responsible for handling LLM provider and model configuration, message threading logic, and session grouping data persistence.
*   **Acceptance Criteria:** Code organized by layers/packages. Business logic (including provider/model/settings management, threading, groups) in `server.service`. Data access (persistence of all entities) in `server.data`. External comms (LLM, Credential Manager) abstracted. Dependencies flow unidirectionally. Interfaces are used. DI (Koin) wires components. Code reviews enforce structure.

### E7.S6 - Use Coroutines for Asynchronous Operations (Cross-cutting Requirement)
*   **Description:** As a developer, I will ensure that all I/O-bound and potentially blocking operations (database access via Exposed, network calls via Ktor, including operations related to provider/model persistence, threading persistence, group management, and context building) are performed asynchronously using Kotlin Coroutines, so that the application UI remains responsive and the application feels fluid.
*   **Acceptance Criteria:** Exposed DB ops (including updates for all entities) in `Dispatchers.IO`. Ktor client calls (frontend->server, backend->LLM) as suspend functions. Long tasks (LLM response, context building) don't block UI/Ktor threads. UI state updates handle async results safely. Coroutine scopes/structured concurrency used correctly. Code reviews enforce Coroutines use.

### E7.S7 - Graceful Application Shutdown
*   **Description:** As the application, I need to shut down cleanly when the user closes the window or the operating system requests termination, ensuring all resources are released and data integrity is maintained.
*   **Estimate:** M
*   **Acceptance Criteria:**
    *   Closing window/OS signal triggers shutdown in `app`.
    *   Sequence stops Ktor server (`server` module).
    *   SQLite DB connection closed.
    *   Other resources (Ktor HttpClient) closed.
    *   Background coroutine scopes cancelled.
    *   Process exits without errors.