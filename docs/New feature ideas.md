# New feature ideas

## Basic features

### (done, partially) Allow message streaming process to stop gracefully
  - (not done yet) Add Boolean field to AssistantMessage to indicate if the message is complete.
  - Add error message String field to AssistantMessage (which can be shown in the UI, together with the partial content).
  - Store partial message content in AssistantMessage table, in case the stream was interrupted. (either due to an error, or because the user stopped the streaming)
  - In the UI, the "send" button should transform into a "stop" button while the message is streaming. 
 
### Allow user to pause an agentic response, in order to provide user feedback.
  - Add a "Pause" button to the input area, which is shown while a message is streaming and when tools are involved.
  - Pausing should stop the (web socket) streaming process, but only after the current assistant message is fully processed. This means that the message loop (used for tool calling) will be interrupted prematurely.
  - This feature is particularly useful for agentic LLM usage, where the user might want to interrupt an undesirable response and provide feedback.
  - When paused, the user can provide feedback to the assistant. This feedback is sent to the assistant as a new message, which becomes the next message in the conversation. Note: this behavior is no different from sending a new message, so there is no special "paused" state that needs to be tracked.

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

### Make chat session copyable to file/clipboard as JSON (for importing into other apps)

### Make chat session importable from file/clipboard (for ChatGPT export format)

### Make messages searchable

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

### Allow certain features to be enabled/disabled on a per-user basis (*Requires user preference feature)
The user should be able to configure this in their profile.

### Allow user to see LLM metadata for assistant responses

### (done) Allow user to quickly scroll to the bottom of the messages list

### (done) Allow user to switch accounts without logging out

### Allow admin to create new accounts for other users.
Users will be forced to change their password on first login.

### (done) Only allow connections to the server over HTTPS.
For security, the server should only be accessible over HTTPS, and not HTTP. This applies to connections over the internet, as well as local network connections.

### (done) Make session list panel collapsible
Add an icon button to expand/collapse panel in the top app bar, on the left side.
In the future we could add a vertical sidebar with icons on the left side of the screen. Clicking the session list icon would expand the panel, clicking it again would collapse it. This would only make sense when we have multiple panels to switch between. Currently we only have the session list panel, so a top app bar button is the best option.

### Allow user to specify chat group when adding a new session
Currently, the user can only add a new session to the "Ungrouped" group. In the future, we should allow the user to specify a group when adding a new session.

### (done) Allow user to clone a chat session
- Add a "Clone" button to the session actions menu. (see SessionItemActionsDropdown)
- Cloning a session should create a new session with the same name, but a new ID.
- The new session should have the same messages and tool calls, but with new IDs.
- The new session should have the same owner, current leaf message, configured tools, LLM model, settings, etc.

### (done) Popout message input area for entering long messages
- Currently the maximum number of visible lines is only 5. This makes it difficult to enter long messages.
- If possible, we should allow the user to pop out the message input area into a separate window.
- Alternative: make the input area part of the message list flow, which is already scrollable. (when user requests)

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


---

## Intermediate features

### (done) User accounts
Each user has their own set of credentials, models, settings, and chat history. Some things could be shared between users, such as public providers, models and settings. Certain users could be assigned special privileges, such as the ability to manage other users, or add public providers, models and settings. There must be at least one admin user, who has all privileges (or has at least the capability to assign privileges to theirself and other users)

### User profile customization (name, avatar, etc.)

### User settings customization (theme, language, feature toggles, etc.)

### Chat message content formatting (Markdown, HTML, etc.)

### Select available models for adding by querying LLM provider API

### (done, partial) Allow user to select predefined (built-in) LLM Tools for a specific prompt
Predefined tools could include: Web search, calculator, current weather, etc.
note: Web search not working yet.

### (done, partial) Allow user to add file references when sending a message
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

### Add "Agent Skills" feature
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

### Add support for OpenAI's Responses API
- See: https://developers.openai.com/api/docs/guides/migrate-to-responses
- We can detect if a model supports the Responses API by sending a POST request to: https://base_url/responses/input_tokens with body: {"model": "<model_name>", "input": "Hello"}. This endpoint is used to count input tokens. If the model supports the Responses API, the response will include the number of input tokens. If not, we get a 404 error. A valid response should be of the form: {"object": "response.input_tokens","input_tokens": 7}.
- If a model supports the Responses API, add an entry to the model capabilities property (LLMModel.capabilities). For instance, add '"ResponsesAPI" = true' to the JSON object.
- Modify OpenAIChatStrategy.kt to support Responses API. Within the function 'prepareRequest' we can check for the model capability we added. And in the function 'processSuccessResponse' we can check if the field "object" in the response body contains the value "response". (For the Chat Completions API the value is "chat.completion"). 

