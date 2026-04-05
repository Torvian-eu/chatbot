# Ubuntu VPS Basics Guide for Self-Hosted Docker Apps
### Practical setup and maintenance tips for Linux novices

This guide is for people who rent or own an **Ubuntu VPS** and want to run self-hosted applications with Docker.

It is intentionally **general-purpose**.  
It is **not mainly about the chatbot app itself**. Instead, it explains the basic VPS habits and concepts that help beginners manage a Linux server safely and confidently.

It focuses on:

- first-time VPS access
- basic security
- updates
- firewall setup
- Docker basics
- logs and troubleshooting
- backups
- safe maintenance habits

---

# 1. What a VPS actually is

A VPS (**Virtual Private Server**) is a remote Linux machine running in a datacenter.

You connect to it over the internet using **SSH** and manage it through the command line.

You can think of it as:

- a computer that is always on
- reachable over the internet
- under your control
- usually rented monthly from a provider

Common VPS uses:

- websites
- APIs
- reverse proxies
- self-hosted tools
- mail servers
- databases
- development/test environments

---

# 2. Important mindset for beginners

When working on a VPS, it helps to follow a few simple rules:

## 1. Prefer small, reversible changes
Make one change at a time and test it.

## 2. Keep notes
Write down:
- what you changed
- which files you edited
- which commands you ran
- which domains/ports you used

## 3. Don’t run everything as root
Use a regular sudo-enabled user such as `ubuntu`.

## 4. Back up important data
Especially:
- configuration files
- databases
- uploaded files
- certificates/secrets

## 5. If something works, don’t “clean up” too aggressively
A common beginner mistake is deleting useful files or reinstalling too early.

---

# 3. Basic terminology

## SSH
A secure way to log into your server remotely.

## root
The all-powerful administrator account on Linux.

## sudo
A way for a normal user to run administrative commands.

## package manager (`apt`)
Ubuntu’s tool for installing and updating software.

## service
A background program managed by the system.

## firewall
Rules that control which network traffic is allowed.

## Docker
A way to run applications in isolated containers.

## container
A packaged app runtime environment.

## volume / bind mount
Ways to make data persist outside a container.

---

# 4. Connecting to your VPS

From Windows PowerShell:

```powershell
ssh ubuntu@your-vps-ip
```

If your provider gave you an SSH key:

```powershell
ssh -i C:\path\to\private-key ubuntu@your-vps-ip
```

If you only have root access initially:

```powershell
ssh root@your-vps-ip
```

But long-term, it is better to use a normal sudo-enabled user rather than logging in as root all the time.

---

# 5. The most important Linux commands for beginners

## Show current directory
```bash
pwd
```

## List files
```bash
ls -la
```

## Change directory
```bash
cd /path/to/folder
```

## Go to your home directory
```bash
cd ~
```

## Create a directory
```bash
mkdir -p my-folder
```

## Copy files
```bash
cp source.txt destination.txt
cp -r folder1 folder2
```

## Move or rename files
```bash
mv oldname newname
```

## Delete a file
```bash
rm file.txt
```

## Delete a directory recursively
```bash
rm -rf foldername
```

Be careful with `rm -rf`.  
It deletes files permanently.

## View a text file
```bash
cat filename
less filename
```

## Edit a text file
```bash
nano filename
```

`nano` is beginner-friendly.

---

# 6. Understanding the Linux filesystem

Some common directories:

## `/home`
Home directories for users.

Example:

```text
/home/ubuntu
```

## `/etc`
System configuration files.

## `/var`
Variable data such as logs, caches, web files.

## `/opt`
Optional application directories, often used for manually installed software.

## `/root`
The home directory of the root user.

---

# 7. Updating Ubuntu safely

A basic maintenance habit is to keep the system updated.

## Refresh package index
```bash
sudo apt update
```

## Upgrade installed packages
```bash
sudo apt upgrade -y
```

## Remove unneeded packages
```bash
sudo apt autoremove -y
```

Good beginner habit:
- update regularly
- preferably when you have time to test afterward

---

# 8. Understanding users and sudo

On Ubuntu VPSes, there is often a user like:

```text
ubuntu
```

This user may already have `sudo` rights.

## Run an admin command
```bash
sudo apt update
```

## Open a root shell temporarily
```bash
sudo -i
```

Be careful in a root shell.  
Everything you do has full system access.

For beginners, it is often safer to use `sudo` per command rather than staying as root for a long time.

