# Docker VPS Deployment Guide for Chatbot
## Separate Caddy and Server Compose stacks

This guide explains how to deploy the chatbot application to an **Ubuntu VPS** using Docker, with:

- a **chatbot server** stack
- a **Caddy** stack
- a **shared Docker network**
- the **WASM client** served by Caddy
- deployment files prepared on your **local machine** and copied to the VPS
- the server container run from the published image by default

If you want to build your own server image, that is supported as an optional path.

This guide is written for **VPS owners/self-hosters** and explains:

- what to edit
- what to customize
- what to run on the **local machine**
- what to run on the **VPS**

It assumes no prior knowledge from our earlier discussion.

---

# 1. Overview

The deployment consists of three parts:

## 1. Chatbot server
The backend application.

- runs in Docker
- listens on internal port `8080`
- is **not** directly exposed to the public internet
- stores config/data/logs on disk

## 2. Caddy
The reverse proxy and static file server.

- runs in a separate Docker container
- exposes public ports `80` and `443`
- terminates HTTPS
- proxies API traffic to the chatbot server
- serves the WASM frontend

## 3. WASM client
A static frontend build.

- built locally
- uploaded to the VPS
- served by Caddy from disk

---

# 2. Why two separate Compose files?

This guide uses:

- `deploy/server/docker-compose.example-vps.yml`
- `deploy/caddy/docker-compose.yml`
- `deploy/caddy/Caddyfile.example`

This lets you manage:

- the backend independently
- Caddy independently

This is useful if you want Caddy to later serve more than one app/domain on the same VPS.

Because the containers are managed separately, they must share a manually created Docker network.

---

# 3. How the containers communicate

Caddy proxies requests to:

```caddyfile
reverse_proxy chatbot-server:8080
```

For this to work:

- the `chatbot-server` container
- and the `caddy` container

must both be attached to the same Docker network.

This guide uses a shared external network named:

```text
chatbot-network
```

You will create that network once on the VPS.

---

# 4. Expected project structure

On your local machine, this guide assumes your repository contains at least:

```text
deploy/
  caddy/
    Caddyfile.example
    docker-compose.yml
  server/
    docker-compose.example-vps.yml
  wasm-client/
    dist/
```

The backend compose file and frontend build outputs will be prepared locally, then uploaded to the VPS.

---

# 5. Files used in this guide

This guide is based on these files:

## Server Compose
`deploy/server/docker-compose.example-vps.yml`

```yaml
services:
  chatbot-server:
    image: ghcr.io/torvian-eu/chatbot-server:latest
    container_name: chatbot-server
    restart: unless-stopped
    environment:
      SERVER_HOST: "0.0.0.0"
      SERVER_PORT: "8080"
      SERVER_CONNECTOR_TYPE: "HTTP"
      SERVER_CORS_ALLOWED_ORIGIN_1: "https://chat.example.com"
    volumes:
      - chatbot-config:/app/config
      - chatbot-data:/app/data
      - chatbot-logs:/app/logs
    expose:
      - "8080"
    networks:
      chatbot-network:
        aliases:
          - chatbot-server
networks:
  chatbot-network:
    external: true
volumes:
  chatbot-config:
  chatbot-data:
  chatbot-logs:
```

## Caddy Compose
`deploy/caddy/docker-compose.yml`

```yaml
services:
  caddy:
    image: caddy:2
    container_name: caddy
    restart: unless-stopped
    ports:
      - "80:80"
      - "443:443"
    volumes:
      - ./Caddyfile:/etc/caddy/Caddyfile:ro
      - ../wasm-client/dist:/srv/client:ro
      - caddy_data:/data
      - caddy_config:/config
    networks:
      - chatbot-network
volumes:
  caddy_data:
  caddy_config:
networks:
  chatbot-network:
    external: true
```

## Caddyfile
`deploy/caddy/Caddyfile.example`

