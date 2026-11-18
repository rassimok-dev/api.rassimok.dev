# Changelog

Dated entries, newest first. Written for a reader picking this up cold —
or a future session needing to know what already exists and why.

## 2026-11-18 - Klad levels 1 to 8: levels 1 to 5 at revision 2, levels 6 to 8 new

The site's release branch `release/klad-levels` gives levels 1 to 5 new
layouts (revision 2) and adds levels 6, 7 and 8 (revision 1; RAS-69,
RAS-70, RAS-71). The API accepts only levels and revisions it knows
(RAS-62), so `Rules.LEVEL_MAX` goes from 5 to 8 and `Rules.LEVEL_REVISIONS`
from `List.of(1, 1, 1, 1, 1)` to `List.of(2, 2, 2, 2, 2, 1, 1, 1)`. Every
bound already reads these two constants, so no other main code changes,
and no request or answer changes its shape.

- **What is accepted now.** `POST /clears`: levels 1 to 8, each only at
  its current revision; revision 1 of levels 1 to 5, any later revision
  and level 9 get 400 `invalid_result`. `POST /runs`: `levelsCleared` 0
  to 8 (still at least 600 ticks per cleared level), 9 gets 400.
  `GET /boards/level`: levels 1 to 8, 400 `invalid_query` for 9.
  `POST /rooms/<id>/level`: 1 to 8, 400 `invalid_level` for 9. The chests
  board sums levels 1 to 8, each at its current revision.
- **The two constants must agree.** `Rules.currentRevision` reads the list
  by index: a list shorter than `LEVEL_MAX` throws for the last levels, a
  longer one leaves revisions nobody reads. `RulesTest` compares the
  revisions of levels 1 to `LEVEL_MAX` with the whole expected list, so
  either mistake fails it.
- **Held until the site release (user, 2026-11-18, RAS-62 question 1 a).**
  The rewritten and new levels ship as one release. The level tickets
  collect on the site branch, the user plays its Pages preview, and only
  after the user's OK does this change merge (a merge here deploys),
  right before the site release merges to `main`. The revision check is
  an exact match, so from this deploy until the site release is live the
  live game's clears of levels 1 to 5 (still revision 1) get 400 and are
  lost; the hold keeps that window to minutes. Not chosen: one small API
  change per level ticket (eight deploys and reviews, the boards reset
  one level at a time), or accepting two revisions of a level during a
  roll-out (a contract change, and clears of the old layouts stored but
  never shown).
- **The boards of the rewritten levels start empty (question 2 a).**
  Clears of revision 1 stay in `level_clear`, unseen, and leave the chests
  total, as the revision rule of 2026-11-13 already does: on a new layout
  an old time means nothing. No row is deleted and there is no migration:
  `level_clear.level`, `level_clear.revision` and
  `game_run.levels_cleared` are plain integers without a check (`V2`).
  Not chosen: an archive board for old revisions (a new board parameter
  and a site ticket), carrying the old rows over (times on different
  layouts ranked together) or deleting them (real players' data).
- **The records board stays as it is (question 3 a).** `game_run` has no
  revision, so runs on the old five levels stay on it next to runs on the
  new eight; a full new run outranks them by `levelsCleared`. Telling
  them apart would need a migration, a new `POST /runs` field and a site
  ticket.
- **Forged results gain three levels.** The API checks a clear against
  bounds only and cannot tell a played clear from a forged one. Each
  extra accepted level adds up to 100 chests per shape
  (`CHESTS_TOTAL_MAX`) that one forger can put on the chests board, here
  up to 600 more. Accepted because levels 6 to 8 ship in the same
  release: no level is accepted before it can be played, and a played
  level is open to the same forgery as levels 1 to 5 are today.
- **Native.** `Boards.CHESTS` is a `static final` string that
  `currentRevisionRows()` builds from these constants when the class
  initialises, which GraalVM may do at image build time. The values come
  from constants, so the result is the same, and `KladRoundTripIT` reads
  a level 8 clear in the chests total of the binary.
- **Contract.** `docs/plans/2026-11-13-klad-api-contract.md` in the
  workspace, sections 4 (`level` 1 to 8, `levelsCleared` 0 to 8, the
  revisions) and 7 (a room's `level` 1 to 8), changes with this entry.

### Tests

- `RulesTest`: the revisions of levels 1 to 8 equal
  `List.of(2, 2, 2, 2, 2, 1, 1, 1)`; revision 2 of levels 1 and 5 and
  revision 1 of levels 6 and 8 are current (+4); revision 1 and 3 of a
  rewritten level, revision 0 and 2 of a new one, level 0 and level 9 are
  not (7 cases become 12, +5: the cases `1, 2`, `5, 2` and `6, 1` became
  current and were replaced).
- `ClearRequestTest`: clears of levels 1, 5, 6 and 8 at their revisions
  pass (+4); level 9, revision 1 of levels 1 and 5 and revision 2 of level
  8 fail (+4). The old level 6 case is now level 9 at revision 1, so it
  fails for the level and not, as it would have, for level 1's revision.
- `RunRequestTest`: 8 levels in 4,800 ticks pass (+1); 9 levels and 8
  levels in 4,799 ticks fail (the old 6-level case became valid and is
  now the 9-level one, +1).
- `ResultsResourceTest`: `POST /clears` stores each of levels 1 to 8 at
  its revision (+8); level 9, revision 1 of levels 1 and 5 and revision 2
  of level 8 get 400 and store nothing (+3, the level 6 case is now level
  9); a run of 8 levels is stored (+1); 9 levels and 8 levels in 4,799
  ticks get 400 (+1, the 6-level case is now the 9-level one).
- `BoardsResourceTest`: for each of levels 1 to 5, revision 1 rows of one
  player and another are on neither the level board nor the chests total,
  next to a revision 2 clear that is (+5); one player's clears of levels 1
  to 8 sum to 36 chests, a revision 1 row of 50 chests left out, and level
  8 has its board (+1); `GET /boards/level` answers an empty board for
  levels 1, 5, 6 and 8 (+4) and 400 for 9 (was 6).
- `RoomResourceTest` (and `RoomResourceIT`): the level the tests send is
  8 (was 4), so the host test compares a whole room at level 8. A test of
  its own would add a room, and rooms stay in memory for the whole run:
  the first version did, and the JVM run reached the cap of 100 rooms
  (two `SocketGuardsTest` cases got 429 creating theirs). Level 9 gets
  400 `invalid_level` (was 6; the test is renamed from
  `...givenLevelOutsideOneToFive` to `...givenLevelOutsideOneToEight`,
  because the old name would be false).
- `KladRoundTripTest` (and `KladRoundTripIT`): the round trip's run
  clears 8 levels (was 5); a clear of level 1 at revision 2 and of level
  8 at revision 1 show on the level 8 board and add up on the chests
  board by nick (+1); revision 1 of levels 1 and 5, revision 2 of level 8
  and level 9 get 400 (+4).
- `RoomStoreTest` and `LoginResourceTest` use `Rules.LEVEL_MAX` and pass
  unchanged with 8.
- Runs: `./mvnw test` 1,199 JVM tests (1,152 before), and in the Mandrel
  Linux container 308 native tests (303 before; `KladRoundTripIT` 9,
  `RoomResourceIT` 143) against a binary of the final `src/main`
  (sha256 `2637ec2752efb23a...`), all passing. The production check
  (one clear per level through the site's proxy) runs after the merge.

## 2026-11-17 - Socket guards: session lookups held to the cap, no upgrade-race ERROR, the per-player gap accepted

The RAS-29 round-6 review left three socket items as minors, and RAS-32
deferred them (RAS-37). The user decided on 2026-11-17: 1 a) hold the
slot until the session lookup returns, 2 a) a no-op request exception
handler after `toWebSocket()`, tested with concurrent resets, 3 b) accept
the per-player cap gap and write it down, with no contract change. The
RAS-29 invariant holds: a slot belongs to its TCP connection and comes
back exactly once (`giveBackSlot` is unchanged); only the moment it comes
back changes for a client that leaves during its lookup.

- **At most 20 session lookups at once.** Until now a client that left
  during its lookup got its slot back at its connection's close while the
  lookup still ran, so the cap bounded the lookups only of clients that
  stayed. The sixth RAS-29 review killed a 256 MiB native instance for
  lack of memory in about 15 s with about 4,200 such handshakes a second.
  Now `WebSocketHandshakeCheck` marks a connection while its lookup runs
  (`lookingUp`); its close then leaves the slot to the lookup's end, and
  `afterLookup` sees the closed connection, gives the slot back and refuses
  with 499 as before. Open sockets and lookups in flight share the 20
  slots, and every further handshake gets 503 on the event loop, with no
  worker thread and no database read. For a client that stays nothing
  changes: its slot was already taken before the lookup.
