package eu.torvian.chatbot.common.models.tool

import kotlinx.serialization.json.*

/**
 * Canonical, server-agnostic catalog of the worker's built-in tools.
 *
 * This is the single source of truth for the public metadata (name, description, and input JSON
 * Schema) of every built-in worker tool. The server seeds these definitions into its tool catalog
 * so the LLM can discover and call them, and the worker tool implementations reuse the same
 * [BuiltInToolSpec] values to keep their advertised schema in sync with what is persisted.
 *
 * The [builtInToolName] is always the unprefixed canonical name (e.g. `read_text_file`); the server
 * derives the public (possibly prefixed) name at seeding time.
 */
object BuiltInToolCatalog {

    /**
     * Immutable specification of a single built-in worker tool.
     *
     * @property builtInToolName Unprefixed canonical name used to resolve the implementation on the worker.
     * @property description Human-readable description surfaced to the LLM.
     * @property inputSchema JSON Schema describing the tool's expected input arguments.
     */
    data class BuiltInToolSpec(
        val builtInToolName: String,
        val description: String,
        val inputSchema: JsonObject,
    )

    /**
     * All built-in tool specifications, in stable catalog order.
     */
    val allTools: List<BuiltInToolSpec> = listOf(
        BuiltInToolSpec(
            builtInToolName = "read_text_file",
            description = "Read the contents of a text file as UTF-8.",
            inputSchema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("path", buildJsonObject {
                        put("type", "string")
                        put("description", "Path to the file, relative to the workspace.")
                    })
                    put("head", buildJsonObject {
                        put("type", "integer")
                        put("description", "Return only the first N lines. Mutually exclusive with 'tail'.")
                    })
                    put("tail", buildJsonObject {
                        put("type", "integer")
                        put("description", "Return only the last N lines. Mutually exclusive with 'head'.")
                    })
                })
                put("required", buildJsonArray { add("path") })
            }
        ),
        BuiltInToolSpec(
            builtInToolName = "write_file",
            description = "Create or overwrite a text file inside the workspace.",
            inputSchema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("path", buildJsonObject {
                        put("type", "string")
                        put("description", "Path to the file, relative to the workspace.")
                    })
                    put("content", buildJsonObject {
                        put("type", "string")
                        put("description", "UTF-8 text content to write to the file.")
                    })
                })
                put("required", buildJsonArray { add("path"); add("content") })
            }
        ),
        BuiltInToolSpec(
            builtInToolName = "edit_file",
            description = "Apply structured edits to a text file with optional dry-run.",
            inputSchema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("path", buildJsonObject {
                        put("type", "string")
                        put("description", "Path to the file, relative to the workspace.")
                    })
                    put("edits", buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject {
                            put("type", "object")
                            put("properties", buildJsonObject {
                                put("oldText", buildJsonObject { put("type", "string") })
                                put("newText", buildJsonObject { put("type", "string") })
                            })
                            put("required", buildJsonArray { add("oldText"); add("newText") })
                        })
                    })
                    put("dryRun", buildJsonObject {
                        put("type", "boolean")
                        put("description", "Preview the changes without applying them.")
                    })
                })
                put("required", buildJsonArray { add("path"); add("edits") })
            }
        ),
        BuiltInToolSpec(
            builtInToolName = "create_directory",
            description = "Create a directory (and parents) inside the workspace. Idempotent.",
            inputSchema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("path", buildJsonObject {
                        put("type", "string")
                        put("description", "Path of the directory to create, relative to the workspace.")
                    })
                })
                put("required", buildJsonArray { add("path") })
            }
        ),
        BuiltInToolSpec(
            builtInToolName = "list_directory",
            description = "List the contents of a directory with [FILE]/[DIR] prefixes.",
            inputSchema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("path", buildJsonObject {
                        put("type", "string")
                        put("description", "Directory path relative to the workspace (defaults to the workspace root).")
                    })
                    put("sortBy", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray { add("name"); add("size") })
                        put("description", "Sort entries by name (default) or size.")
                    })
                    put("includeSizes", buildJsonObject {
                        put("type", "boolean")
                        put("description", "Include file sizes in the listing.")
                    })
                    put("recursive", buildJsonObject {
                        put("type", "boolean")
                        put("description", "Recursively list subdirectories with indentation.")
                    })
                })
                put("required", buildJsonArray { add("path") })
            }
        ),
        BuiltInToolSpec(
            builtInToolName = "move_file",
            description = "Move or rename a file or directory. Fails if the destination exists.",
            inputSchema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("source", buildJsonObject {
                        put("type", "string")
                        put("description", "Source path relative to the workspace.")
                    })
                    put("destination", buildJsonObject {
                        put("type", "string")
                        put("description", "Destination path relative to the workspace.")
                    })
                })
                put("required", buildJsonArray { add("source"); add("destination") })
            }
        ),
        BuiltInToolSpec(
            builtInToolName = "search_files",
            description = "Recursively search for files/directories by glob pattern.",
            inputSchema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("path", buildJsonObject {
                        put("type", "string")
                        put("description", "Starting directory relative to the workspace.")
                    })
                    put("pattern", buildJsonObject {
                        put("type", "string")
                        put("description", "Glob pattern (e.g. '*.kt').")
                    })
                    put("excludePatterns", buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject { put("type", "string") })
                        put("description", "Optional list of glob patterns to exclude.")
                    })
                })
                put("required", buildJsonArray { add("pattern") })
            }
        ),
        BuiltInToolSpec(
            builtInToolName = "run_command",
            description = "Run a process inside the worker workspace with a timeout.",
            inputSchema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("command", buildJsonObject {
                        put("type", "string")
                        put("description", "Executable name (must be on PATH or absolute).")
                    })
                    put("args", buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject { put("type", "string") })
                        put("description", "Command-line arguments.")
                    })
                    put("timeout", buildJsonObject {
                        put("type", "integer")
                        put(
                            "description",
                            "Timeout in seconds. Defaults to the worker's builtInTools.defaultCommandTimeoutSeconds."
                        )
                    })
                })
                put("required", buildJsonArray { add("command") })
            }
        ),
    )

    /**
     * Looks up a built-in tool specification by its unprefixed canonical name.
     *
     * @param builtInToolName Unprefixed tool name (e.g. `read_text_file`).
     * @return The matching [BuiltInToolSpec], or null when the name is unknown.
     */
    fun specFor(builtInToolName: String): BuiltInToolSpec? =
        allTools.firstOrNull { it.builtInToolName == builtInToolName }
}

