This document provides a detailed reference for the Kotlin Model Context Protocol (MCP) SDK, with a specific focus on **Tools**. The MCP is an open-source standard by Anthropic, designed to standardize how AI applications interact with external systems. The Kotlin SDK offers a multiplatform implementation, enabling developers to build both MCP clients and servers across JVM, WebAssembly, and Native platforms.

## Table of Contents

1.  [Introduction to Kotlin MCP SDK and Tools](#1-introduction-to-kotlin-mcp-sdk-and-tools)
    *   1.1 What is MCP?
    *   1.2 Kotlin SDK Overview
    *   1.3 Focus on Tools
2.  [Server-Side Tool Implementation](#2-server-side-tool-implementation)
    *   2.1 Initializing the MCP Server
    *   2.2 Defining a Tool
    *   2.3 Registering Tool Handlers
    *   2.4 Dynamic Tool Management
    *   2.5 Server Transports
3.  [Client-Side Tool Implementation](#3-client-side-tool-implementation)
    *   3.1 Initializing the MCP Client
    *   3.2 Connecting to an MCP Server
    *   3.3 Discovering Available Tools
    *   3.4 Calling a Tool
    *   3.5 Client Transports
4.  [Core SDK Classes and Interfaces for Tools](#4-core-sdk-classes-and-interfaces-for-tools)
    *   4.1 `io.modelcontextprotocol.kotlin.sdk.server.Server`
    *   4.2 `io.modelcontextprotocol.kotlin.sdk.client.Client`
    *   4.3 `io.modelcontextprotocol.kotlin.sdk.Tool`
    *   4.4 `io.modelcontextprotocol.kotlin.sdk.CallToolRequest`
    *   4.5 `io.modelcontextprotocol.kotlin.sdk.CallToolResultBase` & `CallToolResult`
    *   4.6 `io.modelcontextprotocol.kotlin.sdk.ListToolsResult`
    *   4.7 Content Types (`PromptMessageContent`)
    *   4.8 `io.modelcontextprotocol.kotlin.sdk.Implementation`
    *   4.9 `io.modelcontextprotocol.kotlin.sdk.ServerCapabilities`
    *   4.10 Transport Classes
5.  [Example Walkthrough: "Hello World" Tool](#5-example-walkthrough-hello-world-tool)
    *   5.1 Server Implementation (`HelloWorldServer.kt`)
    *   5.2 Client Implementation (`HelloWorldClient.kt`)
6.  [Best Practices and Considerations for Tools](#6-best-practices-and-considerations-for-tools)
    *   6.1 Security and Trust
    *   6.2 Input and Output Schemas
    *   6.3 Error Handling
    *   6.4 Observability

---

## 1. Introduction to Kotlin MCP SDK and Tools

### 1.1 What is MCP?

The Model Context Protocol (MCP) is an open-source standard by Anthropic designed to standardize how AI applications (hosts) connect to external systems (servers). It acts as a bridge, enabling AI models to access data, invoke tools, and utilize workflows, significantly enhancing their capabilities and real-world interaction. MCP uses JSON-RPC 2.0 over various transports for message exchange.

### 1.2 Kotlin SDK Overview

The Kotlin MCP SDK is a multiplatform implementation of the MCP specification. It allows developers to build both MCP clients (the AI application side) and MCP servers (the external system side) using Kotlin. This SDK is compatible with JVM, WebAssembly, and Native targets.

### 1.3 Focus on Tools

This reference specifically focuses on **Tools**, which are one of the core building blocks in MCP.
*   **Purpose:** Tools are executable functions that AI applications can invoke to perform actions in the real world (e.g., file operations, API calls, database queries, interacting with specialized prompts).
*   **How they work:**
    1.  An AI application (MCP Client) discovers available tools from an MCP Server.
    2.  Based on user requests and context, the AI decides which tool to invoke.
    3.  The client calls the tool on the server with specific arguments.
    4.  The server executes the tool's logic and returns a structured result.
*   **User Interaction Model:** While AI-controlled, MCP emphasizes human oversight. Clients should provide UI displays, approval dialogs, permission settings, and activity logs to ensure trust and safety when tools are invoked.

## 2. Server-Side Tool Implementation

An MCP server provides contextual information and capabilities, including tools, to MCP clients.

### 2.1 Initializing the MCP Server

To create an MCP server that offers tools, you first instantiate the `Server` class. You must provide `serverInfo` (for identification) and `ServerOptions`, specifying the capabilities your server supports.

**Key Class:** `io.modelcontextprotocol.kotlin.sdk.server.Server`

```kotlin
// From: kotlin-sdk-server/src/commonMain/kotlin/io/modelcontextprotocol/kotlin/sdk/server/Server.kt
public open class Server(
    protected val serverInfo: Implementation,
    protected val options: ServerOptions,
    protected val instructionsProvider: (() -> String)? = null,
)

// From: kotlin-sdk-server/src/commonMain/kotlin/io/modelcontextprotocol/kotlin/sdk/server/Server.kt
public class ServerOptions(public val capabilities: ServerCapabilities, enforceStrictCapabilities: Boolean = true)

// From: kotlin-sdk-core/src/commonMain/kotlin/io/modelcontextprotocol/kotlin/sdk/types.kt
public data class Implementation(val name: String, val version: String)
public data class ServerCapabilities(
    // ... other capabilities
    val tools: Tools? = null, // Define capabilities for tools
) {
    public data class Tools(
        val listChanged: Boolean?, // Whether the server can notify clients of tool list changes
    )
}
```

**Example (`HelloWorldServer.kt`):**

```kotlin
// file: server/src/main/kotlin/eu/torvian/mcp/helloworld/server/HelloWorldServer.kt
val server = Server(
    serverInfo = Implementation(
        name = "hello-world-server",
        version = "1.0.0"
    ),
    options = ServerOptions(
        capabilities = ServerCapabilities(
            tools = ServerCapabilities.Tools(listChanged = true) // Declare tool capability
        )
    )
)
```
Here, `ServerCapabilities.Tools(listChanged = true)` indicates that this server supports providing tools and can notify clients if its list of tools changes.

### 2.2 Defining a Tool

A tool is defined by its `Tool` data class, which specifies its name, description, and the JSON Schema for its expected inputs and outputs.

**Key Class:** `io.modelcontextprotocol.kotlin.sdk.Tool`

```kotlin
// From: kotlin-sdk-core/src/commonMain/kotlin/io/modelcontextprotocol/kotlin/sdk/types.kt
public data class Tool(
    val name: String,         // The unique identifier for this tool.
    val title: String?,       // A human-readable title.
    val description: String?, // A human-readable description of the tool.
    val inputSchema: Input,   // JSON Schema for input parameters.
    val outputSchema: Output?,// Optional JSON Schema for output.
    val annotations: ToolAnnotations?, // Optional additional tool info.
) {
    public data class Input(val properties: JsonObject = EmptyJsonObject, val required: List<String>? = null)
    public data class Output(val properties: JsonObject = EmptyJsonObject, val required: List<String>? = null)
}
public data class ToolAnnotations(
    val title: String?,          // Human-readable title (redundant with main title but for hint)
    val readOnlyHint: Boolean? = false, // True if tool does not modify environment
    val destructiveHint: Boolean? = true, // True if tool performs destructive updates
    val idempotentHint: Boolean? = false, // True if calling repeatedly has no additional effect
    val openWorldHint: Boolean? = true,   // True if tool interacts with an "open world"
)
```

**Example (`HelloWorldServer.kt`):**

```kotlin
// file: server/src/main/kotlin/eu/torvian/mcp/helloworld/server/HelloWorldServer.kt
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

val greetTool = Tool(
    name = "greet",
    title = "Greet User",
    description = "Returns a simple greeting.",
    inputSchema = Tool.Input(
        properties = buildJsonObject {
            put("name", buildJsonObject { // Define 'name' argument
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("The name to greet."))
            })
        },
        required = listOf("name") // 'name' argument is mandatory
    ),
    outputSchema = null, // No explicit output schema
    annotations = null
)
```

### 2.3 Registering Tool Handlers

Once a `Tool` definition is ready, you associate it with an implementation logic (a handler function) using `server.addTool()`.

**Key Methods:** `Server.addTool()`

```kotlin
// From: kotlin-sdk-server/src/commonMain/kotlin/io/modelcontextprotocol/kotlin/sdk/server/Server.kt
public fun addTool(tool: Tool, handler: suspend (CallToolRequest) -> CallToolResult)

public fun addTool( // Overload for convenience
    name: String,
    description: String,
    inputSchema: Tool.Input = Tool.Input(),
    title: String? = null,
    outputSchema: Tool.Output? = null,
    toolAnnotations: ToolAnnotations? = null,
    handler: suspend (CallToolRequest) -> CallToolResult,
)
```

The `handler` function receives a `CallToolRequest` containing the tool's arguments and must return a `CallToolResult`.

**Key Classes:** `io.modelcontextprotocol.kotlin.sdk.CallToolRequest`, `io.modelcontextprotocol.kotlin.sdk.CallToolResult`, `io.modelcontextprotocol.kotlin.sdk.TextContent`

```kotlin
// From: kotlin-sdk-core/src/commonMain/kotlin/io/modelcontextprotocol/kotlin/sdk/types.kt
public data class CallToolRequest(
    val name: String,
    val arguments: JsonObject = EmptyJsonObject, // Arguments from client
    override val _meta: JsonObject = EmptyJsonObject,
)

public class CallToolResult(
    override val content: List<PromptMessageContent>, // List of content (e.g., TextContent)
    override val structuredContent: JsonObject? = null,
    override val isError: Boolean? = false,
    override val _meta: JsonObject = EmptyJsonObject,
) : CallToolResultBase

public data class TextContent(
    val text: String? = null,
    val annotations: Annotations? = null,
) : PromptMessageContentMultimodal // One type of content a tool can return
```

**Example (`HelloWorldServer.kt`):**

```kotlin
// file: server/src/main/kotlin/eu/torvian/mcp/helloworld/server/HelloWorldServer.kt
import io.modelcontextprotocol.kotlin.sdk.TextContent

server.addTool(greetTool) { request ->
    val name = (request.arguments["name"] as? JsonPrimitive)?.content ?: "World"
    val greeting = "Hello, $name!"
    println("Server: Called 'greet' with name='$name'. Responding with: '$greeting'")

    CallToolResult(
        content = listOf(
            TextContent(text = greeting) // Return the greeting as TextContent
        )
    )
}
```

### 2.4 Dynamic Tool Management

Servers can dynamically add or remove tools. If `ServerCapabilities.Tools.listChanged` is `true`, the server can notify clients about these changes.

**Key Methods:** `Server.removeTool()`, `Server.removeTools()`, `ServerSession.sendToolListChanged()`

```kotlin
// From: kotlin-sdk-server/src/commonMain/kotlin/io/modelcontextprotocol/kotlin/sdk/server/Server.kt
public fun removeTool(name: String): Boolean
public fun removeTools(toolNames: List<String>): Int

// From: kotlin-sdk-server/src/commonMain/kotlin/io/modelcontextprotocol/kotlin/sdk/server/ServerSession.kt
public suspend fun sendToolListChanged() // Sends ToolListChangedNotification
```

### 2.5 Server Transports

The server needs a transport to communicate. The Kotlin SDK supports `StdioServerTransport` for local processes and HTTP-based transports (like SSE or WebSocket) using Ktor.

**Key Classes:** `io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport`

```kotlin
// From: kotlin-sdk-server/src/commonMain/kotlin/io/modelcontextprotocol/kotlin/sdk/server/StdioServerTransport.kt
public class StdioServerTransport(private val inputStream: Source, outputStream: Sink)
```

**Example (`HelloWorldServer.kt`):**

```kotlin
// file: server/src/main/kotlin/eu/torvian/mcp/helloworld/server/HelloWorldServer.kt
val transport = StdioServerTransport(
    inputStream = System.`in`.asSource().buffered(),
    outputStream = System.out.asSink().buffered()
)
server.connect(transport)
```

For HTTP transports, you'd typically use Ktor's `mcp {}` DSL:

```kotlin
// file: docs/MCP/Kotlin-SDK-0.7.3/README.md
import io.ktor.server.application.*
import io.modelcontextprotocol.kotlin.sdk.server.mcp
fun Application.module() {
    mcp {
        // ... Server instance configuration ...
    }
}
```

## 3. Client-Side Tool Implementation

An MCP client (typically an AI application) connects to servers to discover and utilize their exposed tools.

### 3.1 Initializing the MCP Client

To create an MCP client that can interact with tools, you instantiate the `Client` class, providing `clientInfo`.

**Key Class:** `io.modelcontextprotocol.kotlin.sdk.client.Client`

```kotlin
// From: kotlin-sdk-client/src/commonMain/kotlin/io/modelcontextprotocol/kotlin/sdk/client/Client.kt
public open class Client(private val clientInfo: Implementation, options: ClientOptions = ClientOptions())
```

**Example (`HelloWorldClient.kt`):**

```kotlin
// file: client/src/main/kotlin/eu/torvian/mcp/helloworld/client/HelloWorldClient.kt
private val mcp: Client = Client(clientInfo = Implementation(name = "hello-world-client", version = "1.0.0"))
```

### 3.2 Connecting to an MCP Server

The client establishes a connection to the server via a transport and performs an initialization handshake.

**Key Method:** `Client.connect()`

```kotlin
// From: kotlin-sdk-client/src/commonMain/kotlin/io/modelcontextprotocol/kotlin/sdk/client/Client.kt
override suspend fun connect(transport: Transport)
```

**Example (`HelloWorldClient.kt`):**

```kotlin
// file: client/src/main/kotlin/eu/torvian/mcp/helloworld/client/HelloWorldClient.kt
val process = ProcessBuilder(command).start() // Start server as subprocess
val transport = StdioClientTransport(
    input = process.inputStream.asSource().buffered(), // Client reads server's stdout
    output = process.outputStream.asSink().buffered(), // Client writes to server's stdin
)
mcp.connect(transport)
```

### 3.3 Discovering Available Tools

After connection and initialization, the client can request the list of tools available from the server.

**Key Method:** `Client.listTools()`

```kotlin
// From: kotlin-sdk-client/src/commonMain/kotlin/io/modelcontextprotocol/kotlin/sdk/client/Client.kt
public suspend fun listTools(request: ListToolsRequest = ListToolsRequest(), options: RequestOptions? = null): ListToolsResult

// From: kotlin-sdk-core/src/commonMain/kotlin/io/modelcontextprotocol/kotlin/sdk/types.kt
public class ListToolsResult(
    public val tools: List<Tool>, // List of available Tool objects
    override val nextCursor: Cursor?,
    override val _meta: JsonObject = EmptyJsonObject,
)
```

**Example (`HelloWorldClient.kt`):**

```kotlin
// file: client/src/main/kotlin/eu/torvian/mcp/helloworld/client/HelloWorldClient.kt
val toolsResult = mcp.listTools()
availableTools = toolsResult.tools.associateBy { it.name }
println("Client: Discovered tools from server: ${availableTools.keys.joinToString(", ")}")
```

### 3.4 Calling a Tool

Once a tool is discovered, the client can invoke it by name, passing the required arguments.

**Key Methods:** `Client.callTool()`

```kotlin
// From: kotlin-sdk-client/src/commonMain/kotlin/io/modelcontextprotocol/kotlin/sdk/client/Client.kt
public suspend fun callTool(
    name: String,
    arguments: Map<String, Any?>, // Arguments as a Kotlin Map
    meta: Map<String, Any?> = emptyMap(),
    compatibility: Boolean = false,
    options: RequestOptions? = null,
): CallToolResultBase?

public suspend fun callTool( // Overload accepting CallToolRequest
    request: CallToolRequest,
    compatibility: Boolean = false,
    options: RequestOptions? = null,
): CallToolResultBase?
```

The client handles the serialization of Kotlin `Map<String, Any?>` to JSON `JsonObject` for the `arguments` and `_meta` fields in `CallToolRequest`. The return value is `CallToolResultBase`, which is typically cast to `CallToolResult`.

**Example (`HelloWorldClient.kt`):**

```kotlin
// file: client/src/main/kotlin/eu/torvian/mcp/helloworld/client/HelloWorldClient.kt
val arguments = mapOf("name" to nameArg)
println("Client: Calling tool '$toolName' with arguments: $arguments")

val result = mcp.callTool(name = toolName, arguments = arguments)

val resultText = result?.content
    ?.filterIsInstance<TextContent>() // Filter for text content
    ?.joinToString("\n") { it.text.toString() }

println("Server Response: $resultText")
```

### 3.5 Client Transports

Similar to servers, clients need a transport mechanism.

**Key Classes:** `io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport`

```kotlin
// From: kotlin-sdk-client/src/commonMain/kotlin/io/modelcontextprotocol/kotlin/sdk/client/StdioClientTransport.kt
public class StdioClientTransport(private val input: Source, private val output: Sink)
```

## 4. Core SDK Classes and Interfaces for Tools

This section details the primary Kotlin SDK components directly involved in defining, registering, discovering, and calling tools.

### 4.1 `io.modelcontextprotocol.kotlin.sdk.server.Server`

The main entry point for creating an MCP server.

*   **Constructor:**
    *   `serverInfo: Implementation`: Details of the server (name, version).
    *   `options: ServerOptions`: Configuration, including `ServerCapabilities`.
    *   `instructionsProvider: (() -> String)?`: Optional provider for server instructions.
*   **Key Methods for Tools:**
    *   `addTool(tool: Tool, handler: suspend (CallToolRequest) -> CallToolResult)`: Registers a `Tool` definition with its execution logic.
    *   `addTool(name: String, description: String, inputSchema: Tool.Input, ...)`: Convenience overload for `addTool`.
    *   `removeTool(name: String)`: Unregisters a tool by its name.
    *   `removeTools(toolNames: List<String>)`: Unregisters multiple tools.
    *   `connect(transport: Transport)`: Establishes the connection and starts listening.

### 4.2 `io.modelcontextprotocol.kotlin.sdk.client.Client`

The main entry point for creating an MCP client.

*   **Constructor:**
    *   `clientInfo: Implementation`: Details of the client (name, version).
    *   `options: ClientOptions`: Configuration, including `ClientCapabilities`.
*   **Key Methods for Tools:**
    *   `listTools(request: ListToolsRequest = ListToolsRequest()): ListToolsResult`: Discovers all tools offered by the connected server.
    *   `callTool(name: String, arguments: Map<String, Any?>, ...): CallToolResultBase?`: Invokes a tool by name with a map of arguments.
    *   `callTool(request: CallToolRequest, ...): CallToolResultBase?`: Invokes a tool using a pre-constructed `CallToolRequest` object.
    *   `connect(transport: Transport)`: Establishes the connection and performs initialization.

### 4.3 `io.modelcontextprotocol.kotlin.sdk.Tool`

A data class representing the definition of a tool.

*   **`name: String`**: Unique identifier for the tool.
*   **`title: String?`**: Human-readable display name.
*   **`description: String?`**: Explains what the tool does.
*   **`inputSchema: Tool.Input`**: Defines the expected input parameters using a JSON Schema-like structure (`properties: JsonObject`, `required: List<String>`).
*   **`outputSchema: Tool.Output?`**: Defines the expected output structure.
*   **`annotations: ToolAnnotations?`**: Provides hints about tool behavior (e.g., `readOnlyHint`, `destructiveHint`).

### 4.4 `io.modelcontextprotocol.kotlin.sdk.CallToolRequest`

The request object sent from the client to the server when invoking a tool.

*   **`name: String`**: The name of the tool to be called.
*   **`arguments: JsonObject`**: A JSON object containing the arguments that match the tool's `inputSchema`.
*   **`_meta: JsonObject`**: Optional additional metadata for the request.

### 4.5 `io.modelcontextprotocol.kotlin.sdk.CallToolResultBase` & `CallToolResult`

The base interface and concrete class for the server's response to a tool call.

*   **`content: List<PromptMessageContent>`**: A list of various content types (text, image, embedded resources) produced by the tool.
*   **`structuredContent: JsonObject?`**: Optional structured JSON output from the tool.
*   **`isError: Boolean?`**: Indicates if the tool execution resulted in an error (default `false`).
*   **`_meta: JsonObject`**: Optional additional metadata for the response.

`CallToolResult` is the standard implementation. `CompatibilityCallToolResult` exists for older protocol versions.

### 4.6 `io.modelcontextprotocol.kotlin.sdk.ListToolsResult`

The response object from `Client.listTools()`, containing the discovered tools.

*   **`tools: List<Tool>`**: A list of `Tool` objects available on the server.
*   **`nextCursor: Cursor?`**: For paginated results (not typically used for `listTools`).

### 4.7 Content Types (`PromptMessageContent`)

Tools return their results in a list of `PromptMessageContent`. The SDK provides several sealed interface implementations:

*   **`TextContent(text: String?, annotations: Annotations?)`**: Plain text output.
*   **`ImageContent(data: String, mimeType: String, annotations: Annotations?)`**: Base64-encoded image data.
*   **`AudioContent(data: String, mimeType: String, annotations: Annotations?)`**: Base64-encoded audio data.
*   **`EmbeddedResource(resource: ResourceContents, annotations: Annotations?)`**: Contents of another resource, embedded.
*   `UnknownContent(type: String)`: Placeholder for unrecognized content types.

### 4.8 `io.modelcontextprotocol.kotlin.sdk.Implementation`

A simple data class used to identify both the client and server implementations.

*   **`name: String`**: The name of the implementation.
*   **`version: String`**: The version string of the implementation.

### 4.9 `io.modelcontextprotocol.kotlin.sdk.ServerCapabilities`

A data class indicating what features the server supports. For tools, the relevant part is:

*   **`tools: ServerCapabilities.Tools?`**: If present, indicates the server provides tool-related capabilities.
    *   **`listChanged: Boolean?`**: Whether the server can notify clients when its tool list changes.

### 4.10 Transport Classes

These classes implement the `io.modelcontextprotocol.kotlin.sdk.shared.Transport` interface for specific communication channels.

*   **`StdioServerTransport(inputStream: Source, outputStream: Sink)`**: For server-side communication over standard input/output.
*   **`StdioClientTransport(input: Source, output: Sink)`**: For client-side communication over standard input/output.

## 5. Example Walkthrough: "Hello World" Tool

Let's examine the provided `HelloWorldServer.kt` and `HelloWorldClient.kt` to illustrate the concepts.

### 5.1 Server Implementation (`HelloWorldServer.kt`)

```kotlin
package eu.torvian.mcp.helloworld.server

import io.modelcontextprotocol.kotlin.sdk.* // Import all necessary SDK types
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

fun main(): Unit = runBlocking {
    println("Starting Hello World MCP Server...")

    // 1. Initialize the MCP Server, declaring its 'tools' capability
    val server = Server(
        serverInfo = Implementation(name = "hello-world-server", version = "1.0.0"),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = true)
            )
        )
    )

    // 2. Define the 'greet' tool with its input schema
    val greetTool = Tool(
        name = "greet",
        title = "Greet User",
        description = "Returns a simple greeting.",
        inputSchema = Tool.Input(
            properties = buildJsonObject {
                put("name", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("The name to greet."))
                })
            },
            required = listOf("name")
        ),
        outputSchema = null,
        annotations = null
    )

    // 3. Add the tool to the server and provide its handler logic
    server.addTool(greetTool) { request ->
        val name = (request.arguments["name"] as? JsonPrimitive)?.content ?: "World"
        val greeting = "Hello, $name!"
        println("Server: Called 'greet' with name='$name'. Responding with: '$greeting'")

        // Return the greeting as TextContent within a CallToolResult
        CallToolResult(content = listOf(TextContent(text = greeting)))
    }

    // 4. Start the server using STDIO transport
    val transport = StdioServerTransport(
        inputStream = System.`in`.asSource().buffered(),
        outputStream = System.out.asSink().buffered()
    )
    val done = Job()
    server.onClose { done.complete() }
    server.connect(transport)
    done.join()
    println("Server closed.")
}
```

**Summary of Server-Side Tool Flow:**
1.  **Server Initialization:** An `Server` instance is created, explicitly enabling the `tools` capability in `ServerOptions`.
2.  **Tool Definition:** A `Tool` object (`greetTool`) is defined with a `name`, `title`, `description`, and `inputSchema` specifying the `name` argument.
3.  **Tool Registration:** `server.addTool(greetTool) { ... }` registers the tool and provides a lambda function as its handler. This handler extracts the `name` argument from the `CallToolRequest`, constructs a greeting, and returns it wrapped in a `CallToolResult` containing `TextContent`.
4.  **Transport Connection:** The server connects via `StdioServerTransport` to listen for incoming client requests.

### 5.2 Client Implementation (`HelloWorldClient.kt`)

```kotlin
package eu.torvian.mcp.helloworld.client

import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.Tool
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import java.io.File // Assuming JVM for ProcessBuilder

class HelloWorldClient : AutoCloseable {
    // 1. Initialize the core MCP Client instance
    private val mcp: Client = Client(clientInfo = Implementation(name = "hello-world-client", version = "1.0.0"))
    private lateinit var availableTools: Map<String, Tool>

    // 2. Connect to server (launches subprocess, sets up STDIO)
    suspend fun connectToServer(serverScriptPath: String) {
        try {
            val command = listOf("java", "-jar", serverScriptPath)
            println("Client: Starting server process: ${command.joinToString(" ")}")
            val process = ProcessBuilder(command).start()

            val transport = StdioClientTransport(
                input = process.inputStream.asSource().buffered(),
                output = process.outputStream.asSink().buffered(),
            )
            mcp.connect(transport)
            println("Client: Successfully connected to MCP server.")

            // 3. Discover tools from the server
            val toolsResult = mcp.listTools()
            availableTools = toolsResult.tools.associateBy { it.name }
            println("Client: Discovered tools from server: ${availableTools.keys.joinToString(", ")}")
        } catch (e: Exception) {
            println("Client: Failed to connect to MCP server: $e")
            throw e
        }
    }

    // 4. Interactive loop to call the 'greet' tool
    suspend fun interactiveToolLoop() {
        println("\n--- Interactive Tool Caller ---")
        println("Type 'greet' to call the tool, or 'quit' to exit.")

        while (true) {
            print("\n> Enter tool name: ")
            val toolName = readlnOrNull()?.trim() ?: break
            if (toolName.equals("quit", ignoreCase = true)) break

            val tool = availableTools[toolName]
            if (tool == null) {
                println("Unknown tool '$toolName'. Available tools are: ${availableTools.keys.joinToString(", ")}")
                continue
            }

            if (toolName == "greet") {
                print("> Enter value for 'name': ")
                val nameArg = readlnOrNull() ?: ""
                val arguments = mapOf("name" to nameArg) // Prepare arguments

                println("Client: Calling tool '$toolName' with arguments: $arguments")
                // 5. Call the tool on the server
                val result = mcp.callTool(name = toolName, arguments = arguments)

                // Process the tool's response
                val resultText = result?.content
                    ?.filterIsInstance<TextContent>()
                    ?.joinToString("\n") { it.text.toString() }

                println("Server Response: $resultText")
            } else {
                println("This client only knows how to call the 'greet' tool.")
            }
        }
    }

    override fun close() {
        runBlocking {
            mcp.close()
            println("Client: Connection closed.")
        }
    }
}
```

**Summary of Client-Side Tool Flow:**
1.  **Client Initialization:** An `Client` instance is created.
2.  **Connection:** `client.connectToServer()` launches the server as a subprocess and sets up `StdioClientTransport` to communicate with its stdin/stdout. `mcp.connect(transport)` then performs the MCP handshake.
3.  **Tool Discovery:** `mcp.listTools()` is called to retrieve all available tools from the server, which are then cached in `availableTools`.
4.  **Tool Invocation:** In the `interactiveToolLoop`, the user inputs a name. The client prepares a `Map<String, Any?>` for the arguments and calls `mcp.callTool(name = toolName, arguments = arguments)`.
5.  **Result Processing:** The `CallToolResult` is received. The client extracts `TextContent` from its `content` list and prints the greeting.
6.  **Cleanup:** The `close()` method ensures the MCP connection is terminated.

## 6. Best Practices and Considerations for Tools

### 6.1 Security and Trust

Tools represent arbitrary code execution. When building or integrating with MCP, prioritize security:

*   **User Consent and Control:** Always seek explicit user consent before invoking tools, especially those that modify external systems. Provide clear UI for approvals and activity logs.
*   **Input Validation:** Thoroughly validate all input received from the client (AI application) against the tool's `inputSchema` to prevent injection attacks or unexpected behavior.
*   **Trusted Servers:** Consider tool descriptions and annotations as untrusted unless the server is explicitly known and trusted. The client should enforce its own security policies.
*   **Permissions:** Implement granular permission models for tools, allowing users to control what actions an AI can perform.

### 6.2 Input and Output Schemas

*   **JSON Schema:** Use robust JSON Schema definitions for `inputSchema` and `outputSchema` to ensure clear contracts between client and server. This helps AI models understand tool capabilities and expected parameters.
*   **Documentation:** Provide comprehensive `description` fields for tools and their arguments, aiding AI models in understanding when and how to use them effectively.

### 6.3 Error Handling

*   **Server-Side:** Tool handlers should catch exceptions during execution and return a `CallToolResult` with `isError = true` and appropriate `content` (e.g., `TextContent` describing the error) or `structuredContent` for machine-readable errors.
*   **Client-Side:** Clients should be prepared to handle `CallToolResult`s where `isError` is true and display user-friendly error messages or take corrective actions. Network errors or protocol violations during `callTool` will typically result in exceptions on the client side.

### 6.4 Observability

*   **Logging:** Implement comprehensive logging on both client and server sides to track tool discovery, invocation, arguments, and results. This is crucial for debugging, monitoring, and auditing. The SDK provides `LoggingMessageNotification` for server-to-client logging.
*   **Tracing:** For complex interactions involving multiple tools or servers, consider distributed tracing to understand the full flow of requests.

