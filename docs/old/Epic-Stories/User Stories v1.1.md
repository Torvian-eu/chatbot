### AIChat Desktop App - User Stories (V1.1)

#### Epic 1: Core Chat Interaction & Experience

*   **E1.S1 - Send Message via Main Input (Reply to Leaf)**
  *   **Description:** As a user, I want to send a message by typing into the main input area and clicking 'Send' or pressing Enter, so I can add my message to the end of the currently displayed conversation branch.
  *   **Estimate:** M
  *   **Acceptance Criteria:**
    *   A multi-line text input field is present in the main chat view at the bottom of the currently displayed message branch.
    *   A "Send" button is visible and actionable next to the input field.
    *   Pressing the Enter key while in the input field (or clicking the "Send" button) triggers the message sending process via a call to the backend service.
    *   The UI state identifies the ID of the **leaf message of the currently displayed thread branch** and includes it as the `parentMessageId` when calling the backend service. If the session is empty, no `parentMessageId` is included (creating the first message of the session).
    *   The text content of the input field is cleared immediately after sending is triggered.
    *   The "Send" action (button/Enter key) is disabled if the input field is empty or contains only whitespace.

*   **E1.S2 - Display User Message Added to Current Branch (Instantly)**
  *   **Description:** As a user, I want my message, sent via the main input field, to appear instantly as the next message in the currently displayed branch, so I see my contribution without delay.
  *   **Estimate:** S
  *   **Acceptance Criteria:**
    *   A visual representation of the user's message (content, role, temporary ID/timestamp) is added to the chat history display in the UI immediately after the send action (from E1.S1) is triggered via the UI state update.
    *   The visual representation appears immediately in the chat history display, positioned correctly as the next message *after* the message identified as its parent by E1.S1.
    *   The message is clearly styled or positioned to indicate it is from the "user".
    *   The message bubble/item includes the message text.
    *   **(Cross-cutting Requirement E2.S2):** The user message content, **including its `parentMessageId` (if applicable)**, is persisted to the database by the backend service as part of the `sendMessage` call handling.

*   **E1.S3 - Show LLM Response Loading State (Estimate: S)**
  *   **Description:** As a user, I want to see a visual indicator while the application is waiting for the LLM's response after sending a message (either top-level or a reply), so that I know my request is being processed.
  *   **Acceptance Criteria:**
    *   A loading indicator (e.g., progress bar, spinner) becomes visible in or near the chat area after a user message is sent and the call to the backend `processNewMessage` service begins. This indicator could potentially be associated with the specific message/thread being replied to, or a general indicator.
    *   The loading indicator disappears once an assistant message is received and displayed (E1.S4) or an error related to the LLM call is displayed (E1.S6).

*   **E1.S4 - Get LLM Response and Display Assistant Message in Thread (Estimate: L)**
  *   **Description:** As a user, I want the application to send my message (with context) to the configured LLM via the backend, receive its response, and display it in the chat history, **linked to the user message it's a reply to**, so I can see the AI's reply.
  *   **Acceptance Criteria:**
    *   Upon receiving the `processNewMessage` request from the frontend, the backend service collects necessary context: the user message content, **relevant previous messages for the session history, considering the thread structure (including the parent message and its branch)**, and the selected model/settings IDs from the session record.
    *   **(Dependency):** The backend service uses the mechanism from **E5.S2** to retrieve the API key securely using the stored reference ID.
    *   The backend service constructs the appropriate request payload for the selected LLM's OpenAI-compatible API based on gathered context and settings.
    *   The backend service makes an HTTP request to the selected LLM's `baseUrl`/`chat/completions` endpoint using the Ktor client, including the secure API key for authentication.
    *   The backend service successfully receives and parses the LLM's chat completion response payload.
    *   The extracted assistant message content, along with the model and settings IDs used, is saved to the database by the backend service.
    *   The newly saved user and assistant messages are returned by the backend service to the frontend client API.
    *   The frontend client API updates the UI state (E1.S2 for user, this story for assistant) to display the new messages in the chat history, **correctly positioned within the thread structure**.
    *   The assistant message is clearly styled or positioned to indicate it is from the "assistant".
    *   **(Cross-cutting Requirement E2.S2):** The assistant message content, model, settings IDs, **and `parentMessageId`** are persisted to the database by the backend service. The parent message's `childrenMessageIds` list is also updated in the database.
    *   **(Cross-cutting Requirement E7.S6):** All I/O operations (DB read, Ktor call, DB write, including parent/child updates) within this story are performed asynchronously using Coroutines.

*   **E1.S5 - Display Messages in Threaded Structure**
  *   **Description:** As a user, I want messages within a session to be displayed showing **only one branch of the conversation tree at a time**, with a mechanism to switch between branches, so that I can easily follow specific lines of conversation without visual clutter from other threads.
  *   **Estimate:** M
  *   **Acceptance Criteria:**
    *   The frontend UI component for displaying messages in the chat area is designed to visualize message threads by showing **only a single sequence of related messages at a time**.
    *   Messages with `parentMessageId = null` are the root messages, but only one of these is displayed at a time (specifically, the one belonging to the currently selected branch).
    *   When a specific branch is selected or active, only the messages belonging to that path from a root message down through its direct replies are displayed.
    *   A visual indicator or mechanism is present in the UI to show when a message currently displayed has children that are *not* part of the currently viewed branch.
    *   A UI mechanism (e.g., clicking the indicator on a message, a dedicated navigation control) is available to allow the user to switch the view to display a different branch starting from the selected message.
    *   The UI State correctly processes the flat list of `ChatMessage` objects received from the backend (E2.S4), reconstructs the full thread tree structure internally, and manages the state of which branch is currently selected for display.
    *   When a new session is loaded (E2.S4), the UI displays the branch containing the currently active leaf message. (this needs to be tracked in the `ChatSession` record)
    *   **(Cross-cutting Requirement E7.S6):** The UI state update and rendering logic handles the branch display efficiently and asynchronously.

