# AIChat Desktop App - Product Requirements Document

## 1. Document Header

*   **Product Name:** AIChat Desktop App
*   **Version:** 1.0
*   **Date:** May 21, 2025
*   **Author:** [Your Name/Team Name]

## 2. Executive Summary

The AIChat Desktop App is a native Windows 11 application designed to provide users with a powerful, local, and user-controlled interface for interacting with Large Language Models (LLMs). Unlike web-based solutions, this application empowers users by allowing them to use their own OpenAI-compatible API keys, ensuring data privacy and control over model access and expenditure. The core functionality revolves around persistent chat sessions with robust message management capabilities, including editing, deletion, and copying. Built using Kotlin, Compose for Desktop, Ktor, and SQLite, the app will offer a stable foundation for LLM exploration and interaction, with a clear path for future expansion into areas like RAG and advanced tool calling via the Model Context Protocol (MCP).

## 3. Product Vision

The vision for the AIChat Desktop App is to be the preferred desktop client for individuals who value privacy, control, and flexibility in their interactions with LLMs. We aim to provide a reliable, performant application that makes managing conversations with multiple models easy and intuitive. By supporting user-provided API keys and storing data locally, we put the user in control of their data and LLM access. The initial version will focus on core chat functionalities and data persistence, laying the groundwork for future features like advanced context handling (RAG, MCP) and UI customization, ultimately creating a highly adaptable tool for developers, writers, researchers, and power users leveraging AI in their daily workflows.

*   **Purpose:** To provide a private, user-controlled desktop application for interacting with various LLMs via OpenAI-compatible APIs.
*   **Users:** Developers, researchers, writers, students, and general power users who utilize LLMs and require persistent history, editing capabilities, and control over their API access and data.
*   **Business Goals (Initial V1 Focus):**
    *   Launch a stable application meeting all minimum requirements.
    *   Establish a solid technical foundation using the specified stack.
    *   Ensure reliable persistence and management of user data (chat history, model configurations).
    *   Provide a positive user experience for core chat and editing workflows.
    *   Lay the technical groundwork for future feature integration (e.g., modularity for potential API exposure, plugin architecture considerations).

## 4. User Personas

**Persona: Anya, The AI-Powered Developer**

*   **Profile:** A software developer in her late 20s. Uses LLMs daily for code snippets, debugging help, understanding complex concepts, and brainstorming. Works with sensitive code sometimes.
*   **Goals:**
    *   Quick access to LLM help without leaving her desktop environment.
    *   Keep track of useful code snippets or explanations from the AI.
    *   Refine prompts or edit AI responses to integrate them perfectly into her work.
    *   Experiment with different models for different tasks (coding vs. writing documentation).
    *   Ensure her conversations are private and not easily accessible to third parties.
*   **Pain Points with Current Tools:**
    *   Web UIs lack persistent, easily searchable history.
    *   Copying formatted code/text from web interfaces can be clunky.
    *   No easy way to edit past messages or the AI's response if it was close but not perfect.
    *   Concerned about what data is stored serverside by online providers.
    *   Switching between models or API providers isn't seamless.

**Persona: Ben, The Student Researcher**

*   **Profile:** A university student in his early 20s working on a research paper. Uses LLMs for outlining, brainstorming ideas, summarizing concepts, and drafting sections. Needs to keep track of different lines of inquiry.
*   **Goals:**
    *   Maintain separate threads of conversation for different research topics or paper sections.
    *   Go back and review or revise previous discussions.
    *   Easily copy relevant information or drafts into his document.
    *   Ensure his research conversations are saved reliably.
*   **Pain Points with Current Tools:**
    *   Web interfaces often merge unrelated conversations or lose history.
    *   Difficult to organize chats related to a specific topic.
    *   Editing past prompts to refine an answer is often impossible or awkward.
    *   Copying large amounts of text can be inefficient.

## 5. Feature Specifications

### 5.1. Core Chat Interface

*   **User Story:** As a user, I want to type a message and receive a response from an LLM so that I can have a conversation.
*   **Acceptance Criteria:**
    *   There is a primary input area for typing user messages.
    *   Pressing Enter (or clicking a send button) sends the message.
    *   The user message appears in the chat history area.
    *   A loading indicator is displayed while waiting for the LLM response.
    *   The assistant's response appears in the chat history area below the user message.
    *   Messages are ordered chronologically within a session.
*   **Edge Cases:**
    *   Network connection failure during sending or receiving.
    *   LLM API returns an error (e.g., rate limit, invalid request).
    *   Empty user message submission.
    *   Very long user messages or assistant responses.

