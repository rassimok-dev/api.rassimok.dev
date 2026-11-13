# Changelog

Dated entries, newest first. Written for a reader picking this up cold —
or a future session needing to know what already exists and why.

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
  that already carries a socket gets no answer at all (see the cap,
  below). A refused handshake carries the three security
  headers but no body; browsers show neither to the page.
- Every socket starts paused and reads one frame at a time (flow control,
  below).
- A text message over 512 UTF-8 bytes (`Rules.SOCKET_MESSAGE_BYTES_MAX`)
  closes the socket with code 1008 (policy violation).
- At most 20 sockets are open at once on one instance
  (`Rules.SOCKETS_OPEN_MAX`); the next handshake gets 503. `OpenSockets`
  counts each one from the start of its handshake to its close.
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
  - 10,000 pings in one write: dropped, and a new socket echoes;
  - a client that pings and never reads (dropped), and the same after the
    server's 1008 for a long message (it must drop within 5 s, because
    Vert.x's closing timeout cuts it after 10 s anyway);
  - 40 handshakes abandoned during the lookup (a normal socket is accepted
    after them);
  - 40 handshakes with a chunked body of 9,000 bytes, and 40 with a 5-byte
    body and its `Content-Length`: each gets 400, and a normal socket is
    accepted after them;
  - a handshake without `Sec-WebSocket-Version` and `Sec-WebSocket-Key`
    (draft 75/76), and one of version 8: 426 with
    `Sec-WebSocket-Version: 13`;
  - 40 connections that each send two upgrade requests in one write, read
    the 101 and close 500 ms later: then 20 sockets must be open at once,
    because a single new socket would still find room with 19 slots
    lost.
  For each way a socket ends (client close, 1008, not reading, oversized
  frame, ping) it ends 20 sockets in a row and then expects one more to be
  accepted; the same after 40 handshakes refused for a foreign Origin and
  40 refused for no session. It expires a session through plain JDBC on
  the Dev Services database (`DevServicesContext`), so `RaceEchoSocketIT`
  runs the same cases against the native binary.
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
  - A slot is taken before the Origin check, and given back on every
    later refusal and on a failed upgrade, or else by the socket's close
    handler, which Vert.x calls once per socket (`handleClose` clears it
    under the connection lock). So handshakes sent at once cannot all find
    room while their lookups run, and at most 20 lookups take worker
    threads at a time.
  - That holds only for a handshake without a body. Vert.x reads a body
    before the upgrade, and over 8192 bytes it answers 413 and closes
    without completing or failing the upgrade, so the slot never came
    back. Quarkus's 4K body limit checks only `Content-Length`, so a
    chunked body got through: the fourth security review locked a native
    instance at 503 for every socket with 20 such handshakes of 9,000
    bytes. A handshake with a body is now refused before it takes a slot.
  - It also holds only while each socket belongs to one handshake: the
    close handler gives back one slot per socket. A client can send a
    second upgrade request right behind the first, in the same write.
    Vert.x queues it and routes it inside the first one's upgrade, once
    the 101 is out and before the socket's handlers are set. It passed
    every check and took a second slot, its `toWebSocket()` handed over
    the first request's socket, and its handlers replaced the first ones,
    so one close gave back one slot for two. The fifth review left a
    native instance with 1 slot of 20 after 20 such connections, with
    nothing logged; closed at once instead, a few pairs logged "Result is
    already complete" errors. The check now keeps each connection from
    just before `toWebSocket()` until the upgrade fails or the socket
    closes, and ignores a handshake on such a connection: it takes no slot
    and gets no answer, because the connection speaks WebSocket by then.
    Not reachable through the site's proxy, which opens one connection per
    upgrade; at the run.app URL it depends on Google's front end (not
    tested).
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
  class; an upgrade that changes it breaks the build or the ping and
  not-reading tests.
- **An endpoint cannot undo the guards.** It hands
  `WebSocketHandshakeCheck.upgrade` a text callback and gets each text
  message with the socket's `SocketSender`, never the socket. So it cannot
  replace the close, exception or frame handler, or resume the socket. Left
  for the race protocol, which needs them and can test them: a close
  callback, and dropping a socket whose callback throws (Vert.x logs such
  an exception as an ERROR, once per message). The refusal, ping, burst
  and churn tests are left for it too, to run for every socket path.
- **A client gone during the session lookup is refused.** Measured with
  websockets-next: the close lands inside the lookup, and the upgrade then
  threw "Request has already been read" and logged an ERROR with a stack
  trace, once per abandoned handshake. The check now refuses such a
  handshake (499, never sent, because the client has gone) just before the
  upgrade, which logs nothing. The handshake itself runs one event-loop
  task after `toWebSocket()`; a close in that gap fails the upgrade and
  frees the slot.
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
