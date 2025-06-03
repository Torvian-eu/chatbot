# General-Purpose Desktop Chatbot: Project Summary (Condensed) V1.1
## 1. Executive Summary & Project Purpose
The AIChat Desktop App is a **private, user-controlled desktop application for Windows 11** designed to interact with Large Language Models (LLMs) via **OpenAI-compatible APIs**. Its core purpose is to provide users with direct control over their LLM access, data, and conversation history, without relying on third-party web services for storage. Users provide their own API keys, ensuring privacy and control over costs.

The application supports **message threading**, allowing users to create nested replies within conversations, and **chat session grouping**, enabling users to organize conversations into custom categories. These features enhance the application's ability to manage complex or multi-topic discussions and large conversation histories while providing a robust foundation for future advanced features like RAG and Tool Calling via MCP.

**Target Users:** Individuals, power users, developers, and researchers who value privacy, customization, and persistent, editable conversation history with LLMs, with strong features for organization via threading and session grouping.
* * *
## 2. Key Functional Features (V1.1 Requirements)
The AIChat Desktop App V1.1 includes the following core functional features:

*   **Core Chat Interface:** Send user messages, receive and display assistant responses.

*   **Message Threading:** Users can reply to individual messages to create nested conversation threads within a session. The UI visualizes these threads by displaying one branch of the conversation tree at a time, allowing users to navigate between branches.

*   **API Key & Endpoint Configuration:** Securely configure and use personal API keys and custom endpoint URLs for OpenAI-compatible LLMs.

*   **Persistent Chat History:** All chat sessions and their messages are automatically saved locally and persist across application restarts. The history includes threading information.

*   **Chat Session Grouping & Organization:** Users can create named groups and assign chat sessions to these groups for organization and navigation in the session list. Sessions can be moved between groups or made ungrouped. Groups can be added, renamed, and deleted.

*   **Message Editing:** Edit the text content of any message (user or assistant) within a session, including messages within threads, with changes saved persistently.

*   **Message Removal:** Delete individual messages from a session, including messages within threads, with persistent removal. The backend handles how deletion affects child messages (e.g., making them orphaned or deleting recursively).

*   **Copy Message Content:** Copy the raw text of a single message (including messages in threads) to the clipboard.

*   **Copy Visible Thread Branch:** Copy the text content of the currently displayed thread branch to the clipboard.

*   **Model Selection:** Choose a specific LLM model and its settings profile for generating the next assistant response within a session or a thread.

*   **Editable Model Settings:** Configure and save multiple named settings profiles (e.g., system message, temperature, max tokens) for each LLM model.

* * *
## 3. Non-Functional Aspects
*   **Performance:** Responsive UI (including rendering potentially complex thread structures and grouped session lists), fast session loading and switching, non-blocking LLM API calls.

*   **Security:** **Critical emphasis on secure API key storage** (via OS credential manager). Chat history relies on OS file permissions. Data model features like message threading and session grouping leverage the underlying secure persistence mechanism.

*   **Usability:** Intuitive UI for core features (new chat, session switching, message management, settings), enhanced by visualizing and interacting with threads and the grouped session list.

*   **Reliability:** Graceful handling of network issues, API errors, and database failures, ensuring data integrity is maintained during persistence operations.

*   **Maintainability:** Layered architecture (UI, business logic, data access, external comms), Kotlin best practices, clear separation of concerns. Logic for message threading and session grouping is encapsulated within the appropriate layers.

*   **Scalability (Data):** Designed to handle hundreds of sessions with up to 200 messages each (total messages including threads) and a reasonable number of groups without significant performance degradation.

*   **Technical Stack Adherence:** Strict adherence to Kotlin, Compose for Desktop, Ktor, and SQLite (via Exposed).

*   **Architectural Flexibility:** Backend logic is structured for potential future extraction into a separate REST API service, including the threading and grouping API endpoints.

* * *
## 4. Technology Stack & Architecture
The application is built as an **integrated desktop monolith**, where the UI and backend logic reside in the same process. The architecture follows a Layered Architecture pattern, structured across `common`, `server`, and `app` Gradle modules.

*   **Core Technologies:**
  *   **Frontend:** Compose for Desktop (Kotlin) for cross-platform UI.
  *   **Backend/Application Logic:** Kotlin, organized into distinct layers (Service, Data Access, External Services). This logic manages application features including message threading and session grouping.
  *   **LLM Communication:** Ktor HTTP Client for interacting with external OpenAI-compatible LLM APIs. The context provided to the LLM client by the service layer considers message threads.
  *   **Local Database:** SQLite for persistent data storage.
  *   **Database ORM:** Exposed (for fluent, type-safe SQL access in Kotlin). The database schema supports threading and grouping.
  *   **Dependency Management:** Gradle.
  *   **Dependency Injection:** Lightweight framework (Koin/manual) for modularity.

