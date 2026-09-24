# Deployment

Two environments that never share configuration: **local development** and
**cloud**. Keeping them separate is what stops a local default leaking into a
deployed instance.

---

# LOCAL DEVELOPMENT

## Prerequisites

JDK 21, Docker, roughly 4GB free memory. No Google account, no API key.

## Start

```bash
cp .env.example .env      # JWT_SECRET is required; no real secrets needed
docker compose up -d      # Postgres, Redis, Kafka, Prometheus, Grafana, OTel

cd backend
./gradlew bootRun
```

| Service | URL |
|---|---|
| API | http://localhost:8080 |
| OpenAPI | http://localhost:8080/swagger-ui.html |
| Grafana | http://localhost:3000 |
| Prometheus | http://localhost:9090 |
| Kafka UI | http://localhost:8081 |

The catalogue is seeded by migration `V2`, so there are ten games immediately.

Kafka runs in **KRaft mode** — no ZooKeeper container. One less moving part,
and it matches how Kafka 4.x ships.

Topics are declared by a one-shot `kafka-init` container rather than
auto-created on first produce. Auto-creation gives every topic the broker
defaults — one partition, arbitrary retention — making partition count an
accident of which producer ran first, and it cannot be reduced afterwards.

## Running the backend in Docker too

```bash
docker compose --profile app up -d --build
```

Opt-in, because the normal inner loop is infrastructure in Docker and the
application in the IDE. CI uses this profile.

## Stopping

```bash
docker compose down      # keep data
docker compose down -v   # delete volumes
```

---

# CLOUD DEPLOYMENT

**Status: NEVER EXECUTED.** The Terraform validates
(`terraform validate: Success`, Google provider 6.50.0) but has never been
applied. No infrastructure exists, no cost has been incurred, and no
deployment has been verified.

## What it provisions

| Service | Purpose | Notes |
|---|---|---|
| Cloud Run | Stateless backend | Scales to zero by default |
| Cloud SQL | Postgres 16 | **Private IP only** |
| Memorystore | Redis | **Private IP only**, BASIC tier |
| Secret Manager | JWT key, DB password, Gemini key | Per-secret IAM |
| Artifact Registry | Images | Untagged cleanup after 7 days |
| VPC + connector | The only route to the data plane | |

## The Kafka gap

**There is no managed Kafka in this configuration, and that is deliberate.**

Google Cloud Managed Service for Apache Kafka exists but has no scale-to-zero
tier, and costs more per month than everything else here combined. For a
portfolio deployment that is the wrong trade.

Three options, in the order they should be considered:

1. **Run with the event pipeline disabled.** The outbox still records every
   event durably in Postgres, so **nothing is lost**. The consumers simply do
   not run and derived state is not updated. On restoring a broker, the relay
   drains the backlog and consumers catch up.

2. **Replace the transport with Pub/Sub.** Because publishing goes through an
   outbox, only the relay and the listener annotations change — not the
   events, not the idempotency ledger, not any consumer logic. This is
   precisely the benefit the outbox pattern was chosen for.

3. **Provision Managed Service for Apache Kafka**, if the cost is acceptable.

This gap is stated rather than hidden because a deployment diagram showing
Kafka that is not actually deployed would be a lie.

## Deploying

```bash
cd infra/terraform
cp terraform.tfvars.example terraform.tfvars   # fill in project_id
terraform init
terraform plan        # read it
terraform apply
```

Then build and push an image (the `main` workflow does this automatically and
prints the exact command), and deploy it:

```bash
gcloud run deploy gamehub-<env>-backend \
  --image <region>-docker.pkg.dev/<project>/gamehub/gamehub-backend:<sha> \
  --region <region>
```

### Why deployment is manual

CI publishes a scanned, immutable, SHA-tagged image and stops. It does not
deploy.

Automatic deployment on merge is a fine practice **once** there is a staging
environment, smoke tests and an automated rollback trigger. Without those,
auto-deploy means the first person to notice a bad release is a user. The
pipeline produces a release candidate and a human decides.

## Decisions worth explaining

**Images are tagged by commit SHA, never `:latest`.** A deployment must name
an exact immutable artefact, or a rollback has nothing to roll back to.
`variables.tf` rejects a `:latest` tag with a validation rule.

**`max_instances = 5`, not unbounded.** Each instance opens a Hikari pool of
20 against a Postgres configured for 200. Unbounded autoscaling exhausts
database connections long before it exhausts CPU. This is the real scaling
ceiling, and beyond it the answer is PgBouncer, not more instances.

**`cpu_idle` is true in non-production, false in production.** Throttling CPU
between requests is cheaper, but the application has background schedulers —
the outbox relay would only run while requests were arriving.

**Startup probe allows 30 failures at 5s.** A cold JVM plus Flyway migrations
on first boot is genuinely slow, and a probe that gives up early turns a
successful deploy into a crash loop.

**Authentication uses Workload Identity Federation**, not a service account
key. There is no long-lived credential to leak, rotate, or accidentally
commit.

**Deletion protection defaults to true.** `terraform destroy` cannot silently
take the database and everything in it.

## Migrations run at startup

Flyway runs on boot, which is simple and correct for a single-instance
rollout. Two instances starting simultaneously is safe — Flyway takes a lock —
but one waits.

This does **not** scale to zero-downtime rolling deploys with breaking schema
changes. The pattern needed there is expand-migrate-contract: deploy a schema
compatible with both versions, deploy the code, then remove the old columns in
a later release. Not implemented, and stated here rather than discovered
during an outage.

## Cost

Scale-to-zero Cloud Run and `db-f1-micro` keep an idle deployment cheap, but
**Cloud SQL and Memorystore bill continuously whether or not anyone uses
them.** This is not a free-tier architecture.

```bash
terraform destroy   # set db_deletion_protection = false first
```

## Rollback

```bash
gcloud run services update-traffic gamehub-<env>-backend \
  --to-revisions <previous-revision>=100 --region <region>
```

Cloud Run keeps revisions, so a code rollback is a traffic shift and takes
seconds.

**A schema rollback is not.** Flyway has no down-migrations here by design:
an automated reverse migration that drops a column deletes data, and the
recovery path for a bad migration is a forward fix plus point-in-time
recovery, not an automatic undo.

## Gaps

- **No staging environment.** The Terraform supports one via `environment`,
  but nothing is provisioned.
- **No smoke tests after deploy.**
- **No automated rollback trigger.**
- **No CDN or Cloud Armor.** Cloud Run is exposed directly.
- **Single region.**
- **No disaster recovery drill.** Backups are configured; a restore has never
  been tested, and an untested backup is a hypothesis.
