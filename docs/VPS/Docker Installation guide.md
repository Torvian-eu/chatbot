# Install Docker Engine + Docker Compose on Ubuntu VPS
## With prerequisites, firewall notes, verification, and VPS recommendations

---

# 0. Important first: your current OS is a problem

Your VPS says:

```text
Your Ubuntu release is not supported anymore.
New release '25.10' available.
```

And Docker’s current Ubuntu support list does **not include Ubuntu 25.04**.

## Supported Ubuntu versions in the docs
At the moment, Docker officially supports:

- **Ubuntu 26.04 LTS**
- **Ubuntu 25.10**
- **Ubuntu 24.04 LTS**
- **Ubuntu 22.04 LTS**

## Recommended action
For a VPS, the **best choice** is usually:

- **Ubuntu 24.04 LTS**

That gives you:
- longer support
- better stability
- fewer upgrade surprises
- excellent Docker compatibility

## My recommendation
If this VPS is still mostly empty, **reinstall it to Ubuntu 24.04 LTS** in OVHcloud if possible.

If you cannot reinstall right now, you can still try the steps below, but keep in mind:
- Docker may not publish packages for your current Ubuntu codename
- apt repo setup may fail
- even if it installs, you are building on an unsupported OS

---

# 1. What you are installing

You want modern Docker on Ubuntu. That means:

- **Docker Engine**
- **Docker CLI**
- **containerd**
- **Docker Buildx plugin**
- **Docker Compose plugin**

On modern systems, Compose is normally used as:

```bash
docker compose
```

not the old legacy command:

```bash
docker-compose
```

---

# 2. Prerequisites

Before you install Docker, make sure the following are true.

---

## 2.1 You have a supported Ubuntu version

Check your OS:

```bash
cat /etc/os-release
```

Check your architecture:

```bash
dpkg --print-architecture
```

Typical output on your VPS should be:

- `amd64`

Docker supports:
- `amd64`
- `arm64`
- `armhf`
- `s390x`
- `ppc64el`

---

## 2.2 You have sudo access

Check your current user:

```bash
whoami
```

If you are root, you can omit `sudo`.  
If you are a regular admin user, keep `sudo`.

---

## 2.3 Firewall warning: important before installing Docker

Docker’s official docs explicitly warn about this:

### If you publish container ports, they may bypass UFW/firewalld expectations

Example:

```yaml
ports:
  - "80:80"
```

or

```bash
docker run -p 80:80 nginx
```

When you publish ports like that, Docker installs its own networking/firewall rules.

## Practical meaning
Do **not** assume UFW alone will protect Docker-published ports.

### Safe rule:
- If a service should be public, publish the port
- If a service should stay private, **do not publish the port**
- If it should only be reachable from the VPS itself, bind it to localhost

Example:

```yaml
ports:
  - "127.0.0.1:3000:3000"
```

That makes the service local-only.

## Strong recommendation
Do **not** disable Docker’s iptables handling unless you really know what you are doing.

Do **not** put this in Docker config unless you have a very advanced setup:

```json
{
  "iptables": false
}
```

That often breaks normal Docker networking.

---

# 3. Remove old/conflicting Docker packages

Ubuntu or earlier setup attempts may have installed conflicting packages.

Docker’s official docs recommend removing these first:

- `docker.io`
- `docker-compose`
- `docker-compose-v2`
- `docker-doc`
- `podman-docker`
- `containerd`
- `runc`

Run:

```bash
sudo apt remove $(dpkg --get-selections docker.io docker-compose docker-compose-v2 docker-doc podman-docker containerd runc | cut -f1)
```

If that command feels too complex, use this simpler version:

```bash
for pkg in docker.io docker-compose docker-compose-v2 docker-doc podman-docker containerd runc; do
  sudo apt remove -y $pkg
done
```

It is okay if apt says some packages are not installed.

---

# 4. Set up Docker’s official APT repository

This is the current official method.

---

## 4.1 Update apt and install required helper packages

```bash
sudo apt update
sudo apt install -y ca-certificates curl
```

---

## 4.2 Create the keyrings directory

```bash
sudo install -m 0755 -d /etc/apt/keyrings
```

