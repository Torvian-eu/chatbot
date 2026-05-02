# 1. Base image
FROM eclipse-temurin:21-jre

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

# 6. Prepare directory structure for app and MCP caches
RUN mkdir -p /app/config /app/data /app/logs /app/cache/npm /app/cache/uv

# 7. Copy application files (ensuring ownership)
COPY --chown=torvian:torvian dist/ /app
RUN chmod +x /app/start-worker.sh

# 8. Set Environment Variables
ENV NPM_CONFIG_CACHE=/app/cache/npm
ENV UV_CACHE_DIR=/app/cache/uv
ENV NPM_CONFIG_YES=true
# Ensure binaries are found in the PATH for all users/processes
ENV PATH="/usr/local/bin:/usr/bin:${PATH}"

# 9. Execution
USER root
ENTRYPOINT ["/app/start-worker.sh"]