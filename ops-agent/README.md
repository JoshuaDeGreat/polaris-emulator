# Heaven Ops Agent: read-only diagnostic prototype

This prototype allows the VPS to retrieve narrowly defined diagnostic requests from GitHub Issues. It never accepts arbitrary shell commands. The first version supports only status, containers, player_api, recovery_logs and recovery_classes.

## Setup notes

- Create a dedicated service account named heaven-ops without interactive login.
- Install agent.py under /opt/heaven-ops and install the helper as root-owned /usr/local/sbin/heaven-ops.
- The service account must not belong to the Docker group. The helper is intended to be the only privileged entry point.
- Configure sudoers with visudo and permit only the five exact helper operations documented above.
- Create a fine-grained GitHub token limited to Issues read/write on this repository. Store it in /etc/heaven-ops/agent.env, owned by root with mode 0600. Never commit the token.
- Install the systemd unit as /etc/systemd/system/heaven-ops-agent.service.
- This is a diagnostic prototype, not a deployment agent. Review and test the files before installation. Do not add arbitrary command execution or mutating operations without separate security review.

## Request format

Create an issue by the configured account and add both labels: heaven-ops-request and heaven-ops-approved. The issue body must be JSON, for example:

    {"operation":"player_api"}

Results are returned as an issue comment. Allowed operations are status, containers, player_api, recovery_logs and recovery_classes.