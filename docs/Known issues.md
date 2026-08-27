# Known issues

## Category - UI/UX

### Hoverable screen elements are not working on mobile targets
- This is caused by the fact that mobile devices do not have a mouse. The hoverable elements are implemented using the `pointer` input type, which is not supported on mobile devices.

### Emojis (unicode) are not rendering correctly on wasm platform
Could this be due to the default font used by the wasm platform not supporting emojis?

### Sub-category: scroll behavior on ChatScreen

#### Auto-scroll not working when last message is not at the bottom of the screen

#### (fixed) Scroll-to-bottom button weird behavior
- Preconditions:
  - The user is editing a long message
  - The cursor position is somewhere at the top of the message being edited
- Observed behavior:
  - The user clicks the scroll-to-bottom button and the UI scrolls to bottom as expected
  - The user clicks somewhere at the bottom of the text field to continue editing the message, but the UI automatically scrolls way up again.
Note that when using the scrollbar to scroll to bottom, the UI behaves normally.

### (fixed) When the chat input area is automatically expanded while typing, it becomes out of focus.
- The user has to click back into the input area to continue typing.

#### (fixed) Undesirable auto-scroll behavior when switching branches
- When a branch is active that is already scrolled to bottom, switching to another branch, causes the UI to automatically scroll to bottom.

#### (fixed) Scrollbar acting weird on Desktop target
Observed behavior:
- Chat area: Sudden changes in (vertical) scrollbar size while scrolling.
  Question: Could this be related to the use of LazyColumn? This needs to be investigated further.
  Answer: Yes, this behavior is as expected and cannot be changed easily.
  Update: New observation: In very long chats it becomes impossible to scroll up. We probably need to remove the use of LazyColumn in combination with scrollbars.
  Fix applied: Use Column (with vertical scrollbar) instead of LazyColumn.


## Category - Networking
### (fixed) Websocket connections not working for WasmJs target
- Browser WebSocket APIs do not support arbitrary custom headers (including `Authorization`) during handshake.
- Implemented workaround: WasmJs now sends auth through `Sec-WebSocket-Protocol` using `chatbot-auth,<jwt>`.
- The server keeps normal `Authorization: Bearer <jwt>` behavior and falls back to parsing `Sec-WebSocket-Protocol` when `Authorization` is absent.
- Follow-up hardening idea: evaluate stricter `Origin` validation for browser-originated websocket handshakes.
- See: https://stackoverflow.com/questions/4361173/http-headers-in-websockets-client-api/4361358#4361358, https://devcenter.heroku.com/articles/websocket-security, https://ably.com/blog/websocket-authentication

### MCP Server connection timeout after 60 seconds
- Write test case that reproduces the issue and fix it.


## Category - Security
### (by design) Self-signed server certificates are not working for WasmJs target
- This is caused by the Browser's native networking stack enforcing certificate validation. There is no way to disable this behavior. The only solution is to use a CA signed certificate.
- During development this can be circumvented by manually going to the server URL in the browser and accepting the certificate. Or by using the 'mkcert' tool: https://github.com/FiloSottile/mkcert


## Category - Performance

### Slow execution times for some worker built-in tools
- The following tools are experiencing slow execution times, on large directory trees: `search_files` and `search_text`.
- The tools are traversing the entire directory tree regardless of glob pattern, even with non-recursive patterns, such as `*.kt`.

## Category - Other issues

### Updating LLM provider API key can cause issues
- observed behavior: When the user updates the API key for an LLM provider, it can cause the old key to stay in the database. Need to investigate further and fix the issue.

### (resolved) Incomplete Tool result error reporting
- When a built-in worker tool call returns error, the LLM only sees the error summary, not the full output. This is particulary important when calling the "run_command" tool, which often adds detailed error information to the output (stdout and stderr streams).

### (resolved) Tool call times out too early for run command tool
The run command tool often times out early. (See RunCommandTool.kt)
- The default timeout is currently set to 30 seconds. See: DefaultWorkerCommandDispatchService.defaultTimeout
- A possible solution could be to override the timeout in DefaultBuiltInToolDispatchService.dispatchToolCall (and other call sites). For instance, the run command tool has its own timeout property, which should be used instead.

