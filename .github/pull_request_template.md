## What and why

<!-- What changes, and what problem it solves. Link the ticket: Closes LIN-123 -->

## How to verify

<!-- What the reviewer should actually check. -->

## Checklist

- [ ] CI green
- [ ] `CHANGELOG.md` updated if this is a meaningful change
- [ ] Still native-only (no JVM Dockerfile reintroduced)
- [ ] No runtime state in static initialisers — GraalVM evaluates those at
      build time, and the bug is invisible to JVM tests
- [ ] Deploy flags still cap scaling (`--max-instances`)