- **Handshakes queued behind an abandoned one get no answer and start no
  lookup.** Vert.x routes a request queued behind another on the same
  connection even after the connection has closed (the sixth review
  counted 540 session reads for 5 connections of 100 queued handshakes
  each). `upgrade` now gives a handshake on a connection that has already
  closed no slot, no lookup and no answer, not even an `end()`. Ending a
  response makes Vert.x route the next queued request inside that call,
  so with no `end()` no handshake behind it is routed, and the queue goes
  with the connection (a non-upgrade request queued behind it still
  reaches the router before this check; see the follow-up below). The first version of this change answered such a
  handshake 503 instead (`takeSlot` reported no slot on a closed
  connection). The 2026-11-17 security review showed the cost: each 503
  routed the next handshake inside its own `end()`, one stack level per
  queued handshake, and on the JVM 8,000 of them behind one abandoned
  lookup overflowed the event loop's stack. A `StackOverflowError`
  between taking a slot and giving it back lost the slot for good (5
  slots in 12 rounds, no log line). The native binary at 256 MiB lost
  none up to its memory limit, but that margin came from its stack and
  frame sizes, not from the code. A 503 could not reach a client that
  has gone anyway. `takeSlot` still reports no slot on a closed
  connection, but `upgrade` now stops such a connection before it.
  Any answer given on the event loop to a pipelined request still routes
  the next one inside its `end()` and nests the same way: the refusals
  (400, 426, 503, 403) of handshakes on a connection that stays open, and
  non-upgrade requests (the router's 404) queued behind an abandoned
  lookup, also on a closed connection (round 2 review: a
  `StackOverflowError` on the JVM at 3 rounds of 8,000, no slot lost).
  Main did the same before this change; it is left for a follow-up.
- **No "Result is already complete" ERROR.** After `toWebSocket()` the
  check replaces the request's exception handler, which Vert.x 4.5 had set
  to fail the upgrade, with one that does nothing. A close in the one
  event-loop task before the handshake used to fail the upgrade; Vert.x
  then completed it anyway and Quarkus logged `IllegalStateException:
  Result is already complete` with a stack trace (about 3 in 100
  handshakes abandoned in a burst, sixth review; no slot was lost). Now
  the upgrade completes on the closed connection, and the check does not
  open it: Vert.x never calls the close handler of a socket made after its
  connection closed, so an opened one would count as connected in the race
  with no `onClose` to come, and could replace a live socket of the same
  member. The connection's close gives the slot back, as for every upgrade
  that ends without a socket. `hasClosed` reads the connection's close
  future, the signal the close listener hangs on, so both always agree.
- **The per-player cap stays as it is (accepted, not built).** One cookie
  can hold several of the 20 slots while its replaced sockets close. Since
  RAS-32 every server-started close, `replaced` included, drops the
  connection after 1 s (`Rules.SOCKET_CLOSE_GRACE`) whatever the client
  does, so a member who reconnects in a loop holds one slot for the live
  socket plus about one for each reconnect in the last second. Only a
  scripted client reconnects that fast (the game backs off, at most 8
  reconnects a race), and every reconnect is a handshake with a session
  lookup inside the cap. Refusing a member's handshake while the old
  socket closes would change contract section 8 ("a member connecting
  again replaces the old socket"), so it is not done.
- These replace four statements of earlier entries, which stay as they
  were written: in 2026-11-13 (seventh), "The cap bounds the session
  lookups only while their clients stay connected", "Session lookups in
  flight are not bounded by the cap" and "The line comes from inside
  Vert.x and cannot be removed from the endpoint"; in 2026-11-13 (ninth),
  "Round-6 review items, deferred as follow-ups".
- Corrected in place, dated: the "Slow readers" lines of 2026-11-16 (RAS-55)
  and 2026-11-15 (later still, RAS-43) said the game does not reconnect
  after a `not_reading` 1008. The client sees a reset and reconnects
  (measured 2026-11-16). The code path is unchanged.
- No new dependency; `application.properties`, `deploy.yml` and the
  contract are unchanged.

- **Measured on the native binary** of this branch (sha256 `bf4e2af9...`,
  built from the `src/main` of the first version, before the closed check
  in `upgrade`; not measured again, since that check changes only what a
  handshake on a closed connection gets, and the flood below sends one
  handshake per connection), and for comparison on a native binary of
  main's socket code (`4427cbb6...`, found in the checkout's `target/`,
  dated 2026-11-16 20:30, its source tree not recorded; its strings hold
  RAS-39's `klad_oauth`, so it was built after RAS-72, which last changed
  `WebSocketHandshakeCheck`, `OpenSockets`, `Sessions` and `SocketSender`
  on main). Local Docker only, never production: the server at `--memory
  256m --memory-swap 256m --cpus 1`, a throwaway `postgres:18` with
  `log_statement=all`, both fresh for every row, and raw-socket clients in
  containers of their own. The flood is the sixth review's: the allowed
  Origin, an unknown cookie, the connection closed right after the write.
  Sizes in MiB; `memory.peak` and `anon` (sampled every second) from the
  server's cgroup; idle about 97 to 103 peak and 20 to 25 `anon`. After
  each row in which the server survived, 25 handshakes at once got 20
  times 101 and 5 times 503; no row logged anything at WARN or above. With
  an unknown cookie no flood handshake reaches `toWebSocket()`, so these
  rows say nothing about the "Result is already complete" ERROR; that is
  shown on the JVM only (Tests).

  | Row | Main's binary | This branch |
  |---|---|---|
  | about 4,200 a second for 30 s (six clients of 700 each), Postgres held to 0.1 CPU so that lookups are slow, 2 runs | killed for lack of memory both times, 5 to 7 s into the flood; `anon` at most 166.3 and 105.4; about 3,700 lookups | survived both times; peak 140.8 and 141.5, `anon` at most 60.4 and 61.0 and back to 18 after; 19,800 and 18,600 lookups, the rest refused with 503; `/status` at most 3 ms |
  | the same with Postgres not held, 1 run | survived; peak 138.4, `anon` at most 58.9; 121,300 lookups for 122,400 handshakes | survived; peak 139.9, `anon` at most 59.5; 120,600 lookups for 123,700 handshakes |
  | as fast as 4 or 8 client threads go (7,100 to 8,800 a second), Postgres not held, 3 runs | survived 3 of 3; peak 254.2 to the limit (page cache reclaimed), `anon` at most 126.0 to 143.9; 7,000 to 8,000 lookups | survived 3 of 3; peak 238.5 to the limit, `anon` at most 106.6 to 155.9; 7,000 to 8,700 lookups |
  | as fast as 16 client threads go (7,600 to 8,800 a second where the client finished and counted), 2 runs | killed 2 of 2 | killed 1 of 2; the other survived at the limit, `anon` at most 124.7 |
  | `GET /status`, which reads no database, as fast as 8 client threads go (about 5,100 a second, counted on this branch's run), 1 run | killed | killed, 36 s after the flood ended |

  - The first row is the case this change is for, and it reproduces the
    sixth review's kill: with slow lookups, main's code queued one for
    every abandoned handshake and died within seconds. With the hold, at
    most 20 ran, and memory stayed far below the limit.
  - With fast lookups nothing queues, and both binaries behave alike. In
    the unpaced rows the server saturated on connections before lookups
    could queue: both binaries ran about the same number, so those rows
    show no benefit of the hold, and no cost.
  - Above about 7,000 abandoned connections a second a 256 MiB instance
    can die on either binary, and a flood of `GET /status` kills it at
    about 5,100 a second without any database. That limit belongs to the
    instance, REST included, not to the socket guards; its cause was not
    traced. It is an open item for a decision (REQ-001), not part of this
    ticket.
  - One client container held to 4,200 a second ran out of local ports
    after about 28,000 connections (each closed connection waits 60 s in
    TIME_WAIT), so the paced rows use six. At the run.app URL the
    container sees Google's front end, not the client, so local connection
    churn overstates what reaches it (assumed, as in RAS-29).

### Tests

- `SocketGuardsTest` (+4, so `SocketGuardsIT` runs them natively; each
  ends by opening the whole cap at once):
  - `shouldRefuseHandshake_whenConnect_givenCapOfHandshakesAbandonedDuringSlowLookup`:
    the test's own transaction locks the `session` table, so every lookup
    waits; 20 handshakes are sent and closed, all 20 lookups are seen
    waiting for the lock (`pg_stat_activity`), and a 21st handshake kept
    open reads 503 at once. On the old code it got a slot and waited.
  - `shouldStartNoLookup_whenConnect_givenHandshakeQueuedBehindOneAbandonedDuringLookup`:
    20 connections each send a handshake with an unknown cookie and,
    behind it in the same write, one with a valid cookie, then close. The
    first waits for the locked `session` table; once that is free, the
    queued one would read the session and then wait for the also locked
    `player` table. No statement waits there afterwards; with neither the
    closed check in `upgrade` nor the `takeSlot` return, 20 did.
  - `shouldOpenSocketsUpToCap_whenConnect_givenThousandsOfHandshakesQueuedBehindOneAbandonedDuringLookup`:
    5 rounds of one handshake waiting for the locked `session` table with
    8,000 more behind it in the same write; the client resets, then the
    lock is freed. Each round sees one lookup waiting.
  - `shouldOpenSocketsUpToCap_whenConnect_givenHandshakesResetAsLookupsReturn`:
    20 handshakes wait for the locked `session` table together; once it is
    free, the clients reset one after another, 1 ms apart, so the resets
    land before, during and after the upgrades.
- `AbandonedHandshakeLogTest` (+1, JVM only, it reads the log):
  `shouldLogNoWarningAndLeaveNoMemberConnected_whenConnect_givenHandshakesResetAsLookupsReturnOnBusyServer`
  runs the same resets 3 times while a test verticle keeps every event
  loop busy: twice as many instances as processors (Quarkus runs at most
  one event loop per processor, and at least 2), each with 100 tasks that
  spin for 20 microseconds and queue themselves again. Vert.x reads the
  network only between batches of tasks, so on an idle test server a
  reset almost never lands in the gap before the handshake: 2,000 to
  6,000 resets from 20 threads without the busy loops logged the ERROR 0
  to 7 times on this Mac. With them: no WARN or ERROR line, a new socket
  of each room sees no other member connected, and 20 fresh seats open at
  once.
- Checked by mutation, each change on its own (JVM): without the no-op
  handler the log test failed every run (4 of 4, 22 to 40 ERROR lines a
  run); without the closed-connection check in the upgrade callback it
  failed every run (3 of 3), with members shown connected (with the check,
  16 to 17 upgrades in 5 rounds completed on a closed connection and were
  not opened); with the close listener giving the slot back during a
  lookup, the slow-lookup test got no answer. Without the closed check in
  `upgrade`, the deep-queue test lost a slot in 2 of 3 runs (a seat got
  503 until the deadline, nothing logged). That catch depends on the
  platform: 2 of 3 on macOS (arm64), 0 of 3 in a Linux arm64 container
  and 1 of 3 there with `-Xss1m` (round 2 review); the x64 CI runner is
  not measured, and the native binary lost none. The test proves the
  slots come back, not that a regression is always seen. Without that check and with
  the old `takeSlot` return as well, the queued-handshake test saw 20
  lookups waiting; with the `takeSlot` check alone (the first version) it
  passed.
- Runs: `./mvnw test` 1,152 JVM tests (1,147 before), and in the
  Mandrel Linux container 303 native tests (299 before) against a binary
  of the final `src/main` (sha256 `eb4a2a69...`), all passing.

### Decisions worth not relitigating

- **The slot is held, not a second semaphore.** The sixth review offered
  both. Holding the slot (the user's choice) keeps one count for sockets
  and lookups, so 20 is still the most the instance does for sockets at
  once, and no new state is needed beyond the set of connections looking
  up.
- **A lookup that never returns keeps its slot.** The old backstop (the
  connection's close) could not leak; the hold gives that up for lookups.
  A lookup waits at most Agroal's acquisition timeout for a pooled
  connection (5 s, the quarkus-agroal 3.39.3 default, not set here), but a
  query on a database connection that hangs is not bounded in the app
  (pgjdbc 42.7.13 has no socket timeout by default). A full database hang
  stops REST as well; on a partial hang (a few pooled connections stuck
  mid-query) each stuck lookup keeps its slot until the instance restarts,
  while REST goes on with the rest of Agroal's pool of 50 (`max-size` is
  not set here). Twenty stuck lookups refuse every handshake with 503
  while `/status` and the room poll still answer. Not tested; read from
  the source in the 2026-11-17 review, which rated it unlikely: the
  lookups are two indexed reads, and nothing on a serving path takes a
  table lock. A bound (a pgjdbc `socketTimeout`, which would bound REST
  and Flyway too) is a decision for the user, not taken here.
- **The no-op handler depends on the request wrappers passing it on.**
  Vert.x Web's and Quarkus's own wrappers in front of the request today
  hand `exceptionHandler` to Vert.x's request (read in vertx-web 4.5.33 and
  quarkus-vertx-http 3.39.3). `QuarkusRequestWrapper` keeps it to itself;
  Quarkus puts that wrapper in front only with the HTTP access log, a
  same-site cookie setting or a shutdown timeout (`VertxHttpRecorder`),
  none of which is set, and in dev mode for the Dev UI's access log, so
  `quarkus:dev` can still log the ERROR. If one is set later, the ERROR
  comes back (log noise only: the slot still comes back, and the closed
  check in the upgrade callback still keeps the socket from opening).
  `AbandonedHandshakeLogTest` fails only if the setting also applies to
  the test profile; a `%prod`-only setting, such as
  `%prod.quarkus.shutdown.timeout`, is not caught.
- **Upstream.** Vert.x's `Http1xServerConnection.createWebSocket` should
  complete the upgrade with `tryComplete`; 4.5.35 still calls `complete`
  (sixth review). Not reported from here.
- **Busy event loops in a test.** The race is between two event-loop tasks
  of one connection and needs the network read in between, which a
  black-box client cannot force. Tasks that keep the server's event loops
  busy, from a JVM-only test, make it happen every run without a test hook
  in the production code. RAS-72 made the opposite call because its race
  was inside one method call.
- **Through the site's proxy** an abandoned handshake costs a Workers
  request, as before. The lookups behind the cap are what a raw client at
  the run.app URL could pile up.

## 2026-11-16 (later) - Sign in with Google, merged switched off

Players can open or create their klad account with Google instead of
waiting for an emailed code (RAS-39, contract sections 1, 2 and 5). Google
only proves the email address; from there the account rules of the code
login apply unchanged. The user decided on 2026-11-15, as recommended:
Q1 A a full-page trip, Q2 A a second short cookie, Q3 A three steps, Q4 A
the nick prompt after the return, Q5 A one account per address, Q6 A
scope `openid email` and nothing stored but `player.email`, Q7 A the
address waits in memory, Q8 A the JDK's HTTP client and no new library,
Q9 A client id and secret from Secret Manager, and Q13 B: this merges
switched off.

- **Switched off.** Without `GOOGLE_CLIENT_ID` and `GOOGLE_CLIENT_SECRET`
  the service starts as before, `GET /login/google` and its return send
  the player to `https://rassimok.dev/klad/#login=google-unavailable` and
  set no cookie, and `POST /login/google/finish` answers 400
  `no_google_login`. `deploy.yml` is unchanged; the switch-on is the two
  secrets (`google-oauth-client-id`, `google-oauth-client-secret`) in the
  one `--set-secrets` flag, after RAS-41 and RAS-44.
- **`GET /login/google`**: 302 to Google's authorization page with
  `scope=openid email`, the exact redirect address
  `https://rassimok.dev/klad/api/login/google/callback`, `state`, `nonce`
  and a PKCE challenge (S256), and the cookie `klad_oauth` (32 random
  bytes; `Path=/klad/api/login/google; HttpOnly; Secure; SameSite=Lax;
  Max-Age=600`). Nothing is stored: `state`, `nonce` and the PKCE verifier
  are HMAC-SHA256 of fixed labels keyed by the cookie.
- **`GET /login/google/callback`**: always a 302 back to the game,
  `#login=google`, `#login=google-failed` or `#login=google-unavailable`.
  Refused before any call to Google: no cookie, a `state` that is not the
  cookie's (constant time, exactly 43 characters), an `error` from Google,
  no `code` or one over 1,024 characters, a return already used. Then one
  POST to Google's token endpoint with `java.net.http.HttpClient` (3 s to
  connect, 5 s for the whole call, at most 4 at once per instance), and
  the ID token's claims: `iss`, `aud`, `exp`, `nonce`, `email_verified`
  and a valid address after `Emails.normalize`. The verified address then
  waits in memory for 10 minutes and the cookie is set again for as long.
- **`POST /login/google/finish`**: reads no body; takes the waiting
  address and runs exactly what `POST /login/verify` does after its code
  check, now one method both call (`AccountLogins.signIn`, moved out of
  `LoginResource.verify`). 401 `no_session` and 409 `nick_taken` leave the
  address waiting, so the game creates a guest or renames it and finishes
  again without a second trip to Google. 200 clears `klad_oauth`.
- **Stored and logged.** The database gets only `player.email`, as after
  a code login; no Google id, name, picture or token, and no migration.
  Log lines name an HTTP status, an exception class or a claim
  (`Google token call failed: status 400`, `Google ID token refused:
  email_verified`), never an address, code, token or the secret.
- **One lock for both logins.** `AccountLogins.signIn` takes a
  transaction advisory lock on the address first
  (`pg_advisory_xact_lock(hashtextextended(email, 0))`). Two devices
  finishing a Google sign-in for the same new address at once would
  otherwise both turn their guest into the account, and the second would
  fail on `player.email`'s unique index with a 500 whose logged database
  detail holds the address. Measured without the lock: the second finish
  answered 500, and both Hibernate's warning and the `Unexpected error`
  line carried `Key (email)=(<address>)`. Verify was already safe through
  the code row lock; it now also takes the advisory lock, after the code
  row, so the order is the same in both and nothing can deadlock.
- `application.properties`: the client id and secret from the
  environment, and the fixed addresses (Google's authorization and token
  endpoints, the redirect address, the game) next to
  `klad.websocket.allowed-origin`. No profile prefix, so a test resource
  can set them.

### Tests

- Unit, fixed clock (`GoogleLoginsTest`, 73): the derived `state`,
  `nonce`, verifier and challenge against `openssl dgst -hmac` vectors and
  RFC 7636's example; the exact authorization address and cookies; the
  state check (another cookie's state, one character more or less, a
  malformed cookie); the code's length bound; settings missing or blank;
  the claim checks, each failing claim refused (another issuer, an
  audience list, `exp` now or past or not an integer, another nonce,
  `email_verified` false, `"false"`, `"TRUE"`, 1, absent or null, an
  invalid or missing address) and malformed tokens; the waiting address
  given once, given back, kept to the second before 10 minutes and gone
  at 10; the cap of 1,000 waiting addresses; used returns refused until
  their 10 minutes are over, and at their cap of 1,000 the oldest makes
  way without touching a waiting address.
- `@QuarkusTest` against a fake Google (`FakeGoogle`, a test resource on
  the JDK's HTTP server; it keeps no state, the test writes the code and
  the code says who signed in and how the endpoint answers, and it checks
  the client, the redirect address and the PKCE verifier as Google does):
  `GoogleLoginResourceTest` (34) covers every "Done when" line of the
  ticket: the exact `Location` and `Set-Cookie` of the start and of the
  return; eight returns that sign nobody in and make no call (counted at
  the fake); a reused return, also after a failed one; an error answer,
  a non-JSON answer, an unverified address, another nonce, a code issued
  for another start; a slow Google cut at 5 s; 4 slow calls in flight and
  the fifth answered `google-unavailable` at once; a guest becoming the
  account with nick, kind and results; a merge into an account made by
  code and a guest of the other kind left alone; 401 then `POST /players`
  then 200, and 409 then a rename then 200, both with no second call; an
  account on the device and a new address (401); a second finish (400);
  two devices finishing the same new address at once (both 200, one
  account); a scan of every table after a sign-in (the address only in
  `player`; the code, the tokens, `sub`, the secret, the cookie, the state
  and the nonce nowhere); a scan of every log record of a sign-in with
  three failures (only the three expected lines from `GoogleLogins`, none
  holding an address, code, token, `sub`, cookie or the client secret).
- `GoogleLoginRoundTripTest` (4): start, return and finish over HTTP only.
  `GoogleLoginRoundTripIT` runs the same against the native binary, which
  calls the fake over HTTPS: the fake serves a certificate made with the
  JDK's `keytool` at start, and the binary gets a trust store holding only
  that certificate through `javax.net.ssl.trustStore*` (GraalVM reads
  these at run time). So the outgoing TLS call is proven in the image.
- `ResponseHeadersTest` (+8), and so `ResponseHeadersIT` on the binary,
  which runs without Google settings like production today: the start and
  the return answer 302 to `#login=google-unavailable` with the three
  headers and no `Set-Cookie` at all; finish answers 400
  `no_google_login`, also with an empty chunked body and no type; the
  405s.
- `LoginResourceTest`, `LoginRoundTripTest` and the other login, logout
  and deletion tests pass unchanged after the move to `AccountLogins`.
- Runs: `./mvnw test` 1,147 JVM tests (1,028 before), and in the Mandrel
  Linux container 299 native tests (287 before), all passing, against a
  binary built after the last change to `src/main`.

### Decisions worth not relitigating

- **400 `no_google_login`** when no address waits for the cookie (none
  sent, expired, finished, or the return failed): a code of its own, so
  the game never confuses it with `no_session` (create a guest) or
  `nick_taken` (rename).
- **Both clocks from the return.** The start stores nothing, so the
  server cannot know when it was; the successful return sets the cookie
  again for 600 s and keeps the address for 10 minutes from the same
  instant. The Google page and the nick prompt each get 10 minutes.
- **A return is used once, a failed one too, and the store cannot be
  flooded.** Any start yields a cookie and a state for free, so used
  returns are kept apart from waiting addresses: 1,000 of them, the
  oldest making way, for 10 minutes. A flood of fake returns costs a
  token call each, pushes out only other used returns and never a
  waiting address; Google's single-use code stays the guard behind them.
  Only a real Google sign-in adds a waiting address (cap 1,000).
- **`google-unavailable` when busy, `google-failed` otherwise.** All 4
  token calls in flight or 1,000 addresses waiting means "try again
  later"; everything else is a failed sign-in.
- **`email_verified` as `true` or `"true"`.** Google's own example token
  writes the string; real tokens carry the boolean. Anything else is
  refused. `aud` must be a string equal to the client id; Google sends
  one, so a list is refused rather than searched.
- **The finish takes the address out while it runs** and gives it back
  on any error, so of two finishes with one cookie exactly one signs in.
  It rolls back its writes on an error (it has none to keep), while
  verify keeps `dontRollbackOn` for its counted attempt; the shared method
  joins the caller's transaction and has no annotation of its own.
- **The return never answers JSON.** It is a full page in the browser,
  so even an unexpected exception ends in `#login=google-failed`, with one
  ERROR line naming only the exception class.
- **HTTP/1.1, and the whole call bounded** with `sendAsync(...).get(5 s)`
  plus the request timeout, so a slow body cannot hold a thread either;
  the slot is released in `finally`.
- **The advisory lock waits without a timeout,** unlike code requests:
  it is held only by a login's own short transaction, never across a call
  to Google or a mail. Mapping the `player_email_key` violation instead
  would still have left the address in Hibernate's warning.
- **Log lines may name a claim or say "busy".** Besides a status and an
  exception class, `GoogleLogins` writes the name of the refused claim
  (`iss`, `aud`, `exp`, `nonce`, `email_verified`, `email`) and
  `Google sign-in refused: busy`: fixed words that tell why sign-ins fail
  at the switch-on, with nothing from the player or from Google in them.
- **No profile prefix on the Google settings.** `%prod.` would beat the
  system properties a test resource sets for the native test; an empty
  `${GOOGLE_CLIENT_ID:}` counts as missing, which is "off".
- **`GoogleLoginRoundTrip` is an abstract class** that both
  `GoogleLoginRoundTripTest` and `GoogleLoginRoundTripIT` extend, instead
  of the IT extending the JVM test: test resources are inherited, and the
  two need the fake set up differently (HTTP and HTTPS).
- **`TestSql.tablesHolding`** is the table scan of `LoginResourceTest`,
  shared for the new tests; the private copy in `LoginResourceTest` is
  left as it is.
- **The JVM tests talk to the fake over HTTP, the native test over
  HTTPS.** The test JVM's default TLS context may already be fixed by an
  earlier test class, and the production code keeps the default trust
  store with no setting of its own; only the binary under test gets the
  test trust store, through system properties.
- **Not verified here:** the image's built-in CA store against Google's
  real certificate; real browsers sending the `Lax` cookie on the return;
  the native tests on the CI runner (the PR's test job runs only the JVM
  tests, so `GoogleLoginRoundTripIT` first runs in the deploy job, before
  any image is pushed); the memory the HTTP client and TLS take on the
  256 MiB instance once switched on; and production. The production check of the switched-off state is the
  `curl` of `/klad/api/login/google` after the deploy; the full sign-in is
  checked in RAS-44 after the switch-on.

## 2026-11-16 - Chat in a race reaches the other members' race sockets at once

The user wants players to write to each other in a game during a pause,
and to see new chat messages as pop-ups that do not get in the way of play
(RAS-55, contract section 8). This is the API part; the game shows them in
RAS-56. The user decided on 2026-11-15: Q11 a (the player's own pause in a
race and the race's waits only; no pause for everyone, no chat in a solo
game) and Q12 a (send by the REST endpoint of RAS-53, deliver over the
race socket). Q1 to Q9 stay as answered on RAS-53: room-only, memory only,
at most 30 messages per room, never in the database or a log line.

- While a room is `starting` (before and during the countdown) or
  `racing`, a chat message accepted by `POST /rooms/<id>/messages` also
  goes at once to the race socket of every other member who is in the race
  and connected at that moment:
  `{"type":"chat","n","member","slot","nick","kind","guest","text"}`.
  A member who has finished still gets it. It never goes to the sender's
  socket (the 201 answer is their copy), to a member who left or counts as
  left, to a member whose socket is gone at that moment, or to a replaced
  socket. A refused send (400, 404, 429) reaches no socket. A send that
  arrives once the race's end fell due is kept, but goes to no socket,
  because the store applies the end first.
- No replay: `state` carries no chat, and a member who connects or
  reconnects gets no earlier message over the socket. After the race the
  room poll lists the race's messages with the rest (the room's last 30),
  as RAS-53 keeps them.
- Clients send nothing new on the socket. `{"type":"chat",...}` from a
  client is an unknown type and gets `bad_message`, and the socket stays
  open. By the ticket's reading of the site code (not run here),
  `receive()` in `klad/race.js` reads known types only, so the API can
  deploy before RAS-56.
- Code: `Room.send` stores the message as before and then relays it
  (`Room.relay`): the frame is built once with Vert.x `JsonObject`, like
  `pos`, and queued through each socket's `PlayerSocket.send` while
  `RoomStore`'s lock is held, so it goes through the queued `SocketSender`
  (the lock never waits; 8 unfinished sends close a socket with 1008
  `not_reading`) and keeps its place among `pos`, `countdown`, `go` and
  `results`. `Rules.FIELD_TEXT` now holds `"text"` for `RoomResource` and
  `Room`. `RaceMessage` and `WebSocketHandshakeCheck` are unchanged. No new
  dependency, no configuration change.
- Contract section 8 (workspace) lists the new server message, its fields
  and its delivery rules, and says that a client `chat` is `bad_message`.

### Load, by the ticket's facts

- **Traffic.** In a room of 10 each socket already receives 45 positions
  a second (RAS-43). At the rate limit (5 per player per 10 s) the nine
  others add 4.5 chat messages a second on average, each up to about 500
  bytes (120 dashes or curly quotes, which the allow-list admits, are 360
  bytes of UTF-8; 120 Cyrillic letters are 240; a Cyrillic nick up to
  32). The limit allows the
  5 back to back, so in the worst instant all nine others burst at once and
  one socket gets 45 chat frames in a row on top of the positions.
- **Slow readers.** A socket with 8 unfinished sends is closed with 1008
  `not_reading` (`SocketSender`). Corrected 2026-11-17 (RAS-37): this line
  said the game does not reconnect after 1008, but the client never gets
  that 1008. `SocketSender` queues it behind the sends the client has not
  read and drops the connection at once, so the client sees a reset (1006
  in a browser) and the game reconnects (measured 2026-11-16, workspace
  `docs/plans/2026-11-16-ras-55-throttled-reader.md`). More traffic brings
  a phone on a slow link closer to that. The measurement below sent the
  bursts, not an even spread, as the harder case for this rule.
- **Memory.** Only races without chat were measured: a race of 4 (RAS-32,
  109.3 of 256 MiB) and races of 10 (RAS-43, about 147). A race with chat
  is measured below.
- **Requests.** Each sent message is one Workers request and one session
  read from Neon, as in the lobby (RAS-53); receiving costs nothing extra.
- **Abuse.** Only the race's own members reach the sockets: a join is
  refused outside `lobby` (409 `not_in_lobby`), so the fresh guests taking
  turns in a free place that RAS-53 noted for the lobby cannot feed a
  race. A race of 10 gets at most 50 messages per 10 s, each to at most 9
  sockets. A member who floods is held at 5 per 10 s like everyone, and the
  others' game shows what arrives; hiding a sender is RAS-54's "hide this
  player" by member number, on the device.
- **Privacy.** The text now also leaves the API in socket frames, to the
  members who could read it in the room poll anyway. It still goes into no
  log line (`ChatLogTest` covers the socket path now).

- **Measured on the native binary** of this branch, built after its last
  change to `src/main` (the binary the 287 native tests then ran against),
  set up as for the RAS-32 and RAS-43 tables: `--memory 256m
  --memory-swap 256m --cpus 1`, a throwaway `postgres:18`, a raw-socket
  client in a second container that sets up the room over REST, each row
  on a fresh server container. `memory.peak`, and `anon` sampled every
  second, come from the container's cgroup; sizes in MiB. Each row is one
  full race of 10 (the room maximum): 120 s of positions at 5 a second,
  every member sending one `pos` in the same instant as all the others,
  with a `ping` every 20 s, as for RAS-43; then the members finish from
  slot 9 down. On top, every member sends chat by REST on threads of its
  own, at the rate limit, each text 120 Cyrillic letters (the longest in
  characters; 240 bytes, so the burst bytes below are about 1.35 times
  under the 360-byte maximum of 120 dashes or curly quotes).
  In the burst rows all 10 members send 5 messages back to back in the
  same instant, every 10.5 s; in the spread row each member sends one
  every 2.1 s, the members staggered. Idle: 97.7 to 103.4 peak, 21.2 to
  23.2 `anon`.

  | Row | Peak | `anon` max | What happened |
  |---|---|---|---|
  | one race of 10, positions at 5 a second, chat bursts at the rate limit, run 1 | 153.6 | 67.7 | 600 of 600 chat messages accepted (no 429), each delivered to all 9 others once (5,400 frames), none echoed, to only some or twice; 6,000 of 6,000 positions relayed to all 9 others (54,000 frames), none echoed; 60 of 60 pongs; `/status` 122 of 122 times 200 during the race, at most 10.5 ms; no socket ended early, none with `not_reading`; the slowest send 16.1 ms; 10 places in finish order, 10 times 1000 `race_over`; the room back in `lobby`, nobody ready, its poll listing the last 30 messages (up to `n` 600); 6.7 s of CPU |
  | the same, run 2 | 147.3 | 67.6 | the same counts; `/status` at most 8.0 ms; the slowest send 13.8 ms; 6.7 s of CPU |
  | one race of 10, positions at 5 a second, chat spread evenly at the rate limit | 144.5 | 64.7 | 565 of 565 accepted (no 429), each delivered to all 9 others once (5,085 frames), none echoed; positions, pongs, results and closes as above; `/status` 122 of 122, at most 7.7 ms; no socket ended early; the slowest send 27.6 ms; 7.5 s of CPU |

  - Before measuring, a row counted as not fitting on any of these, as for
    RAS-43: an OOM kill or restart, a peak above 80 % of the limit (204.8
    MiB), any `not_reading` or other early close, any chat message or
    position that reached only some members, was echoed or came twice, or
    a `/status` that was not 200 or took over 1 s. Every row fits.
  - Against the RAS-43 race of 10 without chat (146.6 peak, 64.6 `anon`
    max, 6.0 s of CPU), the chat rows peak 144.5 to 153.6 and `anon`
    stays at 64.7 to 67.7, within the spread between two runs; chat adds
    about 0.7 to 1.5 s of CPU over the 2 minutes. `anon` levels off
    within 30 s and stays flat, the room holding its 30 messages.
  - Nothing was logged at WARN or above, no log line held the chat
    letters (run 1 checked the raw letters; the other two rows also the
    JSON escapes, each server logging 15 lines in all), and no socket was
    closed with `not_reading`. The clients read as fast as the
    frames came; a slow phone link was not measured.

### Tests

