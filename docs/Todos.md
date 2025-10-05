# TODO list

- After deleting a message, set scroll position to bottom of the parent message. (or top of child message)
- Split server code in two parts, so that the app can be (optionally) used without the server. The API calls on the client side will need to be routed directly to mocked endpoints (instead of through Ktor). In order to achieve this, most of the server code will need to be made KMP-compatible. The part that cannot be made KMP-compatible (Ktor server) will remain in a separate module.
- User account will be locked after a certain number of failed login attempts.
- Limit number of registration attempts per hour (in total and per IP address).
- User should be given the standard user role (CommonRoles.STANDARD_USER) upon registration. (see UserServiceImpl)
- Errors from Ktor's authentication middleware (on the server) should be mapped to proper API errors. (ApiError class)