---

## 4.3 Add Docker’s official GPG key

```bash
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
```

Make it readable:

```bash
sudo chmod a+r /etc/apt/keyrings/docker.asc
```

---

## 4.4 Add Docker’s repository to APT sources

Run:

```bash
sudo tee /etc/apt/sources.list.d/docker.sources <<EOF
Types: deb
URIs: https://download.docker.com/linux/ubuntu
Suites: $(. /etc/os-release && echo "${UBUNTU_CODENAME:-$VERSION_CODENAME}")
Components: stable
Architectures: $(dpkg --print-architecture)
Signed-By: /etc/apt/keyrings/docker.asc
EOF
```

Then refresh package lists:

```bash
sudo apt update
```

---

## 4.5 If `apt update` fails here

This is very relevant for you.

If your current OS is **Ubuntu 25.04**, Docker may not provide packages for that release anymore.

So `sudo apt update` may fail with errors related to the Docker repo.

If that happens, the proper fix is:

### Best fix
- reinstall to **Ubuntu 24.04 LTS**
- or upgrade to **Ubuntu 25.10**

That is much better than trying to force unsupported package combinations.

---

# 5. Install Docker Engine and Docker Compose plugin

If the repo was added successfully, install Docker with:

```bash
sudo apt install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
```

This installs:

- `docker-ce`
- `docker-ce-cli`
- `containerd.io`
- `docker-buildx-plugin`
- `docker-compose-plugin`

---

# 6. Verify that Docker is installed and running

---

## 6.1 Check Docker service status

```bash
sudo systemctl status docker
```

You want to see it as:
- `active (running)`

If it is not running, start it:

```bash
sudo systemctl start docker
```

Enable it at boot:

```bash
sudo systemctl enable docker
```

Optional but good:

```bash
sudo systemctl enable containerd
```

---

## 6.2 Check installed versions

```bash
docker --version
docker compose version
```

You should see version output for both.

---

## 6.3 Run the test container

```bash
sudo docker run hello-world
```

If successful, Docker will:
- pull the `hello-world` image
- run it
- print a success message

That confirms the engine is working.

---

# 7. Optional: use Docker without sudo

By default, only root can run Docker commands.

If you want your normal user to run Docker without `sudo`:

```bash
sudo usermod -aG docker $USER
```

Apply the new group immediately:

```bash
newgrp docker
```

Then test:

```bash
docker run hello-world
```

If it works, you no longer need `sudo` for Docker commands.

## Security note
Users in the `docker` group effectively have elevated privileges on the host.  
Only add trusted users.

---

# 8. Verify Docker Compose works

Create a small test project.

---

## 8.1 Create a test directory

```bash
mkdir -p ~/compose-test
cd ~/compose-test
```

---

## 8.2 Create a Compose file

```bash
nano compose.yaml
```

Paste:

```yaml
services:
  hello:
    image: hello-world
```

Save and exit.

---

## 8.3 Run it

```bash
docker compose up
```

You should see the hello-world container run successfully.

Then clean up:

```bash
docker compose down
```

---

# 9. Recommended firewall/networking practice on a VPS

This is one of the most important sections.

---

## 9.1 Do not rely on UFW alone for Docker-published ports

If you publish a port in Docker, assume it may be reachable unless you have deliberately designed firewall rules around Docker.

### Bad habit
Publishing everything:

```yaml
ports:
  - "5432:5432"
  - "6379:6379"
  - "8080:8080"
```

That can expose:
- PostgreSQL
- Redis
- admin panels
- internal APIs

to the internet.

---

## 9.2 Good rule of thumb

### Public services
Only publish what should be public:

- `80:80`
- `443:443`

Maybe also:
- `22` for SSH, but that is host-level, not usually in Docker

### Private services
Do **not** publish ports for:
- PostgreSQL
- MySQL
- MariaDB
- Redis
- MongoDB
- admin panels
- internal APIs

Those services can still talk to each other over Docker’s internal network.

---

## 9.3 Local-only access

If you want something reachable only from the VPS itself:

```yaml
ports:
  - "127.0.0.1:3000:3000"
```