- Unit, fake clock: `RoomStoreRaceTest` (+14). The RAS-53 case that
  pinned "a send in `racing` sends nothing over the race sockets" now pins
  the opposite: the message is kept, the other socket gets the `chat`
  frame whole, the sender's gets nothing. New: in `starting` with one
  member not yet connected, only the connected other gets it, and the late
  member's connect brings `state` and `countdown` but no replay (the poll
  has the message); a send during the countdown lands between `countdown`
  and `go`; a finished member gets the others' chat and its own reaches the
  others; a member who left gets none after the leave (the recording
  socket would show a send after its close); a member whose socket is gone
  gets none, and none on reconnect; after a replace only the new socket
  gets it; the sixth message within 10 s (429) and a non-member's send
  (404) reach no socket; `pos`, chat, `pos` and then `results` arrive in
  that order and a chat after the results reaches no socket, while the
  poll lists all three; a send at the instant the 4-minute cap fell due is
  kept and the sockets get only `results`; in a race of 10 each socket gets
  the 9 others' messages in order, never its own; an agent account renamed
  during the race sends with `kind` agent, `guest` false and the new nick
  (as the poll shows it), and a text with quotes, a backslash and Cyrillic
  letters arrives intact. `RaceMessageTest` (+2): `{"type":"chat"}` and
  `{"type":"chat","text":"hello"}` from a client parse as bad.
- `@QuarkusTest`, real sockets: `RaceSocketTest` (+2). A whole race of
  three: a chat before `go` (the third not yet connected), one while
  racing, one to a member who has finished, the third leaving by REST
  (1000 `race_over`, nothing more on its socket), one after that leave,
  then the results with the third as left, and the host's room poll
  listing all four messages whole. A ping after each send gets the pong
  as the very next frame, so an echo would show. A second race: a
  non-member's send is 404, a two-line text 400, five sends 201 and the
  sixth 429; the other member's next frames are exactly the five messages
  and then its pong; a client `{"type":"chat","text":"hello"}` gets
  `bad_message` and the socket stays open.
- `SocketGuardsTest` (+1): the whole cap (two rooms of 10) is opened with
  clients that read nothing, every member sends 5 messages of 120
  Cyrillic letters (the sixth is 429), the clients go, and the whole cap
  opens again.
  `SocketRoute` gets `sendUnpromptedAtLimit` for this, so a later socket
  route has to say what its own traffic at its limits is; `RaceRoute`
  counts `chat` among the messages the route sends on its own.
- `ChatLogTest` (+1, JVM only, production JSON log format): a marker text
  sent during a race reaches the other member's socket in a `chat` frame,
  the same marker in a client `chat` frame gets `bad_message`, and no log
  line or record holds it.
- Native: `RaceSocketIT` and `SocketGuardsIT` run the new cases against
  the binary.
- Runs: `./mvnw test` 1,028 JVM tests (1,008 before), and in the Mandrel
  Linux container 287 native tests (284 before), all passing.

### Decisions worth not relitigating

- **The frame carries the stored message's fields**, so for one `n` the
  socket and the room poll agree: the nick, kind and guest mark at the time
  of sending, which can differ from the race's `nick` in `state` and `pos`
  after a rename during the race. `slot` comes from the sender's racer, so
  the game can tie the message to a runner. There is no `you`: the frame
  never goes to its sender.
- **Only the sockets connected at that moment.** A member whose socket is
  gone, or who connects later, reads the message in the room poll after
  the race; `state` gets no chat history, so a reconnect stays as small as
  before. RAS-56 has to read the poll after the race if it wants what was
  missed.
- **Relayed in `Room`, under the lock**, as `leave` reaches the sockets
  through `remove`: the room queues the sends, and each socket writes on
  its own event loop after the lock is released. A REST request now queues
  up to 9 socket sends; that is queueing, not I/O, as for a `pos`.
- **The test that pinned "nothing over the socket" was rewritten, not
  kept**: RAS-53 wrote it to hold the line until this ticket.
- **Measured with bursts.** The rate limit lets a member send 5 at once,
  and `not_reading` trips on bursts rather than on averages, so every
  member sent 5 at the same instant, every 10.5 s; an even spread was run
  once for comparison. 10.5 s rather than 10 leaves a margin, so a burst
  that the server takes a few milliseconds to accept never meets the edge
  of the sliding window (no 429 from timing), and the 120 s still hold 12
  bursts, as a 10 s period would.

## 2026-11-15 (sixth) - Guests nobody can reach are deleted about 90 days after their last visit, and expired sessions

The user wants klad to follow the GDPR as fully as possible (2026-11-15)
and approved the purge the RAS-21 review proposed (RAS-59). Until now a
guest lived only through its cookie: once the cookie was lost, cleared or
expired, nobody could delete that guest any more, because deletion needs
its session, and nothing deleted it on its own. Its nick and results stayed
on the boards for good, which the storage-limitation principle (keep
personal data no longer than needed) does not allow. Expired session rows
of accounts stayed too. The deletion of expired guests in production is the
approved purpose of the ticket (user, 2026-11-15). Once this is live, the
privacy page (RAS-57) can say that guests are deleted automatically about
90 days after their last visit, instead of offering that deletion by mail.

- What goes, in one transaction (`GuestPurge`, contract section 2):
  - every guest (no email) whose sessions have all expired, with its
    sessions and results (`on delete cascade`, as for `DELETE
    /players/me`), so its rows leave the boards;
  - every guest without any session row whose `last_seen_at` is 90 days
    or more before the purge;
  - every expired session row of an account. Accounts and their results
    never go.
- A purged guest is also dropped from presence and rooms
  (`PresenceStore.forget`, `RoomStore.forgetDeleted`, which takes its chat
  messages too), in case it is still there.
- When: the first REST request after the instance starts, then the first
  one at least a day after the last run (`Rules.PURGE_INTERVAL`). A
  `@ServerRequestFilter` runs it before the request's own work, on the
  request's worker thread, in a transaction of its own
  (`QuarkusTransaction.requiringNew()`). It applies to every REST
  endpoint, `GET /status` included, and not to `/q/health` (a Vert.x
  route) or the race socket.
- Each run deletes at most 1,000 guests, those with the oldest
  `last_seen_at` first, and 1,000 account sessions (`Rules.PURGE_BATCH`,
  `delete ... where id in (select ... limit)`). Each wait for a row lock
  ends after 2 s (`set local lock_timeout`, as code requests do), and each
  statement, its waits included, after 10 s (`set local
  statement_timeout`), so with its two deletes the purge holds up the
  carrying request for about 20 s at the very worst.
