#!/usr/bin/env bash
# Server bootstrap for the Oracle Cloud Always Free Ampere VM (Ubuntu 24.04, arm64). Idempotent: safe to re-run.
# Run as root on the VM (first time, as the default `ubuntu` user):
#   ssh ubuntu@<ip> 'sudo DOMAIN=<name>.duckdns.org DUCKDNS_SUBDOMAIN=<name> bash -s' < infra/oracle/bootstrap.sh
# Secrets never travel on a command line: put the DuckDNS token in place first, see docs/HOSTING.md
#   ssh ubuntu@<ip> 'sudo install -d -m 700 /etc/masroof && sudo install -m 600 /dev/stdin /etc/masroof/duckdns.env' <<< "DUCKDNS_TOKEN=..."
#
# Env (all optional): DOMAIN, DUCKDNS_SUBDOMAIN, DEPLOY_PUBKEY (default: authorized_keys of the invoking user),
# DEPLOY_USER (default deploy).
# Does: Docker + compose plugin, deploy user, ufw (22/80/443), Oracle iptables fix, unattended-upgrades, fail2ban,
# DuckDNS updater (cron), nightly encrypted pg_dump with 7-day retention (+ optional Object Storage copy),
# keep-alive timer, and a generated server-side .env for the stack (random secrets, never in the repo).
set -euo pipefail

[ "$(id -u)" -eq 0 ] || { echo "bootstrap: run as root (sudo)" >&2; exit 1; }
say() { printf 'bootstrap: %s\n' "$*"; }

DEPLOY_USER="${DEPLOY_USER:-deploy}"
DOMAIN="${DOMAIN:-}"
DUCKDNS_SUBDOMAIN="${DUCKDNS_SUBDOMAIN:-}"
CONF=/etc/masroof
APP_DIR="/home/${DEPLOY_USER}/masroof/infra"
export DEBIAN_FRONTEND=noninteractive

# ---------- packages ----------
say "installing packages"
apt-get update -qq
apt-get install -y -qq docker.io docker-compose-v2 ufw fail2ban unattended-upgrades curl gnupg openssl cron ca-certificates >/dev/null

# ---------- deploy user ----------
if ! id "$DEPLOY_USER" >/dev/null 2>&1; then
  say "creating user $DEPLOY_USER"
  adduser --disabled-password --gecos "" "$DEPLOY_USER" >/dev/null
fi
usermod -aG docker "$DEPLOY_USER"
# the deploy user may trigger the (root-owned) backup job, and nothing else
printf '%s ALL=(root) NOPASSWD: /usr/local/sbin/masroof-backup\n' "$DEPLOY_USER" > /etc/sudoers.d/masroof-backup
chmod 440 /etc/sudoers.d/masroof-backup
visudo -cf /etc/sudoers.d/masroof-backup >/dev/null
install -d -m 700 -o "$DEPLOY_USER" -g "$DEPLOY_USER" "/home/${DEPLOY_USER}/.ssh"
auth="/home/${DEPLOY_USER}/.ssh/authorized_keys"
touch "$auth"
if [ -n "${DEPLOY_PUBKEY:-}" ]; then
  grep -qxF "$DEPLOY_PUBKEY" "$auth" || printf '%s\n' "$DEPLOY_PUBKEY" >> "$auth"
elif [ -n "${SUDO_USER:-}" ] && [ -s "/home/${SUDO_USER}/.ssh/authorized_keys" ]; then
  while IFS= read -r k; do grep -qxF "$k" "$auth" || printf '%s\n' "$k" >> "$auth"; done < "/home/${SUDO_USER}/.ssh/authorized_keys"
fi
chown "$DEPLOY_USER:$DEPLOY_USER" "$auth"; chmod 600 "$auth"
[ -s "$auth" ] || say "WARNING: no ssh key installed for $DEPLOY_USER; pass DEPLOY_PUBKEY=... and re-run"

# ---------- ssh hardening ----------
cat > /etc/ssh/sshd_config.d/99-masroof.conf <<'EOF'
PasswordAuthentication no
PermitRootLogin no
EOF
sshd -t && systemctl reload ssh

