# New feature ideas

## Basic features

### Allow message streaming process to stop gracefully
  - Add Boolean field to AssistantMessage to indicate if the message is complete.
  - Add error message String field to AssistantMessage (which can be shown in the UI, together with the partial content).
  - Store partial message content in AssistantMessage table, in case the stream was interrupted. (either due to an error, or because the user stopped the streaming)
  - In the UI, the "send" button should transform into a "stop" button while the message is streaming. 

### Allow recursive deletion of messages. (in addition to single message deletion)
  - The server module is already capable of this, but the ktor route needs to be examined. In `configureMessageRoutes.kt` we have a route `delete<MessageResource.ById>`, where we use a query parameter "mode=single" to perform a non-recursive delete and "mode=recursive" to perform a recursive delete. However, we use the Ktor Resources plugin, and we should examine if a query parameter can be configured for the route declaritively instead, so that it can be validated at compile time.
  - Add a "Delete Thread" button to the message actions menu. (see MessageActionRow.kt)
    (or hide it under a "More" actions button, to show a popup menu with extra actions, less commonly used)

### Insert message above/below
Add an "Insert Above" and "Insert Below" button to the more actions menu. (See MessageActionRow.kt)

### Make message content selectable

### Make messages collapsible (useful for long messages)

### Make message content copyable to clipboard
A "Copy" button is already available in the message actions menu, but it needs to be wired to an action function. (see MessageActionRow.kt)

### Make currently displayed message thread copyable to clipboard

### Make chat session copyable to file/clipboard as JSON (for importing into other apps)

### Make chat session importable from file/clipboard (for ChatGPT export format)

### Make messages searchable

### Add Send button to message edit area (in addition to Save/Cancel)
This action triggers a reply to the edited message from the assistant/LLM. It also creates a new branch in the conversation. (See composable MessageContent.kt)

### Add option to regenerate assistant messages
A "Regenerate" button is already available in the message actions menu, but it needs to be wired to an action in the view model. (see MessageActionRow.kt)
This action should trigger a new response from the assistant/LLM, based on the current message context. It must also create a new branch in the conversation. (See MessageActionRow.kt)

### Allow certain features to be disabled on a per-user basis
The user should be able to configure this in their profile. (Requires: user accounts feature)

### Allow user to see LLM metadata for assistant responses

### Allow user to quickly scroll to the bottom of the messages list

### Allow user to switch accounts without logging out (requires: user accounts feature)

### Allow admin to create new accounts for other users. (requires: user accounts feature)
Users will be forced to change their password on first login.

---

## Intermediate features

### User accounts
Each user has their own set of credentials, models, settings, and chat history. Some things could be shared between users, such as public providers, models and settings. Certain users could be assigned special privileges, such as the ability to manage other users, or add public providers, models and settings. There must be at least one admin user, who has all privileges (or has at least the capability to assign privileges to theirself and other users)

---

## Advanced features

### Allow basic tool calling for LLM models that support it

### Add option to add tools to MCP server
Tools are functions that the LLM can execute. They are defined by the user and can be used to interact with the outside world. For instance a tool could be defined to search the web for information. The LLM could then use this tool to search the web for information to answer a question.

### Add option to load knowledge base into MCP server
This knowledge base can be queried by the LLM to answer questions. It can also add items to the knowledge base itself. The knowledge base is a combination of entities and relationships between them. A query could consist of an entity and an integer to indicate the level of relationships to traverse. The result of the query is a list of entities and relationships that can be used to answer the question. For instance a level 1 query for "John" could return:
  - John is a person
  - John is 30 years old
  - John lives in New York
A level 2 query for "John" could return:
  - John is a person
  - John is 30 years old
  - John lives in New York
  - New York is a city
  - New York is in the USA
  - New York has a population of 8.4 million
  - etc.

### AI Everywhere
Alow LLM agent to control the app itself. For instance: modifying a chat session, Summarizing it, or creating a new session.

---

## Performance improvements

### Override equals & hashCode for ChatMessage class
Make equals and hashCode methods based on the `id` and `updatedAt` fields only. This should improve the performance of the UI when rendering messages. (See MessageList.kt)