*   **Architectural Layers:**
  *   **User Interface (UI):** Compose for Desktop components handle rendering and user input. Manages UI state (`ChatState`, potentially also `SessionListState`). The UI layer and state holder are responsible for receiving message data and presenting it in a threaded view by displaying only a single branch, and for displaying and managing the grouped list of sessions.
  *   **Application Logic (Service Layer):** Contains the core business rules, orchestrating operations like message processing, LLM interaction, session management, and group management. This layer is critical for managing thread relationships (`parentMessageId`, `childrenMessageIds`) and session-to-group assignments via the Data Access Layer, and constructing LLM context based on the thread structure.
  *   **Data Access Layer (DAL):** Abstracts interaction with the SQLite database (CRUD operations). It handles persistence and retrieval of application data including sessions (with groupId), messages (including `parentMessageId` and `childrenMessageIds`), LLM models, model settings, and session groups.
  *   **External Services Layer:** Manages communication with external systems like LLM APIs (via Ktor Client) and the OS Credential Manager. The LLM client receives message context determined by the Service Layer, which may be filtered or ordered based on threads.

* * *
## 5. Data Model Highlights (SQLite via Exposed)
The application's persistent data is stored in a local SQLite database, managed by Exposed. Key entities include:

`ChatSession`: Stores chat metadata (ID, name, timestamps, optional reference to a ChatGroup (groupId: Long?), current model/settings ID, current leaf message ID). Contains the list of all messages belonging to the session.

`ChatMessage`: Stores individual messages (ID, session ID, role, content, timestamps). Includes `parentMessageId` (Long, nullable) to reference the message it's a direct reply to, and `childrenMessageIds` (List) to list the IDs of messages that are replies to this message.

`LLMModel`: Stores LLM provider configurations (ID, name, base URL, type, reference to secure API key storage).

`ModelSettings`: Stores specific settings profiles for models (ID, model ID, name, system message, temperature, max tokens, custom parameters as JSON).

`ChatGroups`: Stores metadata for user-defined groups used to organize sessions (ID, name). ChatSessions reference ChatGroups via the `groupId`.

* * *
## 6. Core Application Flows
*   **Sending a Message (including a Reply):**
  *   User types content and triggers send (via main input or a reply action).
  *   UI captures content and the ID of the message being replied to (if any).
  *   UI State calls the Frontend API Client (`ChatApi.sendMessage`) with the `sessionId`, content, and the optional `parentMessageId`.
  *   Frontend API Client sends an HTTP POST request to the embedded Ktor server, including the parent ID if provided.
  *   Ktor Server receives the request and calls `ChatService.processNewMessage`, passing the `sessionId`, content, and `parentMessageId`.
  *   `ChatService` implementation: Saves the user message (including `parentMessageId`), updates the parent's `childrenMessageIds`, builds LLM context considering threads, calls the LLM client, saves the assistant message (with the same parentId as the user message), and updates the user message's `childrenMessageIds`.
  *   `ChatService` returns the newly created messages.
  *   Ktor Server returns these messages in the HTTP response.
  *   Frontend API Client receives the response and returns the messages to the UI State.
  *   UI State updates its message list.
  *   UI displays the new messages correctly within the threaded structure.

*   **Managing Models/Settings:** Handled via dedicated configuration flows.
*   **Security (API Keys):** Handled via secure storage and retrieval flows.

* * *
## 7. Critical Security Considerations
The primary security focus for this desktop application is:

*   **API Key Storage:** **Absolutely critical.** API keys are stored securely using the operating system's credential manager, with only an alias/reference in the database. Keys are decrypted only in memory for immediate use when needed for API calls.
*   **Secure Communication:** All external LLM API calls utilize **HTTPS** to ensure encrypted communication.
*   **Local Data Protection:** The SQLite database file's security relies on the host OS's file permissions for the logged-in user.

* * *
## 8. Dependencies & Constraints
*   **Operating System:** Windows 11 (V1.1 target).
*   **External Services:** Requires active internet connection and user-provided access to an OpenAI-compatible LLM API endpoint.
*   **Runtime:** Requires a compatible Java Virtual Machine (JVM) installed or bundled.
*   **Scope:** V1.1 includes core chat features, message threading, chat session grouping, and LLM/settings configuration. Future advanced features are deferred.
*   **Tech Stack:** Strict adherence to Kotlin, Compose for Desktop, Ktor, and SQLite (via Exposed).
*   **API Compatibility:** Relies on strict OpenAI API `chat/completions` endpoint compatibility; the way context messages are structured and sent is handled by the Service Layer.
