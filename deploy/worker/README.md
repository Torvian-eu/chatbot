# Worker Deployment

This folder contains the files needed to run the chatbot worker as a Docker container. The worker executes MCP (and built-in) tools on behalf of the server and is required for tool execution.

## Files in this folder

- `Dockerfile`: Dockerfile for the worker module.
- `start-worker-docker.sh`: startup script used inside the Docker image. It runs the worker as the non-root `torvian` user (via `gosu`) while still being able to write to the mounted config, data, and logs volumes. It also supports the trusted-signer admin mode (see below).
- `docker-compose.example-local.yml`: Docker Compose example for local deployment (server reachable at `http://host.docker.internal:8080`).
- `docker-compose.example-vps.yml`: Docker Compose example for VPS deployment (server reachable via a real domain).
- `dist/`: built worker distribution copied here by `deploy/install-worker-dist.ps1` (including `start-worker.sh` / `start-worker.bat`).

## First-time setup

The worker must register with the server before it can run tools. On first run, start it in interactive mode so it can prompt for server URL and credentials and generate its certificate:

```bash
docker compose -f docker-compose.example-vps.yml run -it --rm chatbot-worker
```

After setup completes, start the worker in detached mode:

```bash
docker compose -f docker-compose.example-vps.yml up -d
```

Notes:
- The server must be running before the worker starts.
- An **active** user (or admin) account is required for setup, because the worker authenticates with the server.
- The `PUID`/`PGID` environment variables map the internal `torvian` user to your host user so mounted volumes stay writable. Adjust them to your host UID/GID (`id -u`, `id -g`).

## Authorizing clients (trusted signers / E2EA)

Tool-execution requests are signed by the client (End-to-End Authorization). The worker only accepts requests from signers it trusts. You register a client's signer credentials in one of two ways.

### Option 1: Trusted-signer admin mode (CLI)

Run the worker once with `--add-trusted-signer`. This adds or replaces a single trusted signer in `application.json` and then exits (the runtime does not start):

```bash
docker compose -f docker-compose.example-vps.yml run --rm chatbot-worker \
  --add-trusted-signer \
  --signer-id=<SIGNER_ID> \
  --public-key-base64=<PUBLIC_KEY> \
  --permissions=mcp:read,mcp:write
```

- `--signer-id` and `--public-key-base64` are required.
- `--permissions` is optional (comma-separated list of permission tokens).
- Re-adding the same signer ID replaces the existing entry.

The client's Signer ID and public key are shown under **Settings → E2EA Security**.

### Option 2: Environment variables

Set the `WORKER_TRUSTED_SIGNER_*` variables in your `docker-compose.yml` (see the commented block in `docker-compose.example-vps.yml`). Each signer occupies a numbered slot:

```yaml
environment:
  - WORKER_TRUSTED_SIGNER_1_ID=client-desktop-7f3a
  - WORKER_TRUSTED_SIGNER_1_PUBLIC_KEY_BASE64=MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcD...
  - WORKER_TRUSTED_SIGNER_1_PERMISSION_1=mcp:read
  - WORKER_TRUSTED_SIGNER_1_PERMISSION_2=mcp:write
```

For the full reference (permissions semantics, `application.json` structure, validation rules, and limitations), see the [Trusted Signers guide](../../docs/user%20guides/Trusted%20Signers%20guide.md).

## Building the image locally (optional)

```bash
.\deploy\install-worker-dist.ps1   # builds dist/ and copies start-worker-docker.sh
cd deploy/worker
docker build -t chatbot-worker:local .
```

Then point your `docker-compose.yml` at `chatbot-worker:local` instead of the `ghcr.io` image.
