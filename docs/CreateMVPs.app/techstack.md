# Technology Stack Recommendation: General-Purpose Desktop Chatbot

**Version: 1.0**
**Date: May 21, 2024**

## Technology Summary

This document outlines the recommended technology stack for building a general-purpose desktop chatbot application based on the specified requirements. The architecture follows an integrated desktop application model where the frontend and backend components coexist within a single application process. A key architectural principle is a clean separation of concerns between the UI, application logic, data access, and external service communication layers, enabling potential future extraction of the backend into a standalone service.

The core technologies utilized are:

*   **Frontend:** Compose for Desktop (Kotlin) for building the cross-platform user interface.
*   **Backend/Application Logic:** Kotlin, organized into distinct layers (Application/Service, Data Access, External Services).
*   **Communication:** Ktor HTTP Client for interacting with external LLM APIs, and native process management for interacting with MCP stdio servers.
*   **Database:** SQLite for local, persistent storage of chat history, model configurations, and settings.

This stack leverages Kotlin's multiplatform capabilities and modern libraries to provide a maintainable, testable, and performant application suitable for the specified requirements.

## Frontend Recommendations

*   **Framework:** Compose for Desktop (Kotlin)
    *   **Justification:** Explicitly requested and provides a modern, declarative UI framework in Kotlin, fitting seamlessly with the backend language. It supports Windows 11 as required and offers cross-platform potential for the future.
*   **State Management:** Structured State Holders (e.g., `ViewModel` pattern or similar classes) using Compose's `State` and `MutableStateFlow`.
    *   **Justification:** Managing the complex state of chat sessions (messages, edits, deletions, model settings, loading states) requires a robust pattern. A state holder per screen or major component (like a chat session view) promotes unidirectional data flow, testability, and easier handling of asynchronous operations like fetching data or sending messages.
*   **UI Libraries:**
    *   **Standard Compose Components:** Start with the rich set of built-in Compose components.
    *   **Markdown Renderer:** A Compose-compatible library for rendering markdown text in message displays (e.g., `compose-richtext`).
    *   **File Chooser:** Utilize native OS file choosers via Compose Desktop APIs for future RAG features requiring file selection.

**Implementation Details:**

*   Each chat session should have a dedicated state holder managing its messages, input area state, selected model, and settings overrides.
*   Editing and deleting messages will update the state holder, which then triggers updates to the underlying data layer.
*   Model selection and settings editing UI components will bind to state within the session's state holder, propagating changes to the application logic layer.
*   Copying text involves accessing the platform's clipboard API through Compose Desktop.

## Backend Recommendations

*   **Language:** Kotlin
    *   **Justification:** Explicitly requested and provides a modern, expressive, and performant language that works well for both backend logic and interacting with the frontend.
*   **Framework:** Ktor (Client)
    *   **Justification:** While a Ktor *server* isn't strictly required for the initial integrated app, the Ktor *client* is ideal for making HTTP requests to OpenAI-compatible LLM endpoints as specified. Using Ktor client aligns with the minimum tech stack note and prepares for a potential server extraction.
*   **Architecture:** Layered Architecture
    *   **Application/Service Layer:** Contains the primary business logic. This layer orchestrates actions like creating/loading sessions, sending messages (calling the LLM service), saving/editing/deleting messages (calling the data access layer), managing model configurations, and handling MCP interactions. This layer should contain the logic for parsing MCP config and launching/managing external processes.
    *   **Data Access Layer (DAL):** Abstracts interaction with the SQLite database. Provides clear methods for CRUD operations on sessions, messages, models, and settings.
    *   **External Services Layer:** Handles communication with external dependencies. This includes the Ktor client for LLM APIs and the logic for managing and communicating with MCP processes (handling stdio).
*   **Dependency Management:** Use Gradle (default for Kotlin/Compose).
*   **Dependency Injection:** A lightweight DI framework like Koin or manual constructor injection is recommended to manage dependencies between layers and components, improving testability and modularity.
*   **LLM Communication:** Use Ktor HTTP Client to make requests to OpenAI-compatible endpoints. Model configuration (URL, API key, specific parameters) should be retrieved from the database via the DAL. Handle streaming responses if supported by the API for a better user experience.
*   **MCP (Model Context Protocol):**
    *   Implement logic in the External Services layer to parse the YAML config (e.g., using a library like SnakeYAML or KotlinX Serialization with a YAML format).
    *   Use Kotlin/Java's `ProcessBuilder` to launch external processes specified in the config.
    *   Manage the standard input, output, and error streams (`Process.getInputStream()`, `getOutputStream()`, `getErrorStream()`) for each process.
    *   Implement the MCP protocol over stdio, sending commands (likely JSON) and reading responses (also likely JSON). This will likely require dedicated background threads or coroutines per MCP server process to handle continuous stream reading/writing without blocking.
    *   Integrate the MCP logic into the Application/Service layer so that tool calls or RAG requests can trigger communication with the relevant MCP servers.
