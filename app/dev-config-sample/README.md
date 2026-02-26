# Sample Development Configuration Files

These are sample configuration files for the Chatbot desktop application, provided for development convenience. They contain example values and test credentials suitable for local development only.

## Quick Start for Development

To use these sample configurations during development, set the `CHATBOT_CONFIG_DIR` environment variable:

### PowerShell (Windows):
```powershell
$env:CHATBOT_CONFIG_DIR = ".\app\dev-config-sample"
./gradlew app:run
```

### Bash/Zsh (Linux/macOS):
```bash
export CHATBOT_CONFIG_DIR="./app/dev-config-sample"
./gradlew app:run
```

### IntelliJ IDEA:
1. Open Run → Edit Configurations
2. Add environment variable: `CHATBOT_CONFIG_DIR=./app/dev-config-sample` (or use absolute path)
3. Run the application

## Alternative: Copy to Project Root

You can also copy these files to `./config/` in the project root:

```bash
# Linux/macOS
cp -r app/dev-config-sample config

# Windows (PowerShell)
Copy-Item -Path "app\dev-config-sample" -Destination "config" -Recurse
```

The application will automatically use `./config/` if it exists.

## Files Included

- **config.json** - Development server URL and storage paths
- **secrets.json** - Sample encryption key (test purposes only)
- **setup.json** - Setup flag to skip the setup wizard

## Important Notes

⚠️ **Security Warning:**
- These files contain **sample/test credentials only**
- **DO NOT** use these values in production
- For production, let the setup wizard guide users to create secure configurations
- If you copy these to `./config/`, that directory is already in `.gitignore`

## For Production

In production deployments, the application will:
1. Check for the `CHATBOT_CONFIG_DIR` environment variable
2. Fall back to `./config/` if it exists
3. Otherwise use OS-specific paths and launch the setup wizard to create secure configurations