### 5.2. User Provided OpenAI Compatible API Key

*   **User Story:** As a user, I want to securely provide my own API key for an OpenAI-compatible service so that I can use my own account and models.
*   **Acceptance Criteria:**
    *   There is a dedicated settings section or dialog for managing API configurations.
    *   The user can input an API key and an optional API endpoint URL (defaults to OpenAI).
    *   API keys are stored securely (encrypted) in the local database.
    *   The application uses the configured key and endpoint for LLM requests.
    *   The user can configure multiple API endpoints/keys, associating them with specific model configurations.
*   **Edge Cases:**
    *   User enters an invalid or expired API key.
    *   User enters an incorrect endpoint URL.
    *   Database fails to save the key securely.
    *   Key permissions are insufficient for the requested model.

### 5.3. Persistent Chat Session History

*   **User Story:** As a user, I want my chat conversations to be automatically saved so that I can close and reopen the app and continue where I left off.
*   **Acceptance Criteria:**
    *   Every conversation thread is automatically saved as a distinct session.
    *   Closing and reopening the application displays a list of previous chat sessions.
    *   Selecting a session from the list loads the full message history for that session.
    *   The currently active session is automatically saved as messages are added or edited.
*   **Edge Cases:**
    *   Database saving fails during a session.
    *   App crashes before saving the latest message.
    *   Database file is corrupted.
    *   Managing a very large number of sessions.

### 5.4. Groupable Chat Sessions

*   **User Story:** As a user, I want to organize my chat sessions into groups or folders so that I can easily find related conversations.
*   **Acceptance Criteria:**
    *   There is a UI mechanism (e.g., folders, tags UI) to associate chat sessions with groups.
    *   The main session list can be filtered or navigated by group.
    *   Users can create, rename, and delete groups.
    *   Sessions can be moved between groups.
    *   Deleting a group prompts the user on whether to delete the sessions within it or ungroup them.
*   **Edge Cases:**
    *   Deleting a group containing sessions.
    *   Renaming a group currently in use.
    *   Sessions belonging to a non-existent group (e.g., after corruption).
    *   Empty groups.

### 5.5. Editable Chat Messages (User and Assistant)

