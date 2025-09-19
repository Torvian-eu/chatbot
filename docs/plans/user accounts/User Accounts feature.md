# Feature Specification and Implementation Analysis: User Accounts and Permissions

## 1. Feature Description & Scope

This document outlines the requirements and proposed implementation strategy for introducing user accounts and a robust permission system into our chatbot application. The goal is to transform the application from a single-user system into a multi-user environment where each user has their private space while allowing for controlled sharing of resources.

### User accounts

Each user has their own set of credentials, models, settings, and chat history. Some things could be shared between users, such as public providers, models and settings. Certain users could be assigned special privileges, such as the ability to manage other users, or add public providers, models and settings. There must be at least one admin user, who has all privileges (or has at least the capability to assign privileges to theirself and other users).

### NF.E1 - User Accounts and Permissions (Epic)

*   **Description:** This epic covers the foundational work for implementing a multi-user environment where each user has their own secure account, distinct chat history, personal configurations, and definable privileges. It also addresses the sharing of public resources across users and robust administration capabilities.
*   **Estimate:** XL

### NF.E1.S1 - Register and Log In to Personal Account

*   **Description:** As a user, I want to register for a new account with unique credentials and subsequently log in, so that my chat history, personal model configurations, and settings are private, securely stored, and distinct from other users of the application.
*   **Estimate:** L
*   **Acceptance Criteria:**
    *   The application presents a login screen on startup if no user is authenticated.
    *   A "Register New Account" option is available on the login screen.
    *   The registration process allows users to create a username and password.
    *   Successfully registered users can log in using their credentials.
    *   Upon successful login, the user's personal chat sessions, models, and settings are loaded and displayed.
    *   Passwords are securely hashed and stored (not plaintext).
    *   There is a clear indication in the UI of the currently logged-in user.

### NF.E1.S2 - Manage User Accounts and Privileges (Admin)

