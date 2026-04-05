# Deploy

This directory contains the deployment files for the chatbot application.

This README is focused on first-time deployment only.

## Files in this folder

- `server/docker-compose.example-vps.yml`: server Compose example for VPS deployment (typically behind Caddy)
- `server/docker-compose.example-lan.yml`: server Compose example for direct LAN deployment
- `caddy/docker-compose.yml`: Caddy stack for reverse proxy + static frontend hosting
- `install-server-dist.ps1`: optional local server dist build/install script
- `install-wasm-client-dist.ps1`: local WASM client dist build/install script

## Pick a deployment profile

### VPS profile (recommended for internet-facing deployment)

1. Copy `server/docker-compose.example-vps.yml` to `server/docker-compose.yml`.
2. Edit environment variables in `server/docker-compose.yml`.
3. Configure `caddy/Caddyfile` with your real domain names.
4. Build/install frontend files so Caddy can serve them.

### LAN profile (direct access on local network)

1. Copy `server/docker-compose.example-lan.yml` to `server/docker-compose.yml`.
2. Edit environment variables in `server/docker-compose.yml`.
3. Start the server stack on your LAN.
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

Optional: build/install local server distribution files:

```powershell
.\deploy\install-server-dist.ps1
```

This step is mainly needed if you want to build the server Docker image yourself.

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

## Start on LAN

```bash
cd /path/to/deploy/server
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