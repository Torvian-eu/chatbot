# Suggested Development Commands

## Prerequisites
- JDK 21 or higher
- Gradle 8.x (wrapper included)

## Server Commands
- **Build**: `./gradlew server:assemble`
- **Run (Development)**: `./gradlew server:run`
- **Test**: `./gradlew server:test`
- **Distribute (TAR.GZ)**: `./gradlew server:distTar`
- **Distribute (ZIP)**: `./gradlew server:distZip`

## Desktop App Commands
- **Build**: `./gradlew app:desktopMainClasses`
- **Run (Desktop)**: `./gradlew app:runDesktop`
- **Test (Desktop)**: `./gradlew app:desktopTest`
- **Create Distributable**: `./gradlew app:createDistributable`

## Global & Utility Commands
- **Lint/Format**: (Check build-logic or scripts; assuming standard `./gradlew check` if configured)
- **Windows System Commands**:
  - `dir`: List directories/files.
  - `findstr`: Search within files (like grep).
  - `type`: Print file content (like cat).
  - `copy`/`move`/`del`: File operations.

## Warnings
- **Build/Test Efficiency**: Avoid `./gradlew build` and `./gradlew test` (entire project) as they are slow. Use module-specific tasks instead.
