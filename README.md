# Chatbot Application

A multi-platform chatbot application with AI/LLM integration, featuring a central server and multiple client options (Desktop, Web).

## Project Overview

- **Server Module**: Ktor-based backend with SQLite database, user authentication, and LLM provider integration
- **Desktop Client**: Compose Multiplatform desktop application (Windows, macOS, Linux)
- **Web Client**: WASM-based web application (planned)
- **Common Module**: Shared business logic and models

## Tech Stack

- **Language**: Kotlin 2.2.0
- **UI Framework**: Compose Multiplatform 1.8.2 with Material 3
- **Server Framework**: Ktor 3.2.3 (Server & HTTP Client)
- **Database**: SQLite with Exposed ORM 0.61.0
- **Dependency Injection**: Koin 4.1.0
- **Functional Programming**: Arrow 2.1.2
- **Logging**: Log4j 2.25.1
- **Build Tool**: Gradle

## Quick Start (Development)

### Prerequisites

- JDK 21 or higher
- Gradle 8.x (included via wrapper)

### Build & Run Server

```bash
# Build server module
./gradlew server:assemble

# Run server (development mode with default config)
./gradlew server:run

# Run tests
./gradlew server:test
```

### Build & Run Desktop Client

```bash
# Build desktop application
./gradlew app:desktopMainClasses

# Run desktop application
./gradlew app:runDesktop

# Run tests
./gradlew app:desktopTest
```

## Server Configuration

The server uses a multi-layer JSON configuration system with support for environment variable overrides. C-style comments (`/* */`) are supported in all JSON config files.

### Configuration Files

- `config/application.json` - Main configuration (non-sensitive values)
- `config/secrets.json` - Sensitive credentials, auto-generated on first run (**DO NOT COMMIT**)
- `config/env-mapping.json` - Maps environment variable names to configuration keys
- `config/setup.json` - Setup flow control flag

A `secrets_example.json` is provided in the distribution showing the expected structure with placeholder values.

For development, the server uses configuration from `server/src/main/resources/` which includes standard test credentials.

The config directory can be overridden via the `CHATBOT_SERVER_CONFIG_DIR` environment variable, or the `app.config.dir` system property. By default, it resolves to `./config` relative to the working directory.

### First Run Setup

On first run with `setup.required = true` in `config/setup.json`, the server will:

1. Generate missing secrets (SSL passwords, encryption keys, JWT secret)
2. Write secrets to `config/secrets.json`
3. Update `config/setup.json` to mark setup as complete
4. Continue startup automatically

### Environment Variables

All configuration values can be overridden with environment variables as defined in `config/env-mapping.json`. Key variables:

| Variable | Description | Required |
|----------|-------------|----------|
| `CHATBOT_SERVER_CONFIG_DIR` | Override the config directory path | No |
| `SERVER_HOST` | Server host address (default: localhost) | No |
| `SERVER_PORT` | HTTP port (default: 8080) | No |
| `SERVER_CONNECTOR_TYPE` | HTTP, HTTPS, or HTTP_AND_HTTPS | No |
| `SSL_PORT` | HTTPS port (default: 8443) | No |
| `SSL_KEYSTORE_PASSWORD` | Keystore password | When using HTTPS |
| `SSL_KEY_ALIAS` | Key alias in keystore | When using HTTPS |
| `SSL_KEY_PASSWORD` | Private key password | When using HTTPS |
| `SERVER_DATABASE_FILENAME` | Database filename (default: chatbot.db) | No |
| `ENCRYPTION_MASTER_KEY_V1` | Base64 encryption key | Yes (auto-generated) |
| `JWT_SECRET` | JWT signing secret | Yes (auto-generated) |


## Distribution

### Build Server Distribution

```bash
# Create distribution archive (TAR.GZ)
./gradlew server:distTar

# Create distribution archive (ZIP)
./gradlew server:distZip

# Output: server/build/distributions/chatbot-server-<version>.tar.gz
#         server/build/distributions/chatbot-server-<version>.zip
```

### Distribution Contents

```
chatbot-server-<version>/
├── config/
│   ├── application.json        # Main configuration
│   ├── application_example.json # Annotated configuration template
│   ├── env-mapping.json        # Environment variable mapping
│   ├── setup.json              # Setup control (setup_required = true)
│   └── secrets_example.json    # Secrets template (DO NOT use in production)
├── lib/
│   └── chatbot-server-<version>.jar
├── start-server.sh             # Unix/Linux/Mac startup script
└── start-server.bat            # Windows startup script
```

### Deploy & Run

1. Extract distribution archive
2. (Optional) Copy `config/secrets_example.json` to `config/secrets.json` and customize
3. Edit `config/application.json` for your environment (optional - defaults work for most cases)
4. Run startup script:
   - **Unix/Linux/Mac**: `./start-server.sh`
   - **Windows**: `start-server.bat`

First run with `setup.required = true` (default in `config/setup.json`) will auto-generate secrets.

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

## Development Guidelines

- Use kotlinx.datetime instead of java.time
- Error return types in Arrow should be logical errors, not technical errors
- Follow Material 3 design guidelines for UI components

## Gradle Tasks Reference

```bash
# Server
./gradlew server:assemble        # Build server JAR
./gradlew server:distTar         # Create distribution archive (TAR.GZ)
./gradlew server:distZip         # Create distribution archive (ZIP)
./gradlew server:test            # Run server tests

# Desktop App
./gradlew app:desktopMainClasses # Build desktop app
./gradlew app:desktopTest        # Run desktop tests
./gradlew app:createDistributable  # Create distributable (no installer)

# Don't use these (too slow):
# ./gradlew build
# ./gradlew test
```

## API Documentation

- Base URL: `https://localhost:8443/api/v1` (or configured host/port)
- Authentication: JWT Bearer tokens

### Default Credentials

For development and first-time setup, a default admin user is created:
- **Username**: `admin`
- **Password**: `admin123`

**Important**: Change this password immediately after first login!

## Additional Documentation

- [Project Structure](docs/Project%20and%20Package%20Structure.md)
- [Known Issues](docs/Known%20bugs.md)
- [TODO List](docs/Todos.md)

## License

[TODO: Add license information]

## Contributing

This is an internal project. For questions or issues, contact the development team.

## Support

For support, contact the development team.

