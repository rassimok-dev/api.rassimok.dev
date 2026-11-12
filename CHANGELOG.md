# Changelog

Dated entries, newest first. Written for a reader picking this up cold —
or a future session needing to know what already exists and why.

## 2026-11-12 — Hardening

- **Pipeline least privilege.** `id-token: write` moved from the workflow to
  the deploy job alone, and that job now also requires a `push` event. Before,
  the test job on every pull request could mint an OIDC token. WIF's
  repository condition still limited what it could reach, but nothing ran
  there that needed one.
- **Actions pinned to commit SHAs**, with `persist-credentials: false`:
  checkout v7.0.1, setup-java v6.1.0, auth v3.0.0, setup-gcloud v3.0.1. The
  deploy job holds cloud credentials, so a moved tag here is the expensive
  kind of supply-chain incident. setup-java is 6.1.0, not 6.2.0, because
  6.2.0 was eight days old; nothing pinned is newer than two weeks. The same
  majors as the unmerged `chore/update-dependencies` branch, whose inputs
  check carries over; its Quarkus bump is still worth taking separately.
- **Secrets enter scripts as env vars**, not `${{ }}` splices, so no value
  can change what the shell runs. One deploy at a time via `concurrency`,
  never cancelled midway; timeouts on both jobs.
- **Base image pinned by digest.** `ubi9-quarkus-micro-image:2.0` is rebuilt
  in place for OS patches, so the tag alone could change what ships with no
  commit. Pinned to the digest the tag pointed at on 2026-11-12, which is
  what deploys were already pulling. Dependabot proposes each rebuild.
- **Response headers** on every path: `nosniff`, `X-Frame-Options: DENY`, a
  deny-all CSP with `frame-ancestors 'none'`, `no-referrer`, HSTS, CORP
  `same-site`, a restrictive Permissions-Policy; `Cache-Control: no-store`
  on `/status`. Runtime config only, so native and JVM behave the same.
- **Request bodies capped at 1 KB.** Every endpoint is a GET; the 10 MB
  default was memory an anonymous client could make each instance buffer.
- **Tests** assert the headers, the cache rule, the 413 on large bodies and
  the 405 on non-GET methods. Removing a header or the limit fails them
  (checked).
- **Dependabot** for actions, the Dockerfile digest and Maven, with a 14-day
  cooldown on actions and Maven. **`SECURITY.md`** points at GitHub private
  vulnerability reporting, which must be switched on in settings.
- Full history scanned for credentials: nothing found.

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