# ---------- firewall ----------
# Oracle's Ubuntu images ship an iptables ruleset (netfilter-persistent) that REJECTs everything but ssh before
# ufw's rules are reached. Replace it with an accept-all base; ufw is the only host firewall from here on.
if dpkg -s iptables-persistent >/dev/null 2>&1 || dpkg -s netfilter-persistent >/dev/null 2>&1; then
  say "removing Oracle's default iptables-persistent ruleset (ufw takes over)"
  apt-get purge -y -qq iptables-persistent netfilter-persistent >/dev/null || true
fi
if iptables -S INPUT 2>/dev/null | grep -q -- '-j REJECT'; then
  iptables -F INPUT
  iptables -P INPUT ACCEPT
fi
ufw default deny incoming >/dev/null
ufw default allow outgoing >/dev/null
ufw allow 22/tcp >/dev/null
ufw allow 80/tcp >/dev/null
ufw allow 443/tcp >/dev/null
ufw --force enable >/dev/null
say "ufw active: 22, 80, 443 (the Oracle VCN security list must allow them too: docs/HOSTING.md)"

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

# ---------- stack env (server-side secrets, generated here, never in the repo) ----------
install -d -o "$DEPLOY_USER" -g "$DEPLOY_USER" "/home/${DEPLOY_USER}/masroof" "$APP_DIR" "$APP_DIR/.deployed"
envf="$APP_DIR/.env"
if [ ! -f "$envf" ]; then
  umask 077
  {
    printf 'POSTGRES_PASSWORD=%s\n' "$(openssl rand -hex 24)"
    printf 'S3_ACCESS_KEY=%s\n' "masroof$(openssl rand -hex 4)"
    printf 'S3_SECRET_KEY=%s\n' "$(openssl rand -hex 24)"
    printf 'DOMAIN=\n'
  } > "$envf"
  chown "$DEPLOY_USER:$DEPLOY_USER" "$envf"; chmod 600 "$envf"
  say "generated $envf"
fi
if [ -n "$DOMAIN" ]; then
  if grep -q '^DOMAIN=' "$envf"; then sed -i "s|^DOMAIN=.*|DOMAIN=${DOMAIN}|" "$envf"; else printf 'DOMAIN=%s\n' "$DOMAIN" >> "$envf"; fi
fi

# ---------- DuckDNS updater ----------
cat > /usr/local/sbin/masroof-duckdns <<'EOF'
#!/usr/bin/env bash
# Keeps <sub>.duckdns.org pointing at this VM. Token comes from the root-only /etc/masroof/duckdns.env; never logged.
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

# ---------- nightly backup (pg_dump, gpg-encrypted, 7 days) ----------
cat > /usr/local/sbin/masroof-backup <<'EOF'
#!/usr/bin/env bash
# Nightly Postgres dump, AES-256 symmetric gpg with /etc/masroof/backup.pass, 7-day retention in /var/backups/masroof.
# Optional copy to Oracle Object Storage when OCI_BACKUP_BUCKET is set in /etc/masroof/backup.env and `oci` is installed.
set -euo pipefail
dir=/var/backups/masroof
install -d -m 700 "$dir"
cid="$(docker ps -q --filter label=com.docker.compose.project=masroof --filter label=com.docker.compose.service=postgres | head -n 1)"
[ -n "$cid" ] || { echo "masroof-backup: postgres container not running" >&2; exit 1; }
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
out="$dir/masroof-$stamp.sql.gz.gpg"
tmp="$out.part"
umask 077
docker exec "$cid" sh -c 'pg_dump -U "$POSTGRES_USER" "$POSTGRES_DB"' | gzip -9 \
  | gpg --batch --yes --pinentry-mode loopback --passphrase-file /etc/masroof/backup.pass --symmetric --cipher-algo AES256 -o "$tmp"
