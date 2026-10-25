#!/usr/bin/env bash
# Server bootstrap for the Google Cloud Free Tier VM (e2-micro, 1 GB RAM, Ubuntu 24.04 LTS, amd64). Idempotent: safe to re-run.
# Run as root on the VM, through the login user that GCP creates from the SSH key comment (`masroof-deploy`):
#   ssh masroof-deploy@<ip> 'sudo DOMAIN=<name>.duckdns.org DUCKDNS_SUBDOMAIN=<name> bash -s' < infra/host/bootstrap.sh
# Secrets never travel on a command line: put the DuckDNS token in place first, see docs/HOSTING.md
#   ssh masroof-deploy@<ip> 'sudo install -d -m 700 /etc/masroof && sudo install -m 600 /dev/stdin /etc/masroof/duckdns.env' <<< "DUCKDNS_TOKEN=..."
#
# Env (all optional): DOMAIN, DUCKDNS_SUBDOMAIN, DEPLOY_PUBKEY (extra key to authorize), DEPLOY_USER (default masroof-deploy),
# SWAP_SIZE_MB (default 2048).
# Does: Docker + compose plugin, swapfile, ufw (22/80/443), unattended-upgrades, fail2ban, deploy user + dirs,
# DuckDNS updater (cron), nightly encrypted pg_dump + media backup (7 days, disk-aware), and a generated
# server-side .env for the stack (random secrets, mode 600, never printed, never in the repo).
set -euo pipefail

[ "$(id -u)" -eq 0 ] || { echo "bootstrap: run as root (sudo)" >&2; exit 1; }
say() { printf 'bootstrap: %s\n' "$*"; }

DEPLOY_USER="${DEPLOY_USER:-masroof-deploy}"
DOMAIN="${DOMAIN:-}"
DUCKDNS_SUBDOMAIN="${DUCKDNS_SUBDOMAIN:-}"
SWAP_SIZE_MB="${SWAP_SIZE_MB:-2048}"
CONF=/etc/masroof
APP_DIR="/home/${DEPLOY_USER}/masroof/infra"
export DEBIAN_FRONTEND=noninteractive

[[ "$DEPLOY_USER" =~ ^[a-z_][a-z0-9_-]{0,31}$ ]] || { echo "bootstrap: invalid DEPLOY_USER" >&2; exit 1; }

# ---------- patch + packages ----------
say "patching and installing packages"
export DEBIAN_FRONTEND=noninteractive
APT_OPTS=(-y -qq -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold)
# </dev/null: this script arrives on stdin (ssh ... bash -s), so apt must never read it as answers.
apt-get update -qq </dev/null
apt-get upgrade "${APT_OPTS[@]}" >/dev/null </dev/null
apt-get install "${APT_OPTS[@]}" docker.io docker-compose-v2 ufw fail2ban unattended-upgrades curl gnupg openssl cron ca-certificates >/dev/null </dev/null

# ---------- swap (1 GB RAM: a 2 GB swapfile absorbs JVM/Postgres spikes instead of the OOM killer) ----------
if ! swapon --show=NAME --noheadings | grep -q .; then
  say "creating ${SWAP_SIZE_MB} MB swapfile /swapfile"
  if [ ! -f /swapfile ]; then
    fallocate -l "${SWAP_SIZE_MB}M" /swapfile 2>/dev/null || dd if=/dev/zero of=/swapfile bs=1M count="$SWAP_SIZE_MB" status=none
    chmod 600 /swapfile
    mkswap /swapfile >/dev/null
  fi
  swapon /swapfile
fi
grep -q '^/swapfile ' /etc/fstab || printf '/swapfile none swap sw 0 0\n' >> /etc/fstab
cat > /etc/sysctl.d/99-masroof.conf <<'EOF'
vm.swappiness = 10
vm.vfs_cache_pressure = 50
EOF
sysctl -q -p /etc/sysctl.d/99-masroof.conf

# ---------- docker: bounded logs (30 GB disk), journald cap ----------
install -d /etc/docker
if [ ! -f /etc/docker/daemon.json ]; then
  cat > /etc/docker/daemon.json <<'EOF'
{
  "log-driver": "json-file",
  "log-opts": { "max-size": "10m", "max-file": "3" }
}
EOF
  systemctl restart docker
fi
install -d /etc/systemd/journald.conf.d
cat > /etc/systemd/journald.conf.d/99-masroof.conf <<'EOF'
[Journal]
SystemMaxUse=100M
EOF
systemctl restart systemd-journald

