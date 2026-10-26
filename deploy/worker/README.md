# Worker Deployment

This folder contains the files needed to run the chatbot worker as a Docker container. The worker executes MCP (and built-in) tools on behalf of the server and is required for tool execution.

## Files in this folder

- `Dockerfile`: Dockerfile for the worker module.
- `run-as-agent.c`: source of the privilege-dropping helper that built-in tool commands run through. It is compiled in a builder stage of the Dockerfile; it is not copied into `dist/`.
- `run-as-vault.c`: source of the second privilege-dropping helper, which credential profile targets run through and which also creates the per-attach secret file and empties its tree; the worker removes the emptied directory, because the credential account may only traverse the secret root.
- `privilege_drop.h`: the privilege-drop sequence shared by both helpers, so the security-critical part exists once.
- `torvian-askpass.sh`: root-owned askpass helper handed to `askpass` credential profiles. It holds no secret.
- `start-worker-docker.sh`: startup script used inside the Docker image. It runs the worker as the non-root `torvian` user (via `gosu`) while still being able to write to the mounted config, data, and logs volumes. It also supports the trusted-signer admin mode (see below).
- `docker-compose.example-local.yml`: Docker Compose example for local deployment (server reachable at `http://host.docker.internal:8080`). Sets `init: true` so the container reaps orphaned processes.
- `docker-compose.example-vps.yml`: Docker Compose example for VPS deployment (server reachable via a real domain). Sets `init: true` as well.
- `dist/`: built worker distribution copied here by `deploy/install-worker-dist.ps1` (including `start-worker.sh` / `start-worker.bat`).

## First-time setup

The worker must register with the server before it can run tools. On first run, start it in interactive mode so it can prompt for server URL and credentials and generate its certificate:

```bash
docker compose -f docker-compose.example-vps.yml run -it --rm chatbot-worker
```

After setup completes, start the worker in detached mode:

```bash
docker compose -f docker-compose.example-vps.yml up -d
```

Notes:
- The server must be running before the worker starts.
- An **active** user (or admin) account is required for setup, because the worker authenticates with the server.
- The `PUID`/`PGID` environment variables map the internal `torvian` user to your host user so mounted volumes stay writable. Adjust them to your host UID/GID (`id -u`, `id -g`).

## Command isolation (built-in tools)

Command isolation is **opt-in**: the shipped configuration sets `worker.commandIsolation.enabled = false`, because the mechanism needs a root-owned setuid helper and a separate OS account, which only a prepared host has. While it is off, `run_command` processes run as the worker account and the startup log states that. Enable it where those exist - the shipped image installs both - by setting `enabled` to `true` in `config/application.json` or by starting the worker with `WORKER_COMMAND_ISOLATION_ENABLED=true`.

With it enabled, processes started by the `run_command` tool run as a separate, unprivileged account (`agent`) instead of as the worker account. Ordinary file permissions then keep the worker's secrets out of reach of agent-chosen commands: the child cannot read `config/secrets.json` or `data/token.json`, cannot read the worker's environment through `/proc/<pid>/environ`, and cannot signal or trace the worker process. One exposure remains by design: `/proc/<pid>/cmdline` is world-readable (`0444`), so an isolated command can read the worker's command line (`java -Xmx2G -Xms512M -cp lib/* eu.torvian.chatbot.worker.main.WorkerMain`). Never pass secrets through `JAVA_OPTS` or as entrypoint arguments; supply them through the file source, `config/secrets.json`.

How it works: the image ships a small root-owned setuid helper (`/usr/local/bin/run-as-agent`, mode `4750`, group `torvian`) that drops to `agent` and `exec`s the requested command. While the switch is on, the worker probes it once at startup and logs the result. Nothing else in the container changes: Docker's default capabilities, seccomp profile, namespaces and mounts stay exactly as they are.

### Accounts, groups and permissions