mv "$tmp" "$out"
echo "masroof-backup: wrote $(basename "$out") ($(stat -c %s "$out") bytes)"
# Media: tar of the MinIO data volume (mounted read-only into a throwaway container of the already-present postgres
# image, no network), encrypted the same way.
mid="$(docker ps -q --filter label=com.docker.compose.project=masroof --filter label=com.docker.compose.service=minio | head -n 1)"
if [ -n "$mid" ]; then
  vol="$(docker inspect -f '{{range .Mounts}}{{if eq .Destination "/data"}}{{.Name}}{{end}}{{end}}' "$mid")"
  img="$(docker inspect -f '{{.Image}}' "$cid")"
  [ -n "$vol" ] || { echo "masroof-backup: MinIO data volume not found" >&2; exit 1; }
  mout="$dir/masroof-media-$stamp.tar.gz.gpg"
  docker run --rm --network none --user 0 -v "$vol:/data:ro" --entrypoint tar "$img" -C /data -cf - . | gzip -9 \
    | gpg --batch --yes --pinentry-mode loopback --passphrase-file /etc/masroof/backup.pass --symmetric --cipher-algo AES256 -o "$mout.part"
  mv "$mout.part" "$mout"
  echo "masroof-backup: wrote $(basename "$mout") ($(stat -c %s "$mout") bytes)"
else
  echo "masroof-backup: MinIO not running; media not backed up" >&2
fi
find "$dir" -name 'masroof-*.gpg' -mtime +7 -delete
if [ -r /etc/masroof/backup.env ]; then
  bucket="$(grep -E '^OCI_BACKUP_BUCKET=' /etc/masroof/backup.env | head -n 1 | cut -d= -f2-)"
  if [ -n "$bucket" ] && command -v oci >/dev/null 2>&1; then
    for f in "$out" "${mout:-}"; do
      [ -n "$f" ] && [ -f "$f" ] || continue
      oci os object put --bucket-name "$bucket" --file "$f" --name "$(basename "$f")" --force >/dev/null \
        && echo "masroof-backup: copied $(basename "$f") to Object Storage" || echo "masroof-backup: Object Storage copy failed" >&2
    done
  fi
fi
EOF
chmod 755 /usr/local/sbin/masroof-backup

# ---------- keep-alive ----------
# Oracle may reclaim Always Free instances whose CPU (95th percentile), network and memory all stay under 20% for 7 days.
# A 20-minute niced all-core burst every hour (about 33% average CPU in EVERY hourly sample, so it stays above 20% even at
# hourly sampling) keeps the CPU 95th percentile above the threshold
# without touching the app (nice 19) and without leaving the free allowance (no extra billable resource is used).
cat > /usr/local/sbin/masroof-keepalive <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
n="$(nproc)"
i=0
while [ "$i" -lt "$n" ]; do
  timeout 1200 nice -n 19 sh -c 'while :; do :; done' &
  i=$((i + 1))
done
wait || true
EOF
chmod 755 /usr/local/sbin/masroof-keepalive

mkunit() { # mkunit <name> <description> <exec>
  cat > "/etc/systemd/system/$1.service" <<EOF
[Unit]
Description=$2
[Service]
Type=oneshot
ExecStart=$3
EOF
}
mkunit masroof-backup "Masroof nightly database backup" /usr/local/sbin/masroof-backup
mkunit masroof-keepalive "Masroof keep-alive CPU burst (Oracle idle reclaim)" /usr/local/sbin/masroof-keepalive
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
cat > /etc/systemd/system/masroof-keepalive.timer <<'EOF'
[Unit]
Description=Masroof keep-alive hourly
[Timer]
OnCalendar=hourly
Persistent=false
[Install]
WantedBy=timers.target
EOF
systemctl daemon-reload
systemctl enable --now masroof-backup.timer masroof-keepalive.timer >/dev/null 2>&1

systemctl enable --now docker >/dev/null 2>&1
echo "bootstrap: SSH host key to pin (PROD_KNOWN_HOSTS, prefix with the server IP): $(cut -d" " -f1,2 /etc/ssh/ssh_host_ed25519_key.pub)"
say "done. Next: make deploy-prod HOST=${DEPLOY_USER}@<ip> VERSION=<image tag> (docs/HOSTING.md)"