*   **Security:** Store sensitive information like API keys securely. Leverage OS-level credential managers (Windows Credential Manager) rather than storing them directly in the database or plain files. The database should store references to these credentials.

## Database Selection

*   **Database Type:** SQLite
    *   **Justification:** Explicitly required and an excellent choice for an embedded, single-user desktop application. It is lightweight, file-based, requires no separate server process, and supports standard SQL.
*   **ORM/Database Library:** SQLDelight
    *   **Justification:** Generates type-safe Kotlin code from SQL statements, providing compile-time checks and seamless integration with Kotlin projects, including those using Kotlin Multiplatform/Compose. It simplifies database interactions compared to raw JDBC.
*   **Schema Approach:** Relational Database Schema
    *   **`sessions` Table:** Stores information about each chat session (e.g., `id` PRIMARY KEY, `title` TEXT, `created_at` DATETIME, `updated_at` DATETIME, `group_id` INTEGER NULL).
    *   **`messages` Table:** Stores individual chat messages (e.g., `id` PRIMARY KEY, `session_id` INTEGER, `role` TEXT (user/assistant), `content` TEXT, `created_at` DATETIME, `updated_at` DATETIME, `model_id` INTEGER NULL, `settings_id` INTEGER NULL, `parent_message_id` INTEGER NULL - for tracking edits/alternatives). Foreign keys should link `session_id` to `sessions.id`.
    *   **`models` Table:** Stores configurations for different LLMs (e.g., `id` PRIMARY KEY, `name` TEXT UNIQUE, `base_url` TEXT, `api_key_ref` TEXT - reference to OS credential manager key, `default_settings_id` INTEGER NULL).
    *   **`model_settings` Table:** Stores different sets of settings for models (e.g., `id` PRIMARY KEY, `name` TEXT, `model_id` INTEGER, `system_message` TEXT, `temperature` REAL, `top_p` REAL, etc.). Foreign key links `model_id` to `models.id`.
    *   **`groups` Table (Optional initial table):** If groups need explicit metadata beyond just an ID (e.g., `id` PRIMARY KEY, `name` TEXT). `session.group_id` would link here.

## DevOps Considerations

*   **Build System:** Gradle
    *   **Guidance:** Use Gradle's standard build tasks for compiling Kotlin code, running tests, and packaging the application. Configure platform-specific tasks for creating installers.
*   **Packaging & Deployment:** Compose for Desktop provides Gradle tasks (`packageDistributionForCurrentOS`) for creating native installers (`.exe` for Windows).
    *   **Guidance:** Utilize these tools. For the initial version, manual distribution of these installers is sufficient. Consider signing the executables for better trust on the target OS.
*   **CI/CD:**
    *   **Guidance:** Implement a Continuous Integration (CI) pipeline (e.g., using GitHub Actions, GitLab CI, or Jenkins) to automatically build the application, run unit and integration tests on every code change. This ensures code quality and prevents regressions. Continuous Deployment (CD) can be added later to automate the creation and potentially distribution (e.g., publishing to a release page) of new installers.
*   **Testing:**
    *   **Guidance:** Write unit tests for the backend logic (Application/Service, DAL, External Services layers), especially for complex parts like MCP communication logic and data handling. Use mocking frameworks where necessary. Consider integration tests to verify interactions between layers and with the database (using an in-memory SQLite database for tests). Compose for Desktop also offers testing utilities for UI tests.

## External Services

*   **LLM Providers:** OpenAI, OpenRouter, Google, or any other service providing an OpenAI-compatible API endpoint.
    *   **Usage:** The application communicates directly with the user-configured endpoint using the provided API key via the Ktor HTTP Client.
*   **Model Context Protocol (MCP) Servers:** External processes launched by the application.
    *   **Usage:** The application reads user-provided YAML configuration, launches the specified `stdio` processes, and communicates with them over standard input/output using the MCP. These are user-configured *dependencies* rather than external SaaS services.
*   **Operating System Credential Manager:** Windows Credential Manager (and equivalents on other platforms if supported later).
    *   **Usage:** Used for secure storage of sensitive API keys provided by the user, referenced by the application rather than stored in its own files or database in plaintext.