| Path | Owner | Mode | Purpose |
|---|---|---|---|
| `/usr/local/bin/run-as-agent` | `root:torvian` | `4750` | Privilege-dropping helper for commands. |
| `/usr/local/bin/run-as-vault` | `root:torvian` | `4750` | Privilege-dropping helper for credential profile targets. |
| `/usr/local/bin/torvian-askpass` | `root:root` | `0755` | Askpass helper for `askpass` profiles; holds no secret. |
| `/app/config` | `torvian:torvian` | `0700` | Configuration and `secrets.json`. |
| `/app/data` | `torvian:torvian` | `0700` | Token cache and other worker data. |
| `/app/logs` | `torvian:torvian` | `0700` | Logs, which may contain sensitive lines. |
| `/app/workspace` | `torvian:shared` | `2775` | Shared working directory. |
| `/app/cache` and caches below it | `torvian:shared` | `2775` | npm, uv, Gradle, Go caches. |
| `/home/agent` | `agent:shared` | `0755` | Home directory of the isolated account. |
| `/home/vault` | `vault:shared` | `0711` | Home directory of the credential account. |
| `/home/vault/work` | `vault:shared` | `0711` | Working directory of credential profile targets. |
| `/run/torvian/secrets` | `torvian:torvian` | `0711` | One tree per attached profile. The helper creates and empties a tree; the worker removes the emptied directory, which the credential account cannot do here. |

- `torvian` (uid 10001) runs the worker JVM; `agent` (uid 10002) runs spawned commands; `vault` (uid 10004) runs credential profile targets; `shared` (gid 10003) is the group that joins `agent` and `vault`, and it is their **primary** group so files created by commands land in a group the worker can write to.
- Both helpers are `4750` and owned by `root:torvian`, not world-executable: a world-executable `run-as-vault` would let any ordinary session become `vault` and read every loaded secret. The startup script re-applies that ownership on every start, because `PUID`/`PGID` change the `torvian` group id, and it refuses to run when `torvian` and `shared` resolve to the same gid — `agent` could otherwise execute the credential helper through the shared group.
- `AGENT_UID` / `AGENT_GID` optionally remap the isolated account and `VAULT_UID` the credential account, like `PUID`/`PGID` do for the worker. Leave them unset unless your host's uid range collides with 10002/10003/10004. Isolation needs separate uids, so the startup script fails fast when any two accounts resolve to one uid, and the worker itself refuses to start when an account the helper reports carries another account's user id (two account names can share one uid, and a name check alone would accept that).
- The startup script re-applies ownership and modes on every start, because bind mounts and pre-existing named volumes override whatever the image contains. After an upgrade it also repairs the workspace and cache directories once (it prints `Normalizing shared group and modes under ...`), so you never have to delete a volume.

### Configuration

```jsonc
"worker": {
  "commandIsolation": {
    // The shipped value; set it to true where the helper and the isolated account exist.
    "enabled": false,
    "user": "agent",
    "helperPath": "/usr/local/bin/run-as-agent",
    "environment": {
      "allowPassthrough": ["PATH", "LANG", "LC_ALL"],
      "set": {
        "GIT_CONFIG_COUNT": "1",
        "GIT_CONFIG_KEY_0": "safe.directory",
        "GIT_CONFIG_VALUE_0": "*"
      }
    }
  }
}
```

