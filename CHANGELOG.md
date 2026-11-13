# Changelog

Dated entries, newest first. Written for a reader picking this up cold —
or a future session needing to know what already exists and why.

## 2026-11-13 (fourth) - Email login with 6-digit codes

A guest can verify an email address and so become an account that works on
any device. Paths are the API's own; the game calls them through the site's
proxy.

- `POST /login/code` `{"email"}` answers `202 {}` for every valid email and
  mails a 6-digit code. `400 invalid_email`, `429 rate_limited`.
- `POST /login/verify` `{"email","code"}` answers 200 with the player plus
  `"merged":false` and a new session cookie. If the email has an account,
  this device is logged into it. Otherwise the device's guest becomes the
  account (its email is set). Errors: `400 invalid_code` (wrong, expired,
  used or no code), `429 too_many_attempts` on the fifth wrong attempt,
  which deletes the code, `409 nick_taken` when an account already holds
  the guest's nick key, `401 no_session` when there is no account and no
  guest on this device. The guest merge is the accounts work's next step;
  until then a guest on a device that logs into another account is left
  alone.
- `POST /logout` reads no body, deletes the session row, clears the cookie
  (`Max-Age=0`) and answers 204. `401 no_session` without a session.
- Email: trimmed, lower case, at most 254 characters, one `@` with text on
  both sides. Spaces, control characters and `< > ( ) [ ] , ; : " \` are
  refused too.
- Code: 6 digits from `SecureRandom`, valid 10 minutes, stored as the
  SHA-256 of `email + ":" + code` in lower-case hex, compared with
  `MessageDigest.isEqual`. A new request replaces the email's code. The code
  must be a JSON string; a number does not match.
- Limits, counted in the database: 3 codes per email per hour, 80 mails a
  day overall. All bounds sit in `Rules`.
- Migration `V3` adds `login_code` (one row per email: hash, attempts,
  expiry, creation) and `login_mail`, the send log (SHA-256 of the email and
  the send time). `V1` and `V2` are unchanged.
- Mail: the `mailer` extension from the Quarkus BOM, SMTP to
  `smtp.resend.com:465` with TLS, user `resend`, password from
  `RESEND_SMTP_KEY` (prod only). From `КЛАД <klad@mail.rassimok.dev>`;
  plain Russian text with the code and its 10 minutes, no links, no HTML.
  Dev and test use the mock mailbox (`%dev` and `%test` only). The
  extension brings `qute` along (for mail templates, unused here).
- Deploy: `--set-secrets` also maps `RESEND_SMTP_KEY=resend-smtp-key:latest`
  (one flag, comma-separated, as `--set-secrets` replaces the whole set).
  `api-runtime` already had access to the secret.
- Tests: unit tests for the email check, the code hash (against a vector
  from `shasum`), expiry, code format, the mail text and both limit windows
  on a fixed clock. `LoginResourceTest` (with the mock mailbox) covers the
  sent mail and what is stored, invalid emails, the replaced code, wrong
  codes, four wrong then right, the fifth wrong, 10 parallel guesses,
  expiry, a used code, both limits and their windows, 10 parallel code
  requests, a guest becoming an account, a second device, an account
  with a guest on the device, `nick_taken`, `no_session`, and logout.
  `ResponseHeadersTest` has the 415 rows for both login bodies, the error
  rows, and logout with an empty chunked body and no type (204, no
  `Content-Type`). `LoginRoundTripIT` requests and verifies codes against
  the native binary.

### Decisions worth not relitigating

- **SMTP, not Resend's HTTP API.** Quarkus Mailer is in the BOM, so no new
  library; it has a mock mailbox the tests read; and the provider stays a
  matter of four properties. Port 465 is TLS from the first byte; STARTTLS
  on 587 opens in plain text and upgrades.
- **`202` for every valid email.** A mail goes out whether or not an account
  exists, so the answer never tells anyone who plays here. A new address
  needs the mail anyway to sign up.
- **Verify checks the code before anything else.** Only a correct code
  reveals whether the email has an account (200 against 401 or 409).
  Checking the session first would let anyone probe emails with a wrong
  code.
- **A correct code is used up even when the answer is 401 or 409.** One
  rule ("used codes are deleted"); the contract already has the game ask
  for a new code after `nick_taken`. So the game must create its guest
  before it asks for a code: a code answered with 401 is gone.
- **Verify keeps its writes when it answers an error**
  (`@Transactional(dontRollbackOn = ApiException.class)`). Otherwise the
  counted attempt and the deleted code would roll back with the error, and
  attempts would never add up.
- **Locks, because up to three instances serve requests.** A code request
  takes `lock table login_mail in exclusive mode` before counting, so
  requests count one after another; verify locks the email's code row.
  Measured without them: 10 parallel requests for one email gave one 202
  and nine 500s (the per-email row raced), and 10 parallel wrong guesses
  all got 400: the attempt counts overwrote each other, the fifth was never
  reached and the code survived. With them: three 202s and seven 429s, and
  exactly one 429 among the guesses. Code requests are few, so a lock over
  all of them costs nothing.
- **The mail goes out after the code is committed.** No lock is held while
  Resend answers (the blocking mailer waits up to 60 s). A failed send
  answers 500 (logged), keeps the stored code and still counts toward the
  limits, which errs on the side of the mail quota.
- **The send log keeps a hash of the email, not the address.** The limits
  must outlast the code rows, which are deleted once used or expired. The
  log only needs to recognise an address again, so it stores its SHA-256,
  and rows older than a day are deleted on each send; expired codes are
  deleted then too. An unsalted hash still lets someone holding the table
  test a known address; it keeps the list of addresses out of it.
- **80 mails a day.** Resend's free plan allows 100 a day and 3,000 a month;
  80 leaves room for test mails sent by hand, and 30 days of 80 stay under
  the monthly cap.
- **Constant-time comparison** with `MessageDigest.isEqual`. With 5 attempts
  per 10-minute code the timing would reveal little, but the comparison
  costs nothing.
- **Logging in rotates the device's session.** Verify deletes the session
  the device sent and sets a new cookie, so a token issued before the login
  stops working. A guest left alone on that device keeps its row.
- **An account on the device and an email without an account gives 401
  `no_session`.** An account cannot take a second address, and the contract
  allows only a guest to become an account.
- **The email check is stricter than "one `@`".** Spaces, control
  characters and the characters that separate or decorate addresses in a
  mail header are refused, so an address can never add a recipient or a
  header line.
- **The mock mailbox is never on in prod.** The native integration tests
  run the binary with the prod profile, so `LoginRoundTripIT` turns the mock
  on through a test resource (`MockMailer`) and reads the code from the mock
  mailbox's log line in `target/quarkus.log`. A missing `RESEND_SMTP_KEY`
  does not stop the service: the other native tests start the binary
  without it. The deploy always sets it.

## 2026-11-13 (later still) - Guest players, results and leaderboards for klad

The game can now create a guest, submit level clears and finished runs, and
read the boards. The game calls these through the site's proxy at
`rassimok.dev/klad/api/*`; the paths below are the API's own.

- `POST /players` creates a guest from a nick and a kind (`human` or
  `agent`, required, never changed) and sets the session cookie.
  `GET`, `PATCH` and `DELETE /players/me` read the player, rename it, or
  delete it. Deleting also removes its sessions and results (`on delete
  cascade`) and clears the cookie.
- `POST /clears` (level, shape, revision, ticks, chests, chests total) and
  `POST /runs` (levels cleared, score, ticks) store results. Both answer
  `201 {}`. A clear must carry its level's current revision.
- `GET /boards/records`, `/boards/level` and `/boards/chests` return the top
  20, one entry per player, separately for each kind. `kind` defaults to
  `human`. `/boards/level` takes `level` and `shape` and always shows the
  level's current revision; a `revision` parameter is ignored.
- Migration `V2` adds `level_clear` and `game_run`, each with a
  `player_id` foreign key that cascades on delete. Each has an index on
  (player, time) for the rate limit; `level_clear` also has one on (level,
  shape, revision) for the level and chests boards. `V1` is unchanged.
- Session cookie `klad_session`: 32 `SecureRandom` bytes, base64url.
  The database stores only the SHA-256 of the token, as lower-case hex. The
  cookie is sent as `Path=/klad/api; HttpOnly; Secure; SameSite=Strict;
  Max-Age=7776000`. A request with a session more than a day old moves its
  expiry to 90 days from now, resends the cookie and updates
  `last_seen_at`. So each session row is written at most once a day.
- Nicks are NFKC-normalised and trimmed, then 3 to 16 characters. Allowed
  are Latin letters `A-Z a-z` or Cyrillic letters (U+0410 to U+044F, plus
  U+0401 and U+0451 for Yo), never both in one nick, and digits, `_` and
  `-`. `nick_key` is the lower-case form. Accented Latin letters are
  refused: "Latin letter" is read as plain A to Z.
- Every bound lives in one class, `Rules`. That covers the level range and
  each level's current revision, the tick floor, the tick and score caps,
  the rate limit and the board size. The contract's field names used by
  more than one class are constants there too.
- Every response carries `Cache-Control: no-store`,
  `X-Content-Type-Options: nosniff` and `Content-Security-Policy:
  default-src 'none'; frame-ancestors 'none'`. That includes errors, the
  framework's own 404 and `/q/health`. They are set by
  `quarkus.http.header` in `application.properties`.
- Errors are `{"error":"<code>"}` with the contract's codes. A missing
  field, or one of the wrong JSON type, fails the endpoint's own check
  (`invalid_nick`, `invalid_kind`, `invalid_result`). `PATCH` answers
  `kind_fixed` whenever the body names `kind`, even as null. Framework
  errors are named after their HTTP status: `not_found`,
  `method_not_allowed`, `unsupported_media_type`, `bad_request` for a body
  that is not JSON at all, and `internal_server_error`, which is logged.
  The one exception is 413 for a body over 4 KB: it carries the three
  headers but no body.
- Tests: unit tests for the nick, kind, revision and result checks.
  `@QuarkusTest` classes cover the cookie, the session refresh, every
  endpoint, ordering, kinds apart, the rate limit (also 40 clears sent in
  parallel), a forged revision, clears of another revision left in the
  table, delete (also with an empty chunked body), a database failure, the
  content types, the body limit and the headers. `BodyLimitLogTest` checks
  that a chunked body over the limit logs no warning or error; it runs on
  the JVM only, where the test can read the log.
  Two native tests run the same HTTP checks against the binary.
  `KladRoundTripIT` creates guests (NFKC and Cyrillic nicks), submits a
  clear and a run, reads all three boards, renames and deletes.
  `ResponseHeadersIT` covers the headers and the error bodies. Both passed
  inside the Mandrel Linux container, so both migrations and the JSON
  types are in the binary. Ranking lives in SQL, so it is tested against
  Postgres, not in a unit test.

### Decisions worth not relitigating

- **The rate limit is counted in the database.** Cloud Run may run up to
  three instances, so a counter in memory would allow three times the
  limit. Each submission locks its player's row (`PESSIMISTIC_WRITE`,
  which Hibernate sends as `select ... for no key update` on Postgres)
  before counting the last 60 seconds of clears and runs. Parallel
  submissions by one player are therefore counted one after another, and
  the 31st is refused with 429. Only stored submissions count: a rejected
  or rate-limited one does not.
- **One entry per player per board.** A board shows each player's best
  result, not their latest. Otherwise one player could fill the top 20.
- **The tick floor is 600 (5 s), a guess.** It sits in `Rules.TICKS_FLOOR`
  so it can move once real clear times are known. It is deliberately not a
  database check, so changing it needs no migration. Only the shape is
  checked in the database, like `kind` in `V1`.
- **The API knows each level's current revision** (`Rules.LEVEL_REVISIONS`,
  mirroring `klad/levels.js` in the site repository; every level is 1
  today). `POST /clears` refuses any other revision with 400
  `invalid_result`, and the level and chests boards count only clears at
  the current revision. The first version accepted revisions 1 to 1000,
  and the chests board counted the newest revision that anyone had
  cleared. Found in review: one forged clear with a higher revision, from
  any guest, removed that level and shape from every player's total on
  both kinds' boards, and ten such clears left only the forger. It stayed
  so until the rows were deleted by hand. A forged score only adds one
  entry; this wiped everyone else's. This is plan 3.4's "known level and
  revision" check. The other option was to let the game pass its
  revisions to the boards, which needs no deploy order but still stores
  whatever revision a client sends.
- **A layout change needs the API first.** Bumping a revision in
  `levels.js` means changing `Rules.LEVEL_REVISIONS` too, and the API must
  deploy before the game: until then, clears of the new revision get 400
  and are lost (the game ignores failed sends). Clears of the old revision
  stay in the table and stop counting, so a changed layout starts its
  boards empty.
- **No CORS.** The game reaches the API through the same origin (the
  site's proxy), so no browser needs a cross-origin answer. Scripts and
  agents can call `api.rassimok.dev` directly, which is fine. A
  `SameSite=Strict` cookie and the JSON-only request bodies keep other
  sites from acting for a player.
- **The security headers are set by the API itself.** Pages applies the
  site's `_headers` to static files only, not to what the proxy function
  returns. `no-store` keeps one player's data out of any cache, and the
  CSP and `nosniff` stop a JSON answer from ever being run as a page.
- **Request bodies are read as JSON trees, not bound to records.**
  Binding to a record coerces `900.5` to 900 and `"3"` to 3 (measured),
  and turns a number beyond 32 bits into a framework 400. Reading the tree
  accepts only a real JSON integer or string, so every such case gets the
  contract's code.
- **Only endpoints that read a JSON body require `application/json`.**
  `POST /players`, `PATCH /players/me`, `POST /clears` and `POST /runs`
  answer 415 for any other type and for none, with or without a body.
  `@Consumes` alone lets a body with no `Content-Type`, or one sent as
  `*/*`, `application/*` or `application/json; version=2`, through to the
  JSON reader (measured); it does refuse `application/octet-stream`. A
  request filter therefore checks every resource method that takes a
  `JsonNode` body (a subtype such as `ObjectNode` counts too). A body
  endpoint must take one: the filter would skip a record, `Map` or
  `String` body without a sign. `ResponseHeadersTest` lists every body
  endpoint with a 415 row for no type and one for octet-stream, which
  `ResponseHeadersIT` also runs on the binary; a new body endpoint adds
  its two rows there. The filter compares type and subtype itself and
  allows only a `charset` parameter: `MediaType.isCompatible` would let
  `*/*` and `application/*` through, and `application/json; version=2` is
  not the contract's type either. A type that does not parse at all
  (`garbage`) gets 415 too: `@Consumes` matching answers it before the
  filter runs, and the filter would treat it as not JSON. Found in review,
  the filter used to read the type of every request with a body and threw
  on such a type, answering 500 with an error stack in the log, from any
  anonymous client.
- **Endpoints that read no body ignore any body and its type** (every
  `GET`, `DELETE /players/me`). Found in the second review: the filter
  used to check every request that carried a body, and an empty chunked
  body counted. If the site's proxy forwards a bodiless `DELETE` that way,
  "delete my nick and results" would answer 415 and the player would stay.
  Checking the endpoint rather than the bytes that arrived also removes any
  dependence on the framing; a body sent over HTTP/2 without a length would
  have slipped past the old check. The filter reads the method's parameter
  types from `SimpleResourceInfo`, which needs no reflection in the native
  binary. The contract's section 1 says the same since this change.
- **`nick_taken` is not checked yet.** Nicks may repeat until account nicks
  become unique in the accounts work, as the contract says.
- **Request bodies are capped at 4 KB** (`quarkus.http.limits.max-body-size`).
  Real bodies are under 200 bytes. The 10 MB default let anonymous
  requests fill an instance's 256 MiB: a 10 MB body parsed into a JSON
  tree held about 309 MB on the JVM (measured in review). Vert.x answers
  413 itself, so the reply has the three headers but no JSON body. With a
  chunked body the JSON reader then fails with an `IOException`. A mapper
  for it hands the exception back to Quarkus once the response has been
  sent, and Quarkus logs it at debug level only. Before that (found in
  review), the generic mapper logged it as an error and Quarkus logged a
  second error for the response already written: about 60 lines and
  6.6 KB of log per request, needing no session. A script could have
  multiplied log ingestion, which Cloud Logging bills above the free
  allowance, and buried real errors. Any other `IOException` while the
  response is still open stays a logged 500.
- **Pooled connections idle for over a minute are checked when taken**
  (`quarkus.datasource.jdbc.foreground-validation-interval=1M`). Neon
  suspends after 5 idle minutes, and a connection held across that is
  assumed dead, so the first request after a quiet spell would answer 500.
  A connection that fails the check is dropped and another is taken.
  `max-lifetime`, idle removal and background validation all run on a
  background timer, which gets no CPU while Cloud Run throttles an idle
  instance, so they cannot fix this. `validate-on-borrow` would also work,
  but it adds a round trip to Neon to every request, and its own docs
  advise against it.

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