# ---------- deploy user ----------
# On GCP the login user already exists (created by the guest agent from the SSH key comment) and keeps its key in
# ~/.ssh/authorized_keys, managed by the agent. We never touch that file unless an extra DEPLOY_PUBKEY is passed.
if ! id "$DEPLOY_USER" >/dev/null 2>&1; then
  say "creating user $DEPLOY_USER"
  adduser --disabled-password --gecos "" "$DEPLOY_USER" >/dev/null
fi
usermod -aG docker "$DEPLOY_USER"
# the deploy user may trigger the (root-owned) backup job without a password even where it has no general sudo
printf '%s ALL=(root) NOPASSWD: /usr/local/sbin/masroof-backup\n' "$DEPLOY_USER" > /etc/sudoers.d/masroof-backup
chmod 440 /etc/sudoers.d/masroof-backup
visudo -cf /etc/sudoers.d/masroof-backup >/dev/null
install -d -m 700 -o "$DEPLOY_USER" -g "$DEPLOY_USER" "/home/${DEPLOY_USER}/.ssh"
auth="/home/${DEPLOY_USER}/.ssh/authorized_keys"
if [ -n "${DEPLOY_PUBKEY:-}" ]; then
  touch "$auth"
  grep -qxF "$DEPLOY_PUBKEY" "$auth" || printf '%s\n' "$DEPLOY_PUBKEY" >> "$auth"
  chown "$DEPLOY_USER:$DEPLOY_USER" "$auth"; chmod 600 "$auth"
fi

# ---------- ssh hardening ----------
cat > /etc/ssh/sshd_config.d/99-masroof.conf <<'EOF'
PasswordAuthentication no
PermitRootLogin no
EOF
sshd -t && systemctl reload ssh

# ---------- firewall (the GCP firewall must allow 80/443 too: tick the HTTP/HTTPS boxes on the VM, docs/HOSTING.md) ----------
ufw default deny incoming >/dev/null
ufw default allow outgoing >/dev/null
ufw allow 22/tcp >/dev/null
ufw allow 80/tcp >/dev/null
ufw allow 443/tcp >/dev/null
ufw --force enable >/dev/null
say "ufw active: 22, 80, 443"

# ---------- unattended upgrades + fail2ban ----------
cat > /etc/apt/apt.conf.d/20auto-upgrades <<'EOF'
APT::Periodic::Update-Package-Lists "1";
APT::Periodic::Unattended-Upgrade "1";
APT::Periodic::AutocleanInterval "7";
EOF
cat > /etc/fail2ban/jail.d/masroof-sshd.local <<'EOF'
[sshd]
enabled = true
maxretry = 5
findtime = 10m
bantime = 1h
EOF
systemctl enable --now fail2ban >/dev/null 2>&1
systemctl restart fail2ban

# ---------- config dir ----------
install -d -m 700 "$CONF"
if [ ! -s "$CONF/backup.pass" ]; then
  umask 077
  openssl rand -hex 32 > "$CONF/backup.pass"
  say "generated $CONF/backup.pass (backup encryption passphrase): copy it somewhere safe, backups are unreadable without it"
fi

# ---------- stack env (server-side secrets, generated here once, never printed, never in the repo) ----------
install -d -o "$DEPLOY_USER" -g "$DEPLOY_USER" "/home/${DEPLOY_USER}/masroof" "$APP_DIR" "$APP_DIR/.deployed"
envf="$APP_DIR/.env"
if [ ! -f "$envf" ]; then
  umask 077
  {
    printf 'POSTGRES_PASSWORD=%s\n' "$(openssl rand -hex 24)"
    printf 'S3_ACCESS_KEY=%s\n' "masroof$(openssl rand -hex 4)"
    printf 'S3_SECRET_KEY=%s\n' "$(openssl rand -hex 24)"
    printf 'MASROOF_JWT_SECRET=%s\n' "$(openssl rand -base64 48 | tr -d '\n')"
    printf 'MASROOF_AUDIT_KEY=%s\n' "$(openssl rand -base64 48 | tr -d '\n')"
    printf 'DOMAIN=\n'
  } > "$envf"
  chown "$DEPLOY_USER:$DEPLOY_USER" "$envf"; chmod 600 "$envf"
  say "generated $envf"
fi
# Independent of each other so rotating one never changes the other's derived values. Never overwritten once present.
for k in MASROOF_JWT_SECRET MASROOF_AUDIT_KEY; do
  if ! grep -q "^${k}=." "$envf"; then
    sed -i "/^${k}=/d" "$envf"
    printf '%s=%s\n' "$k" "$(openssl rand -base64 48 | tr -d '\n')" >> "$envf"
    say "generated $k in $envf"
  fi