### Add support for MCP's resources and prompts
- The user should be able to retrieve a resource or prompt, if currently active for the chat session. (The MCP server will be contacted to retrieve the resource or prompt)
- When at least one resource or prompt is active, the UI should indicate this by showing an icon button with badge (indicating the total number of active resources and prompts) underneath the chat input area. Clicking the icon should open a popup with a list of all active resources and prompts (with name, description and type). Clicking an item in the list should copy it's content to the chat input area, or, if the item requires arguments it should open a dialog to enter the arguments, and then copy the formatted resource or prompt to the chat input area.

### (done) Allow user to seamlessly switch between chat sessions while requests are in progress
- The streaming process should continue running, allowing for parallel processing of multiple requests.

### Allow user to queue multiple messages while a request is in progress
- The user should be able to queue multiple messages while a request is in progress. The queue should be processed in the order they were received.
- A queue message button should appear in the input area when a request is in progress. Clicking the button should add the current message to the queue. The button should be visible for the duration of the request.

### Memory spaces
Allow users to create multiple memory spaces, which are separate knowledge bases that can be loaded into the LLM context. This allows users to organize their knowledge base into different topics or projects, and only load the relevant information for each chat session. For instance, a user could have a "Work" memory space with information about their job, and a "Personal" memory space with information about their personal life. When starting a new chat session, the user can choose which memory space(s) to load into the LLM context.
- Each memory space has an AGENTS.md file, which is loaded into the LLM context when the memory space is loaded. This file contains instructions for the LLM on how to use the information in the memory space, as well as general information about the topic of the memory space.
- A memory space can contain both structured and unstructured information. Structured data may be stored as entities and relationships in a graph database or in a relational SQL database (depending on access and query patterns). Unstructured information is stored as text documents. The LLM can query the structured store for relevant entities/relations and consult the text documents for detailed context.
- The agent can add/remove items to/from the memory space, and update the AGENTS.md file as needed. This allows the memory space to evolve over time, and for the LLM to learn from its interactions with the user and the environment.

### Skill spaces
Allow users to create multiple skill spaces, which are separate collections of SKILL folders. This allows users to organize their skills into different topics or projects, and only load the relevant skills for each chat session. For instance, a user could have a "Programming" skill space with skills related to programming tasks, and a "Cooking" skill space with skills related to cooking tasks. When starting a new chat session, the user can choose which skill space(s) to load into the LLM context.
- Each skill space has an AGENTS.md file, which is loaded into the LLM context when the skill space is loaded. This file contains instructions for the LLM on when and how to use the skills in the skill space, as well as general information about the topic of the skill space.
- The agent can add/remove skills to/from the skill space, and update the AGENTS.md file as needed. This allows the skill space to evolve over time, and for the LLM to learn from its interactions with the user and the environment.

### Workspaces
Combine memory spaces and skill spaces into workspaces. A workspace is a collection of memory spaces and skill spaces that are relevant for a specific topic or project. When starting a new chat session, the user can choose which workspace to load into the LLM context, which will load all the memory spaces and skill spaces associated with that workspace.

---

## Advanced features

### (done) Allow users to add local MCP servers
Users should be able to add local (STDIO) MCP servers to their account, by specifying a local command to launch the MCP server. This command will run on the client machine, and communicate with the AIChat app over STDIO.

### Allow users to add remote MCP servers
Users should be able to add remote (Streamable HTTP) MCP servers to their account, by specifying the server URL.

### (done) Add option to add tools to MCP server
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
Allow LLM agent to control the app itself. For instance: modifying a chat session, Summarizing it, or creating a new session.

### AI assistant for prompting
- Assistant gives suggestions for follow-up questions, based on the conversation history. (and possibly other things)
- Assistant can be used to enhance (rephrase) the user's prompt, based on the conversation history.

### Allow LLM to enable/disable (MCP) tools for the current chat session
- Give LLM access to a set of tools: 
  - Tool to enable/disable tool for the current chat session. (parameters: tool name, enable/disable)
  - Tool to list all available tools for the current chat session.
  - Tool to search for tools based on a keyword. (match against tool name and description)
- The purpose of this feature is to reduce the context size, by only including the tools that are relevant for the current task.

### LLM Council
Have multiple LLM's (or agent runs) solve the same task, and then have a final LLM summarize the answers and decide which answer is the best.

### Chatbot MCP server (which can be used as a local MCP server within the Chatbot app)
- The API for the chatbot server application is quite big, and not all of it is needed for an MCP server. Still, the number of MCP functions will be quite large. So we need to add a second layer for the MCP server. For instance: a search function for MCP functions and a generic execute function which takes a function name and parameters. That way the MCP server would require only two functions: search and execute. Other ideas: "Code mode" as described by FastMCP: https://www.jlowin.dev/blog/fastmcp-3-1-code-mode, Cloudflare: https://blog.cloudflare.com/code-mode/, and Anthropic: https://www.anthropic.com/engineering/code-execution-with-mcp