- `enabled` defaults to `false`, so a host that has neither the helper nor the isolated account still runs: commands then use the worker's own account and the worker logs a warning naming that consequence. Set it to `true` where both exist; from then on the worker **refuses to start** (exit 1 with the reason and the opt-out) if the helper is missing or cannot drop privileges, instead of silently running agent-chosen commands with the worker's account. The switch does not require editing `config/application.json`: the environment-mapping layer exposes it as `WORKER_COMMAND_ISOLATION_ENABLED`, so an image whose configuration ships it off can be started with `-e WORKER_COMMAND_ISOLATION_ENABLED=true` instead. `WORKER_COMMAND_ISOLATION_USER` and `WORKER_COMMAND_ISOLATION_HELPER_PATH` map `user` and `helperPath` the same way. These entries come from the shipped `config/env-mapping.json` template; a config directory created by an earlier version keeps its own copy, so add the entries there (or remove only that file and let the worker recreate it) before relying on the variables.
- On Windows and macOS, and in a distribution run directly on a host without the helper, the mechanism does not exist. The setting is ignored, one informational line is logged, and commands run as the worker account with the worker's environment.
- `environment.allowPassthrough` lists the worker environment variables that isolated commands receive. The two names the worker uses for its own credential material - `CHATBOT_WORKER_PRIVATE_KEY_PEM` and `CHATBOT_WORKER_SETUP_PASSWORD` - are refused in both `allowPassthrough` and `set`, because a child running as a different account must not receive them. `environment.set` instead gives the child its own values, whether or not the worker defines them, so a variable can reach commands without being in the worker's process environment at all. Both are applied **only while isolation is active**; `HOME`, `USER` and `LOGNAME` always describe `agent`, so a `set` entry naming one of them has no effect. An empty `set` (`{}`) removes the shipped default rather than adding to it. Neither of the two is reachable through `config/env-mapping.json`, which maps a scalar setting to a variable name, so both are edited in `config/application.json`. Besides `PATH`, `LANG` and `LC_ALL`, `allowPassthrough` forwards names only when the worker defines them.
- The annotated version of the block above is `config/application_example.json`: setup and `--add-trusted-signer` rewrite `config/application.json` and drop every comment in it, so read the example for the full list of options. The worker creates that reference beside `application.json` in a fresh config directory (the shipped image carries the same file), it is never loaded, and a config directory created by an older version has no copy of it.
  - the image's shared caches, if commands should reuse them instead of per-account caches under `/home/agent`: `NPM_CONFIG_CACHE`, `UV_CACHE_DIR`, `NPM_CONFIG_YES`;
  - toolchain cache roots: `GOPATH`, `GOCACHE`, `GOMODCACHE`, `GRADLE_USER_HOME` and any custom ones;
  - `HTTP_PROXY`, `HTTPS_PROXY`, `NO_PROXY` when the deployment reaches the network through a proxy.

### Startup log lines

| Line | Meaning |
|---|---|
| `Command isolation is disabled ...` | WARN; the switch is off, which is the shipped default, and commands run as the worker account. |
| `Command isolation active: ... runs as 'agent' (home /home/agent)` | INFO; the helper works and commands are isolated. |
| `Command isolation does not apply on this platform ...` | INFO; no mechanism exists here. |
| `Worker startup failed: ... CommandIsolationUnavailable(...)` | The host cannot honour setuid, so the worker exits 1. Fix the host or turn the switch off. |

### Notes and limitations