- A failed purge logs `Guest purge failed: <exception class>` at ERROR and
  leaves the request's answer as it would be. A run logs `Guest purge
  deleted <n> guests and <m> expired sessions` at INFO after each start
  and then once a day. Neither
  line holds a nick or an id.
- No migration: `session`, `level_clear` and `game_run` already reference
  `player` with `on delete cascade` (V1, V2). No new dependency and no
  configuration change.
- In production nothing is old enough yet: the first guests were made on
  2026-11-13, so the first can go in February 2027. Until then each run
  finds nothing, unless rows were dated back by hand.

### Tests

- `GuestPurgeTest` (unit, `FakeClock`): the first run is claimed, a second
  one a second short of a day later is not, one a day later is, the day
  counts from the last run, and of 32 threads claiming at once exactly one
  gets the run.
- `GuestPurgeDatabaseTest` (`@QuarkusTest`, Postgres, `purge` called with a
  fixed purge time, every test from an empty database; its own profile
  gives it a database of its own, so emptying it takes no players from
  other test classes, such as the race tests' reused ones): an expired guest
  goes with its session, clear and run; a guest made by `POST /players`
  whose session was then set to have expired an hour ago goes (its
  `last_seen_at` is fresh); a session expiring exactly at the purge time
  counts as expired; a session valid one more second keeps the guest; an
  old `last_seen_at` with a valid session keeps it; a guest without a
  session goes at 90 days and stays a second before; an account with only
  expired sessions keeps its row and results and loses the sessions; an
  account keeps its valid session and loses the expired one; the three
  boards list the purged guest before and not after; a purged guest is
  gone from presence and from the room list; 1,001 expired guests take two
  runs, 1,000 and 1, and the one left over keeps its session in between;
  1,001 expired account sessions take two runs too; of 1,001 expired
  guests, the one with the oldest last visit, inserted last, goes in the
  first run. Two cases hold a second connection open while the purge waits
  on its row, then commit: one turns a guest in its session's last moment
  into an account as `POST /login/verify` does (email set, old session
  deleted, a valid one added), and the account keeps its email, new
  session and results; the other refreshes an account's expired session as
  a request does, and the refreshed session stays. Both failed against the
  statements without the outer re-check, and the oldest-first case without
  the `order by`. Kept rows are compared whole, before and after.
- `GuestPurgeRequestTest` (`@QuarkusTest`, its own profile for a fresh
  application, HTTP and JDBC only): 10 parallel first requests all answer
  200, the expired guest and its rows are gone, the account stays without
  its expired session, a guest expired after that stays through one more
  request, exactly one purge line is logged, and no log line holds the
  guest's id or nick. `GuestPurgeRequestIT` runs the same against the
  native binary (with the profile again, as a profile is not inherited, so
  the binary is a fresh one), reading the binary's log file. In the
  Mandrel Linux container all 284 native tests passed, and that binary
  logged `Guest purge deleted 1 guests and 1 expired sessions`, so the
  `delete ... returning id` read as UUIDs works in the image.
- `GuestPurgeFailureTest` (`@QuarkusTest`, its own profile): a second
  connection holds the expired guest's row; the first request waits out
  the 2 s lock timeout and answers 200, the guest stays unchanged, one
  failure line is logged and no success line, the next request runs no
  purge, and no log line (Hibernate's own warning included) holds the id or
  the nick.

### Decisions worth not relitigating

- **Every REST request can carry it, not one endpoint.** The ticket named
  `POST /players` or the first session check as examples. A filter is the
  broadest choice: the game's `GET /players/me` on every page load still
  carries it, and so do the deploy's smoke request and any uptime check on
  `GET /status`, so the purge does not wait for a player. It also runs
  before the endpoint's transaction begins, so it holds none of that
  request's locks and that request none of its own. All endpoints are
  blocking, so the filter runs on a worker thread.
- **Application scoped, not a singleton.** RESTEasy creates its filters
  while the native image is built. As a `@Singleton`, `GuestPurge` was
  created then too, with the `RoomStore` and `PresenceStore` it injects,
  and the native build stopped at the `RoomStore`'s `SecureRandom` in the
  image heap (the static-init rule of the rules file, caught by the native
  build). The client proxy of `@ApplicationScoped` defers the bean,
  and so the stores, to the first request.
- **In the request, not after the answer.** Cloud Run gives no CPU between
  requests, so work left running after the answer could stop in the middle
  of its transaction, holding its locks. The first request after a start,
  and then one a day, waits for the purge.
- **The guard is in memory.** One instance (`--max-instances 1`) holds it
  under a lock, so parallel requests run one purge. A restart or a scale to
  zero runs it again on the next first request; each run is cheap and
  finds nothing new, so no table (and no migration) was added for it. Two
  instances during a deploy may each run one; the second waits for the
  first's row locks and finds those rows gone (or, past 2 s, fails, logs
  and tries a day later).
- **Claimed before it runs, kept when it fails.** A broken purge then
  retries a day later instead of running, and logging, on every request.
- **A guest without any session goes by `last_seen_at`.** A guest with
  sessions goes once they have all expired, as the ticket says. A guest
  without any session row (one that called `POST /logout`, or one left
  behind by a login into an account of the other kind, contract section 5)
  has no expiry to go by: selecting only expired sessions would keep it
  forever, selecting "no valid session" would delete it within a day of
  that login, against the section 5 decision that it keeps its nick and
  results. `Sessions` sets `last_seen_at` and `expires_at` from the same
  `now`, on creation and on every refresh, and a guest has only the
  session it was created with, so 90 days after `last_seen_at` is when its
  session would have expired: it goes on the same clock as every other
  guest.
- **The boundary is the request's.** `expires_at` equal to the purge time
  counts as expired, as in `Sessions.findValid`.
- **Each delete re-checks its deciding column outside the subquery.** When
  a delete has to wait for a row that a concurrent transaction changes,
  Postgres (read committed) re-checks only the outer `where` on the
  committed row; the `in (select ... limit)` answer stays as it was. So
  the outer `where` repeats the column a request can change: `email is
  null` for the guests, `expires_at <= :now` for the sessions. Without
  them, both reviews reproduced on Postgres 18 that a guest turned into an
  account by `POST /login/verify` in its session's last moment was deleted
  with its email, new session and results, and that an account session
  refreshed at its expiry instant was deleted; both cases are now closed
  and tested. `for update skip locked` in the subqueries would also avoid
  them, but it skips held rows instead of waiting, which changes the
  lock-timeout behaviour `GuestPurgeFailureTest` pins.
- **A guest refreshed at its expiry instant can still lose.** The refresh
  changes the session and `last_seen_at`, not `email`, so if that day's
  purge picked the guest first, it goes, and the request answers 500 or
  its new cookie leads nowhere: one failed request, as if the guest had
  expired a moment earlier. Reasoned, not tested here (the security review
  reproduced it); the window is one request's length at the expiry
  instant, and a margin would delete later than the ticket says.
- **1,000 rows per table and run, oldest guests first.** Real traffic is a
  few guests a day; a flood of scripted guests (`POST /players` has no
  limit) then goes over several days instead of making one request slow.
  Without an order Postgres takes the candidates in table order, so such a
  flood could sit ahead of real guests whose last visit came before it and
  hold them back past "about 90 days"; `order by player.last_seen_at`
  before the `limit` drains the backlog first in, first out by last visit,
  for one sort of the candidates. It still runs once a day: draining a
  backlog faster would go against the ticket's "at most once a day". The
  guest delete reads every guest, live ones included, so its time grows
  with the number of guests, not with the batch: on a local Postgres 18
  with 200,000 expired guests and 20,000 live ones it took 348 ms for its
  1,000 rows, while in a container held to a quarter vCPU about 1.1
  million guests reached the 10 s statement timeout (Neon's small compute
  not measured). Past that size every run is cancelled, logs its class and
  deletes nothing, until `POST /players` gets a creation limit (a
  follow-up) or the purge is run by hand; a longer timeout or a partial
  index on `player (last_seen_at)` for guests are the options then.
  The session delete takes only accounts' sessions: a guest's go with it,
  and a guest left over by the batch keeps its expired session, so the
  next run still finds it as a guest whose sessions have expired rather
  than one without a session, which would wait for its `last_seen_at`.
- **The failure line names the exception class only.** A Postgres message
  can quote key values, which are player ids. Hibernate's own warning for
  the failed statement stays; for the statements here it names a relation
  and a row position, not a key (checked for the lock timeout).
- **Agent test entries go too.** The production test players of kind
  `agent` (contract section 6) are guests and go about 90 days after their
  last visit like any other.

## 2026-11-15 (fifth) - A socket upgrade never runs inside the read of its handshake

`SocketGuardsTest.shouldOpenSocketsUpToCap_whenConnect_givenTwoHandshakesInOneWrite`
failed twice on main's CI on 2026-11-15 (RAS-72): the JVM test of the
RAS-51 merge and the native `SocketGuardsIT` of the RAS-53 merge. One of
the 40 connections read end of stream where it expected
`HTTP/1.1 101 Switching Protocols`; reruns passed. The cause was the
server, not the test.

- **Cause.** `WebSocketHandshakeCheck` runs the session lookup on a worker
  thread and attaches `onComplete` to the returned future. If the lookup
  ended before `onComplete` was attached (the event-loop thread descheduled
  for a millisecond or two right after submitting the task, likelier on a
  busy 4-vCPU runner), Vert.x ran the callback at once, inside the read
  that carried the handshake, and the upgrade happened while that read was
  still being decoded. Netty's handshaker swaps the HTTP decoder for the
  WebSocket decoder before it writes the 101, and the HTTP decoder hands
  the bytes it has not decoded yet, here the second handshake of the same
  write, to the WebSocket decoder. `GET` is no WebSocket frame (RSV bits
  set), the decoder throws, Vert.x closes the connection, and the 101 is
  written to a closed channel. Vert.x's default HTTP server exception
  handler logs this at TRACE only, so CI showed nothing. On the usual path
  the callback runs in a later event-loop task, after the read has decoded
  the second request and queued it behind the first, which is the case the
  test was written for.
- **Fix.** The lookup's result goes through `runOnContext`, so `afterLookup`
  always runs in an event-loop task of its own and the upgrade never runs
  inside the read of its handshake. It costs one extra task per handshake.
  The test is unchanged: with the fix the first handshake gets its 101
  whatever the lookup's timing, and the 500 ms hold still gives a second
  handshake that got past the guard the time to do harm.
- **Reach.** Only a client that sends bytes behind its handshake before
  the 101 could meet this; browsers wait for the 101, and the site's proxy
  opens one connection per upgrade. No slot was lost: the connection's
  close gave it back (both CI failures opened all 20 sockets afterwards).
  The native failure also carried a suppressed `IllegalStateException:
  WebSocket is closed` from the test's teardown: one of the 20 cap sockets
  had been closed from the server side before the teardown. This fix does
  not explain that; it is a follow-up on the race socket.
- **Proof.** By mutation, local only: a 50 ms pause on the event loop
  between submitting the lookup and attaching `onComplete` made the test
  fail on all 40 connections with the CI's message, and TRACE logging
  showed `CorruptedWebSocketFrameException: RSV != 0 and no extension
  negotiated` thrown from `WebSocketServerHandshaker` through
  `DefaultChannelPipeline.replace` and `ByteToMessageDecoder.handlerRemoved`.
  With the fix and a 10 ms pause on every handshake, `SocketGuardsTest`,
  `RaceSocketTest`, `AbandonedHandshakeLogTest` and
  `ThrowingListenerLogTest` passed. Without a pause the flake never showed
  locally (24 runs of `SocketGuardsTest` and 8 full JVM suites in Linux
  containers capped at 2 CPUs with busy loops beside them, and about 4,200
  handshake pairs from a temporary harness, up to 6 threads at once), which
  fits a race inside one call.

### Decisions worth not relitigating

- **The server, not the test.** A test that read the status again, or
  retried, would have hidden a server that closes a valid handshake
  without an answer.
- **No deterministic regression test.** The race is between the worker
  thread and the event-loop thread inside one method call, which no
  black-box test can force; a test-only pause in production code was not
  wanted. The mutation check above stands in for it.
- **Not handled:** a second handshake split across two TCP reads, with the
  upgrade running between them, still reaches the WebSocket decoder and
  closes the connection without a 101. It is not reachable through the
  site's proxy, and loopback delivers the test's single write of about
  530 bytes in one read. A raw client at the run.app URL can reach it, and
  it only closes its own connection; the slot comes back at the close.

## 2026-11-15 (fourth) - Room chat: members write in the room, read it in the room poll, memory only

The user wants players to write to each other in the lobby before a game
(RAS-53, contract sections 1 and 7). This is the API part; the game shows
the chat in RAS-54, and chat during a race is RAS-55 and RAS-56. The user
answered every open question of the ticket on 2026-11-15, each as
recommended. The API ships first and reaches players only by hand until
RAS-54 brings the chat and the privacy notes together, as the email login
did (RAS-23 and RAS-24 before RAS-25). By the ticket's reading of the
site code (not run here), the game copies named fields only (`roomView`
in `klad/lobby.js`), so the API can deploy first and the new fields do
not reach players before that.

- `POST /rooms/<id>/messages` with `{"text"}` answers 201 with the message
  as stored: `{"n","member","nick","kind","guest","you","text"}`. Only a
  member may send (Q1 a); every member does, guests and accounts, humans
  and agents (Q3 a). It works in every state, so RAS-55 can build on it.
  `n` counts up from 1 in each room (under `RoomStore`'s lock) and is never
  given again; a message removed with its deleted sender (below) leaves a
  gap. `member`, `nick`, `kind` and `guest` are the sender's at the
  moment of sending and come from the server, so a message cannot fake its
  sender; `you` marks the caller's own messages (Q7 c). No player id and no
  email leave the API.
- Reading travels in the room poll (Q2 a): every room answer of a member
  (`GET /rooms/<id>`, `join`, `ready`, `level`, `listed`, `start`) carries
  `messages`, oldest first, holding the messages sent after the caller's
  join (Q4 a). `GET /rooms/<id>?after=<n>` keeps those with a number above
  `n`, so the game asks for what is newer than the last one it showed and
  a late or lost answer loses none. A caller who is not a member, or who
  has left, gets the room as before (RAS-45: `host` null, `players` empty)
  with `messages` empty, and 404 `not_found` on a send.
- The member number (`member`) is new on each `players` entry too: each
  join takes the room's next number from 1, never given twice in one room.
  It tells two guests with one nick apart, and RAS-54's "hide this player"
  works by it (Q8 b, on the device, no API change beyond the number). It
  is not the player id and not the race `slot`.
- Text (Q5 a, Q6 a): NFKC-normalised and trimmed as nicks are
  (`ChatTexts`, next to `Nicks`), then 1 to 120 characters from an
  allow-list: printable ASCII (letters, digits, the space, punctuation),
  the Russian Cyrillic letters, and the quotes and dashes phone keyboards
  put in by themselves (U+00AB, U+00BB, U+2018, U+2019, U+201C, U+201D,
  U+2013, U+2014). Everything else is 400 `invalid_message`: a line break,
  a tab or another control character, zero-width and other invisible
  characters, direction overrides and marks (U+202E and the rest), emoji,
  accented and other letters, a missing or non-string `text`. Links stay
  plain text and no rule looks for them; the game shows the text as text.
- Limits (Q5 a): at most 5 accepted messages per player in a room in any
  10 s, counted in memory as a sliding window like `isOverPositionRate`;
  the sixth is 429 `rate_limited` and is not kept. `Rules` holds the
  numbers (`CHAT_TEXT_LENGTH_MAX`, `CHAT_MESSAGES_KEPT`,
  `CHAT_MESSAGES_PER_WINDOW`, `CHAT_WINDOW`).
- What is kept (Q4 a): the last 30 messages of each room, in `Room`, in
  memory only; the 31st drops the oldest. They go with the room (10
  minutes after it empties, or when its host opens a new room) and with
  every restart or deploy. A deleted player's messages go from every room
  at once (below). Nothing is written to the database (no migration) and
  nothing to a log line, also not on an error.
- Errors, checked in the order of section 7: 401 `no_session`; 400
  `invalid_message`, or `invalid_query` for an `after` that is not 1 to 9
  ASCII digits; 404 `not_found` for an unknown room and for a non-member;
  429 `rate_limited`; 415 and 413 as in contract section 1 (the send reads
  a JSON body, so the 415 filter and the 4 KB limit apply).
- Code: `RoomResource` (the POST, the `after` query), `RoomStore.send` and
  `find(..., after)` under the lock, `RoomStore.forgetDeleted` (called by
  `DELETE /players/me` and the merge in `login/verify`; logout keeps
  `forget`), `Room` (the chat list, `n`, the member number, the rate
  window, `removeMessagesOf`), `RoomView` (`messages`, `member`, the new
  `Message` record, returned typed on the 201 so the native image
  registers it), `ChatTexts`, `Rules`, `ApiException.INVALID_MESSAGE`.

### Abuse and privacy, by the ticket's facts

- **Free text from strangers.** Guests are free to create (`POST /players`
  has no limit, and the API has no IP limits by a plan decision), so a
  limit per player does not stop someone who makes new guests: in a
  `lobby` room with a free place, a fresh guest joins, sends 5 and leaves,
  and the next one does the same, held back only by the request rate. The
  window is kept per player id in the room, so the same player leaving and
  joining again does not reset it; only a new guest does. The members
  present at one time (at most 10) send at most 50 messages per 10 s;
  fresh guests taking turns in a free place can add more.
- **A room is not always friends.** Since the open rooms list (RAS-45) any
  player can enter a listed room with a free place, then read and write
  there. A chat among invited friends needs a link-only room
  (`POST /rooms?listed=false`). A member reads only what was written after
  their join, so entering a room shows none of the earlier chat.
- **Nobody can check afterwards.** The messages live in memory only, so no
  one can read them later, the owner included. That keeps the privacy
  promise small, and it means a complaint cannot be checked. The contact
  for reports is the chat note of RAS-54 (hello.rassimok@gmail.com, the
  user's choice; research item 19: a chat likely brings EU DSA hosting
  duties, not legal advice).
- **Agents read it too.** A player may be an AI or a script; for an agent,
  chat is text written by strangers. The text is one line from the
  allow-list, and the nick and marks come from the server, so a message
  cannot imitate a second message or another sender.
- **Only deleting the player takes a message back.** Deleting the player,
  or a merge that deletes the guest, removes their messages from every
  room at once. A member who leaves or logs out, or a guest turned into a
  new account, leaves their messages in the room, under the nick and marks
  they were sent with, until 30 newer ones push them out or the room goes.
  The stored message keeps the sender's player id for that, in memory
  only; it never leaves the API. A game page that already showed a
  removed message keeps it until it reloads, as the API cannot recall it.
- **The privacy promise.** The site says what the server keeps: a nick,
  the choice of human or agent, results, and an email for an account. Chat
  messages are new. Both rules files say the note changes before the API
  keeps more; by the user's order b (Q9) the note comes with the chat UI
  in RAS-54, and until then only someone who calls the API by hand can
  send a message.
- **Requests.** Each sent message is one Workers request and one session
  read from Neon; reading costs nothing extra. A full room of 10 at the
  limit sends at most 300 a minute (more with fresh guests taking turns in
  a free place), which would use the shared 100,000 a day in about 5.5
  hours; any signed-in player can flood other endpoints as cheaply, so the
  chat adds no new way to do it.
- **Logs.** No log line may hold the text of a chat message, as for an
  email address (rules file, Klad privacy). No code path logs the text,
  and no exception message carries it. The same must hold for whatever
  monitoring tool RAS-50 brings: it must not collect request bodies.
- **Memory, measured on the native binary** of this branch (the binary
  the native tests then ran against), set up as for the RAS-32 and RAS-43
  tables: `--memory 256m --memory-swap 256m --cpus 1`, a throwaway
  `postgres:18`, a Java client in a second container, each row on a fresh
  server container. 600 guests in 100 rooms of 6, all joined first; in the
  chat rows each member then sends 5 messages of 120 Cyrillic letters
  (240 bytes of UTF-8, the largest text), so every room holds its 30; then
  every member reads its room three times, 10 s apart (a full answer is
  about 6,800 characters). Sizes in MiB from the container's cgroup. Idle:
  97.7 to 103.5 peak, 21.5 to 23.5 `anon`.

  | Row | Peak | `anon` max | `anon` at the end |
  |---|---|---|---|
  | 100 rooms of 6, no chat, run 1 | 149.7 | 62.6 | 57.9 |
  | the same, run 2 | 143.8 | 65.0 | 58.1 |
  | 100 rooms of 6, full chats, run 1 | 141.7 | 62.5 | 51.4 |
  | the same, run 2 | 141.5 | 62.5 | 51.4 |

  - All 3,000 messages were accepted, and each of the 1,800 reads in a
    chat row held all 30. `/status` answered 200 after every row, nothing
    was logged at WARN or above, and no log line held the chat letters
    (the binary logs JSON, as in production).
  - The chats do not show in the numbers: the chat rows peak lower than
    the rows without (not traced to a cause). By reasoning, a kept message is about 450 bytes (the
    text, at most 240 bytes in its string, its own nick and kind strings,
    the record), so the 3,000 messages hold about 1.3 MiB, plus at most a
    few hundred KB for the rate windows; that is less than the spread
    between two runs of one row. Against the 20-socket flood's 228.7 MiB
    (2026-11-13, ninth) the chat adds about 2 MiB at most, and no row came
    near the 80 % line (204.8 MiB) used for RAS-43. The sender's player id
    on each kept message came after this measurement (the deletion
    decision below): by reasoning about 40 bytes more per message (a
    reference and its own UUID), about 120 KB for the 3,000; not measured
    again.

### Tests

- Unit: `ChatTextsTest` (new, 54 cases), one case or more per rule of
  Q5 and Q6: 120 characters pass (Latin and Cyrillic), 121 fail; empty,
  null and spaces only; a line break inside (LF, CR, U+2028, U+2029), a
  tab, BEL, DEL and NEL; zero-width space, non-joiner and joiner, word
  joiner, BOM, soft hyphen, invisible times; U+202E, U+202D, isolates,
  RLM, LRM and the Arabic mark; emoji (a face, a heart with its selector,
  a flag, a smiley symbol, a lone surrogate); an accented letter, a
  combining mark, a Ukrainian letter, Greek, Chinese and Arabic digits;
  the normalised form (spaces, fullwidth, U+00A0 to a space, U+2026 to
  three dots, a trailing line break trimmed), and a text that grows past
  120 under NFKC.
- Unit, fake clock: `RoomStoreTest` (+33): the stored message and the
  sender's marks, whole rooms for the sender and another member; an agent
  account (`kind` agent, `guest` false); two twins told apart by `member`;
  the nick kept as sent after a rename; 404 for a non-member, a member who
  left and an unknown room; no messages for an outsider and for a member
  who left; a later joiner reads only later messages, and so does a member
  who comes back (with a new number); `after` on both sides and together
  with the join rule; the messages on the answers of ready, level, listed,
  join and start; 30 kept, the 31st drops the oldest (whole list); a host's
  new room starts empty and the old one is gone; a room removed after its
  10 minutes is 404; a send in `starting`; the sixth message within 10 s
  less 1 ms is 429 and not kept, at 10 s it passes; a refused message does
  not count toward the window; leaving and joining again does not reset
  it; each member counts apart; a send counts as seen (a member who only
  sends is not swept); 50 sends from all 10 members at once on 16 threads
  get the numbers 1 to 50 with no gap or repeat, and the room keeps 21 to
  50 in order. `forgetDeleted`, each compared as the whole room another
  member then reads: a deleted member's messages go and the others' keep
  their numbers; also after an explicit leave, after a move to another
  room (both rooms lose them), after the 60 s sweep, and under an old and
  a new member number after a leave and a join; the next message takes
  the next number, not a removed one; `forget` (logout) keeps them. Every
  existing whole-room comparison now carries `member` and `messages`;
  after a leave or a handover the numbers are not the list positions (Bob
  stays 2), which the tests pin. `RoomStoreRaceTest` (+1): a
  send in `racing` is kept and sends nothing over the race sockets.
- `@QuarkusTest`: `RoomResourceTest` (+45): a player who sent, left and
  then sent `DELETE /players/me` is gone from the other member's next read
  (whole JSON, the host's later message keeps `n` 2); after a logout the
  message stays; the send and
  `GET /rooms/<id>?after=x` in the 401 lists (no cookie, unknown cookie),
  an expired session on a send; whole JSON answers of the send (201, no
  cookie resent) and of both members' reads; `after` 1 and 2, an empty
  `after`, ten bad values (`x`, `-1`, `+5`, `1.5`, `1e3`, ` 1`, `0x1`,
  ten digits, 2147483648, twenty digits) and a bad one for an unknown room
  (400 before 404); outsider and left member reads; 404 sends; a later
  joiner; the ready answer with the message; the stored normalised text;
  120 Cyrillic letters pass, 121 characters and twelve bad bodies fail
  with 400 (sent to an unknown room, so the text is checked first); the
  sixth message at once is 429 and not kept; 415 for `text/plain`; 413
  over 4 KB with no body. Whole JSON objects are compared, so an extra
  field, a player id or an email fails. `ResponseHeadersTest` (+7): the
  three headers on 201, 400 and 429 of the send, plus 401, 415 and 400
  `bad_request` cases and the two bodies without a JSON type.
  `LoginResourceTest` (+2, JVM only, as it reads the code from the mock
  mailbox): a guest who sent and is merged into an existing account of the
  same kind is gone with their messages from the host's read; a guest
  turned into a new account stays, with the message as sent
  (`guest` true).
- `ChatLogTest` (new, JVM only, under `JsonLogProfile`, so the lines are
  the production JSON format written to a file): one marker text goes
  through a chunked 413, five accepted sends, a 429, a 121-character, a
  zero-width and a two-line 400, two bodies that are not JSON (the marker
  as a bare token, and unterminated), a non-member's 404, a 401 and a 415;
  then `/test-only/boom` writes a known ERROR line with a stack trace. The
  test waits for that line (counted, as the file outlives a run) and finds
  the marker in no line of the file and in no log record (message,
  parameters, exception chain). Checked by mutation: one `LOG.info` of the
  text fails it on the file check (the record check after it was not
  reached).
- Native: `RoomResourceIT` and `ResponseHeadersIT` run the new cases
  against the binary (the JSON of `RoomView.Message`, nested and alone).

### Decisions worth not relitigating

- **Deleting a player removes their chat messages from every room at
  once**, decided by the main session in this run (2026-11-15, under the
  user's in-ticket approval), after the review found the first build kept
  them. That covers `DELETE /players/me` and the `login/verify` merge
  that deletes a guest of the same kind (`RoomStore.forgetDeleted`); a
  leave, a logout (`forget`) and a guest turned into a new account (the
  same player, so neither is called) keep them. Reason: the live privacy
  page says the server deletes the nick, and the 2026-11-13 (eighth) rule
  removes a deleted player from their room "so a deleted nick does not
  linger"; a message carries that nick. The match is by player id, kept
  on the stored message in memory only, not by member number: at deletion
  the player has usually left the room already (the lobby leaves it on
  close, the 60 s sweep removes a silent member), may have sent in other
  rooms, and gets a new number on each join. Membership and messages go
  under the one lock, so a send already in flight is either removed or
  finds no member (404). The counter is not reset, so a removed number is
  never given again and `after` still holds; a reader sees a gap. A page
  that already showed a removed message keeps it until it reloads; the
  API cannot recall it, and RAS-54's note must say "removed from the
  server", not more. As for presence (2026-11-13, eighth), a join and a
  send of the same player whose session was read before the delete
  committed can still land after it; such a message then lives until 30
  newer ones or the room's end. Accepted as there.
- **The allow-list, decided here.** Printable ASCII, the Russian Cyrillic
  letters (the nick alphabet's), and the guillemets, curly quotes and en
  and em dashes that phone keyboards insert by themselves; without those,
  "don't" typed on a phone would be refused. Accented Latin letters and
  other Cyrillic letters (Ukrainian U+0456, U+0457, U+0454) are left out on
  purpose: widening the list later breaks no client, narrowing it would.
- **The length is counted on the stored form**, after NFKC and the trim,
  as for nicks, so a text that NFKC makes longer (U+2026 becomes three
  dots) can fail at 120. Every allowed character is one UTF-16 unit, so
  the count is in characters, and 120 Cyrillic letters are 240 bytes of
  UTF-8, within RAS-55's 512-byte socket message.
- **A trailing line break is trimmed, not refused**: the trim of nicks
  removes it, and the stored text is still one line. A line break inside
  the text is 400.
- **`after`** is 1 to 9 ASCII digits, so no value can fail the int
  conversion (a 500); an empty value counts as absent, as Quarkus REST
  hands it in as none (the same as `listed` in RAS-45). Only
  `GET /rooms/<id>` reads it; the other room answers list every message
  the caller may read, as the ticket says.
- **The member number** is a counter per room from 1, never given twice,
  rather than the lowest free number: a reused number would give a new
  player the place of a hidden one in RAS-54's "hide this player". The
  price: a member who comes back, also after the 60 s sweep of a hidden
  tab, gets a new number and reads only what comes after the new join, so
  a hide by number does not follow them, as it does not follow a new
  guest.
- **The rate window is kept per player id in the room**, not on the member,
  so a leave and a join does not reset it (it would have made 5 messages
  cost 7 requests). Entries older than the window are dropped on every
  send in the room, so the map holds at most the players who sent in the
  last 10 s; a deleted player's entry goes at once with their messages.
  Only accepted messages count; a refused one (400, 404, 429) counts
  neither toward the window nor as seen. An accepted one counts as seen,
  like every successful room request of a member.
- **A message keeps the sender's nick, kind and guest mark of the moment
  of sending** (from the request's player, not the member entry, which
  could be stale after a login), and the member number. The player id
  stays on the stored message only, for the deletion above; the answer
  record `RoomView.Message` has no field for it. `you` compares member
  numbers, which is exact because a member reads only messages sent after
  their own join.
- **Sending is allowed in every state**, `starting` and `racing` too, so
  RAS-55 can build on it. Nothing is sent over the race socket yet.

## 2026-11-15 (later still) - Rooms of up to 10 players

The user wants up to 10 players in one room (RAS-43, contract sections 7
and 8). The game was made ready first (site, RAS-42: 10 ghost slots, 10
colours), or a fifth runner would have been invisible to the others.

- `Rules.ROOM_SIZE` goes from 4 to 10. The tenth player joins with 200; the
  eleventh gets 409 `room_full`. The room answer and every list entry say
  `"size":10`.
- Race slots run 0 to 9. They already followed the join order in
  `Room.start`, so no race logic changed.
- `Rules.SOCKETS_OPEN_MAX` stays 20 (below). Its comment now says 2 full
  races of 10 at once instead of 5 full races.
- Test fix: the socket tests set their handlers in the callback form of
  `client.connect(options, handler)`, not on the returned future. Vert.x
  4.5.33 resumes the new socket before it completes that future, so a
  descheduled test thread lost the first `state` frame (flaky on main).
- Contract sections 7 and 8 (workspace) say up to 10 players, `"size":10`
  and `slot` 0 to 9, the 503 for a third full race, and the lobby request
  cost of a full room.

### Load, by the ticket's numbers

- **Sockets.** The 20 sockets held 5 full races of 4. They hold 2 full
  races of 10, or more smaller ones. The players of a third full race get
  503 on the handshake, and the game says online play is not available.
- **Relayed positions.** Every position goes to every other member. A full
  room of 4 relays 4 x 5 x 3 = 60 messages a second. A full room of 10
  relays 10 x 5 x 9 = 450, or 900 if every member sends at the server's
  limit of 10 a second. Each socket receives 45 a second (90 at the limit)
  instead of 15.
- **Slow readers.** A socket with 8 unfinished sends is closed with 1008
  `not_reading` (`SocketSender`). Corrected 2026-11-17 (RAS-37): the
  client sees a reset, not the 1008, and the game reconnects (see the
  RAS-55 entry of 2026-11-16). A send counts as unfinished until Netty has
  written it to the socket. By the Vert.x 4.5.33 code (read, not traced at
  runtime), a send queued to the socket's event loop is written and
  flushed at once, outside any read, and its completion runs inline on
  that loop while the kernel takes the bytes, so 9 relays queued back to
  back do not pile up for a client that reads. Measured below: no socket
  closed at about 82 messages a second each (all members sending at 10 a
  second in the same instant, less what the limit dropped). A phone on a
  slow link was not measured; it now gets three times the traffic.
- **Memory.** Only a race of 4 had been measured (109.3 of 256 MiB, 2026-11-13
  ninth); the 20-socket flood peaked at 228.7. The race rows below peak at
  about 147 MiB, one race or two.
- **Requests.** Each member polls the room every 10 s through the Pages
  Function. A full room of 10 in the lobby costs about 6,000 requests an
  hour (2,400 for 4) of the shared 100,000 a day, and each one reads the
  session from Neon.
- **Abuse.** One member who floods `pos` at the limit now reaches 9 others,
  not 3 (the limit drops the rest silently, as before). Guests are free to
  create (`POST /players` has no limit), so filling a room of 10 with fresh
  guests is as cheap as filling one of 4. Taking every place on the room
  list (RAS-45) with full rooms now needs 200 polling guests in 20 rooms of
  10 instead of 80 in rooms of 4; rooms that are not full still offer a
  join. The 100-room cap fill is unchanged (about 100 guests for about 11
  minutes).

### Tests

- Unit, fake clock: `RoomStoreTest` fills its full room with 10 players, so
  Eve is the eleventh. The join of a member already in it compares all 10
  entries whole and pins the size 10. The join race is now 20 players at
  once against 9 free places (`shouldTakeExactlyNineMore_whenJoin_givenTwentyPlayersAtOnce`,
  was 8 against 3). `RoomStoreRaceTest` (+3), a race of 10: the last member's
  `state` lists slots 0 to 9 in join order, whole, then the countdown; all
  ten send a `pos` in the same instant and each socket gets exactly the 9
  others, in order, never its own; finishes from slot 9 down give 10 places
  in receipt order, every socket gets the same results and 1000
  `race_over`, and the room is back in `lobby`.
- `@QuarkusTest`: `RoomResourceTest` replaces the fifth-player test with
  the tenth join (200, `size` 10, 10 players) and the eleventh (409
  `room_full`), and the six joins at once with twelve (nine 200, three
  409). `RaceSocketTest` (+1) runs a whole race of 10 real sockets: each
  `state` whole as the members connect one by one, one `countdown` and one
  `go` time for all, ten `pos` sent at once, each socket getting exactly the
  9 others, then a `pong` that proves no echo; 10 places in receipt order,
  10 times 1000 `race_over`, the room in `lobby` with no ready marks. The
  three-member race stays.
- `SocketGuardsTest` is unchanged in code: `RaceRoute` fills the cap in
  rooms of `ROOM_SIZE`, so every abuse test now ends by opening the whole
  cap as two rooms of 10, each connected within its 15 s wait.
- Native: `RoomResourceIT`, `RaceSocketIT` and `SocketGuardsIT` run the
  same against the binary.

### Decisions worth not relitigating

- **The socket cap stays 20** (user, 2026-11-15, the ticket's option a): no
  deploy change, 2 full races of 10 at once. Raising it, to 40 for example,
  would need `--memory 512Mi` in `deploy.yml` (CODEOWNERS-protected) and a
  higher bill per active second, because the 20-socket flood already used
  228.7 of 256 MiB. Sockets would also take half of the 80 request slots
  that REST needs. There is one real player today, and RAS-37 item 1 (the
  session-lookup flood that killed a 256 MiB instance) is still open. Raise
  it later, with a measurement.
- **Assumed, not asked:** every room has the size 10 (the host does not
  choose one), and Start still needs at least 2 players, all ready.
- **Measured on the native binary** of this branch, built after its last
  change to `src/main` (the same binary the native tests then ran
  against), set up as for the RAS-32 table: `--memory 256m --memory-swap 256m --cpus 1`, a throwaway
  `postgres:18`, a raw-socket client in a second container that sets up
  the rooms over REST, each row on a fresh server container. `memory.peak`,
  and `anon` sampled every second, come from the container's cgroup; sizes
  in MiB. Each row runs 120 s of positions; every member sends one `pos` in
  the same instant as all the others, with a `ping` every 20 s as the game
  does. Then the members finish from slot 9 down. Idle: 95.2 to 99.9 peak,
  20.2 to 24.4 `anon`.

  | Row | Peak | `anon` max | What happened |
  |---|---|---|---|
  | one race of 10, sent at 5 a second | 146.6 | 64.6 | 6,000 of 6,000 positions relayed to all 9 others (54,000 frames), none echoed; 60 of 60 pongs; `/status` 122 of 122 times 200, at most 10.3 ms; no socket ended early; 10 places in receipt order; 10 times 1000 `race_over`; the room back in `lobby`, nobody ready; 6.0 s of CPU |
  | one race of 10, sent at 10 a second | 143.8 | 64.7 | 10,981 of 12,000 relayed to all 9 others (98,829 frames, about 82 a second per socket), 1,019 dropped by the 10-a-second limit, none relayed to only some, none echoed; 60 of 60 pongs; `/status` 122 of 122, at most 8.6 ms; no socket ended early; results and closes as above; 8.2 s of CPU |
  | two races of 10 at once (20 sockets, the whole cap), sent at 5 a second | 144.9 | 65.1 | 12,000 of 12,000 relayed to all 9 others (108,000 frames), none echoed; 120 of 120 pongs; `/status` 122 of 122, at most 7.9 ms; no socket ended early; 20 times 10 places; 20 times 1000 `race_over`; 7.8 s of CPU |
  | two races of 10 at once, sent at 10 a second | 144.2 | 64.6 | 21,942 of 24,000 relayed to all 9 others (197,478 frames, about 82 a second per socket), 2,058 dropped by the limit, none relayed to only some, none echoed; 120 of 120 pongs; `/status` 122 of 122, at most 8.7 ms; no socket ended early; results and closes as above; 10.9 s of CPU |

  - "Relayed" means a position reached all 9 other members of its room. A
    position the limit dropped reaches none. A position that reached some
    but not all would be a relay fault: there were none. At a nominal 10 a
    second, about 8.5 % fall to the limit. It is a sliding window on the
    server's receipt time, so timing jitter puts an 11th position inside a
    second now and then (contract section 8: dropped silently). The game
    sends about 5 a second.
  - `anon` levels off at about 62 MiB within 30 s and stays flat to the
    end, whether one race runs or two. The peaks include the binary's
    mapped file pages (about 71 MiB idle).
  - Nothing was logged at WARN or above, apart from the known line about
    `quarkus.log.console.json`. No socket was closed with `not_reading`.
  - Before measuring, a row counted as not fitting on any of these: an OOM
    kill or restart, a peak above 80 % of the limit (204.8 MiB), any
    `not_reading` or other early close, any position relayed to only some
    members or echoed, or a `/status` that was not 200 or took over 1 s.

## 2026-11-15 (later) - Production logs are JSON, with a severity and the stack trace in one field

Cloud Run sends the console to Cloud Logging, and until now every line
arrived as plain text (RAS-51). The key `%prod.quarkus.log.console.json=true`
did nothing: no JSON extension was installed, and Quarkus warned about an
unrecognized key at every start. The user chose, on 2026-11-15, the
`quarkus-logging-json` extension (Q1 A, no version, the Quarkus BOM 3.39.3
manages it) and the extension's own configuration keys, no code (Q2 A).

- `application.properties`: the dead key is renamed to the real one,
  `%prod.quarkus.log.console.json.enabled=true`. The extension turns JSON on
  in every profile once it is present, so `quarkus.log.console.json.enabled`
  and `quarkus.log.file.json.enabled` are `false` otherwise: dev, `./mvnw
  test` and the log file stay plain text. The file matters because the
  native test launcher writes the binary's log to `target/quarkus.log`, and
  `LoginRoundTripIT` reads the mock mailbox from it as text.
- Fields: `key-overrides=level=severity,stack_trace=stack_trace`
  and `exception-output-type=formatted`. Cloud Logging reads `severity`,
  `message` and `timestamp` (the extension's default time key already
  works); Error Reporting reads the stack trace from `message` or
  `stack_trace`. `formatted` puts the whole trace, with causes, in one
  string; the default `detailed` would give a nested object. `message` stays
  the log message (`Unexpected error`), so the trace is not duplicated there.
  The Quarkus `log-format=gcp` was tried and left out: it keeps the
  camelCase `stackTrace`, a name Error Reporting is not documented to read.
- `excluded-keys` drops `sequence`, `loggerClassName`, `threadId`, `mdc`,
  `ndc`, `hostName`, `processName` and `processId`: no use for them in
  Cloud Logging, and each adds bytes to every line. A line keeps
  `timestamp`, `loggerName`, `severity`, `message`, `threadName` and, for an
  exception, `stack_trace`.
- No new log lines. The failed mail send keeps its fixed line with no
  exception, so its JSON object has no `stack_trace`; no field carries an
  email, a cookie or a secret.
- Tests: `JsonLogFormatTest` (JVM, a test-only route that throws) proves one
  JSON object with severity ERROR, message `Unexpected error` and the stack
  trace; `JsonLogNativeIT` proves the same settings in the native binary
  (severity INFO on a start line); `JsonLogMailFailureTest` proves the
  failed mail send line has no `stack_trace` and no address. None can read a
  console, so they put the console settings on the log file. `JsonLogSettings`
  reads them from `application.properties` (every `quarkus.log.console.json.*`
  key and `%prod...enabled`), so a dropped `enabled`, a mistyped
  `key-overrides` or a changed `exception-output-type` fails the tests
  (checked by mutation in review round 2). Not guarded: a mistyped
  `excluded-keys` (the noise fields come back) and a dropped base
  `enabled=false` (the `./mvnw test` console turns JSON).
- Not yet known, to check after the deploy: the level names go out
  unchanged (`WARN`, `FATAL`, `SEVERE` may not be mapped by Cloud Logging;
  mapping them needs code), and the first stack trace line starts with ": "
  before the exception class, which Error Reporting may not group on.
  Both are open follow-ups, not promises of this entry.

## 2026-11-15 - Open rooms listed at GET /rooms, link-only rooms, no members seen from outside

The lobby can show the open rooms, so a player can enter one without an
invite link (RAS-45, contract section 7; the site shows the list in
RAS-46). The user answered every open question of the ticket on
2026-11-15, each as recommended. The two reviews of the first version
found that a listed id showed every member to anyone and that no room
could be link only before it was listed; the link-only create and the
outside view below fix that.

- `GET /rooms` answers 200
  `{"rooms":[{"id","state","host":{"nick"},"players","size","level","shape"}]}`:
  every room with at least one member that its host has not made link
  only, in `lobby`, `starting` or `racing`, full ones too; at most 20
  (`Rules.ROOMS_LISTED_MAX`), the most players first, then the oldest.
  `players` is a count here. An entry carries no player id, no email and
  no nick but the host's. Empty and link-only rooms are never listed.
  Joining from the list is the usual `POST /rooms/<id>/join`.
- A new room is listed unless it is created link only (below).
  `POST /rooms/<id>/listed` with
  `{"listed":true|false}` switches it to link only and back and answers
  the room, which now carries `"listed":true|false` for every member. Host
  only: anyone else, member or not, gets 403 `not_host`, as for `level`
  and `start`. Anything but a JSON boolean is 400 `invalid_listed`, checked
  before the room; an unknown room is 404. It works in every state, and
  the setting belongs to the room, so it stays through a host handover and
  a race.
- `POST /rooms?listed=false` makes the room link only from the start, so
  it is never on the list, not even for one read. Without the parameter,
  with `true` or with an empty value (Quarkus REST hands an empty value in
  as none) the room is listed. Any other value, `False`, `0` and `yes`
  included, is 400 `invalid_listed`, checked after the session and before
  the room cap (401, then 400, then 429). The answer stays `{"id"}`.
- To a player who is not a member, `GET /rooms/<id>` answers 200 with the
  room as if it were empty: `"host":null` and `"players":[]`, with the id,
  `state`, `level`, `shape`, `size` and `listed` as they are. This holds in
  every state and for link-only rooms too (`Room.view`). The answers that
  only a member gets (join, ready, level, listed, start) are unchanged. A
  member silent for over 60 s who polls again now sees the room with no
  members at all, not the room without themselves (2026-11-13, eighth).
  By the reviews' reading of the site's `klad/lobby.js` (not run here),
  the game's re-join needs only a 200 with the id, the state and no `you`
  entry, which this keeps.
- The list is built under `RoomStore`'s lock after the sweep every access
  runs (`removeExpired`), and `list()` takes no player, so reading it is
  not a heartbeat: nobody counts as seen, no empty room's 10 minutes start
  again, and presence is not touched. The session is required (401
  `no_session`) and refreshed once a day as on every endpoint; the three
  headers of section 1 apply.
- `RoomStore` keeps its rooms in a `LinkedHashMap` (creation order; a room
  is put once, when it is made) and the list sorts them stably by player
  count, so "then the oldest" needs no creation time and does not fall back
  to hash order for rooms made in the same instant.
- New record `RoomListView` (with `ListedRoom`, reusing `RoomView.Host`)
  next to `RoomView`, which gains `listed`. Both are returned typed
  (`RestResponse<...>`), as the other views, so the native image registers
  them like the room.

### Tests

- Unit, fake clock: `RoomStoreTest` (+24): the whole entry with the host's
  level and shape; full, `starting` and `racing` rooms listed; empty and
  link-only rooms left out, listed again after the switch back; the order
  (three players, two, then two rooms of one, the older first); 25 rooms
  give 20, a newer room of two above the 19 oldest; 150 creates at once
  still list 20; a member whom only list reads "see" is swept after 60 s;
  an empty room read at 9 minutes is gone at 10 minutes and 1 ms; the
  switch by the host, refused with 403 for a member (the room stays
  listed) and for a stranger, 404 for an unknown room, allowed in
  `starting`, kept through a host handover. A room created link only: its
  whole answer has `listed` false, it is not listed, not even with two
  members, and it is listed after the switch. A player who is not a member
  reads a full, a `starting` and a link-only room with no members (whole
  objects); the swept member's poll gets the same, while the host still
  sees the whole room. Since an outsider sees no members at all, the test
  that keeps silent members in `starting` now reads as a member, and the
  read of a room everyone left comes from the player who left.
- `RoomStoreRaceTest` (+1): an outsider reads a `racing` room with no
  members. The reads that prove members are still in or gone now come from
  the members themselves: after the race both are still in, after 60 s
  more each one sees the room empty, and so do both after they left a
  `starting` room or let 10 minutes pass after the results.
- `@QuarkusTest`: `RoomResourceTest` adds both endpoints and
  `POST /rooms?listed=yes` to the 401 cases (no cookie, unknown cookie),
  an expired session for the list, the list's fields (only `rooms`, 1 to
  20 entries, each with exactly the seven fields and `host` exactly
  `{"nick"}`), a player who only reads the list missing from
  `GET /presence`, and the switch (the whole room with `listed` false,
  `not_host`, six bodies that are not a boolean, 404). It reads a lobby
  room of two and a `starting` room from outside (whole objects with no
  members), creates with `listed=false` (the whole room, `listed` false),
  `true` and an empty value (listed), and refuses six other values with
  400. The empty room after its only member left is now read by that
  member. Every whole room it compares now has `listed`.
- `RoomListTest` (new, with a test profile of its own, so a fresh
  application holds only the rooms made there): a second player's list is
  compared as a whole JSON object and the listed id joins the room; a room
  seen on the list is gone from it after the switch to link only, and
  after its only member leaves; a room created link only is never on it.
  In the shared application the other classes leave many live rooms, full
  ones from the socket tests among them, so a new room's place in a list
  of 20 is not certain there, and a "left out" check would pass even
  without the filter; `RoomResourceTest` checks only what holds whatever
  else is in the store.
- `LobbyCapsTest`: at the 100-room cap the list is the 20 oldest rooms,
  compared whole, and `POST /rooms?listed=yes` is 400, not 429. The room
  tests fill the store with the same 100 hosts: whichever runs first, the
  next fill replaces each host's room, and the store holds exactly the
  cap.
- `ResponseHeadersTest`: `GET /rooms` was a 405 case and is now 401
  `no_session` (the 405 case moved to `DELETE /rooms`); the three headers
  on a 200 list; the 415 cases for `listed`.
- Native: `RoomResourceIT` and `ResponseHeadersIT` run the new cases
  against the binary (the JSON of the new records, the outside view, the
  query parameter). `RoomListTest` has no native twin; `RoomResourceIT`
  checks every entry's fields there.

### Decisions worth not relitigating

- **The room id stops being the key.** Until now 60 random bits in the
  link decided who could enter. A listed room is open to every signed-in
  player, and `POST /players` has no limit, so that is anyone. A room for
  friends only is created link only (`POST /rooms?listed=false`). Switching
  a listed room to link only later hides it from the lists after the
  switch, but does not take back an id already seen: whoever read the list
  before can still join it while it is in `lobby` with a free place.
- **New facts shown to others:** who hosts a room (the nick) and how many
  are in it, to every signed-in player. The lobby's note on what other
  players see must say so (site, RAS-46). Member nicks, kinds and ready
  marks are shown only to the room's members: a player outside reads any
  room, listed or not, in any state, with no members, so neither the list
  nor a listed id shows who plays with whom. The limit: a player who joins
  a listed `lobby` room with a free place is a member and sees the others,
  and can leave at once; the members see them only if one of their polls
  lands in between. So a full, `starting` or `racing` room cannot be read
  from outside, but a joinable one can, by joining.
- **A stranger can block Start** (accepted, user 2026-11-15). Start needs
  every member ready, and a member who joins from the list, never presses
  Ready and keeps polling is never swept; until now that took the link.
  Link only does not remove someone already in. The host's way out is a
  new room created link only (`POST /rooms?listed=false`), which closes the
  old one and is never on the list. A new listed room switched afterwards
  does not help against a stranger who keeps polling: it is on the list
  with the host's nick from its first moment. A host who removes a member
  would be its own ticket.
- **Junk rooms at the cap** (still open, not decided here). About 100
  fresh guests can fill the 100-room cap for about 11 minutes. The list
  shows only rooms whose members still poll: an abandoned junk room leaves
  the list when its member is swept after 60 s, though it holds its place
  under the cap. Junk hosts that keep polling stay on the list with their
  nicks, up to 20 in every lobby, but below any room of two or more. That
  holds only for rooms of one: 80 fresh guests in 20 full rooms of 4, each
  polling at least once a minute, take every place on the list, so it
  shows no room one can enter. That needs fewer sessions than the cap fill
  (80, not 100), but all of them must keep polling. It stays open with the
  cap fill; listing joinable rooms first would change the order the user
  chose (most players first), so it is not done here.
- **Request cost.** A lobby tab outside a room goes from about 240 to about
  360 requests an hour (one `GET /rooms` every 30 s), so the 100,000 a day
  Workers quota covers about 275 tab hours a day outside a room instead of
  415. Inside a room nothing changes; the site lists rooms only while the
  player is in no room. Each list read also reads the session from Neon,
  like every request.
- **The switch, decided here:** `POST /rooms/<id>/listed` with a boolean,
  shaped like `ready`, guarded like `level` (403 `not_host` to everyone but
  the host), but not lobby-only: the flag changes only the list, so there
  is nothing to protect in a race.
- **The link-only create, decided here:** a query parameter, because
  `POST /rooms` ignores any body and its type (contract section 1) and the
  proxy keeps the query string. Only the exact words `true` and `false`
  count, so a typo is refused instead of guessed; the default stays listed
  (the user's answer), and the site can offer the choice before the room
  is made (RAS-46).
- **The outside view, decided here:** `host` null and `players` empty, the
  answer of an empty room, rather than a 404 or the host's nick. By the
  reviews' reading of the site code (not run here), a 404 would break the
  game's re-join of a swept member, which reads the room first, while the
  empty answer is one the game already gets for an empty room. Without the
  host's nick, an old id of a link-only room shows nobody. It applies to
  every room, not only listed ones, because a switch to link only does not
  take back an id already seen.

## 2026-11-13 (ninth) - Race socket at /race/<room>, the echo spike removed

Players in a room race on their own copies of the same level, and the API
relays their positions and decides who finished first (RAS-32, contract
section 8). The socket opens only for the race; the lobby stays REST. The
server never simulates the game.

- `GET /race/<room>` with `Upgrade: websocket` (`RaceSocket`), reached by the
  game as `wss://rassimok.dev/klad/api/race/<room>`. It registers through
  `WebSocketHandshakeCheck.upgrade` like every socket route, so it has every
  guard of the spike, in the same order: no answer for a second handshake on
  a connection that holds a socket, 400 (a body), 426 (another version), 503
  (20 sockets), 403 (Origin), 401 (session). Then the room: 404 for an
  unknown room, 409 for a player who is not a member or a room that is not
  `starting` or `racing`. Both give the slot back through the one release
  helper before the answer ends, like the other refusals.
