# 1a. Helper builder stage
# Compiles the privilege-dropping helpers used by built-in tool commands and by credential profile
# targets. The base image is the same as the runtime stage so the helpers link against an identical
# glibc; the compiler never reaches the runtime image.
FROM eclipse-temurin:25-jdk AS helper-builder

ARG DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y --no-install-recommends gcc libc6-dev \
    && rm -rf /var/lib/apt/lists/*

# Both helpers include privilege_drop.h, so the privilege-drop sequence exists once.
COPY run-as-agent.c run-as-vault.c privilege_drop.h /src/
RUN mkdir -p /out \
    && gcc -O2 -Wall -o /out/run-as-agent /src/run-as-agent.c \
    && gcc -O2 -Wall -o /out/run-as-vault /src/run-as-vault.c

# 1b. Runtime base image
FROM eclipse-temurin:25-jdk

# Set environment to non-interactive to avoid prompts
ARG DEBIAN_FRONTEND=noninteractive

WORKDIR /app

# 2. Install basic tools and Python (needed for uv)
# xz-utils is required to extract the .tar.xz file from Nodejs.org
RUN apt-get update && apt-get install -y --no-install-recommends \
    curl \
    ca-certificates \
    xz-utils \
    python3 \
    python3-venv \
    gosu \
    git \
    && rm -rf /var/lib/apt/lists/*

# 3. Download and extract official Node.js binaries (OpenJS Foundation)
# Using the LTS version you found: v24.15.0
RUN curl -fsSL https://nodejs.org/dist/v24.15.0/node-v24.15.0-linux-x64.tar.xz -o node.tar.xz \
    && tar -xJf node.tar.xz -C /usr/local --strip-components=1 \
    && rm node.tar.xz

# 4. Install uv (Astral) - Direct binary installation \
RUN curl -LsSf https://astral.sh/uv/install.sh | env UV_INSTALL_DIR="/usr/local/bin" sh

# 5. Create the 'torvian' user and group
RUN groupadd -g 10001 torvian && \
    useradd -u 10001 -g torvian -m -s /bin/bash torvian

# Remove the default 'ubuntu' user from the base image.
# This prevents UID collisions when torvian is remapped at runtime.
RUN userdel ubuntu

# 5b. Create the isolated account used by built-in tool commands.
# 'shared' is the PRIMARY group of both isolated accounts, which is what makes files created by
# spawned commands writable by the worker; the helpers discard supplementary groups.
RUN groupadd -g 10003 shared \
    && useradd -u 10002 -g shared -m -s /bin/bash agent \
    && usermod -aG shared torvian

# 5c. Create the credential-bearing account.
# Only credential profile targets run as 'vault'. Its own trees under /run/torvian/secrets are
# 0700 and vault-owned, so the 'shared' primary group does not expose them. The home directory is
# 0711 on purpose: pty4j changes directory in the forked child before it execs the helper, while the
# child still has the worker's uid, so the path must be traversable but not listable.
RUN useradd -u 10004 -g shared -m -s /bin/bash vault \
    && mkdir -p /home/vault/work \
    && chown vault:shared /home/vault /home/vault/work \
    && chmod 0711 /home/vault /home/vault/work

# 5d. Install the privilege-dropping helpers.
# Root-owned inside a root-owned directory, setuid so they can drop privileges, and never on a mount.
# 4750, not 4755: only the worker group may execute them, because a world-executable run-as-vault
# would let any ordinary session become 'vault' and read every secret.
COPY --from=helper-builder /out/run-as-agent /usr/local/bin/run-as-agent
COPY --from=helper-builder /out/run-as-vault /usr/local/bin/run-as-vault
RUN chown root:torvian /usr/local/bin/run-as-agent /usr/local/bin/run-as-vault \
    && chmod 4750 /usr/local/bin/run-as-agent /usr/local/bin/run-as-vault

# 5e. Install the askpass helper used by 'askpass' credential profiles.
# Root-owned and not writable by the worker or the isolated accounts; it holds no secret, only the
# path of the per-attach file the selected profile's secret is written to.
COPY torvian-askpass.sh /usr/local/bin/torvian-askpass
RUN chown root:root /usr/local/bin/torvian-askpass \
    && chmod 0755 /usr/local/bin/torvian-askpass

# 5f. Create the secret root.
# Worker-owned and traversable by the credential account but not listable by it, and never
# world-listable: the per-attach trees the helper creates inside are what hold secrets.
RUN mkdir -p /run/torvian/secrets \
    && chown torvian:torvian /run/torvian /run/torvian/secrets \
    && chmod 0711 /run/torvian /run/torvian/secrets

# 6. Prepare directory structure for app and MCP caches, plus the isolated account's home
RUN mkdir -p /app/config /app/data /app/logs /app/workspace /app/cache/npm /app/cache/uv /home/agent \
    && mkdir -p /app/data/tmp/pty4j

# 7. Copy application files
# App code and startup scripts stay root-owned to prevent tampering.
COPY dist/ /app

# Startup script must be executable but not writable by 'torvian'.
RUN chmod 755 /app/start-worker.sh

# Secret-bearing directories are readable only by the worker account: an isolated command has a
# different account, so these modes are what keep secrets.json and the token cache out of reach.
RUN chown -R torvian:torvian /app/config /app/data /app/logs \
    && chmod 700 /app/config /app/data /app/logs \
    && chown torvian:torvian /app/data/tmp/pty4j \
    && chmod 0700 /app/data/tmp/pty4j

# Shared directories are group-writable with setgid so new entries keep the 'shared' group.
RUN chown -R torvian:shared /app/workspace /app/cache \
    && chmod 2775 /app/workspace /app/cache /app/cache/npm /app/cache/uv

RUN chown agent:shared /home/agent && chmod 0755 /home/agent

# The workspace is owned by 'torvian' while isolated commands run as 'agent', so git refuses it with
# "detected dubious ownership" until the deployment exempts it; the README documents the two ways to do
# that for isolated commands only. Nothing is set here on purpose: a system-wide safe.directory would
# also cover the worker account, the account that a repository planted in the agent-writable workspace
# can attack through git's configuration hooks.

# 8. Set Environment Variables
ENV NPM_CONFIG_CACHE=/app/cache/npm
ENV UV_CACHE_DIR=/app/cache/uv
ENV NPM_CONFIG_YES=true
# Ensure binaries are found in the PATH for all users/processes
ENV PATH="/usr/local/bin:/usr/bin:${PATH}"

# 9. Execution
USER root
ENTRYPOINT ["/app/start-worker.sh"]