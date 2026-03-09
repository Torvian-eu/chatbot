# Code Style and Conventions

## Language
- **Kotlin 2.2.0**
- **Type Safety**: Strictly typed (DTOs in `common/`).

## Architecture
- **Layered approach**: Presentation (app), Logic (server), Models (common).
- **Dependency Injection**: Use Koin 4.1.0 for all modules.
- **Transactions**: Always use standard `TransactionScope` for DB interactions.
- **Error Handling**: Use Arrow's `Either` for logical/domain errors. Technical errors should be captured and converted where applicable.
- **Date/Time**: Use `kotlinx.datetime` for multi-platform compatibility.

## UI/UX
- **Compose Multiplatform**: Default UI library.
- **Material 3**: Design standard for components.
- **Preview components**: Use `prev` prefix or common practice for Compose previews.

## Naming Conventions
- **Controllers**: (Ktor) and **ViewModels** (App).
- **DAOs**: In the server module.
- **Repositories**: In the app module.
- **Entities**: Suffix `Entity`.

## Documentation
- Document complex logic with KDoc.
- Keep `Project and Package Structure.md` updated with structural changes.