- Messages are JSON objects in text frames, read with Vert.x `JsonObject`
  (no new dependency). Client to server: `ping` (answered `pong`), `pos`
  with `x`, `y` (finite numbers, fractions allowed) and `tick`, and
  `finish` with `ticks` (non-negative JSON integers within a long; `5.0`,
  `"5"` and `1e999` do not count). Server to client: `state` on every
  connect, `countdown` (`"seconds":3`), `go` with `at` (one server time in
  epoch milliseconds for everyone), `pos` with the sender's `slot` and
  `nick`, `results`, `pong` and `error`. A text message over 512 bytes
  closes the socket with 1008 `message_too_long` as in the spike (the
  contract wins over the ticket's "ignored"); anything else that is not a
  valid message gets `{"type":"error","error":"bad_message"}`, `pos` or
  `finish` outside `racing` gets `not_racing`, a second `finish` gets
  `already_finished`, and neither closes the socket or the room.
- The race (`Room`, under `RoomStore`'s lock, every rule on its `Clock`):
  - `POST /rooms/<id>/start` fixes each member's `slot` (0 to 3, join
    order) and the room is `starting`.
  - The countdown starts when every member is connected, or 15 s after
    `starting`; a member not connected then counts as left. `go` follows
    3 s after the countdown and the room is `racing`.
  - `pos` is relayed to every other connected member, never back to the
    sender. More than 10 from one member in any second (a sliding window)
    are dropped silently.
  - The first `finish` received wins; ticks are kept and shown only.
  - `results` go out when every member has finished or left, 30 s after
    the first finish, or 4 minutes after `go`. `places` lists the finishers
    in the order received, then the others in slot order, each with
    `slot`, `nick`, `kind`, `place` (null without a finish), `ticks` (null
    without a finish) and `left`. Every socket is then closed with 1000
    `race_over`, and the room is back in `lobby` with the ready marks
    cleared and every member counted as seen at the end of the race.
  - A member connecting again replaces their old socket, which is closed
    with 1000 `replaced`; the new one gets `state` with the others' last
    known positions while `racing`. Only the current socket's close counts,
    so a replaced socket that closes late changes nothing. After the
    countdown, a member whose socket has been gone for more than 30 s
    counts as left. A socket with no message for more than 60 s is closed
    with 1000 `idle`, and its member counts as left 30 s later.
  - A member who counts as left is out of the room (with the host handover
    of a leave) and is listed with `"left":true` in the results. So is a
    member who leaves, joins another room, is deleted, merged or logs out
    during the race; their socket closes with 1000 `race_over`.
  - A host's new `POST /rooms` closes their old room in any state; a racing
    room's sockets close with 1000 `race_over`, without results.
- `WebSocketHandshakeCheck.upgrade` now takes a `SocketEndpoint`: after the
  session lookup, which now returns the player (`Sessions.signedInPlayer`),
  the endpoint can refuse with a status, and once the socket is open it gets
  a `SocketListener` with `onText` and `onClose`. `onClose` runs once,
  however the socket ends (the socket's close handler, which also gives the
  slot back). A listener that throws drops the socket and logs nothing.
  The 512-byte check moved from the endpoint into the check, so every
  route has it.
- Removed (approved by the user on 2026-11-13, REQ-005): the spike endpoint
  `RaceEchoSocket` (`src/main/java/dev/rassimok/api/klad/RaceEchoSocket.java`,
  route `/race/echo`), its tests `RaceEchoSocketTest` and its native test
  `RaceEchoSocketIT`. Every check they made now runs against
  `/race/<room>` in `SocketGuardsTest` and `SocketGuardsIT` (below).
  `/race/echo` now reaches the race route as a room named `echo`, which
  does not exist: 404 after the guards.

### Tests

- Unit, with the fake clock and recording sockets: `RoomStoreRaceTest` (49)
  covers the refusals (404, 409 for a non-member and for a lobby room), the
  `state` message whole, a socket that finds the room changed or gone since
  its handshake was checked (closed with `race_over`), replacement and a
  replaced socket's late close and message; the countdown when the last
  member connects and not before, `go` at 3 s and not 1 ms before, with one
  `at` for everyone; the 15 s wait on both sides with the missing member
  out of the room and in the results as left; relaying to the others only,
  `not_racing`, the rate limit (10 in a second, the 11th dropped, the next
  accepted once the first is a second old), ping and pong, `bad_message`
  keeping the race; finish order against ticks, `already_finished`; the
  30 s after the first finish and the 4-minute cap, each on both sides;
  the end with every member seen and unready, kept 60 s and removed just
  after; reconnecting within 30 s with the others' positions, leaving after
  more than 30 s, the idle close at 60 s and just over, and the leave 30 s
  after it; leave, forget and join elsewhere during a race, the leave of
  the only member not yet connected (the countdown starts) and of every
  member (back to an empty lobby); the host's new room closing a racing
  room. RAS-31's hard condition: 100 started rooms
  nobody connects to are empty after 15 s and gone 10 minutes later, when
  `POST /rooms` works again, checked step by step and with one late
  access. One late `advance` walks countdown, go and the end in order, each
  at the time it fell due. `RaceMessageTest` (40) covers parsing, and
  `WebSocketHandshakeCheckTest` the 512-byte boundary.
- `@QuarkusTest`, real sockets: `RaceSocketTest` (12) runs a whole race of
  two members (every message compared whole, 1000 `race_over`, the room
  back in `lobby` with no ready marks) and of three (relayed to both
  others, three places), a drop and reconnect during the race, replacement,
  the errors in `starting`, `already_finished`, the host's new room, the
  refusals (unknown room, not a member, lobby, after the race), and 20
  replaced sockets followed by the whole cap.
- `SocketGuardsTest` (37) is the spike's test, moved onto `/race/<room>` and
  parameterised by socket route (`SocketRoute`; today only `RaceRoute`), so
  a later route runs them too. Every abuse test still ends by opening the
  whole cap at once (20 sockets, here in 5 rooms of 4). It adds the route's
  own refusals (404, 409 twice) to the refused-handshake churn, the
  security headers and the pipelined case (a refusal then 101 in one
  write). The not-reading flood now writes empty frames by hand without
  reading: the race answers each with a 41-byte error, so the 512-byte
  messages the echo flooded with no longer produced more replies than the
  network buffers hold.
- `AbandonedHandshakeLogTest` runs against `/race/<room>`;
  `ThrowingListenerLogTest` (JVM only, with the test bean
  `ThrowingSocketRoute`, a route every JVM test application then has)
  checks that a throwing listener drops the socket without a close frame
  or any WARN line, that `onClose` runs once, and then opens the whole cap
  on that route.
- Native: `RaceSocketIT` and `SocketGuardsIT` run both classes against the
  binary.

### Decisions worth not relitigating

- **One ticker, not a timer per room.** `RaceSocket` starts one Vert.x
  periodic timer of 250 ms (`Rules.RACE_TICK`) at startup that calls
  `RoomStore.advance()`, the same sweep every REST access already runs.
  No timer belongs to a room, so none can outlive one. Each rule compares
  the store's `Clock` with the time it falls due, so the unit tests drive
  every rule with the fake clock and never see the timer. A tick costs a
  pass over at most 100 rooms. Cloud Run may throttle the CPU between
  requests, so a tick can come late; while a race socket is open there is
  a request in flight and the CPU is on. A late sweep applies every rule
  that fell due since, in order and dated when it fell due, so the 15 s
  rule and the room cap work with no socket at all (RAS-31's hard
  condition), through any REST access.
- **Sends are queued, the lock never waits.** Each socket's `SocketSender`
  is used on its own event loop only. The room hands every send and close
  to the socket's own context (`runOnContext`) while it holds the lock, and
  the write runs there after the lock is released. Queueing is not I/O,
  and doing it under the lock keeps every socket's messages in the order
  the room produced them (a countdown never overtakes a state). It is
  queued even from the socket's own event loop: a direct send can drop the
  socket, and the close would come back into the room in the middle of its
  work.
- **Who is in the race.** The `state` message lists the members still in
  the race; members who counted as left appear only in `results`. The
  15 s rule applies until the countdown, the 30 s rule from the countdown
  on (the contract names it for `racing`; a drop during the 3 s countdown
  is treated the same). A finished member can still send `pos`, which is
  relayed, and can also count as left. A race whose other members all left
  goes on for the one left.
- **Close reasons.** A member's own leave, a room closed by its host's new
  room and a socket that finds the race gone at connect all close with
  1000 `race_over`, the only reason the contract has for "this race is over
  for you"; the client does not reconnect after 1000.
- **Socket sizing, on purpose.** `Rules.SOCKETS_OPEN_MAX` stays 20, and with
  one instance it is the cap for the whole service: 5 full races of 4 at
  once, against up to 100 rooms. The rest of Cloud Run's 80 request slots
  stay free for REST, which every lobby needs. A sixth race's players get
  503 and the game shows a failed socket. At a full cap a reconnect also
  gets 503 until the old socket's slot comes back. A socket whose client
  vanished without a close holds its slot until the idle close (60 s
  without a message).
- **Grace drop after every close the server starts.** A client that does
  not answer a close frame, or has stopped reading, used to hold its slot
  until Vert.x's 10 s closing timeout. Now every close the server starts
  (replaced, idle, race_over, left, the host's new room, and 1008
  `message_too_long`) also starts, in the same task on the socket's own
  event loop, a timer of `Rules.SOCKET_CLOSE_GRACE` (1 s) that drops the
  connection. A drop on a connection that has ended does nothing, so a
  client that did answer is unaffected. A replaced socket therefore frees
  its slot within about 1 s whatever its client does, which also bounds
  how many slots one cookie can hold by reconnecting. Tests open the whole
  cap with raw clients that read nothing and ignore the close, after a
  1008 and after a replace, and need it back in under 3 s (they take 10 s
  without the drop).
- **A throwing `endpoint.open` drops the socket** instead of leaving a
  paused socket with no handlers; the handlers are installed only once the
  listener exists, and the connection's close gives the slot back.
- **The parser skips text that does not start with a brace**, which cannot
  be a JSON object, so a flood of empty or junk frames costs no exception
  each.
- **Round-6 review items, deferred as follow-ups** (RAS-29 r6): bounding
  the session lookups in flight (holding the slot until the lookup returns
  would change when a slot comes back, so it needs a ticket of its own);
  the "Result is already complete" ERROR when a client leaves right after
  `toWebSocket()`, not tried; and a per-player socket cap, mostly answered
  by one socket per member (a second connect replaces the first) and now
  by the 1 s grace drop.
- **`application.properties` is unchanged.** Its WebSocket comment still
  says the endpoint refuses a message over 512 bytes; the check does that
  now, for every route. The file is CODEOWNERS-protected and this ticket
  does not name it, so the comment waits for a change there.
- **Measured on the native binary** of this branch, set up as for the
  spike: `--memory 256m --memory-swap 256m --cpus 1`, a throwaway
  `postgres:18`, a raw-socket client in a second container that sets up
  the rooms over REST, each row on a fresh server container. `memory.peak`,
  and `anon` sampled every second, come from the container's cgroup; sizes
  in MiB. Idle: 94.4 peak, 20.5 `anon`.

  | Row | Peak | `anon` max | What happened |
  |---|---|---|---|
  | one race of 4 clients | 109.3 | 31.5 | one `go` time for all 4; 180 of 180 positions relayed, none echoed; results in receipt order; 4 times 1000 `race_over`; the room back in `lobby`, nobody ready |
  | 20 reading sockets, 3 MiB of empty frames each (the spike's worst row), run 1 | 202.8 | 103.9 | each of the 10,485,760 frames answered with `bad_message`, no socket dropped, 165 s of CPU |
  | the same, run 2 | 200.3 | 100.9 | 19 sockets answered in full, 1 dropped as not reading, 157 s of CPU |
  | 20 reading sockets, 3 MiB of `{}` frames each, run 1 | 228.7 | 119.9 | each of the 7,864,320 frames parsed and answered with `bad_message`, no socket dropped, 129 s of CPU |
  | the same, run 2 | 214.4 | 107.3 | the same, 128 s of CPU |

  - Empty frames skip the JSON parser (above), so the `{}` rows are the
    worst case: each frame is parsed into an empty object before its
    error. They peak where the spike's echo did in its worst row (226 and
    220 MiB, `anon` 124 and 102), about 27 MiB under the limit at worst;
    the empty-frame rows peak lower although each reply is now 43 bytes
    instead of 2 (not traced to a cause).
  - `/status` answered in 2 ms 10 s into each flood. Nothing was logged at
    WARN or above, apart from the known line about
    `quarkus.log.console.json`.
  - A flood keeps the one CPU busy for as long as it lasts (here about 2.5
    minutes).

## 2026-11-13 (eighth) - Presence and rooms in memory, one instance

Players can see who is online and gather in a room of up to four before a
race (RAS-31, contract section 7). It is all plain REST, polled by the
lobby; the WebSocket opens only when a race starts (RAS-32). Presence and
rooms live in the memory of the one API instance, so Cloud Run now runs at
most one.

- `POST /presence` (204) is the heartbeat; `GET /presence` lists the players
  seen by one in the last 60 s as `{"players":[{"nick","guest","kind"}]}`,
  in the order they came online. `GET /presence` is not a heartbeat.
- `POST /rooms` (201 `{"id"}`) opens a room with the caller as host and only
  member, at level 1, `landscape`. The id is 10 characters of
  `[A-Za-z0-9_-]` from `SecureRandom` (60 bits). `GET /rooms/<id>` answers
  the room: `{"id","state","host":{"nick"},"players":[{"nick","kind","ready","host","you"}],"level","shape","size":4}`.
  `you` is true only on the caller's own entry: nicks may repeat among
  guests, so the game cannot tell itself apart by nick. The view is built
  per caller inside the store lock, so no id leaves the API.
  `POST /rooms/<id>/join`, `leave`, `ready` (`{"ready":true|false}`),
  `level` (`{"level","shape"}`, host only) and `start` (host only, at least
  two players, all ready; the room goes to `starting`) do the rest. Answers
  carry nicks only, never a player id or an email.
- Every endpoint looks up the `klad_session` cookie first, as the other
  resources do, so without a valid session the answer is 401 `no_session`
  whatever the room; the sliding refresh resends the cookie as elsewhere.
  `ready` and `level` read a JSON body and get the 415 and 4 KB rules of
  section 1; the other POSTs read no body and ignore one.
- Room rules: a player is in one room at a time, and joining another room
  leaves the first, but only once the new room has taken them (a full room
  leaves the player where they were). A new `POST /rooms` closes the
  caller's old room, even with other players in it or in `starting`; their
  next poll gets 404. The host is the first member in join order, so when
  the host leaves the next one takes over. In `lobby`, a member whose last
  poll, heartbeat or own room request is older than 60 s is removed on the
  next access to the rooms. A room without members is removed once it has
  been empty for more than 10 minutes.
- Bounds, so memory cannot grow without limit: 100 rooms (`POST /rooms`
  beyond that is 429 `rate_limited`), 1,000 presence entries (a new
  player's heartbeat beyond that is 429; a known player's is not), 4
  players a room (409 `room_full`). Reasoned, not measured: a presence
  entry is a few hundred bytes and a full room about a kilobyte, so both
  stores at their caps stay under 1 MB of the 256 MB.
- Cleanup is lazy: every request to a store first drops what has expired
  there (old presence entries; silent lobby members and old empty rooms,
  across all rooms). There is no scheduler and no new dependency. Every
  time rule reads a `java.time.Clock` that the unit tests replace with a
  fake one.
- `PresenceStore` and `RoomStore` are `@Singleton` beans, each with one
  lock (`synchronized`) around every operation, and no database call inside
  it. `Room` holds one room's lobby and is used only under the store's
  lock; RAS-32 adds the race to it and moves `starting` on to `racing` and
  back to `lobby`.
- `deploy.yml`: `--max-instances 1` (was 3; approved in plan 3.7), nothing
  else. `--min-instances 0` stays, and there is no `--timeout`: the default
  300 s covers a race socket, which opens only for the race (user decision).
- Tests:
  - Unit, with a fake clock: `PresenceStoreTest` (60 s on both sides, the
    count from the last heartbeat, order, rename, two guests with one nick,
    the cap for new and known players, the cap freed by expired entries,
    1,200 heartbeats from 16 threads give exactly 1,000 entries) and
    `RoomStoreTest` (id form and distinct ids, one open room per host, also
    an empty or `starting` one, leaving a room as a guest on create, the
    cap, a host replacing their room at the cap, a guest left in place by a
    refused create, rooms abandoned for 11 minutes on both sides of the
    boundary, 150 creates at once give 100 rooms; join order, full room,
    re-join, also in `starting`, `not_in_lobby`, one room per player and a
    refused join that keeps the old room, 8 joins at once give 3; leave,
    also in `starting`, host handover, the empty room's missing host; ready,
    level and start with every host, member and state rule and an unknown
    room; silent members at 60 s and just over, a silent host, the
    member's own late poll, heartbeats instead of polls, `starting` keeps
    silent members; an empty room at 10 minutes and just over).
  - `@QuarkusTest`, with real cookies: `PresenceResourceTest` (a guest and
    an account, written over plain JDBC, appear with `guest` and `kind`; no
    heartbeat, no entry; two heartbeats, one entry; an expired session; the
    cookie resent; an empty chunked body) and `RoomResourceTest` (401 for
    all nine endpoints without and with an unknown cookie and for an
    expired session on a real room; create, find, 404 for every room
    endpoint, a second room by the same host; join, `room_full`, six joins at once, moving rooms;
    `not_in_lobby` for join, ready, level and start; leave and handover;
    ready marks and six bodies that are not a boolean; level, `not_host`,
    six bad levels and four bad shapes; start, two `not_ready` cases and
    `not_host`; 413 over 4 KB; a body of another type on create). Whole
    JSON objects are compared, so an extra field fails.
    `ResponseHeadersTest` adds the lobby 401, 405, 415 and 400 cases.
  - `LobbyCapsTest` (JVM only) fills the stores through the beans, then
    checks the 429 over HTTP. It has a test profile of its own, so Quarkus
    starts a fresh application for it and the full stores never reach
    another test class.
  - Native: `PresenceResourceIT` and `RoomResourceIT` run the two HTTP
    classes against the binary (JSON of nested records in the native image).

### Decisions worth not relitigating

- **One instance, and what it costs.** Rooms and presence are in memory,
  so every request must reach the same instance. `--max-instances 1` is
  also the free tier's size. Cloud Run applies the cap per revision: while
  a deploy rolls out, the old instance can still serve next to the new one
  (and Cloud Run may briefly exceed the cap in a sudden spike), and a room
  made on one is unknown to the other. Every deploy, restart or scale to
  zero loses all rooms and presence; an invite link then gives 404, which
  the game shows as "room not found". `--min-instances 1` would keep them
  through idle periods at the price of an always-billed instance; not done
  (user decision). The result endpoints keep counting their rate limit in
  the database, which outlives a restart and is shared across a rollout.
  The per-instance limits are now global because there is one instance
  (2 concurrent login-code requests, 20 sockets, Cloud Run concurrency 80)
  and they fail closed: past them the answer is a refusal.
- **Lazy cleanup, all rooms at once.** A scheduler would be a new moving
  part and a test with a fake clock could not see it run. Sweeping every
  room on each access is at most 100 rooms of 4 members, so the cap always
  counts live rooms only.
- **An abandoned room's ten minutes start when its last member counted as
  gone** (last seen plus 60 s), not at the access that noticed it. Dated
  from the sweep, 100 rooms abandoned in a quiet hour would refuse every
  new room for ten more minutes after the first access.
- **An empty room keeps its last host.** If leaving freed the host from
  "one open room per host", one session could fill the 100 rooms by
  creating and leaving, 200 requests, and block rooms for everyone for ten
  minutes. So the last host's next `POST /rooms` still closes the empty
  room. Two sessions that hand a room to each other can still pile up
  empty rooms (about 300 requests to the cap); 100 fresh guests are the
  cheaper route (about 200 requests, healing about 11 minutes after they
  stop). The cap then answers 429, as the contract allows. Whoever joins an empty room becomes its host; its
  `host` is `null` while it is empty.
- **The sweep runs before the caller counts as seen.** A member silent for
  over 60 s who polls again is already out and sees the room without
  themselves; the game can join again. Otherwise a member's removal would
  depend on whether someone else happened to poll first.
- **Any room request by a member counts as seen**, not only the poll and
  the heartbeat the contract names: a member who acts is present.
- **Not in the contract, decided here:** for a player who is not in the
  room, `ready` answers 404 `not_found` (to them it is not there), `level`
  and `start` answer 403 `not_host`, and `leave` answers 204 and changes
  nothing; a member joining their own room again gets 200, also when it is
  full or in `starting`; `leave` works in every state. The body is checked
  right after the session, before the room (400 before 404, 403 and 409),
  as the result endpoints do.
- **Request budget** on the shared 100,000 requests a day of the Workers
  plan: a lobby tab outside a room sends about 240 requests an hour (a
  heartbeat and a presence read every 30 s), inside a room about 600 (also
  the room poll every 10 s and once after each own action), and nothing
  outside the lobby screen. That is about 415 tab hours a day outside a
  room or 165 inside. Each of these requests also
  reads the session from Neon, so an open lobby keeps the database awake
  (it suspends after 5 idle minutes).
- **Not done here:** nothing moves a room out of `starting` yet. Until
  RAS-32 such a room keeps its members and its place under the cap until
  they leave or the host opens a new room.
- **Delete, merge and logout remove the player at once.** `DELETE
  /players/me`, a guest merged into an account by `login/verify`, and
  `POST /logout` call `forget` on both stores: the player leaves the
  presence list and their room (in any state, with the host handover of a
  leave) at once, so a deleted nick does not linger for the 60 s rules. The
  stores never call the database, so the call is safe inside the
  transaction. A request of the same player already in flight can still
  put the entry back; the 60 s rules then drop it.

## 2026-11-13 (seventh) - WebSocket echo spike at /race/echo

A spike for the ghost race: it shows that the API can serve a WebSocket in
the native image, read the player from the session cookie and refuse a
foreign Origin. The endpoint only echoes. It stays until the user approves
its removal; the PR that makes it obsolete lists it for deletion.

- Built on Vert.x core's `ServerWebSocket` (vertx-core 4.5.33, already in
  the build through `quarkus-vertx-http`); no new dependency.
  `RaceEchoSocket` registers `GET /race/echo` on the Quarkus `Router`
  (`@Observes Router`). A request without `Upgrade: websocket` goes on to
  REST and gets the usual 404.