### (resolved) Broken indentation after using edit file tool
- The broken indentation always seems to happen on the first line of the "old text" match. The amount of indentation on that line is always double the amount required.

### (resolved) Pressing stop button can cause unstable chat session state
Problem: When pressing the stop button while tools are executing, it works, but when trying to send a user message after that, some LLM providers will always return an error. That's because every tool call message must be matched by a tool result message, according to the OpenAI protocol.
Solution: Fix problem code in `DefaultChatContextBuilder.buildContext` and `DefaultConversationTurnOrchestrator.appendAssistantAndToolResults`.
- `DefaultChatContextBuilder.buildContext`: Currently, `RawChatMessage.Tool` objects are only added to the context for the tool call statuses SUCCESS, ERROR and USER_DENIED, but not for the others. We should add a new tool call status CANCELLED, and use that to write both `RawChatMessage.Tool` and `RawChatMessage.Assistant.ToolCall` objects to the context, when status is not SUCCESS, ERROR or USER_DENIED.
- `DefaultConversationTurnOrchestrator.appendAssistantAndToolResults`: similar issue here.

### (resolved) Inaccurate Diff reporting for edit_text tool
- A small change at the beginning of a file can cause a very long diff.
- Output is not in line with universal diff format. (For instance, as used by Git.)

### (resolved) Idle user can cause multiple tool call timeouts
The timeout for manually approving a tool call is currently set to 5 minutes, but when this happens the LLM will receive a tool result message with timeout error, and the tool call loop continues. So if the user is AFK, this will continue running until maximum number of loop iterations is reached (default value: 100). The easiest fix is to simply remove the 5-minute timeout completely. Or set it to a large value (for extra safety), but then the tool call loop should stop.

### (resolved) Hanging run_command tool
Sometimes the LLM calls the run_command tool with `args` as a single string (not as an array). This can cause the run command to hang. For instance when the command is `bash`, which requires user input when run without arguments. Solution: add support for `args` with single string value (in RunCommandTool.kt).

### (resolved) Tool names containing dot (".") cause LLM API Error
Some LLM providers do not support tool names with dots, leading to API errors when these tools are called. We should remove the code responsible for automatically adding a dot after the tool name prefix for built-in worker tools. 

### (resolved) Tool names containing non-standard characters can cause LLM API Error
Some LLM providers only support the characters `a-zA-Z0-9_-` in tool names, leading to API errors when these tools are called. We should validate tool names against this character set and reject any names containing unsupported characters.

### (resolved) Wrong usage of the `run_command` tool by some LLMs
Some less intelligent LLMs do not use the `run_command` tool correctly:
They put the full command into the `command` field instead of using the `args` field. A possible solution could be:
- Detect when the `command` field is not used correctly. For instance, when it contains spaces.
- Instead of executing it (which would fail), provide a helpful error message.

### (resolved) Permanent LLM API errors occur after an LLM uses a malformed JSON for built-in tool input  
This work is done:
```
Malformed JSON input is not handled well for built-in tools:
- See ToolCallInteraction.kt : Malformed JSON is converted to a JsonObject with a value of null
- For MCP tools this is handled better. See: MCPToolCallInteraction.kt and McpToolCallExecutorImpl.kt
```
But still some LLM providers return a permanent API error, when sending a request with malformed JSON within a tool call `arguments` string.

### (resolved) Input lag occurs while typing in search box
Lag occurs when typing the first few characters in the search text field (located in top app bar for the chatscreen). Most likely reason is that there are too many search matches for a single character. This could be resolved by introducing a delay for initiating a search action while typing.

### (resolved) Long search times for search_text tool
Problem: LLMs often don't use the `path` parameter, which leads to long search times. The tree with workspace as root has to be fully searched, because the default for `path` is set to ".", which is the workspace root.
Solutions:
- Make `path` a required parameter, so that the LLM is more likely to use a more specific path to start the search from.
- Include a search duration string in the output, so that the LLM knows how long the search took.
- If the search took too long (longer than 3 seconds), add a hint in the output to instruct the LLM to use a more specific path to start the search from, next time.
- Add a `timeout` parameter to limit search times. Set default to 5 seconds. On timeout show truncated search results, instead of returning an error. (also show trailing info message that search results were truncated due to timeout)