---

# 9. Firewall basics with UFW

## What UFW is
**UFW** means **Uncomplicated Firewall**.  
It is a simpler interface for Linux firewall rules.

It is still useful on a Docker host, but you need to understand one important caveat:

> Docker can manage iptables rules directly, which means published container ports may not always behave exactly the way UFW beginners expect.

That said, UFW is still very useful as a host-level baseline firewall.

---

## Recommended basic UFW setup

Allow:

- SSH
- HTTP
- HTTPS

Commands:

```bash
sudo ufw allow OpenSSH
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw default deny incoming
sudo ufw default allow outgoing
sudo ufw enable
sudo ufw status
```

---

## Important Docker note

If Docker publishes ports like:

- `80`
- `443`

those are intended to be reachable.

The bigger risk is accidentally publishing extra ports, such as:

- `8080`
- `5432`
- `3306`

So a good beginner rule is:

## Only publish ports you really want public
For many setups:
- only `80` and `443` should be public
- backend/database ports should stay internal

---

# 10. DNS basics

If you want a domain name like:

```text
app.example.com
```

to point to your VPS, you need to create a DNS record at your domain provider.

Usually that means an **A record**:

```text
app.example.com -> your VPS IP
```

If HTTPS is handled automatically by a reverse proxy such as Caddy, DNS must already be correct before certificates can be issued.

Useful check:

```bash
ping app.example.com
```

or from your local machine:

```powershell
nslookup app.example.com
```

---

# 11. Why Docker is popular on VPSes

Docker helps package apps in a repeatable way.

Benefits:

- fewer host-level dependencies
- easier deployment
- easier removal
- cleaner separation between apps
- easier upgrades and rollbacks

This is especially useful when the VPS may later run multiple services.

---

# 12. Docker basics for beginners

## Install Docker on Ubuntu

Copy-paste this step-by-step installation process:
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

## Optional: allow your user to run Docker without sudo

```bash
sudo usermod -aG docker $USER
```

Then log out and log in again.

If you don’t do this, use `sudo docker ...`.

---

# 13. The most important Docker concepts

## Image
A packaged application blueprint.

## Container
A running instance of an image.

## Compose
A way to define and run multi-container apps with a YAML file.

## Network
How containers communicate with each other.

## Volume
Persistent data managed by Docker.

## Bind mount
A direct mapping from a host folder into a container.

---

# 14. Safe Docker habits for beginners

## 1. Prefer bind mounts or volumes for important data
If data only exists inside a container filesystem, it may disappear when the container is replaced.

## 2. Do not publish unnecessary ports
Avoid exposing backend/admin/database ports unless you really need them.

## 3. Use `docker compose logs`
Logs are your friend.

## 4. Use `docker compose ps`
Quick way to see what is running.

## 5. Keep deployment files organized
For example:

```text
/home/ubuntu/myapp/deploy
```

---

# 15. Useful Docker commands

## List running containers
```bash
docker ps
```

## List all containers
```bash
docker ps -a
```

## List images
```bash
docker images
```

## Show Compose services
```bash
docker compose ps
```

## Start services
```bash
docker compose up -d
```

## Rebuild and start
```bash
docker compose up -d --build
```

## Stop and remove containers
```bash
docker compose down
```

## View logs
```bash
docker compose logs
docker compose logs -f
docker compose logs -f service-name
```

## Follow one service’s logs
```bash
docker compose logs -f caddy
```

---

# 16. Docker networks in plain language

Containers often need to talk to each other.

Example:
- reverse proxy container
- backend app container

If they are on the same Docker network, one can often reach the other by service name.

Example:

```text
reverse_proxy backend:8080
```

When using multiple separate Compose files, you may need to manually create a shared external network:

```bash
docker network create my-shared-network
```

This is a common pattern.

---

# 17. Basic reverse proxy concept

A reverse proxy sits in front of your app.

It can handle:

- HTTPS
- domain routing
- static file serving
- forwarding requests to backend apps

Popular reverse proxies:
- Caddy
- Nginx
- Traefik

Caddy is beginner-friendly because it automatically handles TLS certificates.

---

# 18. Logs and troubleshooting

When something fails, start with logs.

## System logs
For systemd-managed services:

```bash
journalctl -xe
```

## Docker logs
```bash
docker compose logs -f
```

## Disk space
```bash
df -h
```

## Memory/CPU
```bash
top
```

or:

```bash
htop
```