- `/race/echo` answers each text message with the same text. The game will
  reach it as `wss://rassimok.dev/klad/api/race/echo` through the site's
  proxy, so the browser sends the `klad_session` cookie (`Path=/klad/api`).
  Passing the upgrade through the Pages Function is a separate site ticket.
- `/race/echo` goes through `WebSocketHandshakeCheck`, and every later
  WebSocket route must too. Nothing enforces that: a route that calls
  `toWebSocket()` itself gets none of the checks. In this order: a
  handshake with a body (any `Transfer-Encoding`, or a `Content-Length`
  other than 0) gets 400; a `Sec-WebSocket-Version` other than 13, or none,
  gets 426 with `Sec-WebSocket-Version: 13`; a full instance gets 503; an
  `Origin` other than exactly `klad.websocket.allowed-origin`
  (`https://rassimok.dev`), or no `Origin` at all, gets 403; a missing,
  unknown or expired session gets 401, through the existing session lookup
  (`Sessions.isSignedIn`). Before all of these, a handshake on a connection
  that already holds a slot gets no answer at all (see the cap, below). A
  refused handshake carries the three security
  headers but no body; browsers show neither to the page.
- Every socket starts paused and reads one frame at a time (flow control,
  below).
- A text message over 512 UTF-8 bytes (`Rules.SOCKET_MESSAGE_BYTES_MAX`)
  closes the socket with code 1008 (policy violation).
- At most 20 sockets are open at once on one instance
  (`Rules.SOCKETS_OPEN_MAX`); the next handshake gets 503. A slot is taken
  at the start of a handshake and belongs to its TCP connection (below).