### (resolved) Misused `args` parameter in run_command tool
Problem: LLMs often use the `args` parameter as string, which leads to problems with execution.
Solution: Remove the option to use `args` with type string, and only allow array type.

### (resolved) Constant CPU usage for desktop app, while idle
Problem: The windows desktop app is consuming constant CPU usage of around 3%, even when idle, and window is minimized. It only seems to occur when tool badges are visible in the UI.

### (resolved) Duplicate lines displayed in output of `search_text` tool
Problem: When query text appears several times in the source, it can happen that some lines are displayed multiple times in the output. This happens when search occurrences are close to eachother, and contextBefore (or contextAfter) is greater than zero.
Solution: Compact the output, in order to remove duplicate lines.
Example: 
```kotlin
// ...
fun test: Test = 
Test (a = 4)
// ...
```
Searching for "Test" with contextAfter = 2, results in duplicate lines in output.

### (resolved) Desktop app freezes on very long LLM response
Problem: Sometimes an LLM goes haywire, and produces a very long output (up to 2 MB).
Solution: We should limit the output to a reasonable number. For instance, 64000 bytes.

### (resolved) LLM sometimes assumes sequential edits are possible for `edit_file` tool
Problem: LLM submits an array of edits, where the second edit depends on the first one being done already. But the second edit fails, because the tool doesn't support sequential edits.
Solution 1 (done): Clarify description in tool schema (see BuiltinToolCatalog.kt)
Solution 2: Explicitly support sequential edits, via an extra (boolean) tool parameter to enable it. (Defaults to false.)

### (resolved) LLM may not know that edit array indices are zero-based for `edit_file` tool
Solution: clarify error messages.

### (resolved) OpenRouter sometimes closes the connection unexpectedly
Problem: Sometimes Openrouter closes the connection unexpectedly and this results in a tool call loop that ends prematurely. The chatbot server writes an empty assistant message to the database, but it appears that the LLM was never called. The logs on the OpenRouter website show no entry about this request. The logs from the chatbot server show the following entries around this event:
```
my-chatbot-server  | 2026-08-02 17:06:31 [DefaultDispatcher-worker-12] DEBUG eu.torvian.chatbot.server.service.llm.LLMApiClientKtor - Received HTTP streaming response: 200 OK
my-chatbot-server  | 2026-08-02 17:06:31 [DefaultDispatcher-worker-12] DEBUG eu.torvian.chatbot.server.service.llm.strategy.OpenAIChatStrategy - Processing streaming response
my-chatbot-server  | 2026-08-02 17:06:31 [DefaultDispatcher-worker-11] DEBUG eu.torvian.chatbot.server.service.llm.LLMApiClientKtor - Completed reading UTF-8 lines from channel (total bytes read: 363)
my-chatbot-server  | 2026-08-02 17:06:31 [DefaultDispatcher-worker-6] DEBUG Exposed - UPDATE chat_messages SET content='', updated_at=1785690391699 WHERE chat_messages.id = 6200
my-chatbot-server  | 2026-08-02 17:06:31 [DefaultDispatcher-worker-6] DEBUG Exposed - SELECT chat_messages.id, chat_messages.session_id, chat_messages."role", chat_messages.content, chat_messages.created_at, chat_messages.updated_at, chat_messages.parent_message_id, chat_messages.children_message_ids, chat_messages.file_references, assistant_messages.message_id, assistant_messages.model_id, assistant_messages.settings_id FROM chat_messages LEFT JOIN assistant_messages ON chat_messages.id = assistant_messages.message_id WHERE chat_messages.id = 6200
my-chatbot-server  | 2026-08-02 17:06:31 [ktor-jetty-8080-12] INFO  SessionRoutes - WebSocket closed: sessionId=119}
my-chatbot-server  | 2026-08-02 17:06:31 [ktor-jetty-8080-14] INFO  SessionRoutes - WebSocket channel closed by client for session 119
```

The channel shows 363 bytes read, which is very low. (For healthy responses the number of bytes read is at least 3000 bytes.) I think the key is determining what the content is of these 363 bytes, so that we can get more information about what could be wrong, the next time this occurs.

