# TODO list

## General
- [x] Add database migration support for server module.
- User should be given the standard user role (CommonRoles.STANDARD_USER) upon registration. (see UserServiceImpl)
- Errors from Ktor's authentication middleware (on the server) should be mapped to proper API errors. (ApiError class)
- Add better support for parallel tool calls (when a model calls multiple tools in a single response). Currently they are executed sequentially.
- Remove LLMProviderType.OPENROUTER because OpenRouter uses the same API as OpenAI. Also remove LLMProviderType.CUSTOM. And LLMProviderType.ANTHROPIC should be marked as not supported yet in the UI.
- The material icons libraries are now deprecated. Replace them with individual icon downloads. See TODO in app/build.gradle.kts. Other docs: https://kotlinlang.org/docs/multiplatform/compose-multiplatform-resources-usage.html#icons, https://developer.android.com/develop/ui/compose/graphics/images/material, https://developers.google.com/fonts/docs/material_icons#rtl_icons_on_android

## UI/UX
- After deleting a message, set scroll position to bottom of the parent message. (or top of child message)
- Improve LLM API error display to the user.
- Only show tool options in chat area if the selected model supports tool calling.
- Limit the displayed length of snackbar messages. Add "more" link to expand.
- Hide chat top bar (ChatTopBarContent), when no session is selected.
- Make auto-scrolling during assistant message streaming smoother. Currently, it feels a bit janky. Alternative: Start assistant message stream at the top of the viewport, with empty space below.
- Pretty print JSON in tool calls and tool results.
- Make text in info sections "status" and "configuration" in MCP server configuration screen selectable
- [x] Save expanded states of chat messages (in memory only), so that they are restored when the user revisits the session.
- Save scrollbar position (in memory only), so that it is restored when the user revisits the session (when in LRU cache).
- When the user selects a model in the chat area, the first available settings profile for that model should be selected automatically.
- MCP tools for chat session should be collapsed by default in the configuration dialog. 

## Security
- User account will be locked after a certain number of failed login attempts.
- Limit number of registration attempts per hour (in total and per IP address).

## Code quality


## Performance


## Android
- Use the Android keystore for storing encryption keys on Android. See KeyGenerator.android.kt.
- Add support for different screen sizes, in order to support portrait mode on mobile devices.

## WASM


## Misc
- Split server code in two parts, so that the app can be (optionally) used without the server. The API calls on the client side will need to be routed directly to mocked endpoints (instead of through Ktor). In order to achieve this, most of the server code will need to be made KMP-compatible. The part that cannot be made KMP-compatible (Ktor server) will remain in a separate module.
- (deprecated) Remove LocalMCPServerLocalTable. Store it on the server instead. Environment variables remain to be encrypted and stored locally on the client side.
