# Conventions for api.rassimok.dev

## What this is
A Quarkus REST service deployed to Google Cloud Run as a **native image**.

## Hard rules
- **Native only.** There is one Dockerfile: `src/main/docker/Dockerfile.native-micro`.
  The JVM recipes were deliberately deleted. Do not reintroduce them.
- **Java 25, and not higher.** Mandrel (the native compiler) tracks the JDK and
  currently tops out at `jdk-25`; there is no Mandrel for 26 or 27. The builder
  image is pinned in `application.properties`. 25 is also the current LTS.
- **Nothing static-initialised with runtime state.** GraalVM runs static
  initialisers at *build* time, so `static final Instant STARTED = Instant.now()`
  freezes the compile moment. Use `@Observes StartupEvent` instead. This class of
  bug passes every JVM test and only appears in production.

## Versions
| | |
|---|---|
| Java | 25 (LTS, Mandrel ceiling) |
| Quarkus | 3.39.3 |
| PostgreSQL | 18 — pinned but not yet wired |

`.sdkmanrc` pins the local toolchain: run `sdk env` in this directory.

## Cloud Run notes
- The port comes from `$PORT`. Do not hardcode 8080.
- The service is **stateless** — no disk, requests capped at 60 min.
- Always deploy with `--max-instances` set. Uncapped scaling is how a personal
  project generates a real bill.

## Testing
`./mvnw test` for JVM tests. Native behaviour can differ, so anything touching
reflection, resources or static init needs a native integration test too.

## Changelog
Record every meaningful change in `CHANGELOG.md` — dated entry, newest first.
Include the *reasoning*, especially for decisions that look arbitrary later
(region choices, version ceilings, things deliberately not done). The point
is that a future session can recap what exists without re-deriving it, and
does not undo a deliberate choice by mistake.
