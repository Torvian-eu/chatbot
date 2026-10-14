# New feature ideas

## Basic features

### (done) Allow message streaming process to stop gracefully
- Store partial message content in AssistantMessage table, in case the stream was interrupted. (either due to an error, or because the user stopped the streaming)
- In the UI, the "send" button should transform into a "stop" button while the message is streaming. 

### (done) Allow user to see why an assistant message was not completed
- An assistant message may be incomplete due to an error, or manual interruption by the user (by pressing the stop button in the UI)
- Add Boolean field to AssistantMessageTable to indicate if the message is complete.
- Add error message String field to AssistantMessageTable (which can be shown in the UI, together with the partial content).

### (done) Allow user to pause an agentic response
- When a message starts streaming, show a "Pause" icon instead of a "Stop" icon. When user clicks the "Pause" button, the icon starts blinking slowly, indicating that the streaming process is being paused. When streaming process is actually paused, the icon changes to default mode again (with "Play" icon). While the icon is slowly blinking on Pause state, the user can click on it again, which will force a hard stop. (Websocket connection will be closed immediately)
- Pausing should stop the (web socket) streaming process, but only after the current assistant message is fully processed. This means that the message loop (used for tool calling) will be interrupted prematurely.
- This feature is particularly useful for agentic LLM usage, where the user might want to interrupt an undesirable response and provide feedback.

### (done) Allow recursive deletion of messages. (in addition to single message deletion)
- The server module is already capable of this, but the ktor route needs to be examined. In `configureMessageRoutes.kt` we have a route `delete<MessageResource.ById>`, where we use a query parameter "mode=single" to perform a non-recursive delete and "mode=recursive" to perform a recursive delete. However, we use the Ktor Resources plugin, and we should examine if a query parameter can be configured for the route declaritively instead, so that it can be validated at compile time.
- Add a "Delete Thread" button to the message actions menu. (see MessageActionRow.kt)
  (or hide it under a "More" actions button, to show a popup menu with extra actions, less commonly used)

### (done) Insert message above/below
- Add an "Insert" button to the more actions menu. (See MessageActionRow.kt)
- This should insert a user or assistant message above/below the target message.
- After clicking the "Insert" button, a dialog should be shown with the following options: above/below (default: below), user/assistant (default: user), message content (optional, may be empty)

### (done) Make message content selectable

### (done) Make messages collapsible (useful for long messages)

### (done) Make message content copyable to clipboard

### (done) Make currently displayed message thread copyable to clipboard

### (done) Make messages searchable

#### (done) Make chat messages searchable (within a single chat session)
- add an option to the "more" menu in the top app bar, which allows the user to search for messages within the current chat session. (As an alternative it should be possible to open the search bar by pressing Ctrl+F / Cmd+F)
- The search bar should temporarily replace the center items in the top app bar: model & model settings selector, tools selector, and more menu.
- The search bar should have:
  - a text input field
  - a back and forward button to cycle through search results
  - a label showing the current search result index and total number of results (e.g., "2/5"). Clicking the label should allow the user to manually enter a search result index to jump to.
  - a close button to exit the search mode and restore the original top app bar items.
- After the search is performed, it should highlight all instances of the search text found.
- The scroll position should move to the closest instance (in relation to current scroll position).
- The user should be able to cycle thru instances (with back/forward navigation, and manual number entry)

#### (done) Make chat messages searchable (across all chat sessions)
- Add a search icon button to the left panel on the chat screen (next to the "Add Session" button). This opens a new dialog with a search text input field and a list of search results.
- Each search result should show:
  - The partial message content with the search text highlighted. The search text should be surrounded by a few lines of message content, so that it appears right in the middle.
  - Session name
  - A link to the message in the session. Clicking the link should open the session and scroll to the search text within the message content. (We can use the local search feature to achieve this, by pre-filling the search text and selecting the correct search result index)
- The search dialog should contain:
  - A text input field for the search text
  - A list of search results
  - A search action button to perform the search
  - A close button to close the dialog and return to the chat screen
- Closing the dialog should not reset the search results. Reoping the dialog should show the previous search results, until a new search is performed.
- The search should be done server-side, and should search across all chat sessions for the current user. The server should return a list of search results, which are then displayed in the dialog.

### (done) Add Save as Copy button to message edit area (in addition to Save/Cancel)
This action saves the edited message as a new message, and creates a new branch in the conversation (also for leaf messages).

### (done) Add option to continue conversation from (any) message
- Add a "Branch & Continue" button to the message actions menu. (see MessageActionRow.kt)
- This action creates a new branch in the conversation, with the selected message as the parent.
- This feature is more versatile than "Regenerate" because it can be used on any message, not just assistant messages.

### (done) Add Regenerate button for assistant messages
- This is functionally equivalent to "Branch & Continue" from the target's parent message, but it is more intuitive for assistant messages.
- The regenerate button is already present in the code, but is currently deactivated in the UI. (see MessageActionRow.kt)
- In case the assistant message is the root of the conversation (very unlikely), the regenerate button should not be shown. (simpler alternative: do null operation in viewmodel instead, or do both)

### (done) Allow user to quickly scroll to the bottom of the messages list

### (done) Allow user to switch accounts without logging out

### (done) Only allow connections to the server over HTTPS.
For security, the server should only be accessible over HTTPS, and not HTTP. This applies to connections over the internet, as well as local network connections.

### (done) Make session list panel collapsible
Add an icon button to expand/collapse panel in the top app bar, on the left side.
In the future we could add a vertical sidebar with icons on the left side of the screen. Clicking the session list icon would expand the panel, clicking it again would collapse it. This would only make sense when we have multiple panels to switch between. Currently we only have the session list panel, so a top app bar button is the best option.

### (done) Allow user to clone a chat session
- Add a "Clone" button to the session actions menu. (see SessionItemActionsDropdown)
- Cloning a session should create a new session with the same name, but a new ID.
- The new session should have the same messages and tool calls, but with new IDs.
- The new session should have the same owner, current leaf message, configured tools, LLM model, settings, etc.

### (done) Popout message input area for entering long messages
- Currently the maximum number of visible lines is only 5. This makes it difficult to enter long messages.
- If possible, we should allow the user to pop out the message input area into a separate window.
- Alternative: make the input area part of the message list flow, which is already scrollable. (when user requests)

