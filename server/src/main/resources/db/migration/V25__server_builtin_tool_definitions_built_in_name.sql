-- Server built-in tool definitions: canonical (unprefixed) name column.
--
-- Server built-in tools are executed in-process on the server. Their public name is the user's
-- configurable tool-name prefix concatenated to a canonical catalog name (e.g. "chatbot-" +
-- "list_agent_roles" = "chatbot-list_agent_roles"). The canonical name must be persisted so that
-- deduplication, reconciliation, and in-process execution dispatch stay stable across prefix
-- changes; it mirrors built_in_tool_definitions.built_in_tool_name (V14) for worker built-ins.
--
-- The backfill maps today's public names (which are canonical, since no prefix existed before this
-- migration) into the new column. The column stays NULLABLE at the DB level because SQLite's
-- ALTER TABLE cannot add a NOT NULL column without a table rebuild; the application always writes
-- it and the mappers fail loudly if a row is ever missing the value.

ALTER TABLE server_builtin_tool_definitions ADD COLUMN built_in_tool_name VARCHAR(255);

UPDATE server_builtin_tool_definitions
   SET built_in_tool_name = (SELECT name FROM tool_definitions
                              WHERE tool_definitions.id = server_builtin_tool_definitions.tool_definition_id)
 WHERE built_in_tool_name IS NULL;
