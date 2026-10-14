# New feature ideas

## Basic features

### Make chat session copyable to file/clipboard as JSON (for importing into other apps)

### Make chat session importable from file/clipboard (for ChatGPT export format)

### Allow certain features to be enabled/disabled on a per-user basis (*Requires user preference feature)
The user should be able to configure this in their profile.

### Allow user to see LLM metadata for assistant responses

### Allow admin to create new accounts for other users.
Users will be forced to change their password on first login.

### Allow user to specify chat group when adding a new session
Currently, the user can only add a new session to the "Ungrouped" group. In the future, we should allow the user to specify a group when adding a new session.

### Add model provider test button
- Add a "Test" button to the add/update model provider configuration form.
- The test button should call a new endpoint on the server, which takes the provider configuration and tries to list the available models.
- The server should return the list of models, which are then displayed in the form. (for instance: "Test successful! 35 models available: gpt-3.5-turbo, gpt-4, gpt-5, etc...")

### Add model test button
- Add a "Test" button to the add/update model configuration form.
- The test button should call a new endpoint on the server, which takes the model configuration and tries to send a test message to the model.
- The server should return the response from the model, which is then displayed in the form. (for instance: "Test successful! Model answered: Hello, world!")

### Allow user to auto-approve future tool calls, when the assistant calls a tool during a chat session

### Allow user to call (MCP) tools manually
Normally, only the assistant can call tools. However, it sometimes is useful for the user to be able to call tools manually, for instance to debug or demonstrate a tool.
- A dialog should be presented with all the arguments for the tool, which can be filled in manually.
- The dialog should allow the user to select a tool from the list of available tools for the current session.

### (done) Display reasoning/thinking tokens in UI
- reasoning tokens are currently only produced by the Responses API
- a new subtype in `ChatStreamEvent` for streaming reasoning tokens should be added
- reasoning tokens are persisted in `AssistantMessage.reasoningItems`, which could be used in the UI
- ChatScreen: Each assistant message item should get a separate section for reasoning output, when available. It should be hidden (collapsed) by default, and expanded when clicked on.
- ChatScreen: reasoning text should be added incrementally, while streaming (similar to the way its done for regular assistant message streaming content)
- ChatScreen: While streaming, a "thinking" indicater should be shown, or something similar (non-animated)
- ChatScreen: In case of encrypted tokens, displaying the content would not be useful. Instead, some information text should be displayed that the tokens are encrypted. 
- ChatScreen: display the reasoning summary, if available.

---




## Intermediate features

### User profile customization (name, avatar, etc.)

### User settings customization (theme, language, feature toggles, etc.)

### Chat message content formatting (Markdown, HTML, etc.)

### Select available models for adding by querying LLM provider API

### Add support for MCP's resources and prompts
- The user should be able to retrieve a resource or prompt, if currently active for the chat session. (The MCP server will be contacted to retrieve the resource or prompt)
- When at least one resource or prompt is active, the UI should indicate this by showing an icon button with badge (indicating the total number of active resources and prompts) underneath the chat input area. Clicking the icon should open a popup with a list of all active resources and prompts (with name, description and type). Clicking an item in the list should copy it's content to the chat input area, or, if the item requires arguments it should open a dialog to enter the arguments, and then copy the formatted resource or prompt to the chat input area.

### Allow user to queue multiple messages while a request is in progress
- The user should be able to queue multiple messages while a request is in progress. The queue should be processed in the order they were received.
- A queue message button should appear in the input area when a request is in progress. Clicking the button should add the current message to the queue. The button should be visible for the duration of the request.



### Add skills to agent roles

- (future extension) `SkillsInstruction`: contains information for the agent about how to use a skill, and which skills are available. This instruction is dynamic and is derived from the property `AgentRole.skills`.

