# Heaven Hotel — secure VPS deployment foundation

**Status: PREPARATION ONLY.** Nothing in this directory is connected to a VPS, deployed by GitHub Actions, or configured with live secrets. It is intentionally inert until reviewed, tested, and explicitly enabled.

## Scope

This is a Docker Compose starter for the existing Java 25 Polaris emulator and MariaDB, not a new game server. Keep the Octane UI/assets on Cloudflare; do not change established Polaris interfaces. Existing Railway remains the production hotel until functional and recovery tests pass.

The existing `Emulator/Dockerfile` uses Java **25**. The sample `POLARIS_IMAGE` must be replaced with a vetted, immutable image digest after a separate CI image-publishing workflow has been reviewed. Do **not** use an arbitrary `latest` release or an image from an untrusted registry.

## What is deliberately blocked

- No database port is published.
- Polaris ports 2096 and 30000 bind to **127.0.0.1 only**; they are *not* publicly accessible. Before cutover, test whether Polaris needs WebSocket proxying, direct TCP access, or both. Configure a minimal reverse proxy/firewall for only the verified ports. Never expose Docker's API.
- This PR contains **no** workflow with SSH privileges, no automatic VPS deployment, and no ability to execute remote commands.
- No production passwords, tokens, SSH keys, database dumps, or screenshots of secrets belong in GitHub.

## Before purchasing/cutover

1. Keep Railway running. Back up its MariaDB logically (dump), store an encrypted copy **off the VPS**, and record source database major version. Do not mount the old database data directory into a different MariaDB version or assume an in-place downgrade is safe.
2. Choose a MariaDB version compatible with the source export, and test restoration with a **disposable** database before touching production.
3. Create an Ubuntu 24.04 VPS; apply security updates, use SSH keys and a non-root admin, disable password SSH login, and configure a firewall for SSH plus only verified public application ports.
4. Install Docker Engine and its Compose plugin from trusted packages. Make a root-owned application directory (e.g. `/opt/heaven-hotel`). Keep service credentials on the VPS in a root-owned, mode-0600 `.env` file. Never log `docker compose config` if it would print interpolated secret values.
5. Publish a reviewed Polaris Docker image from a trusted branch, verify its digest, and test the emulator against a restored database. In `.env`, fill `MARIADB_IMAGE`, `POLARIS_IMAGE`, `DB_DATABASE`, `DB_USERNAME`, `DB_PASSWORD`, and `DB_ROOT_PASSWORD`. Avoid unpinned container tags in production.
6. On the VPS, from the directory holding this Compose file, run `docker compose --env-file .env -f compose.yaml config --quiet` before any rollout. Test with `docker compose --env-file .env -f compose.yaml up -d` **only on the new VPS**, then verify logs, accounts, rooms, catalogue and WebSocket connections.
7. Back up before final cutover; verify a **restore**, not just the existence of a backup. Change traffic only after successful tests. Keep an actionable rollback path to Railway until confident.

## Proposed GitHub management (not installed in this PR)

The eventual production management workflow should use **manual `workflow_dispatch`** on a reviewed trusted branch, GitHub-hosted runners, `contents: read`, separate protected deployment environment secrets, and explicit owner approvals. No `pull_request_target` with secrets. Never execute code from an untrusted PR with deployment credentials.

Provide a dedicated, non-root SSH identity. Have the SSH server **force a root-owned, immutable allowlist command** such as `status`, `restart-polaris`, or `backup-mariadb`, not a shell, file upload, port forwarding, `sudo su`, or general Docker commands. A tightly scoped administrator-installed helper can run only the necessary fixed commands. Restrict access to that helper and avoid giving the SSH account membership of the `docker` group (which effectively provides host-root access).

Future `deploy` and `rollback` must accept only previously reviewed immutable image digests, with manual approval, health check, and fail-safe rollback. Log operation IDs and outcomes while redacting credentials.

### Threat model

| Threat | Required control |
|---|---|
| Public PR modifies workflow or build script | PR checks never receive production secrets; approve production releases |
| GitHub account takeover | MFA/passkeys, branch protection, protected environments and least privilege |
| Leaked SSH key | Dedicated forced-command key; rotate/revoke key; no general shell access |
| Untrusted image update | Exact reviewed digest, supply-chain verification, explicit release approval |
| Exposed DB / Docker daemon | Private database network, no public Docker socket, host firewall |
| Accidental deletion / ransomware | Encrypted offsite backup, tested restoration, limited deployment rights |

**Important limitation:** The GitHub connector available in normal ChatGPT can review files, create branches and PRs, but does not currently expose a workflow-run trigger. Once manual deployment workflows are added, they can be run in GitHub Actions by an authorized user; this chat cannot yet promise a direct restart button without an additional compatible integration.
