# Torvian Chatbot

## Project Purpose
The project is a multi-platform chatbot application with AI/LLM integration. It features a central server and multiple client options (Desktop, Web, Android). It supports various LLM providers (OpenAI, Ollama) and has a plugin system for MCP tools.

- **Server Module**: Ktor-based backend with SQLite database, user authentication, and LLM provider integration
- **Desktop Client**: Compose Multiplatform desktop application (Linux, Windows, macOS)
- **Web Client**: WASM-based web application (planned)
- **Android Client**: Android application (planned)
- **Common Module**: Shared business logic and models

## Tech Stack
- **Languages**: Kotlin 2.3.10
- **UI Framework**: Compose Multiplatform 1.10.2 (with Material 3 1.9.0)
- **Server**: Ktor 3.4.1
- **Database**: SQLite with Exposed ORM 1.1.1 (Server) and SQLDelight 2.2.1 (App)
- **Dependency Injection**: Koin 4.1.1
- **Functional Programming**: Arrow 2.2.2
- **Logic**: kotlinx.serialization
- **Build Tool**: Gradle 9.4.0

## Features
- User authentication and session management
- Chat sessions with message threading
- LLM provider configuration and model selection. Allows using your own API keys from any OpenAI compatible provider (e.g. OpenAI, Gemini, OpenRouter). Also supports using Ollama for local models.
- Tool execution with local (stdio) MCP server integration. Remote (http) MCP server integration is planned.
- Agentic LLM responses with tool calling. All tool calls need to be user approved before execution. Automatic approval can be configured on a per-tool basis. Allows streaming multiple LLM responses in parallel (from different chat sessions).
- File attachment and reference in messages
- (WIP) Multi-platform support (Desktop, Web, Android)

## Project Status
The project is in active development. The server and desktop client are feature complete in terms of their core functionality. The desktop client is currently the most stable and useable version available. The web client is partially useable, though MCP server integration is not yet functional. The Android client is the least useable, primarily due to its layout being optimized for landscape mode (not portrait), and it includes elements that require mouse hover to be visible, making them inaccessible on touchscreens.

## Getting Started

### Use pre-built packages
Pre-built packages are available from the [Releases page](https://github.com/Torvian-eu/chatbot/releases):
- **Server**: Available for all platforms. Use Docker (recommended) or JDK 21+ to run it locally.
- **Desktop Client**: Available for Windows and Linux.
- **Web Client**: Available as a static web app. It must be served over HTTP(S).

### Run the server
```bash
# Linux/Mac
<install-path>/start-server.sh
# Windows
<install-path>/start-server.bat
```

**Docker quick start:**

```bash
docker run -d \
  --name chatbot-server \
  -p 8080:8080 \
  -v chatbot-config:/app/config \
  -v chatbot-data:/app/data \
  -v chatbot-logs:/app/logs \
  -e SERVER_HOST=0.0.0.0 \
  -e SERVER_CONNECTOR_TYPE=HTTP \
  --restart unless-stopped \
  ghcr.io/torvian-eu/chatbot-server:latest
```

For full deployment options and configuration details (including Docker Compose + Caddy), see [deploy/README.md](deploy/README.md).

### Run the desktop application
```bash
# Windows
<install-path>/Chatbot-with-logs.bat
# Linux/Mac (if building from source, see below)
<install-path>/Chatbot-with-logs.sh
```

### Serve the web client
The web client is a static web application and must be served over HTTP(S). Opening `index.html` directly via `file://` will not work, because browsers block loading WASM and related assets from local files.

For local testing, you can use any simple static file server.

**Example using Python:**
```bash
cd <web-client-dist-path>
python -m http.server 4000
```

Then open your browser and navigate to:
```text
http://localhost:4000
```

For production or VPS deployments, serve the same files using a regular web server such as Caddy or nginx.

### Login
Login with username `admin` and password `admin123`. You will be asked to change the password on first login.

## Build from Source

### Prerequisites
- [Git](https://git-scm.com/install/)
- [JDK 21](https://adoptium.net/installation) or higher
- Gradle 9.x (included via wrapper)

### Clone the repository
```bash
cd <parent-path>
git clone https://github.com/Torvian-eu/chatbot.git
```

### Build & Install Server application
```bash
./gradlew server:installDist
```
The files will be installed to `server/build/install/server/`. You can run the server using the scripts in that folder.

### Build & Install Desktop application
```bash
./gradlew app:createDistributable
```
The files will be installed to `app/build/compose/binaries/main/app/Chatbot`. You can run the desktop client using the scripts in that folder.

### Build & Install Web application
```bash
./gradlew app:wasmJsBrowserDistribution
```
The files will be installed to `app/build/dist/wasmJs/productionExecutable`. You can serve the web client using any static file server, as described in the "Serve the web client" section above.

## Guides
These guides provide information on how to configure and use specific features of the chatbot.
- [LLM configuration guide](docs/user%20guides/LLM%20configuration%20guide.md) - How to configure LLM providers, models and model settings. And how to use them in the chatbot.
- [MCP server configuration guide](docs/user%20guides/MCP%20server%20configuration%20guide.md) - How to configure and use MCP servers.

## Deployment
For information on deploying the chatbot application to a VPS or server environment, please refer to the [Deployment Guide](deploy/README.md). This includes:
- Quick Docker server startup
- Docker setup with Caddy reverse proxy
- VPS deployment with Docker Compose
- Configuration via environment variables

Additional deployment-related documentation:
- [VPS Deployment Guide](docs/VPS/Docker%20VPS%20Deployment%20Guide.md) - Comprehensive guide for VPS deployment
- [VPS Basics Guide](docs/VPS/Ubuntu%20VPS%20Basics%20Guide%20for%20Self-Hosted%20Docker%20Apps.md) - Linux basics for VPS management
- [Docker Installation Guide](docs/VPS/Docker%20Installation%20guide.md) - How to install Docker on a VPS

## Project Structure
```
chatbot/
├── app/                    # Desktop client module
├── server/                 # Server module
├── common/                 # Shared code
├── build-logic/            # Gradle convention plugins
├── docs/                   # Documentation
└── gradle/                 # Gradle wrapper and dependencies
```

## Additional Documentation
- [Project Structure](docs/Project%20and%20Package%20Structure.md)
- [Known Issues](docs/Known%20bugs.md)
- [TODO List](docs/Todos.md)
- [New feature ideas](docs/New%20feature%20ideas.md)

## License
This project is licensed under the [MIT License](LICENSE)

## Contributing
We welcome community contributions to the Torvian Chatbot! Your feedback, bug reports, feature suggestions, and code contributions are highly valued.

Please see our comprehensive [Contributing Guide](CONTRIBUTING.md) for detailed information on how to get involved.

## Support
For support or general questions, please post a message in the [GitHub discussion forum](https://github.com/Torvian-eu/chatbot/discussions).

## Screenshots
- Desktop app GUI: ![](https://i.imgur.com/aaFyKLk.png)
- MCP server configuration: ![](https://i.imgur.com/c4Oskp0.png)

