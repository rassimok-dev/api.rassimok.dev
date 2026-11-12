# Security policy

## Reporting a vulnerability

Please report privately through GitHub:
**Security → Report a vulnerability** on this repository
(<https://github.com/rassimok-dev/api.rassimok.dev/security/advisories/new>).
Do not open a public issue for anything exploitable.

You will get an acknowledgement within a week. This is a one-person
project, so there is no bounty, but credit is given in the fix's changelog
entry if you want it.

## Scope

- The service at `api.rassimok.dev` and its Cloud Run origin.
- The deploy pipeline in `.github/workflows/`, which holds the only path to
  the Google Cloud project (keyless, via Workload Identity Federation).

The static site has its own policy in `rassimok-dev/rassimok.dev`.

## How the service is protected

- No stored credentials: CI deploys through Workload Identity Federation,
  locked to this repository. Only the deploy job, on pushes to `main`, may
  request a token.
- Actions pinned to commit SHAs, the base image to a digest, dependencies
  watched by Dependabot.
- Non-root container (UID 1001) running a native executable on a minimal
  UBI micro image.
- Every response carries `nosniff`, a deny-all CSP, `frame-ancestors 'none'`
  and HSTS; request bodies are capped at 1 KB; scaling is capped by
  `--max-instances`.