### Avoid loading the extended Icons library for Compose, to reduce app size
See Gradle dependencies: `libs.version.toml` and `app/build.gradle.kts`. We now load the extended Icons library: `implementation(compose.materialIconsExtended)`


Here are detailed user stories for the new feature ideas, following the format and structure of your existing `User Stories v1.2.md` document, with new feature IDs starting from `NF`. Non-functional requirements (NFRs) are listed separately.

---

# AIChat Desktop App - New Feature User Stories

## New Features: Basic Interactions

### NF.S1 - Interrupt Streaming Assistant Response
*   **Description:** As a user, I want to see a "Stop" button in place of the "Send" button while an assistant message is being streamed, so I can gracefully interrupt a long, undesirable, or irrelevant response before it completes.
*   **Estimate:** M
*   **Acceptance Criteria:**
    *   While an assistant message is actively streaming (i.e., `AssistantMessage.isComplete` is false), the "Send" button transforms into a "Stop" button.
    *   Clicking the "Stop" button sends a signal to the backend to stop the LLM streaming process.
    *   The UI updates immediately to reflect that the stream has stopped.
    *   The "Stop" button reverts to "Send" after the stream is interrupted.

### NF.S2 - View Partial Assistant Message Content
*   **Description:** As a user, if an assistant message stream is interrupted (either by me stopping it or due to an error), I want to see the partial content that was generated before the interruption, so I don't lose the information that was already received.
*   **Estimate:** S
*   **Acceptance Criteria:**
    *   When an assistant message stream stops prematurely, the displayed message content includes all text received up to that point.
    *   The partial message content is stored in the `AssistantMessage` table.
    *   The UI clearly indicates that the message is incomplete or was interrupted.

### NF.S3 - Display Streaming Interruption Error
*   **Description:** As a user, if an assistant message stream is interrupted due to an error (e.g., network issue, LLM error), I want to see a clear error message along with the partial content (if any), so I understand why the stream stopped unexpectedly.
*   **Estimate:** M
*   **Acceptance Criteria:**
    *   If an error occurs during message streaming, the `AssistantMessage.errorMessage` field is populated with a user-friendly description.
    *   The UI displays this error message clearly, alongside any partial content (NF.S2).
    *   The error message is visible in the chat interface where the message was being displayed.

### NF.S4 - Delete Entire Message Thread
*   **Description:** As a user, I want to delete a specific message and all its descendant messages (i.e., the entire sub-thread originating from that message) with a single action, so I can quickly remove entire branches of a conversation that are no longer relevant.
*   **Estimate:** M
*   **Acceptance Criteria:**
    *   A "Delete Thread" button is available in the message actions menu for any message that has children.
    *   Clicking "Delete Thread" prompts a confirmation dialog warning about recursive deletion.
    *   Upon confirmation, the backend `deleteMessage` route is called with `mode=recursive` for the specified message ID.
    *   The backend successfully deletes the target message and all messages that are children or descendants of it from the `ChatMessageTable`.
    *   The frontend UI instantly removes all deleted messages from the display and updates the conversation structure.

### NF.S5 - Insert Message Above Existing Message
*   **Description:** As a user, I want to insert a new message *above* an existing message in the conversation flow, so I can add historical context, a forgotten detail, or correct a previous statement without altering the original message or creating a new branch.
*   **Estimate:** M
*   **Acceptance Criteria:**
    *   An "Insert Above" button is available in the message actions menu.
    *   Clicking "Insert Above" opens an input field to compose a new message, visually placed above the target message.
    *   The newly inserted message maintains the same `parentMessageId` as the target message (if any) and becomes a new sibling before it.
    *   The UI updates to reflect the new message's position in the thread.

### NF.S6 - Insert Message Below Existing Message
*   **Description:** As a user, I want to insert a new message *below* an existing message in the conversation flow, so I can add a follow-up, a clarification, or additional information that logically extends the preceding message without creating a new conversation branch.
*   **Estimate:** M
*   **Acceptance Criteria:**
    *   An "Insert Below" button is available in the message actions menu.
    *   Clicking "Insert Below" opens an input field to compose a new message, visually placed below the target message.
    *   The newly inserted message becomes a new child of the target message, maintaining its position in the thread.
    *   The UI updates to reflect the new message's position and the `childrenMessageIds` of the target message are updated.