**Skills**
Agent Skills are a lightweight, open format for extending AI agent capabilities with specialized knowledge and workflows. See: https://agentskills.io/home.
The properties for class `Skill` are:
- `id`: type: Long
- `name`: type: String, same name as directory (for example: workspace/skills/<name>)
- `description`: type String

### Allow agents to be ran by a background service
- `operator type`: type: `OperatorType`. An operator manages the agent's instance. It's either the client app or a dedicated agent operator (running as a background service; similar to the worker, which stays connected to the server (via websocket) at all times). The operator must be registered in the worker as a trusted signer. Every tool call request for the worker is signed by the operator. (The client app already has this capability.)

### Add system message property to chat session object
- The system message is derived from the session's agent role's instructions list, using the `SystemPromptComposer`.
- Currently, the system message is re-composed on every turn in `DefaultConversationTurnPreparationService.prepareNewMessageTurn`. This behavior could be replaced by on-demand composition.
- Lifecycle: new chat session created (with non-null agent role), session's agent role updated with non-null value, current agent role itself is updated, or manual system message sync via UI -> load system message; in all other cases the session's system message stays the same
- Main benefit: The system message for a chat session stays the same for as long as the user wants, which helps increase the (read) cache hit rate for LLM requests.

### Add LLM tool for creating a user dialog
This tool enables an LLM to gather information interactively by asking targeted questions or presenting options. It supports workflows such as generating requirements documents, specification documents, bug reports, decision-support dialogs, troubleshooting flows, configuration wizards, and other tasks that benefit from structured user interviews.

### (done) Add a status indicator in chat session list panel
For each chat session, show a different visual indication in each of these situations:
- agent is busy (tool call loop is running)
- agent is requesting user input (e.g., tool call approval or user dialog)
- agent has just completed a task with success (removed when chat session is selected)
- agent has just completed a task with failure (removed when chat session is selected)

### Allow users to call an LLM tool manually
- Any tool can be called without using an LLM
- Selecting a tool brings up a dialog with all the form inputs that the tool needs. 

### Add a built-in agent role for creating other agent roles
Add a built-in "Agent Role Creator" agent role. 
- Contains instructions for interviewing the user about what agent role(s) to create.
- The interview questions can be open, or multiple choice. Multiple choice questions are preferred, because it requires less input from the user and therefore is faster.
- Can be used to create a set of agent roles, to form a workflow. In case of a workflow, usually one agent is the orchestrator (or planner), and the others perform tasks that the orchestrator requests (via the `spawn_agent` tool).
- The Agent Role Creator can make use of the following server tools: `list_agent_roles`, `read_agent_role`, `create_agent_role`, `update_agent_role`, `list_models`, `list_model_settings`, `list_tools`, `read_tool`, `insert_agent_role_instruction`, `edit_agent_role_instructions`, and `remove_agent_role_instruction`. (see ServerBuiltInToolCatalog.kt)
- In order to get a better idea of what capabilities an agent can be given, the Agent Role Creator should start by examining which tools are available.
- In the final step (upon user approval), the Agent Role Creator creates the agent role(s), according to the information gathered from the interview.

### Add server built-in tools for chat session management
All tools are scoped by user to prevent unauthorized access. The tools are described below.

#### Tool `list_chat_groups`:
**inputs:** none
**output:** Json array of objects with properties `id`, `name`, `createdAt` (See class `ChatGroup`)

#### Tool `read_chat_group`:
**inputs:** `id`
**output:** Json object with properties `id`, `name`, `createdAt` (See class `ChatGroup`)

#### Tool `create_chat_group`:
**inputs:** `name`
**output:** Json object with properties: `id`, `name`, `createdAt` (See class `ChatGroup`)

### Tool `update_chat_group`:
**inputs:**
- `id`
- `name`
**output:** message confirming update was successful

#### Tool `delete_chat_group`:
**inputs:** `id`
**output:** message confirming deletion was successful

