# Changelog

Dated entries, newest first. Written for a reader picking this up cold —
or a future session needing to know what already exists and why.

## 2026-11-13 (later) - PostgreSQL wired, first migration, native tests and smoke test in the deploy

The klad leaderboard needs players and sessions, so the datasource that was
deliberately left unwired on 2026-09-16 is wired now. The database is
PostgreSQL 18 on Neon's free plan.

- Extensions from the Quarkus BOM, no explicit versions: `jdbc-postgresql`,
  `hibernate-orm-panache`, `flyway`. Flyway's PostgreSQL module arrives as a
  conditional dependency of `jdbc-postgresql`. `hibernate-validator` and
  `mailer` come with the tickets that use them.
- Dev and test get a throwaway `postgres:18` container from Dev Services,
  so tests never touch Neon. `./mvnw test` now needs Docker; GitHub's
  runners have it.
- Prod reads one JDBC URL from the Secret Manager secret `klad-db-url`
  (`KLAD_DB_URL`). User, password and `sslmode` are inside the URL, so no
  username or password properties are set. Flyway logs that URL at start
  with the password masked (checked).
- Flyway migrates at start and owns the schema. Hibernate's schema
  management is `none`. `V1` creates `player` (uuid id, nick, nick_key,
  kind limited to `human` or `agent`, nullable unique email, created_at,
  last_seen_at) and `session` (token_hash as the key, player_id with
  `on delete cascade` and an index, created_at, expires_at). The partial
  unique index on nick_key for accounts comes with the accounts work.
- **The service no longer starts without the database.** An unreachable
  database fails Flyway at start, so Cloud Run keeps serving the previous
  revision.
- Deploy: Cloud Run now runs as the dedicated `api-runtime` service
  account, which holds only `secretAccessor` on the service's secrets,
  instead of the default compute account. The URL comes in with
  `--set-secrets`.
- Deploy: `./mvnw -B verify -Dnative` replaces `package -Dnative
  -DskipTests`. The native integration test (`HealthReadinessIT`) runs
  against the binary with a Dev Services database before any image is
  pushed. `-DskipTests` would also skip Failsafe, so the JVM tests run once
  more in that step.
- On a Mac, `verify -Dnative` cannot run that test: the container build
  makes a Linux binary that macOS cannot launch (exit 126). On Linux it
  passes (run inside the Mandrel builder container): the binary starts with
  the prod profile, the Dev Services URL overrides the `%prod` fallback,
  Flyway applies V1 and readiness lists `<default>` UP.
- Deploy: a smoke step after `gcloud run deploy` checks
  `https://api.rassimok.dev/q/health/ready` and
  `https://rassimok.dev/klad/api/status`, 5 attempts 10 s apart for cold
  starts. The job fails if either does not answer.

### Decisions worth not relitigating

- **The prod URL has a fallback that never resolves**
  (`klad-db-url-not-set.invalid`). Quarkus quietly deactivates a
  datasource that has no URL. Measured: with `KLAD_DB_URL` unset, the
  service started, skipped Flyway and still answered `/q/health/ready` with
  200 UP. With the fallback it fails at start, like any unreachable
  database. `quarkus.datasource.active=true` does not help: a missing URL
  deactivates the datasource anyway.
- **The smoke step checks bodies, not only status codes.** Pages answers
  unknown paths with the home page and a 200, so a 200 from
  `/klad/api/status` proves nothing on its own. The status body must name
  the service. Readiness must list the `<default>` datasource as UP, an
  entry an inactive datasource does not report. The readiness tests assert
  the same entry.
- **The secret must hold Neon's direct (non-pooled) URL.** Flyway migrates
  on every start, and the code does not work around the pooler.
- **Readiness keeps Quarkus' built-in database check.** When that check
  fails, the readiness body at `api.rassimok.dev/q/health/ready` carries the
  JDBC driver's error message. The site's proxy refuses `/klad/api/q/*`, so
  the message never appears on rassimok.dev. What it contains was not
  observed. A custom check without the message would be more code for text
  that appears only during an outage, so none was written.

## 2026-11-13 - Agent instructions moved out of the repository

`CLAUDE.md` is removed, and its CODEOWNERS entry with it. Agent instructions
for this service now live outside the repository, in a private workspace
that holds every rassimok-dev project, so one set of rules covers all of them
and a stale copy on an old branch cannot compete with it.

### Decisions worth not relitigating

- **Do not re-add a `CLAUDE.md` or `.claude/` here.** The rules that matter
  most (native only, Java 25 ceiling, no runtime state in static
  initialisers, `--max-instances` on deploy) are also in the pull request
  checklist and in the entry below. A cloud agent session that sees this
  repository alone gets no instructions, and that trade was accepted.
- **The `chore/cloud-session-hook` branch is not merged as it stands.** It
  adds an in-repo `.claude/` SessionStart hook and a `CLAUDE.md` section for
  cloud sessions, which this decision rules out.

## 2026-09-16 — Service created and deployed

**Live:** `https://api-973103193216.europe-west4.run.app` → `api.rassimok.dev` (mapping created, DNS pending)

- Quarkus 3.39.3 service with `/status` and `/q/health`.
- **Java 25**, and this is a ceiling rather than a preference: Mandrel
  (the native compiler) tracks the JDK and stops at `jdk-25`. There is no
  Mandrel for 26 or 27, so native compilation rules them out. 25 is also
  the current LTS, so nothing is given up.
- **Native only.** JVM Dockerfiles deleted; `Dockerfile.native-micro` is
  the single build path. Measured: 23 ms startup, 40 MB RSS, 51 MB binary,
  5m51s to compile on 2 cores.
- Uptime captured via `@Observes StartupEvent`, *not* a static initialiser.
  GraalVM evaluates static init at build time, which would freeze the
  compile moment and silently report nonsense — a bug invisible to JVM tests.
- CI deploys via **Workload Identity Federation**; no service-account key
  is stored in GitHub. The OIDC provider is locked with
  `assertion.repository=='rassimok-dev/api.rassimok.dev'`.
- Guardrails: `--max-instances 3`, budget alert at 5 PLN (50/90/100%).
- **PostgreSQL 18** pinned in config but deliberately not wired — the
  service stores nothing, and an unreachable datasource fails startup.

### Decisions worth not relitigating

- **Region is europe-west4, not Warsaw.** europe-central2 is ~20 ms closer
  but **forbids Cloud Run domain mappings** (HTTP 501). europe-west3 is
  likewise excluded. europe-west1 and europe-west4 allow them; west4 is
  nearer. The latency is irrelevant behind Cloudflare.
- **Cloud Run over Render.** Render's free tier spins down after 15 min
  with 30–60 s cold starts on 0.1 CPU, and its free Postgres expires after
  30 days. Neither is acceptable for something linked from a CV.
