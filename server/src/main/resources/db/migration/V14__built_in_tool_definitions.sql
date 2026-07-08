-- Built-in worker tool definitions.
-- Each row links a base tool_definitions row to the worker that exposes the tool as a built-in
-- tool. The base row carries the public (possibly prefixed) name, description, schemas, config,
-- enabled flag, and timestamps; this side-table adds the unprefixed internal worker runtime name
-- and the owning worker. Deleting a worker cascades to its built-in tool linkages; deleting a base
-- tool definition cascades to the linkage as well.

CREATE TABLE built_in_tool_definitions (
    tool_definition_id BIGINT NOT NULL,
    worker_id BIGINT NOT NULL,
    built_in_tool_name VARCHAR(255) NOT NULL,
    PRIMARY KEY (tool_definition_id),
    FOREIGN KEY (tool_definition_id) REFERENCES tool_definitions (id) ON DELETE CASCADE,
    FOREIGN KEY (worker_id) REFERENCES workers (id) ON DELETE CASCADE
);

-- Optional prefix applied to the public names of a worker's built-in tools. Nullable so workers
-- that do not use a prefix keep the unprefixed canonical tool names.
ALTER TABLE workers ADD COLUMN tool_name_prefix VARCHAR(255);