### NF.S7 - Select Text within Messages
*   **Description:** As a user, I want to be able to select and highlight specific portions of text within any message (user or assistant), so I can easily copy snippets, search for definitions, or interact with the text.
*   **Estimate:** S
*   **Acceptance Criteria:**
    *   All message content (user and assistant) in the chat display is selectable using standard text selection methods (e.g., click and drag with the mouse).
    *   Selected text can be copied to the clipboard using system-standard shortcuts (e.g., Ctrl+C/Cmd+C).

### NF.S8 - Collapse/Expand Long Messages
*   **Description:** As a user, I want to collapse long messages to hide their full content and expand them when needed, so I can reduce visual clutter, improve readability, and easily navigate conversations with verbose responses.
*   **Estimate:** M
*   **Acceptance Criteria:**
    *   Messages exceeding a defined length (e.g., number of lines or characters) are initially displayed in a collapsed state, showing only a summary or the first few lines.
    *   A clear UI indicator (e.g., an "Expand" [button, "Show More" text) is present for collapsed messages.
    *   Clicking the indicator expands the message to reveal its full content.]()
    *   An indicator (e.g., "Collapse" button, "Show Less" text) is present for expanded messages to revert them to a collapsed state.

### NF.S9 - Export Chat Session to JSON
*   **Description:** As a user, I want to export an entire chat session, including all its messages, thread structure, and associated metadata (e.g., model, settings), to a JSON file or the clipboard, so I can back up my conversations, share them, or import them into other applications.
*   **Estimate:** L
*   **Acceptance Criteria:**
    *   A "Export Session" action is available for an active chat session (e.g., in a session menu or more actions menu).
    *   The action provides options to export to a file (allowing user to select location) or copy to clipboard.
    *   The exported JSON contains all messages in the session, their content, roles, timestamps, parent/children relationships, and relevant session metadata (name, model IDs, etc.).
    *   The JSON format is well-structured and documented.

### NF.S10 - Import Chat Session from JSON (ChatGPT Format)
*   **Description:** As a user, I want to import a chat session from a JSON file (specifically supporting the common ChatGPT export format), so I can migrate my conversations from other platforms or restore previously exported backups.
*   **Estimate:** L
*   **Acceptance Criteria:**
    *   An "Import Session" action is available (e.g., in the main application menu or session list).
    *   The action allows the user to select a JSON file for import or paste JSON content from the clipboard.
    *   The backend parses the JSON, creating a new `ChatSession` and `ChatMessage` records based on the imported data, including reconstructing the thread relationships.
    *   The imported session appears in the session list after successful import.
    *   Error handling is robust for invalid or malformed JSON.

### NF.S11 - Search Chat Messages
*   **Description:** As a user, I want to search for specific keywords or phrases within the messages of the currently active chat session, or even across all sessions, so I can quickly find relevant information or past discussions.
*   **Estimate:** L
*   **Acceptance Criteria:**
    *   A search input field is available in the UI (e.g., in the chat area or a global search bar).
    *   Typing in the search field filters messages in the current session, highlighting matches.
    *   An option exists to expand the search to all sessions, showing matching sessions and messages.
    *   Clicking a search result navigates to the relevant message in its session.

### NF.S12 - Send Edited Message as New Prompt
*   **Description:** As a user, when I am editing a message (user or assistant), I want to have a "Send" button in addition to "Save" and "Cancel" within the edit area, so that the edited message is treated as a new prompt to the assistant, triggering a new LLM response and creating a new conversation branch.
*   **Estimate:** L
*   **Acceptance Criteria:**
    *   When a message is in edit mode (E3.S1, E3.S2), a "Send" button is displayed alongside "Save" and "Cancel".
    *   Clicking "Send" (instead of "Save") updates the message content locally, then uses this edited message as the `parentMessageId` for a new LLM request.
    *   The LLM generates a new response, which appears as a child of the edited message, starting a new conversation branch.
    *   The UI automatically switches to display this new branch.