*   **E1.S6 - Handle Basic LLM API Errors and Display to User (Estimate: M)**
  *   **Description:** As a user, I want to be clearly notified if the application encounters an error when trying to get a response from the LLM (for a top-level message or a reply), so that I understand why the assistant message didn't appear.
  *   **Acceptance Criteria:**
    *   If the backend's Ktor client call (in E1.S4) fails (e.g., network error, non-success HTTP status code from API), the backend service catches the error.
    *   A user-friendly error message string is returned to the frontend client API.
    *   The frontend client API communicates this error to the UI state.
    *   The UI displays this error message to the user (e.g., temporary notification, error bar, or modal dialog).
    *   The error message can be associated with the specific thread if the UI design supports it, or a general error.
    *   The application does not crash due to these API errors.
    *   The loading indicator (E1.S3) is dismissed when the error is displayed.

*   **E1.S7 - Reply to Specific Message & Create New Branch**
  *   **Description:** As a user, I want to specifically reply to any existing message (not just the last one in the current branch) to create a new branch in the conversation, so I can fork discussions or respond to earlier points.
  *   **Estimate:** L
  *   **Acceptance Criteria:**
    *   A UI mechanism (e.g., a "Reply" button or action on individual messages) is available on *any* message in the displayed history (except potentially the very last one, which is implicitly replied to by E1.S1) to initiate a specific reply action.
    *   Clicking this "Reply" mechanism prepares the UI (e.g., updates the input area context) to indicate that the *next* message sent should be a reply to the message on which the action was initiated. The UI clearly shows which message is being replied to.
    *   Sending a message via the main input field (from E1.S1) while the UI is in this "reply to specific message" context triggers the `sendMessage` process (via the backend service) with the ID of the message being replied to included as the `parentMessageId`.
    *   The newly sent user message appears in the chat history display as a child of the message it is replying to.
    *   The UI automatically switches the displayed branch to show the newly created branch, with the newly sent user message as the leaf of the displayed branch.
    *   When the LLM response is received (via E1.S4), the assistant message appears as a child of the user message within this newly displayed branch.
    *   **(Cross-cutting Requirement E2.S2):** The user message, its `parentMessageId`, and the assistant message (including its `parentMessageId`) are persisted to the database, and the parent message's `childrenMessageIds` list is updated.
    *   **(Cross-cutting Requirement E7.S6):** All sending and display updates involving this branching action are performed asynchronously using Coroutines.

#### Epic 2: Robust Chat Session Management

*   **E2.S1 - Create New Chat Session (Estimate: S)**
  *   **Description:** As a user, I want to click a button to start a brand new, empty conversation, so that I can begin a fresh topic with no previous messages or threads.
  *   **Acceptance Criteria:**
    *   A button or action labelled "New Chat" or similar is present in the session list UI.
    *   Clicking this action triggers a call to the backend `createSession` service.
    *   The backend service creates a new `ChatSession` record in the database, assigning a unique ID and setting timestamps. A default name is assigned.
    *   The backend service returns the newly created `ChatSession` object (with an empty message list).
    *   The frontend client API receives the new session object and updates the UI state to add it to the session list and select it as the active session.
    *   The main chat area clears to show an empty session view.
    *   **(Cross-cutting Requirement E2.S2):** The new session record is persisted in the database by the backend service.

*   **E2.S2 - Data Persistence (Cross-cutting Requirement)**
  *   **Description:** As a user, I want all changes to my application data (sessions, messages, models, settings, **including message thread relationships**) to be automatically saved to the database as I make them via UI actions, so that my data is retained between application uses and is safe from unexpected closures.
  *   **Acceptance Criteria:** This is not a single user story but a fundamental behavior required by several other stories. Implementation involves ensuring the relevant backend service methods use the Exposed DAOs within database transactions to perform the necessary Insert/Update/Delete operations. Persistence must be ensured for:
    *   Creation of new sessions (E2.S1).
    *   Saving user messages (E1.S2).
    *   Saving assistant messages (E1.S4).
    *   **Updating parent message's `childrenMessageIds` list when a child is added** (E1.S4).
    *   Renaming sessions (E2.S5).
    *   Deleting sessions (E2.S6).
    *   Saving message edits (E3.S3).
    *   Deleting messages (E3.S4).
    *   Adding/Updating/Deleting LLM models (E4.S1, E4.S3, E4.S4).
    *   Adding/Updating/Deleting Model Settings (E4.S5, E4.S6).
    *   Saving API key references (E5.S1).
    *   Assigning sessions to groups (E6.S1).
  *   **(Cross-cutting Requirement E7.S6):** Database operations for persistence should be performed asynchronously using Coroutines.

*   **E2.S3 - Load and Display Session List on Startup (Estimate: M)**
  *   **Description:** As a user, when I open the application, I want to see a list of all my previously saved chat sessions, so that I can choose which conversation to resume.
  *   **Accept Criteria:**
    *   Upon application launch, the frontend client API calls the backend service to retrieve all session summaries.
    *   The backend service uses the Exposed DAO to query all `ChatSession` records, potentially joining for group names if a separate table is used for groups in V1.1.
    *   The backend service returns a list of `ChatSessionSummary` objects.
    *   The frontend UI state receives this list and displays it in a dedicated UI panel or area (the session list).
    *   Each list item shows the session's name and potentially the group it belongs to (E6.S2).
    *   The list is ordered logically (e.g., by `updatedAt` descending).
    *   **(Cross-cutting Requirement E7.S6):** The database query and data mapping are performed asynchronously using Coroutines.

*   **E2.S4 - Select Session and Load Messages (Estimate: M)**
  *   **Description:** As a user, I want to click on an item in the session list to make it the active conversation and load **all** its messages, so that I can continue chatting in that specific context and see the full conversation history, including threads.
  *   **Acceptance Criteria:**
    *   Clicking a session item in the list triggers a call via the frontend client API to the backend `getSessionDetails` service with the session ID.
    *   The backend service uses the Exposed DAO to query the specific `ChatSession` record and **all** associated `ChatMessage` records for that session ID.
    *   **(Dependency):** Database schema (E7.S4) includes necessary foreign key constraints and indexing (`ChatMessage.sessionId`, `ChatMessage.parentMessageId`) to efficiently load messages for a given session and reconstruct threads.
    *   The backend service returns the `ChatSession` object, including the **complete flat list of all messages** for that session.
    *   The frontend UI state receives the session object and its messages and updates to show that session as active.
    *   The frontend UI state uses the message data (`parentMessageId`, `childrenMessageIds`) to organize the messages into a threaded structure.
    *   The messages are displayed in the main chat area according to their threaded structure (E1.S5).
    *   **(Cross-cutting Requirement E7.S6):** The database query and data mapping are performed asynchronously using Coroutines.