#### Tool `search_chat_groups`:
**inputs:** `query` (string); regular expression; searches for chat groups whose name matches the query.
**output:** Json array of objects with properties `id`, `name`, `createdAt` (See class `ChatGroup`)

#### Tool `list_chat_sessions`:
**inputs:**
- `group_id`: optional; if null, lists sessions from all chat groups
- `page_range`: optional; [start, end), matching Python slice semantics (similar to `read_text_file` tool in worker module); if null, lists all pages
- `page_size`: defaults to 50; not applicable when `page_range` is null
- `sort_by`: enum of `date_created`, `date_last_updated`, `name`; default: `date_last_updated`
- `sort_direction`: enum of `asc`, `desc`; default: `desc`
**output:** Json object with properties:
- `chat_sessions`: Json array of objects with properties: `id`, `name`, `createdAt`, `updatedAt`, `groupId` (See class `ChatSessionSummary`)
- `current_page` (zero-based index),
- `total_pages` 

#### Tool `read_chat_session`:
**inputs:** `id`
**output:** Json object with properties:
- `id`
- `name`
- `createdAt`
- `updatedAt`
- `groupId`
- `agentRoleId`
- `currentLeafMessageId` (See class `ChatSession`)
- `messageTurns`: number of message turns; a message turn is a sequence of messages that are part of the same conversation turn. For example, a user message followed by a reasoning message followed by an assistant message counts as one turn. Though since we only count turns we dont have to consider tool call, tool result or reasoning messages. That's because a turn must include at least a user or assistant message (and at most one of each).

### Tool `create_chat_session`:
**inputs:**
- `name`
- `group_id`: optional; if null, creates session in default group
- `agent_role_id`: optional; if null, creates session with no agent role
**output:** Json object with properties: `id`, `name`, `createdAt`, `updatedAt`, `groupId`, `agentRoleId`, `currentLeafMessageId`(always null, since the session is new and has no messages yet) (See class `ChatSession`)

### Tool `update_chat_session`:
**inputs:**
- `id`
- `name`: optional; if null, name is not updated
- `group_id`: optional; if null, group is not updated
- `agent_role_id`: optional; if null, agent role is not updated
**output:** message confirming update was successful

#### Tool `delete_chat_session`:
**inputs:** `id`
**output:** message confirming deletion was successful

#### Tool `search_chat_sessions`:
**inputs:**
- `query` (string); regular expression; searches for chat sessions whose name matches the query
- `group_id`: optional; if null, searches sessions from all chat groups
**output:** Json array of objects with properties: `id`, `name`, `createdAt`, `updatedAt`, `groupId`, `agentRoleId`, `currentLeafMessageId`

#### Tool `list_chat_messages`
**inputs:** 
- `session_id`
- `leafMessageId` (default: current leaf)
- `include_tool_call_messages` (default: false)
- `include_tool_result_messages` (default: false)
- `include_reasoning_messages` (default: false)
- `exclude_content` (default: false)
- `turn_range`: optional range of message turns; [start, end), matching Python slice semantics (similar to `read_text_file` tool in worker module); if null, lists all message turns
**output:** Json object with properties:
- `session_id`
- `leafMessageId`
- `message_turns`: ordered list of message turns; a message turn is ordered as follows: user, reasoning, assistant, tool call, tool result; a turn may exclude one or more items (each turn should at least include a user **or** an assistant message); Json array of message turn objects:
  - `turn_index`: zero-based
  - `messages`: Json array of message objects
    - common properties for all message types: `role` (enum of user, reasoning, assistant, tool call, tool result)
    - extra properties for user message: `id`, `content`, `createdAt`, `updatedAt`
    - extra properties for reasoning message: `content`
    - extra properties for assistant message: `id`, `content`, `createdAt`, `updatedAt`, `modelId`, `modelSettingsId`, `agentRoleId`
    - extra properties for tool call: `id`, `toolDefinitionId`, `toolName`, `input` (json string); See class `common.models.tool.ToolCall`
    - extra properties for tool result: `output` (json string), `status`, `errorMessage`, `denialReason`, `executedAt`, `durationMs`, `errorCode`, `errorDetails`; See class `common.models.tool.ToolCall`; properties with value null are omitted