### (deprecated in favor of agent roles) Add system message composer
The system message is what the LLM uses to understand the context of the conversation. It's always the first message in the conversation, and carries the heaviest weighting. We want to let the user compose the system message within the UI:
- There should be several snippets of text (components) that the user can choose from to compose the system message:
  - The system message property from the current chat model settings (ChatModelSettings.systemMessage)
  - A predefined role-based message, stored in the database. For example: "You are a helpful assistant.".
  - File contents from one or more `AGENTS.md` files. (Every worker instance should have an AGENTS.md file, located in the workspace root. This file can contain specific project instructions for the LLM.)
  - A custom message that the user can edit.
- The user should be able to add new role-based message objects to the database. Each object should have at least the following properties: id, name (unique), display name, and system message content.
- The system message composition object is stored per-session. It should have at least the following properties: id, chat session id, role-based message id (nullable), custom message content (String, nullable), list of worker ids (to include AGENTS.md files from), whether the ChatModelSettings.systemMessage should be included or not (Boolean, default: true), ordering of components (List<String>)
- The system message composer should be accessible from the top bar in the chatscreen, in the "More" menu.
- In the composer dialog, the user should be able to select which components to include, and change the ordering. Each component should be presented as a card in the UI.

Technical notes:
- The database should hold a cached version of the system message for performance reasons, so that the composed system message doesn't have to be rebuilt on every request. It should be stored in the database as an object with at least the following properties: chat session id, system message (String). The client app is responsible for keeping the system message updated (by calling an endpoint on the server)
- The client app can get the contents of an AGENTS.md file by calling the built-in worker tool `read_text_file`.
- The client app should have Repository class for managing the cached (per-session) system message and other related data.


---




## Intermediate features

### (done) User accounts
Each user has their own set of credentials, models, settings, and chat history. Some things could be shared between users, such as public providers, models and settings. Certain users could be assigned special privileges, such as the ability to manage other users, or add public providers, models and settings. There must be at least one admin user, who has all privileges (or has at least the capability to assign privileges to theirself and other users)

### (deprecated) Allow user to select predefined (built-in) LLM Tools for a specific prompt
Predefined tools could include: Web search, calculator, current weather, etc.
note: Web search not working yet.

### (done, deprecated) Allow user to add file references when sending a message
- The UI should include a button to add file references, which opens a file picker. (below the message input area)
- By default, the actual file content should not be sent to the LLM, only a reference to the file.
- The user should be able to select which files to send, and the base path for the file references. The base path is not included in the file references sent to the LLM. Only the relative path from the base path is included in the file reference. By default, the base path is the same as where the file itself is located, or when multiple files are selected, the common (longest) parent path of all selected files. Selecting a custom base path is useful when alligning with Local MCP Tools, which should be using the same base path. That way the LLM can use the file references in the Tools.
- The user should be able to place a file reference within the message content (inline). Optionally, this could also include the actual file content (usually text-based only).
- The LLM context should include the file references appended to the message content (For instance: "Referenced files: [file1.txt, file2.txt]"). And, if available, the LLM context should also include the inline file references with or without the file content. 
- (optional) The user should be able select file references from previous messages in the conversation, for inline placement in the current message.
- The file references should be visible as badges in the message content (similar to tool call badges). Clicking the badge shows metadata about the file, such as the file name, size, and modification date. The badges should be visible for all messages in the conversation, as well as in the message input area.
- Inline references should be clickable to show the file's metadata, and optionally the file content if it was included.
technical notes:
- Add FileReference data class with base path, relative path, optional content, insert position (for inline placement).
- Add ChatMessageTable.fileReferences with type TEXT (JSON array of file references)
- Add ChatMessage.fileReferences property with type List<FileReference>
Not completed:
- UI for inline placement of file references
- Drag & drop files from OS into message input area

### (deprecated) Add "Agent Skills" feature
- Agent Skills are folders of instructions, scripts, and resources that agents can discover and use to do things more accurately and efficiently. See: https://agentskills.io/home
- Skills should be installed in the following way:
  - User downloads a skill from places like https://skills.sh/ and saves it to the "skills" directory in the user's data directory of the AIChat app.
  - The user triggers the app to scan the "skills" directory for new skills. And the skill is added to the app's local database.
  - (optional) The user configures the skill in the app's GUI.
- A skill should be activated in the following way:
  - The user mentions the skill in a chat message. For instance: "Could you please help me with the 'git-branch' skill?".
  - The LLM should be able to understand the user's intent to use the skill, and ask the user to confirm. (For instance: "Do you want to use the 'git-branch' skill to list all local branches?")
  - Alternatively, the user can manually load the skill into the current chat session, using the GUI.
- In order for the LLM to know under which conditions a skill should be used, the user should create an AGENTS.md file, which is loaded into the LLM context (as the system message). This file could also contain general project information, with links to other files. A user could have many different AGENTS.md files for different projects, or agent roles. See: https://github.com/agentsmd/agents.md
- Requirements for using skills:
  - A built-in agent tool for listing available skills.
  - A built-in agent tool for loading skills into the LLM context of the current chat session.
  - An agent tool for running commands on the local machine. (It could be a third-party MCP tool, or a tool built into the app.) This is necessary for the agent to run scripts from the skill.
  - An agent tool for fetching information from the web. This is necessary for the agent to visit links mentioned in the SKILL.md or AGENTS.md files.
  - An agent tool for reading files from the local file system. This is necessary for the agent to read the content of files mentioned in the SKILL.md or AGENTS.md files.
  - A convenient way for users to load the content of an AGENTS.md file into the LLM context for the current chat session. (For instance: a button in the GUI to select an AGENTS.md file, which is then loaded into the LLM context)

