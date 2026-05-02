# General-Purpose Desktop Chatbot: Project Summary V1.2

## 1. Executive Summary & Project Purpose

The AIChat Desktop App is a private, user-controlled Windows 11 application for interacting with Large Language Models (LLMs) via OpenAI-compatible APIs. It prioritizes user control, data privacy, and persistent, local conversation history. Users provide their own API keys, managing privacy and costs.

Key features include:
*   Message threading for nested replies.
*   Chat session grouping for organization.
*   Configuration of multiple LLM providers (endpoints).
*   Definition of specific LLM models available from providers.
*   Creation and management of settings profiles for fine-tuning model behavior.

These features support complex discussions, extensive history, and flexible LLM access, building a foundation for future features.

**Target Users:** Individuals, power users, developers, and researchers valuing privacy, customization, persistent/editable history, organization (threading, grouping), and flexible LLM configuration.

* * *

## 2. Key Functional Features

*   Core Chat: Send user messages, receive/display assistant responses.
*   Message Threading: Reply to any message to create/navigate nested conversation branches.
*   LLM Provider Config: Set up connections to LLM endpoints (name, URL, type, description).
*   Secure API Key Management: Securely store/use API keys linked to providers.
*   LLM Model Config: Define specific models from configured providers (name, provider link, active status).
*   Persistent Chat History: All sessions, messages (with threads), providers, models, settings, and groups auto-save locally across restarts.
*   Chat Session Grouping: Create, rename, delete groups; assign/unassign sessions to groups. Session list visualizes groups.
*   Message Editing: Modify content of user or assistant messages.
*   Message Removal: Delete individual messages (recursively deletes children).
*   Copy Message: Copy single message text to clipboard.
*   Copy Visible Branch: Copy current thread branch text to clipboard.
*   Model/Settings Selection: Choose specific LLM Model and its Settings Profile for the next response in a session.
*   Editable Model Settings: Customize parameters (temp, max tokens, system message, etc.) for named settings profiles per model.

* * *

## 3. Non-Functional Aspects

*   **Performance:** Responsive UI (threads, grouped lists), fast loading/switching, non-blocking LLM calls.
*   **Security:** Critical focus on secure API key storage (per provider, via custom encryption/DB). Data relies on OS file permissions.
*   **Usability:** Intuitive UI for core features, clear configuration workflows.
*   **Reliability:** Handles errors (network, API, DB) gracefully, maintains data integrity. Validates configurations.
*   **Maintainability:** Layered architecture, Kotlin best practices, clear separation of concerns.
*   **Scalability (Data):** Handles hundreds of sessions (~200 messages each), reasonable groups/providers/models/settings.
*   **Technical Stack Adherence:** Strict use of Kotlin, Compose, Ktor, SQLite (Exposed), custom crypto.
*   **Architectural Flexibility:** Backend structured for potential future separation.

* * *

## 4. Technology Stack & Architecture

**Primary Architecture: Client-Server.**  
The application now uses a client-server architecture: the backend (Ktor server) runs as a separate process, exposing APIs for the frontend (Compose desktop app) to consume. The integrated monolith (single-process UI+backend) remains a future option.

*   Core Technologies:
    *   Frontend: Compose Multiplatform (Kotlin UI, 1.8.2) with Material 3.
    *   Backend/Logic: Kotlin 2.2.0, layered services manage features (config, threads, groups).
    *   API Layer: Ktor 3.2.3 Server (with Resources plugin for type-safe routing) exposes backend API for the frontend; Ktor HTTP Client used for LLM communication.
    *   Database: SQLite (local persistence).
    *   ORM: Exposed 0.61.0 (type-safe SQL). Schema supports all entities (sessions, messages, groups, providers, models, settings, secrets).
    *   Secure Storage: Custom DB-backed encryption for API keys.
    *   DI: Koin 4.1.0.
    *   Functional Programming: Arrow 2.1.2.
    *   Logging: Log4j 2.25.1.
    *   Date/Time: kotlinx.datetime.
    *   Build: Gradle.