```caddyfile
# Reverse proxy for API server
chatbot-server-demo.torvian.eu {
    # Ktor server listens on chatbot-server:8080, Caddy will proxy requests to it
    reverse_proxy chatbot-server:8080 {
        # Preserve original request information for the backend server
        header_up Host {host}
        header_up X-Forwarded-Host {host}
        header_up X-Forwarded-Proto {scheme}
        header_up X-Forwarded-For {remote_host}
    }
    
    # Security headers
    header Strict-Transport-Security max-age=31536000
    header X-Content-Type-Options nosniff
    header X-Frame-Options DENY
}
# Static site for WASM client
chatbot-client-demo.torvian.eu {
    root * /srv/client
    file_server
    
    # SPA fallback: if file not found, serve index.html
    try_files {path} /index.html
    
    # WASM MIME type
    @wasmFiles {
        path *.wasm
    }
    header @wasmFiles Content-Type application/wasm
    
    # Caching
    header /index.html Cache-Control "no-cache, no-store, must-revalidate"
    header *.js Cache-Control "public, max-age=31536000, immutable"
    header *.wasm Cache-Control "public, max-age=31536000, immutable"
    
    # Security headers
    header Strict-Transport-Security max-age=31536000
    header X-Content-Type-Options nosniff
    header X-Frame-Options DENY
    header Permissions-Policy "geolocation=(), microphone=(), camera=()"
}
```

---

# 6. What users need to customize

A VPS owner typically needs to customize:

## 1. Domain names
Copy `deploy/caddy/Caddyfile.example` to `deploy/caddy/Caddyfile`, then replace:

- `chatbot-server-demo.torvian.eu`
- `chatbot-client-demo.torvian.eu`

with their own real domains.

Example:

```caddyfile
api.example.com {
    reverse_proxy chatbot-server:8080
    ...
}

chat.example.com {
    root * /srv/client
    file_server
    ...
}
```

---

## 2. DNS records
At their DNS provider, they must point the chosen domains to the VPS IP.

For example:

- `api.example.com` → VPS IP
- `chat.example.com` → VPS IP

Without this, HTTPS certificate issuance will fail.

---

## 3. Server config
Set the runtime environment variables in `deploy/server/docker-compose.example-vps.yml` (or the copied `deploy/server/docker-compose.yml`).

Use `server/src/main/dist/config/env-mapping.json` as the reference for available environment variable names.

The most common values are:

- `SERVER_HOST=0.0.0.0`
- `SERVER_PORT=8080`
- `SERVER_CONNECTOR_TYPE=HTTP`
- `SERVER_CORS_ALLOWED_ORIGIN_1=https://chat.example.com`

---

## 4. Setup state
Usually you do not need to edit this manually.

If you do need to preseed setup state, it lives in the server config volume at:

```text
/app/config/setup.json
```

On first deployment, it may be:

```json
{
  "setup": {
    "required": true
  }
}
```

After first successful setup, the app may change this automatically.

---

## 5. Optional custom image build
Only if you want to build the server Docker image yourself, run `deploy/install-server-dist.ps1` and use `deploy/server/Dockerfile`.

If you use the published server image in the example compose file, you can skip the custom-image build path.

---

# 7. Prerequisites for the VPS owner

They need:

- Ubuntu VPS
- SSH access
- a non-root user with sudo, e.g. `ubuntu`
- Docker installed
- Docker Compose plugin installed
- DNS control for the chosen domain(s)

---

# 8. Local machine steps — prepare deployment files

These steps are performed on the **local machine**, not on the VPS.

---

## 8.1 Optional: build and install the server distribution locally

This is mainly needed if you want to build the server Docker image yourself:

```text
deploy/install-server-dist.ps1
```

Run it in PowerShell from the project root:

```powershell
.\deploy\install-server-dist.ps1
```

This will:

- run the Gradle server build
- install the server distribution into:
  ```text
  deploy/server/dist
  ```

### Result
You should get:

```text
deploy/server/dist/
  lib/
  start-server.sh
  config/
  data/
```

---

## 8.2 Build and install the WASM client locally

Run:

```powershell
.\deploy\install-wasm-client-dist.ps1
```

This will:

- run the frontend build
- copy the output into:
  ```text
  deploy/wasm-client/dist
  ```

### Result
You should get:

```text
deploy/wasm-client/dist/
  index.html
  ...
```

---

## 8.3 Edit the deployment files locally

Before uploading to the VPS, edit these files locally.

