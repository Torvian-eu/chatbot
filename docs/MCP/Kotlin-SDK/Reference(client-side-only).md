This document provides a detailed reference for developing **client-side** applications using the Kotlin Model Context Protocol (MCP) SDK, with an exclusive focus on integrating and utilizing **Tools**. We will concentrate on how your AI application (the MCP client) can discover, invoke, and process the results of tools exposed by external MCP servers.

## Table of Contents

1.  [Introduction to Kotlin MCP Client and Tools](#1-introduction-to-kotlin-mcp-client-and-tools)
    *   1.1 What is MCP (Client Perspective)?
    *   1.2 Kotlin SDK Client Overview
    *   1.3 Focus on Client-Side Tool Interaction
2.  [Client-Side Tool Implementation Details](#2-client-side-tool-implementation-details)
    *   2.1 Initializing the MCP Client
    *   2.2 Connecting to an MCP Server
    *   2.3 Discovering Available Tools
    *   2.4 Calling a Tool
    *   2.5 Processing Tool Results
    *   2.6 Client Transports
3.  [Core SDK Classes and Interfaces for Client-Side Tool Interaction](#3-core-sdk-classes-and-interfaces-for-client-side-tool-interaction)
    *   3.1 `io.modelcontextprotocol.kotlin.sdk.client.Client`
    *   3.2 `io.modelcontextprotocol.kotlin.sdk.ClientOptions` & `io.modelcontextprotocol.kotlin.sdk.ClientCapabilities`
    *   3.3 `io.modelcontextprotocol.kotlin.sdk.Tool`
    *   3.4 `io.modelcontextprotocol.kotlin.sdk.CallToolRequest`
    *   3.5 `io.modelcontextprotocol.kotlin.sdk.CallToolResultBase` & `CallToolResult`
    *   3.6 `io.modelcontextprotocol.kotlin.sdk.ListToolsRequest` & `ListToolsResult`
    *   3.7 Content Types (`PromptMessageContent`)
    *   3.8 `io.modelcontextprotocol.kotlin.sdk.Implementation`
    *   3.9 `io.modelcontextprotocol.kotlin.sdk.ServerCapabilities`
    *   3.10 Transport Classes
4.  [Example Walkthrough: "Hello World" Client](#4-example-walkthrough-hello-world-client)
    *   4.1 Client Application Entry Point (`Main.kt`)
    *   4.2 The `HelloWorldClient` Class
5.  [Client-Side Best Practices and Considerations for Tools](#5-client-side-best-practices-and-considerations-for-tools)
    *   5.1 User Consent and Control
    *   5.2 Tool Selection and Argument Construction
    *   5.3 Processing Untrusted Tool Descriptions and Results
    *   5.4 Error Handling and Recovery
    *   5.5 Observability (Logging)

---

## 1. Introduction to Kotlin MCP Client and Tools

### 1.1 What is MCP (Client Perspective)?

The Model Context Protocol (MCP) is an open-source standard designed to standardize how AI applications (your client applications, acting as an **MCP Host**) connect to external systems (various **MCP Servers**). From a client's perspective, MCP provides a structured way to interact with a broader digital ecosystem, allowing your AI to:

*   **Access Data:** Retrieve information from databases, file systems, APIs, etc.
*   **Invoke Tools:** Perform actions by calling functions exposed by external systems.
*   **Utilize Workflows:** Engage with specialized prompts and services.

This significantly enhances your AI's capabilities, enabling it to act on behalf of users in the real world. MCP utilizes [JSON-RPC 2.0](https://www.jsonrpc.org/) for message exchange over various transport layers.

### 1.2 Kotlin SDK Client Overview

The Kotlin MCP SDK offers a multiplatform implementation of the MCP specification, enabling you to build robust MCP client applications that can communicate with any compliant MCP server. This SDK handles the underlying protocol details, allowing you to focus on your application's logic. It supports JVM, WebAssembly, and Native targets.

### 1.3 Focus on Client-Side Tool Interaction

This reference specifically focuses on how your Kotlin client application interacts with **Tools** provided by MCP servers.

*   **Purpose:** Tools are executable functions that an MCP client (your AI application) can invoke on an MCP server to perform actions. These actions can range from simple data operations to complex API calls, database queries, or external system commands.
*   **Client's Role:** Your client application will typically:
    1.  **Connect** to one or more MCP servers.
    2.  **Discover** the tools those servers offer.
    3.  Based on user intent or internal AI logic, **select** an appropriate tool.
    4.  **Construct** the necessary arguments for the selected tool.
    5.  **Call** the tool on the server.
    6.  **Process** the structured results returned by the server.
*   **Human-in-the-Loop:** While AI-controlled, MCP strongly emphasizes human oversight for tool execution. Your client application **should** provide user interfaces for displaying tool descriptions, requesting approval before potentially destructive actions, managing permissions, and logging all tool activities to build trust and ensure safety.

## 2. Client-Side Tool Implementation Details

Implementing an MCP client involves setting up the SDK, connecting to a server, discovering its capabilities, and then interacting with those capabilities (in our case, tools).

### 2.1 Initializing the MCP Client

To begin, you create an instance of the `Client` class. You must provide `clientInfo` to identify your application and can optionally configure `ClientOptions` to declare your client's capabilities.

**Key Classes:**
*   `io.modelcontextprotocol.kotlin.sdk.client.Client`
*   `io.modelcontextprotocol.kotlin.sdk.Implementation`
*   `io.modelcontextprotocol.kotlin.sdk.client.ClientOptions` (often with default `ClientCapabilities` if no special client-exposed features are used).

```kotlin
// From: kotlin-sdk-client/src/commonMain/kotlin/io/modelcontextprotocol/kotlin/sdk/client/Client.kt
public open class Client(private val clientInfo: Implementation, options: ClientOptions = ClientOptions())

// From: kotlin-sdk-core/src/commonMain/kotlin/io/modelcontextprotocol/kotlin/sdk/types.kt
public data class Implementation(val name: String, val version: String)
```

**Example:**

```kotlin
// file: client/src/main/kotlin/eu/torvian/mcp/helloworld/client/HelloWorldClient.kt
import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.client.Client

val mcpClient = Client(clientInfo = Implementation(name = "my-ai-app-client", version = "1.0.0"))
```

### 2.2 Connecting to an MCP Server

The `Client` needs a `Transport` to communicate with the MCP server. The `connect()` method establishes this connection and performs the mandatory MCP initialization handshake, where client and server exchange their capabilities and agree on a protocol version.

**Key Method:** `Client.connect(transport: Transport)`

```kotlin
// From: kotlin-sdk-client/src/commonMain/kotlin/io/modelcontextprotocol/kotlin/sdk/client/Client.kt
override suspend fun connect(transport: Transport)
```

**Example (using `StdioClientTransport`):**

```kotlin
// file: client/src/main/kotlin/eu/torvian/mcp/helloworld/client/HelloWorldClient.kt
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered

// Assume 'process' is a running subprocess of the MCP server
val transport = StdioClientTransport(
    input = process.inputStream.asSource().buffered(),  // Client reads server's stdout
    output = process.outputStream.asSink().buffered(), // Client writes to server's stdin
)
mcpClient.connect(transport)
println("Client: Successfully connected to MCP server.")

// After connection, you can access server info:
val serverCapabilities = mcpClient.serverCapabilities // Capabilities server supports
val serverInfo = mcpClient.serverVersion           // Server's name and version
val serverInstructions = mcpClient.serverInstructions // Optional server instructions
```
Upon successful connection, the client will have populated `serverCapabilities`, `serverVersion`, and `serverInstructions` fields, which provide crucial information about the connected server's offerings.

### 2.3 Discovering Available Tools

Once connected and initialized, your client can query the server for the list of tools it provides.

**Key Method:** `Client.listTools()`

```kotlin
// From: kotlin-sdk-client/src/commonMain/kotlin/io/modelcontextprotocol/kotlin/sdk/client/Client.kt
public suspend fun listTools(request: ListToolsRequest = ListToolsRequest(), options: RequestOptions? = null): ListToolsResult

// From: kotlin-sdk-core/src/commonMain/kotlin/io/modelcontextprotocol/kotlin/sdk/types.kt
public class ListToolsResult(
    public val tools: List<Tool>, // A list of Tool objects
    override val nextCursor: Cursor? = null,
    override val _meta: JsonObject = EmptyJsonObject,
)

// From: kotlin-sdk-core/src/commonMain/kotlin/io/modelcontextprotocol/kotlin/sdk/types.kt
public data class Tool(
    val name: String,         // Unique identifier
    val title: String?,       // Human-readable title
    val description: String?, // Detailed explanation
    val inputSchema: Input,   // JSON Schema for arguments
    val outputSchema: Output?,// JSON Schema for results
    val annotations: ToolAnnotations?, // Hints about behavior
)
```

**Example:**

```kotlin
// file: client/src/main/kotlin/eu/torvian/mcp/helloworld/client/HelloWorldClient.kt
import io.modelcontextprotocol.kotlin.sdk.Tool

lateinit var availableTools: Map<String, Tool> // Store discovered tools

// ... after mcpClient.connect(transport) ...

val toolsResult = mcpClient.listTools()
availableTools = toolsResult.tools.associateBy { it.name } // Map for easy lookup
println("Client: Discovered tools from server: ${availableTools.keys.joinToString(", ")}")
```
The `Tool` object provides all the necessary metadata for your AI to understand the tool's purpose (`description`), what arguments it expects (`inputSchema`), and potentially what output format to expect (`outputSchema`).

### 2.4 Calling a Tool

To invoke a tool, you use the `Client.callTool()` method, providing the tool's `name` and a `Map` of arguments. The SDK handles the serialization of these arguments into the required JSON format.

**Key Methods:** `Client.callTool()`

```kotlin
// From: kotlin-sdk-client/src/commonMain/kotlin/io/modelcontextprotocol/kotlin/sdk/client/Client.kt
public suspend fun callTool(
    name: String,
    arguments: Map<String, Any?>, // Kotlin Map, SDK handles JSON serialization
    meta: Map<String, Any?> = emptyMap(), // Optional metadata for the request
    compatibility: Boolean = false,       // For older protocol versions
    options: RequestOptions? = null,
): CallToolResultBase?

// From: kotlin-sdk-client/src/commonMain/kotlin/io/modelcontextprotocol/kotlin/sdk/client/Client.kt
// Overload for when you construct CallToolRequest directly
public suspend fun callTool(
    request: CallToolRequest,
    compatibility: Boolean = false,
    options: RequestOptions? = null,
): CallToolResultBase?
```

The `arguments` map should correspond to the `inputSchema` of the `Tool` definition. The keys of the map should match the property names in the schema.

**Example:**

```kotlin
// file: client/src/main/kotlin/eu/torvian/mcp/helloworld/client/HelloWorldClient.kt
// Assume toolName is "greet" and nameArg is "Alice"
val arguments = mapOf("name" to nameArg) // Prepare arguments as a Kotlin Map

println("Client: Calling tool '$toolName' with arguments: $arguments")

val result = mcpClient.callTool(name = toolName, arguments = arguments)
```

### 2.5 Processing Tool Results

The `callTool` method returns a `CallToolResultBase` object (typically a `CallToolResult`). This object contains the output of the tool execution, which can be a list of various content types.

**Key Classes:**
*   `io.modelcontextprotocol.kotlin.sdk.CallToolResultBase`
*   `io.modelcontextprotocol.kotlin.sdk.CallToolResult`
*   `io.modelcontextprotocol.kotlin.sdk.PromptMessageContent` (sealed interface) and its implementations:
    *   `io.modelcontextprotocol.kotlin.sdk.TextContent`
    *   `io.modelcontextprotocol.kotlin.sdk.ImageContent`
    *   `io.modelcontextprotocol.kotlin.sdk.AudioContent`
    *   `io.modelcontextprotocol.kotlin.sdk.EmbeddedResource`

```kotlin
// From: kotlin-sdk-core/src/commonMain/kotlin/io/modelcontextprotocol/kotlin/sdk/types.kt
public sealed interface CallToolResultBase : ServerResult {
    public val content: List<PromptMessageContent>
    public val structuredContent: JsonObject?
    public val isError: Boolean? get() = false
}

public class CallToolResult(
    override val content: List<PromptMessageContent>, // List of content (e.g., TextContent)
    override val structuredContent: JsonObject? = null,
    override val isError: Boolean? = false, // True if tool execution failed
    override val _meta: JsonObject = EmptyJsonObject,
) : CallToolResultBase
```

**Example (processing `TextContent`):**

```kotlin
// file: client/src/main/kotlin/eu/torvian/mcp/helloworld/client/HelloWorldClient.kt
import io.modelcontextprotocol.kotlin.sdk.TextContent

// ... after receiving 'result' from mcpClient.callTool ...

val resultText = result?.content
    ?.filterIsInstance<TextContent>() // Filter for TextContent objects
    ?.joinToString("\n") { it.text.toString() } // Extract the actual text

println("Server Response: $resultText")

// Handle potential errors
if (result?.isError == true) {
    println("Tool execution reported an error!")
    // You might parse structuredContent for more details
}
```

### 2.6 Client Transports

The SDK provides `StdioClientTransport` for client-server communication via standard input/output streams, which is common for local subprocess interactions. For remote or HTTP-based communication, you would integrate a Ktor client with the MCP `Protocol` or `Client` class.

**Key Class:** `io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport`

```kotlin
// From: kotlin-sdk-client/src/commonMain/kotlin/io/modelcontextprotocol/kotlin/sdk/client/StdioClientTransport.kt
public class StdioClientTransport(private val input: Source, private val output: Sink)
```

## 3. Core SDK Classes and Interfaces for Client-Side Tool Interaction

This section details the primary Kotlin SDK components directly involved in building an MCP client that interacts with tools.

### 3.1 `io.modelcontextprotocol.kotlin.sdk.client.Client`

The central class for client-side MCP operations.

*   **`Client(clientInfo: Implementation, options: ClientOptions)`**: Constructor to initialize the client.
*   **`suspend connect(transport: Transport)`**: Establishes connection, performs handshake, populates `serverCapabilities`, `serverVersion`, `serverInstructions`.
*   **`suspend listTools(request: ListToolsRequest): ListToolsResult`**: Discovers tools offered by the server.
*   **`suspend callTool(name: String, arguments: Map<String, Any?>, ...): CallToolResultBase?`**: Invokes a tool on the server.
*   **`val serverCapabilities: ServerCapabilities?`**: Capabilities declared by the connected server.
*   **`val serverVersion: Implementation?`**: Information about the server's implementation.
*   **`val serverInstructions: String?`**: Optional instructions from the server.
*   **`suspend close()`**: Terminates the client connection gracefully (important for resource cleanup).

### 3.2 `io.modelcontextprotocol.kotlin.sdk.ClientOptions` & `io.modelcontextprotocol.kotlin.sdk.ClientCapabilities`

Used to configure the `Client` instance.

*   **`ClientOptions(capabilities: ClientCapabilities = ClientCapabilities(), enforceStrictCapabilities: Boolean = true)`**: Configuration options for the client. For basic tool interaction, default `ClientCapabilities` are often sufficient as tools are server-exposed features.
*   **`ClientCapabilities(...)`**: Declares what features the *client itself* supports (e.g., `sampling`, `roots`, `elicitation`). These are mainly relevant if the *server* were to request capabilities from the *client* (which is not our current focus).

### 3.3 `io.modelcontextprotocol.kotlin.sdk.Tool`

A data class representing the definition of a tool, as received from the server during discovery.

*   **`name: String`**: Unique identifier.
*   **`title: String?`**: Human-readable display name.
*   **`description: String?`**: Detailed explanation for AI and human understanding.
*   **`inputSchema: Tool.Input`**: JSON Schema fragment defining expected input parameters.
    *   `properties: JsonObject`: Map of parameter names to their JSON Schema definitions.
    *   `required: List<String>?`: List of mandatory parameter names.
*   **`outputSchema: Tool.Output?`**: JSON Schema fragment defining expected output structure.
*   **`annotations: ToolAnnotations?`**: Provides hints about tool behavior (e.g., `readOnlyHint` for safety).

### 3.4 `io.modelcontextprotocol.kotlin.sdk.CallToolRequest`

The data class used internally by `Client.callTool()` to construct the JSON-RPC request sent to the server.

*   **`name: String`**: The tool's unique name.
*   **`arguments: JsonObject`**: JSON representation of the tool's input arguments.
*   **`_meta: JsonObject`**: Optional client-provided metadata.

### 3.5 `io.modelcontextprotocol.kotlin.sdk.CallToolResultBase` & `CallToolResult`

The base interface and primary implementation for the structured response received from the server after a tool invocation.

*   **`content: List<PromptMessageContent>`**: The main payload, containing rich content (text, images, etc.).
*   **`structuredContent: JsonObject?`**: Optional, tool-specific structured JSON output.
*   **`isError: Boolean?`**: Indicates if the tool execution on the server resulted in an error (`true` means error).
*   **`_meta: JsonObject`**: Optional server-provided metadata.

### 3.6 `io.modelcontextprotocol.kotlin.sdk.ListToolsRequest` & `ListToolsResult`

*   **`ListToolsRequest()`**: The request sent by the client to get the list of tools. Usually an empty object.
*   **`ListToolsResult(tools: List<Tool>, nextCursor: Cursor?)`**: The response containing the list of discovered `Tool` objects.

### 3.7 Content Types (`PromptMessageContent`)

A sealed interface representing various types of content that can be returned in a `CallToolResult`.

*   **`TextContent(text: String?, annotations: Annotations?)`**: For plain text results.
*   **`ImageContent(data: String, mimeType: String, annotations: Annotations?)`**: For image results (data is base64-encoded).
*   **`AudioContent(data: String, mimeType: String, annotations: Annotations?)`**: For audio results (data is base64-encoded).
*   **`EmbeddedResource(resource: ResourceContents, annotations: Annotations?)`**: For embedding the content of another resource.
*   `UnknownContent(type: String)`: A fallback for unrecognized content types.

### 3.8 `io.modelcontextprotocol.kotlin.sdk.Implementation`

Used by both client and server to identify themselves.

*   **`name: String`**: Name of the implementation (e.g., "my-ai-agent").
*   **`version: String`**: Version of the implementation (e.g., "1.0.0").

### 3.9 `io.modelcontextprotocol.kotlin.sdk.ServerCapabilities`

Received from the server during initialization, this object tells the client what features the server supports.

*   **`tools: ServerCapabilities.Tools?`**: If this field is non-null, the server supports tool-related operations.
    *   **`listChanged: Boolean?`**: Indicates if the server can send notifications when its list of tools changes.

### 3.10 Transport Classes

These classes implement the `io.modelcontextprotocol.kotlin.sdk.shared.Transport` interface.

*   **`io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport`**: Enables client-side communication over standard input and output streams. Ideal for connecting to a local subprocess MCP server.

## 4. Example Walkthrough: "Hello World" Client

Let's examine the provided `client/src/main/kotlin/eu/torvian/mcp/helloworld/client/Main.kt` and `HelloWorldClient.kt` to illustrate the client-side tool interaction flow.

### 4.1 Client Application Entry Point (`Main.kt`)

This `main` function simply sets up the client and orchestrates its connection to a server (simulated here by launching a JAR) and starts the interactive loop.

```kotlin
// file: client/src/main/kotlin/eu/torvian/mcp/helloworld/client/Main.kt
package eu.torvian.mcp.helloworld.client

import kotlinx.coroutines.runBlocking

/**
 * The main entry point for the MCP Hello World Client application.
 *
 * This function handles command-line arguments, initializes the [HelloWorldClient],
 * connects it to an MCP Server, and starts the interactive tool-calling loop.
 */
fun main(args: Array<String>) = runBlocking {
    // Check if the server JAR path argument is provided.
    if (args.isEmpty()) {
        println("Usage: java -jar <client.jar> <path_to_server.jar>")
        return@runBlocking // Exit if no argument.
    }

    val serverPath = args.first() // Get the path to the server JAR from the arguments.
    val client = HelloWorldClient() // Create an instance of our MCP client.

    // Use a `use` block to ensure `close()` is called automatically when the client is no longer needed,
    // even if exceptions occur. This handles resource cleanup for the AutoCloseable client.
    client.use {
        client.connectToServer(serverPath) // Establish connection to the server.
        client.interactiveToolLoop()       // Start the interactive loop for calling tools.
    }
}
```

### 4.2 The `HelloWorldClient` Class

This class demonstrates the core client-side logic: initialization, connection, tool discovery, and tool invocation.

```kotlin
// file: client/src/main/kotlin/eu/torvian/mcp/helloworld/client/HelloWorldClient.kt
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

/**
 * This class represents a minimal "Hello World" example of an MCP (Model Context Protocol) Client in Kotlin.
 *
 * This client connects to a local server, discovers its "greet" tool, and allows interactive execution of it.
 */
class HelloWorldClient : AutoCloseable {
    // 1. Initialize the core MCP Client instance with its identification.
    private val mcp: Client = Client(clientInfo = Implementation(name = "hello-world-client", version = "1.0.0"))

    // A local cache to store the tools discovered from the connected MCP Server.
    private lateinit var availableTools: Map<String, Tool>

    /**
     * Connects this MCP Client to an MCP Server.
     * Launches the server as a subprocess and uses STDIO for communication.
     * After connection, it discovers the tools offered by the server.
     *
     * @param serverScriptPath The file path to the server's executable JAR file.
     */
    suspend fun connectToServer(serverScriptPath: String) {
        try {
            // Command to launch the server JAR.
            val command = listOf("java", "-jar", serverScriptPath)

            // Start the server application as a new subprocess.
            println("Client: Starting server process: ${command.joinToString(" ")}")
            val process = ProcessBuilder(command).start()

            // Setup STDIO transport using the subprocess's streams.
            val transport = StdioClientTransport(
                input = process.inputStream.asSource().buffered(),  // Client reads server's stdout.
                output = process.outputStream.asSink().buffered(), // Client writes to server's stdin.
            )

            // Connect the MCP client instance to the configured transport.
            // This initiates the MCP handshake and establishes the communication session.
            mcp.connect(transport)
            println("Client: Successfully connected to MCP server.")

            // Request the list of available tools from the connected server.
            val toolsResult = mcp.listTools()
            availableTools = toolsResult.tools.associateBy { it.name } // Store them in our map.

            println("Client: Discovered tools from server: ${availableTools.keys.joinToString(", ")}")
        } catch (e: Exception) {
            println("Client: Failed to connect to MCP server: $e")
            throw e
        }
    }

    /**
     * Runs an interactive loop, allowing the user to input tool names and arguments
     * to call tools on the connected MCP Server.
     */
    suspend fun interactiveToolLoop() {
        println("\n--- Interactive Tool Caller ---")
        println("Type a tool name to call it (e.g., 'greet'), or 'quit' to exit.")

        while (true) {
            print("\n> Enter tool name: ")
            val toolName = readlnOrNull()?.trim() ?: break
            if (toolName.equals("quit", ignoreCase = true)) break

            val tool = availableTools[toolName]
            if (tool == null) {
                println("Unknown tool '$toolName'. Available tools are: ${availableTools.keys.joinToString(", ")}")
                continue
            }

            // For this example, we only handle the "greet" tool specifically.
            if (toolName == "greet") {
                print("> Enter value for 'name' (string): ")
                val nameArg = readlnOrNull() ?: ""
                val arguments = mapOf("name" to nameArg) // Prepare arguments for the tool call.

                println("Client: Calling tool '$toolName' with arguments: $arguments")
                // Execute the tool on the server.
                val result = mcp.callTool(name = toolName, arguments = arguments)

                // Process and print the text content from the tool's response.
                val resultText = result?.content
                    ?.filterIsInstance<TextContent>() // Filter for text content objects.
                    ?.joinToString("\n") { it.text.toString() } // Extract the text.

                println("Server Response: $resultText")
            } else {
                println("This client only knows how to call the 'greet' tool.")
            }
        }
    }

    /**
     * Closes the MCP client connection and any associated resources, including the server subprocess.
     */
    override fun close() {
        runBlocking {
            mcp.close() // Close the MCP connection.
            println("Client: Connection closed.")
            // The subprocess launched by ProcessBuilder will typically terminate
            // when its standard input/output streams are closed by the parent process.
        }
    }
}
```

**Summary of Client-Side Tool Flow in the Example:**
1.  **Client Initialization:** An `Client` instance (`mcp`) is created with basic `Implementation` details.
2.  **Connection to Server:** `connectToServer()` launches a separate process for the MCP server and sets up a `StdioClientTransport`. `mcp.connect(transport)` then initiates the MCP handshake.
3.  **Tool Discovery:** Immediately after connection, `mcp.listTools()` is called to fetch all tools from the server. The `Tool` objects received are stored in `availableTools` for later use.
4.  **Interactive Loop:** `interactiveToolLoop()` prompts the user for a tool name and arguments.
5.  **Tool Invocation:** When the user enters "greet," a `Map<String, Any?>` is created for the arguments, and `mcp.callTool()` is used to send the request to the server.
6.  **Result Processing:** The `CallToolResult` is received. The client filters its `content` list for `TextContent` and prints the extracted greeting to the console.
7.  **Resource Cleanup:** The `close()` method (part of `AutoCloseable`) is called to ensure the MCP connection is gracefully terminated, which also helps shut down the subprocess.

## 5. Client-Side Best Practices and Considerations for Tools

When building an MCP client, especially one that uses tools, keep the following best practices in mind:

### 5.1 User Consent and Control

*   **Explicit Approval:** For any tool that modifies external systems, involves sensitive data, or has significant real-world impact, **always require explicit user approval** before invoking it. Display the tool's `description` and the arguments to the user in a clear, understandable manner.
*   **Permissions:** Implement granular permission settings that allow users to pre-approve specific tools or categories of tools, or to revoke access at any time.
*   **Activity Logs:** Maintain a clear, human-readable log of all tool discovery and invocation events. This builds trust and provides an audit trail.
*   **Sensitive Data:** Never pass credentials, API keys, or highly sensitive personal data directly as tool arguments without extreme caution and robust security measures. Prefer that the *server* handles its own authentication and secrets securely.

### 5.2 Tool Selection and Argument Construction

*   **AI Orchestration:** The AI model (your client's core intelligence) is responsible for analyzing user requests, understanding context, and deciding which tool (if any) to call.
*   **Schema Adherence:** When constructing arguments for `callTool()`, ensure they strictly adhere to the `inputSchema` provided in the `Tool` definition. Mismatched types, missing required fields, or extra unexpected fields can lead to server-side errors.
*   **User Input Validation:** If tool arguments come from user input, validate them against the `inputSchema` on the client side before sending them to the server to provide immediate feedback and prevent unnecessary requests.

### 5.3 Processing Untrusted Tool Descriptions and Results

*   **"Hints," Not Guarantees:** Tool `description` and `annotations` (like `readOnlyHint` or `destructiveHint`) from an MCP server should be treated as **hints**, not absolute guarantees, especially if the server is not fully trusted. Your client's security policies should prioritize user safety regardless of these hints.
*   **Sanitize Output:** Any `TextContent` or other content received from `CallToolResult` should be sanitized before being displayed to the user or passed to another system to prevent XSS attacks or other vulnerabilities, especially if the content might contain HTML or scripts.
*   **Structured Output Validation:** If `outputSchema` is provided in the `Tool` definition, validate `structuredContent` received against this schema to ensure consistency and prevent unexpected data formats.

### 5.4 Error Handling and Recovery

*   **`isError` Flag:** Always check the `isError` flag in the `CallToolResult`. If `true`, display an informative error message to the user and consider if a fallback action is appropriate.
*   **Connection Errors:** Implement robust error handling for `connect()` and `callTool()` that can catch network issues, timeouts, or protocol violations. Inform the user and guide them on how to resolve the issue (e.g., "Server unreachable, please check your network connection").
*   **Graceful Shutdown:** Ensure your client application properly calls `mcpClient.close()` to release resources and cleanly disconnect from the server. This is vital for managing long-running connections and subprocesses.

### 5.5 Observability (Logging)

*   **Detailed Logging:** Integrate comprehensive logging in your client application. Log:
    *   Client initialization and connection status.
    *   All discovered tools and their key properties (`name`, `description`).
    *   Every tool invocation: the tool's name, arguments sent, and the full `CallToolResult` received.
    *   Any errors encountered during connection, tool discovery, or invocation.
*   **Structured Logs:** Consider structured logging (e.g., JSON logs) for easier parsing and analysis in monitoring systems.

By adhering to these guidelines and utilizing the provided Kotlin MCP SDK, your team can build capable, secure, and user-friendly AI client applications that leverage the power of external tools.