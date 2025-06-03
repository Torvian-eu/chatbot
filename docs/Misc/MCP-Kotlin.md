## Model Context Protocol (MCP)

The Model Context Protocol (MCP) is an open protocol designed to standardize how LLM applications interact with external data sources and tools. It provides a structured way for AI systems to access contextual information, utilize external tools, and build complex, composable workflows.

At its core, MCP operates on a client-server architecture using **JSON-RPC 2.0** messages:
*   **Hosts**: LLM applications (e.g., IDEs, chat interfaces) that initiate connections.
*   **Clients**: Connectors within the host application that manage communication.
*   **Servers**: Services that provide specific context and capabilities to the LLM.

MCP servers can offer features such as:
*   **Resources**: Contextual data for the user or AI model.
*   **Prompts**: Templated messages and workflows.
*   **Tools**: Functions the AI model can execute.

The protocol also supports **Sampling** (server-initiated agentic behaviors), configuration, progress tracking, cancellation, error reporting, and logging.

### **MCP Kotlin SDK: Focus on Kotlin Development**

The **MCP Kotlin SDK** is the official Kotlin implementation, providing full support for both client and server capabilities. It simplifies the integration of LLMs by handling the protocol specifics, allowing developers to focus on providing or consuming context.

**Key Capabilities of the Kotlin SDK:**
*   **Build MCP Clients**: Connect to any MCP server to list resources, read data, invoke tools, etc.
*   **Create MCP Servers**: Expose resources, prompts, and tools from your Kotlin application.
*   **Standard Transports**: Supports `stdio`, `SSE` (Server-Sent Events), and `WebSocket` for communication.
*   **Protocol Handling**: Manages all MCP protocol messages and lifecycle events automatically.

**Installation:**

Add the Maven Central repository and the `kotlin-sdk` dependency to your build file (e.g., `build.gradle.kts`):

```kotlin
// build.gradle.kts
repositories {
    mavenCentral()
}

dependencies {
    implementation("io.modelcontextprotocol:kotlin-sdk:0.5.0")
}
```

**Quick Start Examples:**

#### **Creating an MCP Client in Kotlin**

This example demonstrates how to create a client that connects to an MCP server via standard I/O (STDIO) and interacts with resources.

```kotlin
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.ReadResourceRequest

// Assume 'processInputStream' and 'processOutputStream' are connected to the server process's stdio
// Example: If running a server as a separate process:
// val processBuilder = ProcessBuilder("java", "-jar", "your-mcp-server.jar")
// val process = processBuilder.start()
// val processInputStream = process.inputStream // server's output
// val processOutputStream = process.outputStream // server's input

val client = Client(
    clientInfo = Implementation(
        name = "example-client",
        version = "1.0.0"
    )
)

val transport = StdioClientTransport(
    inputStream = processInputStream, // Input stream from the server
    outputStream = processOutputStream // Output stream to the server
)

// Connect to server
client.connect(transport)

// List available resources
val resources = client.listResources()
println("Available resources: ${resources.resources.map { it.uri }}")

// Read a specific resource
val resourceContent = client.readResource(
    ReadResourceRequest(uri = "file:///example.txt") // Assuming the server provides this resource
)
resourceContent.contents.forEach { content ->
    if (content is TextResourceContents) {
        println("Content of file:///example.txt:\n${content.text}")
    }
}

// Don't forget to close resources if managing process manually
// process.destroy()
```

#### **Creating an MCP Server in Kotlin**

This example shows how to set up an MCP server that exposes a simple text resource via STDIO.

```kotlin
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.TextResourceContents
import io.modelcontextprotocol.kotlin.sdk.Implementation

val server = Server(
    serverInfo = Implementation(
        name = "example-server",
        version = "1.0.0"
    ),
    options = ServerOptions(
        capabilities = ServerCapabilities(
            resources = ServerCapabilities.Resources(
                subscribe = true, // Server can notify clients of resource changes
                listChanged = true // Server supports listing changed resources
            )
        )
    )
)

// Add a resource handler to the server
server.addResource(
    uri = "file:///example.txt",
    name = "Example Resource",
    description = "An example text file",
    mimeType = "text/plain"
) { request ->
    // This lambda is called when a client requests to read this resource
    ReadResourceResult(
        contents = listOf(
            TextResourceContents(
                text = "This is the content of the example resource. Hello from Kotlin!",
                uri = request.uri,
                mimeType = "text/plain"
            )
        )
    )
}

// Start server with stdio transport
// The server will now listen for messages on System.in and send responses to System.out
val transport = StdioServerTransport()
server.connect(transport) // This is a blocking call, server runs until disconnected/process ends
```

#### **Using SSE Transport with Ktor**

The Kotlin SDK provides convenient integration with the Ktor framework for web-based transports like SSE.

```kotlin
import io.ktor.server.application.*
import io.ktor.server.sse.SSE // Required for SSE support in Ktor
import io.ktor.server.routing.* // Required for routing
import io.modelcontextprotocol.kotlin.sdk.server.mcp // Extension function for Ktor
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.Implementation

fun Application.module() {
    // Install SSE feature if using SSE transport
    install(SSE)

    // Option 1: Directly within the Application's main module function
    // This will set up an MCP endpoint at the root path "/" if no routing specified.
    mcp {
        Server(
            serverInfo = Implementation(
                name = "example-sse-server-root",
                version = "1.0.0"
            ),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    // Configure capabilities as needed for your server
                    prompts = ServerCapabilities.Prompts(listChanged = null),
                    resources = ServerCapabilities.Resources(subscribe = null, listChanged = null)
                )
            )
        )
    }

    // Option 2: Within a custom Ktor Route
    routing {
        route("myRoute") { // This will create an MCP endpoint at "/myRoute"
            mcp {
                Server(
                    serverInfo = Implementation(
                        name = "example-sse-server-myroute",
                        version = "1.0.0"
                    ),
                    options = ServerOptions(
                        capabilities = ServerCapabilities(
                            prompts = ServerCapabilities.Prompts(listChanged = null),
                            resources = ServerCapabilities.Resources(subscribe = null, listChanged = null)
                        )
                    )
                )
            }
        }
    }
    // You can also add resource handlers to these Ktor-based servers just like the STDIO example.
    // For instance, within the mcp { ... } block:
    // server.addResource(...)
}
```

The Kotlin SDK significantly streamlines the process of building robust, context-aware LLM applications and services, enabling seamless integration with the broader MCP ecosystem. Sample projects are available (e.g., `kotlin-mcp-server`, `weather-stdio-server`, `kotlin-mcp-client`) to provide further practical guidance.