- The helper must be able to honour setuid. Running with `--security-opt no-new-privileges`, `allowPrivilegeEscalation: false` or a dropped capability set makes isolation unavailable when it is enabled, and the worker then fails startup with the reason. Leave the switch off (`enabled = false`, the shipped default) on such hosts, or set `WORKER_COMMAND_ISOLATION_ENABLED=false`; Kubernetes pods that forbid privilege escalation normally keep it off.
- **`git` in the workspace (while isolation is active).** Git refuses a repository whose owner is not the invoking account, and the workspace is owned by `torvian` while commands run as `agent`, so a command using git there needs a trust exemption. The image deliberately sets none: a system-wide `safe.directory` would also cover the worker account, which is the account a repository planted in the agent-writable workspace can attack through git's configuration hooks. The shipped `worker.commandIsolation.environment.set` block supplies the exemption to isolated commands only, by giving the child git's environment configuration (`GIT_CONFIG_COUNT` with `GIT_CONFIG_KEY_0=safe.directory` and `GIT_CONFIG_VALUE_0=*`, git 2.31 or newer); it has no effect while the switch is off, because commands then run as `torvian` and git accepts the workspace for them. Because those values live in `config/application.json` and not in the worker's process environment, they never reach a git the worker itself runs; a config directory from an earlier version has no `set` block and must be given one, and `"set": {}` removes the default. Replace the value with a specific path instead of `*` if you prefer a narrower exemption, or move the decision to the isolated account by giving it a global config (`git config --global --add safe.directory /app/workspace` run as `agent` writes `/home/agent/.gitconfig`, the child's `HOME`); a command can create that file itself, because the account owns its home, and a volume mounted over `/home` must contain it or it has to be re-created after an upgrade.
- **Orphaned processes and the container's init.** A terminated command can leave a descendant whose parent has already exited; the kernel re-parents it to PID 1, and the entrypoint `exec`s the JVM as PID 1, so the JVM never `wait`s for it and the entry stays in the container's PID table as a zombie (`[sh] <defunct>`, state `Z`) until the container is recreated. The compose examples set `init: true`, which puts Docker's init (tini) in front of the entrypoint: it becomes PID 1, reaps those orphans and forwards `SIGTERM` to the JVM. When you start the image with a plain `docker run` instead, pass `--init` for the same effect. This involves no capability, seccomp, namespace or mount option.
- **Custom workspace path.** The startup script normalizes `/app/workspace` and the caches under `/app/cache` only. When `worker.workspace.path` points anywhere else, create that directory yourself as group-writable by `shared` with the setgid bit (`chown torvian:shared` and `chmod 2775`), and do the same for its contents; otherwise commands running as `agent` cannot write in it while the worker still can.
- This is an OS account boundary, not a sandbox. It does not limit CPU, memory, disk or network usage of a command, and it does not change the kernel attack surface.
- Commands that rely on the worker's environment (proxies, cache roots, toolchain variables) need those names in `allowPassthrough`; without them, tools fall back to per-account locations under `/home/agent` and proxied network calls simply time out.
- MCP server processes still run as the worker account (`torvian`) with the worker's environment; only built-in tool commands are isolated.
- **Terminating an isolated command.** With isolation active the worker cannot signal the command directly: the command runs as `agent`, the worker as `torvian` with no capabilities, so terminating a tree after a timeout or a cancellation goes through the helper's signal mode (`run-as-agent --signal TERM|KILL <pgid>`), which drops to `agent` first and signals the command's process group. This adds no capability to the container and needs no extra mount, permission or `security_opt`. Two consequences are worth knowing:
  - The helper binary and the worker must come from the same image build. A worker that resolves isolation against a helper without the signal mode will log a `could not deliver ...` WARN on every timeout or cancellation and leave the command running, so rebuild the image or pull a matching tag before upgrading the worker distribution.
  - The worker logs a WARN whenever a termination signal cannot be delivered. A signal to a process group that no longer exists is normal and is not logged; repeated WARNs mean the helper is missing, replaced, or not executable any more.
- A command that calls `setsid()` creates its own session and leaves the process group, so neither the worker nor the relay can terminate it after the fact. This is the same best-effort limit that applied before isolation was introduced.

## Credential profiles (`terminal_session` attach)

The worker can attach a **credential profile** to an existing `terminal_session`: a server-defined fixed command that runs as the separate `vault` account with a secret loaded into it. The model names a profile only; it never sees the secret, the fixed arguments, or the secret reference.

Availability follows command isolation, with no separate switch: with `commandIsolation.enabled = false` the worker starts normally and `attach` answers `PERMISSION_DENIED`, and on Windows or macOS the same happens because no such account exists. Nothing ever falls back to delivering a secret under the worker's own account.

```jsonc
"worker": {
  "credentials": {
    "vaultUser": "vault",
    "vaultHelperPath": "/usr/local/bin/run-as-vault",
    "askpassScriptPath": "/usr/local/bin/torvian-askpass",
    "secretDir": "/run/torvian/secrets",
    "secretWorkingDirectory": "/home/vault/work",
    "maxSecretBytes": 8192
  }
}
```

