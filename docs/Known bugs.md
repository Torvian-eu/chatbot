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

## Category - Other issues

### Updating LLM provider API key can cause issues
- observed behavior: When the user updates the API key for an LLM provider, it can cause the old key to stay in the database. Need to investigate further and fix the issue.