- `start_turn`: turn index; inclusive
- `end_turn`: turn index; inclusive
- `total_turns`: total turns in chat session

#### Tool `read_chat_message`
**inputs:** `message_id`
**output:** Json object with properties:
- `role` (enum of user, reasoning, assistant, tool call, tool result)
- extra properties for user message: `id`, `content`, `createdAt`, `updatedAt`
- extra properties for reasoning message: `content`
- extra properties for assistant message: `id`, `content`, `createdAt`, `updatedAt`, `modelId`, `modelSettingsId`, `agentRoleId`
- extra properties for tool call: `id`, `toolDefinitionId`, `toolName`, `input` (json string); See class `common.models.tool.ToolCall`
- extra properties for tool result: `output` (json string), `status`, `errorMessage`, `denialReason`, `executedAt`, `durationMs`, `errorCode`, `errorDetails`; See class `common.models.tool.ToolCall`; properties with value null are omitted

#### Tool `search_chat_messages`
**inputs:**
- `session_id`: optional; if null, searches messages from all chat sessions
- `role`: optional; if specified, only searches for messages of the specified role (user, assistant)
- `query`: regular expression; searches for user or assistant messages whose content matches the query
**output:** Json array of message objects with the same properties as `read_chat_message` tool

Notes:
- file references will be deprecated in the near future, and are not considered in chat messages
- tool call and tool result pairs have the same id


### (done) Allow user to compact a conversation on demand
- compact the currently displayed thread only

### Allow user to queue a message while an assistant's turn is still in progress
The user message should be injected into the conversation as soon as possible. (When the current tool step completes)

### Allow LLM to use tools which require a secret key
- The LLM should never be able to see the secret key itself.
- Let the user store predefined keys in the server's database, each having a unique (predefined) name. The name could be fixed for the tool, or the LLM could choose from several names using an enum parameter. (For example: GITHUB_PAT_FOR_PACKAGE_UPLOAD)
- It allows the LLM to do things like these:
  - Inject a secret key via the run_command tool, using a predefined template string. For example: "<<GITHUB_PAT_FOR_PACKAGE_UPLOAD>>" 
- `ApiSecretTable` and `ApiSecretOwnersTable` could be used for this, with some modifications: use a standard Long `id` field as primary key instead of `alias` (optional, but preferred to keep referential integrity); add a unique `name` field to `ApiSecretOwnersTable`

### Import/export project
- Allow user to import/export a project from the UI.
- JSON format
- Import: show textfield to paste json code into.
- Export: show copyable textfield with json code.
- Should check for unique name conflicts with existing roles before importing

### Add `get_chat_session_summary` server LLM tool
**inputs:** `id`
**output:** session summary

### (done) Make conversation compaction configurable per model preset
- two fields should be added to the model preset database table: enabled flag (default: true) & threshold value (default: null) 
- If the threshold value is null, the threshold value from user preferences should be used as fallback.

### (done) Add usage stats for assistant messages
- persist usage stats in assistant message table as a (nullable) JSON text field `usage_stats`. The `AssistantMessage` class should get a new property `usageStats` of type `UsageStats?`.
- The new data class `UsageStats` should get the following properties: input tokens(total), output tokens(total), total tokens (input + output), reasoning tokens (optional), cached tokens(optional), cache write tokens (optional). All these items are part of the Responses API.
- ChatScreen: display a dialog with usage stats (and other info) when the message item header (`<model name>:`) is clicked on. Other info could include: model settings name, cost information (out of scope for this feature).
- adapt `LLMStreamChunk.UsageChunk` and `LLMCompletionResult.UsageStats` (rename `promptTokens` to `inputTokens` and `completionTokens` to `outputTokens`, and add the new properties mentioned above)
- The client app should **not** need to process a separate usage stats event. The data should be available in `ChatStreamEvent.AssistantMessageEnd.assistantMessage` or `ChatEvent.AssistantMessageSaved.assistantMessage`.