All six values are the image defaults, so the block can be omitted; every one is also reachable through `WORKER_CREDENTIALS_*` entries in `config/env-mapping.json`.

- Profiles and secrets are created in the app's Settings, bound to a worker, and kept in worker memory only. The server stores the secret encrypted to the worker certificate; the decryption key is `config/secrets.json`, which therefore protects credential ciphertext as well as the worker identity. Keep it `0600` and back it up like the identity.
- **Rotating the worker certificate makes every bound secret undecryptable**, because the server holds neither the plaintext nor the data key and cannot re-encrypt. `attach` then reports the fingerprint mismatch; re-create each secret with its plaintext after the rotation.
- Mount `/run/torvian` as a tmpfs (`tmpfs: [/run/torvian:mode=0711,uid=10001,gid=10001]`, as the compose examples do) so per-attach secret files cannot survive a restart. Without it they live on the container layer for the profile's lifetime, and the worker's startup sweep removes leftovers.
- The worker refuses to start when `secretDir` is readable or writable by another account, group membership included: the directory must stay traversable (at most `0711`) so an ordinary session cannot list the per-attach trees. A directory the worker creates itself gets that mode; a mounted one keeps whatever the operator gave it. The same startup check refuses a missing or non-executable `askpassScriptPath`, a missing `secretWorkingDirectory`, and a working directory not owned by `vault`.
- Profile targets start in `secretWorkingDirectory`, never in the shared workspace: a command that loads configuration or hooks from its working directory would otherwise execute model-writable files as `vault`. To give a profile a repository, clone it into `/home/vault/work` as `vault`.
- The value is delivered by one of three channels, chosen by the profile: `askpass` (a variable points at `torvian-askpass`, which reads the secret file), `env` (one variable holds the value) or `file` (one variable holds the path of a `0400` file). The profile carries the tool-specific knobs itself — for OpenSSH that is `askpassVariables: ["SSH_ASKPASS"]` plus `environmentVariables: {"SSH_ASKPASS_REQUIRE": "force", "DISPLAY": ":0"}`; the worker applies no default for either. `askpass` and `file` keep the value out of the child's environment, which is the one surface a JVM `String` copy cannot be wiped from; the value itself never reaches a log, a tool result or an error message.
- The image ships no `ssh` or `rsync`; a profile naming them works only in an operator-extended image. `git`, `curl`, `python3`, `node` and `uv` are present.
- There is **no output redaction**: a profile whose fixed command prints its own environment or credential file leaks the value to the model. Choose commands that do not print secrets.
- All profiles share one `vault` uid, so one profile can read another vault process's environment and files. Per-profile accounts are not implemented; treat the fixed command as the boundary it is.
- Stopping the worker is not instant: every live session's processes are signalled and its secret tree removed before the JVM exits, bounded by the shared shutdown budget plus one cleanup grace per step per session, which is about a minute with the default `maxSessions` of 8. Both compose examples set `stop_grace_period: 90s` for that reason; with Docker's 10 s default the hook is killed mid-cleanup and the remaining trees are swept at the next start. A slow shutdown therefore comes from live sessions, not from a hung worker.

The verification commands for an existing deployment are:

```bash
id vault
ls -l /usr/local/bin/run-as-agent /usr/local/bin/run-as-vault   # root:torvian 4750
/usr/local/bin/run-as-vault id -un                              # vault
ls -ld /home/vault /home/vault/work /run/torvian/secrets        # 0711, vault:shared / torvian:torvian
ls -l /app/config/secrets.json                                  # 0600 torvian:torvian
```

An ordinary session must **not** be able to run the credential helper: from inside a `terminal_session`, `/usr/local/bin/run-as-vault id -un` has to answer `Permission denied`.

