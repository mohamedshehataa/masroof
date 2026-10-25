# Hosting Masroof for free (Oracle Cloud Always Free + DuckDNS)

Goal: Masroof runs 24/7 at `https://<name>.duckdns.org` for 0 EGP/month. You do the three account-side steps below
(about 30 minutes, once). Then you send back **three values** and the rest is automated.

## What you send back

1. **The server's public IP** (looks like `129.151.10.20`)
2. **The DuckDNS subdomain** you chose (just the name, e.g. `masroof-mo`)
3. **The DuckDNS token** (a long string shown on the DuckDNS page after you log in)

**Never send a private key to anyone, in chat or anywhere else.** The orchestrator generates the SSH key pair on its own
side and gives you only the *public* key to paste into Oracle (step 2). Keep the DuckDNS token out of GitHub issues and commits.

## Step 1: Oracle Cloud account

1. Go to https://www.oracle.com/cloud/free/ and click "Start for free".
2. Sign up. A credit/debit card is needed **for identity verification only**; Always Free resources are not charged.
   Pick a home region close to you (e.g. Frankfurt or Marseille). You cannot change it later, and Ampere capacity differs by region.
3. Stay on **Always Free** resources only. Do not click "Upgrade" unless you want to (see "Idle reclaim" below).

## Step 2: Create the VM

1. Console menu -> **Compute -> Instances -> Create instance**.
2. Name: `masroof`.
3. Image: **Canonical Ubuntu 24.04** (the aarch64 / ARM build). Shape: **Change shape -> Ampere -> VM.Standard.A1.Flex**,
   set **4 OCPU and 24 GB** memory (this is the whole Always Free allowance; fewer is fine too: 2 OCPU / 12 GB).
   The shape must carry the "Always Free-eligible" label.
4. Networking: keep the default VCN and public subnet, **Assign a public IPv4 address = yes**.
5. SSH keys: choose **Paste public keys** and paste the **public** key the orchestrator gives you (one line starting with
   `ssh-ed25519`). Do not use "Generate a key pair": that would create a private key you would then have to hand over.
6. Boot volume: 100 GB is within the free 200 GB.
7. Optional but recommended: **Show advanced options -> Management -> Cloud-init script** and paste the contents of
   `infra/oracle/cloud-init.yaml`. It firewalls, patches and installs Docker before anyone logs in. (If you skip it,
   `bootstrap.sh` does the same later.)
8. Create. If you see **"Out of capacity for shape VM.Standard.A1.Flex"**, try again later, try another availability
   domain, or pick 2 OCPU / 12 GB. This is common; it is not an error on your side.
9. When it is running, copy the **Public IP address** from the instance page.

### Open the ports in Oracle's network (required, in addition to the server firewall)

Oracle blocks traffic at the network level before it reaches the VM. Instance page -> **Primary VNIC -> Subnet ->
Security Lists -> Default Security List -> Add Ingress Rules**:

| Source CIDR | Protocol | Dest. port | Why |
|---|---|---|---|
| `0.0.0.0/0` | TCP | `22` | ssh (usually already present) |
| `0.0.0.0/0` | TCP | `80` | HTTP, certificate issuance + redirect |
| `0.0.0.0/0` | TCP | `443` | HTTPS |

Leave "Stateless" unticked. The server's own firewall (ufw) allows the same three ports and nothing else.

## Step 3: DuckDNS subdomain

1. Go to https://www.duckdns.org and sign in (Google/GitHub login).
2. In "domains" type a name (e.g. `masroof-mo`) and click **add domain**. Your address is `masroof-mo.duckdns.org`.
3. Put the VM's public IP in the "current ip" box and click **update ip**.
4. Copy the **token** shown at the top of the page.

## Then: what the orchestrator does (no action from you)

```bash
# 1. one-time server setup (as the default ubuntu user), token goes in over stdin, never on a command line
ssh ubuntu@<ip> 'sudo install -d -m 700 /etc/masroof && sudo install -m 600 /dev/stdin /etc/masroof/duckdns.env' <<< "DUCKDNS_TOKEN=<token>"
ssh ubuntu@<ip> 'sudo DOMAIN=<name>.duckdns.org DUCKDNS_SUBDOMAIN=<name> bash -s' < infra/oracle/bootstrap.sh

# 2. GitHub secrets for tag-driven deploys (values never printed)
gh secret set PROD_HOST   --body "<ip>"
gh secret set PROD_DOMAIN --body "<name>.duckdns.org"
gh secret set PROD_SSH_KEY < deploy_key          # the orchestrator-side private key, read from a local file, never printed or sent
# bootstrap.sh prints the server's ssh host key; pin it so CI refuses any other server (release.yml then sets
# SSH_STRICT_HOSTKEY=yes). Format: "<ip> ssh-ed25519 AAAA..."
gh variable set PROD_KNOWN_HOSTS --body "<ip> <ssh-ed25519 key printed by bootstrap.sh>"

# 3. images are published to GHCR by main.yml; make them public once (GitHub -> Packages -> masroof-backend / masroof-web
#    -> Package settings -> Change visibility) or `docker login ghcr.io` on the server with a read-only token.

# 4. first deploy (inside /release), then every v* tag deploys by itself
make deploy-prod HOST=<ip> DOMAIN=<name>.duckdns.org VERSION=<image tag>
```