### add stateful mode to `run_command` tool
This would allow an agent to run shell commands interactively
- add a `mode` enum parameter with values `stateful` and `stateless` (default)
- add an optional `session_id` parameter for the agent to reference currently running terminal sessions.
- a new session is created by the tool, when `mode` is stateful, but no `session_id` is provided by the agent. The tool outputs a new session id in that case. The first command should be a shell command such as `bash` or `sh` without parameters, causing the thread to block without terminating.
- A session is kept alive for some time (specified by the `timeout` parameter), after the last command is issued. The agent can also close a session immediately, by simply using a shell command such as `exit`.


---




## Advanced features


### Allow users to add remote MCP servers
Users should be able to add remote (Streamable HTTP) MCP servers to their account, by specifying the server URL.

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
Allow LLM agent to control the app itself. For instance: modifying a chat session, Summarizing it, or creating a new session.

### AI assistant for prompting
- Assistant gives suggestions for follow-up questions, based on the conversation history. (and possibly other things)
- Assistant can be used to enhance (rephrase) the user's prompt, based on the conversation history.

### Auto-complete user prompts
- The user starts typing a prompt, and the AI suggests possible completions based on the current conversation, and prompt history (also from other chat sessions), or prompt templates.

### Support for CLI's that an LLM can operate
- It's still open for debate which approach is better: custom built CLI's, or MCP servers (with Code mode).
- A benefit of using a CLI is that the LLM doesn't need to know all the details about the tools it can use beforehand (which saves precious context tokens). It could progressively learn about how to use the tools by trying things out. For instance, by running a command with --help flag, or by getting a list of available commands. Also, the CLI could provide more detailed usage information upon request, which delays the use of context tokens until it's actually needed.
- Another benefit of using a CLI is that it can have state, which can be used to remember things between commands. For instance, the current working directory, or the last used parameters for a command.

### Use Reinforcement Learning to optimize system instructions
- The goal is that the LLM will use tools more effectively, and have better conversations.
- First, we would have to create a dataset of tasks, where each task has a preferred way to be completed. For instance:
  - Task: "Book a flight from New York to San Francisco"
    - Preferred way: To use the tool "SearchFlights" with the parameters "origin: New York, destination: San Francisco"
- Next, we would make incremental changes to the system instructions and then evaluate the changes by looking at the conversation quality. This process could be automated if we let an LLM evaluate the results, and update the system instructions.

### Continuous AI - AI Message Board
- AI agent monitors what the user is doing, and makes suggestions, and can start chat sessions about things it finds interesting. For instance: "It seems you're working on a bug related to the database. Here are some similar bugs that were fixed in the past, and how they were fixed."
- Given a project with resources (code, docs, etc.), the AI suggests new feature ideas, todo's, and discovers bugs.

### Allow Chatbot AI agent to create simple apps and run them on the local machine in a VM
 - The app gets a built-in API, so that an agent can interact with it. For instance, it could have endpoints for creating a screenshot of the GUI, simulate mouse movement and clicks, enter text, etc. (Although things like that could also be done with MCP tools, external to the app.)

### Allow LLM Agent to access previous chat sessions.
- Store chat sessions as markdown files and create an index for them in markdown format, to make them searchable and accessible for future conversations. Each item in the index should have:
  - the name of the markdown file (which is usually the same as the chat session name)
  - the date of the chat session
  - a list of keywords or tags that describe the main topics discussed in the chat session
  - a short summary of the chat session
