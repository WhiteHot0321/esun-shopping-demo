#!/usr/bin/env bash
# Phase 3.3 #18 B3 — one-time VM bootstrap for esun-shopping.
#
# Run this ONCE as root (or via sudo) on a fresh Ubuntu 22.04/24.04 LTS VM. It prepares the host: Docker, a non-root
# deploy user, the firewall, basic SSH hardening, the deploy checkout, and the restricted key the GitHub Actions deploy
# job logs in with (see docs/tasks/075). It does NOT start the application stack, does NOT create or fill .env.prod,
# and does NOT touch GitHub.
#
# Usage (from your own machine, not the VM):
#   scp scripts/vm-bootstrap.sh root@<VM_IP>:/root/
#   ssh root@<VM_IP> 'ACTIONS_DEPLOY_PUBKEY="ssh-ed25519 AAAA... esun-actions-deploy" bash /root/vm-bootstrap.sh'
# or paste the file contents into your VM provider's cloud-init/user-data "run once" field.
#
# Safe to re-run: every step below is idempotent (checks before creating/changing anything).
# Re-run it after scripts/esun-deploy-entry changes in the repo, because the root-owned copy is only refreshed here.

set -euo pipefail

DEPLOY_USER="deploy"
REPO_URL="https://github.com/WhiteHot0321/esun-shopping-demo.git"   # must be public: the VM clones and fetches without credentials
REPO_BRANCH="advanced-v2"                                            # the only branch the pipeline deploys (main has no prod profile)
REPO_DIR="/opt/esun-shopping"
STATE_DIR="/var/lib/esun-deploy"
ENTRY_SRC="$REPO_DIR/scripts/esun-deploy-entry"
ENTRY_DST="/usr/local/sbin/esun-deploy-entry"

# The operator's own login key: its PUBLIC half only (safe to embed — this is not a secret). The matching private key stays
# on the operator's machine at ~/.ssh/esun_shop_deploy and is NOT stored in GitHub.
DEPLOY_PUBKEY="ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAII5BcEaNU3+xs11/9CSXEiI49nZPhlTar5Q9/J7SkQyt esun-shopping-deploy"

# The GitHub Actions key is a different, dedicated key (create it with: ssh-keygen -t ed25519 -f esun_actions_deploy -C esun-actions-deploy).
# Pass its PUBLIC half in ACTIONS_DEPLOY_PUBKEY; the private half goes only into the GitHub Environment secret DEPLOY_SSH_KEY.
# It is installed with `restrict,command=` so it can do nothing except run the deploy entry point.
ACTIONS_DEPLOY_PUBKEY="${ACTIONS_DEPLOY_PUBKEY:-}"

if [[ "$(id -u)" -ne 0 ]]; then
  echo "Run this as root (or with sudo)." >&2
  exit 1
fi

echo "==> Updating apt and installing prerequisites"
apt-get update -y
apt-get install -y ca-certificates curl gnupg ufw git

echo "==> Installing Docker Engine + Compose plugin (official Docker apt repo)"
if ! command -v docker >/dev/null 2>&1; then
  install -m 0755 -d /etc/apt/keyrings
  curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
  chmod a+r /etc/apt/keyrings/docker.asc
  . /etc/os-release
  echo \
    "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu ${VERSION_CODENAME} stable" \
    > /etc/apt/sources.list.d/docker.list
  apt-get update -y
  apt-get install -y docker-ce docker-ce-cli containerd.io docker-compose-plugin
else
  echo "    docker already installed, skipping"
fi

echo "==> Creating non-root deploy user"
if ! id -u "$DEPLOY_USER" >/dev/null 2>&1; then
  adduser --disabled-password --gecos "" "$DEPLOY_USER"
fi
usermod -aG docker "$DEPLOY_USER"

echo "==> Installing the deploy public key for $DEPLOY_USER"
DEPLOY_HOME="/home/$DEPLOY_USER"
install -d -m 700 -o "$DEPLOY_USER" -g "$DEPLOY_USER" "$DEPLOY_HOME/.ssh"
AUTH_KEYS="$DEPLOY_HOME/.ssh/authorized_keys"
touch "$AUTH_KEYS"
grep -qxF "$DEPLOY_PUBKEY" "$AUTH_KEYS" || echo "$DEPLOY_PUBKEY" >> "$AUTH_KEYS"
chmod 600 "$AUTH_KEYS"
chown "$DEPLOY_USER:$DEPLOY_USER" "$AUTH_KEYS"

echo "==> Configuring firewall (22/80/443 only)"
ufw allow OpenSSH
ufw allow 80/tcp
ufw allow 443/tcp
ufw --force enable

echo "==> Hardening SSH (key-only, no root login)"
SSHD_CONFIG="/etc/ssh/sshd_config"
sed -i 's/^#\?PasswordAuthentication.*/PasswordAuthentication no/' "$SSHD_CONFIG"
sed -i 's/^#\?PermitRootLogin.*/PermitRootLogin no/' "$SSHD_CONFIG"
systemctl reload ssh || systemctl reload sshd

