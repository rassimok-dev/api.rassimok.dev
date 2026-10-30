# Changelog

Dated entries, newest first. Written for a reader picking this up cold —
or a future session needing to know what already exists and why.

## 2026-10-30 — Dependency refresh

- **Quarkus 3.39.3 → 3.40.1.1**, the newest stable 3.x platform. Quarkus 4.0
  exists only as Beta2 and was deliberately not adopted: a beta has no place
  under a deployed service, and the Mandrel `jdk-25` builder pin has to be
  re-validated against 4.0 once it goes GA. The BOM bump carries the managed
  libraries with it (Jackson 2.22.2 → 2.22.4 among others). Tests pass.
- maven-compiler-plugin 3.15.0 → 3.16.0; surefire/failsafe 3.5.6 → 3.6.1.
- **GitHub Actions**: checkout v4 → v7, setup-java v4 → v6,
  google-github-actions/auth v2 → v3, setup-gcloud v2 → v3. All now run on
  the node24 runtime. Each action's definition was read at the new tag: every
  input this workflow uses still exists and none is deprecated. setup-java v6
  verifies JDK download checksums automatically, which is a free win for a
  keyless deploy pipeline.
- **Left as is, on purpose**: Maven wrapper 3.9.16 is the latest 3.9.x (a
  3.10.0 tag exists, but nothing here needs it and the wrapper's SHA-256 pin
  would need re-verifying); Mandrel builder `jdk-25` is the ceiling (see
  2026-09-16); `ubi9-quarkus-micro-image:2.0` is the current tag line.
- No CVE scan was possible from the cloud session (the NVD feed is not
  reachable there), so "less vulnerable" here means "on the newest supported
  platform". Enabling Dependabot on the repo would give continuous coverage.

## 2026-10-30 — Cloud session toolchain

- Added a Claude Code **SessionStart hook** (`.claude/hooks/session-start.sh`,
  registered in `.claude/settings.json`). The cloud container ships Java 21
  and this project compiles with `--release 25`, so until now every cloud
  session failed at `./mvnw test` before it could do anything useful. An
  agent that cannot run the tests it writes is flying blind.
- **OpenJDK 25 comes from Ubuntu's archive**, not Temurin. The session's
  egress policy blocks Adoptium (API and apt repo), SDKMAN, Corretto and
  Azul; Ubuntu's archive and Maven Central are allowed. For JVM tests any
  OpenJDK 25 build is equivalent. Local (`.sdkmanrc`) and CI stay on Temurin.
- The hook **warms the Maven cache** with `-DskipTests test` and deliberately
  does not run the tests: the container is snapshotted after the hook, so
  dependencies download once, but a red test should fail a session's work,
  not its startup.
- Hook timeout raised to **600 s** (default is 60 s). A cold run does an apt
  install plus roughly 200 MB of Quarkus dependencies, which exceeds the
  default. Warm runs take a few seconds.
- The hook is a no-op outside cloud sessions (`CLAUDE_CODE_REMOTE` unset), so
  it never touches a developer's machine.

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