- The LLM agent can then query this index to find relevant information from previous chat sessions, and use it to answer questions or provide suggestions.
- alternative: use [Graphify](https://github.com/safishamsi/graphify) SKILL.

### Role-playing mode
A single LLM assumes multiple roles/characters, and lets them interact with each other. For example: Product Owner, Scrum Master, Developers (Senior Software architect, Backend lead, Frontend lead).
- Could be used for a quick brainstorm session, or to simulate a meeting. 

### Workspace snapshots
Create snapshots of the workspace folder, to allow roll back to previous points in time. Integrate in the UI: When the user sends a new message, a snapshot is created automatically. User can roll back later by clicking an action button below any user message in history (up to a certain point. For example, a maximum of 20 snapshots are kept in storage).

### Allow LLM to search within chat session history

### Agent Tool call loop interleaving
Interleave an agent tool call loop with user messages generated by another LLM. This could be used in several ways. For instance:
- to steer an agent in the right direction and prevent tunnel-vision.
- to control costs or time.


### Interactive helper tool to guide new users, or aide in quick setup


### Agent retrospective
- A workflow orchestrator sends retrospective prompts to agents, after doing some work. This could include things such as:
  - issues which could have been prevented
  - improvement of certain tools (or their specs)
  - improvement of their own instructions
  - suggest new tools
- add a "master orchestrator" role, which drives workflow orchestrator agents, and can alter agent instructions (based on the workflow orchestrator feedback).
- the master orchestrator can notify the user about issues (blocking, or non-blocking) which require a decision
- the master orchestrator always uses the `spawn_agent` and `send_message` tools in `fire_and_forget` mode, so that it's always ready to receive new prompts from the user or workflow agents (which use the `send_message` tool in `fire_and_forget` mode after finishing a task, or when faced with a blocking issue.)
- when the master orchestrator is busy, new prompts should be queued.
- the master orchestrator role should have auto context compaction enabled, in order to never run out of context. (requires new auto compaction setting for model presets)
- The master orchestatror can write agent retrospective reports, summarizing the interviews of several agents.


### Use Jev to pick suitable skills for a certain task from a library of skills

### Use Jev for computer use
- OCR the screen, Jev classifies the next action, then click
- https://github.com/kraayenjon/awesome-jev
- https://github.com/awlevin/typesafe-computer-use


### Oracle
Idea: Use a very strong (and expensive) LLM as an "oracle" for receiving single-shot responses.
- The given prompt should be very detailed, and should include all relevant resources (e.g. file contents) needed.
- An agent supplying the prompt should be able to use predefined templates for including files. For example: `<\INCLUDE-FILE path="/app/workspace/chatbot/...">`. Templates within the prompt are expanded before being sent to the oracle. That way the generated prompt by the LLM can stay small (and cheap), while the received prompt by the oracle can be big. 
- This idea could also be used for simulating conversations by members of an imaginary software team.


### Train a small, specialized LLM model for this harness
- it should learn how to use all the built-in tools


### Provide a canvas for an agent to draw on
- Could be used for creating videos, using HTML/JS + Canvas, or third-party tools such as remotion.dev
- Compose Multiplatform WebView

---




## Performance improvements

### Avoid loading the extended Icons library for Compose, to reduce app size
See Gradle dependencies: `libs.version.toml` and `app/build.gradle.kts`. We now load the extended Icons library: `implementation(compose.materialIconsExtended)`


Here are detailed user stories for the new feature ideas, following the format and structure of your existing `User Stories v1.2.md` document, with new feature IDs starting from `NF`. Non-functional requirements (NFRs) are listed separately.

---

# AIChat Desktop App - New Feature User Stories

## New Features: Basic Interactions

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

---

## New Features: Intermediate

### (done, partial) NF.E1 - User Accounts and Permissions (Epic)
*   **Description:** This epic covers the foundational work for implementing a multi-user environment where each user has their own secure account, distinct chat history, personal configurations, and definable privileges. It also addresses the sharing of public resources across users and robust administration capabilities.
*   **Estimate:** XL

#### (done, partial) NF.E1.S2 - Manage User Accounts and Privileges (Admin)
*   **Description:** As an administrator, I want to view, create, edit, and delete user accounts, and assign specific roles or privileges (e.g., create public resources, manage other users' permissions), so I can control access levels and administrative capabilities across the application.
*   **Estimate:** L
*   **Dependencies:** NF.E1.S1 - Register and Log In to Personal Account
*   **Acceptance Criteria:**
    *   A dedicated "User Management" section is accessible only to users with administrator privileges.
    *   Admins can view a list of all registered users, their usernames, and assigned roles/privileges.
    *   (todo) Admins can create new user accounts, assigning an initial role (e.g., standard user, admin).
    *   (todo) Admins can modify existing user roles and assign/revoke specific privileges (e.g., "Can create public providers", "Can manage other users").
    *   Admins can delete user accounts (with confirmation), ensuring all associated user data is handled according to policy.
    *   At least one admin user is guaranteed to exist or be created during initial setup.

    
## New Features: Advanced

### NF.SA3 - Manage Structured Knowledge Base
*   **Description:** As an advanced user, I want to import, view, and manage a structured knowledge base within the server, consisting of entities and relationships between them, so I can provide the LLM with specific, domain-relevant factual information that goes beyond its general training data.
*   **Estimate:** L
*   **Acceptance Criteria:**
    *   A dedicated "Knowledge Base" section in the application settings allows importing data from a structured format (e.g., CSV, JSON, or a custom schema).
    *   The system supports defining and storing entities (e.g., "Person", "Company", "Location") and relationships between them (e.g., "works for", "lives in").
    *   A UI is available to view and potentially edit (basic CRUD) the loaded entities and relationships.
    *   The knowledge base is persisted on the server.

### NF.SA4 - LLM Agent Query Knowledge Base
*   **Description:** As a user, when I ask questions that rely on factual information present within the loaded knowledge base (NF.S18), I want the LLM to query and utilize that knowledge base to provide specific and contextually rich answers, including traversing relationships to give deeper insights (e.g., a "level 2" query for related entities).
*   **Estimate:** L
*   **Dependencies:** NF.S18 - Manage Structured Knowledge Base
*   **Acceptance Criteria:**
    *   The backend's LLM context building logic includes a mechanism to provide relevant knowledge base entities and relationships to the LLM based on the user's query.
    *   The LLM can formulate queries to the knowledge base (e.g., "Find relationships for 'John' up to level 2").
    *   The knowledge base service responds to these queries with lists of relevant entities and their relationships.
    *   The LLM incorporates the retrieved knowledge into its final response, explicitly citing information from the knowledge base when appropriate.
    *   For example, asking "Tell me about John" after loading a knowledge base containing "John is 30 years old" and "John lives in New York" will result in the LLM stating those facts.

### NF.SA5 - LLM Agent Control over Chat Session Management
*   **Description:** As a user, I want to be able to issue natural language commands to the LLM agent to perform application-level actions related to chat session management (e.g., "Summarize this session," "Create a new session about [topic]," "Delete all messages before [date] in this chat"), so I can manage my conversations more intuitively without direct manual UI interaction.
*   **Estimate:** L
*   **Acceptance Criteria:**
    *   The LLM agent understands and correctly interprets commands related to session operations (e.g., `create_session`, `delete_messages`, `summarize_session`).
    *   Upon receiving such a command, the LLM agent invokes the corresponding backend service method.
    *   Successfully executed commands result in the expected application state changes (e.g., a new session appears, messages are deleted, a summary is provided).
    *   The LLM agent provides feedback to the user regarding the execution of the command (e.g., "Session 'New Topic' created successfully.").
    *   Appropriate confirmation dialogs are triggered for destructive actions (e.g., "Are you sure you want to delete messages?").

### NF.SA6 - LLM Agent Control over Application Configuration
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