# Known bugs

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

### Pressing stop button can cause unstable chat session state
When pressing the stop button while tools are executing, it works, but when trying to send a user message after that, the LLM will always return an error. That's because every tool call message must be matched by a tool result message in the OpenAI protocol.

### (resolved) Inaccurate Diff reporting for edit_text tool
- A small change at the beginning of a file can cause a very long diff.
- Output is not in line with universal diff format. (For instance, as used by Git.)

### Idle user can cause multiple tool call timeouts
The timeout for manually approving a tool call is currently set to 5 minutes, but when this happens the LLM will receive a tool result message with timeout error, and the tool call loop continues. So if the user is AFK, this will continue running until maximum number of loop iterations is reached (default value: 100). The easiest fix is to simply remove the 5-minute timeout completely. Or set it to a large value (for extra safety), but then the tool call loop should stop.

### Hanging run_command tool
Sometimes the LLM calls the run_command tool with `args` as a single string (not as an array). This can cause the run command to hang. For instance when the command is `bash`, which requires user input when run without arguments. Solution: add support for `args` with single string value (in RunCommandTool.kt).

### (resolved) Tool names containing dot (".") cause LLM API Error
Some LLM providers do not support tool names with dots, leading to API errors when these tools are called. We should remove the code responsible for automatically adding a dot after the tool name prefix for built-in worker tools. 

### (resolved) Tool names containing non-standard characters can cause LLM API Error
Some LLM providers only support the characters `a-zA-Z0-9_-` in tool names, leading to API errors when these tools are called. We should validate tool names against this character set and reject any names containing unsupported characters.
