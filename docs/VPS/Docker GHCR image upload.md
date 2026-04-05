## Manual GHCR publish guide (Windows)

This uses your current repo and `deploy/server/Dockerfile`, building locally and pushing manually to GHCR.

Your image name will be:

```text
ghcr.io/torvian-eu/chatbot-server:latest
```

---

# 1. Prerequisites

You need on your Windows machine:

- Docker Desktop installed and running
- Git installed
- access to the GitHub repo/org
- a GitHub token with package publish permission

---

# 2. Create a GitHub token

Go to:

```text
https://github.com/settings/tokens
```

Create a token for manual pushing.

## Recommended scopes
For a classic token, give it at least:

- `write:packages`
- `read:packages`

If needed, also:
- `repo`

Copy the token and keep it safe.

---

# 3. Open PowerShell in your project root

In your case, that is the folder containing:

- `deploy/`
- `server/`
- `app/`
- `gradlew`

Example path:

```text
C:\Users\Rogier\Documents\MyData\MyProjects\Chatbot\chatbot
```

---

# 4. Build the server distribution first

Run:

```powershell
.\deploy\install-server-dist.ps1
```

This prepares:

```text
deploy\server\dist
```

including the files needed by the Dockerfile.

---

# 5. Check that the expected files exist

Optional but useful:

```powershell
Get-ChildItem .\deploy\server\dist
Get-ChildItem .\deploy\server\dist\config
```

You should have at least:

- `lib\`
- `start-server.sh`
- `config\application.json`
- `config\setup.json`
- `config\env-mapping.json`

---

# 6. Build the Docker image locally

From the project root, run:

```powershell
docker build -t ghcr.io/torvian-eu/chatbot-server:latest .\deploy\server
```

If successful, Docker builds the image using:

- build context: `.\deploy\server`
- Dockerfile: `.\deploy\server\Dockerfile`

---

# 7. Verify the image exists locally

Run:

```powershell
docker image ls
```

Look for:

```text
ghcr.io/torvian-eu/chatbot-server   latest
```

---

# 8. Log in to GHCR

In PowerShell, run:

```powershell
docker login ghcr.io -u YOUR_GITHUB_USERNAME
```

Example:

```powershell
docker login ghcr.io -u Rogier
```

Docker will prompt for a password.

## Important
Paste your GitHub token as the password, not your normal GitHub password.

If successful, you should see:

```text
Login Succeeded
```

---

# 9. Push the image to GHCR

Run:

```powershell
docker push ghcr.io/torvian-eu/chatbot-server:latest
```

This uploads the image to GitHub Container Registry.

---

# 10. Verify the package on GitHub

Open:

```text
https://github.com/orgs/Torvian-eu/packages
```

You should see a container package for:

```text
chatbot-server
```

---

# 11. Optional: make the package public

If you want people to pull the image without authenticating to GHCR:

1. open the package in GitHub
2. go to package settings
3. change visibility to public

If you keep it private, users must `docker login ghcr.io` before pulling.

---

# 12. Optional: test pulling the image

You can test with:

```powershell
docker pull ghcr.io/torvian-eu/chatbot-server:latest
```

If already present locally, it may just say it is up to date.

---

# Full command sequence

From your project root in PowerShell:

```powershell
.\deploy\install-server-dist.ps1
docker build -t ghcr.io/torvian-eu/chatbot-server:latest .\deploy\server
docker image ls
docker login ghcr.io -u YOUR_GITHUB_USERNAME
docker push ghcr.io/torvian-eu/chatbot-server:latest
```

---

# Example with your likely username

If your GitHub username is `Rogier`, then:

```powershell
.\deploy\install-server-dist.ps1
docker build -t ghcr.io/torvian-eu/chatbot-server:latest .\deploy\server
docker login ghcr.io -u Rogier
docker push ghcr.io/torvian-eu/chatbot-server:latest
```

---

# Optional: add a version tag too

If you also want a version tag, for example `v0.1.0`, run:

```powershell
docker tag ghcr.io/torvian-eu/chatbot-server:latest ghcr.io/torvian-eu/chatbot-server:v0.1.0
docker push ghcr.io/torvian-eu/chatbot-server:v0.1.0
```

Then you have both:

- `latest`
- `v0.1.0`

---

# Common issues

## 1. Build fails because files are missing
Make sure you ran:

```powershell
.\deploy\install-server-dist.ps1
```

first.

## 2. Push is denied
Check:
- token scopes
- org package permissions
- correct image name
- successful `docker login`

## 3. Wrong GHCR path
Use lowercase org name:

```text
ghcr.io/torvian-eu/chatbot-server:latest
```

not uppercase `Torvian-eu`.