### A. `deploy/server/docker-compose.example-vps.yml`
Copy it to `deploy/server/docker-compose.yml` and edit the environment variables using the names from `server/src/main/dist/config/env-mapping.json`.

### B. `deploy/caddy/Caddyfile.example`
Copy it to `deploy/caddy/Caddyfile`, then replace the example domains with the real domains.

Example:

```caddyfile
api.example.com {
    reverse_proxy chatbot-server:8080 {
        header_up Host {host}
        header_up X-Forwarded-Host {host}
        header_up X-Forwarded-Proto {scheme}
        header_up X-Forwarded-For {remote_host}
    }

    header Strict-Transport-Security max-age=31536000
    header X-Content-Type-Options nosniff
    header X-Frame-Options DENY
}

chat.example.com {
    root * /srv/client
    file_server
    try_files {path} /index.html

    @wasmFiles {
        path *.wasm
    }
    header @wasmFiles Content-Type application/wasm

    header /index.html Cache-Control "no-cache, no-store, must-revalidate"
    header *.js Cache-Control "public, max-age=31536000, immutable"
    header *.wasm Cache-Control "public, max-age=31536000, immutable"

    header Strict-Transport-Security max-age=31536000
    header X-Content-Type-Options nosniff
    header X-Frame-Options DENY
    header Permissions-Policy "geolocation=(), microphone=(), camera=()"
}
```


## 8.4 Ensure logs directory exists

You do not need to create a host-side `deploy/server/dist/logs` directory in the default published-image flow.

If you are building a custom image and using a different host layout, follow that layout instead.

---

# 9. VPS steps — install Docker

These steps are performed on the VPS.

SSH in:

```bash
ssh ubuntu@your-vps-ip
```

Then install Docker and the Compose plugin:

```bash
sudo apt update
sudo apt install -y ca-certificates curl

sudo apt remove $(dpkg --get-selections docker.io docker-compose docker-compose-v2 docker-doc podman-docker containerd runc | cut -f1)

sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc

sudo tee /etc/apt/sources.list.d/docker.sources <<EOF
Types: deb
URIs: https://download.docker.com/linux/ubuntu
Suites: $(. /etc/os-release && echo "${UBUNTU_CODENAME:-$VERSION_CODENAME}")
Components: stable
Architectures: $(dpkg --print-architecture)
Signed-By: /etc/apt/keyrings/docker.asc
EOF

sudo apt update
sudo apt install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

sudo systemctl enable docker
sudo systemctl start docker

docker --version
docker compose version

sudo docker run hello-world
```

Also see: [Docker Installation Guide](Docker%20Installation%20guide.md)

Check:

```bash
docker version
docker compose version
```

---

## 9.4 Optional: allow the `ubuntu` user to run Docker

```bash
sudo usermod -aG docker ubuntu
```

Then log out and back in.

If you do not do this, prepend `sudo` to Docker commands.

---

# 11. VPS steps — create target directory

Create the deployment base directory:

```bash
mkdir -p /home/ubuntu/chatbot
```

---

# 12. Local machine steps — upload deployment files

From your local machine, upload the prepared `deploy/` directory to the VPS.

Example:

```powershell
scp -r .\deploy ubuntu@your-vps-ip:/home/ubuntu/chatbot/
```

This should result in:

```text
/home/ubuntu/chatbot/deploy/
```

on the VPS.

All of the later VPS commands assume that `deploy/` has already been copied there.

---

# 13. VPS steps — verify uploaded files

On the VPS:

```bash
ls -R /home/ubuntu/chatbot/deploy
```

You should see:

- `caddy/`
- `server/`
- `wasm-client/`

and their expected contents.

---

# 14. VPS steps — create the shared Docker network

This is required because the Caddy stack and server stack are separate.

Create it once:

```bash
docker network create chatbot-network
```

If it already exists, that is fine.

You can list networks with:

```bash
docker network ls
```

---

# 15. VPS steps — start the chatbot server stack

Go to the server directory:

```bash
cd /home/ubuntu/chatbot/deploy/server
```

Build and start:

```bash
docker compose up -d
```

This will:

- create the `chatbot-server` container
- connect it to `chatbot-network`

If you are using a custom built image, follow the custom-image path above before starting this stack.

---

# 16. VPS steps — inspect server logs

