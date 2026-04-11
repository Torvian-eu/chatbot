# Worker Module

This module runs the standalone worker process that authenticates against the server using worker service tokens.

## What phase 3 includes

- tries an existing persisted access token first;
- when no valid token is available, performs certificate-based challenge-response;
- persists newly issued token;
- automatically refreshes token before expiry.

## Configuration

Use a JSON config file (see `worker/dev-config-sample/config.json`).

Resolution order:

1. CLI flag: `--config=<path>`
2. Environment variable: `CHATBOT_WORKER_CONFIG`
3. Default: `./worker-config/config.json`

## Run

```powershell
./gradlew worker:run --args="--config=./worker/dev-config-sample/config.json --once"
```

Use `--once` to authenticate and exit, or run without `--once` to keep refreshing tokens in a loop.

## Test

```powershell
./gradlew worker:test
```