done
if [ -n "$DOMAIN" ]; then
  if grep -q '^DOMAIN=' "$envf"; then sed -i "s|^DOMAIN=.*|DOMAIN=${DOMAIN}|" "$envf"; else printf 'DOMAIN=%s\n' "$DOMAIN" >> "$envf"; fi
fi

# ---------- DuckDNS updater ----------
cat > /usr/local/sbin/masroof-duckdns <<'EOF'
#!/usr/bin/env bash
# Keeps <sub>.duckdns.org pointing at this VM (the ephemeral external IP changes when the VM is stopped and started).
# Token comes from the root-only /etc/masroof/duckdns.env; never logged.
set -euo pipefail
f=/etc/masroof/duckdns.env
[ -r "$f" ] || { echo "masroof-duckdns: $f missing" >&2; exit 1; }
token="$(grep -E '^DUCKDNS_TOKEN=' "$f" | head -n 1 | cut -d= -f2-)"
sub="$(grep -E '^DUCKDNS_SUBDOMAIN=' "$f" | head -n 1 | cut -d= -f2-)"
[ -n "$token" ] && [ -n "$sub" ] || { echo "masroof-duckdns: DUCKDNS_TOKEN/DUCKDNS_SUBDOMAIN not set in $f" >&2; exit 1; }
[[ "$token" =~ ^[A-Za-z0-9-]+$ ]] && [[ "$sub" =~ ^[A-Za-z0-9-]+$ ]] || { echo "masroof-duckdns: unexpected characters in token/subdomain" >&2; exit 1; }
# The token goes to curl through a config file on stdin (printf is a builtin), so it never appears in any argv/ps.
res="$(printf 'url = "https://www.duckdns.org/update?domains=%s&token=%s&ip="\n' "$sub" "$token" | curl -fsS --max-time 20 -K - 2>/dev/null || true)"
[ "$res" = "OK" ] || { echo "masroof-duckdns: update failed" >&2; exit 1; }
EOF
chmod 755 /usr/local/sbin/masroof-duckdns
if [ -n "$DUCKDNS_SUBDOMAIN" ] && [ -f "$CONF/duckdns.env" ]; then
  sed -i '/^DUCKDNS_SUBDOMAIN=/d' "$CONF/duckdns.env"
  printf 'DUCKDNS_SUBDOMAIN=%s\n' "$DUCKDNS_SUBDOMAIN" >> "$CONF/duckdns.env"
fi
if [ -f "$CONF/duckdns.env" ]; then
  chmod 600 "$CONF/duckdns.env"
  printf '*/5 * * * * root /usr/local/sbin/masroof-duckdns\n' > /etc/cron.d/masroof-duckdns
  if /usr/local/sbin/masroof-duckdns; then say "DuckDNS updated"; else say "WARNING: DuckDNS update failed (check token/subdomain)"; fi
else
  say "no $CONF/duckdns.env yet: DuckDNS updater not scheduled (see docs/HOSTING.md)"
fi

# ---------- nightly backup (pg_dump + MinIO media, gpg-encrypted, 7 days, disk-aware) ----------
cat > /usr/local/sbin/masroof-backup <<'EOF'
#!/usr/bin/env bash
# Nightly Postgres dump + MinIO media tar, AES-256 symmetric gpg with /etc/masroof/backup.pass, 7-day retention in
# /var/backups/masroof. The 30 GB boot disk is shared with Docker, so: refuse to start below MIN_FREE_MB free,
# verify every file decrypts and decompresses before keeping it, and drop dangling Docker layers afterwards.
set -euo pipefail
dir=/var/backups/masroof
keep_days="${BACKUP_KEEP_DAYS:-7}"
min_free_mb="${MIN_FREE_MB:-3072}"
install -d -m 700 "$dir"
free_mb="$(df -Pm "$dir" | awk 'NR==2 {print $4}')"
if [ "$free_mb" -lt "$min_free_mb" ]; then
  # make room by dropping everything but the newest dump of each kind, then re-check
  for pat in 'masroof-2*.sql.gz.gpg' 'masroof-media-*.tar.gz.gpg'; do
    find "$dir" -maxdepth 1 -name "$pat" -printf '%T@ %p\n' | sort -rn | tail -n +2 | cut -d' ' -f2- | xargs -r rm -f --
  done
  free_mb="$(df -Pm "$dir" | awk 'NR==2 {print $4}')"
  [ "$free_mb" -ge "$min_free_mb" ] || { echo "masroof-backup: only ${free_mb} MB free on the disk (< ${min_free_mb}); free space first (docs/HOSTING.md)" >&2; exit 1; }