- Replies and closes are never awaited (`SocketSender`). A socket with 8
  unfinished sends (`Rules.SOCKET_UNFINISHED_SENDS_MAX`) is closed with
  1008 (`not_reading`) and its TCP connection dropped at once.
- A ping frame drops the TCP connection, and so does any error on the
  connection: a frame or a message over 4096 bytes, for example. Neither
  logs anything.
- A handshake whose client has gone by the end of its session lookup is
  refused instead of upgraded.
- Tests: `WebSocketHandshakeCheckTest` (unit) covers the Origin rule
  (scheme, case, trailing slash, port, subdomains, `null`, none) and
  `OpenSocketsTest` the cap. `RaceEchoSocketTest` uses a real Vert.x
  WebSocket client to cover the echo, the 512-byte boundary in two-byte
  letters, the 1008 close, a burst of 200 messages, a client that stops
  reading (dropped while it pauses), a frame over 4096 bytes, a message in
  two fragments (echoed), a fragmented message over 4096 bytes (dropped),
  a client offering either kind of compression, the socket cap under 40
  handshakes at once, no cookie, an unknown and an expired cookie, a
  foreign Origin (also without a cookie, so the Origin is checked first),
  a missing Origin, the headers on a 401 and a 403, and a plain GET (404).
  With a plain TCP socket it covers:
  - 10,000 empty text frames in one write: the replies come in order, all
    of them or up to the drop for not reading (see below), and a new
    socket echoes meanwhile;
  - 10,000 pings in one write: dropped;
  - a client that pings and never reads (dropped), and the same after the
    server's 1008 for a long message (it must drop within 5 s, because
    Vert.x's closing timeout cuts it after 10 s anyway);
  - 40 handshakes abandoned during the lookup;
  - 40 handshakes with a chunked body of 9,000 bytes, and 40 with a 5-byte
    body and its `Content-Length`: each gets 400; 40 with
    `Content-Length: 0` get 101;
  - 40 handshakes without `Sec-WebSocket-Version` and `Sec-WebSocket-Key`
    (draft 75/76), and 40 of version 8: 426 with
    `Sec-WebSocket-Version: 13`;
  - 40 connections that each send two upgrade requests in one write, read
    the 101 and close 500 ms later;
  - a handshake refused for a foreign Origin, or for an unknown session,
    with a valid one behind it in the same write: the refusal, then 101.
  For each way a socket ends (client close, 1008, not reading, oversized
  frame, ping) it ends 20 sockets in a row; the same after 40 handshakes
  refused for a foreign Origin and 40 refused for no session. Each of these
  cases, and each item of the list above, ends by opening 20 sockets at
  once and keeping them open: with one slot lost, a single new socket
  would still find room. It expires a session through plain JDBC on the
  Dev Services database (`DevServicesContext`), so `RaceEchoSocketIT` runs
  the same cases against the native binary.
  `AbandonedHandshakeLogTest` (JVM only, it reads the log) checks that the
  40 abandoned handshakes log no warning or error, and neither do 40 pairs
  of upgrade requests in one write, each closed once its 101 is read.

### Decisions worth not relitigating

- **Vert.x core, not websockets-next.** The first build of this spike used
  `quarkus-websockets-next` (3.39.3). Its third security review measured
  the native binary under a 256 MB limit, run out of memory by a burst of
  tiny frames: 2 MB of empty text frames on one socket, or 128 KB of empty
  pings on each of 20 sockets. websockets-next queues one event-loop task
  per incoming text message and per ping, with no limit, and nothing stops
  reading while the tasks pile up; it has no per-connection pause or
  demand. Vert.x core's `ServerWebSocket` is a `ReadStream` with `pause`
  and `fetch`, so the server decides when the next frame is read. The
  extension and its keys are gone. Text frames still need no codec, and
  so no reflection setup in the native image; the race protocol will
  parse its JSON text frames itself with Vert.x `JsonObject`.
- **Flow control.** Read in the Vert.x 4.5.33 and Netty 4.1.137 sources:
  - `WebSocketHandshakeCheck` pauses the socket in the same event-loop task
    as the upgrade, sets the frame handler and fetches one frame. Vert.x
    calls the frame handler after the endpoint's message handler; for a
    text, binary or continuation frame it fetches the next one, in a later
    event-loop task. So a frame is handled and its reply's send started
    before the next one is read.
  - Not at once: fetched inside the handler, Vert.x hands over the next
    frame inside the same read, which runs through every byte read (up to
    16 reads of 64 KiB), and no reply is flushed before the read ends. Each
    reply then counts as unfinished, so a reading client sending 9
    messages at once would be dropped as not reading.
  - While a socket waits, Vert.x queues its frames (`InboundBuffer`). From
    16 queued frames on it stops reading the TCP connection (`autoRead`
    off), and the client's further frames wait in TCP. Netty still decodes
    the rest of the chunk already read, up to 64 KiB, so one socket holds at
    most about one chunk of frames: some 11,000 empty frames, or 16 of 4096
    bytes. With 20 sockets that is about 220,000 queued frames at worst,
    and it does not grow; before, each read pass of one socket (up to 1 MiB)
    queued another 175,000 tasks. These counts hold for version 13 frames,
    which a client must mask, so an empty one is 6 bytes. Only version 13 is
    accepted (below).
  - Pings: Vert.x writes the pong for a ping as soon as it is decoded,
    before queueing it and whether the socket is paused or not
    (`WebSocketImplBase.handleFrame`), and fetches by itself after handing
    a ping or a pong to the frame handler (`receiveFrame`). So the frame
    handler fetches only after data frames; fetching after a pong too
    would add up demand. The pongs written before the drop are bounded by
    the same chunk. A client's pong gets no reply and costs nothing.
  - The frame handler keeps fetching after the server's own 1008, so a
    later ping still reaches the drop.
- **Measured on the native binary.** Setup: `--memory 256m --memory-swap
  256m --cpus 1`, a throwaway `postgres:18`, the third review's raw-socket
  client in a second container, every frame empty (6 bytes), each row on
  a fresh container. The server was watched until it went idle, and
  `anon` and `sock` were sampled every second. Sizes are in MiB, like the
  limit. At idle the server held about 95 MiB `memory.peak`, of which
  20 MiB was `anon`; the rest is mostly the page cache of the 87 MiB
  binary. With websockets-next, 5 of the review's first 7 rows were killed
  for lack of memory. Now every row survived, and the server logged
  nothing at WARN or above. `/status` answered in 1 to 2 ms while 20
  reading sockets kept the server busy.

  | Burst | Peak (MiB) | What happened |
  |---|---|---|
  | 1 socket, 1 MiB of pings | 115 | dropped at the first ping |
  | 1 socket, 1 MiB of text, not reading | 142 | every frame answered; 0.33 MiB of replies wait in kernel buffers, socket kept |
  | 1 socket, 2 MiB of text, not reading | 145 | the same, 0.67 MiB of replies |
  | 1 socket, 2 MiB of text, reading | 143 | dropped after 142,000 echoes |
  | 20 sockets, 128 KiB of pings each | 114 | all dropped |
  | 20 sockets, 1 MiB of pings each | 115 | all dropped |
  | 20 sockets, 1 MiB of text each, not reading | 153 | all dropped |
  | 20 sockets, 1 MiB of text each, reading, 2 runs | 206, 200 | `anon` at most 105 and 99; every echo in one run (46 s of full CPU), 3 sockets dropped in the other |
  | 20 sockets, 3 MiB of text each, reading, 2 runs | 226, 220 | `anon` at most 124 and 102; every echo, about 140 s of full CPU |
  | 20 sockets, 0.5 MiB of text then 0.5 MiB of pings, not reading | 153 | all dropped |
  | the same, reading | 215 | derived from the byte count: every text frame echoed, about 1,700 pongs per socket, then no more |

  - The worst peak, 226 MiB, leaves 30 MiB of the 256. The reading rows
    peak highest, because a reading client keeps the server busy the
    longest; `sock` stayed at about 17 MiB in those rows.
  - The GC log of one 3 MiB row shows the heap after each collection
    between 31 and 70 MiB, with no upward trend over 140 s; the heap before
    collection reached 109 MiB. So the 3 MiB rows ask for more headroom,
    not more retained memory. Floods longer than 140 s were not measured.
  - A temporary build that logged each drop showed why one reading socket
    is dropped: not reading (8 unfinished sends), because the native
    server answers faster than the network takes 2-byte replies. Over
    several runs that came after between 1,200 and 230,000 echoes. The
    other drops in the table were not traced to a cause.
  - Messages of a real size do not get there: 20 runs of 200 messages of
    512 bytes from a reading client in another container, and 20 runs of
    1,000, were echoed in full every time.
  - A flood keeps the instance's one CPU busy for as long as it lasts.
- **The Origin rule exists because WebSockets have no CORS.** A browser
  opens a socket to any server from any page, and the server alone decides
  whether to accept it. `SameSite=Strict` already keeps the cookie off
  sockets that other sites open, but it trusts every page on the same site.
  The exact Origin also refuses pages on other rassimok.dev subdomains. A
  non-browser client can send any Origin; the check protects players in
  browsers, which is its job. The Origin is checked before the session, so
  a foreign page never causes a database read.
- **Only version 13 handshakes, without a body.** The fourth security
  review found the server also accepting the draft 75/76 handshake (no
  `Sec-WebSocket-Version`). Its frames are unmasked, so an empty one is 2
  bytes, and one 64 KiB read queued about 32,700 frames per socket instead
  of 11,000; 20 reading sockets took a 256 MiB container to its limit,
  where only reclaimed page cache kept it alive. Browsers send version 13,
  and so must the Workers runtime behind the site's proxy (assumed, not
  tested), so anything else gets 426 like Vert.x's own answer for an
  unknown version. A WebSocket handshake carries no body and browsers send
  none; why one is refused is under the socket cap below.
- **The body and the version come first, then the cap, then the Origin,
  then the session.** The first two take no slot. On a full instance every
  other handshake gets 503, whatever its Origin. All of these but the
  session are checked on the event loop, so neither a full instance nor a
  foreign page takes a worker thread.
- **The allowed Origin is a config value** (`klad.websocket.allowed-origin`
  in `application.properties`). The JVM tests and the native test send the
  production value, so the binary is shown to carry it; the unit test uses
  its own.
- **The session lookup runs on a worker thread.** The route runs on the
  event loop, where a JDBC call must not block. The check hands the lookup
  to the Vert.x context's `executeBlocking`, whose result comes back on the
  event loop, where the upgrade happens. `Sessions.isSignedIn` is
  `@Transactional`, because that worker thread has no request context;
  `POST /players` already calls it inside a transaction and is unchanged.
  Quarkus pauses every request before routing, so the request is still
  unread when the upgrade runs after the lookup; `toWebSocket()` throws,
  instead of failing its future, only for a request already read.
- **The handshake does not move the session's expiry.** The REST calls
  refresh it at most once a day, and a race lasts at most 4 minutes.
- **512 bytes is checked in the endpoint, not only by the frame size
  limit.** Measured with websockets-next, on the same Vert.x server: at a
  frame limit of 512, a larger frame drops the TCP connection without a
  close frame, so the client sees no code. `max-frame-size` and
  `max-message-size` (`quarkus.http.websocket-server.*`) are set to 4096
  instead, only to cap the memory one socket can take. A frame over 4096
  still ends that way, which only a hand-made client can cause
  (`RaceEchoSocketTest` sends one of 5000 bytes).
- **Compression is off.** Measured with websockets-next and
  permessage-deflate on: the limits
  count compressed bytes, and a frame well under 4096 was inflated to
  64 KB before the message limit refused it. The ratio can reach about
  1000 to 1. Messages of at most 512 bytes gain nothing from compression.
  Vert.x accepts permessage-deflate and the older per-frame deflate by
  default, and Quarkus has no key for either, so `WebSocketCompressionOff`
  (an `HttpServerOptionsCustomizer`) turns both off on the HTTP, HTTPS and
  domain-socket servers; the websockets-next key had turned off only the
  first. A client that offers either, as browsers do with the first, gets
  plain frames; `RaceEchoSocketTest` checks that the handshake answer
  carries no `Sec-WebSocket-Extensions`. That test does not pin the class:
  while `vertx.disableWebsockets` is set (below), Vert.x installs no
  extension handler anyway (`HttpServerConnectionHandler`, read in 4.5.33).
  What the class changes today is that the frame decoder treats a frame
  with an extension bit set as a protocol error; it also keeps compression
  off if a later extension clears the property.
- **`vertx.disableWebsockets` does not matter.** Without a WebSocket
  extension, Quarkus sets this Vert.x property. Read in the 4.5.33 code, it
  only skips Vert.x's own upgrade dispatch and the compression handlers;
  `HttpServerRequest.toWebSocket()` does not read it. Vert.x reads it once
  into a static field, so the native test is what shows that the binary
  upgrades.
- **At most 20 open sockets per instance.** On Cloud Run each open socket
  holds one of the instance's concurrent request slots until it closes.
  Without a cap, one free guest cookie and a few hundred idle sockets would
  take every slot, and REST would fail for every player. 20 keeps most of
  the slots free, assuming Cloud Run's default concurrency of 80, which
  `deploy.yml` does not change.
  - **A slot belongs to a TCP connection and comes back exactly once:** at
    a refusal, or when its socket or its connection closes, whichever comes
    first. The slot is taken after the body and version checks and before
    the Origin check, so handshakes sent at once cannot all find room while
    their lookups run. `WebSocketHandshakeCheck` records the connection in
    the same step (`takeSlot`), and every path gives the slot back through
    one helper that does so only while the connection still holds it
    (`giveBackSlot`): the 403, the 401, the 499, a failed lookup, a failed
    upgrade, the socket's close handler and the connection's close.
  - The connection's close is the backstop, for any path that ends in no
    refusal and no socket. Three reviews each found such a path, and each
    had been patched where it was found:
    - a client that left during its session lookup (second review);
    - a handshake with a chunked body over 8192 bytes: Vert.x answers 413
      and closes the connection without completing or failing the
      upgrade. Quarkus's 4K body limit checks only `Content-Length`, so
      the fourth review locked a native instance at 503 for every socket
      with 20 such handshakes of 9,000 bytes;
    - a second upgrade request right behind the first, in the same write.
      Vert.x routed it inside the first one's upgrade, its `toWebSocket()`
      handed over the same socket, and its close handler replaced the
      first one, so one close gave back one slot for two. The fifth review
      left a native instance with 1 slot of 20 after 20 such connections,
      with nothing logged; closed at once instead, a few pairs logged
      "Result is already complete" errors.
  - The backstop listens on `ConnectionBase.closeFuture()`, through the
    same `impl` cast as the drop (below). `HttpConnection.closeHandler`
    holds one handler, and Quarkus sets it when
    `quarkus.http.limits.max-connections` is set (`VertxHttpRecorder`, read
    in 3.39.3), so taking it would break that count; the close future takes
    any number of listeners. A connection stays recorded, without its slot,
    until it closes, so it gets one listener however many handshakes it
    sends: a refusal leaves a connection open for its next request, and
    Cloud Run's front end may reuse its connections (assumed).
  - A connection holds one slot at most. A handshake on a connection that
    holds one is neither upgraded nor answered: Vert.x holds back a request
    sent right behind another until that one's answer is complete, so it
    normally reaches the check only once the connection speaks WebSocket,
    where no HTTP answer fits. Our refusals give the slot back before they
    end their response, because ending it routes the request queued behind
    it at once. Vert.x's own refusals after the lookup (400 for a
    `Connection` header without `upgrade`, or for a missing key) end their
    response before the upgrade fails, so a request queued behind one of
    them gets no answer; no slot is lost (measured on the native binary in
    the sixth review: 400, then no answer, the cap whole). Two upgrades in
    one write are not reachable through the site's proxy, which opens one
    connection per upgrade; at the run.app URL it depends on Google's front
    end (not tested).
  - Checked by mutation in the JVM tests. With the body refusal turned
    off, 40 chunked handshakes of 9,000 bytes got 413, and then 20 sockets
    opened at once; on the code before the backstop, the same left the
    instance at 503. With the socket's close handler giving nothing back,
    every socket test still passed, through the connection's close; with
    the backstop removed as well, the churn tests failed with 503.
  - Repeated on the native binary, set up as in the measurement above,
    each on a fresh container: 25 chunked handshakes of 9,000 bytes (400
    each); 20 connections with two upgrades in one write, held 500 ms and
    closed; 25 such pairs read and closed at once; 20 reading sockets with
    3 MiB of empty frames each (peak 215 MiB, not killed). Nothing was
    logged at WARN or above, and after each, 25 handshakes at once got 20
    times 101 and 5 times 503.
  - A handshake with a body is still refused, before it takes a slot:
    Vert.x would read up to 8192 bytes of it into memory first.
  - The cap bounds the session lookups only while their clients stay
    connected: at most 20 run at a time. A client that leaves during its
    lookup gets its slot back at its close, and the lookup still runs. The
    REST endpoints have no bound on their database reads either.
  - Accepted, not solved: the cap protects REST capacity, not the bill.
    One open socket keeps an instance busy and billed; `--max-instances`
    and the budget alert bound that cost.
  - A socket closed for a long message while its client does not read is
    cut by Vert.x 10 s after its close frame is written (the closing
    timeout; measured with websockets-next). If unread replies hold that
    frame back, the timeout never starts (read in the Vert.x 4.5 code, not
    measured), and the socket stays up until the client or Cloud Run's
    request timeout ends it. The server sends it nothing more, and the cap
    counts it until then. A socket closed for not reading, or sent a ping,
    is dropped at once and frees its slot.
- **Sends are not awaited.** An echo that waited for its send would stall
  once the client stopped reading, and the socket would keep its slot.
  Each message is handled as it arrives, and after 8 unfinished sends the
  socket is closed and dropped, so Netty holds at most 8 unsent replies
  per socket. The kernel's socket buffers hold more for a client that does
  not read (0.3 to 0.6 MiB measured), and they count in the instance's
  memory. A client that reads about as fast as it sends stays far below
  8: a burst of 200 or 1,000 messages of 512 bytes is echoed in full. A
  client that floods tiny
  frames faster than the network takes the 2-byte replies is dropped like
  one that does not read: measured on the native binary, one reading
  socket was dropped after between 1,200 and 230,000 echoes, depending on
  the run. The race protocol sends through `SocketSender` too.
- **A client that does not read is dropped, not only closed.** Vert.x
  closes the TCP connection only once its close frame is written, and the
  1008 waits behind the replies the client does not read, so it would
  never arrive and the socket would keep its slot until Cloud Run's
  timeout. The 1008 is still sent first, but the client is not sure to
  get it; the tests check only that the connection ends.
- **A ping drops the connection.** Vert.x answers every ping with a pong,
  even after the server's close, so a client that pings and does not read
  would make the server queue pongs; without the drop, a plain TCP client
  kept its connection through 10 s of pings. Browsers cannot send pings,
  so no player is affected. Assumed, not measured: neither Cloud Run's
  front end nor the site's proxy sends pings of its own. If one does,
  every socket through it ends at its first ping.
- **Errors drop the socket and log nothing.** Vert.x logs an ERROR for
  every connection error without a handler; websockets-next logged two,
  one with a stack trace, for each frame over 4096 bytes, a cost in Cloud
  Logging that any client could cause. The socket's exception handler now
  drops the connection instead. That also ends a fragmented message over
  4096 bytes, which Vert.x reports without closing the socket.
- **How the drop is done.** In Vert.x 4.5 `HttpConnection.close()` and
  Netty's `Channel.close()` both turn into the WebSocket close and wait
  for its frame. `SocketSender.drop` calls
  `ConnectionBase.channelHandlerContext().close()`, the call Vert.x makes
  itself once the frame is out. The socket's own connection is not public
  API, and Vert.x Web wraps the socket, so `SocketSender` keeps the
  handshake request's connection. `ConnectionBase` is a Vert.x `impl`
  class, and the slot backstop uses it too (`closeFuture()`); an upgrade
  that changes it breaks the build, the ping and not-reading tests, or the
  tests that open the whole cap.
- **An endpoint cannot undo the guards.** It hands
  `WebSocketHandshakeCheck.upgrade` a text callback and gets each text
  message with the socket's `SocketSender`, never the socket. So it cannot
  replace the close, exception or frame handler, or resume the socket. Left
  for the race protocol, which needs them and can test them: a close
  callback, and dropping a socket whose callback throws (Vert.x logs such
  an exception as an ERROR, once per message). Left for it too: the
  refusal, ping, burst and churn tests parameterised by socket path, so
  they run for every path.
- **A client gone during the session lookup is refused.** Measured with
  websockets-next: the close lands inside the lookup, and the upgrade then
  threw "Request has already been read" and logged an ERROR with a stack
  trace, once per abandoned handshake. The check now refuses such a
  handshake (499, never sent, because the client has gone) just before the
  upgrade, which logs nothing; its slot came back at the close. The
  handshake itself runs one event-loop task after `toWebSocket()`; a close
  in that gap fails the upgrade, and Vert.x 4.5 then logs one ERROR with a
  stack trace ("Result is already complete", from its own
  `createWebSocket`; about 3 in 100 handshakes abandoned in a concurrent
  burst, measured in the sixth review). No slot is lost. The line comes
  from inside Vert.x and cannot be removed from the endpoint; RAS-32 may
  try a no-op exception handler after `toWebSocket()`.
