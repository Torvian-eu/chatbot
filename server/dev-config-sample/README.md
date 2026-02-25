# Sample Development Configuration Files

These are sample configuration files for the Chatbot server application, provided for development convenience. They contain example values and test credentials suitable for local development only.

## Configuration Directory Resolution

The server uses a flexible three-tier system to locate configuration files:

1. **Environment variable**: `CHATBOT_SERVER_CONFIG_DIR` (highest priority)
2. **System property**: `-Dapp.config.dir=<path>`
3. **Default**: `./config` directory (relative to working directory)

## Quick Start for Development

This directory is a **template** — copy it to a directory **outside the project root** and point `CHATBOT_SERVER_CONFIG_DIR` there. The server resolves its data directory as a sibling of the config directory, so they must share the same parent.

> ⚠️ Do **not** place the config directory inside the project root — git will pick up any generated files (database, keystore) and you risk accidentally committing secrets.

### Recommended Workflow

Choose a location outside the project, e.g. `C:\Dev\chatbot-server-dev\config` on Windows or `~/dev/chatbot-server-dev/config` on Linux/macOS.

**PowerShell (Windows):**
```powershell
# Create config directory and copy sample files
New-Item -ItemType Directory -Path "C:\Dev\chatbot-server-dev\config" -Force
Copy-Item -Path "server\dev-config-sample\*" -Destination "C:\Dev\chatbot-server-dev\config\"

# Set environment variable (add to your profile to make it permanent)
$env:CHATBOT_SERVER_CONFIG_DIR = "C:\Dev\chatbot-server-dev\config"
./gradlew server:run
# → config files in C:\Dev\chatbot-server-dev\config\
# → runtime data in C:\Dev\chatbot-server-dev\data\
```

**Bash/Zsh (Linux/macOS):**
```bash
# Create config directory and copy sample files
mkdir -p ~/dev/chatbot-server-dev/config
cp -r server/dev-config-sample/. ~/dev/chatbot-server-dev/config/

# Set environment variable (add to ~/.bashrc or ~/.zshrc to make it permanent)
export CHATBOT_SERVER_CONFIG_DIR="$HOME/dev/chatbot-server-dev/config"
./gradlew server:run
# → config files in ~/dev/chatbot-server-dev/config/
# → runtime data in ~/dev/chatbot-server-dev/data/
```

**IntelliJ IDEA:**
1. Copy `server/dev-config-sample/` contents to your chosen directory outside the project
2. Open Run → Edit Configurations
3. Add environment variable: `CHATBOT_SERVER_CONFIG_DIR=/path/to/chatbot-server-dev/config`
4. Run the application

## Files Included

- **application.json** - Server configuration (host, port, database, etc.)
- **secrets.json** - Sample encryption keys and passwords (test purposes only)
- **setup.json** - Setup flag to skip the setup wizard
- **env-mapping.json** - (Optional) Maps environment variables to config values

## Important Notes

⚠️ **Security Warning:**
- These files contain **sample/test credentials only**
- **DO NOT** use these values in production
- The encryption key and JWT secret are publicly visible in this repository

## For Production

In production deployments:
- The server defaults to using the `./config` directory relative to the working directory
- Optionally set `CHATBOT_SERVER_CONFIG_DIR` for custom locations
- Generate secure, random keys for encryption and JWT
- Use strong passwords for SSL keystores
- Keep the config directory secure with appropriate file permissions
- Never commit production config files to version control
