# Worker Module

This module runs the standalone worker process that authenticates against the server using worker service tokens.

## Configuration

Uses a layered config directory, similar to the server module:

- `application.json` (required): base defaults and non-secret settings.
- `setup.json` (optional): setup/runtime overrides written by `--setup`.
- `env-mapping.json` (optional): maps config keys to environment variable names.
- `secrets.json`: certificate private key used by worker auth.

See `worker/dev-config-sample/` for an example set.

Resolution order:

1. CLI flag: `--config=<config-dir>`
2. Environment variable: `CHATBOT_WORKER_CONFIG_DIR`
3. System property: `worker.config.dir`
4. Default: `./worker-config`

Layer precedence (highest wins):

1. `env-mapping.json` resolved from process environment
2. `setup.json`
3. `application.json`

All worker fields can be supplied via environment variables through `env-mapping.json`.

## Run

```powershell
./gradlew worker:run --args="--config=./worker/dev-config-sample"
```
For setup mode, pass `--setup`.

### Initial setup

The setup process is required to register the worker with the server and generate the necessary credentials for authentication. This only needs to be done once per worker instance.
The setup process can be run with manual user input from the command line, or it can be automated using environment variables. The following environment variables can be used to for automated setup:
- CHATBOT_WORKER_CONFIG_DIR: The directory to write the generated credentials and config overrides to (e.g., ./config).
- CHATBOT_WORKER_SETUP_SERVER_URL: The URL of the server to register with (e.g., http://localhost:8080/).
- CHATBOT_WORKER_SETUP_USERNAME: The username of an existing user with permissions to register workers.
- CHATBOT_WORKER_SETUP_PASSWORD: The password of the user.
- CHATBOT_WORKER_SETUP_DISPLAY_NAME: The display name for the worker to register (e.g., "My Worker").

## Test

```powershell
./gradlew worker:test
```