*   **Architectural Layers:**
    *   **User Interface (UI):** Compose components. Handles rendering, input, UI state (`ChatViewModel`, `SessionListViewModel`, `SettingsViewModel`). Presents threads (single branch view), grouped sessions, config UI.
    *   **Application Logic (Service Layer):** Core business rules. Orchestrates ops (messaging, LLM interaction, session/group/provider/model/settings management). Manages thread relationships, group assignments, model/settings selection logic. Builds LLM context from session thread/config.
    *   **Data Access Layer (DAL):** Abstracts DB access (SQLite via Exposed). CRUD for all data entities. Handles persistence of thread links, group assignments, provider configs (`apiKeyId`), model/settings links.
    *   **External Services Layer:** Comm with external systems (LLM APIs via Ktor Client using Provider config; Secure Credential Manager for Provider API keys).

*   **Database Data Models (SQLite via Exposed):**
    *   `ChatSessionTable`: Session metadata, links to group, current model, settings, leaf message.
    *   `ChatMessageTable`: Messages, session link, role, content, timestamps, parent/children links, model/settings links (for assistant).
    *   `ChatGroupTable`: User-defined groups for sessions.
    *   `LLMProviderTable`: Provider configs (name, URL, type, description, secure key reference `apiKeyId`).
    *   `LLMModelTable`: Specific model configs (name, provider link `providerId`, active status, display name).
    *   `ModelSettingsTable`: Settings profiles for models (name, model link `modelId`, parameters).
    *   `LLMProviderType`: Enum defining provider types.
    *   `ApiSecretTable`: Encrypted API keys and keys for secure storage.

* * *

## 6. Core Application Flows

*   **Sending a Message (User & Assistant):**
    1.  User types, triggers send (main input or reply action).
    2.  UI captures content, optional `parentMessageId`.
    3.  UI State calls Frontend API Client (`processNewMessage`).
    4.  API Client sends HTTP POST to Ktor server.
    5.  Ktor receives request, calls `ChatService.processNewMessage`.
    6.  `ChatService`: Saves user message (with parentId), updates parent's children list.
    7.  `ChatService`: Builds LLM context (thread history + selected model/settings from session).
    8.  `ChatService`: Retrieves `LLMModel` (from session's `currentModelId`) and `LLMProvider` (from model's `providerId`).
    9.  `ChatService`: Uses E5.S2 to get API key from provider's `apiKeyId`.
    10. `ChatService`: Calls LLM client (using provider's `baseUrl`/type, retrieved key) with context.
    11. `ChatService`: Parses LLM response, saves assistant message (same parentId, using session's `modelId`/`settingsId`).
    12. `ChatService`: Updates user message's children list and session's `currentLeafMessageId`.
    13. `ChatService` returns new user+assistant messages.
    14. Ktor returns messages in HTTP response.
    15. Frontend API Client receives/returns messages.
    16. UI State updates message list, selected branch.
    17. UI displays new messages in thread.

*   **Managing Config (Providers, Models, Settings):** Dedicated UI flows call backend services via API endpoints (`LLMProviderService`, `LLMModelService`, `ModelSettingsService`). Services use DAL for persistence and E5.S1/S3 for credential management.

*   **Security (API Keys):** Secure storage (E5.S1), retrieval for calls (E5.S2), and deletion (E5.S3) tied to `LLMProvider` entities.

* * *

## 7. Critical Security Considerations

*   **API Key Storage:** Absolutely critical. Keys stored encrypted in DB via Credential Manager (`ApiSecretTable`), referenced by `LLMProvider` via alias (`apiKeyId`). Decrypted only in memory for immediate use.
*   **Secure Communication:** External LLM API calls use HTTPS (via Provider `baseUrl`).
*   **Local Data Protection:** SQLite DB security relies on OS file permissions.

* * *

## 8. Dependencies & Constraints

*   OS: Windows 11 (V1.2 target).
*   External: Internet, user-provided access to OpenAI-compatible LLM API (configured as a Provider).
*   Runtime: JVM (bundled/installed).
*   Scope: V1.2 includes core chat, threading, grouping, full LLM config (providers, models, settings).
*   Tech Stack: Strict adherence (Kotlin, Compose, Ktor, SQLite/Exposed, custom crypto).
*   API Comp: Relies on Provider APIs matching selected `LLMProviderType`. Backend maps to correct strategy.