echo "==> Cloning $REPO_BRANCH into $REPO_DIR"
if [[ ! -d "$REPO_DIR/.git" ]]; then
  # No prompt: a private repository must fail here instead of hanging, because the pipeline fetches without credentials.
  GIT_TERMINAL_PROMPT=0 git clone --branch "$REPO_BRANCH" "$REPO_URL" "$REPO_DIR" \
    || { echo "Clone of $REPO_URL ($REPO_BRANCH) failed. The repository must be public and the branch must exist." >&2; exit 1; }
  chown -R "$DEPLOY_USER:$DEPLOY_USER" "$REPO_DIR"
else
  echo "    $REPO_DIR already exists, skipping clone"
fi

echo "==> Preparing deploy state, backups and secret-file permissions"
install -d -m 700 -o "$DEPLOY_USER" -g "$DEPLOY_USER" "$STATE_DIR"
install -d -m 700 -o "$DEPLOY_USER" -g "$DEPLOY_USER" "$REPO_DIR/backups"
# These two hold secrets and are created by you on the VM, never by this script; if they already exist, lock them down.
for secret_file in "$REPO_DIR/.env.prod" "$REPO_DIR/.backup-passphrase"; do
  if [[ -e "$secret_file" ]]; then
    chown "$DEPLOY_USER:$DEPLOY_USER" "$secret_file"
    chmod 600 "$secret_file"
    echo "    $(basename "$secret_file"): owner $DEPLOY_USER, mode 600"
  fi
done

echo "==> Installing the deploy entry point (root-owned, not writable by $DEPLOY_USER)"
if [[ ! -f "$ENTRY_SRC" ]]; then
  echo "$ENTRY_SRC is missing: the checkout is not on a branch that contains the deploy pipeline. Update it (git -C $REPO_DIR pull) and re-run." >&2
  exit 1
fi
install -m 0755 -o root -g root "$ENTRY_SRC" "$ENTRY_DST"

echo "==> Installing the restricted GitHub Actions key"
ACTIONS_KEY_INSTALLED=no
if [[ -z "$ACTIONS_DEPLOY_PUBKEY" ]]; then
  echo "    ACTIONS_DEPLOY_PUBKEY is not set: skipped. Re-run with it set before the first pipeline deploy."
else
  # One bare ed25519 public key, nothing else: no leading options (that is where an attacker would smuggle command=/permitopen=).
  key_re='^ssh-ed25519 AAAA[0-9A-Za-z+/]+=*( [A-Za-z0-9._@-]+)?$'
  if [[ ! "$ACTIONS_DEPLOY_PUBKEY" =~ $key_re ]]; then
    echo "ACTIONS_DEPLOY_PUBKEY must be a single 'ssh-ed25519 AAAA... [comment]' public key line." >&2
    exit 1
  fi
  actions_blob="$(awk '{print $2}' <<<"$ACTIONS_DEPLOY_PUBKEY")"
  if [[ "$actions_blob" == "$(awk '{print $2}' <<<"$DEPLOY_PUBKEY")" ]]; then
    echo "ACTIONS_DEPLOY_PUBKEY is the operator login key; the pipeline needs its own dedicated key." >&2
    exit 1
  fi
  # Replace any earlier line carrying this key (e.g. one installed without restrictions), then add the restricted form.
  { grep -vF "$actions_blob" "$AUTH_KEYS" || true; } > "$AUTH_KEYS.new"
  echo "restrict,command=\"$ENTRY_DST\" $ACTIONS_DEPLOY_PUBKEY" >> "$AUTH_KEYS.new"
  mv "$AUTH_KEYS.new" "$AUTH_KEYS"
  chmod 600 "$AUTH_KEYS"
  chown "$DEPLOY_USER:$DEPLOY_USER" "$AUTH_KEYS"
  ACTIONS_KEY_INSTALLED=yes
  echo "    installed (restrict, forced command $ENTRY_DST)"
fi

cat <<EOF

==> Bootstrap done. Next steps (manual, on your own machine unless noted):
  1. Verify the operator login: ssh -i ~/.ssh/esun_shop_deploy $DEPLOY_USER@<VM_IP>
  2. On the VM: docker --version && docker compose version && sudo ufw status
  3. Point DuckDNS (whitehot0321.duckdns.org) at this VM's public IP.
  4. On the VM, in $REPO_DIR: copy .env.prod.example to .env.prod, fill in real values, chmod 600 it, and set
     DOMAIN to the real hostname. Also create .backup-passphrase (mode 600) and keep a copy off the VM: without it the
     encrypted backups cannot be restored. Secrets are entered on the VM itself, not on your machine and not in chat.
     Application secrets live ONLY there; GitHub holds just DEPLOY_SSH_KEY, DEPLOY_HOST and DEPLOY_KNOWN_HOSTS.
  5. Get the VM's host key out of band (from the provider console, or from the VM: ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub)
     and store the matching known_hosts line in the GitHub Environment variable DEPLOY_KNOWN_HOSTS.
  6. Make sure the GHCR packages (backend, frontend) are public, then run the deploy workflow (workflow_dispatch on advanced-v2).
EOF
if [[ "$ACTIONS_KEY_INSTALLED" = no ]]; then
  echo "  !! The restricted Actions key is NOT installed yet: re-run this script with ACTIONS_DEPLOY_PUBKEY set before step 6."
fi
