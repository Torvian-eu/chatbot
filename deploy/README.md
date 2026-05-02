# Deploy

This directory contains the deployment files for the chatbot application.

This README is focused on first-time deployment only.

## Files in this folder

- `server/Dockerfile`: Dockerfile for the server module 
- `server/docker-compose.example-local.yml`: Docker Compose example for local deployment of the server
- `server/docker-compose.example-vps.yml`: Docker Compose example for VPS deployment of the server
- `server/application.example-local.json`: example server application configuration for local deployment
- `server/application.example-vps.json`: example server application configuration for VPS deployment
- `worker/Dockerfile`: Dockerfile for the worker module
- `worker/start-worker-docker.sh`: modified startup script for the worker, used in the Docker image to allow running as non-root user while still being able to write to mounted volumes
- `worker/docker-compose.example-local.yml`: Docker Compose example for local deployment of the worker
- `worker/docker-compose.example-vps.yml`: Docker Compose example for VPS deployment of the worker
- `caddy/docker-compose.example.yml`: Docker Compose example for Caddy with reverse proxy & static frontend hosting
- `install-server-dist.ps1`: server distribution build/install script
- `install-worker-dist.ps1`: worker distribution build/install script
- `install-wasm-client-dist.ps1`: WASM client distribution build/install script

## Pick a deployment profile

### VPS profile (recommended for internet-facing deployment)

1. Copy `server/docker-compose.example-vps.yml` to `server/docker-compose.yml`.
2. Edit environment variables in `server/docker-compose.yml`.
3. Configure `caddy/Caddyfile` with your real domain names.
4. Build/install frontend files so Caddy can serve them.

### Local profile (direct access on local network)

1. Copy `server/docker-compose.example-local.yml` to `server/docker-compose.yml`.
2. Edit environment variables in `server/docker-compose.yml`.
3. Start the server stack on your local machine.
4. If you also want the WASM client in a browser, use Caddy or another static host/reverse proxy and adapt `caddy/Caddyfile` for your LAN hostnames.

## Common environment variables to set

These are the most common variables to adjust in `server/docker-compose.yml`:

- `SERVER_HOST`
- `SERVER_PORT` (HTTP mode)
- `SSL_PORT` (HTTPS mode)
- `SERVER_CONNECTOR_TYPE` (`HTTP` or `HTTPS`)
- `SERVER_CORS_ALLOWED_ORIGIN_1` (and `_2` if needed)

If you need to look up the available environment variable names, use:

- `server/src/main/dist/config/env-mapping.json`

## Local preparation (project root)

Build the frontend files used by Caddy:

```powershell
.\deploy\install-wasm-client-dist.ps1
```

## Build Docker images from source (optional)
Either use the prebuilt images from ghcr.io or build them locally from source.

### Server module
Build/install local server distribution files:

```powershell
.\deploy\install-server-dist.ps1
```

Build the Docker image locally (from deploy/server):

```bash
docker build -t chatbot-server:local .
```

Then update `server/docker-compose.yml` to use `chatbot-server:local` instead of the ghcr.io image:

```yaml
# Example snippet from docker-compose.yml
services:
  chatbot-server:
    image: chatbot-server:local # Use the locally built image instead of ghcr.io
    # ... rest of the service definition
    
```

For quick testing without using Compose, you can also run the server container directly:

```bash
docker run --rm \
  --name chatbot-server-test \
  -p 8080:8080 \
  -e SERVER_HOST=0.0.0.0 \
  -e SERVER_PORT=8080 \
  -e SERVER_CONNECTOR_TYPE=HTTP \
  chatbot-server:local
```

```powershell
docker run --rm `
  --name chatbot-server-test `
  -p 8080:8080 `
  -e SERVER_HOST=0.0.0.0 `
  -e SERVER_PORT=8080 `
  -e SERVER_CONNECTOR_TYPE=HTTP `
  chatbot-server:local
```

Note: all data will be lost when using `--rm` and not mounting volumes, so this is only recommended for quick testing of the image build.

### Worker module (required for MCP tool execution)
#### Build/install local worker distribution files:

```powershell
.\deploy\install-worker-dist.ps1
```
Note: This also copies a modified startup script `start-worker-docker.sh` to the `deploy/worker/dist` folder, overwriting the default `start-worker.sh`. This is necessary to allow the worker to run as a non-root user in the Docker container while still being able to write to the mounted volumes for config, data, and logs.

#### Build the worker image locally (from deploy/worker):

```bash
docker build -t chatbot-worker:local .
```

Then update `worker/docker-compose.yml` to use `chatbot-worker:local` instead of the ghcr.io image:

```yaml
# Example snippet from docker-compose.yml
services:
  chatbot-worker:
    image: chatbot-worker:local # Use the locally built image instead of ghcr.io
    # ... rest of the service definition
```

For quick testing without using Compose, you can also run the worker container directly:

```bash
docker run -it --rm  \ 
  --name chatbot-worker-test \
  -e CHATBOT_WORKER_SETUP_SERVER_URL=http://host.docker.internal:8080 \
  -e CHATBOT_WORKER_SETUP_AUTO_START=true \
  -e PUID=1000 \
  -e PGID=1000 \
  chatbot-worker:local
```

```powershell
docker run -it --rm  `
  --name chatbot-worker-test `
  -e CHATBOT_WORKER_SETUP_SERVER_URL=http://host.docker.internal:8080 `
  -e CHATBOT_WORKER_SETUP_AUTO_START=true `
  -e PUID=1000 `
  -e PGID=1000 `
  chatbot-worker:local
```

**Notes**:
- The worker needs to connect to the server for setup, so we use `host.docker.internal` to allow it to reach the server running on the host machine.
- Use `-it` for interactive mode since the worker requires user input for initial setup on first run
- The PUID and PGID environment variables are set to 1000 (the default non-root user on many Linux systems) to allow the worker to write to the mounted volumes without permission issues. Adjust these values if your system uses different user/group IDs.


## Start on VPS

First copy the `deploy/` folder to your VPS, keeping the same folder structure. The examples below assume it is available at something like `/home/ubuntu/chatbot/deploy`.

Create shared Docker network (once):

```bash
docker network create chatbot-network
```

Start server stack:

```bash
cd /home/ubuntu/chatbot/deploy/server
docker compose up -d
```

Start Caddy stack:

```bash
cd /home/ubuntu/chatbot/deploy/caddy
docker compose up -d
```

Start worker (required for MCP tool execution):

```bash
cd /home/ubuntu/chatbot/deploy/worker
# Initial setup requires interactive mode, so we run it with -it and --rm
docker compose run -it --rm chatbot-worker

# After initial setup is complete, you can start the worker in detached mode:
docker compose up -d
```

## Start on Local machine

```bash
cd /path/to/deploy/server
docker compose up -d
```

```bash
cd /path/to/deploy/worker
docker compose run -it --rm chatbot-worker
docker compose up -d
```

## Logs

Server logs:

```bash
cd /home/ubuntu/chatbot/deploy/server
docker compose logs -f chatbot-server
```

Caddy logs:

```bash
cd /home/ubuntu/chatbot/deploy/caddy
docker compose logs -f caddy
```

## Related docs

- [VPS deployment guide](../docs/VPS/Docker%20VPS%20Deployment%20Guide.md)
- [VPS maintenance/basic Linux guide](../docs/VPS/Ubuntu%20VPS%20Basics%20Guide%20for%20Self-Hosted%20Docker%20Apps.md)
- [Docker Installation Guide](../docs/VPS/Docker%20Installation%20guide.md)