### NF.S13 - Regenerate Assistant Response
*   **Description:** As a user, I want to be able to re-trigger the generation of an assistant message, based on its original parent message and context, so I can obtain an alternative response if the previous one was unsatisfactory or incomplete, creating a new branch in the conversation.
*   **Estimate:** M
*   **Acceptance Criteria:**
    *   A "Regenerate" button is available in the message actions menu for assistant messages.
    *   Clicking "Regenerate" triggers a new LLM request using the context leading up to the assistant's parent message.
    *   A new assistant message is generated and displayed as a sibling to the original assistant message, creating a new conversation branch.
    *   The UI automatically switches to display this new branch.

### NF.S14 - Customize Enabled Features (Per User)
*   **Description:** As a user, I want to view and customize which optional application features are enabled or disabled for my individual account in my profile settings, so I can tailor the interface and functionality to my specific preferences and workflow.
*   **Estimate:** M
*   **Dependencies:** NF.E1 - User Accounts and Permissions
*   **Acceptance Criteria:**
    *   A "Feature Settings" section is available in the user's profile.
    *   This section lists configurable features (e.g., "Allow Tool Calling", "Enable Knowledge Base").
    *   Users can toggle (e.g., checkboxes) the activation status of these features for their own account.
    *   Changes are saved to the user's profile and take effect immediately or upon next session.
    *   Features disabled by an administrator (NF.S15) cannot be re-enabled by the user.

### NF.S15 - Admin Disable Features (Per User)
*   **Description:** As an administrator, I want to disable specific application features for individual users or groups of users, so I can manage access, control application complexity, or enforce policies for different user roles within the system.
*   **Estimate:** L
*   **Dependencies:** NF.E1 - User Accounts and Permissions
*   **Acceptance Criteria:**
    *   An admin interface allows selecting a user or user group.
    *   For the selected user/group, a list of configurable features is displayed with toggles to enable/disable them.
    *   Disabling a feature for a user/group overrides any personal settings (NF.S14) for that feature.
    *   Disabled features are hidden or clearly marked as unavailable in the UI for affected users.
    *   Changes are saved and applied immediately.

### NF.S16 - Show LLM Response Metadata
*   **Description:** As a user, I want to view metadata associated with an assistant's response (e.g., model, settings, tokens used, latency), so I can understand the context and performance of the response.
*   **Estimate:** M
*   **Acceptance Criteria:**
    *   Metadata (e.g., model name, settings profile, tokens used, latency) is stored with each assistant message.
    *   A "Show Metadata" button or similar UI element is available for assistant messages.
    *   Clicking the button displays a panel or dialog with the assistant message's metadata.

### NF.S17 - Quickly scroll to the bottom of the messages list
*   **Description:** As a user, I want to be able to quickly scroll to the bottom of the messages list to see the latest messages, so I can easily follow the conversation.
*   **Estimate:** S
*   **Acceptance Criteria:**
    *   A "Scroll to Bottom" button or similar UI element is available.
    *   Clicking the button smoothly scrolls the messages list to the bottom.
    *   The button is only visible when the messages list is not already at the bottom.

---

## New Features: Intermediate

### NF.E1 - User Accounts and Permissions (Epic)
*   **Description:** This epic covers the foundational work for implementing a multi-user environment where each user has their own secure account, distinct chat history, personal configurations, and definable privileges. It also addresses the sharing of public resources across users and robust administration capabilities.
*   **Estimate:** XL

### NF.E1.S1 - Register and Log In to Personal Account
*   **Description:** As a user, I want to register for a new account with unique credentials and subsequently log in, so that my chat history, personal model configurations, and settings are private, securely stored, and distinct from other users of the application.
*   **Estimate:** L
*   **Acceptance Criteria:**
    *   The application presents a login screen on startup if no user is authenticated.
    *   A "Register New Account" option is available on the login screen.
    *   The registration process allows users to create a username and password.
    *   Successfully registered users can log in using their credentials.
    *   Upon successful login, the user's personal chat sessions, models, and settings are loaded and displayed.
    *   Passwords are securely hashed and stored (not plaintext).
    *   There is a clear indication in the UI of the currently logged-in user.