*   **E2.S5 - Rename a Chat Session (Estimate: S)**
  *   **Description:** As a user, I want to be able to change the name of a chat session after it's created, so that I can give it a more descriptive title based on the conversation content.
  *   **Acceptance Criteria:**
    *   A UI mechanism (e.g., context menu option "Rename", clickable session title) allows initiating the rename action for a session in the list.
    *   An input field or editor appears in the UI, pre-filled with the current session name.
    *   Saving the new name triggers a call via the frontend client API to the backend `updateSessionDetails` service with the session ID and new name.
    *   The backend service uses the Exposed DAO to update the `name` field of the `ChatSession` record.
    *   The backend service returns the updated session or confirmation.
    *   The frontend UI updates the session list to show the new name.
    *   **(Cross-cutting Requirement E2.S2):** The renaming action is persisted in the database by the backend service.
    *   **(Cross-cutting Requirement E7.S6):** The database update is performed asynchronously using Coroutines.

*   **E2.S6 - Delete a Chat Session with Confirmation (Estimate: M)**
  *   **Description:** As a user, I want to remove a chat session and all its associated messages, so that I can clean up unwanted conversations from my list.
  *   **Acceptance Criteria:**
    *   A UI mechanism (e.g., context menu option "Delete") allows initiating the delete action for a session.
    *   Clicking "Delete" displays a confirmation dialog asking the user to confirm the action and warning that messages (including all threads) will be lost.
    *   Confirming the deletion triggers a call via the frontend client API to the backend `deleteSession` service with the session ID.
    *   The backend service uses the Exposed DAO to delete the `ChatSession` record.
    *   **(Dependency):** Associated `ChatMessage` records for that session **(including all messages within all its threads)** are automatically deleted from the database due to the foreign key constraint (`ChatMessage.sessionId`) with `ON DELETE CASCADE` configured in the Exposed schema (E7.S4).
    *   The frontend UI removes the deleted session from the session list.
    *   If the deleted session was the currently active one, the UI navigates to an empty state or selects a default session (e.g., the most recent one).
    *   **(Cross-cutting Requirement E2.S2):** The deletion action is persisted in the database by the backend service.
    *   **(Cross-cutting Requirement E7.S6):** The database delete operation is performed asynchronously using Coroutines.

*   **E2.S7 - Copy Visible Thread Branch to Clipboard (Estimate: M)**
  *   **Description:** As a user, I want to copy the text content of the **currently visible thread branch** in the chat history to my system clipboard, so that I can easily paste or share that specific line of conversation.
  *   **Acceptance Criteria:**
    *   A UI action (e.g., button in the chat area header, menu item) is available to trigger copying the current session's visible branch.
    *   Clicking this action retrieves the list of messages that belong to the **currently visible thread branch** directly from the frontend UI state. *No backend call to retrieve messages is necessary for this specific operation, as the frontend already has the session's full message list loaded (E2.S4) and manages the state of the visible branch (E1.S5).*
    *   The frontend UI logic formats the retrieved messages from the visible branch into a single text string, including clear indicators of speaker turns (e.g., "User: ", "Assistant: ").
    *   *Since only a single branch is copied, explicit indentation or other visual cues to show threading relative to *other* branches are not required; the messages are formatted sequentially in their order within the branch.*
    *   The formatted text string is placed onto the system clipboard using Compose Desktop's `ClipboardManager`.
    *   A brief visual confirmation (e.g., a "Copied!" tooltip) is shown to the user.
    *   **(Dependency):** Relies on the frontend UI State correctly identifying and providing the messages for the currently visible branch as per E1.S5.

#### Epic 3: Advanced Message Control

*   **E3.S1 - Initiate Editing User Message (Estimate: M)**
  *   **Description:** As a user, I want to click on a user message to start editing its content, so I can correct mistakes or refine my prompts, **regardless of whether it's a top-level message or part of a thread**.
  *   **Acceptance Criteria:**
    *   Each user message item in the chat history display (including those within threads) has an accessible "Edit" action (e.g., an icon visible on hover or in a context menu).
    *   Clicking the "Edit" action updates the UI state for that specific message item.
    *   The message text area is replaced by an editable text input field pre-populated with the current message content.
    *   "Save" and "Cancel" actions become visible within or near the editing message item UI.

*   **E3.S2 - Initiate Editing Assistant Message (Estimate: M)**
  *   **Description:** As a user, I want to click on an assistant message to start editing its content, so I can adjust AI responses to better suit my needs (e.g., fix formatting, shorten), **regardless of whether it's a top-level message or part of a thread**.
  *   **Acceptance Criteria:**
    *   Each assistant message item in the chat history display (including those within threads) has an accessible "Edit" action (same mechanism as E3.S1).
    *   Clicking "Edit" changes the display of the message item to an editable state, same as E3.S1.

*   **E3.S3 - Save Edited Message Content (Estimate: M)**
  *   **Description:** As a user, when I finish editing a message, I want to save my changes, so that the updated message is permanently recorded, **while preserving its position and relationships within its thread**.
  *   **Acceptance Criteria:**
    *   Clicking the "Save" action on an editing message item triggers a call via the frontend client API to the backend `updateMessageContent` service with the message ID and the new text content.
    *   The backend service uses the Exposed DAO to update the `content` field for the corresponding `ChatMessage` record in the database.
    *   The `updatedAt` timestamp for the message is also updated in the database.
    *   The backend service returns the updated `ChatMessage` object.
    *   The frontend UI state receives the updated message object and replaces the old version in the display list, **maintaining its correct position within its thread**.
    *   The message item in the UI reverts from the editing state back to display mode, showing the newly saved content.
    *   **(Optional V1.1 Enhancement):** The message item could display a small visual indicator (e.g., "(edited)") next to the timestamp.
    *   **(Cross-cutting Requirement E2.S2):** The updated message record is persisted in the database by the backend service.
    *   **(Cross-cutting Requirement E7.S6):** The database update is performed asynchronously using Coroutines.