*   **User Story:** As a user, I want to be able to edit any message in a chat session (both mine and the AI's) so that I can correct errors, refine prompts, or adjust AI responses.
*   **Acceptance Criteria:**
    *   Each message in the chat history has an "Edit" option (e.g., button, context menu).
    *   Clicking "Edit" makes the message content editable in the UI.
    *   Saving edits updates the message in the database.
    *   The UI reflects the edited message immediately.
    *   Editing a user message should ideally invalidate subsequent assistant messages in that thread (or mark them as based on a previous version) – *V1 Scope: Simply update the message content in the DB/UI. More complex "regenerate from here" is V2.*
*   **Edge Cases:**
    *   Attempting to edit a message while a new response is being generated.
    *   Saving an empty message (should perhaps delete?).
    *   Database error during save.
    *   Concurrency issues if multiple edits were attempted simultaneously (unlikely in single-user desktop app, but good to consider).

### 5.6. Removable Chat Messages

*   **User Story:** As a user, I want to be able to delete individual messages from a chat session so that I can clean up the history or remove irrelevant parts.
*   **Acceptance Criteria:**
    *   Each message in the chat history has a "Delete" option (e.g., button, context menu).
    *   Clicking "Delete" removes the message from the UI.
    *   The message is marked as deleted or removed from the database.
    *   Subsequent messages should ideally re-render correctly after a message is removed.
    *   A confirmation prompt should appear for deletion.
*   **Edge Cases:**
    *   Deleting the first message (potentially the system message or initial prompt).
    *   Deleting multiple messages quickly.
    *   Attempting to delete a message while a new response is being generated.
    *   Database error during deletion.

### 5.7. Copy Single Message Text

*   **User Story:** As a user, I want to copy the raw text content of a single message to my clipboard so that I can easily use it elsewhere.
*   **Acceptance Criteria:**
    *   Each message in the chat history has a "Copy" option (e.g., button, context menu).
    *   Clicking "Copy" copies the raw text content (including any markdown source if applicable, *though markdown rendering is V2*) of that specific message to the system clipboard.
    *   A visual indicator confirms the text has been copied (e.g., tooltip, status bar message).
*   **Edge Cases:**
    *   Copying an empty message.
    *   Copying a very long message (clipboard size limits?).
    *   Clipboard access issues on the OS.

### 5.8. Copy Entire Chat Session Text

*   **User Story:** As a user, I want to copy the raw text of an entire chat session to my clipboard so that I can save or share it easily.
*   **Acceptance Criteria:**
    *   There is an option (e.g., button, menu item) within a chat session view to "Copy Full Session".
    *   Clicking this option copies the raw text content of all messages in the current session, ordered chronologically, to the system clipboard.
    *   The copied text includes some basic formatting to indicate speaker (e.g., "User: [message]", "Assistant: [message]").
    *   A visual indicator confirms the text has been copied.
*   **Edge Cases:**
    *   Copying a very long session (clipboard size limits, performance).
    *   Copying an empty session.
    *   Clipboard access issues on the OS.

### 5.9. Select Model for Next Response

*   **User Story:** As a user with multiple configured models, I want to select which model is used for the next response in a session so that I can compare models or use the most appropriate one.
*   **Acceptance Criteria:**
    *   There is a UI element (e.g., dropdown, button group) visible near the input area or associated with the session settings that shows the currently selected model.
    *   The user can click this element to choose from a list of configured models.
    *   The selected model is used for the *next* message interaction initiated by the user in that session.
    *   The model choice persists for the session until changed.
*   **Edge Cases:**
    *   No models are configured.
    *   Selected model becomes unavailable (e.g., API key revoked, service down).
    *   User attempts to change model mid-response generation (should apply to the *next* interaction).

### 5.10. Editable Model Settings (per Model Configuration)

*   **User Story:** As a user, I want to customize settings like the system message and parameters (e.g., temperature, max tokens) for my configured models so that I can fine-tune their behavior.
*   **Acceptance Criteria:**
    *   The settings section allows associating multiple distinct configurations (sets of settings) with each configured model endpoint.
    *   Users can create, rename, and delete settings configurations.
    *   Users can edit a text field for the "System Message" within a settings configuration.
    *   Users can edit common LLM parameters (e.g., temperature, max tokens, top_p, frequency_penalty, presence_penalty).
    *   Changes to a settings configuration are saved to the database.
    *   When selecting a model for a session, the user should implicitly or explicitly select a *settings configuration* associated with that model.
    *   The selected settings configuration for a session persists until changed.
*   **Edge Cases:**
    *   Invalid parameter values entered (e.g., temperature outside 0-2).
    *   Saving settings fails.
    *   Default settings needed if no custom settings are configured.
    *   Applying settings mid-session (should apply to *next* message exchange).

## 6. Technical Requirements

*   **Platform:** Windows 11 (Target OS for V1). Consideration for future macOS/Linux support using Compose for Desktop's capabilities would be beneficial during architecture design but is not required for V1.
*   **Architecture:**
    *   Integrated Monolith: Frontend (Compose for Desktop) and Backend (Kotlin business logic, Ktor client for LLM, SQLite) run in the same process.
    *   Backend Logic: Responsible for managing chat sessions, messages, model configurations, database interactions, and LLM API calls via Ktor.
    *   Frontend: Handles UI rendering and user input, communicating with the backend logic.
    *   Future API Consideration: Design backend modules (e.g., `ChatService`, `ModelService`, `DatabaseService`) with clean interfaces to facilitate later exposure via a Ktor server if needed.
*   **LLM Interaction:**
    *   Use the Ktor HTTP client to communicate with OpenAI-compatible API endpoints.
    *   Must support the standard OpenAI `chat/completions` endpoint structure.
    *   Handle API keys securely (encrypted storage, avoid logging).
    *   Implement handling of standard LLM parameters (temperature, max_tokens, system message, etc.).
    *   Support both streaming and non-streaming API responses, though streaming is preferred for perceived performance in the UI.
*   **Data Storage (SQLite):**
    *   Utilize a SQLite database for all persistent data.
    *   **Tables:**
        *   `models`: `id` (PK), `name` (TEXT), `endpoint_url` (TEXT), `api_key` (TEXT, encrypted), `default_settings_id` (FK to model_settings, nullable).
        *   `model_settings`: `id` (PK), `name` (TEXT), `model_id` (FK to models), `system_message` (TEXT), `temperature` (REAL), `max_tokens` (INTEGER, nullable), `top_p` (REAL, nullable), `frequency_penalty` (REAL, nullable), `presence_penalty` (REAL, nullable), `other_params_json` (TEXT, store less common/model-specific params).
        *   `chat_sessions`: `id` (PK), `created_at` (DATETIME), `updated_at` (DATETIME), `title` (TEXT, nullable), `group_id` (FK to groups, nullable).
        *   `chat_messages`: `id` (PK), `session_id` (FK to chat_sessions), `sender` (TEXT, e.g., 'user', 'assistant'), `content` (TEXT), `timestamp` (DATETIME), `order_index` (INTEGER, for message order within session), `is_edited` (BOOLEAN, default FALSE), `is_deleted` (BOOLEAN, default FALSE - soft delete).
        *   `groups`: `id` (PK), `name` (TEXT), `created_at` (DATETIME). (Could potentially use a simpler flat structure or tags without a dedicated table initially for grouping).
    *   **Data Security:** API keys *must* be stored encrypted in the database.
    *   **Migrations:** Plan for database schema evolution if future features require it.
*   **Frontend (Compose for Desktop):**
    *   Implement UI elements for chat view, session list, settings dialogs (API keys, model settings).
    *   Handle user input and trigger backend logic.
    *   Display chat messages, including basic text formatting.
    *   Implement message editing and deletion UI interactions.
    *   Handle state management for chat sessions and messages efficiently.
    *   Implement copy-to-clipboard functionality using OS APIs.
*   **Backend/Frontend Integration:**
    *   Since they are in the same process, direct Kotlin function calls can be used initially.
    *   Design services (Kotlin classes) in the backend module (`backend/src/main/kotlin`) that the frontend module (`frontend/src/main/kotlin`) can call.
    *   Database access should be abstracted within the backend module.
    *   LLM calls should be handled by a dedicated service in the backend.

## 7. Implementation Roadmap

This roadmap outlines the planned development phases.

**Phase 1: Minimum Viable Product (V1)**

*   **Focus:** Implement all minimum requirements and establish the core architecture and data persistence.
*   **Features Included:**
    *   Basic Chat Interface (Send/Receive messages)
    *   User Provided API Key Input & Secure Storage
    *   Persistent Chat Session History (Auto-save, Load session list, Load selected session)
    *   Editable Chat Messages (User & Assistant)
    *   Removable Chat Messages (Soft delete or actual removal, confirm dialog)
    *   Copy Single Message Text to Clipboard
    *   Copy Entire Chat Session Text to Clipboard
    *   Select Model for Next Response (Dropdown/Selector)
    *   Editable Model Settings (System Message, Parameters, multiple settings configs per model)
    *   Basic Session Grouping (e.g., a single level of folders/groups UI)
    *   SQLite Database Setup and Schema for V1 features
    *   Ktor Client for OpenAI-compatible API calls (non-streaming first, then add streaming)
    *   Compose for Desktop UI implementation for all V1 features.
*   **Goal:** Release a stable V1 app that fulfills the core need of private, persistent, and editable LLM chat on the desktop.

**Phase 2: Enhancements & Foundation for Expansion (V1.1 - V1.x)**

*   **Focus:** Improve usability based on initial feedback and build out foundational elements for major future features.
*   **Potential Features (Prioritization based on feedback):**
    *   Markdown Rendering in Chat Messages (from Optional list)
    *   Copy Code Snippets to Clipboard (from Optional list, dependent on Markdown rendering)
    *   Generate another answer from the same (or edited) prompt (from Optional list)
    *   Refine UI/UX for editing and message management.
    *   Better error handling and user feedback for API issues.
    *   Performance improvements for large sessions/histories.
    *   Improved Model Settings UI/Discoverability.
    *   Refining the grouping feature (e.g., drag-and-drop, search within groups).

**Phase 3: Advanced Context & Tooling (V2)**

*   **Focus:** Integrate more sophisticated methods for providing context to the LLM and interacting with external tools. Requires significant architectural work.
*   **Potential Features:**
    *   Attach files (Text/PDF/Image - basic RAG implementation) (from Optional list)
    *   MCP Client Implementation (stdio type first) (from Optional list)
    *   Tool calling integration using user-provided MCP server configs (from Optional list)
    *   More advanced RAG features (chunking, indexing UI).

**Phase 4: Data Management & Customization (V2+)**

*   **Focus:** Features related to managing data in bulk and allowing user customization.
*   **Potential Features:**
    *   Import/export multiple chat sessions (as JSON) (from Optional list)
    *   Collapse/Expand messages in a session (from Optional list)
    *   Chat session Title / Tags generation (using cheap LLM) (from Optional list)
    *   Plugin System (from Optional list)
    *   UI Settings Customization (CSS, layout) (from Optional list)
    *   Cross-platform support (macOS, Linux - if deemed valuable after Windows launch).

This roadmap is subject to change based on development progress, user feedback, and evolving priorities. V1 is the absolute priority before any optional features are considered for implementation.

