# Known issues

## Category - UI/UX

### Hoverable screen elements are not working on mobile targets
- This is caused by the fact that mobile devices do not have a mouse. The hoverable elements are implemented using the `pointer` input type, which is not supported on mobile devices.

### Emojis (unicode) are not rendering correctly on wasm platform
Could this be due to the default font used by the wasm platform not supporting emojis?

### Sub-category: scroll behavior on ChatScreen

#### Auto-scroll not working when last message is not at the bottom of the screen

## Category - Networking

### MCP Server connection timeout after 60 seconds
- Write test case that reproduces the issue and fix it.

## Category - Security

## Category - Performance

### Slow execution times for some worker built-in tools
- The following tools are experiencing slow execution times, on large directory trees: `search_files` and `search_text`.
- The tools are traversing the entire directory tree regardless of glob pattern, even with non-recursive patterns, such as `*.kt`.

## Category - Other issues

### Updating LLM provider API key can cause issues
- observed behavior: When the user updates the API key for an LLM provider, it can cause the old key to stay in the database. Need to investigate further and fix the issue.

### Message not updated in UI after editing, and clicking "Save"
This seems to happen only when switching to another session and back again, while the edit input box is still active.

### (resolved?) Adding a user message can cause no reasoning tokens to be replayed
- Issue seems provider-related. Not caused by the app.