### (done) Add support for OpenAI's Responses API
The main benefit over the Chat Completions API is support for reasoning tokens. It allows replaying of reasoning tokens from previous requests.
- Add new model type `RESPONSES` in enum class `LLMModelType`.
- Docs:
  - [Responses Overview](https://developers.openai.com/api/reference/responses/overview)
  - [Migrate to the Responses API](https://developers.openai.com/api/docs/guides/migrate-to-responses)
  - [Create a model response](https://developers.openai.com/api/reference/resources/responses/methods/create)
  - [Streaming API responses](https://developers.openai.com/api/docs/guides/streaming-responses)
  - [Streaming events](https://developers.openai.com/api/reference/resources/responses/streaming-events)
  - [Function calling](https://developers.openai.com/api/docs/guides/function-calling)
  - [Reasoning models](https://developers.openai.com/api/docs/guides/reasoning)

### (done) Allow user to seamlessly switch between chat sessions while requests are in progress
- The streaming process should continue running, allowing for parallel processing of multiple requests.

### (deprecated) Memory spaces
Allow users to create multiple memory spaces, which are separate knowledge bases that can be loaded into the LLM context. This allows users to organize their knowledge base into different topics or projects, and only load the relevant information for each chat session. For instance, a user could have a "Work" memory space with information about their job, and a "Personal" memory space with information about their personal life. When starting a new chat session, the user can choose which memory space(s) to load into the LLM context.
- Each memory space has an AGENTS.md file, which is loaded into the LLM context when the memory space is loaded. This file contains instructions for the LLM on how to use the information in the memory space, as well as general information about the topic of the memory space.
- A memory space can contain both structured and unstructured information. Structured data may be stored as entities and relationships in a graph database or in a relational SQL database (depending on access and query patterns). Unstructured information is stored as text documents. The LLM can query the structured store for relevant entities/relations and consult the text documents for detailed context.
- The agent can add/remove items to/from the memory space, and update the AGENTS.md file as needed. This allows the memory space to evolve over time, and for the LLM to learn from its interactions with the user and the environment.

### (deprecated) Skill spaces
Allow users to create multiple skill spaces, which are separate collections of SKILL folders. This allows users to organize their skills into different topics or projects, and only load the relevant skills for each chat session. For instance, a user could have a "Programming" skill space with skills related to programming tasks, and a "Cooking" skill space with skills related to cooking tasks. When starting a new chat session, the user can choose which skill space(s) to load into the LLM context.
- Each skill space has an AGENTS.md file, which is loaded into the LLM context when the skill space is loaded. This file contains instructions for the LLM on when and how to use the skills in the skill space, as well as general information about the topic of the skill space.
- The agent can add/remove skills to/from the skill space, and update the AGENTS.md file as needed. This allows the skill space to evolve over time, and for the LLM to learn from its interactions with the user and the environment.

### (deprecated) Workspaces
Combine memory spaces and skill spaces into workspaces. A workspace is a collection of memory spaces and skill spaces that are relevant for a specific topic or project. When starting a new chat session, the user can choose which workspace to load into the LLM context, which will load all the memory spaces and skill spaces associated with that workspace.

### (done) Built-in worker tools
Add built-in tools for the worker to use.
- All tools can be prefixed with a unique identifier to indicate that they are built-in tools for a specific worker instance. This prefix is configurable, so that the user can change it to something more meaningful for their specific use case. For instance: "project1.read_text_file", "project1.write_file", "project1.edit_file", etc.
- Each worker as a special `workspace` directory, which is used to store files that the worker can access. All file paths are relative to this workspace directory. The worker can read/write files in this directory, but cannot access files outside of it. This is a security measure to prevent the worker from accessing sensitive files on the host machine.

#### File Operation Tools
- **read_text_file**: Read contents of a file as text.
  - Inputs: `path`, `head`/`tail` (optional, first/last N lines)
  - Outputs: file content as text
  - Always treats the file as UTF-8 text regardless of extension.
  - Cannot specify both head and tail simultaneously.
- **write_file**: Create new file or overwrite existing (exercise caution with this)
  - Inputs: `path`, `content`.
- **edit_file**: Make selective edits using advanced pattern matching and formatting
  - Features:
    - Line-based and multi-line content matching
    - Whitespace normalization with indentation preservation
    - Multiple simultaneous edits with correct positioning
    - Indentation style detection and preservation
    - Git-style diff output with context
    - Preview changes with dry run mode
  - Inputs:
    - `path` (string): File to edit
    - `edits` (array): List of edit operations
      - `oldText` (string): Text to search for (plain text or regex)
      - `newText` (string): Text to replace with
    - `dryRun` (boolean): Preview changes without applying (default: false)
  - Returns detailed diff and match information for dry runs, otherwise applies changes
  - Best Practice: Always use dryRun first to preview changes before applying them
- **create_directory**: Create new directory or ensure it exists
  - Input: `path` (string)
  - Creates parent directories if needed
  - Succeeds silently if directory exists
- **list_directory**: List directory contents with [FILE] or [DIR] prefixes
  - Inputs: 
    - `path` (string)
    - `sortBy` (string, optional): Sort entries by "name" or "size" (default: "name")
    - `includeSizes` (boolean, optional): Include file sizes in output (default: false)
    - `recursive` (boolean, optional): List contents recursively (default: false)
  - Returns a simple listing of files and directories, optionally including sizes
  - If `recursive` is true, the listing will include all subdirectories and their contents, formatted with indentation to indicate directory depth.
  - Shows total files, directories, and combined size if `includeSizes` is true
- **move_file**: Move or rename files and directories
  - Inputs:
    - `source` (string)
    - `destination` (string)
  - Fails if destination exists
- **search_files**: Recursively search for files/directories that match or do not match patterns
  - Inputs:
    - `path` (string): Starting directory
    - `pattern` (string): Search pattern (glob-style)
    - `excludePatterns` (string[]): Exclude any patterns.
  - Returns paths to matches

#### CLI Tools
- **run_command**: Execute a command-line command
  - Inputs:
    - `command` (string): Command to execute
    - `args` (string[]): Optional arguments for the command
    - `timeout` (number): Optional timeout in seconds (default: 600)
  - Returns:
    - `stdout` (string): Standard output of the command
    - `stderr` (string): Standard error output of the command
    - `exitCode` (number): Exit code of the command
  - The current working directory is the worker's `workspace` directory. The command must be available in the system PATH or specified with an absolute path.
  - The worker should run within a docker container or sandboxed environment to prevent malicious commands from affecting the host system.


### (done) Retry LLM request on 429 or 503
- Implement retry logic for LLM requests that receive a 429 Too Many Requests or 503 Service Unavailable error, with exponential backoff.

### (done) Show hints to LLM when built-in tool fails (or returns empty result)

### (done) Add search capability for `fetch_web_content` tool
Allow LLM to search for text in a web page directly, similarly to `search_text` tool.
- add parameters:
  - `searchQuery` : string (optional); cannot be mixed with `range` parameter
  - `searchMode` : `regex` or `plain` (default: `regex`)
  - `contextBefore` : number (optional, default: 0); only used when `searchQuery` is specified
  - `contextAfter` : number (optional, default: 0); only used when `searchQuery` is specified
  - `maxResults` : number (optional, default: 10); only used when `searchQuery` is specified
  - `caseSensitive` : boolean (optional, default: false); only used when `searchQuery` is specified
  - `wholeWord` : boolean (optional, default: false); only used when `searchQuery` is specified and `searchMode` is `plain`
- updated parameters:
  - `range` : cannot be mixed with `searchQuery` parameter

Notes:
- if `range` and `searchQuery` are both specified, the tool should return an error message indicating that only one of them can be used at a time.
- If any of the `searchQuery` related parameters (`searchMode`, `contextBefore`, `contextAfter`, `maxResults`, `caseSensitive`, `wholeWord`) are specified, the tool should return an error message indicating that they can only be used when `searchQuery` is specified.
- Spefifying both `maxLines` and `searchQuery` is allowed. The number of lines is counted as the total number of lines returned (including context), regardless of how many matches are found. If `maxLines` is reached, the tool should return a message indicating that the maximum number of lines has been reached.

### (done) Add strip HTML capability for `fetch_web_content` and `download_file` tools
Add an option to these tools to strip a HTML page to a bare minimum, so that only the core HTML tags, core attributes, and text content remain. A boolean parameter should be added as an on/off switch (default: on). 

### (done, partially) User-defined agent roles

#### UI
- User can select an agent role for the current chat session. Replaces the model, model settings, and tools selection form elements in the top app bar.

#### Data structure for `AgentRole`
An agent role consists of the following items:
- `id`: type: long, unique, cannot be modified after creation, created by db
- `name`: type: String, unique, modifiable, short name
- `display name`: type: String?
- `description`: type: String
- `model id`: type: Long, references `LLMModel` object
- `model settings id`: type: Long, references `ModelSettings` object (`LLMModelType` must be `CHAT` or `RESPONSES`)
- `tools`: type: List<ToolDefinition>, list of tools available to the agent
- `instructions`: type: List<`AgentInstruction`>, an ordered list of instructions that the agent must always follow. This list is used to compose the system message for the LLM. (All of the message strings from `AgentInstruction.message` are concatened together, with markdown separator "---".)
- (done) `project id`: type: Long?, references the project that this agent role belongs to. Useful for grouping several agent roles together.
- (done) `spawnable agent roles`: type: List<Long>, a list of agent roles that this agent is allowed to spawn. An agent can use a `spawn_agent` tool to spawn another agent instance. (This tool will be implemented in a future stage.)
- (future extension) `skills`: type: List<Long>, items reference class `Skill`, list of skills that the agent can use.

#### interface `AgentInstruction`
```
interface AgentInstruction {
    val name: String,
    val message: String,
    fun loadMessage()
}
```
Implementations of this class include:
- `RoleInstruction`: describes the role that the agent should take. For instance: "You are a senior software architect assisting a user who often delegates implementation to a separate AI coding agent."
- `MainInstruction`: contains information about the project the agent should be working on. (usually AGENTS.md file contents)
- (deprecated) `ModelSettingsInstruction`: contains the instructions for the referenced model settings object in the agent role (`ChatModelSettings.systemMessage` or `ResponsesModelSettings.instructions`). Can be useful in case a certain model needs specific instructions. This class has an extra property `modelSettingsId`. The function `loadMessage` loads the instruction from the database, and stores it in a private `_message` field.
- `CustomInstruction`: A custom user-editable instruction for the agent. There can be multiple of these within the same list of instructions.
- (done) `SpawnableAgentsInstruction`: contains information for the agent about how to spawn another agent, and which agent roles are available. This instruction is dynamic and is derived from the property `AgentRole.spawnable agent roles`.
- (future extension) `SkillsInstruction`: contains information for the agent about how to use a skill, and which skills are available. This instruction is dynamic and is derived from the property `AgentRole.skills`.

#### Future extensions
**(deprecated for now, because probably not needed) Agent role overrides**
Sometimes it's useful to override some properties from `AgentRole` temporarily, without having to create a new `AgentRole` in the databse. The class `AgentRoleOverrides` has the following properties:
- `model settings override`: can override settings defined in the object `ModelSettings` referenced by `model settings id`. For example, this could be useful for setting a specific `temperature` for the current task.
- `tools override`: Add or remove tools for the current task. This overrides the default `tools` list for the agent role.
This class can be used in both the UI (ViewModel) and agent instances. 

**Skills**
Agent Skills are a lightweight, open format for extending AI agent capabilities with specialized knowledge and workflows. See: https://agentskills.io/home.
The properties for class `Skill` are:
- `id`: type: Long
- `name`: type: String, same name as directory (for example: workspace/skills/<name>)
- `description`: type String

### (done, partially) Spawnable agent instances
An agent role can be spawned as an agent instance. An agent instance needs an `AgentInstanceConfig` object to be spawned. It consists of the following items:
- `agent role`: type: `AgentRole`
- `operator type`: type: `OperatorType`. An operator manages the agent's instance. It's either the client app or a dedicated agent operator (running as a background service; similar to the worker, which stays connected to the server (via websocket) at all times). The operator must be registered in the worker as a trusted signer. Every tool call request for the worker is signed by the operator. (The client app already has this capability.)
- `conversation`: type List<`RawChatMessage`>. This is usually a list with a single user message item. It contains a prompt, which describes the task for the agent to execute.
- (future extension) `agent role overrides`: type: `AgentRoleOverrides?`, optional

#### (done) Operator built-in tool `spawn_agent`
Sends a signal to the operator to initiate the creation of a new agent instance. It can be called by the LLM at any time. It has the following parameters:
- `agent role name`: type: String, references `AgentRole.name`
- `prompt`: type: String, describes the task for the agent to execute.

**Tool execution flow**
- The tool must be approved by the operator first, before it can be executed: the server sends a `Chat(Stream)Event.ToolCallApprovalRequested` to the operator and the operator responds with `ChatClientEvent.ServerToolCallApproval`, a new event type. New class to be created: `ServerToolDefinition` with new `ToolType.SERVER`. It's a class derived from the interface `ToolDefinition` with no extra properties.
- After approval, the server relays the execution of the tool back to the operator, waits for the tool result, and sends the result to the LLM that initiated the `spawn_agent` tool call. The execution relay can be done via a new type of event, sent over websocket. For instance: `Chat(Stream)Event.ServerToolExecutionRequested`.
- The execution of the tool is orchestrated by the operator. Upon receiving the tool execution request from the server, it creates a new chat session, and sends a `ProcessNewMessageRequest` (with the value of `prompt` as content) to the server, which initiates a new websocket connection. So now the operator has to manage two websocket connections simultaneously.
- When the `ProcessNewMessageRequest` finishes, the operator has to aggregrate the result. For simplicity, we can use the contents of the last assistant's message from the chat session, which should include a summary report.
- The operator sends the tool result to the server, via a new `ChatClientEvent.ToolExecutionResult` event.
- The server sends the result to the LLM that initiated the `spawn_agent` tool call.


### (done) Add agent instruction type that's only active for a certain model
- Multiple instances of this new type of instruction are possible in the list of instructions. The only one active will be the one equal where its model id property is equal to AgentRole.modelId
- (future stage) `ModelSettingsInstruction` can be deprecated in favor of this. Also, the `systemMessage` property can be removed from `ChatModelSettings` and `ResponsesModelSettings`, since it would have no purpose anymore.

### (done) Projects
Define a project that several agents can work on together. The properties for class `Project` are: `id`, `name`, `description`.
- Allows a user to group several agent roles together
- A project name can be selected in the top app bar. When a project is selected, only the agent roles from that project are shown in the agent role selection box. If no project is selected only the agent roles not currently associated with a project are shown.
- Each project should be owned by a specific user. (implemented as link table)
- An agent role can be only be associated with a single project
- The `name` property of agent role becomes unique per user & project. (no longer just per user)

### (done) Add predefined Chat model configurations, for use in agent roles
Problem: New models get released often, but we currently are unable to switch several agent roles to the new model quickly, at once. Also, sometimes we only want to switch the provider for OpenRouter by using a different model setting (with `{"provider":{"only":["<provider name>"]}}` as custom JSON header), but we also can't do that quickly.
Solution: 
- Add a class to define a model + model settings combination, and reference that in agent roles (instead of referencing model and model settings directly). This class could have the following properties:
  - `id`: type: Long; unique ID for database
  - `name`: type: String; unique short name; for example: `smart_model`, `cheap_model`
  - `displayName`: type: String; optional
  - `description`: type: String; optional
  - `modelId`: type Long; references `LLMModel`; nullable: becomes null when referenced model is removed from database
  - `modelSettingsId`: type Long; references `ModelSettings`; nullable: becomes null when referenced model settings is removed from database
- Open question: what would be a good name for this class? `ModelPreset` perhaps.
- The class `AgentRole` (and `AgentRoleDto`) gets a new property (`modelPresetId`)
- The `LLMModelType` referenced by `ModelPreset.modelSettingsId` should **not** be restricted to just `CHAT` and `RESPONSES`. However, when used in `AgentRole` it should. That way the preset class can be re-used for other things in the future.

### (done) Server built-in tools
These could be used by the LLM for actions such as:
- list agent roles for current user (shows a list of items with all properties from AgentRoleSummary)
- read agent role info (input: id; shows all properties from AgentRole)
- create agent role (input: all fields except id)
- update agent role (input: id and fields to be modified only)
- list models (only list items accessible by current user; shows all properties from LLMModel)
- list model settings for a specific model (input: modelId; only list items accessible by current user; shows all properties from ModelSettings, including subtype)
- list tools (only list items accessible by current user; lists shows only the properties: id, name, description, type, isEnabled)
- read tool info (input: id; shows all properties from ToolDefinition and its subtype)
- (future extension) Compact a conversation

All built-in server tools should be scoped by user to prevent unauthorized access

### (deprecated) Add server built-in tool for compacting a chat session
#### Tool `compact_chat_session`
**inputs:** `session_id`
**output:** message confirming compaction was successful

### Allow user to compact a conversation manually
- Compacts the currently active thread (determined by the current leaf message id) in the chat session.

### (done) Automated compaction of a conversation
- compact context while tool call loop is running; loop should not be stopped
- compact when context size exceeds a certain threshold; for instance, 100K tokens
- context size should be measured accurately in tokens; in v1 we should use are own implementation for that; don't use third-party libraries in v1
- should work seamlessly; after compaction the user should still see the original messages
- a compacted context chunk should be persisted separately; it should record the message ids that it has compacted; the compacted messages themselves should stay intact
- a compacted context chunk should only be used when **all** the messages it refers to would be used in the current context; so for instance, if a user creates a new thread from a previous message in the context that was compacted, the compacted context chunk is not used anymore.
- the user can configure a specific model to do compaction with; it's usually a cheap model and should not require tool calling capability
- the user can configure the model instruction for compacting / summarizing a conversation

### (done) Add option to let the spawn_agent tool return immediately
Add an optional parameter to the `spawn_agent` tool, to have it return immediately without waiting for the last assistant's message. This allows the user have a conversation with the agent being spawned. The user should now manually copy & paste the summary message back to the orchestrator, when finished.

### (done) Clone project
- Allow user to clone a project from the UI.
- Should clone all agent roles associated with the project
- Input required: a new name for the cloned project (constraint: project name must be unique per user)
- Also add an LLM (server built-in) tool for this.

### (done) Allow user to disable an agent role
- When disabled, the agent role should no longer be selectable for a session on the chat screen (top app bar).
- The agent role database table should **not** get an extra "disabled" property. Instead, a separate database table should be created for that. When no entry in the table exists, the agent role is assumed to be enabled. This is done so that agent roles can easily be made a shareable resource in the future (similar to model providers, models, and model settings).
- the agent role (dto) class(es) can get an extra disabled property, if that makes the code more efficient
- Settings Screen -> Agent roles Tab: Each role should get an enabled/disabled switch

### (done) Add `get_current_session_info` server LLM tool
**inputs:** none
**output:** Json object with properties:
- `session_id`
- `session_name`
- `agent_role_id`
- `agent_role_name`
- `agent_role_display_name` (if not empty or null)
- (deferred) `message_turn_count`
- (deferred) `token_count`

### (done) Allow bi-directional communication between caller and spawned agent
Currently, the communication between caller and spawned agent is a one-time prompt. The caller waits for the agent to complete its task and receives a summary. But after that, there's no communication possible anymore. Proposed solution: both the caller and spawned agent receive an address (the chat session id) to send messages to, and a `send_message` operator tool is added.
- The caller receives the chat session id of the spawned agent, by including it as output for the `spawn_agent` tool.
- The spawned agent receives the chat session id of the caller via the prompt from the `spawn_agent` tool. So the calling agent should first retrieve the current session information via the `get_current_session_info` tool, before calling the `spawn_agent` tool. So this is the responsibility of the caller. No additional code is needed for this to work. 

#### (done) Add a `send_message` operator tool
**inputs:**
- `chat_session_id`
- `message`
- `mode`: immediate return or wait for last assistant's message
**output:** one of:
- waiting mode -> content of the last assistant's message, usually a summary of the task that was completed. (similar to the output of the `spawn_agent` tool)
- immediate return -> simple notification that message was sent succesfully

Notes: 
- The tools `send_message` and `spawn_agent` are similar, but the `interactive` flag for `spawn_agent` currently does not let it return immediately. This should be changed, so that it behaves the same way as the `send_message` tool. Also the `interactive` flag should be replaced by a `mode` enum (with the same values as the `mode` param for `send_message`).
- Open question: which values for `mode` enum would fit the described behavior best?
- Technical info: For immediate return, we don't wait for `sendJob.join()` in `DefaultAgentSpawnExecutor` anymore.
- Immediate return mode for the `spawn_agent` tool should return the chat session id of the agent that was spawned, on success.
- Waiting mode for the `spawn_agent` tool should return the chat session id of the agent that was spawned, on both success and failure (not all failures, but whenever a valid chat session id is available).

### (done) Add server built-in tools for project management
All tools are scoped by user to prevent unauthorized access. The tools are described below.

#### Tool `list_projects`:
**inputs:** none
**output:** Json array of objects with properties `id`, `name`, `description`, `createdAt`, `agentRoleIds` (See class `ProjectDto`)

#### Tool `read_project`:
**inputs:** `id`
**output:** Json object with properties `id`, `name`, `description`, `createdAt`, `agentRoleIds` (See class `ProjectDto`)

#### Tool `create_project`:
**inputs:** `name`, `description`, `agentRoleIds`
**output:** Json object with properties `id`, `name`, `description`, `createdAt`, `agentRoleIds` (See class `ProjectDto`)

#### Tool `update_project`:
**inputs:** `id`, `name`, `description`, `agentRoleIds`
**output:** message confirming update was successful (mentions project id) 

#### Tool `delete_project`:
**inputs:** `id`
**output:** message confirming deletion was successful (mentions project id)

---




## Advanced features

### (done) Allow users to add local MCP servers
Users should be able to add local (STDIO) MCP servers to their account, by specifying a local command to launch the MCP server. This command will run on the client machine, and communicate with the AIChat app over STDIO.

### (done) Add option to add tools to MCP server
Tools are functions that the LLM can execute. They are defined by the user and can be used to interact with the outside world. For instance a tool could be defined to search the web for information. The LLM could then use this tool to search the web for information to answer a question.

### (deprecated) Allow LLM to enable/disable (MCP) tools for the current chat session
- Give LLM access to a set of tools:
    - Tool to enable/disable tool for the current chat session. (parameters: tool name, enable/disable)
    - Tool to list all available tools for the current chat session.
    - Tool to search for tools based on a keyword. (match against tool name and description)
- The purpose of this feature is to reduce the context size, by only including the tools that are relevant for the current task.

### (deprecated) LLM Council
Have multiple LLM's (or agent runs) solve the same task, and then have a final LLM summarize the answers and decide which answer is the best.

### (deprecated) Chatbot MCP server (which can be used as a local MCP server within the Chatbot app)
- The API for the chatbot server application is quite big, and not all of it is needed for an MCP server. Still, the number of MCP functions will be quite large. So we need to add a second layer for the MCP server. For instance: a search function for MCP functions and a generic execute function which takes a function name and parameters. That way the MCP server would require only two functions: search and execute. Other ideas: "Code mode" as described by FastMCP: https://www.jlowin.dev/blog/fastmcp-3-1-code-mode, Cloudflare: https://blog.cloudflare.com/code-mode/, and Anthropic: https://www.anthropic.com/engineering/code-execution-with-mcp

### (deprecated) Sub-agents
- Agents are responsible for a specific task. The main agent (or system agent) is responsible for managing the conversation and delegating tasks to the sub-agents. Sub-agents can be created on the fly, and have their own memory and tools. They can also be persisted, and loaded for future conversations.
  Open questions:
- How to persist sub-agent memory?
- How to present sub-agents in the UI?
- Using Chatbot MCP server we could allow the LLM (main agent) call the MCP server to create a new chat session, and send a message with a specially crafted prompt to that session. When the sub-agent in the new chat session completes it's task, it sends a message (with task result) to the main agent to let it know it's done. The main agent can then decide what to do with the result.


---


## Performance improvements

### (deprecated) Override equals & hashCode for ChatMessage class
Make equals and hashCode methods based on the `id` and `updatedAt` fields only. This should improve the performance of the UI when rendering messages. (See MessageList.kt)

---

# AIChat Desktop App - New Feature User Stories

## New Features: Basic Interactions

### (done) NF.S1 - Interrupt Streaming Assistant Response
*   **Description:** As a user, I want to see a "Stop" button in place of the "Send" button while an assistant message is being streamed, so I can gracefully interrupt a long, undesirable, or irrelevant response before it completes.
*   **Estimate:** M
*   **Acceptance Criteria:**
    *   While an assistant message is actively streaming (i.e., `AssistantMessage.isComplete` is false), the "Send" button transforms into a "Stop" button.
    *   Clicking the "Stop" button sends a signal to the backend to stop the LLM streaming process.
    *   The UI updates immediately to reflect that the stream has stopped.
    *   The "Stop" button reverts to "Send" after the stream is interrupted.

### (done) NF.S2 - View Partial Assistant Message Content
*   **Description:** As a user, if an assistant message stream is interrupted (either by me stopping it or due to an error), I want to see the partial content that was generated before the interruption, so I don't lose the information that was already received.
*   **Estimate:** S
*   **Acceptance Criteria:**
    *   When an assistant message stream stops prematurely, the displayed message content includes all text received up to that point.
    *   The partial message content is stored in the `AssistantMessage` table.
    *   The UI clearly indicates that the message is incomplete or was interrupted.

### (done) NF.S3 - Display Streaming Interruption Error
*   **Description:** As a user, if an assistant message stream is interrupted due to an error (e.g., network issue, LLM error), I want to see a clear error message along with the partial content (if any), so I understand why the stream stopped unexpectedly.
*   **Estimate:** M
*   **Acceptance Criteria:**
    *   If an error occurs during message streaming, the `AssistantMessage.errorMessage` field is populated with a user-friendly description.
    *   The UI displays this error message clearly, alongside any partial content (NF.S2).
    *   The error message is visible in the chat interface where the message was being displayed.

### (done) NF.S4 - Delete Entire Message Thread
*   **Description:** As a user, I want to delete a specific message and all its descendant messages (i.e., the entire sub-thread originating from that message) with a single action, so I can quickly remove entire branches of a conversation that are no longer relevant.
*   **Estimate:** M
*   **Acceptance Criteria:**
    *   A "Delete Thread" button is available in the message actions menu for any message that has children.
    *   Clicking "Delete Thread" prompts a confirmation dialog warning about recursive deletion.
    *   Upon confirmation, the backend `deleteMessage` route is called with `mode=recursive` for the specified message ID.
    *   The backend successfully deletes the target message and all messages that are children or descendants of it from the `ChatMessageTable`.
    *   The frontend UI instantly removes all deleted messages from the display and updates the conversation structure.

### (done) NF.S5 - Insert Message Above Existing Message
*   **Description:** As a user, I want to insert a new message *above* an existing message in the conversation flow, so I can add historical context, a forgotten detail, or correct a previous statement without altering the original message or creating a new branch.
*   **Estimate:** M
*   **Acceptance Criteria:**
    *   An "Insert Above" button is available in the message actions menu.
    *   Clicking "Insert Above" opens an input field to compose a new message, visually placed above the target message.
    *   The newly inserted message maintains the same `parentMessageId` as the target message (if any) and becomes a new sibling before it.
    *   The UI updates to reflect the new message's position in the thread.

### (done) NF.S6 - Insert Message Below Existing Message
*   **Description:** As a user, I want to insert a new message *below* an existing message in the conversation flow, so I can add a follow-up, a clarification, or additional information that logically extends the preceding message without creating a new conversation branch.
*   **Estimate:** M
*   **Acceptance Criteria:**
    *   An "Insert Below" button is available in the message actions menu.
    *   Clicking "Insert Below" opens an input field to compose a new message, visually placed below the target message.
    *   The newly inserted message becomes a new child of the target message, maintaining its position in the thread.
    *   The UI updates to reflect the new message's position and the `childrenMessageIds` of the target message are updated.

### (done) NF.S7 - Select Text within Messages
*   **Description:** As a user, I want to be able to select and highlight specific portions of text within any message (user or assistant), so I can easily copy snippets, search for definitions, or interact with the text.
*   **Estimate:** S
*   **Acceptance Criteria:**
    *   All message content (user and assistant) in the chat display is selectable using standard text selection methods (e.g., click and drag with the mouse).
    *   Selected text can be copied to the clipboard using system-standard shortcuts (e.g., Ctrl+C/Cmd+C).

### (done) NF.S8 - Collapse/Expand Long Messages
*   **Description:** As a user, I want to collapse long messages to hide their full content and expand them when needed, so I can reduce visual clutter, improve readability, and easily navigate conversations with verbose responses.
*   **Estimate:** M
*   **Acceptance Criteria:**
    *   Messages exceeding a defined length (e.g., number of lines or characters) are initially displayed in a collapsed state, showing only a summary or the first few lines.
    *   A clear UI indicator (e.g., an "Expand" [button, "Show More" text) is present for collapsed messages.
    *   Clicking the indicator expands the message to reveal its full content.]()
    *   An indicator (e.g., "Collapse" button, "Show Less" text) is present for expanded messages to revert them to a collapsed state.

### (done) NF.S11 - Search Chat Messages
*   **Description:** As a user, I want to search for specific keywords or phrases within the messages of the currently active chat session, or even across all sessions, so I can quickly find relevant information or past discussions.
*   **Estimate:** L
*   **Acceptance Criteria:**
    *   A search input field is available in the UI (e.g., in the chat area or a global search bar).
    *   Typing in the search field filters messages in the current session, highlighting matches.
    *   An option exists to expand the search to all sessions, showing matching sessions and messages.
    *   Clicking a search result navigates to the relevant message in its session.

### (done) NF.S12 - Send Edited Message as New Prompt
*   **Description:** As a user, when I am editing a message (user or assistant), I want to have a "Send" button in addition to "Save" and "Cancel" within the edit area, so that the edited message is treated as a new prompt to the assistant, triggering a new LLM response and creating a new conversation branch.
*   **Estimate:** L
*   **Acceptance Criteria:**
    *   When a message is in edit mode (E3.S1, E3.S2), a "Send" button is displayed alongside "Save" and "Cancel".
    *   Clicking "Send" (instead of "Save") updates the message content locally, then uses this edited message as the `parentMessageId` for a new LLM request.
    *   The LLM generates a new response, which appears as a child of the edited message, starting a new conversation branch.
    *   The UI automatically switches to display this new branch.

### (done) NF.S13 - Regenerate Assistant Response
*   **Description:** As a user, I want to be able to re-trigger the generation of an assistant message, based on its original parent message and context, so I can obtain an alternative response if the previous one was unsatisfactory or incomplete, creating a new branch in the conversation.
*   **Estimate:** M
*   **Acceptance Criteria:**
    *   A "Regenerate" button is available in the message actions menu for assistant messages.
    *   Clicking "Regenerate" triggers a new LLM request using the context leading up to the assistant's parent message.
    *   A new assistant message is generated and displayed as a sibling to the original assistant message, creating a new conversation branch.
    *   The UI automatically switches to display this new branch.

### (done) NF.S17 - Quickly scroll to the bottom of the messages list
*   **Description:** As a user, I want to be able to quickly scroll to the bottom of the messages list to see the latest messages, so I can easily follow the conversation.
*   **Estimate:** S
*   **Acceptance Criteria:**
    *   A "Scroll to Bottom" button or similar UI element is available.
    *   Clicking the button smoothly scrolls the messages list to the bottom.
    *   The button is only visible when the messages list is not already at the bottom.

### (done) NF.S18 - Copy Message Content to Clipboard
*   **Description:** As a user, I want to be able to copy the content of a specific message to the clipboard, so I can easily share or use the message elsewhere.
*   **Estimate:** S
*   **Acceptance Criteria:**
    *   A "Copy" button or similar UI element is available for each message.
    *   Clicking the "Copy" button copies the message content to the clipboard.
    *   A brief visual confirmation (e.g., a "Copied!" tooltip) is shown to the user.

### (done) NF.S19 - Copy Visible Thread Branch to Clipboard
*   **Description:** As a user, I want to be able to copy the content of the currently visible thread branch to the clipboard, so I can easily share or use the conversation elsewhere.
*   **Estimate:** M
*   **Acceptance Criteria:**
    *   A "Copy Branch" button or similar UI element is available.
    *   Clicking the "Copy Branch" button copies the content of the currently visible thread branch to the clipboard.
    *   A brief visual confirmation (e.g., a "Copied!" tooltip) is shown to the user.

### (done) NF.S20 - Switch Between User Accounts
*   **Description:** As a user, I want to be able to switch between my personal account and any other accounts I have access to (for instance, admin account), without logging out, so I can easily switch between different personas or roles within the application.
*   **Estimate:** L
*   **Acceptance Criteria:**
    *   A "Switch Account" button or similar UI element is available.
    *   Clicking the "Switch Account" button displays a list of available accounts.
    *   Selecting an account does not log out the current user but simply switches the active account context.

### (done) NF.S21 - Secure Communication with Server
*   **Description:** As a user, I want all communication between the client and server to be encrypted, so my data is protected from interception.
*   **Estimate:** M
*   **Acceptance Criteria:**
    *   All communication between the client and server is encrypted using HTTPS.
    *   The server has a valid SSL/TLS certificate.
    *   The client verifies the server's certificate to prevent man-in-the-middle attacks.
    *   For local network (LAN) connections only: On first connection to the server, the client displays a warning if the server's certificate is self-signed. The user must confirm they trust the certificate before proceeding. The client stores the certificate for future use.
---

## New Features: Intermediate

### (done, partial) NF.E1 - User Accounts and Permissions (Epic)
*   **Description:** This epic covers the foundational work for implementing a multi-user environment where each user has their own secure account, distinct chat history, personal configurations, and definable privileges. It also addresses the sharing of public resources across users and robust administration capabilities.
*   **Estimate:** XL

#### (done) NF.E1.S1 - Register and Log In to Personal Account
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

#### (done) NF.E1.S3 - Access Shared Public Resources
*   **Description:** As a user, I want certain application resources, such as specific LLM providers, models, or settings profiles, to be marked as "public" and accessible to all users, so I don't have to reconfigure commonly used or institutionally provided resources myself.
*   **Estimate:** M
*   **Dependencies:** NF.E1.S2 - Manage User Accounts and Privileges (Admin)
*   **Acceptance Criteria:**
    *   Users with appropriate privileges (e.g., administrators, or specific roles defined by NF.E1.S2) can mark LLM providers, models, and settings profiles as "public" during their creation or editing.
    *   Public resources are clearly identifiable in the UI (e.g., with a "Public" badge).
    *   All users, regardless of their individual account, can view and select public LLM providers, models, and settings for their chat sessions.
    *   Standard users cannot edit or delete public resources.

### (done) NF.SM1 - Chat message content rendering support for Markdown and other formats
*   **Description:** As a user, I want chat message content to support rendering of Markdown and other formats, so I can read assistant responses with formatting, links, and other rich content.
*   **Estimate:** M
*   **Acceptance Criteria:**
    *   Chat message content supports rendering of Markdown and other formats.
    *   The UI provides a toggle to enable/disable Markdown rendering.

### (deprecated) NF.SM2 - Predefined LLM Tools
*   **Description:** As a user, I want to be able to select predefined tools (e.g web search), which the assistant can use to extend its capabilities.
*   **Estimate:** L
*   **Acceptance Criteria:**
    *   The user can select which tools are available for a specific prompt.
    *   Predefined tools are only available for LLMs that support tool calling.
    *   An admin user can configure predefined tools. (For instance, a web search tool that uses DuckDuckGo)
    *   When a tool is called, the UI displays a notification that the tool is being used.
    *   When an assistant message is generated, it includes a list of tools that were used to generate it.
    *   The user can select an item in the list to view the tool's output.

### (deprecated) NF.SM3 - Web search tool for assistant
*   **Description:** As a user, I want to be able to let the assistant perform a web search, so that I can get answers to questions that require real-time or up-to-date information.
*   **Estimate:** L
*   **Acceptance Criteria:**
    *    A web search tool can be turned on/off for a specific prompt.
    *    The tool is only available for LLMs that support tool calling.
    *    The tool uses DuckDuckGo to perform the search.
    *    The tool returns the search results to the assistant.
    *    The tool can be configured to use a different search engine.
    *    When the tool is called, the UI displays a notification that the tool is being used.
    *    When an assistant message is generated, it includes a list of tools that were used to generate it.
    *    The user can select an item in the list to view the tool's output.


## New Features: Advanced

### (deprecated) NF.SA1 - Define and Register Custom LLM Tools
*   **Description:** As an advanced user or developer, I want to define and register custom external functions or API calls as "tools" with the server, providing their schema and description, so that compatible LLMs can discover and invoke these tools to extend their capabilities (e.g., web search, custom data retrieval).
*   **Estimate:** L
*   **Acceptance Criteria:**
    *   A UI interface (e.g., a dedicated "Tools" section in settings) allows users to define new tools.
    *   Tool definition includes a unique name, a natural language description, and an OpenAPI-style JSON schema for its input parameters.
    *   The system allows specifying the actual backend endpoint or function that the tool executes.
    *   Registered tools are persisted on the server.
    *   The server exposes a mechanism for LLMs to query available tools and their schemas.

### (done) NF.SA2 - LLM Agent Automated Tool Use
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

### (done) NF.EA1 - Local MCP Servers (Epic)
*   **Description:** As a user, I want the LLM to be able to call local tools via MCP, so that the LLM can access real-time data and perform actions in the external world (such as searching the web, accessing local files, controlling Github repos, etc.).
*   **Estimate:** XL
*   **Acceptance Criteria:**
  *   The user can configure their own local (STDIO) MCP servers. This configuration is comprised of:
      *   A name for the server
      *   An executable command to launch the server (e.g., "java", "uv", "docker")
      *   Arguments to pass to the command
      *   Environment variables to set before launching the server (e.g., Github access token)
      *   A working directory to launch the server in (optional)
  *   The user can test the connection to the MCP server. (For instance, by executing an MCP tool manually, and seeing the result).
  *   The list of available MCP tools can be retrieved from the MCP server and added to the tool definitions table in the database (on the server). This has to be done at least once, when the user adds the MCP server.
  *   The user should be able to view the list of available MCP servers and tools from the UI.
  *   The user should be able to refresh the list of tools from the MCP server. (which will update the tool definitions table)
  *   The MCP server configuration is stored in the database and is linked to the user's account. (through a separate ownership table)
  *   The MCP tools are stored in the tool definitions table, and are linked to the MCP server that provides them. (through a separate linkage table)
  *   MCP servers can be started/stopped from within the application.
  *   The MCP clients are managed by the application. (i.e. the application launches the MCP server process, and manages the STDIO communication)
  *   The user can select which MCP servers are enabled for the current chat session.
  *   The user can select which tools from each MCP server are enabled for the current chat session.  
  *   The user can configure which tools from each MCP server are enabled/disabled by default.
  *   The LLM can request to call tools exposed by local MCP servers.
  *   The server can request the client application to execute MCP tools locally on behalf of the LLM (using WebSocket protocol). (Note: the server controls the LLM, and the client executes MCP tools locally)
  *   The application can send tool results back to the server (using WebSocket protocol). (The tool results are used by the LLM to generate a response.)


## Non-Functional Requirements (NFRs)
