# TODO list

- After deleting a message, set scroll position to bottom of the parent message. (or top of child message)
- Split server code in two parts, so that the app can be (optionally) used without the server. The API calls on the client side will need to be routed directly to mocked endpoints (instead of through Ktor). In order to achieve this, most of the server code will need to be made KMP-compatible. The part that cannot be made KMP-compatible (Ktor server) will remain in a separate module.