## Authorizing clients (trusted signers / E2EA)

Tool-execution requests are signed by the client (End-to-End Authorization). The worker only accepts requests from signers it trusts. You register a client's signer credentials in one of two ways.

### Option 1: Trusted-signer admin mode (CLI)

Run the worker once with `--add-trusted-signer`. This adds or replaces a single trusted signer in `application.json` and then exits (the runtime does not start):

```bash
docker compose -f docker-compose.example-vps.yml run --rm chatbot-worker \
  --add-trusted-signer \
  --signer-id=<SIGNER_ID> \
  --public-key-base64=<PUBLIC_KEY> \
  --permissions=mcp:read,mcp:write
```

- `--signer-id` and `--public-key-base64` are required.
- `--permissions` is optional (comma-separated list of permission tokens).
- Re-adding the same signer ID replaces the existing entry.

The client's Signer ID and public key are shown under **Settings → E2EA Security**.

### Option 2: Environment variables

Set the `WORKER_TRUSTED_SIGNER_*` variables in your `docker-compose.yml` (see the commented block in `docker-compose.example-vps.yml`). Each signer occupies a numbered slot:

```yaml
environment:
  - WORKER_TRUSTED_SIGNER_1_ID=client-desktop-7f3a
  - WORKER_TRUSTED_SIGNER_1_PUBLIC_KEY_BASE64=MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcD...
  - WORKER_TRUSTED_SIGNER_1_PERMISSION_1=mcp:read
  - WORKER_TRUSTED_SIGNER_1_PERMISSION_2=mcp:write
```

For the full reference (permissions semantics, `application.json` structure, validation rules, and limitations), see the [Trusted Signers guide](../../docs/user%20guides/Trusted%20Signers%20guide.md).

## Building the image locally (optional)

```bash
.\deploy\install-worker-dist.ps1   # builds dist/ and copies start-worker-docker.sh
cd deploy/worker
docker build -t chatbot-worker:local .
```

The build context is `deploy/worker`: the helper source (`run-as-agent.c`) is compiled in a builder stage and copied into the runtime image, so no compiler ends up in the shipped image.

Then point your `docker-compose.yml` at `chatbot-worker:local` instead of the `ghcr.io` image.

## Upgrading an existing deployment

1. Rebuild or pull the image (`docker compose build --pull` or `docker pull ghcr.io/torvian-eu/chatbot-worker:snapshot`) and recreate the container. `docker compose up` alone does not refresh the base layer of a locally built image. Keep `init: true` in your `docker-compose.yml` (the shipped examples set it): an old copy without it leaves a killed descendant of an isolated command behind as a zombie, because the JVM is PID 1 and cannot reap it.
2. **Decide the switch.** The shipped configuration sets `worker.commandIsolation.enabled = false`, so a fresh container and a container whose mounted `config/application.json` gained that key run commands as `torvian` and log a warning. The image installs everything isolation needs, so the usual upgrade is to turn it on: set `"enabled": true` in `config/application.json`, or pass `WORKER_COMMAND_ISOLATION_ENABLED=true` to the container. A configuration directory created by an earlier version has no key at all; the code default is off, so add the key to state the decision explicitly.
3. Add the environment variables your commands need to `worker.commandIsolation.environment.allowPassthrough` in `config/application.json` (the default is `PATH`, `LANG`, `LC_ALL` only). A variable that is not in the worker's environment but should still reach commands belongs in `worker.commandIsolation.environment.set` instead; a configuration directory created by an earlier version has no `set` block, so add the git exemption (see the `git` note in the notes and limitations) or another entry there if you need one.
4. Check the first start's log lines for the resolved isolation state. Once the switch is on, a host that cannot honour setuid restarts repeatedly; the reason is in `docker logs <container>`.
5. No volume surgery is needed: the startup script repairs existing workspace, cache and `/home` volumes in place on the first start after the upgrade.