(if installed)

## Open ports
```bash
sudo ss -tulpn
```

---

# 19. The most common beginner problems

## 1. DNS is wrong
The domain does not point to the VPS.

## 2. Ports are already in use
Another service is already listening on `80` or `443`.

## 3. Wrong file path in a bind mount
The container expects files in one place, but they are elsewhere on the host.

## 4. Permissions problem
The app tries to write to a directory it cannot access.

## 5. The service is running, but not reachable
Often caused by:
- wrong port
- wrong firewall rule
- wrong reverse proxy config
- app bound to localhost instead of all interfaces

---

# 20. Backups: what beginners should back up

At minimum, back up:

- deployment files
- application config
- databases
- uploaded assets
- TLS/certificate data if applicable
- secrets

For Dockerized apps, often important folders are:
- bind-mounted `config/`
- bind-mounted `data/`
- bind-mounted `logs/` if relevant
- Docker volumes containing certificates or state

A very basic approach is to copy important directories periodically to another machine or storage provider.

---

# 21. Good maintenance habits

## Weekly or regular checks
```bash
sudo apt update
sudo apt upgrade -y
docker ps
df -h
```

## After app changes
- restart affected services
- check logs
- test the public URL

## After changing DNS or TLS config
- wait a little
- check proxy logs
- test again

## Before making big changes
- back up config/data
- note current working state

---

# 22. Security basics for beginners

## Use SSH keys if possible
Password login is weaker than key-based login.

## Avoid logging in as root all the time
Use a normal sudo-enabled user.

## Keep the system updated
Especially security updates.

## Don’t expose internal ports
Databases and backend ports usually shouldn’t be public.

## Don’t store secrets carelessly
Avoid putting private secrets in public repos or screenshots.

## Remove software you don’t use
Less software means less attack surface.

---

# 23. Editing files safely

A simple safe workflow:

1. make a backup first
2. edit the file
3. save
4. validate if possible
5. restart/reload the affected service
6. check logs

Example:

```bash
cp Caddyfile Caddyfile.bak
nano Caddyfile
docker compose up -d
docker compose logs -f caddy
```

---

# 24. A simple beginner-friendly deployment workflow

For many self-hosted apps, a sane process is:

1. prepare deployment files locally
2. upload to VPS
3. SSH into VPS
4. start or rebuild containers
5. check logs
6. test in browser

This is much safer than editing live production files blindly on the server.

---

# 25. Useful file transfer options from Windows

## SCP
```powershell
scp -r .\deploy ubuntu@your-vps-ip:/home/ubuntu/myapp/
```

## SFTP GUI tools
Tools like WinSCP can be more comfortable for beginners.

---

# 26. What not to panic about

Beginners often worry when they see:

- warnings in logs
- a container restarting once
- certificates taking a minute to issue
- Docker creating many networks/volumes

Not every warning is fatal.

Focus on:
- whether the app is reachable
- whether logs show clear errors
- whether data persists correctly

---

# 27. What to do when stuck

A good troubleshooting order:

1. check the exact error message
2. check logs
3. verify file paths
4. verify DNS
5. verify ports
6. verify firewall
7. verify container status
8. only then start changing configs

Randomly changing many things at once usually makes debugging harder.

---

# 28. Helpful commands reference

## System
```bash
pwd
ls -la
cd /path
nano file.txt
cat file.txt
cp file.txt file.bak
mv old new
rm file.txt
```

## Updates
```bash
sudo apt update
sudo apt upgrade -y
sudo apt autoremove -y
```

## Firewall
```bash
sudo ufw status
sudo ufw allow OpenSSH
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
```

## Networking
```bash
ping domain.example
nslookup domain.example
sudo ss -tulpn
```

## Docker
```bash
docker ps
docker images
docker compose ps
docker compose up -d
docker compose up -d --build
docker compose down
docker compose logs -f
docker network ls
```

---

# 29. Final practical advice

If you are new to Linux and VPS hosting:

- keep things simple
- expose as few ports as possible
- use Docker to isolate apps
- use a reverse proxy for HTTPS
- update regularly
- back up important data
- do one change at a time
- keep notes

You do **not** need to become a Linux expert immediately to run a small VPS responsibly.

Good habits matter more than knowing every command.

---

If you want, I can also turn this into:
1. a **Markdown file** for your docs
2. a version with a short **“recommended first-day checklist”**
3. a version with a small **appendix specifically about Docker + UFW caveats**