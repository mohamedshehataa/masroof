# Hosting Masroof on the Google Cloud Free Tier (e2-micro + DuckDNS)

Goal: Masroof runs 24/7 at `https://<name>.duckdns.org` on one small VM. You do the account-side steps below
(about 30 minutes, once). Then you send back **three values** and the rest is automated.

**Honest cost: about USD 3.65 per month (about EGP 180), all of it the VM's public IPv4 address.** The VM, its 30 GB
disk and 1 GB of monthly traffic are free; the IPv4 address is not (see "What it costs" below). New Google Cloud
accounts get a USD 300 trial credit for 90 days that covers it.

**Current instance (created by the owner, 2026-10-26):** e2-micro in `us-central1-c`, Ubuntu 24.04 x86_64, 30 GB standard
disk, no snapshot schedule, Ops Agent off, HTTP/HTTPS firewall tags on, ephemeral external IP `35.254.195.156`, login user
`masroof-deploy` with the key `~/.ssh/masroof_deploy`. The owner steps below are the recipe that produced it.

## What you send back

1. **The VM's external IP** (looks like `34.123.45.67`)
2. **The DuckDNS subdomain** you chose (just the name, e.g. `masroof-mo`)
3. **The DuckDNS token** (a long string shown on the DuckDNS page after you log in)

**Never send a private key to anyone, in chat or anywhere else.** The orchestrator generates the SSH key pair on its own
side and gives you only the *public* key (one line: `ssh-ed25519 AAAA... masroof-deploy`). Keep the DuckDNS token out of
GitHub issues and commits.

## Owner steps (Google Cloud console, https://console.cloud.google.com)

1. **Project and billing.** Create a project (name `masroof`) and attach a billing account (a card is required for
   identity; the Free Tier resources below are not charged). Then **Billing -> Budgets & alerts -> Create budget**:
   amount **USD 5** per month, alerts at 50%, 90% and 100%. (A USD 1 budget would fire in the first week from the IPv4
   address alone; USD 5 fires only if something else starts costing money.)
2. **Compute Engine -> VM instances -> Create instance.**
   - Name `masroof`. **Region `us-east1` (South Carolina, closest to Egypt)**, or `us-central1` / `us-west1`. Only these three are free.
   - Machine: series **E2**, type **e2-micro** (2 shared vCPU, 1 GB). Provisioning model: Standard (not Spot).
3. **OS and storage -> Change.** Operating system **Ubuntu**, version **Ubuntu 24.04 LTS** (x86/64, not Arm), boot disk type
   **Standard persistent disk** (not Balanced or SSD: those are billed), size **30 GB**.
4. **Networking:** tick **Allow HTTP traffic** and **Allow HTTPS traffic**. (Port 22 is already open by the default VPC
   rule.) Leave the external IPv4 on **Ephemeral**: a reserved static IP costs the same, and the server updates DuckDNS
   by itself if the address ever changes.
5. **Security -> Manage access -> Add manually generated SSH key -> Add item**, and paste the public key the
   orchestrator gave you. Google creates the login user from the comment, so it becomes the user `masroof-deploy`.
6. **Data protection / Backups:** make sure **no snapshot schedule** is attached (snapshots are billed; the server makes
   its own encrypted backups). Do not enable "Back up your data".
