## Project Information
- The project uses JetBrains Exposed version 0.58 (latest stable) and Koin version 4.0.2 (latest stable).
- The gradle library versions are defined in `gradle/libs.versions.toml`. Each gradle module (app, common, server) can pick the desired dependencies in its own `build.gradle.kts` file.

## Guidelines
- Use kotlinx.datetime instead of java.time.

## Tips
- JUnit 5 parallel testing can make console output hard to follow. For failed tests, run them individually to view output in order.