### (resolved) The `search_files` tool seems to use case-sensitive matching
Solution: Explore whether case insensitive matching can be implemented efficiently. If that's the case:
- Add boolean parameter `caseSensitive` and set default to false. 

### (resolved) OpenRouter returns error 400 when LLM sends tool_calls without tool results
Problem: When the LLMApiClient sends a message with `tool_calls`, but does not send matching `tool_results` messages, OpenRouter returns an error 400. The logs from the chatbot server show the following entries around this event:

```
my-chatbot-server  | 2026-08-03 11:52:14 [DefaultDispatcher-worker-3] ERROR eu.torvian.chatbot.server.service.llm.LLMApiClientKtor - LLM API OpenRouter returned error (Streaming Status: 400): ApiError(statusCode=400, message=OpenAI API returned error 400: Provider returned error, errorBody={"error":{"message":"Provider returned error","code":400,"metadata":{"raw":"{\"error\":{\"message\":\"An assistant message with 'tool_calls' must be followed by tool messages responding to each 'tool_call_id'. (insufficient tool messages following tool_calls message)\",\"type\":\"invalid_request_error\",\"param\":null,\"code\":\"invalid_request_error\"}}","provider_name":"DeepSeek","is_byok":false,"provider_error_code":"invalid_request_error"}},"user_id":"...."})
```

Solution: fix problem code in DefaultChatContextBuilder.kt and DefaultConversationTurnOrchestrator.kt
- in DefaultChatContextBuilder.kt: add `.filter` on `ToolCallStatus` for assistant message tool calls, similar to tool result messages.
- in DefaultConversationTurnOrchestrator.kt: remove parameter `toolCallRequests` in function `appendAssistantAndToolResults`.. use only the parameter `completedToolCalls` 

### (resolved) Regenerate response and Branch & Continue buttons are available while receiving
Problem: Regenerate response and Branch & Continue buttons can always be clicked, which allows simultaenous websocket connections to be active for the same chat session. This can result in orphaned connections which can never be closed again.

### (resolved) Edit tool can cause very large outputs
Problem: When `newText` is much longer than `oldText`, and there are many occurences of `oldText` in the source text, the output diff can become very long. This can be very costly as it consumes many input tokens for the LLM on the next request.

### Message not updated in UI after editing, and clicking "Save"
This seems to happen only when switching to another session and back again, while the edit input box is still active.

### (resolved) Maximum tool call arguments size of 32,000 bytes not permissive enough
Increase to 100,000 bytes

### (resolved) Chat session names with line breaks can be entered in the UI
- It's currently not possible to see or remove such line break characters in the UI, when renaming. They can only be seen in the tool tip when hovering over a session name, which also reveals the text after a line break.
- The issue arises when copy-pasting a text with multiple lines into the session name field, when creating a new chat session.

### (resolved) Maximum number of tool call iterations (100) not permissive enough
Increase to 200.

### (resolved) Updating an agent role instruction is inefficient
Problem: If an LLM wants to update only a small part of an instruction, the entire list of instructions needs to be overwritten, which costs a lot of tokens, and is error prone.
Solution: Introduce three new tools: `insert_agent_role_instruction`, `edit_agent_role_instructions` and `remove_agent_role_instruction`. 
Tool parameters for `insert_agent_role_instruction`:
- `agent_role_id`
- `position`: the (zero-based) position in the instruction list where the new instruction should be inserted
- `instruction`: object with the following properties:
  - `type`: the type of instruction (e.g. "role", "main", "custom", "spawnable_agents", "model_specific" (see AgentInstructionTypes.kt))
  - `text`: the instruction text (must be null for "spawnable_agents" type)
  - `custom_properties`: optional object with custom properties (e.g. "model_id" for "model_specific" type)

Tool parameters for `edit_agent_role_instructions`:
- `agent_role_id`
- `edits`: array of edits, where each edit is an object with the following properties:
  - `old_text`: the text to be replaced
  - `new_text`: the new text to replace it with

Tool parameters for `remove_agent_role_instruction`:
- `agent_role_id`
- `position`: the (zero-based) position in the instruction list where the instruction should be removed

Note: The description of the `instructions` parameter of the `update_agent_role` tool should be updated to reflect the new tools. 