7. Click **Create**, then copy the **External IP** from the VM list.
8. **DuckDNS** (https://www.duckdns.org, sign in with Google/GitHub): in "domains" type a name (e.g. `masroof-mo`),
   click **add domain**, paste the VM's external IP into "current ip", click **update ip**, copy the **token**.

Send back the three values. That is all.

## Then: what the orchestrator does (no action from you)

```bash
# 1. one-time server setup. GCP gives the metadata-key user passwordless sudo. The token goes in over stdin,
#    never on a command line.
ssh masroof-deploy@<ip> 'sudo install -d -m 700 /etc/masroof && sudo install -m 600 /dev/stdin /etc/masroof/duckdns.env' <<< "DUCKDNS_TOKEN=<token>"
ssh masroof-deploy@<ip> 'sudo DOMAIN=<name>.duckdns.org DUCKDNS_SUBDOMAIN=<name> bash -s' < infra/host/bootstrap.sh

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

`HOST=<ip>` means `masroof-deploy@<ip>`; pass `HOST=user@ip` to override. `PROD_HOST` (a secret) may carry either form.
If any of `PROD_HOST`, `PROD_DOMAIN`, `PROD_SSH_KEY` is missing the release workflow skips the deploy with a visible
warning; it never fails because of that.

## What it costs (checked 2026-10-26)

| Item | Free Tier allowance | Masroof usage | Cost |
|---|---|---|---|
| e2-micro VM, us-west1 / us-central1 / us-east1 | 1 non-preemptible instance, a full month of hours | 1 VM, 24/7 | USD 0 |
| Boot disk | 30 GB-months of **standard** persistent disk | 30 GB standard | USD 0 (Balanced/SSD would be billed; beyond 30 GB is USD 0.04 per GB-month) |
| **External IPv4 address** | **none** (Free Tier is 1 hour per month per account) | 1 address in use, 24/7 | **USD 0.005 per hour, about USD 3.65 per month, ephemeral or static alike** |
| Internet egress | **1 GB per month** from North America to all destinations except China and Australia | see below | USD 0.12 per GB (first 1 TiB, Premium Tier standard rate) above the free GB |
| Ingress (uploads, `docker pull`, Let's Encrypt, DuckDNS) | n/a | any | free |
| Snapshots / backup service | none | not used | keep disabled |

Sources: [Google Cloud Free Tier features](https://cloud.google.com/free/docs/free-cloud-features) (e2-micro regions,
30 GB-months standard disk, 1 GB egress); [VPC network pricing](https://cloud.google.com/vpc/network-pricing)
(external IPv4 in use on a standard VM: USD 0.005 per hour, free tier 1 hour per month; internet egress to worldwide
destinations excluding Australia USD 0.12 per GiB for the first TiB); [Disk pricing](https://cloud.google.com/compute/disks-image-pricing)
(standard provisioned space: first 30 GiB-months free). Rates change; confirm on those pages before relying on them. I
could not read a per-country Egypt rate off the pricing page (the table is an interactive widget), so the USD 0.12 per
GB is the headline worldwide rate: check **Billing -> Reports** once, after the first month.

**Typical household usage stays inside the free 1 GB.** A first load of the PWA is about 110 KB of gzip JavaScript and is
then cached by the service worker; an API response is 2 to 20 KB. Two people making about 30 calls a day is on the order of
20 MB per month. Receipt photos (when that feature lands) are about 0.3 to 2 MB each and count as egress only when viewed
again: 100 per month is roughly 30 to 200 MB. Plan for 100 to 300 MB per month, so egress is USD 0; a bad month of 5 GB
would be about USD 0.50. The APK is served from GitHub Releases, not from the VM.

## How it works

- **Images**: `main.yml` builds `linux/amd64` images (the e2-micro is x86_64; arm64 was dropped with Oracle) and pushes
  `ghcr.io/mohamedshehataa/masroof-backend` and `-web` tagged with the commit sha; `release.yml` pushes version tags.
  Nothing is built on the server.
- **Stack**: `infra/docker-compose.yml` + `infra/docker-compose.prod.yml` (Caddy with automatic Let's Encrypt HTTPS,
  HSTS, CSP, redirect from HTTP; only ports 80 and 443 are published; Postgres and MinIO data in Docker volumes;
  restart policies; per-service memory and CPU caps sized for 1 GB RAM).
- **Deploy**: `make deploy-prod HOST=<ip> DOMAIN=<domain> VERSION=<tag>` copies the compose files over ssh, pulls the
  images, runs `compose up -d`, then `make smoke URL=https://<domain>`. If the smoke test fails it restores the previous
  tag on the server, re-runs the smoke test and exits non-zero. Server state lives in `~/masroof/infra/.deployed/`.
  The first deploy has nothing to roll back to and just reports the failure.
- **Secrets**: generated on the server in `~masroof-deploy/masroof/infra/.env` (mode 600) by `bootstrap.sh`
  (`MASROOF_JWT_SECRET`, `MASROOF_AUDIT_KEY`, DB and MinIO passwords) and never printed; the DuckDNS token is in
  `/etc/masroof/duckdns.env` (root only). Nothing secret is in the repo or the images.

### Memory budget (1 GB RAM, measured)

The prod overlay caps every service. The numbers below come from running the exact overlay locally (Docker 29.9.2,
linux/amd64, project `masrooftest` on a random subnet, caps applied, images built from `main` at 8c30840, MinIO included),
`docker stats --no-stream`:

| Service | Cap | After startup | After 600 requests* |
|---|---|---|---|
| backend (JVM: `-Xmx224m`, SerialGC, C1 only, 5-connection Hikari pool) | 384 MiB | 164 MiB | 175 MiB |
| postgres (`shared_buffers=48MB`, `max_connections=20`, `jit=off`) | 192 MiB | 38 MiB | 45 MiB |
| minio (`GOMEMLIMIT=96MiB`) | 128 MiB | 32 MiB | 32 MiB |
| web (static nginx) | 48 MiB | 19 MiB | 19 MiB |
| caddy (`GOMEMLIMIT=48MiB`) | 64 MiB | 11 MiB | 16 MiB |
| **Total** | **816 MiB** | **264 MiB** | **286 MiB** |

\* 300 expense creates, 100 list, 100 category and 100 web-page requests with 4 in parallel, plus 6 logins; every
request returned 2xx; no container was OOM-killed or restarted.

The OS, dockerd and containerd take roughly 200 to 250 MB, so a running host sits near 500 MB of the roughly 970 MB an
e2-micro reports, and `bootstrap.sh` adds a 2 GB swapfile (`vm.swappiness=10`) for spikes such as `apt` upgrades.
Backend start time: **4.1 s** with the 1.5 CPU cap on the (fast) test machine, 6.1 s at 0.4 CPU, **27.8 s at 0.1 CPU**.
The e2-micro's shared vCPUs sustain 0.25 CPU each and burst higher for short periods, so expect roughly 10 to 40 s; the
backend health check allows 240 s before declaring it unhealthy and `deploy.sh` waits up to 180 s for the first smoke test.
No startup-snapshot (AppCDS) is built: it needs a training run at image build time and the start time is already fine.

MinIO is kept (32 MiB measured, capped at 128 MiB). Since STORY-168 the backend stores receipt attachments in it
(bucket `masroof-media`, created on first upload; `S3_ENDPOINT`, `S3_ACCESS_KEY`, `S3_SECRET_KEY`, `S3_BUCKET` in the
backend environment; without them only the attachment endpoints answer 503). Uploads are capped at 8 MiB and held on the
Java heap (never spooled to the memory-backed `/tmp`); a single upload peaks at roughly 3 x 8 MiB of the 224 MiB heap,
so the memory budget above is unchanged.

### First login
`bootstrap.sh` generates `MASROOF_JWT_SECRET`. To create the first admin, add `MASROOF_BOOTSTRAP_ADMIN_EMAIL` and
`MASROOF_BOOTSTRAP_ADMIN_PASSWORD` to the server `.env` before the first start. The password must be 12 to 72
characters (BCrypt reads 72 bytes), have at least 5 different characters, not be a single repeated or sequential
run, and not be a common password (the SecLists top 10,000, also with digits, symbols or spaces around the word);
`bootstrap-host.sh admin-add` also limits it to letters, digits and `. _ ~ -`. A rejected password stops the first
start with a message that names the rule, never the value. Once a login exists the variables are ignored. **Remove both
lines after the first start**: they only act while no login exists, and the backend logs a warning (without values)
when they are still set later. The compose network is pinned (`172.28.0.0/24`, Caddy at `172.28.0.10`; override with `MASROOF_NET_SUBNET`/`CADDY_IP` if it
collides with something on the host) and the backend trusts `X-Forwarded-For` only from that one address
(`MASROOF_TRUSTED_PROXIES` is set by compose, do not put it in `.env`). `bootstrap.sh` also generates `MASROOF_AUDIT_KEY` (48 random bytes, base64, mode 600 `.env`, never overwritten once present)
to key the audit-log IP hashes, independent of `MASROOF_JWT_SECRET`, so rotating the JWT secret does not change audit hashes.
Under the prod profile the backend refuses to start if `MASROOF_TRUSTED_PROXIES` has an entry broader than /16 (IPv4) or /64 (IPv6). The network also sets `ip_range` (`MASROOF_NET_RANGE`, default `172.28.0.128/25`) so dynamic addresses never collide with Caddy's `.10`.

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
| Encrypted `pg_dump` + MinIO media tar, verified | 01:30 UTC nightly (systemd timer) | `/var/backups/masroof/`, 7-day retention |
| Firewall (ufw) | always | 22, 80, 443 only |
| Docker log rotation, journald cap | always | 10 MB x 3 per container, 100 MB journal |

There is no idle-reclaim keep-alive job: the e2-micro Free Tier is not reclaimed for being idle (that was an Oracle
behaviour). Re-running `bootstrap.sh` on a server bootstrapped for Oracle removes the old keep-alive timer.

### Backups

Both the database dump (`masroof-<stamp>.sql.gz.gpg`) and the MinIO media (`masroof-media-<stamp>.tar.gz.gpg`) are gzip + AES-256 gpg encrypted with `/etc/masroof/backup.pass` (generated on the server). **Copy that passphrase
somewhere safe** (a password manager); without it the dumps cannot be read. Each file is decrypted and gunzip-tested right after it
is written (a failed file is deleted and the job fails). The disk is only 30 GB and shared with Docker: the job refuses
to start under 3 GB free (after first deleting all but the newest dump of each kind), keeps 7 days, and drops
dangling Docker layers afterwards. Restore:
`gpg --batch --passphrase-file /etc/masroof/backup.pass -d masroof-<stamp>.sql.gz.gpg | gunzip | docker exec -i <postgres container> psql -U masroof masroof`.
These backups live on the VM disk, so they protect against mistakes, not against losing the VM or the disk. For an
off-box copy, pull them from your own machine, for example
`ssh masroof-deploy@<ip> 'sudo cat /var/backups/masroof/<file>' > <file>` (egress counts against the free 1 GB; a dump is a few MB).
Run one by hand: `make backup HOST=<ip>`.

## Troubleshooting

- Browser cannot connect: check "Allow HTTP traffic" and "Allow HTTPS traffic" on the VM (Compute Engine -> VM ->
  Edit -> Networking), then `sudo ufw status` on the VM.
- Certificate not issued: DuckDNS must point at the VM IP (`nslookup <name>.duckdns.org`) and port 80 must be reachable.
  Caddy retries by itself; `docker logs masroof-caddy-1`.
- VM was stopped and started: the external IP changed. The DuckDNS cron fixes DNS within 5 minutes; check
  `sudo /usr/local/sbin/masroof-duckdns`.
- Out of memory symptoms (a container restarting, `OOMKilled` in `docker inspect`): `free -m` and `docker stats --no-stream`
  on the VM; the caps in `infra/docker-compose.prod.yml` are the first thing to adjust.
- `exec format error` in a container: an image was built for the wrong platform; the VM is linux/amd64, re-run the `main` workflow.
- Smoke fails after deploy: the script already rolled back; read `docker compose -p masroof logs --tail=200 backend` on the server.
- Disk filling up: `df -h /`, `docker system df`; old tagged images can be removed with `docker image rm` (keep the two
  releases listed in `~/masroof/infra/.deployed/`).