### Sub-agents
- Agents are responsible for a specific task. The main agent (or system agent) is responsible for managing the conversation and delegating tasks to the sub-agents. Sub-agents can be created on the fly, and have their own memory and tools. They can also be persisted, and loaded for future conversations.
Open questions: 
- How to persist sub-agent memory?
- How to present sub-agents in the UI?
- Using Chatbot MCP server we could allow the LLM (main agent) call the MCP server to create a new chat session, and send a message with a specially crafted prompt to that session. When the sub-agent in the new chat session completes it's task, it sends a message (with task result) to the main agent to let it know it's done. The main agent can then decide what to do with the result.

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

### (done) NF.S17 - Quickly scroll to the bottom of the messages list
*   **Description:** As a user, I want to be able to quickly scroll to the bottom of the messages list to see the latest messages, so I can easily follow the conversation.
*   **Estimate:** S
*   **Acceptance Criteria:**
    *   A "Scroll to Bottom" button or similar UI element is available.
    *   Clicking the button smoothly scrolls the messages list to the bottom.
    *   The button is only visible when the messages list is not already at the bottom.

### NF.S18 - Copy Message Content to Clipboard
*   **Description:** As a user, I want to be able to copy the content of a specific message to the clipboard, so I can easily share or use the message elsewhere.
*   **Estimate:** S
*   **Acceptance Criteria:**
    *   A "Copy" button or similar UI element is available for each message.
    *   Clicking the "Copy" button copies the message content to the clipboard.
    *   A brief visual confirmation (e.g., a "Copied!" tooltip) is shown to the user.

### NF.S19 - Copy Visible Thread Branch to Clipboard
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

#### (done) NF.E1.S3 - Access Shared Public Resources
*   **Description:** As a user, I want certain application resources, such as specific LLM providers, models, or settings profiles, to be marked as "public" and accessible to all users, so I don't have to reconfigure commonly used or institutionally provided resources myself.
*   **Estimate:** M
*   **Dependencies:** NF.E1.S2 - Manage User Accounts and Privileges (Admin)
*   **Acceptance Criteria:**
    *   Users with appropriate privileges (e.g., administrators, or specific roles defined by NF.E1.S2) can mark LLM providers, models, and settings profiles as "public" during their creation or editing.
    *   Public resources are clearly identifiable in the UI (e.g., with a "Public" badge).
    *   All users, regardless of their individual account, can view and select public LLM providers, models, and settings for their chat sessions.
    *   Standard users cannot edit or delete public resources.

### NF.SM1 - Chat message content rendering support for Markdown and other formats
*   **Description:** As a user, I want chat message content to support rendering of Markdown and other formats, so I can read assistant responses with formatting, links, and other rich content.
*   **Estimate:** M
*   **Acceptance Criteria:**
    *   Chat message content supports rendering of Markdown and other formats.
    *   The UI provides a toggle to enable/disable Markdown rendering.

### NF.SM2 - Predefined LLM Tools
*   **Description:** As a user, I want to be able to select predefined tools (e.g web search), which the assistant can use to extend its capabilities.
*   **Estimate:** L
*   **Acceptance Criteria:**
    *   The user can select which tools are available for a specific prompt.
    *   Predefined tools are only available for LLMs that support tool calling.
    *   An admin user can configure predefined tools. (For instance, a web search tool that uses DuckDuckGo)
    *   When a tool is called, the UI displays a notification that the tool is being used.
    *   When an assistant message is generated, it includes a list of tools that were used to generate it.
    *   The user can select an item in the list to view the tool's output.

### NF.SM3 - Web search tool for assistant
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

### NF.SA1 - Define and Register Custom LLM Tools
*   **Description:** As an advanced user or developer, I want to define and register custom external functions or API calls as "tools" with the server, providing their schema and description, so that compatible LLMs can discover and invoke these tools to extend their capabilities (e.g., web search, custom data retrieval).
*   **Estimate:** L
*   **Acceptance Criteria:**
    *   A UI interface (e.g., a dedicated "Tools" section in settings) allows users to define new tools.
    *   Tool definition includes a unique name, a natural language description, and an OpenAPI-style JSON schema for its input parameters.
    *   The system allows specifying the actual backend endpoint or function that the tool executes.
    *   Registered tools are persisted on the server.
    *   The server exposes a mechanism for LLMs to query available tools and their schemas.

### NF.SA2 - LLM Agent Automated Tool Use
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

### NF.EA1 - Local MCP Servers (Epic)
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