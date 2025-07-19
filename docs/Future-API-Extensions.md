# Potential Future API Extensions

This document outlines potential API endpoints and features that are considered for future versions of the AIChat Desktop App beyond V1.1. These are not implemented in the current version and are subject to change. They serve as potential scope for future development epics.

## Potential Versioned API (e.g., V2)

A new API version might be introduced for significant changes or new capabilities that break compatibility with the V1 API.

### `GET /api/v2/sessions`

*   **Description:** Placeholder for a potentially revised sessions endpoint in a future API version. Could include features like:
  *   Pagination/Filtering for very large session lists.
  *   Support for syncing sessions if multi-device features are added.
  *   Different data structures or response formats.

## Future Feature Endpoints

These endpoints represent potential features like Retrieval Augmented Generation (RAG), Tool Calling (Function Calling), or other advanced capabilities. They would likely live under the `/api/v1/` path if they don't introduce core breaking changes to V1.

### `POST /api/v1/rag/ingest`

*   **Description:** Endpoint to facilitate the ingestion of user-provided data (documents, text, etc.) into a local RAG index.
*   **Potential Request Body:** Might include `sourceId` (identifier for the source), `content` (the text), maybe `metadata` (file name, URL, etc.).
*   **Potential Response:** Confirmation of successful ingestion or status of the ingestion process.

### `GET /api/v1/tools`

*   **Description:** Endpoint to retrieve a list of tools (functions) that the application can perform and expose to the LLM for function calling. The response would likely include tool definitions in a format compatible with LLM APIs (e.g., OpenAI's function calling schema).
*   **Potential Response:** A list of tool objects, each describing the tool's name, description, and parameters.

### `POST /api/v1/tool/execute/{toolName}`

*   **Description:** Endpoint that the backend (specifically, the LLM interaction logic) might call internally *if* the LLM requests a function call. This endpoint executes the requested tool function within the desktop app context.
*   **Potential Request Body:** Might include the `toolName` and the `arguments` (parsed from the LLM's function call request).
*   **Potential Response:** The result of the tool execution, which would then be formatted and sent back to the LLM as part of the conversation turn.

### `POST /api/v1/sessions/{sessionId}/export`

*   **Description:** Endpoint to export a chat session's history in a specific format (e.g., JSON, Markdown).
*   **Potential Request Body:** Might include `format` type, maybe filtering options.
*   **Potential Response:** The session data in the requested format.

### `POST /api/v1/sessions/import`

*   **Description:** Endpoint to import chat sessions from a file or data structure.
*   **Potential Request Body:** Might include the session data and format information.
*   **Potential Response:** Confirmation of successful import.

---

These two documents now cleanly separate the current API contract for V1.1 from potential future directions.