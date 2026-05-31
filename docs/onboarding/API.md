# Torvian Chatbot API Documentation

## Overview
The server provides a RESTful API built with Ktor using the Resources plugin for type-safe routing. It also supports WebSocket connections for real-time chat streaming and worker communication.

| Aspect | Details |
|--------|---------|
| Base URL | `https://yourdomain.com/api/v1` |
| WebSocket | `wss://yourdomain.com/api/v1/ws` |
| Authentication | JWT Bearer tokens (access & refresh) |
| Protocol | HTTP/1.1 + WebSocket |

## Authentication Endpoints (`/api/v1/auth`)

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/v1/auth/register` | POST | Register a new user account |
| `/api/v1/auth/login` | POST | Login with credentials |
| `/api/v1/auth/logout` | POST | Logout current session |
| `/api/v1/auth/logout-all` | POST | Logout all sessions |
| `/api/v1/auth/refresh` | POST | Refresh access token |
| `/api/v1/auth/me` | GET | Get current user info |
| `/api/v1/auth/change-password` | POST | Change password |
| `/api/v1/auth/change-email` | POST | Change email |
| `/api/v1/auth/service-token` | POST | Request service token (for workers) |
| `/api/v1/auth/trusted-devices` | GET | List trusted devices |

## Chat Endpoints

### Sessions (`/api/v1/sessions`)
| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/v1/sessions` | GET | List all sessions |
| `/api/v1/sessions` | POST | Create new session |
| `/api/v1/sessions/{sessionId}` | GET | Get session details |
| `/api/v1/sessions/{sessionId}` | DELETE | Delete session |
| `/api/v1/sessions/{sessionId}/name` | PUT | Update session name |
| `/api/v1/sessions/{sessionId}/model` | PUT | Update session model |
| `/api/v1/sessions/{sessionId}/settings` | PUT | Update session settings |
| `/api/v1/sessions/{sessionId}/leafMessage` | PUT | Update current leaf message |
| `/api/v1/sessions/{sessionId}/group` | PUT | Assign session to group |
| `/api/v1/sessions/{sessionId}/clone` | POST | Clone session |
| `/api/v1/sessions/{sessionId}/messages` | GET | Get session messages (SSE stream) |
| `/api/v1/sessions/{sessionId}/messages` | POST | Send new message (SSE stream) |
| `/api/v1/sessions/{sessionId}/toolcalls` | GET | Get tool calls for session |

### Messages (`/api/v1/messages`)
| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/v1/messages/{messageId}` | GET | Get message by ID |
| `/api/v1/messages/{messageId}` | DELETE | Delete message (SINGLE or RECURSIVE) |
| `/api/v1/messages/{messageId}/content` | PUT | Update message content |
| `/api/v1/messages/insert` | POST | Insert new message at position |

### Groups (`/api/v1/groups`)
| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/v1/groups` | GET | List all groups |
| `/api/v1/groups` | POST | Create new group |
| `/api/v1/groups/{groupId}` | GET | Get group details |
| `/api/v1/groups/{groupId}` | PUT | Update group |
| `/api/v1/groups/{groupId}` | DELETE | Delete group |

## LLM Configuration Endpoints

### Providers (`/api/v1/providers`)
| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/v1/providers` | GET | List all LLM providers |
| `/api/v1/providers` | POST | Add new LLM provider |
| `/api/v1/providers/{providerId}` | GET | Get provider details |
| `/api/v1/providers/{providerId}` | PUT | Update provider |
| `/api/v1/providers/{providerId}` | DELETE | Delete provider |
| `/api/v1/providers/{providerId}/test` | POST | Test provider connection |
| `/api/v1/providers/{providerId}/models` | GET | Discover models from provider |

### Models (`/api/v1/models`)
| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/v1/models` | GET | List all LLM models |
| `/api/v1/models` | POST | Add new LLM model |
| `/api/v1/models/{modelId}` | GET | Get model details |
| `/api/v1/models/{modelId}` | PUT | Update model |
| `/api/v1/models/{modelId}` | DELETE | Delete model |

