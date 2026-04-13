# Worker Module

This module runs the standalone worker process that authenticates against the server using worker service tokens.

## What phase 3 includes

- tries an existing persisted access token first;
- when no valid token is available, performs certificate-based challenge-response;
- persists newly issued token;
- automatically refreshes token before expiry.

## What phase 6 adds

- a `--setup` mode for initial worker provisioning;
- automatic generation of a self-signed certificate and private key into `secrets.json` when needed;
- writing setup overrides to `setup.json`;
- register the worker with the server during setup, using the generated certificate for authentication.

## Configuration

Use a layered config directory, similar to the server module:

- `application.json` (required): base defaults and non-secret settings.
- `setup.json` (optional): setup/runtime overrides written by `--setup`.
- `env-mapping.json` (optional): maps config keys to environment variable names.
- `secrets.json`: certificate and private key material used by worker auth.

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

For setup mode, pass `--setup` and, when merged config does not already provide a server URL, also pass `--server-url=<server-base-url>`.

## Run

```powershell
./gradlew worker:run --args="--config=./worker/dev-config-sample --once"
```

Use `--once` to authenticate and exit, or run without `--once` to keep refreshing tokens in a loop.

### Initial setup
Set the `CHATBOT_WORKER_SETUP_USERNAME` and `CHATBOT_WORKER_SETUP_PASSWORD` environment variables to the credentials of an existing user with permissions to register workers, then run with `--setup`:
```powershell
$env:CHATBOT_WORKER_SETUP_USERNAME="admin"
$env:CHATBOT_WORKER_SETUP_PASSWORD="Qazxsw321!"
./gradlew worker:run --args="--setup --config=./worker/dev-config-sample --server-url=https://localhost:8443/"
```

## Test

```powershell
./gradlew worker:test
```