fi
cid="$(docker ps -q --filter label=com.docker.compose.project=masroof --filter label=com.docker.compose.service=postgres | head -n 1)"
[ -n "$cid" ] || { echo "masroof-backup: postgres container not running" >&2; exit 1; }
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
encrypt() { gpg --batch --yes --pinentry-mode loopback --passphrase-file /etc/masroof/backup.pass --symmetric --cipher-algo AES256 -o "$1"; }
verify() { # verify <file> <min bytes>: size, decrypt, gzip and tar/sql sanity; removes the file on failure
  local f="$1" min="$2"
  if [ "$(stat -c %s "$f")" -ge "$min" ] \
     && gpg --batch --pinentry-mode loopback --passphrase-file /etc/masroof/backup.pass -d "$f" 2>/dev/null | gzip -t; then
    return 0
  fi
  echo "masroof-backup: $(basename "$f") failed verification; removed" >&2
  rm -f "$f"
  return 1
}
umask 077
out="$dir/masroof-$stamp.sql.gz.gpg"
docker exec "$cid" sh -c 'pg_dump -U "$POSTGRES_USER" "$POSTGRES_DB"' | gzip -9 | encrypt "$out.part"
mv "$out.part" "$out"
verify "$out" 200
echo "masroof-backup: wrote $(basename "$out") ($(stat -c %s "$out") bytes, verified)"
# Media: tar of the MinIO data volume (mounted read-only into a throwaway container of the already-present postgres
# image, no network), encrypted the same way.
mid="$(docker ps -q --filter label=com.docker.compose.project=masroof --filter label=com.docker.compose.service=minio | head -n 1)"
if [ -n "$mid" ]; then
  vol="$(docker inspect -f '{{range .Mounts}}{{if eq .Destination "/data"}}{{.Name}}{{end}}{{end}}' "$mid")"
  img="$(docker inspect -f '{{.Image}}' "$cid")"
  [ -n "$vol" ] || { echo "masroof-backup: MinIO data volume not found" >&2; exit 1; }
  mout="$dir/masroof-media-$stamp.tar.gz.gpg"
  docker run --rm --network none --user 0 -v "$vol:/data:ro" --entrypoint tar "$img" -C /data -cf - . | gzip -9 | encrypt "$mout.part"
  mv "$mout.part" "$mout"
  verify "$mout" 20
  echo "masroof-backup: wrote $(basename "$mout") ($(stat -c %s "$mout") bytes, verified)"
else
  echo "masroof-backup: MinIO not running; media not backed up" >&2
fi
find "$dir" -name 'masroof-*.gpg' -mtime "+${keep_days}" -delete
docker image prune -f >/dev/null 2>&1 || true   # dangling layers only: tagged images stay (rollback needs the previous release)
EOF
chmod 755 /usr/local/sbin/masroof-backup

cat > /etc/systemd/system/masroof-backup.service <<'EOF'
[Unit]
Description=Masroof nightly database backup
[Service]
Type=oneshot
ExecStart=/usr/local/sbin/masroof-backup
EOF
cat > /etc/systemd/system/masroof-backup.timer <<'EOF'
[Unit]
Description=Masroof nightly backup
[Timer]
OnCalendar=*-*-* 01:30:00
RandomizedDelaySec=300
Persistent=true
[Install]
WantedBy=timers.target
EOF
# clean up units from older bootstraps (the idle keep-alive was Oracle-only and is not needed on GCP)
systemctl disable --now masroof-keepalive.timer >/dev/null 2>&1 || true
rm -f /etc/systemd/system/masroof-keepalive.timer /etc/systemd/system/masroof-keepalive.service /usr/local/sbin/masroof-keepalive
systemctl daemon-reload
systemctl enable --now masroof-backup.timer >/dev/null 2>&1

systemctl enable --now docker >/dev/null 2>&1
echo "bootstrap: SSH host key to pin (PROD_KNOWN_HOSTS, prefix with the server IP): $(cut -d" " -f1,2 /etc/ssh/ssh_host_ed25519_key.pub)"
say "memory: $(free -m | awk '/^Mem:/ {print $2}') MB RAM, $(free -m | awk '/^Swap:/ {print $2}') MB swap"
say "done. Next: make deploy-prod HOST=${DEPLOY_USER}@<ip> DOMAIN=<name>.duckdns.org VERSION=<image tag> (docs/HOSTING.md)"