Check whether the backend starts correctly:

```bash
docker compose logs -f chatbot-server
```

If you only want recent logs without following:

```bash
docker compose logs chatbot-server
```

You should make sure the backend is healthy before starting Caddy.

---

# 17. VPS steps — start the Caddy stack

Now go to the Caddy directory:

```bash
cd /home/ubuntu/chatbot/deploy/caddy
```

Start it:

```bash
docker compose up -d
```

This will:

- start Caddy
- connect it to `chatbot-network`
- bind ports `80` and `443`
- begin certificate provisioning for the domains in `Caddyfile`

---

# 18. VPS steps — inspect Caddy logs

Check Caddy logs:

```bash
docker compose logs -f caddy
```

Watch for:
- certificate issuance
- DNS validation problems
- reverse proxy errors

---

# 19. VPS steps — test internal connectivity

If Caddy appears unable to reach the server, test from inside the Caddy container.

From the Caddy directory:

```bash
docker compose exec caddy sh
```

Then inside the container:

```sh
wget -O- http://chatbot-server:8080
```

If this works, the shared Docker network and hostname resolution are working.

Exit:

```sh
exit
```

---

# 20. Test the public deployment

From the VPS or another machine, test:

## Backend domain
```bash
curl -I https://api.example.com
```

## Frontend domain
```bash
curl -I https://chat.example.com
```

Also open both in a browser.

---

# 21. Day-to-day redeployment workflow

When the application changes, the deployment workflow is:

---

## On the local machine

### 1. Rebuild backend distribution only if you are building a custom server image
```powershell
.\deploy\install-server-dist.ps1
```

### 2. Rebuild frontend distribution
```powershell
.\deploy\install-wasm-client-dist.ps1
```

### 3. Re-check custom config changes
Make sure your domain edits and config changes are still present:

- `deploy/server/docker-compose.yml`
- `deploy/caddy/Caddyfile`
- `/app/config/setup.json` (if you use or customize it)

### 4. Re-upload deployment files
```powershell
scp -r .\deploy ubuntu@your-vps-ip:/home/ubuntu/chatbot/
```

---

## On the VPS

### 5. Rebuild and restart server stack
```bash
cd /home/ubuntu/chatbot/deploy/server
docker compose up -d
```

### 6. Restart/update Caddy stack if needed
If only the backend changed, you may not need to restart Caddy.

If `Caddyfile` or client files changed:

```bash
cd /home/ubuntu/chatbot/deploy/caddy
docker compose up -d
```

---

# 22. Where persistent data lives

## Server config
Container path:

```text
/app/config
```

Stored in the Docker volume `chatbot-config` in the default example compose file.

## Server data
Container path:

```text
/app/data
```

Stored in the Docker volume `chatbot-data` in the default example compose file.

## Server logs
Container path:

```text
/app/logs
```

Stored in the Docker volume `chatbot-logs` in the default example compose file.

## Frontend static files
Host path:

```text
/home/ubuntu/chatbot/deploy/wasm-client/dist
```

Container path in Caddy:

```text
/srv/client
```

## Caddy certificates
Stored in Docker volumes:
- `caddy_data`
- `caddy_config`

---

# 23. How to stop the services

## Stop server stack
```bash
cd /home/ubuntu/chatbot/deploy/server
docker compose down
```

## Stop Caddy stack
```bash
cd /home/ubuntu/chatbot/deploy/caddy
docker compose down
```

---

# 24. How to remove the shared network

Only remove the network after both stacks are stopped:

```bash
docker network rm chatbot-network
```

---

# 25. Common problems and how to fix them

## Problem 1 — Caddy cannot resolve `chatbot-server`
### Cause
The shared Docker network was not created or the containers are not attached to it.

### Fix
- create:
  ```bash
  docker network create chatbot-network
  ```
- verify both Compose files include the external network
- restart both stacks

---

## Problem 2 — Caddy can resolve the name but cannot connect
### Cause
The backend is not listening on the right interface.

### Fix
In the server compose file, make sure `SERVER_HOST=0.0.0.0`.

---

## Problem 3 — HTTPS certificates are not issued
### Cause
DNS is not correctly pointing to the VPS.