If you want to set the secrets yourself instead (you only ever type values you own; the SSH private key stays with the
orchestrator, so for `PROD_SSH_KEY` the orchestrator must run `gh secret set`): GitHub repo -> Settings -> Secrets and variables -> Actions -> New repository
secret, names exactly `PROD_HOST`, `PROD_DOMAIN`, `PROD_SSH_KEY`. If any of the three is missing the release workflow
skips the deploy with a visible warning; it never fails because of that.

## How it works

- **Images**: `main.yml` builds `linux/amd64` + `linux/arm64` images and pushes `ghcr.io/mohamedshehataa/masroof-backend`
  and `-web` tagged with the commit sha; `release.yml` pushes version tags. The Ampere VM pulls them; nothing is built on the server.
- **Stack**: `infra/docker-compose.yml` + `infra/docker-compose.prod.yml` (Caddy with automatic Let's Encrypt HTTPS,
  HSTS, CSP, redirect from HTTP; only ports 80 and 443 are published; Postgres data in a Docker volume; restart
  policies; memory/CPU limits that fit 4 OCPU / 24 GB).
- **Deploy**: `make deploy-prod HOST=<ip> DOMAIN=<domain> VERSION=<tag>` copies the compose files over ssh, pulls the
  images, runs `compose up -d`, then `make smoke URL=https://<domain>`. If the smoke test fails it restores the previous
  tag on the server, re-runs the smoke test and exits non-zero. Server state lives in `~/masroof/infra/.deployed/`.
  The first deploy has nothing to roll back to and just reports the failure.
- **Secrets**: generated on the server in `~deploy/masroof/infra/.env` (mode 600) by `bootstrap.sh`; the DuckDNS token is in
  `/etc/masroof/duckdns.env` (root only). Nothing secret is in the repo, the images, or cloud-init.

### First login
`bootstrap.sh` generates `MASROOF_JWT_SECRET`. To create the first admin, add `MASROOF_BOOTSTRAP_ADMIN_EMAIL` and
`MASROOF_BOOTSTRAP_ADMIN_PASSWORD` (12+ characters) to the server `.env` before the first start. **Remove both
lines after the first start**: they only act while no login exists, and the backend logs a warning (without values)
when they are still set later. The compose network is pinned (`172.28.0.0/24`, Caddy at `172.28.0.10`; override with `MASROOF_NET_SUBNET`/`CADDY_IP` if it
collides with something on the host) and the backend trusts `X-Forwarded-For` only from that one address
(`MASROOF_TRUSTED_PROXIES` is set by compose, do not put it in `.env`). `MASROOF_AUDIT_KEY` (optional, 32+ bytes) keys the audit-log
IP hashes; without it the key is derived from `MASROOF_JWT_SECRET`.

### DNS: publish an A record only
Create only an **A** record (IPv4) for the domain, **no AAAA**, until Docker IPv6 is native and the proxy trust model has been
re-checked: with Docker's IPv6 NAT/userland proxy, clients can reach Caddy from a bridge-gateway address and X-Forwarded-For
handling would no longer match the pinned Caddy address. The DuckDNS refresh job sets IPv4 only.

## What the server does on its own

| Job | When | Where |
|---|---|---|
| Security updates (unattended-upgrades) | daily | apt |
| ssh brute-force bans (fail2ban) | always | 5 failures -> 1 h ban |
| DuckDNS IP refresh | every 5 min (cron) | `/usr/local/sbin/masroof-duckdns` |
| Encrypted `pg_dump` + MinIO media tar | 01:30 UTC nightly (systemd timer) | `/var/backups/masroof/`, 7-day retention |
| Keep-alive CPU burst | hourly, 20 min, niced | `masroof-keepalive.timer` |

### Backups

Both the database dump (`masroof-<stamp>.sql.gz.gpg`) and the MinIO media (`masroof-media-<stamp>.tar.gz.gpg`) are gzip + AES-256 gpg encrypted with `/etc/masroof/backup.pass` (generated on the server). **Copy that passphrase
somewhere safe** (a password manager); without it the dumps cannot be read. Restore:
`gpg --batch --passphrase-file /etc/masroof/backup.pass -d masroof-<stamp>.sql.gz.gpg | gunzip | docker exec -i <postgres container> psql -U masroof masroof`.
These backups live on the VM disk, so they protect against mistakes, not against losing the VM. For an off-box copy
create a free Object Storage bucket (Oracle gives 20 GB), install the `oci` CLI for root, and set
`OCI_BACKUP_BUCKET=<bucket>` in `/etc/masroof/backup.env`; the nightly job then uploads each dump. Run one by hand:
`make backup HOST=<ip>`.

### Idle reclaim

Oracle may reclaim Always Free instances that stay idle (CPU 95th percentile, network and memory all under 20% for
7 days). The keep-alive timer adds a low-priority 20-minute all-core burst every hour (about 33% CPU in each hourly sample) so the CPU figure stays above that line; it uses no
billable resource. The only fully reliable alternative is upgrading the account to **Pay As You Go**: Always Free
resources stay free and are not reclaimed, you are only charged if you create paid resources. Your call, not needed to start.

## Troubleshooting

- Browser cannot connect: check the three ingress rules above, then `sudo ufw status` on the VM.
- Certificate not issued: DuckDNS must point at the VM IP (`nslookup <name>.duckdns.org`) and port 80 must be reachable.
  Caddy retries by itself; `docker logs masroof-caddy-1`.
- `exec format error` in a container: an image was built without the arm64 platform; re-run the `main` workflow.
- Smoke fails after deploy: the script already rolled back; read `docker compose -p masroof logs --tail=200 backend` on the server.