*   **E3.S4 - Delete a Specific Message (Estimate: M)**
  *   **Description:** As a user, I want to permanently remove an individual message (user or assistant) from a chat session, **managing its impact on any replies (children)**, so I can prune the conversation history or remove irrelevant parts.
  *   **Acceptance Criteria:**
    *   Each message item in the chat history display has an accessible "Delete" action (e.g., an icon visible on hover or in a context menu).
    *   Clicking the "Delete" action displays a confirmation dialog.
    *   Confirming the deletion triggers a call via the frontend client API to the backend `deleteMessage` service with the message ID.
    *   The backend service uses the Exposed DAO to delete the corresponding `ChatMessage` record from the database and handles its impact on thread relationships. All children of the deleted message will need to deleted as well.
    *   The frontend UI state removes the deleted message item from the display list for the session and updates the UI to reflect the change in threading structure.
    *   **(Cross-cutting Requirement E2.S2):** The deletion action, including updates to parent/child records, is persisted in the database by the backend service.
    *   **(Cross-cutting Requirement E7.S6):** The database delete and related updates are performed asynchronously using Coroutines.

*   **E3.S5 - Copy Single Message Content to Clipboard (Estimate: S)**
  *   **Description:** As a user, I want to copy the raw text content of a single message to my system clipboard, so I can easily use specific parts of a conversation elsewhere.
  *   **Acceptance Criteria:**
    *   Each message item in the chat history display (including those within threads) has an accessible "Copy" action (e.g., an icon visible on hover or in a context menu).
    *   Clicking the "Copy" action retrieves the raw text content of that specific message from the UI state or data model.
    *   The message content is placed onto the system clipboard using Compose Desktop's `ClipboardManager`.
    *   A brief visual confirmation (e.g., a "Copied!" tooltip) is shown to the user.

#### Epic 4: Comprehensive LLM & Settings Configuration

*   **E4.S1 - Add New LLM Model Configuration (Estimate: M)**
  *   **Description:** As a user, I want to define a new LLM endpoint connection by providing its name, base URL, type, and optionally the API key, so the application can connect to it.
  *   **Acceptance Criteria:**
    *   A dedicated settings section or dialog is available for managing LLM models (E4.S2).
    *   An action to add a new model is present (e.g., a button).
    *   A form is displayed allowing input for Model Name (text), Base URL (text), Type (e.g., dropdown with 'openai', 'openrouter'), and API Key (text input).
    *   Saving the form triggers a call via the frontend client API to the backend `addModel` service.
    *   The backend service uses the Exposed DAO to create a new `LLMModel` record, assigning a unique ID.
    *   **(Dependency):** If an API Key is provided, the backend service uses the mechanism from **E5.S1** to securely store the key and saves the returned reference ID in the `apiKeyId` field of the `LLMModel` record.
    *   The backend service returns the newly created `LLMModel` object (excluding the raw API key).
    *   The frontend updates the list of configured models (E4.S2) to include the new model.
    *   **(Cross-cutting Requirement E2.S2):** The new model record is persisted in the database by the backend service.
    *   **(Cross-cutting Requirement E7.S6):** The database insert and key storage/retrieval (if applicable) are performed asynchronously using Coroutines.

*   **E4.S2 - View Configured LLM Models List (Estimate: M)**
  *   **Description:** As a user, I want to see a list of all the LLM connections I have configured in a settings section, so I can manage my available models.
  *   **Acceptance Criteria:**
    *   A dedicated settings screen or dialog is accessible from the main application UI.
    *   Within the settings, a section displays a list of all configured `LLMModel` records.
    *   The list is populated by calling the backend `getAllModels` service via the frontend client API.
    *   Each list item shows the model name.
    *   Each list item also indicates the API key configuration status for that model (linking to E5.S4).
    *   Actions (e.g., buttons, context menus) are available to select, edit (E4.S3), or delete (E4.S4) models from the list.
    *   **(Cross-cutting Requirement E7.S6):** The backend database query and data mapping are performed asynchronously using Coroutines.

*   **E4.S3 - Update LLM Model Configuration Details (Estimate: M)**
  *   **Description:** As a user, I want to modify the name, base URL, or type of an existing LLM model configuration, and update its API key, so I can correct errors or update connection details.
  *   **Acceptance Criteria:**
    *   Selecting a model from the list (in E4.S2) displays its editable details in a form (Model Name, Base URL, Type, API Key input). The API Key input field might be empty or masked for security (E5.S4).
    *   Saving changes in the form triggers a call via the frontend client API to the backend `updateModel` service with the model ID and changed fields.
    *   The backend service uses the Exposed DAO to update the corresponding `LLMModel` record.
    *   **(Dependency):** If a _new_ API Key string is provided in the form, the backend service uses the mechanism from **E5.S1** to securely store it (potentially deleting the old one via E5.S3 if the reference ID changes) and updates the `apiKeyId` in the `LLMModel` record.
    *   The backend service returns the updated `LLMModel` object (excluding the raw key).
    *   The frontend UI updates the model list (in E4.S2) and the details form to reflect the saved changes.
    *   **(Cross-cutting Requirement E2.S2):** The updated model record is persisted by the backend service.
    *   **(Cross-cutting Requirement E7.S6):** The database update and key storage/retrieval (if applicable) are performed asynchronously using Coroutines.

*   **E4.S4 - Delete LLM Model Configuration (Estimate: M)**
  *   **Description:** As a user, I want to remove an LLM model configuration I no longer plan to use, so I can keep my list of available models clean and ensure its associated API key is removed.
  *   **Acceptance Criteria:**
    *   A UI action to delete a model configuration is available (e.g., button on edit form, context menu on list item).
    *   Clicking "Delete" displays a confirmation dialog.
    *   Confirming deletion triggers a call via the frontend client API to the backend `deleteModel` service with the model ID.
    *   The backend service retrieves the `LLMModel` record, identifies the `apiKeyId`.
    *   **(Dependency E5.S3):** The backend service calls the `CredentialManager` to delete the credential associated with that `apiKeyId`.
    *   The backend service uses the Exposed DAO to delete the `LLMModel` record.
    *   **(Dependency):** All `ModelSettings` records associated with this model are automatically deleted from the database due to the foreign key constraint with `ON DELETE CASCADE` configured in the Exposed schema (E7.S4).
    *   **(Cross-cutting Requirement E2.S2):** The deletion action is persisted in the database by the backend service.
    *   The frontend UI removes the deleted model from the list in E4.S2.
    *   Any sessions or messages referencing the deleted model/settings in the UI should update to reflect that (e.g., show "Model Deleted" or revert to a default).
    *   **(Cross-cutting Requirement E7.S6):** The database delete and key deletion are performed asynchronously using Coroutines.

