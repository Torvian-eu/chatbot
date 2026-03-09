# Completion Checklist

When a task is completed, ensure the following steps are performed:

1. **Linting and Formatting**: (Assuming no specific linter is mentioned, follow project style).
2. **Unit Tests**:
   - For Server: `./gradlew server:test`
   - For Desktop App: `./gradlew app:desktopTest`
3. **Integration Tests**: (If applicable).
4. **Build Verification**:
   - For Server: `./gradlew server:assemble`
   - For Desktop App: `./gradlew app:desktopMainClasses`
5. **Documentation**:
   - Update `README.md` if necessary.
   - Update `docs/Project and Package Structure.md` for new modules or package changes.
   - Update `docs/Todos.md` if a task was part of it.
6. **Code Review Preparation**:
   - Ensure `common/` module changes don't break both `app/` and `server/`.
   - Verify `secrets.json` is not committed.
7. **Clean up**:
   - Delete temporary files or `.db-shm`/`.db-wal` files if not needed.