### NF.E1.S2 - Manage User Accounts and Privileges (Admin)
*   **Description:** As an administrator, I want to view, create, edit, and delete user accounts, and assign specific roles or privileges (e.g., create public resources, manage other users' permissions), so I can control access levels and administrative capabilities across the application.
*   **Estimate:** L
*   **Dependencies:** NF.E1.S1 - Register and Log In to Personal Account
*   **Acceptance Criteria:**
    *   A dedicated "User Management" section is accessible only to users with administrator privileges.
    *   Admins can view a list of all registered users, their usernames, and assigned roles/privileges.
    *   Admins can create new user accounts, assigning an initial role (e.g., standard user, admin).
    *   Admins can modify existing user roles and assign/revoke specific privileges (e.g., "Can create public providers", "Can manage other users").
    *   Admins can delete user accounts (with confirmation), ensuring all associated user data is handled according to policy.
    *   At least one admin user is guaranteed to exist or be created during initial setup.

### NF.E1.S3 - Access Shared Public Resources
*   **Description:** As a user, I want certain application resources, such as specific LLM providers, models, or settings profiles, to be marked as "public" and accessible to all users, so I don't have to reconfigure commonly used or institutionally provided resources myself.
*   **Estimate:** M
*   **Dependencies:** NF.E1.S2 - Manage User Accounts and Privileges (Admin)
*   **Acceptance Criteria:**
    *   Users with appropriate privileges (e.g., administrators, or specific roles defined by NF.E1.S2) can mark LLM providers, models, and settings profiles as "public" during their creation or editing.
    *   Public resources are clearly identifiable in the UI (e.g., with a "Public" badge).
    *   All users, regardless of their individual account, can view and select public LLM providers, models, and settings for their chat sessions.
    *   Standard users cannot edit or delete public resources.

## New Features: Advanced

### NF.S16 - Define and Register Custom LLM Tools
*   **Description:** As an advanced user or developer, I want to define and register custom external functions or API calls as "tools" with the server, providing their schema and description, so that compatible LLMs can discover and invoke these tools to extend their capabilities (e.g., web search, custom data retrieval).
*   **Estimate:** L
*   **Acceptance Criteria:**
    *   A UI interface (e.g., a dedicated "Tools" section in settings) allows users to define new tools.
    *   Tool definition includes a unique name, a natural language description, and an OpenAPI-style JSON schema for its input parameters.
    *   The system allows specifying the actual backend endpoint or function that the tool executes.
    *   Registered tools are persisted on the server.
    *   The server exposes a mechanism for LLMs to query available tools and their schemas.

### NF.S17 - LLM Agent Automated Tool Use
*   **Description:** As a user, when interacting with an LLM that has access to configured tools (NF.S16), I want it to automatically identify and use the appropriate tools to perform actions or retrieve real-world information that is relevant to my requests, so I receive more accurate, dynamic, and action-oriented responses without manually invoking tools.
*   **Estimate:** L
*   **Dependencies:** NF.S16 - Define and Register Custom LLM Tools
*   **Acceptance Criteria:**
    *   When an LLM response is requested, the backend determines if a configured LLM model supports tool calling.
    *   If supported, the backend provides the LLM with the user's prompt and the definitions of available tools.
    *   If the LLM decides to call a tool, the backend intercepts this call, executes the tool's defined function/API call with the LLM-provided arguments.
    *   The output of the tool is then fed back to the LLM as additional context.
    *   The LLM generates a final response, potentially incorporating or summarizing the tool's output.
    *   The UI indicates when a tool is being used by the LLM (e.g., "LLM is using [Tool Name]...").

### NF.S18 - Manage Structured Knowledge Base
*   **Description:** As an advanced user, I want to import, view, and manage a structured knowledge base within the server, consisting of entities and relationships between them, so I can provide the LLM with specific, domain-relevant factual information that goes beyond its general training data.
*   **Estimate:** L
*   **Acceptance Criteria:**
    *   A dedicated "Knowledge Base" section in the application settings allows importing data from a structured format (e.g., CSV, JSON, or a custom schema).
    *   The system supports defining and storing entities (e.g., "Person", "Company", "Location") and relationships between them (e.g., "works for", "lives in").
    *   A UI is available to view and potentially edit (basic CRUD) the loaded entities and relationships.
    *   The knowledge base is persisted on the server.

### NF.S19 - LLM Agent Query Knowledge Base
*   **Description:** As a user, when I ask questions that rely on factual information present within the loaded knowledge base (NF.S18), I want the LLM to query and utilize that knowledge base to provide specific and contextually rich answers, including traversing relationships to give deeper insights (e.g., a "level 2" query for related entities).
*   **Estimate:** L
*   **Dependencies:** NF.S18 - Manage Structured Knowledge Base
*   **Acceptance Criteria:**
    *   The backend's LLM context building logic includes a mechanism to provide relevant knowledge base entities and relationships to the LLM based on the user's query.
    *   The LLM can formulate queries to the knowledge base (e.g., "Find relationships for 'John' up to level 2").
    *   The knowledge base service responds to these queries with lists of relevant entities and their relationships.
    *   The LLM incorporates the retrieved knowledge into its final response, explicitly citing information from the knowledge base when appropriate.
    *   For example, asking "Tell me about John" after loading a knowledge base containing "John is 30 years old" and "John lives in New York" will result in the LLM stating those facts.

### NF.S20 - LLM Agent Control over Chat Session Management
*   **Description:** As a user, I want to be able to issue natural language commands to the LLM agent to perform application-level actions related to chat session management (e.g., "Summarize this session," "Create a new session about [topic]," "Delete all messages before [date] in this chat"), so I can manage my conversations more intuitively without direct manual UI interaction.
*   **Estimate:** L
*   **Acceptance Criteria:**
    *   The LLM agent understands and correctly interprets commands related to session operations (e.g., `create_session`, `delete_messages`, `summarize_session`).
    *   Upon receiving such a command, the LLM agent invokes the corresponding backend service method.
    *   Successfully executed commands result in the expected application state changes (e.g., a new session appears, messages are deleted, a summary is provided).
    *   The LLM agent provides feedback to the user regarding the execution of the command (e.g., "Session 'New Topic' created successfully.").
    *   Appropriate confirmation dialogs are triggered for destructive actions (e.g., "Are you sure you want to delete messages?").

### NF.S21 - LLM Agent Control over Application Configuration
*   **Description:** As a user, I want to issue natural language commands to the LLM agent to manage application settings and configurations (e.g., "Show me available models," "Set the temperature for this session to 0.7," "Select OpenAI as the provider for this session"), so I can manage various aspects of the application directly through conversation.
*   **Estimate:** L
*   **Acceptance Criteria:**
    *   The LLM agent understands and correctly interprets commands related to configuration (e.g., `list_models`, `set_session_temperature`, `select_session_provider`).
    *   Upon receiving such a command, the LLM agent invokes the corresponding backend service method or updates frontend state.
    *   Successfully executed commands result in the expected application state changes (e.g., the session's model/settings are updated, a list of models is displayed).
    *   The LLM agent provides feedback to the user regarding the execution of the command (e.g., "Session temperature set to 0.7.").

## Non-Functional Requirements (NFRs)

### NFR.1 - Smooth UI Rendering Performance
*   **Description:** As a user, I expect the chat message list and other UI components to render and update smoothly and efficiently, even when displaying a large number of messages, complex thread structures, or during rapid content streaming, so that the application feels consistently responsive and fluid.
*   **Acceptance Criteria:**
    *   The application maintains a consistent frame rate (e.g., 60 FPS on typical hardware) during scrolling, message additions, and UI state changes in the chat area.
    *   Performance profiling confirms that `ChatMessage` equality checks (e.g., in Compose `LazyColumn`s) are optimized by overriding `equals` and `hashCode` based on `id` and `updatedAt` fields.
    *   No noticeable UI freezes or stutters occur when interacting with long message lists.

### NFR.2 - Minimized Application Install Size
*   **Description:** As a user, I expect the application's overall installation size to be minimized, so it consumes less disk space on my system and downloads or updates quickly.
*   **Acceptance Criteria:**
    *   The final packaged application size (e.g., `.exe` installer size) is demonstrably smaller than previous versions or comparable applications.
    *   The `app` module's Gradle build is configured to avoid loading unnecessary libraries, specifically by removing the `compose.materialIconsExtended` dependency if only a subset of Material Icons is required.
    *   Regular size audits are performed during the build process to detect unexpected bloat.