# Known bugs

## (done, not a bug) Scrollbar acting weird on Desktop target
Observed behavior:
  - Chat area: Sudden changes in (vertical) scrollbar size while scrolling.
Question: Could this be related to the use of LazyColumn? This needs to be investigated further.
Answer: Yes, this behavior is as expected and cannot be changed easily.

## When the chat input area is expanded while typing, it becomes out of focus.
- The user has to click back into the input area to continue typing.

## MCP server configuration: info sections "status" and "configuration" are not selectable
- This causes that the user cannot copy text from these sections.