That is very useful for:
- admin tools
- dashboards behind a reverse proxy
- databases for SSH tunneling
- development services

---

# 10. Recommended baseline UFW setup

UFW is still useful for host-level protection, just don’t over-trust it for Docker-published ports.

## Allow SSH first
Very important so you do not lock yourself out:

```bash
sudo ufw allow OpenSSH
```

## If you will host websites
Allow:

```bash
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
```

## Enable UFW

```bash
sudo ufw enable
```

## Check status

```bash
sudo ufw status
```

A common simple setup is:
- OpenSSH allowed
- 80 allowed
- 443 allowed

If OVHcloud offers a provider firewall, you can also use that as an extra layer.

---

# 11. Real Compose example: public Nginx container

This is a simple test you can use after install.

---

## 11.1 Create project directory

```bash
mkdir -p ~/nginx-test
cd ~/nginx-test
```

---

## 11.2 Create Compose file

```bash
nano compose.yaml
```

Paste:

```yaml
services:
  web:
    image: nginx:latest
    container_name: nginx-web
    ports:
      - "80:80"
    restart: unless-stopped
```

Save and exit.

---

## 11.3 Start it

```bash
docker compose up -d
```

Check running containers:

```bash
docker ps
```

Open in browser:

```text
http://YOUR_SERVER_IP
```

In your case that would be something like:

```text
http://141.94.36.165
```

You should see the Nginx welcome page.

Stop it with:

```bash
docker compose down
```

---

# 12. Real Compose example: web app + internal database

This is the safer production-style pattern.

```yaml
services:
  web:
    image: nginx:latest
    ports:
      - "80:80"
      - "443:443"
    depends_on:
      - app

  app:
    image: your-app-image
    depends_on:
      - db

  db:
    image: postgres:16
    environment:
      POSTGRES_PASSWORD: strongpassword
    volumes:
      - db_data:/var/lib/postgresql/data

volumes:
  db_data:
```

Notice:

- `web` is public
- `app` is internal
- `db` is internal
- **Postgres is not published**

That is how you should usually structure a VPS deployment.

---

# 13. Useful Docker commands

## Show running containers
```bash
docker ps
```

## Show all containers
```bash
docker ps -a
```

## Show images
```bash
docker images
```

## View logs
```bash
docker logs <container_name>
```

## Follow logs live
```bash
docker logs -f <container_name>
```

## Stop a container
```bash
docker stop <container_name>
```

## Remove unused objects
```bash
docker system prune -a
```

Be careful: this can remove unused images and stopped containers.

## Check Docker disk usage
```bash
docker system df
```

---

# 14. Full copy-paste install block

If you want the current official-style installation in one block, here it is:

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

---

# 15. What to do if you are staying on Ubuntu 25.04 temporarily

If you try the above and get repository errors, that is not surprising.

Because your OS is unsupported, the likely correct path is:

## Option A — best
Reinstall to **Ubuntu 24.04 LTS**

## Option B
Upgrade to **Ubuntu 25.10**

Then repeat the Docker install steps.

For a fresh VPS, I strongly prefer **Option A**.

---

# 16. My final recommendation for your OVH VPS

Given your server specs:

- 4 vCore
- 8 GB RAM
- 75 GB NVMe
- 400 Mbps unmetered

This is a very solid small Docker host.

## Best OS choice
Use:
- **Ubuntu 24.04 LTS**

## Best Docker practice
- install Docker from Docker’s official apt repo
- use `docker compose`, not `docker-compose`
- keep Docker firewall management enabled
- only publish ports you truly want public
- do not expose databases directly
- use Nginx, Caddy, or Traefik as your public reverse proxy

---

# 17. Exact next step I recommend

Because your current Ubuntu release is unsupported, the best next move is:

## Reinstall or upgrade first
Prefer:
- **Ubuntu 24.04 LTS**

Then use the guide above exactly.

---

If you want, I can next give you one of these:

1. a **short version** of this guide with only the commands,
2. a **safe post-install VPS hardening guide** (SSH, UFW, fail2ban, Docker), or
3. a **production Docker Compose starter template** with:
    - reverse proxy
    - app
    - Postgres
    - volumes
    - restart policy.