- **Session lookups in flight are not bounded by the cap.** A handshake
  abandoned during its lookup gets its slot back when its connection
  closes, while the lookup still runs. The sixth review flooded a 256 MiB
  native instance with about 4,200 abandoned handshakes a second from a
  raw client and killed it for lack of memory in about 15 s. The review
  rated it minor: it is the same class as a flood of any REST request with a
  cookie; at the run.app URL any client can send the expected `Origin`,
  and through the site's proxy each attempt is a metered Workers request;
  `--max-instances 3` bounds the cost.
  RAS-32 may hold the slot until the lookup returns, which caps lookups at
  20 at a time.
- **The Cloud Run idle timeout was not measured.** That optional check needs a
  deploy. The race cap of 4 minutes keeps a socket inside the default
  5-minute request timeout, so `deploy.yml` is unchanged.

## 2026-11-13 (sixth) - Nick keys compare lookalike letters

A nick written in Cyrillic letters that look like Latin letters or digits,
or with 0 for O and 1 or I for l, no longer gets past an account's nick
(RAS-35; the user decided on a skeleton in the RAS-24 security review).

- `Nicks.key` is now a lookalike skeleton: the NFKC-normalised, trimmed nick
  in lower case, then each character of `Nicks.LOOKALIKES` replaced by the
  character at the same place in `Nicks.LATIN_EQUIVALENTS`. The Cyrillic
  letters a, ve, ghe, ie, ka, em, en, o, er, es, te, u, ha, sha and the
  soft sign (U+0430, U+0432, U+0433, U+0435, U+043A, U+043C, U+043D,
  U+043E, U+0440, U+0441, U+0442, U+0443, U+0445, U+0448, U+044C) become
  `a b r e k m h o p c t y x w b`, the Cyrillic be and ze (U+0431, U+0437)
  become `6` and `3`, the digit 0 becomes `o`, and the digit 1 and the
  Latin i become `l`. An account `BOB` so holds `bob`, `B0B`, BOB in
  Cyrillic capitals, the same in Cyrillic small letters, and every case mix
  of them; an account `Paul` holds `PauI`, and `Bill` holds `Bi11`.
- The displayed nick does not change, and the nick rules do not either:
  `isValid` still runs on the nick, so Latin and Cyrillic letters never mix
  in one nick, also where the key would be all Latin. The chests board
  still breaks ties by `nick_key`, so tied nicks with a mapped character
  may sort differently.
- Migration `V5` recomputes `nick_key` for every row with SQL `translate()`
  and the same two strings; the partial unique index from `V4` stays.
  Production has no accounts yet, so no conflict is expected. If two
  accounts ever held keys that become one, the update fails on the index,
  Flyway rolls `V5` back and the service does not start, so the deploy
  fails; Cloud Run should keep serving the previous revision (reasoned, not
  tried) until one of the two is renamed by hand.
- Verify computes the key from the guest's nick again and stores it with
  the email, instead of trusting the stored key (see the deploy window
  below).
- Guests may still share a lookalike nick with each other. A guest that
  already shares a key with an account keeps its nick, with the guest
  marker, and cannot become an account under it (verify answers 409), as
  before.
- Tests:
  - Unit (`NicksTest`): the key of each mapped Cyrillic letter in both
    cases, of 0, 1, I and i, and of ze and be in both cases; every other
    nick character only lower-cased; ten spellings of `bob` (Cyrillic
    capitals, small letters and both, zero, fullwidth) give one key; so do
    `Paul` and `PauI`, `Bill` and `Bi11`, `Bill` and `Bili`, `B3AP` and its
    Cyrillic spelling with ze, `6ob` and its Cyrillic spelling with be;
    three mixed-script nicks whose key would be `bob` are still refused.
    The two existing Cyrillic key expectations now end in a Latin `k`.
  - `PlayersResourceTest`: an account `BobCat` refuses eight lookalike and
    case variants on create (409, no cookie, no new row); a guest's rename
    to `Tomcat` in Cyrillic capitals, an account's nick, is refused and
    leaves the guest unchanged; three guests share `Comet` in Latin, in
    Cyrillic and with a zero, each shown as typed. Three existing
    expectations follow the new key: `MiXeD` is stored as `mlxed`, and the
    counts look for `kept_nlck` and `twlnguest`.
  - `LoginResourceTest`: a guest `BOB_42` in Cyrillic capitals cannot
    become an account once the account `Bob_42` exists (409 `nick_taken`,
    no cookie, guest row unchanged). A guest whose stored key is only
    lower-cased, as the old revision wrote it, gets the same 409; without a
    clash it becomes an account and its row then holds the skeleton.
    Measured: with the stored key trusted again, both of these fail.
  - `NickKeyMigrationTest`: the `V5` file, run again on rows with the old
    key (every nick character in one row, and seven real nicks), gives
    `Nicks.key` and changes no other column; two accounts whose keys `V5`
    joins make it fail with a unique violation.
  - `LoginRoundTripTest`, and so `LoginRoundTripIT` on the native binary:
    an account `TeamBox` refuses its Cyrillic spelling and `teamb0x`.
  - `TestPlayers.insertAccount` now writes `Nicks.key`, not SQL `lower()`.

### Decisions worth not relitigating

- **Lower case first, then the map, and a small letter is listed when it or
  its capital looks like the Latin letter.** Mapping only the capitals
  before lower-casing turns Cyrillic capital ve into B, but leaves the
  small ve, whose confusable is a small capital B outside the nick
  alphabet. BOB in Cyrillic capitals and the same nick in small letters
  would then get two keys, and two accounts could hold them; the existing
  one-key-per-case test would fail that way. Keys ignore case, so a letter
  counts as a lookalike when either of its cases does. The cost: an account
  `Tom` also holds its Cyrillic spelling in small letters, which looks less
  alike. The capitals pay the same way: a letter that looks Latin in only
  one case counts in both (capital ghe as r, capital be as 6), so an
  account reserves a little more than what really looks like it.
- **The map is every entry of Unicode's `confusables.txt` (UTS #39, the
  file dated 2026-08-06) that maps a nick character to one other nick
  character, taken in lower case.** Next to the ticket's examples that adds
  ghe (small ghe looks like r), sha (w), the soft sign (its capital looks
  like b), capital ze (3), small be (6), and on the Latin side 1 and
  capital I (both l). Digits are allowed in Latin and Cyrillic nicks, so
  without ze and be an account `B3AP` and the Cyrillic ve, ze, a, er could
  be two accounts that look alike. The file lists capital I, not small i,
  as l; small i is mapped only because keys are lower-cased first, so I
  and l meet, and with it `Bill` also reserves `Bili` (accepted). Left
  out: letters listed as two (yeru as `bl`, yu as `lO`, m as `rn`) and `|`,
  which a nick cannot hold. Yo is not mapped: its confusable is E with a
  diaeresis, which a Latin nick cannot hold either. The map was widened to
  1, I, ze and be after the review, before RAS-35 is deployed: with no
  players in production `V5` cannot clash now, while a later widening could
  fail on real accounts.
- **`V5` maps the stored key, not the nick.** Every stored key is the nick
  lower-cased by Java, so only the map is left. SQL `lower()` changes
  Cyrillic letters only under some database locales, and the Neon
  database's locale was not checked; mapping the key does not depend on it.
  The update is idempotent: no character it writes is one it replaces.
- **One map, written twice.** `LOOKALIKES` and `LATIN_EQUIVALENTS` mirror
  the two arguments of `translate()`, and `V5` repeats them, because a
  migration cannot read Java. `NickKeyMigrationTest` runs the `V5` file on
  every nick character and compares with `Nicks.key`, so a typo in either
  copy fails the build (measured: one changed letter in `V5` fails it).
  `LATIN_EQUIVALENTS` keeps its name although it now holds 3 and 6: they
  are characters of a Latin nick.
- **The deploy window and rollbacks.** Flyway runs `V5` when the new
  revision starts, while the old one still serves. In that window the old
  revision writes and compares keys that are only lower-cased against keys
  `V5` has mapped, so not only a lookalike but the exact nick of an account
  whose key `V5` changed (BOB in Cyrillic capitals, `B0B`, `Bill`) can be
  taken there by a new guest or a rename. A guest made so cannot become an
  account on the new revision, because its verify computes the key from the
  nick again. An account made or renamed by the old revision keeps its old
  key. A rollback to a revision before RAS-35 does the same for as long as
  it serves, and `V5` does not run twice, so after such a rollback the `V5`
  update has to be run again (by hand or in a new migration). Production
  has no players yet.

## 2026-11-13 (fifth) - Unique account nicks, guest merge and account deletion

An account's nick now belongs to it, results follow the account to a new
device, and deleting an account leaves nothing of it behind.

- Nick keys (the NFKC-normalised, trimmed nick in lower case) are unique
  among accounts, in one pool for both kinds. Migration `V4` adds the
  partial unique index `player_nick_key_account_idx` on `player (nick_key)
  where email is not null`; `V1` to `V3` are unchanged, and production has
  no rows yet.
- `POST /players` and `PATCH /players/me` answer `409 nick_taken` when an
  account holds the nick's key, so `Bob`, `BOB`, ` bob ` and full-width
  letters all count as one. Guests still share nicks with other guests. An
  account may take a nick that only guests use (they keep it), and may
  change the case of its own nick.
- `POST /login/verify`, no account for the email: the device's guest still
  becomes the account, and gets `409 nick_taken` when an account holds its
  key. The check runs first; the unique index catches what the check
  missed (see below).
- `POST /login/verify` into an existing account A: if the device's guest
  has A's kind, its `level_clear` and `game_run` rows move to A, the guest
  and its sessions are deleted in the same transaction, and the answer has
  `"merged":true`. A guest of the other kind is left as it is, without a
  session (`"merged":false`, the decision recorded in contract section 5).
  A device that holds an account, A itself or another, merges nothing. The
  boards already show one entry per player, its best, so A appears once
  with the best of both.
- `DELETE /players/me` for an account also deletes the `login_code` row of
  its email's hash. The send-log rows (hashes only) go a day later, with the
  next code request, as the contract says.
- From the second RAS-23 reviews:
  - Code requests take one of 2 slots per instance before their
    transaction (`Rules.CODE_REQUESTS_PER_INSTANCE`, a `Semaphore` in
    `LoginCodes`). Without a free slot the answer is `429 rate_limited` at
    once; the slot is released before the mail goes out.
  - `quarkus.mailer.login=REQUIRED`: without `RESEND_SMTP_KEY` a send fails
    after the greeting, before any address is sent, and answers 500 like any
    failed send.
  - The root-cause walk for the failed-mail log line follows at most 10
    causes, so a cause cycle cannot spin a thread.
- Tests:
  - Unit: one nick key for case, width and decomposed variants (Latin and
    Cyrillic); a Latin nick with a Cyrillic lookalike is refused, also after
    NFKC; the bounded cause walk, with a cycle.
  - `DatabaseMigrationTest`: a second account with the same key is refused
    (also of the other kind), guests with an account's key are not.
  - `PlayersResourceTest`: 409 for five variants of an account's nick on
    create; guests share a nick; a guest's and an account's rename to an
    account's key; an account's case change; an account taking a guests'
    nick; and the race: another connection gives an account the key after
    the check, the rename waits on the index and answers 409.
  - `LoginResourceTest`: the merge (every column of the moved rows compared,
    the guest's rows gone, one board entry); another kind left alone, with
    its results; an account already on the device; the verify race on the
    index (409, the guest and the code unchanged); deletion of an account
    with two devices, results and a pending code (no row of the player, no
    code row, the address in no table); a wrong code answering the same
    with and without an account; a code row held past 2 s (429); two
    requests waiting for the lock and a third refused at once.
  - `LoginMailFailureTest` also checks that the stored code is kept, and
    `LoginMailWithoutKeyTest` that a send without a key ends before the
    recipient command (no SMTP reply, no address in the log).
  - `LoginRoundTripTest`, and so `LoginRoundTripIT` on the native binary:
    an account's nick refused on create and on rename.

### Decisions worth not relitigating

- **Check first, then the index.** The query answers the normal case. Two
  instances can still pass the check for the same key at once; the partial
  unique index then refuses the second write. To answer that with 409 and
  not a 500 at commit, `PATCH /players/me` and verify flush the change
  inside the method (`Player.flushNickKey`), match the violation on the
  index name, and mark the transaction rollback-only: the failed statement
  has aborted it, and verify's `dontRollbackOn` would otherwise try to
  commit it. In that race verify's code is therefore not used up, unlike
  the other 409 (one-off, and only the code's holder gets there). Measured:
  without the flush both race tests answer 500.
- **A guest can still race an account for a key.** The index covers only
  accounts, so a guest created or renamed in the same instant as an account
  takes the key keeps it. It shows with the guest marker and can never
  become an account under that key (the verify gets 409). Closing it would
  need a lock across instances on every nick write; not worth it for a
  marker.
- **The merge locks the guest's row** the way a result submission does. A
  submission already under way is moved with the rest; one that waits for
  the lock finds its guest gone and fails with a 500 (reasoned, not
  tested), as it would anyway once the verify has ended the device's
  session.
- **The merge moves every row, not only the best.** The boards pick each
  player's best already, and nothing is lost if the account's best is later
  beaten by the guest's.
- **An account's code row is deleted before the player**, with a query
  delete that runs at once (Panache's `deleteById` would only run at
  commit; the SQL log shows the order). Verify locks the code row and then
  the player; the other order could deadlock with a verify of the same
  email.
- **2 code-request slots per instance.** The lock timeout bounds each wait,
  and so how long a flood keeps going once it stops; it does not bound how
  many pooled connections the waiters hold (50 by default, with up to 80
  requests in flight per instance). The slots do: at most 2 per instance, 6
  across three instances. Code requests count one after another in the
  database anyway, so a third waiting request gains nothing. Normal traffic
  never sees them.
- **The 10-parallel-requests test sends two codes first** and expects one
  202 and nine 429s. With the slots, how many of a burst reach the database
  depends on timing; with one code left to send the outcome does not, and
  two requests in the database at once still show the table lock at work.
- **`quarkus.mailer.login=REQUIRED`.** With the default (`NONE`) and no
  key, the mailer skips the login and goes on to the mail commands (read
  from the vertx-mail code, not tried against Resend). With `REQUIRED` it
  stops after the greeting. The local SMTP stub in the tests now offers `AUTH PLAIN`, and
  its resource sets a test key unless told not to.

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
  guest on this device. Here a guest on a device that logs into another
  account is left alone; the fifth entry merges a guest of the account's
  kind (`"merged":true`).
- `POST /logout` reads no body, deletes the session row, clears the cookie
  (`Max-Age=0`) and answers 204. `401 no_session` without a session.
- Email: trimmed, lower case, at most 254 characters, one `@` with text on
  both sides. Spaces, control characters and `< > ( ) [ ] , ; : " \` are
  refused too.
- Code: 6 digits from `SecureRandom`, valid 10 minutes, stored as the
  SHA-256 of `email + ":" + code` in lower-case hex, compared with
  `MessageDigest.isEqual`. A new request replaces the email's code. In
  `POST /login/verify` the code must be a JSON string of exactly 6 ASCII
  digits; anything else (a JSON number such as `123456`, `null`, 5 or 7
  digits, a space, Arabic-Indic or full-width digits) is `400 invalid_code`
  and counts no attempt.
- Limits, counted in the database: 3 codes per email per hour, 80 mails a
  day overall. All bounds sit in `Rules`. Every valid code request, a
  refused one too, first deletes send-log rows older than a day and expired
  codes. A code request that waits more than 2 s for its lock answers
  `429 rate_limited` too, and so, since the fifth entry, does one that finds
  no free request slot on its instance.
- A failed send answers `500 internal_server_error` and logs one line with
  the root cause's class name and the SMTP reply code, never the exception
  or its text.
- No table holds the address for a code request. `login_code` is keyed by
  the SHA-256 of the normalised email (lower-case hex) and the send log
  keeps the same hash; the address is stored only in `player.email`, when a
  verify turns a guest into an account. The API sends no mail but the login
  code.
- Migration `V3` adds `login_code` (one row per email: `email_hash`, code
  hash, attempts, expiry, creation) and `login_mail`, the send log (SHA-256
  of the email and the send time). `V1` and `V2` are unchanged.
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
  from `shasum`), expiry, the generated code's format, the accepted code
  (6 ASCII digits only), the mail text and both limit windows on a fixed
  clock.
  `LoginResourceTest` (with the mock mailbox) covers the sent mail and what
  is stored, a scan of every table for the address after a code request
  (none holds it) and after a verify (only `player`), invalid emails, the
  replaced code, wrong codes, a numeric and other malformed codes (no
  attempt counted, the right string still works), four wrong then right,
  the fifth wrong, 10 parallel guesses, expiry, a used code, both limits
  and their windows, old rows deleted on a refused request, 10 parallel
  code requests, a guest becoming an account, a second device, an account
  with a guest on the device, `nick_taken`, `no_session`, and logout; verify
  and logout send no mail. It also covers a code request while a verify
  holds and then deletes the code row (202 and a new code), and one that
  waits past the lock timeout (429, and the next request works).
  `LoginMailFailureTest` sends through a local SMTP stub that refuses the
  recipient and repeats the address in its reply: 500, one log line from
  `LoginResource`, no logged throwable, the address in no log record.
  `DatabaseMigrationTest` pins the `login_code` columns and its
  `email_hash` key.
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
  requests count one after another; verify locks the email's code row, and
  so does the code request before it replaces the code. Measured without
  them: 10 parallel requests for one email gave one 202 and nine 500s (the
  per-email row raced), and 10 parallel wrong guesses all got 400: the
  attempt counts overwrote each other, the fifth was never reached and the
  code survived. With them: three 202s and seven 429s, and exactly one 429
  among the guesses (the burst test now sends two codes first, see the
  fifth entry). Without the row lock in the code request, a verify
  that deleted the row first made the request's update find nothing (500).
  Code requests are few, so a lock over all of them costs nothing.
- **A 2 s lock timeout on code requests** (`set local lock_timeout`, so it
  ends with the transaction, also on a pooled connection). It bounds each
  wait, and so how long a flood keeps going once it stops; it does not
  bound how many pooled connections the waiters hold. The request slots in
  the fifth entry do that. A timed-out request answers
  `429 rate_limited`, also when the wait is for the code row; Hibernate
  still logs two warnings, the SQL state `55P03` and PostgreSQL's "canceling
  statement due to lock timeout", with no address.
- **The mail goes out after the code is committed.** No lock is held while
  Resend answers (the blocking mailer waits up to 60 s). A failed send
  answers 500, keeps the stored code and still counts toward the limits,
  which errs on the side of the mail quota.
- **A failed send logs no exception text.** The mail server's reply is in
  the exception's message (`recipient address not accepted: 550 ...`), and
  replies often repeat the address; logged as a throwable it would put the
  address in Cloud Logging for 30 days, against the contract's "no address
  stored for a code request". So `requestCode` logs only the root cause's
  class and the SMTP reply code (0 when there is none), which tells a
  refused recipient (550) from a refused key (535). For the same reason
  `quarkus.mailer.mock` (it logs every mail with recipient and code) and
  `quarkus.mailer.log-invalid-recipients` stay off in prod.
- **A code request stores hashes, never the address** (contract section 5,
  decided 2026-11-13 after the RAS-25 review; the site's privacy note
  promises it). An address that asks for a code and never verifies leaves
  only hashes, which go with the next code request once they are outdated:
  `login_code` and the send log both key on the SHA-256 of the normalised
  address, and the code hash still binds email and code. Both only need to
  recognise an address again, which a hash does.
  The address is written only into `player.email`, by a verify that makes
  an account. An unsalted hash still lets someone holding the tables test a
  known address; it keeps the list of addresses out of them.
- **`V3` was changed in place.** It had run only in tests (Dev Services
  starts an empty database each time), never against Neon, so no database
  holds the old checksum. Once it has run anywhere real, a change needs a
  new migration.
- **A malformed code counts no attempt.** It can never be the right code
  and is refused before the row is read, with the same `400 invalid_code`
  whether or not a code exists, so it reveals nothing; counting it would let
  a broken client use up a player's five attempts.
- **Old rows go on every code request, a refused one too.** The privacy
  note promises that outdated records go with the next code request, and a
  429 is one. `issue` deletes send-log rows older than a day and expired
  codes before it counts, and `dontRollbackOn = ApiException.class` keeps
  those deletes when the answer is 429. Nothing runs between requests, so a
  day is not a hard bound.
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
  without it. The deploy always sets it. Since the fifth entry
  (`quarkus.mailer.login=REQUIRED`), a send without it fails before any
  address is sent.

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
