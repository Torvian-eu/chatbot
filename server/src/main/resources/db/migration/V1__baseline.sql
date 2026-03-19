-- Baseline schema for all server tables managed by ExposedDataManager.

CREATE TABLE users (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    username VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    email VARCHAR(255),
    status VARCHAR(50) NOT NULL DEFAULT 'DISABLED',
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    last_login BIGINT,
    requires_password_change BOOLEAN NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX users_username_unique ON users (username);
CREATE UNIQUE INDEX users_email_unique ON users (email);

CREATE TABLE roles (
	id INTEGER PRIMARY KEY AUTOINCREMENT,
	name VARCHAR(50) NOT NULL,
	description TEXT
);

CREATE UNIQUE INDEX roles_name_unique ON roles (name);

CREATE TABLE permissions (
	id INTEGER PRIMARY KEY AUTOINCREMENT,
	action VARCHAR(100) NOT NULL,
	subject VARCHAR(100) NOT NULL
);

CREATE UNIQUE INDEX permissions_action_subject_unique ON permissions (action, subject);

CREATE TABLE role_permissions (
	role_id BIGINT NOT NULL,
	permission_id BIGINT NOT NULL,
	PRIMARY KEY (role_id, permission_id),
	FOREIGN KEY (role_id) REFERENCES roles (id) ON DELETE CASCADE,
	FOREIGN KEY (permission_id) REFERENCES permissions (id) ON DELETE CASCADE
);

CREATE TABLE user_role_assignments (
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    assigned_at BIGINT NOT NULL,
    PRIMARY KEY (user_id, role_id),
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    FOREIGN KEY (role_id) REFERENCES roles (id) ON DELETE CASCADE
);

CREATE TABLE user_sessions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id BIGINT NOT NULL,
    expires_at BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    last_accessed BIGINT NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE TABLE user_groups (
	id INTEGER PRIMARY KEY AUTOINCREMENT,
	name VARCHAR(255) NOT NULL,
	description TEXT
);

CREATE UNIQUE INDEX user_groups_name_unique ON user_groups (name);

CREATE TABLE user_group_memberships (
	user_id BIGINT NOT NULL,
	group_id BIGINT NOT NULL,
	PRIMARY KEY (user_id, group_id),
	FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
	FOREIGN KEY (group_id) REFERENCES user_groups (id) ON DELETE CASCADE
);

CREATE TABLE api_secrets (
	alias VARCHAR(36) NOT NULL,
	encrypted_credential TEXT NOT NULL,
	wrapped_dek VARCHAR(255) NOT NULL,
	key_version INTEGER NOT NULL,
	created_at BIGINT NOT NULL,
	updated_at BIGINT NOT NULL,
	PRIMARY KEY (alias)
);

CREATE TABLE llm_providers (
	id INTEGER PRIMARY KEY AUTOINCREMENT,
	api_key_id VARCHAR(255),
	name VARCHAR(255) NOT NULL,
	description TEXT NOT NULL,
	base_url VARCHAR(500) NOT NULL,
	type VARCHAR(50) NOT NULL
);

CREATE UNIQUE INDEX llm_providers_api_key_id_unique ON llm_providers (api_key_id);

CREATE TABLE llm_models (
	id INTEGER PRIMARY KEY AUTOINCREMENT,
	name VARCHAR(255) NOT NULL,
	provider_id BIGINT NOT NULL,
	active BOOLEAN NOT NULL DEFAULT 1,
	display_name VARCHAR(255),
	type VARCHAR(50) NOT NULL,
	capabilities TEXT,
	FOREIGN KEY (provider_id) REFERENCES llm_providers (id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX llm_models_name_unique ON llm_models (name);

CREATE TABLE model_settings (
	id INTEGER PRIMARY KEY AUTOINCREMENT,
	model_id BIGINT NOT NULL,
	name VARCHAR(255) NOT NULL,
	type VARCHAR(50) NOT NULL,
	variable_params_json TEXT NOT NULL,
	custom_params_json TEXT,
	FOREIGN KEY (model_id) REFERENCES llm_models (id) ON DELETE CASCADE
);

CREATE INDEX model_settings_model_id_idx ON model_settings (model_id);

CREATE TABLE chat_groups (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name VARCHAR(255) NOT NULL,
    created_at BIGINT NOT NULL
);

CREATE TABLE chat_sessions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name VARCHAR(255) NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    group_id BIGINT,
    current_model_id BIGINT,
    current_settings_id BIGINT,
    FOREIGN KEY (group_id) REFERENCES chat_groups (id) ON DELETE SET NULL,
    FOREIGN KEY (current_model_id) REFERENCES llm_models (id) ON DELETE SET NULL,
    FOREIGN KEY (current_settings_id) REFERENCES model_settings (id) ON DELETE SET NULL
);

CREATE INDEX chat_sessions_group_id_idx ON chat_sessions (group_id);

CREATE TABLE chat_messages (
	id INTEGER PRIMARY KEY AUTOINCREMENT,
	session_id BIGINT NOT NULL,
	role VARCHAR(50) NOT NULL,
	content TEXT NOT NULL,
	created_at BIGINT NOT NULL,
	updated_at BIGINT NOT NULL,
	parent_message_id BIGINT,
	children_message_ids TEXT NOT NULL,
	file_references TEXT NOT NULL DEFAULT '[]',
	FOREIGN KEY (session_id) REFERENCES chat_sessions (id) ON DELETE CASCADE,
	FOREIGN KEY (parent_message_id) REFERENCES chat_messages (id) ON DELETE SET NULL
);

CREATE INDEX chat_messages_session_id_idx ON chat_messages (session_id);
CREATE INDEX chat_messages_parent_message_id_idx ON chat_messages (parent_message_id);

CREATE TABLE assistant_messages (
	message_id BIGINT NOT NULL,
	model_id BIGINT,
	settings_id BIGINT,
	PRIMARY KEY (message_id),
	FOREIGN KEY (message_id) REFERENCES chat_messages (id) ON DELETE CASCADE,
	FOREIGN KEY (model_id) REFERENCES llm_models (id) ON DELETE SET NULL,
	FOREIGN KEY (settings_id) REFERENCES model_settings (id) ON DELETE SET NULL
);

CREATE TABLE session_current_leaf (
	session_id BIGINT NOT NULL,
	message_id BIGINT NOT NULL,
	PRIMARY KEY (session_id),
	FOREIGN KEY (session_id) REFERENCES chat_sessions (id) ON DELETE CASCADE,
	FOREIGN KEY (message_id) REFERENCES chat_messages (id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX session_current_leaf_message_id_unique ON session_current_leaf (message_id);

CREATE TABLE tool_definitions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name VARCHAR(255) NOT NULL,
    description TEXT NOT NULL,
    type VARCHAR(50) NOT NULL,
    config_json TEXT NOT NULL,
    input_schema_json TEXT NOT NULL,
    output_schema_json TEXT,
    is_enabled BOOLEAN NOT NULL DEFAULT 1,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
);

CREATE TABLE tool_calls (
	id INTEGER PRIMARY KEY AUTOINCREMENT,
	message_id BIGINT NOT NULL,
	tool_definition_id BIGINT,
	tool_name VARCHAR(255) NOT NULL,
	tool_call_id TEXT,
	input_json TEXT,
	output_json TEXT,
	status VARCHAR(50) NOT NULL,
	error_message TEXT,
	denial_reason TEXT,
	executed_at BIGINT NOT NULL,
	duration_ms BIGINT,
	FOREIGN KEY (message_id) REFERENCES chat_messages (id) ON DELETE CASCADE,
	FOREIGN KEY (tool_definition_id) REFERENCES tool_definitions (id) ON DELETE CASCADE
);

CREATE INDEX tool_calls_message_id_idx ON tool_calls (message_id);

CREATE TABLE session_tool_config (
	session_id BIGINT NOT NULL,
	tool_definition_id BIGINT NOT NULL,
	is_enabled BOOLEAN NOT NULL DEFAULT 1,
	PRIMARY KEY (session_id, tool_definition_id),
	FOREIGN KEY (session_id) REFERENCES chat_sessions (id) ON DELETE CASCADE,
	FOREIGN KEY (tool_definition_id) REFERENCES tool_definitions (id) ON DELETE CASCADE
);

CREATE TABLE local_mcp_servers (
	id INTEGER PRIMARY KEY AUTOINCREMENT,
	user_id BIGINT NOT NULL,
	is_enabled BOOLEAN NOT NULL DEFAULT 1,
	FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE TABLE local_mcp_tool_definitions (
	tool_definition_id BIGINT NOT NULL,
	mcp_server_id BIGINT NOT NULL,
	mcp_tool_name VARCHAR(255) NOT NULL,
	PRIMARY KEY (tool_definition_id),
	FOREIGN KEY (tool_definition_id) REFERENCES tool_definitions (id) ON DELETE CASCADE,
	FOREIGN KEY (mcp_server_id) REFERENCES local_mcp_servers (id) ON DELETE CASCADE
);

CREATE TABLE user_tool_approval_preferences (
	user_id BIGINT NOT NULL,
	tool_definition_id BIGINT NOT NULL,
	auto_approve BOOLEAN NOT NULL DEFAULT 1,
	conditions TEXT,
	denial_reason TEXT,
	PRIMARY KEY (user_id, tool_definition_id),
	FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
	FOREIGN KEY (tool_definition_id) REFERENCES tool_definitions (id) ON DELETE CASCADE
);

CREATE INDEX user_tool_approval_preferences_user_id_idx ON user_tool_approval_preferences (user_id);

CREATE TABLE chat_session_owners (
	session_id BIGINT NOT NULL,
	user_id BIGINT NOT NULL,
	PRIMARY KEY (session_id),
	FOREIGN KEY (session_id) REFERENCES chat_sessions (id) ON DELETE CASCADE,
	FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE TABLE chat_group_owners (
	group_id BIGINT NOT NULL,
	user_id BIGINT NOT NULL,
	PRIMARY KEY (group_id),
	FOREIGN KEY (group_id) REFERENCES chat_groups (id) ON DELETE CASCADE,
	FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE TABLE llm_provider_owners (
	provider_id BIGINT NOT NULL,
	user_id BIGINT NOT NULL,
	PRIMARY KEY (provider_id),
	FOREIGN KEY (provider_id) REFERENCES llm_providers (id) ON DELETE CASCADE,
	FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE TABLE llm_model_owners (
	model_id BIGINT NOT NULL,
	user_id BIGINT NOT NULL,
	PRIMARY KEY (model_id),
	FOREIGN KEY (model_id) REFERENCES llm_models (id) ON DELETE CASCADE,
	FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE TABLE model_settings_owners (
	settings_id BIGINT NOT NULL,
	user_id BIGINT NOT NULL,
	PRIMARY KEY (settings_id),
	FOREIGN KEY (settings_id) REFERENCES model_settings (id) ON DELETE CASCADE,
	FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE TABLE api_secret_owners (
	secret_alias VARCHAR(36) NOT NULL,
	user_id BIGINT NOT NULL,
	PRIMARY KEY (secret_alias),
	FOREIGN KEY (secret_alias) REFERENCES api_secrets (alias) ON DELETE CASCADE,
	FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE TABLE llm_provider_access (
	provider_id BIGINT NOT NULL,
	user_group_id BIGINT NOT NULL,
	access_mode VARCHAR(50) NOT NULL,
	PRIMARY KEY (provider_id, user_group_id, access_mode),
	FOREIGN KEY (provider_id) REFERENCES llm_providers (id) ON DELETE CASCADE,
	FOREIGN KEY (user_group_id) REFERENCES user_groups (id) ON DELETE CASCADE
);

CREATE INDEX llm_provider_access_provider_group_idx ON llm_provider_access (provider_id, user_group_id);

CREATE TABLE llm_model_access (
	model_id BIGINT NOT NULL,
	user_group_id BIGINT NOT NULL,
	access_mode VARCHAR(50) NOT NULL,
	PRIMARY KEY (model_id, user_group_id, access_mode),
	FOREIGN KEY (model_id) REFERENCES llm_models (id) ON DELETE CASCADE,
	FOREIGN KEY (user_group_id) REFERENCES user_groups (id) ON DELETE CASCADE
);

CREATE INDEX llm_model_access_model_group_idx ON llm_model_access (model_id, user_group_id);

CREATE TABLE model_settings_access (
	settings_id BIGINT NOT NULL,
	user_group_id BIGINT NOT NULL,
	access_mode VARCHAR(50) NOT NULL,
	PRIMARY KEY (settings_id, user_group_id, access_mode),
	FOREIGN KEY (settings_id) REFERENCES model_settings (id) ON DELETE CASCADE,
	FOREIGN KEY (user_group_id) REFERENCES user_groups (id) ON DELETE CASCADE
);

CREATE INDEX model_settings_access_settings_group_idx ON model_settings_access (settings_id, user_group_id);

