# Known bugs

## (fixed) Scrollbar acting weird on Desktop target
Observed behavior:
  - Chat area: Sudden changes in (vertical) scrollbar size while scrolling.
Question: Could this be related to the use of LazyColumn? This needs to be investigated further.
Answer: Yes, this behavior is as expected and cannot be changed easily.
Update: New observation: In very long chats it becomes impossible to scroll up. We probably need to remove the use of LazyColumn in combination with scrollbars.
Fix applied: Use Column (with vertical scrollbar) instead of LazyColumn.

## When the chat input area is automatically expanded while typing, it becomes out of focus.
- The user has to click back into the input area to continue typing.

## (fixed) Websocket connections not working for WasmJs target
- Browser WebSocket APIs do not support arbitrary custom headers (including `Authorization`) during handshake.
- Implemented workaround: WasmJs now sends auth through `Sec-WebSocket-Protocol` using `chatbot-auth,<jwt>`.
- The server keeps normal `Authorization: Bearer <jwt>` behavior and falls back to parsing `Sec-WebSocket-Protocol` when `Authorization` is absent.
- Follow-up hardening idea: evaluate stricter `Origin` validation for browser-originated websocket handshakes.
- See: https://stackoverflow.com/questions/4361173/http-headers-in-websockets-client-api/4361358#4361358, https://devcenter.heroku.com/articles/websocket-security, https://ably.com/blog/websocket-authentication

## (by design) Self-signed server certificates are not working for WasmJs target
- This is caused by the Browser's native networking stack enforcing certificate validation. There is no way to disable this behavior. The only solution is to use a CA signed certificate.
- During development this can be circumvented by manually going to the server URL in the browser and accepting the certificate. Or by using the 'mkcert' tool: https://github.com/FiloSottile/mkcert

## Selecting the "MCP servers" tab on the Settings screen results in a crash on WasmJs target
- This is caused by a missing implementation of the `LocalMCPServerViewModel` for the WasmJs target.
- Local MCP server functionality is difficult to implement for WasmJs. It would probably require a browser extension, in order to be able to launch local processes.
Suggested fix: Remove the tab for WasmJs target.

## Hoverable screen elements are not working on mobile targets
- This is caused by the fact that mobile devices do not have a mouse. The hoverable elements are implemented using the `pointer` input type, which is not supported on mobile devices.

## Auto-scroll not working when last message is not at the bottom of the screen

## (fixed) Undesirable auto-scroll behavior when switching branches
- When a branch is active that is already scrolled to bottom, switching to another branch, causes the UI to automatically scroll to bottom.

## MCP Server connection timeout after 60 seconds
- Write test case that reproduces the issue and fix it.