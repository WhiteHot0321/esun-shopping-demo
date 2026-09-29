#!/usr/bin/env bash
# Phase 3.3 #18 B3 — one-time VM bootstrap for esun-shopping.
#
# Run this ONCE as root (or via sudo) on a fresh Ubuntu 22.04/24.04 LTS VM, before B3's
# actual deploy pipeline exists. It only prepares the host: Docker, a non-root deploy user,
# the firewall, and basic SSH hardening. It does NOT start the application stack, does NOT
# create any .env.prod, and does NOT touch GitHub — that is all separate, later work.
#
# Usage (from your own machine, not the VM):
#   scp scripts/vm-bootstrap.sh root@<VM_IP>:/root/
#   ssh root@<VM_IP> 'bash /root/vm-bootstrap.sh'
# or paste the file contents into your VM provider's cloud-init/user-data "run once" field.
#
# Safe to re-run: every step below is idempotent (checks before creating/changing anything).

set -euo pipefail

DEPLOY_USER="deploy"
REPO_URL="https://github.com/WhiteHot0321/esun-shopping-demo.git"
REPO_DIR="/opt/esun-shopping"

# Deploy key's PUBLIC half only (safe to embed — this is not a secret). The matching
# private key stays on the operator's own machine at ~/.ssh/esun_shop_deploy and is never
# copied here or committed to the repo.
DEPLOY_PUBKEY="ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAII5BcEaNU3+xs11/9CSXEiI49nZPhlTar5Q9/J7SkQyt esun-shopping-deploy"

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

echo "==> Cloning the repository into $REPO_DIR"
if [[ ! -d "$REPO_DIR/.git" ]]; then
  git clone "$REPO_URL" "$REPO_DIR"
  chown -R "$DEPLOY_USER:$DEPLOY_USER" "$REPO_DIR"
else
  echo "    $REPO_DIR already exists, skipping clone"
fi

cat <<EOF

==> Bootstrap done. Next steps (manual, on your own machine unless noted):
  1. Verify: ssh -i ~/.ssh/esun_shop_deploy $DEPLOY_USER@<VM_IP>
  2. On the VM: docker --version && docker compose version && sudo ufw status
  3. Point DuckDNS (whitehot0321.duckdns.org) at this VM's public IP.
  4. On the VM, in $REPO_DIR: copy .env.prod.example to .env.prod and fill in real
     values (never commit .env.prod). Real secrets are generated/entered on the VM itself,
     not on your local machine and not in chat.
  5. Only after that: docker compose -f docker-compose.prod.yml --env-file .env.prod up -d
     as a manual smoke test — the actual B3 deploy pipeline (GitHub Actions + Environment
     approval + backup + health gate + rollback) is separate, later work.
EOF