### Fix
Check that your chosen domains resolve to the VPS public IP.

---

## Problem 4 — Ports 80 or 443 are already in use
### Cause
Another service is already listening on those ports.

### Fix
Check:

```bash
sudo ss -tulpn | grep ':80\|:443'
```

Stop the conflicting service.

---

## Problem 5 — CORS errors in the browser
### Cause
Frontend domain does not match the allowed origins in backend environment variables.

### Fix
In `deploy/server/docker-compose.yml`, set:

```yaml
SERVER_CORS_ALLOWED_ORIGIN_1: "https://chat.example.com"
```

---

## Problem 6 — Uploading `deploy/` overwrites custom edits unexpectedly
### Cause
You rebuilt locally and copied files again.

### Fix
Always review these after rebuilds:
- `deploy/server/docker-compose.yml`
- `deploy/caddy/Caddyfile`
- `/app/config/setup.json` (if used)

If needed, keep a documented copy of your production-specific values.

---

# 26. Final reference files

## `deploy/server/docker-compose.yml`

```yaml
services:
  chatbot-server:
    image: ghcr.io/torvian-eu/chatbot-server:latest
    container_name: chatbot-server
    restart: unless-stopped
    environment:
      SERVER_HOST: "0.0.0.0"
      SERVER_PORT: "8080"
      SERVER_CONNECTOR_TYPE: "HTTP"
      SERVER_CORS_ALLOWED_ORIGIN_1: "https://chat.example.com"
    volumes:
      - chatbot-config:/app/config
      - chatbot-data:/app/data
      - chatbot-logs:/app/logs
    expose:
      - "8080"
    networks:
      chatbot-network:
        aliases:
          - chatbot-server
networks:
  chatbot-network:
    external: true
volumes:
  chatbot-config:
  chatbot-data:
  chatbot-logs:
```

## `deploy/caddy/docker-compose.yml`

```yaml
services:
  caddy:
    image: caddy:2
    container_name: caddy
    restart: unless-stopped
    ports:
      - "80:80"
      - "443:443"
    volumes:
      - ./Caddyfile:/etc/caddy/Caddyfile:ro
      - ../wasm-client/dist:/srv/client:ro
      - caddy_data:/data
      - caddy_config:/config
    networks:
      - chatbot-network
volumes:
  caddy_data:
  caddy_config:
networks:
  chatbot-network:
    external: true
```

## `deploy/caddy/Caddyfile`

```caddyfile
# Reverse proxy for API server
chatbot-server-demo.torvian.eu {
    # Ktor server listens on chatbot-server:8080, Caddy will proxy requests to it
    reverse_proxy chatbot-server:8080 {
        # Preserve original request information for the backend server
        header_up Host {host}
        header_up X-Forwarded-Host {host}
        header_up X-Forwarded-Proto {scheme}
        header_up X-Forwarded-For {remote_host}
    }
    
    # Security headers
    header Strict-Transport-Security max-age=31536000
    header X-Content-Type-Options nosniff
    header X-Frame-Options DENY
}
# Static site for WASM client
chatbot-client-demo.torvian.eu {
    root * /srv/client
    file_server
    
    # SPA fallback: if file not found, serve index.html
    try_files {path} /index.html
    
    # WASM MIME type
    @wasmFiles {
        path *.wasm
    }
    header @wasmFiles Content-Type application/wasm
    
    # Caching
    header /index.html Cache-Control "no-cache, no-store, must-revalidate"
    header *.js Cache-Control "public, max-age=31536000, immutable"
    header *.wasm Cache-Control "public, max-age=31536000, immutable"
    
    # Security headers
    header Strict-Transport-Security max-age=31536000
    header X-Content-Type-Options nosniff
    header X-Frame-Options DENY
    header Permissions-Policy "geolocation=(), microphone=(), camera=()"
}
```

---

# 27. Summary

This deployment model works like this:

## Local machine
- build backend distribution if you are building a custom server image
- build frontend distribution
- edit domains/config in the compose and Caddy files
- upload `deploy/` to VPS

## VPS
- install Docker
- create `chatbot-network`
- start server stack
- start Caddy stack
- test domains

Caddy and the backend are managed independently, but communicate through the shared external Docker network.