### Settings (`/api/v1/settings`)
| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/v1/settings` | GET | List all model settings |
| `/api/v1/settings` | POST | Create model settings |
| `/api/v1/settings/{settingsId}` | GET | Get settings details |
| `/api/v1/settings/{settingsId}` | PUT | Update settings |
| `/api/v1/settings/{settingsId}` | DELETE | Delete settings |

## Tool Endpoints

### Tools (`/api/v1/tools`)
| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/v1/tools` | GET | List all tool definitions |
| `/api/v1/tools` | POST | Create tool definition |
| `/api/v1/tools/{toolId}` | GET | Get tool details |
| `/api/v1/tools/{toolId}` | PUT | Update tool |
| `/api/v1/tools/{toolId}` | DELETE | Delete tool |
| `/api/v1/tools/{toolId}/enabled` | PUT | Enable/disable tool |
| `/api/v1/tools/{toolId}/approval` | PUT | Set approval preference |

### MCP Servers (`/api/v1/mcp/servers`)
| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/v1/mcp/servers` | GET | List all MCP servers |
| `/api/v1/mcp/servers` | POST | Create MCP server |
| `/api/v1/mcp/servers/{serverId}` | GET | Get server details |
| `/api/v1/mcp/servers/{serverId}` | PUT | Update MCP server |
| `/api/v1/mcp/servers/{serverId}` | DELETE | Delete MCP server |
| `/api/v1/mcp/servers/{serverId}/test` | POST | Test MCP server connection |
| `/api/v1/mcp/servers/{serverId}/start` | POST | Start MCP server |
| `/api/v1/mcp/servers/{serverId}/stop` | POST | Stop MCP server |

### MCP Tools (`/api/v1/mcp/tools`)
| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/v1/mcp/tools` | GET | List all MCP tools |
| `/api/v1/mcp/tools/{toolId}` | GET | Get tool details |

## Admin Endpoints

### Users (`/api/v1/users`)
| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/v1/users` | GET | List all users |
| `/api/v1/users` | POST | Create new user |
| `/api/v1/users/{userId}` | GET | Get user details |
| `/api/v1/users/{userId}` | PUT | Update user |
| `/api/v1/users/{userId}` | DELETE | Delete user |
| `/api/v1/users/{userId}/status` | PUT | Update user status |
| `/api/v1/users/{userId}/roles` | GET | Get user roles |
| `/api/v1/users/{userId}/roles` | POST | Assign role to user |
| `/api/v1/users/{userId}/roles/{roleId}` | DELETE | Revoke role from user |

### Roles (`/api/v1/roles`)
| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/v1/roles` | GET | List all roles |
| `/api/v1/roles` | POST | Create new role |
| `/api/v1/roles/{roleId}` | GET | Get role details |
| `/api/v1/roles/{roleId}` | PUT | Update role |
| `/api/v1/roles/{roleId}` | DELETE | Delete role |
| `/api/v1/roles/{roleId}/permissions` | GET | Get role permissions |

### User Groups (`/api/v1/user-groups`)
| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/v1/user-groups` | GET | List all user groups |
| `/api/v1/user-groups` | POST | Create new group |
| `/api/v1/user-groups/{groupId}` | GET | Get group details |
| `/api/v1/user-groups/{groupId}` | PUT | Update group |
| `/api/v1/user-groups/{groupId}` | DELETE | Delete group |
| `/api/v1/user-groups/{groupId}/members` | GET | List group members |
| `/api/v1/user-groups/{groupId}/members/{userId}` | POST | Add user to group |
| `/api/v1/user-groups/{groupId}/members/{userId}` | DELETE | Remove user from group |

## Worker Endpoints (`/api/v1/workers`)
| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/v1/workers` | GET | List registered workers |
| `/api/v1/workers/register` | POST | Register new worker |
| `/api/v1/workers/{workerId}` | GET | Get worker details |
| `/api/v1/workers/{workerId}` | PUT | Update worker |
| `/api/v1/workers/{workerId}` | DELETE | Delete worker |

## WebSocket Endpoints
| Endpoint | Description |
|----------|-------------|
| `/api/v1/ws` | Real-time chat streaming and worker communication |

## Access Control
All endpoints support role-based and resource-based access control. Users can only access resources they own or that are shared with their user group. Admin endpoints require the `ADMIN` role.