*   **E4.S5 - Manage Model Settings Profiles List (Estimate: M)**
  *   **Description:** As a user, for each configured LLM model, I want to create, name, and manage a list of different interaction settings profiles (like "Default", "Creative", "Strict"), so I can easily apply preferred behaviors for specific tasks.
  *   **Acceptance Criteria:**
    *   Within the UI for a specific model configuration (E4.S3 edit view), there is a list displaying its associated settings profiles.
    *   The list is populated by calling the backend `getSettingsForModel` service (or implicitly loaded with the model).
    *   Actions are available to add a new settings profile (triggering E4.S6 editing), rename existing ones, and delete existing ones (with confirmation triggering backend `deleteSettings` service).
    *   Adding a new profile triggers the backend service to create a `ModelSettings` record linked to the current `modelId`.
    *   **(Cross-cutting Requirement E2.S2):** Changes (add/rename/delete settings profiles) are persisted.
    *   **(Cross-cutting Requirement E7.S6):** Backend database operations are performed asynchronously using Coroutines.

*   **E4.S6 - Edit Model Settings Parameters (Estimate: L)**
  *   **Description:** As a user, within a selected settings profile, I want to customize parameters like the system message, temperature, max tokens, and other model-specific options, so I can fine-tune the LLM's behavior.
  *   **Acceptance Criteria:**
    *   Selecting a settings profile from the list (in E4.S5) displays an editable form for its parameters.
    *   The form includes input fields/controls for: System Message (text area), Temperature (numeric), Max Tokens (integer), Top P (numeric), Frequency Penalty (numeric), Presence Penalty (numeric), and a way to handle arbitrary custom parameters stored as JSON (`customParamsJson` - e.g., a simple key-value editor for V1.1).
    *   Saving the form triggers a call via the frontend client API to the backend `updateSettings` service with the settings ID and the updated parameter values.
    *   Input validation is performed for numeric parameters within the UI and/or backend.
    *   The backend service uses the Exposed DAO to update the corresponding `ModelSettings` record.
    *   The backend service returns the updated `ModelSettings` object.
    *   The frontend UI updates the displayed settings details.
    *   **(Cross-cutting Requirement E2.S2):** The updated settings record is persisted by the backend service.
    *   **(Cross-cutting Requirement E7.S6):** The database update is performed asynchronously using Coroutines.

*   **E4.S7 - Select Model & Settings Profile for Session (Estimate: M)**
  *   **Description:** As a user, within an active chat session, I want to easily choose which configured model and specific settings profile will be used for the _next_ assistant response, allowing me to change the AI's approach mid-conversation.
  *   **Acceptance Criteria:**
    *   A UI element (e.g., dropdown menu) is visible in the chat session view (near the input area) showing the currently selected model and settings profile name for this session.
    *   Clicking the element displays a list populated with configured models and their available settings profiles (from E4.S5 data).
    *   Selecting a different model/settings profile updates the UI element to show the new selection.
    *   The frontend client API calls the backend `updateSessionDetails` service to update the `currentModelId` and `currentSettingsId` fields for the active `ChatSession` record in the database.
    *   **(Dependency E1.S4):** The backend LLM calling logic reads these `currentModelId` and `currentSettingsId` from the session record _before_ making the LLM call to determine which configuration to use.
    *   **(Cross-cutting Requirement E2.S2):** The updated session record is persisted.
    *   **(Cross-cutting Requirement E7.S6):** The database update is performed asynchronously using Coroutines.

#### Epic 5: Secure API Key Handling

*   **E5.S1 - Implement Secure API Key Storage (Database-Backed Encryption)**
  *   **Description:** As a developer, I need to implement the core logic for securely storing and managing sensitive API keys by utilizing **custom envelope encryption** and storing the resulting encrypted data in a **dedicated database table** (`api_secrets`), so that user keys are protected locally without relying on OS-specific credential managers.
  *   **Estimate:** L
  *   **Acceptance Criteria:**
    *   A backend `CredentialManager` interface is implemented by `DbEncryptedCredentialManager`.
    *   The implementation uses **custom envelope encryption** via an `EncryptionService` and `CryptoProvider` (handling DEK/KEK), with KEK configuration managed by `EncryptionConfig`.
    *   Persistence uses an `ApiSecretDao` to interact with a dedicated `api_secrets` database table (E7.S4).
    *   The `api_secrets` table stores the encrypted API key (`encrypted_credential`), the wrapped Data Encryption Key (`wrapped_dek`), and the KEK version, uniquely identified by a generated UUID `alias`.
    *   The `DbEncryptedCredentialManager`'s `storeCredential` method encrypts the raw key, generates a UUID `alias`, and saves the encrypted data (with the `alias`) to the `api_secrets` table via `ApiSecretDao`, returning the `alias`.
    *   The `DbEncryptedCredentialManager`'s `getCredential` method retrieves the encrypted data by `alias` from `api_secrets` via `ApiSecretDao`, unwraps the DEK, decrypts the key, and returns the raw key string.
    *   The `DbEncryptedCredentialManager`'s `deleteCredential` method deletes the record matching the `alias` from the `api_secrets` table via `ApiSecretDao`.
    *   The `LLMModel` database schema (E7.S4) includes an `apiKeyId` field (String, nullable) that stores the UUID `alias` referencing the `api_secrets` table record, *not* the raw key.
    *   Backend services for model management (E4.S1, E4.S3) use the `CredentialManager` to store keys securely, saving the returned `alias` in the corresponding `LLMModel` record.
    *   Raw API keys are only held in application memory temporarily when actively needed for storage, retrieval, or deletion.
    *   **(Cross-cutting Requirement E2.S2):** All database operations on the `api_secrets` table are persisted.
    *   **(Cross-cutting Requirement E7.S6):** Database interactions via `ApiSecretDao` and encryption/decryption operations are performed asynchronously using Coroutines.

