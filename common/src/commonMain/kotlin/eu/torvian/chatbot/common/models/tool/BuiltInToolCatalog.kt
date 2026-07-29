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
 * The [BuiltInToolSpec.builtInToolName] is always the unprefixed canonical name (e.g. `read_text_file`); the server
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
                    put("range", buildJsonObject {
                        put("type", "array")
                        put("minItems", 2)
                        put("maxItems", 2)
                        put("items", buildJsonObject {
                            put("type", buildJsonArray {
                                add("integer")
                                add("null")
                            })
                        })
                        put(
                            "description",
                            "Line range as [start, end), matching Python slice semantics. " +
                            "Negative values count from the end. Use null for open-ended."
                        )
                    })
                    put("maxLines", buildJsonObject {
                        put("type", "integer")
                        put("minimum", 1)
                        put("default", 500)
                        put("description", "Maximum number of lines to return.")
                    })
                    put("maxBytes", buildJsonObject {
                        put("type", "integer")
                        put("minimum", 1)
                        put("default", 20000)
                        put("description", "Maximum number of bytes to return.")
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
                        put("minItems", 1)
                        put("items", buildJsonObject {
                            put("type", "object")
                            put("properties", buildJsonObject {
                                put("oldText", buildJsonObject {
                                    put("type", "string")
                                    put("description", "Text to replace. ALL occurrences in the file will be replaced. Include surrounding context to target a specific instance.")
                                })
                                put("newText", buildJsonObject {
                                    put("type", "string")
                                    put("description", "Replacement text.")
                                })
                            })
                            put("required", buildJsonArray { add("oldText"); add("newText") })
                        })
                    })
                    put("dryRun", buildJsonObject {
                        put("type", "boolean")
                        put("description", "Preview the changes without applying them.")
                        put("default", false)
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
                        put("default", ".")
                    })
                    put("sortBy", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray { add("name"); add("size") })
                        put("description", "Sort entries by name (default) or size.")
                        put("default", "name")
                    })
                    put("includeSizes", buildJsonObject {
                        put("type", "boolean")
                        put("description", "Include file sizes in the listing.")
                        put("default", false)
                    })
                    put("recursive", buildJsonObject {
                        put("type", "boolean")
                        put("description", "Recursively list subdirectories with indentation.")
                        put("default", false)
                    })
                    put("maxEntries", buildJsonObject {
                        put("type", "integer")
                        put("minimum", 1)
                        put("default", 25)
                        put("description", "Maximum number of directory entries to return.")
                    })
                })
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
            description = "Search for files/directories by glob pattern.",
            inputSchema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("path", buildJsonObject {
                        put("type", "string")
                        put("description", "Starting directory relative to the workspace (defaults to the workspace root).")
                        put("default", ".")
                    })
                    put("pattern", buildJsonObject {
                        put("type", "string")
                        put("description", "Glob pattern (e.g. '**.kt'). Use ** for recursive matching from the starting directory; a bare '*.kt' matches only the starting directory.")
                    })
                    put("excludePatterns", buildJsonObject {
                        put("oneOf", buildJsonArray {
                            add(buildJsonObject {
                                put("type", "string")
                                put("description", "Single glob pattern to exclude.")
                            })
                            add(buildJsonObject {
                                put("type", "array")
                                put("items", buildJsonObject { put("type", "string") })
                                put("description", "List of glob patterns to exclude.")
                            })
                        })
                        put("description", "Optional glob pattern(s) to exclude. Supports both string and array formats.")
                    })
                    put("maxResults", buildJsonObject {
                        put("type", "integer")
                        put("minimum", 1)
                        put("default", 25)
                        put("description", "Maximum number of matching files/directories to return.")
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
                        put("description", "Executable name (must be on PATH or absolute). This should be only the executable or program name, not the full command line with arguments.")
                    })
                    put("args", buildJsonObject {
                        put("oneOf", buildJsonArray {
                            add(buildJsonObject {
                                put("type", "string")
                                put("description", "A single command-line argument.")
                            })
                            add(buildJsonObject {
                                put("type", "array")
                                put("items", buildJsonObject { put("type", "string") })
                                put("description", "Command-line arguments.")
                            })
                        })
                        put("description", "Command-line arguments. Accepts a single string or an array of strings. Arguments belong here and should be separate entries.")
                    })
                    put("timeout", buildJsonObject {
                        put("type", "integer")
                        put("minimum", 1)
                        put(
                            "description",
                            "Timeout in seconds. Defaults to the worker's builtInTools.defaultCommandTimeoutSeconds."
                        )
                    })
                    put("maxLines", buildJsonObject {
                        put("type", "integer")
                        put("minimum", 1)
                        put("default", 50)
                        put("description", "Maximum number of output lines to return.")
                    })
                    put("maxBytes", buildJsonObject {
                        put("type", "integer")
                        put("minimum", 1)
                        put("default", 2000)
                        put("description", "Maximum number of output bytes to return.")
                    })
                })
                put("required", buildJsonArray { add("command") })
            }
        ),
        BuiltInToolSpec(
            builtInToolName = "search_text",
            description = "Search UTF-8 text files in the workspace for matching text or regex patterns, returning matching file paths and line numbers.",
            inputSchema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("path", buildJsonObject {
                        put("type", "string")
                        put("description", "Starting directory or file path relative to the workspace. Defaults to the workspace root.")
                        put("default", ".")
                    })
                    put("query", buildJsonObject {
                        put("type", "string")
                        put("description", "Text or regex pattern to search for.")
                    })
                    put("mode", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray { add("plain"); add("regex") })
                        put("description", "Interpret 'query' as plain text or a regular expression. Use 'plain' for exact literal matching.")
                        put("default", "regex")
                    })
                    put("caseSensitive", buildJsonObject {
                        put("type", "boolean")
                        put("description", "Whether matching is case-sensitive.")
                        put("default", false)
                    })
                    put("wholeWord", buildJsonObject {
                        put("type", "boolean")
                        put("description", "When true, matches whole words only. Only valid and applicable when mode='plain'.")
                        put("default", false)
                    })
                    put("filePattern", buildJsonObject {
                        put("type", "string")
                        put("description", "Optional glob pattern to include only matching files, for example '*.kt' or '*.md'. A bare '*.kt' matches only files in the starting directory; use '**.kt' to match recursively.")
                    })
                    put("excludePatterns", buildJsonObject {
                        put("oneOf", buildJsonArray {
                            add(buildJsonObject {
                                put("type", "string")
                                put("description", "Single glob pattern to exclude.")
                            })
                            add(buildJsonObject {
                                put("type", "array")
                                put("items", buildJsonObject { put("type", "string") })
                                put("description", "List of glob patterns to exclude.")
                            })
                        })
                        put("description", "Optional glob pattern(s) to exclude. Supports both string and array formats.")
                    })
                    put("contextBefore", buildJsonObject {
                        put("type", "integer")
                        put("minimum", 0)
                        put("description", "Number of context lines to include before each match.")
                        put("default", 0)
                    })
                    put("contextAfter", buildJsonObject {
                        put("type", "integer")
                        put("minimum", 0)
                        put("description", "Number of context lines to include after each match.")
                        put("default", 0)
                    })
                    put("maxResults", buildJsonObject {
                        put("type", "integer")
                        put("minimum", 1)
                        put("default", 10)
                        put("description", "Maximum number of matched lines to return across all files.")
                    })
                    put("maxBytes", buildJsonObject {
                        put("type", "integer")
                        put("minimum", 1)
                        put("default", 1200)
                        put("description", "Maximum number of text bytes returned in the tool output.")
                    })
                })
                put("required", buildJsonArray { add("query") })
            }
        ),
        BuiltInToolSpec(
            builtInToolName = "fetch_web_content",
            description = "Fetch textual content from a public internet URL. Localhost, loopback, link-local, and private-network addresses are not allowed.",
            inputSchema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("url", buildJsonObject {
                        put("type", "string")
                        put("format", "uri")
                        put("description", "Public internet URL to fetch.")
                    })
                    put("range", buildJsonObject {
                        put("type", "array")
                        put("minItems", 2)
                        put("maxItems", 2)
                        put("items", buildJsonObject {
                            put("type", buildJsonArray {
                                add("integer")
                                add("null")
                            })
                        })
                        put(
                            "description",
                            "Line range as [start, end), matching Python slice semantics. " +
                            "Negative values count from the end. Use null for open-ended."
                        )
                    })
                    put("timeoutSeconds", buildJsonObject {
                        put("type", "integer")
                        put("minimum", 1)
                        put("description", "Optional request timeout in seconds.")
                    })
                    put("maxDownloadBytes", buildJsonObject {
                        put("type", "integer")
                        put("minimum", 1)
                        put("default", 100000)
                        put("description", "Maximum number of response bytes to download from the network.")
                    })
                    put("maxBytes", buildJsonObject {
                        put("type", "integer")
                        put("minimum", 1)
                        put("default", 20000)
                        put("description", "Maximum number of text bytes returned in the tool output.")
                    })
                    put("maxLines", buildJsonObject {
                        put("type", "integer")
                        put("minimum", 1)
                        put("default", 500)
                        put("description", "Maximum number of text lines returned in the tool output.")
                    })
                    put("followRedirects", buildJsonObject {
                        put("type", "boolean")
                        put("description", "Whether HTTP redirects should be followed.")
                        put("default", true)
                    })
                    put("returnMode", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray { add("auto"); add("text"); add("html") })
                        put("description", "How to interpret the response body. 'auto' uses the response content type.")
                        put("default", "auto")
                    })
                })
                put("required", buildJsonArray { add("url") })
            }
        ),
        BuiltInToolSpec(
            builtInToolName = "download_file",
            description = "Download content from a public internet URL directly to a file inside the workspace. Supports binary data. Localhost, loopback, link-local, and private-network addresses are not allowed.",
            inputSchema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("url", buildJsonObject {
                        put("type", "string")
                        put("format", "uri")
                        put("description", "Public internet URL to download.")
                    })
                    put("path", buildJsonObject {
                        put("type", "string")
                        put("description", "Destination file path relative to the workspace.")
                    })
                    put("overwrite", buildJsonObject {
                        put("type", "boolean")
                        put("description", "Whether to overwrite the destination file if it already exists.")
                        put("default", false)
                    })
                    put("timeoutSeconds", buildJsonObject {
                        put("type", "integer")
                        put("minimum", 1)
                        put("description", "Optional request timeout in seconds.")
                    })
                    put("maxBytes", buildJsonObject {
                        put("type", "integer")
                        put("minimum", 1)
                        put("description", "Maximum number of bytes allowed for the download.")
                    })
                    put("followRedirects", buildJsonObject {
                        put("type", "boolean")
                        put("description", "Whether HTTP redirects should be followed.")
                        put("default", true)
                    })
                })
                put("required", buildJsonArray { add("url"); add("path") })
            }
        ),
    )

    /**
     * Number of built-in tool specifications in the catalog.
     */
    val size: Int get() = allTools.size

    /**
     * Looks up a built-in tool specification by its unprefixed canonical name.
     *
     * @param builtInToolName Unprefixed tool name (e.g. `read_text_file`).
     * @return The matching [BuiltInToolSpec], or null when the name is unknown.
     */
    fun specFor(builtInToolName: String): BuiltInToolSpec? =
        allTools.firstOrNull { it.builtInToolName == builtInToolName }
}