*   **Description:** As an administrator, I want to view, create, edit, and delete user accounts, and assign specific roles or privileges (e.g., create public resources, manage other users' permissions), so I can control access levels and administrative capabilities across the application.
*   **Estimate:** L
*   **Dependencies:** NF.E1.S1 - Register and Log In to Personal Account
*   **Acceptance Criteria:**
    *   A dedicated "User Management" section is accessible only to users with administrator privileges.
    *   Admins can view a list of all registered users, their usernames, and assigned roles/privileges.
    *   Admins can create new user accounts, assigning an initial role (e.g., standard user, admin).
    *   Admins can modify existing user roles and assign/revoke specific privileges (e.g., "Can create public providers", "Can manage other users").
    *   Admins can delete user accounts (with confirmation), ensuring all associated user data is handled according to policy.
    *   At least one admin user is guaranteed to exist or be created during initial setup.

### NF.E1.S3 - Access Shared Public Resources

*   **Description:** As a user, I want certain application resources, such as specific LLM providers, models, or settings profiles, to be marked as "public" and accessible to all users, so I don't have to reconfigure commonly used or institutionally provided resources myself.
*   **Estimate:** M
*   **Dependencies:** NF.E1.S2 - Manage User Accounts and Privileges (Admin)
*   **Acceptance Criteria:**
    *   Users with appropriate privileges (e.g., administrators, or specific roles defined by NF.E1.S2) can mark LLM providers, models, and settings profiles as "public" during their creation or editing.
    *   Public resources are clearly identifiable in the UI (e.g., with a "Public" badge).
    *   All users, regardless of their individual account, can view and select public LLM providers, models, and settings for their chat sessions.
    *   Standard users cannot edit or delete public resources.

---

## 2. Executive Summary of Implementation Strategy

The current chatbot application operates as a single-user system with no authentication, user management, or data isolation. Implementing the `NF.E1 - User Accounts and Permissions` epic will fundamentally transform the application into a multi-user environment.

A key architectural decision for this implementation is to **keep all existing database tables structurally intact**. Instead of adding `user_id` columns to existing tables, we will introduce new **association tables** (also known as link or join tables) defined using **JetBrains Exposed ORM**. These new tables will explicitly define user ownership of private resources and user group access to shared resources.

For "public" resources, we will implement a special, non-deletable **"All Users" group**. All newly registered users will be automatically assigned to this group, and no user can be removed from it. A resource is designated as "public" by being associated with the "All Users" group via the new access tables. This provides a clear, integrity-enforced mechanism for managing shared resources without altering core data tables.

This strategy minimizes migration risks to existing data and preserves the current database schema, while enabling the full suite of multi-user functionality, authentication, and authorization.

## 3. Current State Analysis

### Authentication & User Management

-   **Status**: No authentication or user management system exists.
-   **Current behavior**: The application starts directly to the chat interface without any login prompt.
-   **Data isolation**: All data (chat sessions, messages, LLM providers, models, settings, API secrets) is globally accessible to anyone running the application instance.
-   **Security**: There is no authentication, authorization, or session management in place.

### Database Schema

-   **Current tables**: `chat_sessions`, `chat_groups`, `llm_providers`, `llm_models`, `model_settings`, `chat_messages`, `assistant_messages`, `api_secrets`, `session_current_leaf`.
-   **Data ownership**: All data is implicitly global; there's no concept of a record belonging to a specific user.
-   **User context**: No tables contain a `user_id` column or any other direct link to a user entity.
-   **ORM**: All existing database interactions use JetBrains Exposed.

### Application Architecture

-   **Entry points**: The application directly loads the main chat interface.
-   **State management**: Global application state with no user-specific context.
-   **API security**: No authentication middleware protects API routes.
-   **Session handling**: No concept of user sessions, only application-level state.

## 4. Required Changes by Component

### 4.1. Database Schema Changes (Using JetBrains Exposed)

This implementation strategy focuses on adding new tables using JetBrains Exposed for user management and resource association, ensuring all existing tables remain structurally unchanged.

*(Note: For clarity, existing table objects (`ChatSessionTable`, `LLMProviderTable`, etc.) are referenced below. You will use the existing definitions from `server/src/main/kotlin/eu/torvian/chatbot/server/data/tables/`)*

```kotlin
// Import necessary Exposed classes
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp // If using Instant for timestamps
// import existing table objects from your project
import eu.torvian.chatbot.server.data.tables.ChatSessionTable
import eu.torvian.chatbot.server.data.tables.ChatGroupTable
import eu.torvian.chatbot.server.data.tables.ModelSettingsTable
import eu.torvian.chatbot.server.data.tables.ApiSecretTable
import eu.torvian.chatbot.server.data.tables.LLMProviderTable
import eu.torvian.chatbot.server.data.tables.LLMModelTable

// --- New Core User Management Tables ---

/**
 * Exposed table definition for user accounts.
 */
object UsersTable : LongIdTable("users") {
    val username = varchar("username", 255).uniqueIndex()
    val passwordHash = varchar("password_hash", 255)
    val email = varchar("email", 255).nullable().uniqueIndex()
    val createdAt = long("created_at") // Using epoch milliseconds as per existing tables
    val updatedAt = long("updated_at")
    val lastLogin = long("last_login").nullable()
}

/**
 * Exposed table definition for user roles (e.g., Admin, Standard User).
 */
object RolesTable : LongIdTable("roles") {
    val name = varchar("name", 50).uniqueIndex()
    val description = text("description").nullable()
}

/**
 * Exposed table definition for granular permissions (e.g., create_public_provider, manage_users).
 */
object PermissionsTable : LongIdTable("permissions") {
    val action = varchar("action", 100)
    val subject = varchar("subject", 100)

    init {
        // Unique constraint for (action, subject) pairs
        uniqueIndex(action, subject)
    }
}

/**
 * Exposed table definition to link roles to specific permissions.
 */
object RolePermissionsTable : Table("role_permissions") {
    val roleId = reference("role_id", RolesTable, onDelete = ReferenceOption.CASCADE)
    val permissionId = reference("permission_id", PermissionsTable, onDelete = ReferenceOption.CASCADE)

    override val primaryKey = PrimaryKey(roleId, permissionId)
}

/**
 * Exposed table definition to assign roles to users.
 */
object UserRoleAssignmentsTable : Table("user_role_assignments") {
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE)
    val roleId = reference("role_id", RolesTable, onDelete = ReferenceOption.CASCADE)
    val assignedAt = long("assigned_at").default(System.currentTimeMillis())

    override val primaryKey = PrimaryKey(userId, roleId)
}

/**
 * Exposed table definition for active user sessions.
 * 
 * Note: Store generated user_sessions.id in JWT token for later verification.
 *       For security reasons, do not store JWT tokens in the database.
 */
object UserSessionsTable : LongIdTable("user_sessions") {
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE)
    val expiresAt = long("expires_at")
    val createdAt = long("created_at")
    val lastAccessed = long("last_accessed")
}

/**
 * Exposed table definition for user-defined groups (e.g., 'Team A', 'All Users').
 * Will contain a special group 'All Users' during initial setup.
 */
object UserGroupsTable : LongIdTable("user_groups") {
    val name = varchar("name", 255).uniqueIndex()
    val description = text("description").nullable()
}

/**
 * Exposed table definition to link users to groups.
 */
object UserGroupMembershipsTable : Table("user_group_memberships") {
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE)
    val groupId = reference("group_id", UserGroupsTable, onDelete = ReferenceOption.CASCADE)

    override val primaryKey = PrimaryKey(userId, groupId)
}


// --- New Ownership and Access Association Tables ---

/**
 * Links a chat session to its owning user.
 */
object ChatSessionOwnersTable : Table("chat_session_owners") {
    val sessionId = reference("session_id", ChatSessionTable, onDelete = ReferenceOption.CASCADE)
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE)

    override val primaryKey = PrimaryKey(sessionId) // session_id is primary key, ensuring 1 owner per session
}

/**
 * Links a chat group to its owning user.
 */
object ChatGroupOwnersTable : Table("chat_group_owners") {
    val groupId = reference("group_id", ChatGroupTable, onDelete = ReferenceOption.CASCADE)
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE)

    override val primaryKey = PrimaryKey(groupId) // group_id is primary key, ensuring 1 owner per group
}

/**
 * Links an LLM provider to its owning user.
 */
object LLMProviderOwnersTable : Table("llm_provider_owners") {
    val providerId = reference("provider_id", LLMProviderTable, onDelete = ReferenceOption.CASCADE)
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE)
    override val primaryKey = PrimaryKey(providerId)
}

/**
 * Links an LLM model to its owning user.
 */
object LLMModelOwnersTable : Table("llm_model_owners") {
    val modelId = reference("model_id", LLMModelTable, onDelete = ReferenceOption.CASCADE)
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE)
    override val primaryKey = PrimaryKey(modelId)
}

/**
 * Links model settings to their owning user.
 */
object ModelSettingsOwnersTable : Table("model_settings_owners") {
    val settingsId = reference("settings_id", ModelSettingsTable, onDelete = ReferenceOption.CASCADE)
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE)

    override val primaryKey = PrimaryKey(settingsId) // settings_id is primary key, ensuring 1 owner per settings profile
}

/**
 * Links an API secret to its owning user.
 */
object ApiSecretOwnersTable : Table("api_secret_owners") {
    val secretAlias = reference("secret_alias", ApiSecretTable, onDelete = ReferenceOption.CASCADE)
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE)

    override val primaryKey = PrimaryKey(secretAlias) // secret_alias is primary key
}

/**
 * Defines which user groups (including 'All Users') can access an LLM provider.
 */
object LLMProviderAccessTable : Table("llm_provider_access") {
    val providerId = reference("provider_id", LLMProviderTable, onDelete = ReferenceOption.CASCADE)
    val userGroupId = reference("user_group_id", UserGroupsTable, onDelete = ReferenceOption.CASCADE)

    override val primaryKey = PrimaryKey(providerId, userGroupId) // Composite primary key
}

/**
 * Defines which user groups (including 'All Users') can access an LLM model.
 */
object LLMModelAccessTable : Table("llm_model_access") {
    val modelId = reference("model_id", LLMModelTable, onDelete = ReferenceOption.CASCADE)
    val userGroupId = reference("user_group_id", UserGroupsTable, onDelete = ReferenceOption.CASCADE)

    override val primaryKey = PrimaryKey(modelId, userGroupId) // Composite primary key
}

/**
 * Defines which user groups can access a ModelSettings profile.
 */
object ModelSettingsAccessTable : Table("model_settings_access") {
    val settingsId = reference("settings_id", ModelSettingsTable, onDelete = ReferenceOption.CASCADE)
    val userGroupId = reference("user_group_id", UserGroupsTable, onDelete = ReferenceOption.CASCADE)
    override val primaryKey = PrimaryKey(settingsId, userGroupId)
}


```

**Key Concept: The "All Users" Group for Public Resources**

-   A special `UserGroupsTable` entry named "All Users" (or similar) will be created during initial application setup.
-   This group will be non-deletable via application logic.
-   Every new user registered in the system will automatically be added to this "All Users" group via `UserGroupMembershipsTable`. Users cannot be removed from this group manually.
-   To make an `llm_provider` or `llm_model` "public," an entry will be created in `LLMProviderAccessTable` or `LLMModelAccessTable` linking the resource to the `id` of the "All Users" group.
-   When a user requests a list of providers or models, the system will query for resources owned by the user (via `_owners` tables, if applicable for that resource type) AND resources accessible to any group the user is a member of (by joining `_access` tables with `UserGroupMembershipsTable`), which will include all "public" resources.

#### Existing Tables (Unchanged)

The following tables will **not be modified** in their schema, and their existing Exposed definitions remain valid:

-   `ChatSessionTable`
-   `ChatGroupTable`
-   `LLMProviderTable`
-   `LLMModelTable`
-   `ModelSettingsTable`
-   `ChatMessageTable`
-   `AssistantMessageTable`
-   `ApiSecretTable`
-   `SessionCurrentLeafTable`

The `ChatMessageTable` and `AssistantMessageTable` inherit their user context from the `ChatSessionTable` they belong to. Therefore, once `ChatSessionTable` records are properly linked to `UsersTable` via `ChatSessionOwnersTable`, messages will automatically be user-scoped.

### 4.2. Authentication System

#### New Components Required

-   **`UserService`**: Handles user CRUD operations, user registration, and password management.
-   **`AuthenticationService`**: Manages user login/logout, JWT or session token generation and validation.
-   **`AuthorizationService`**: Performs permission checks based on user roles and assigned privileges.
-   **`SessionService`**: Manages active user sessions (e.g., token expiration, renewal).
-   **`PasswordService`**: Provides secure password hashing, verification, and complexity validation.

#### Security Infrastructure

-   **JWT/Session Tokens**: Used for maintaining user sessions across API calls, replacing stateless requests.
-   **Password Hashing**: Employ strong, industry-standard hashing algorithms (e.g., BCrypt) for secure password storage.
-   **Authentication Middleware**: Implement API middleware to protect routes, requiring valid tokens for access.
-   **CORS Updates**: Adjust CORS policies to properly handle authentication headers (e.g., `Authorization` header).

### 4.3. API Layer Changes

#### New API Endpoints

```kotlin
// Authentication and User Profile
POST /api/v1/auth/register          // User registration
POST /api/v1/auth/login             // User login, returns token
POST /api/v1/auth/logout            // Invalidates token
GET  /api/v1/auth/me                // Get current user's profile and permissions
PUT  /api/v1/auth/password          // Change current user's password

// User Management (Admin Only)
GET  /api/v1/users                  // List all users
POST /api/v1/users                  // Create new user, assign initial role
GET  /api/v1/users/{id}             // Get a specific user's details
PUT  /api/v1/users/{id}             // Update user details - excluding password
DELETE /api/v1/users/{id}           // Delete user account
PUT  /api/v1/users/{id}/roles       // Assign/revoke roles for a user
```

#### Existing API Modifications

All existing API endpoints will need to be modified:

-   **Authentication Middleware**: Apply middleware to all relevant routes to enforce authentication.
-   **User Context Injection**: Extract the authenticated `userId` from the session token and inject it into the request context for downstream services.
-   **Data Filtering**: Modify endpoint logic to filter data based on the authenticated user's ownership (via `_owners` tables) or access rights (via `_access` tables and `UserGroupMembershipsTable`).
-   **Permission Checks**: Implement authorization checks for sensitive operations (e.g., only admins can modify public resources).

### 4.4. Service Layer Changes

#### User Context Integration

All existing services will require significant modifications to incorporate the concept of a `currentUser` and enforce data isolation and access rules.

-   **`UserService`**:
    -   Handles new user registration, including automatically adding new users to the "All Users" group.
    -   Manages roles and permissions for users (admin-only operations).
    -   Prevents deletion of the "All Users" group or manual removal of users from it.
-   **`SessionService`**:
    -   `getAllSessions()`: Must filter results to return only sessions owned by the `currentUser` (by joining with `ChatSessionOwnersTable`).
    -   `insertSession()`: Must also create an entry in `ChatSessionOwnersTable` linking the new session to the `currentUser`.
    -   All other operations (`getSessionById`, `updateSessionName`, etc.) must verify ownership against `ChatSessionOwnersTable` before proceeding.
-   **`GroupService`**: Similar to `SessionService`, all operations must respect ownership via `ChatGroupOwnersTable`.
-   **`LLMProviderService`**:
    -   `getAllProviders()`: Must return providers accessible to any group the `currentUser` is a member of (by joining `LLMProviderAccessTable` with `UserGroupMembershipsTable`). This implicitly includes providers linked to the "All Users" group.
    -   `insertProvider()`, `updateProvider()`, `deleteProvider()`: These will require permission checks (e.g., only admins or users with `create_public_provider` permission can create/modify/delete public providers). Creating a new provider will also require inserting into `LLMProviderAccessTable` to define its initial sharing status.
-   **`LLMModelService`**: Analogous to `LLMProviderService`, applying the same group-based access logic via `LLMModelAccessTable` and `UserGroupMembershipsTable`.
-   **`ModelSettingsService`**: All operations must respect ownership via `ModelSettingsOwnersTable`.
-   **`MessageService`**: Messages derive their user context from the `sessionId`. Access checks will be performed at the `SessionService` level; if a user can access a session, they can access its messages.
-   **`ApiSecretService`**: All operations must respect ownership via `ApiSecretOwnersTable`.

#### Permission System

-   **Role-Based Access Control (RBAC)**: Implement roles (Admin, Standard User) and granular permissions.
-   **Resource Ownership**: Users can only access/modify resources they explicitly own (e.g., private chat sessions, model settings).
-   **Public/Shared Resource Access**: Logic will be implemented to allow users to view resources associated with groups they belong to, including the "All Users" group.
-   **Admin Privileges**: Admins will have privileges to manage users, roles, permissions, and create/manage public resources.

### 4.5. Data Access Layer (DAO) Changes

#### DAO Modifications

All existing DAOs will require substantial updates to their query and mutation logic, leveraging Exposed's DSL for `JOIN`s and filtering:

-   **Read Operations (`getAll`, `getById`, `get...By...Id`)**: These methods must be updated to accept a `userId` (and `userGroupIds` for shared resources) and perform `JOIN`s with the appropriate `_owners` or `_access` tables.
    -   Example for `SessionDao.getAllSessions(userId: Long)`: Will join `ChatSessionTable` with `ChatSessionOwnersTable` on `sessionId` and filter by `ChatSessionOwnersTable.userId eq userId`.
    -   Example for `LLMProviderDao.getAllProviders(userId: Long, userGroupIds: List<Long>)`: Will join `LLMProviderTable` with `LLMProviderAccessTable` and `UserGroupMembershipsTable` to filter results where the provider is accessible to one of the user's groups.
-   **Write Operations (`insert...`)**: These will need to be wrapped in transactions to:
    1.  Insert the record into the primary data table (e.g., `ChatSessionTable`).
    2.  Insert a corresponding record into the relevant ownership table (e.g., `ChatSessionOwnersTable`) with the `currentUser`'s ID.
    3.  For public resources (providers, models), insert into the `_access` table linking to the "All Users" group (or other specified groups).
-   **Update/Delete Operations**: Must include an ownership/permission check (`JOIN` with `_owners` or `_access` tables) to ensure the `currentUser` has the right to modify or delete the resource.

#### New DAOs Required

-   **`UserDao`**: For CRUD operations on `UsersTable`.
-   **`RoleDao`**: For CRUD on `RolesTable` and `RolePermissionsTable`.
-   **`PermissionDao`**: For `PermissionsTable`.
-   **`UserSessionDao`**: For managing `UserSessionsTable`.
-   **`UserGroupDao`**: For managing `UserGroupsTable` and `UserGroupMembershipsTable`. This DAO will also contain logic to handle the special "All Users" group (creation, auto-assignment, non-deletable).
-   Dedicated DAOs or specific methods within existing DAOs to handle the **ownership and access association tables** (`ChatSessionOwnersTable`, `LLMProviderAccessTable`, etc.).

### 4.6. UI/Frontend Changes

#### New Screens Required

-   **Login Screen**: For user authentication.
-   **Registration Screen**: For new user signup.
-   **User Management Screen**: For administrators to manage users, roles, and groups.
-   **Profile Settings**: For users to manage their own account details (e.g., change password).

#### Application Flow Changes

-   **Startup Flow**: The application must first check for an authenticated user. If none, it redirects to the login screen. If authenticated, it proceeds to the main application and loads user-specific data.
-   **Navigation**: Add a user menu (e.g., in the header) with options for "Profile," "User Management" (admin only), and "Logout."
-   **State Management**: The frontend application's global state will need to store the `currentUser`'s ID, username, roles, and permissions, which will drive UI rendering and access control.
-   **Error Handling**: Implement robust error handling for authentication failures, session expiry, and authorization errors.

#### Existing Screen Modifications

-   **Settings Screen**: Introduce a "User Management" tab or section, visible only to users with administrative privileges.
-   **Providers/Models Management**: Update screens where providers and models are created/edited to include options for marking them as "public" (by assigning to the "All Users" group) or sharing with specific groups. Display "Public" indicators for publicly shared resources.
-   **Chat Interface**: Display the currently logged-in user's name or avatar.
-   **All screens**: Adapt to reflect the authentication state and display user-specific data only.

### 4.7. Configuration & Deployment

#### New Configuration

-   **JWT Secrets**: Configuration for signing and verifying JWT tokens.
-   **Session Configuration**: Parameters for session timeouts, refresh token policies, etc.
-   **Admin User Setup**: Environment variables or a secure initial setup process for creating the first admin user.
-   **"All Users" Group ID**: Configuration to identify the `id` of the special "All Users" group (e.g., an application constant, database lookup).
-   **Password Policies**: Configuration for password strength requirements.

#### Database Migration

-   **Schema Migration**: Create all new tables (user management, roles, permissions, groups, and all association tables) using Exposed migration tools.
-   **Initial Setup Script**: A critical, one-time script must be executed on first deployment (or during a specific upgrade phase):
    1.  Create the special **"All Users" group** in the `UserGroupsTable`.
    2.  Create the **initial administrator user account** in the `UsersTable`.
    3.  Assign the "Admin" role to this user via `UserRoleAssignmentsTable`.
    4.  Add the initial administrator user to the "All Users" group via `UserGroupMembershipsTable`.
-   **Data Migration**: A separate one-time script will be needed to transition existing data in `LLMProviderTable`, `LLMModelTable`, `ChatSessionTable`, `ChatGroupTable`, `ModelSettingsTable`, and `ApiSecretTable` to the new multi-user model:
    1.  **Ownership**: All existing `ChatSessionTable`, `ChatGroupTable`, `ModelSettingsTable`, and `ApiSecretTable` records will be assigned to the initial administrator user by creating corresponding entries in their respective `_owners` tables.
    2.  **Public Access**: All existing `LLMProviderTable` and `LLMModelTable` records will be made "public" by creating entries in `LLMProviderAccessTable` and `LLMModelAccessTable`, linking them to the `id` of the "All Users" group.

---

## 5. Implementation Phases

This project will be implemented in three distinct phases. This approach isolates foundational work from administrative features and resource sharing, allowing for iterative development and testing.

### Phase 1: Foundation - Authentication & Private User Space

**Goal:** Establish the core user system, allowing users to register, log in, and see only their own private chat sessions and groups.

1.  **Database (Schema Setup):**
    *   Define and create all new JetBrains Exposed table objects in a single, comprehensive migration. This includes `UsersTable`, `RolesTable`, `PermissionsTable`, all `_owners` tables (`ChatSessionOwnersTable`, `GroupOwnersTable`, etc.), and all `_access` tables (`LLMProviderAccessTable`, `LLMModelAccessTable`, etc.).
    *   Implement the initial setup script to create the special **"All Users" group** and the first **administrator user account**.

2.  **Data Access Layer (Core DAOs):**
    *   Implement the new foundational DAO interfaces required for user identity and basic ownership: `UserDao`, `UserSessionDao`, `SessionOwnershipDao`, and `GroupOwnershipDao`.

3.  **Backend (Core Services & API):**
    *   Implement the core services: `UserService` (for registration/user lookup), `AuthenticationService`, and `PasswordService`.
    *   Implement the public authentication API endpoints: `/api/v1/auth/register`, `/api/v1/auth/login`, etc.
    *   **Refactor `SessionServiceImpl` and `GroupServiceImpl`:**
        *   Update all methods to accept a `userId`.
        *   Replace direct calls to `sessionDao.getAllSessions()` and `groupDao.getAllGroups()` with calls to `sessionOwnershipDao.getAllSessionsForUser(userId)` and `groupOwnershipDao.getAllGroupsForUser(userId)`.
        *   For create/update/delete operations, use the new ownership DAOs to verify that the user owns the resource before proceeding.
        *   `createGroup(userId, ...)` will now call `groupDao.insertGroup(...)` and then `groupOwnershipDao.setOwner(...)` within a single transaction.

4.  **Frontend & API:**
    *   Develop the Login and Registration screens.
    *   Implement client-side authentication logic (token storage, authenticated requests).
    *   Update the main application UI to fetch and display only the sessions and groups for the currently logged-in user.

### Phase 2: Administration - User & Role Management

**Goal:** Build the necessary tools for administrators to manage the user base and their permissions.

1.  **Data Access Layer (Admin DAOs):**
    *   Implement the DAO interfaces required for the administration panel: `RoleDao`, `PermissionDao`, and `UserGroupDao`.

2.  **Backend (Admin Services & API):**
    *   Implement the `AuthorizationService` for checking user roles and permissions.
    *   Enhance `UserService` with administrator-only methods (e.g., `listAllUsers`, `assignRoleToUser`, `deleteUser`).
    *   Implement the protected, admin-only API endpoints under `/api/v1/users/*`.
    *   Secure these endpoints using the `AuthorizationService`.

3.  **Frontend:**
    *   Develop the "User Management" screen, accessible only to users with an "Admin" role.
    *   This screen should allow admins to view, create, edit, and delete users, and manage their role assignments.

### Phase 3: Resource Sharing & Final Data Migration

**Goal:** Enable the sharing of resources like LLM providers and models, and migrate all pre-existing data into the new multi-user structure.

1.  **Data Access Layer (Sharing DAOs):**
    *   Implement the remaining `_owners` DAO interfaces: `ProviderOwnershipDao`, `ModelOwnershipDao`, `SettingsOwnershipDao`, `ApiSecretOwnershipDao`.
    *   Implement the `_access` DAO interfaces for shared resources: `ProviderAccessDao`, `ModelAccessDao`, `SettingsAccessDao`.

2.  **Backend (Sharing Services):**
    *   **Refactor `LLMProviderService`, `LLMModelService`, and `SettingsService`:**
        *   Update all methods to operate within a user context (`userId`).
        *   Modify "get all" methods to return a combined list of resources the user **owns** (via `_owners` DAOs) and resources they can **access** through their groups (via `_access` DAOs), including those shared with the "All Users" group.
        *   Add admin-only logic for creating/updating resources and assigning them to specific groups (e.g., making a provider public by linking it to the "All Users" group via `ProviderAccessDao`).
        *   Protect modification and deletion endpoints with ownership and permission checks.

3.  **Frontend:**
    *   Update the provider, model, and settings management screens to display ownership and sharing status (e.g., a "Public" badge).
    *   For admins, add UI controls to these screens to manage the sharing settings of a resource (e.g., a dropdown to select which group can access it).

4.  **Data Migration:**
    *   Develop and test the final, one-time data migration script. This script will:
        *   Assign ownership of all existing private resources (sessions, user-created groups, settings, secrets) to the initial admin user.
        *   Make all existing shared resources (providers, models) "public" by creating entries in the `_access` tables that link them to the "All Users" group.
    *   Execute this script as the final step before deploying the feature.

---

## 6. Risk Assessment

### High-Risk Areas

-   **Data Migration**: The process of assigning existing data (sessions, models, providers, settings) to users and making resources public during the initial deployment is complex and critical. Errors could lead to data loss or incorrect access rights.
-   **Authentication Security**: Implementing a secure authentication system (JWT, password hashing, session management) requires expertise and careful attention to detail to prevent vulnerabilities (e.g., token hijacking, brute-force attacks).
-   **Performance Impact**: Introducing multiple `JOIN` operations across several new association tables in nearly all data access queries could introduce performance bottlenecks. Careful indexing and query optimization will be essential.
-   **Authorization Complexity**: Managing roles, permissions, ownership, and group-based access across all services can become intricate and prone to subtle bugs.

### Migration Challenges

-   **Existing Data Handling**: Deciding how to attribute existing "global" data to specific users or make it public. The proposed solution is to assign existing private data to the initial admin user and make existing shared resources public.
-   **Backward Compatibility**: All API endpoints will change from being unauthenticated/global to authenticated/user-scoped. This requires a full client-side update.
-   **Database Size/Complexity**: Adding numerous new tables and indices will increase database footprint and potentially query planning complexity.
-   **Testing Complexity**: Testing multi-user scenarios, concurrent access, and various permission combinations is significantly more complex than a single-user system.

## 7. Estimated Effort

This is a high-level estimate; specific tasks will require more detailed breakdown.

-   **Database Schema Design & Migration (Exposed)**: 3-4 days (Slight increase due to Exposed-specific considerations for new table definitions and migrations)
-   **Authentication System (Backend Services)**: 5-7 days
-   **API Layer Modifications (New Endpoints & Middleware)**: 4-5 days
-   **Service Layer Updates (User Context, Ownership, Access Logic)**: 4-5 days
-   **Data Access Layer Updates (Exposed DAO Logic for JOINs & Ownership)**: 4-5 days
-   **UI/Frontend Implementation (New Screens, Flow Changes, Existing Mods)**: 6-8 days
-   **Testing & Integration (Unit, Integration, End-to-End, Security)**: 4-5 days
-   **Documentation**: 1-2 days

**Total Estimated Effort**: 31-41 days

## 8. Recommendations

1.  **Prioritize Database Design (Exposed)**: Ensure the Exposed schema for all new tables and their relationships (especially the association tables and "All Users" group mechanism) is meticulously designed and reviewed before implementation begins. Leverage Exposed's DSL for robust and type-safe schema definitions.
2.  **Build Authentication First**: This forms the foundational layer. A robust and secure authentication system is paramount before any other multi-user features are developed.
3.  **Develop and Test Migration Scripts Thoroughly**: Create and rigorously test the initial setup script (for the "All Users" group and admin user) and the data migration script (for existing data) in a staging environment. This is a critical step to prevent data issues.
4.  **Focus on Query Optimization (Exposed)**: Given the increased number of `JOIN`s, pay close attention to database indexing on all foreign keys and frequently queried columns in the new association tables. Profile and optimize critical data retrieval queries using Exposed's DSL capabilities early.
5.  **Conduct a Security Review**: The authentication and authorization components are high-risk. Plan for an internal or external security review to validate their implementation.
6.  **Comprehensive Testing Strategy**: Develop a detailed testing plan that covers unit tests, integration tests, and end-to-end tests for various user roles, permissions, and concurrent access scenarios.
7.  **Clear Documentation**: Document the new architecture, API changes, and especially the permission model and the "All Users" group's functionality for future maintainability.
8.  **Phased Rollout**: Implement this feature in the proposed phases to manage complexity and reduce risk.