*   **E5.S2 - Securely Retrieve API Key for LLM API Calls (Estimate: S)**
  *   **Description:** As the application backend, I need to use the secure storage mechanism (E5.S1) to retrieve the actual API key from the OS Credential Manager just before making an external LLM API call, ensuring the key is not sitting in memory unnecessarily.
  *   **Acceptance Criteria:**
    *   The backend LLM communication logic (part of E1.S4's orchestration) identifies the `apiKeyId` from the selected `LLMModel` record.
    *   The backend service uses the `CredentialManager` (from E5.S1) to retrieve the actual decrypted API key string using the `apiKeyId`.
    *   The retrieved key is passed to the `LLMApiClient` (from E1.S4's backend flow) to authenticate the HTTP request to the LLM API endpoint (typically in the `Authorization: Bearer <key>` header).
    *   The retrieved key is not logged or stored in the database.
    *   **(Cross-cutting Requirement E7.S6):** The key retrieval operation (if blocking) is performed asynchronously using Coroutines.

*   **E5.S3 - Securely Delete API Key on Model Removal (Estimate: S)**
  *   **Description:** As the application backend, when a user deletes an LLM model configuration, I need to ensure the associated API key is also removed from the secure OS credential manager, so that sensitive data is cleaned up.
  *   **Acceptance Criteria:**
    *   The backend model deletion logic (part of E4.S4) identifies the `apiKeyId` associated with the `LLMModel` record being deleted.
    *   The backend service uses the `CredentialManager` (from E5.S1) to delete the credential corresponding to that `apiKeyId` from the Windows Credential Manager.
    *   **(Cross-cutting Requirement E7.S6):** The key deletion operation (if blocking) is performed asynchronously using Coroutines.

*   **E5.S4 - Indicate API Key Configuration Status in UI (Estimate: S)**
  *   **Description:** As a user, I want to see if an API key has been configured for a specific model in the settings UI without the key being displayed, so I know if that model is ready for use.
  *   **Acceptance Criteria:**
    *   The backend service provides a way (e.g., a method `isApiKeyConfiguredForModel` in `ModelService`, called by the frontend client API) to check if an `LLMModel` record has a non-null `apiKeyId`.
    *   The Model management UI (E4.S2 list and E4.S3 edit form) displays a status indicator for each model (e.g., text label like "Configured", "Not Set") based on the result of this check.
    *   The actual API key string is never displayed in the UI after initial input, only the status.
    *   **(Cross-cutting Requirement E7.S6):** The backend check (if involving I/O like querying the DB for the ID) is performed asynchronously.

#### Epic 6: Chat Session Organization & Navigation

*   **E6.S1 - Assign Session to a Group via Menu/Dialog**
  *   **Description:** As a user, I want to assign a chat session to a defined group, or remove it from a group, by selecting from a list of available groups using a menu or dialog in the session list UI, so I can categorize my conversations.
  *   **Estimate:** M
  *   **Acceptance Criteria:**
    *   A UI mechanism (e.g., a context menu option "Move to Group" or a button that opens a group picker dialog) is available **within the session list UI** (E2.S3) for individual chat sessions.
    *   Initiating this mechanism displays a list of **existing session groups** (from E6.S4) and an option for "Ungrouped".
    *   Selecting a group from the list (or "Ungrouped") triggers a call via the frontend client API to a backend service (e.g., `assignSessionToGroup`) with the session ID and the selected **Group ID** (or null for ungrouped).
    *   The backend service uses the Data Access Layer (DAO) to update the `groupId` field for the `ChatSession` record with the provided **Group ID** (or null).
    *   The backend service returns the updated session summary.
    *   The frontend UI updates the session list display (E6.S2) immediately to reflect the new grouping for that session.
    *   **(Cross-cutting Requirement E2.S2):** The change is persisted in the database by the backend service.
    *   **(Cross-cutting Requirement E7.S6):** The database update is performed asynchronously using Coroutines.

*   **E6.S2 - View Sessions Organized by Group**
  *   **Description:** As a user, I want the main list of chat sessions to be organized visually by the group they are assigned to, so I can easily browse my categorized conversations within a group hierarchy.
  *   **Estimate:** M
  *   **Acceptance Criteria:**
    *   The session list UI (E2.S3) retrieves session summaries, including their associated **Group ID**.
    *   The UI component also retrieves the list of **defined groups** (from E6.S4).
    *   The UI displays sessions grouped together based on their **`groupId` reference to a `ChatGroup` entity**.
    *   Groups are displayed visually as containers or headers.
    *   Sessions assigned to a group are listed visually under that group's name.
    *   Sessions with a null `groupId` are listed in a separate "Ungrouped" section.
    *   Sessions are ordered within each group by their `updatedAt` timestamp, with the most recently updated sessions at the top.
    *   The grouping structure is applied when the session list is initially loaded (E2.S3) and updates automatically in the UI as sessions are assigned to groups (E6.S1, E6.S7) or groups are managed (E6.S5, E6.S6).
    *   **(Cross-cutting Requirement E7.S6):** The backend database queries and data mapping for both sessions and groups are performed asynchronously using Coroutines.

*   **E6.S3 - Add New Session Group**
  *   **Description:** As a user, I want to create a new, named group to categorize my chat sessions, so I can define my organization structure.
  *   **Estimate:** S
  *   **Acceptance Criteria:**
    *   A UI section or dialog for managing groups is available (e.g., accessible via settings or a dedicated panel within the session list area).
    *   An action to add a new group is present (e.g., a button labelled "Add Group").
    *   Clicking the action displays an input field or form to enter the name for the new group.
    *   Saving the new name triggers a call via the frontend client API to a backend service (e.g., `createGroup`) with the desired group name.
    *   The backend service uses the DAO to insert a new `ChatGroup` record into the database, assigning a unique ID and storing the provided name.
    *   The backend service returns the newly created `ChatGroup` object.
    *   The frontend UI updates the list of defined groups (E6.S4) to include the new group, and the session list UI (E6.S2) is updated to reflect the new group as a possible container.
    *   **(Cross-cutting Requirement E2.S2):** The new group record is persisted in the database by the backend service.
    *   **(Cross-cutting Requirement E7.S6):** The database insert is performed asynchronously using Coroutines.

*   **E6.S4 - View List of Session Groups**
  *   **Description:** As a user, I want to see a list of all the session groups I have created, so I can understand and manage my organization structure. This list should be visible in the session list UI to support organizing.
  *   **Estimate:** S
  *   **Acceptance Criteria:**
    *   A dedicated UI section or panel for managing groups (potentially integrated into the session list panel, or accessible via a link/button near it) is accessible.
    *   Within this section/panel, a list displays all configured `ChatGroup` records.
    *   The list is populated by calling a backend service (e.g., `getAllGroups`) via the frontend client API upon loading the session list area or opening the group management UI.
    *   Each list item shows the name of the group.
    *   Actions (e.g., buttons, context menus) are available on each list item to rename (E6.S5) or delete (E6.S6) the group.
    *   **(Cross-cutting Requirement E7.S6):** The backend database query and data mapping are performed asynchronously using Coroutines.

*   **E6.S5 - Rename a Session Group**
  *   **Description:** As a user, I want to change the name of an existing session group, so I can correct typos or refine its title.
  *   **Estimate:** S
  *   **Acceptance Criteria:**
    *   A UI mechanism (e.g., action from the list in E6.S4, or in a group details view) allows initiating the rename action for a specific group.
    *   An input field or editor appears in the UI, pre-filled with the current group name.
    *   Saving the new name triggers a call via the frontend client API to a backend service (e.g., `renameGroup`) with the Group ID and the new name.
    *   The backend service uses the DAO to update the `name` field for the `ChatGroup` record.
    *   The backend service returns the updated `ChatGroup` object.
    *   The frontend UI updates the group list display (E6.S4) and the session list view (E6.S2) to show the new name.
    *   **(Cross-cutting Requirement E2.S2):** The renaming action is persisted in the database by the backend service.
    *   **(Cross-cutting Requirement E7.S6):** The database update is performed asynchronously using Coroutines.

*   **E6.S6 - Delete a Session Group**
  *   **Description:** As a user, I want to remove a session group that I no longer need, so I can keep my group list clean.
  *   **Estimate:** M
  *   **Accept Criteria:**
    *   A UI action to delete a group is available (e.g., from the list in E6.S4).
    *   Clicking "Delete" displays a confirmation dialog asking the user to confirm the action and warning that sessions currently in this group will become ungrouped.
    *   Confirming the deletion triggers a call via the frontend client API to a backend service (e.g., `deleteGroup`) with the Group ID.
    *   The backend service uses the DAO to delete the `ChatGroup` record.
    *   The backend service must also update all `ChatSession` records that reference the deleted group's ID, setting their `groupId` field to null (ungrouped).
    *   The backend service returns confirmation.
    *   The frontend UI removes the deleted group from the group list (E6.S4).
    *   The frontend UI updates the session list (E6.S2) to show sessions that were in the deleted group as now being in the "Ungrouped" section.
    *   **(Cross-cutting Requirement E2.S2):** The deletion action, including the updates to affected sessions, is persisted in the database by the backend service.
    *   **(Cross-cutting Requirement E7.S6):** The database delete and related updates are performed asynchronously using Coroutines.

*   **E6.S7 - Drag and Drop Session to Group**
  *   **Description:** As a user, I want to visually move a chat session into a specific group, or into the ungrouped section, by dragging its item in the session list and dropping it onto the target group or section header, so I can quickly organize my sessions.
  *   **Estimate:** M (This estimate can vary significantly based on Compose Desktop's D&D support maturity and complexity)
  *   **Acceptance Criteria:**
    *   Individual chat session items in the session list UI (E2.S3) are draggable.
    *   Visual feedback is provided while a session item is being dragged (e.g., a ghost image of the item).
    *   Session group items (headers/entries from E6.S4) and the "Ungrouped" section header in the session list UI are valid drop targets for session items.
    *   Visual feedback is provided when a dragged session item is hovered over a valid drop target (e.g., highlight the target group/section).
    *   Dropping a session item onto a group item (or the "Ungrouped" section) triggers a call via the frontend client API to the backend service (e.g., `assignSessionToGroup`) with the dragged session's ID and the target **Group ID** (or null for ungrouped).
    *   The frontend UI updates the session list display (E6.S2) immediately to reflect the new grouping for the dropped session.
    *   **(Cross-cutting Requirement E2.S2):** The change is persisted in the database by the backend service.
    *   **(Cross-cutting Requirement E7.S6):** The backend database update is performed asynchronously using Coroutines.

#### Epic 7: Application Core Framework & Windows 11 Integration

*   **E7.S1 - Create Basic Windows 11 Installer (Estimate: M)**
  *   **Description:** As a potential user, I want to easily install the application on my Windows 11 computer, so I can start using it without complex manual setup.
  *   **Acceptance Criteria:**
    *   The Gradle build configuration in the `app` module includes a task (using Compose Desktop's packaging tools) to generate a Windows-specific installer package (e.g., `.exe`).
    *   Running the generated installer successfully installs the application files (containing the bundled `app` and `server` module code, JVM, etc.) in a standard Windows location (e.g., `Program Files` or user's `AppData`).
    *   The installed application can be launched via a desktop shortcut or the Start Menu.

*   **E7.S2 - Initialize Main Application Window (Estimate: S)**
  *   **Description:** As a user, when I launch the application, a primary application window should appear, providing the container for all UI elements.
  *   **Acceptance Criteria:**
    *   The application's entry point (`main` function in the `app` module) correctly uses Compose for Desktop's `application` and `Window` components to create the main application window.
    *   The window has a title (e.g., "AIChat Desktop App").
    *   The window provides a basic layout structure (e.g., using `Column`, `Row`) ready for integrating the main UI components (session list, chat area, input area) from the `app.ui` package.
    *   The UI state holder (`ChatState`) and its dependencies (including the Frontend API Client) are initialized, preferably via Dependency Injection.

*   **E7.S3 - Initialize Embedded Ktor Server (Estimate: L)**
  *   **Description:** As a developer, I need the Ktor HTTP server from the `server` module to start automatically within the application process on launch, configured to handle API endpoints and use the backend services, so the frontend can communicate with the backend logic via HTTP requests.
  *   **Acceptance Criteria:**
    *   Code exists in the `app` module's startup sequence to configure and start an embedded Ktor server instance from the `server` module (e.g., using Netty engine).
    *   The server binds to a `localhost` address on a configured or dynamically found available port (e.g., 8080 or 0 for random). The chosen port needs to be accessible by the frontend Ktor client.
    *   Ktor features like Content Negotiation (for JSON) are configured within the `server` module's API setup.
    *   API routing structure (`/api/v1/...`) is defined in the `server` module's `api.server` package, mapping incoming HTTP requests to calls to the backend service interfaces (`ChatService`, `ModelService`).
    *   The necessary backend services (`ChatService`, `ModelService`) are injected into the Ktor routing handlers.
    *   The server starts successfully without errors reported in logs.
    *   **(Cross-cutting Requirement E7.S6):** Server setup and routing handlers correctly manage asynchronous operations and thread pools.

*   **E7.S4 - Initialize SQLite Database & Exposed Schema (Estimate: L)**
  *   **Description:** As a developer, I need the application to connect to the SQLite database file on startup and ensure all necessary tables, indexes, and foreign key constraints are created or updated using Exposed, so that data persistence works reliably, **including support for message threading relationships**.
  *   **Acceptance Criteria:**
    *   Database connection logic using Exposed is implemented within the `server` module's `data.exposed` package.
    *   The database file (`chat.db`) is created in a user-specific, platform-appropriate location if it doesn't exist.
    *   Exposed Table objects (`ChatSessions`, `ChatMessages`, `LLMModels`, `ModelSettings`) are defined in the `server` module's `data.models` package, reflecting the schema requirements.
    *   The `ChatMessages` Exposed Table definition **includes a column for `parentMessageId` (Long, nullable, treated as a foreign key referencing `ChatMessages.id`) and a column for `childrenMessageIds` (likely stored as a serialized data type like JSON or comma-separated string).**
    *   Database creation or migration logic using `SchemaUtils.create` (or similar Exposed migration features) runs on startup to create tables if they are missing.
    *   Foreign key constraints are defined using Exposed references: `ChatMessage.sessionId` referencing `ChatSession.id` with `ReferenceOption.CASCADE` for `onDelete`. **Optionally, `ChatMessage.parentMessageId` referencing `ChatMessage.id` could be defined as a self-referencing foreign key with `ReferenceOption.SET_NULL` on delete for the orphan strategy, but for V1.1 the orphan logic is handled at the Service/DAO layer.**
    *   Indices are defined in Exposed Table objects for frequently queried columns: `ChatMessage.sessionId`, **`ChatMessage.parentMessageId`**, `ModelSettings.modelId`, `ChatSession.groupId`.
    *   The database connection is established and ready for use by Exposed DAO implementations.
    *   **(Cross-cutting Requirement E7.S6):** Database connection and schema operations are performed asynchronously using Coroutines if they could be blocking.

*   **E7.S5 - Implement Layered Architecture (Cross-cutting Requirement)**
  *   **Description:** As a developer, I will structure the codebase with clear boundaries between the UI (`app.ui`), Frontend API Client (`app.api.client`), Embedded Server (`server.api.server`), Application Logic (`server.service`), Data Access (`server.data`), and External Services (`server.external`) across the defined Gradle modules (`common`, `app`, `server`). I will ensure dependencies flow correctly and interfaces are used to decouple components, allowing for potential future extraction or modularity. **This architecture now explicitly includes layers responsible for handling message threading logic and data persistence.**
  *   **Acceptance Criteria:** This is not a single user story but a coding standard and architectural task applied _during_ the implementation of other stories.
    *   Code is organized into packages corresponding to the layers and modules as defined in the architectural document (V1.1).
    *   Core business logic, **including threading logic**, resides in the `server.service` layer.
    *   Data access logic, **including persistence of threading relationships**, is encapsulated within the `server.data` package (DAO interfaces and Exposed implementations).
    *   External service communication (LLM calls, Credential Manager) is abstracted behind interfaces/classes in `server.external`.
    *   Dependencies flow primarily unidirectionally as defined in the V1.1 architecture document.
    *   Interfaces are defined and used by the calling code.
    *   Code reviews enforce adherence to this modular and layered architecture.
    *   Dependency Injection (e.g., Koin) is used to wire concrete implementations to interfaces across modules.

*   **E7.S6 - Use Coroutines for Asynchronous Operations (Cross-cutting Requirement)**
  *   **Description:** As a developer, I will ensure that all I/O-bound and potentially blocking operations (database access via Exposed, network calls via Ktor, **including operations related to threading persistence and context building**) are performed asynchronously using Kotlin Coroutines, so that the application UI remains responsive and the application feels fluid.
  *   **Acceptance Criteria:** This is a coding standard applied _during_ the implementation of other stories.
    *   All Exposed database read/write operations, **including updates to parent/child lists**, are performed within appropriate coroutine contexts (e.g., `Dispatchers.IO`).
    *   All Ktor client calls (both frontend to server and backend to LLM) are correctly handled as suspend functions within coroutine scopes.
    *   Long-running background tasks (like waiting for an LLM response or complex context building for a thread) do not block the main UI thread or Ktor's Netty event loop threads unnecessarily.
    *   State updates in the UI triggered by asynchronous results are handled correctly to ensure thread safety and proper Compose recomposition.
    *   Coroutine scopes and structured concurrency are used appropriately to manage the lifecycle of asynchronous tasks.
    *   Code reviews enforce the consistent and correct use of coroutines for asynchronous work.

*   **E7.S7 - Graceful Application Shutdown (Estimate: M)**
  *   **Description:** As the application, I need to shut down cleanly when the user closes the window or the operating system requests termination, ensuring all resources are released and data integrity is maintained.
  *   **Acceptance Criteria:**
    *   Clicking the main application window's close button or receiving an OS termination signal initiates a shutdown sequence in the `app` module.
    *   The shutdown sequence correctly stops the embedded Ktor server instance (`server` module).
    *   The SQLite database connection (managed in the `server` module's `data.exposed` package) is properly closed.
    *   Any other significant resources (like Ktor HttpClient instances) are closed.
    *   Background coroutine scopes are cancelled.
    *   The application process exits without errors or leaving zombie processes.
