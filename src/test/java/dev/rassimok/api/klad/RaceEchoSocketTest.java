package dev.rassimok.api.klad;

import io.quarkus.test.common.DevServicesContext;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.UpgradeRejectedException;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketClient;
import io.vertx.core.http.WebSocketClientOptions;
import io.vertx.core.http.WebSocketConnectOptions;
import io.vertx.core.http.WebSocketFrame;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.postgresql.ds.PGSimpleDataSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A real WebSocket client and plain JDBC, no injection, so the native
 * integration test can run it against the binary.
 */
@QuarkusTest
class RaceEchoSocketTest {

    private static final String ALLOWED_ORIGIN = "https://rassimok.dev";
    private static final long TIMEOUT_SECONDS = 10;
    // 256 Cyrillic letters of two bytes each: exactly the 512-byte limit.
    private static final String LARGEST_MESSAGE = "\u0416".repeat(256);
    private static final short POLICY_VIOLATION = SocketSender.POLICY_VIOLATION;
    private static final String FOREIGN_ORIGIN = "https://example.com";
    private static final int BURST_MESSAGES = 200;
    // 32 MB, far more than the network buffers between client and server
    // can hold, so the server is past its bound while the client pauses.
    private static final int FLOOD_MESSAGES = 65_536;
    private static final int OVERSIZED_FRAME_BYTES = 5000;
    // Two of these are over the message limit of 4096, each under the frame limit.
    private static final int FRAGMENT_BYTES = 3000;
    // Below the Vert.x client's default of 50 sockets per server.
    private static final int CAP_ATTEMPTS = 2 * Rules.SOCKETS_OPEN_MAX;
    private static final int ABANDONED_HANDSHAKES = 2 * Rules.SOCKETS_OPEN_MAX;
    private static final long PAUSE_MILLIS = 10;
    // A ping without payload, masked as every client frame must be.
    private static final byte[] PING_FRAME = {(byte) 0x89, (byte) 0x80, 0, 0, 0, 0};
    private static final int PINGS_PER_WRITE = 100;
    // 60 KB of empty frames, about what the server reads from TCP at once.
    private static final int BURST_FRAMES = 10_000;
    // An empty text frame from the server, which sends without a mask.
    private static final byte[] EMPTY_ECHO = {(byte) 0x81, 0};
    // Below Vert.x's closing timeout of 10 s, which ends a socket by itself
    // once the server's close frame is written.
    private static final long DROP_SECONDS = 5;
    // Time for the server to send its 1008 before the first ping arrives.
    private static final long SERVER_CLOSE_MILLIS = 500;
    private static final String SWITCHING_PROTOCOLS = "HTTP/1.1 101 Switching Protocols";
    private static final String BAD_REQUEST = "HTTP/1.1 400 Bad Request";
    private static final String UPGRADE_REQUIRED = "HTTP/1.1 426 Upgrade Required";
    private static final String END_OF_HEADERS = "\r\n\r\n";
    private static final String KEY = "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n";
    private static final String VERSION_13 = "Sec-WebSocket-Version: 13\r\n";
    // Over the 8192 bytes of body Vert.x reads before an upgrade.
    private static final int CHUNK_BYTES = 9000;
    // Long enough for a second handshake on the same connection to get
    // through its session lookup before the client closes.
    private static final long SECOND_HANDSHAKE_MILLIS = 500;

    private static Vertx vertx;
    private static WebSocketClient client;

    @TestHTTPResource("/race/echo")
    URI echoUri;

    DevServicesContext devServices;

    private final List<WebSocket> socketsToClose = new ArrayList<>();

    @BeforeAll
    static void startClient() {
        vertx = Vertx.vertx();
        client = vertx.createWebSocketClient();
    }

    @AfterAll
    static void stopClient() throws Exception {
        await(vertx.close());
    }

    // Waits until the connections are gone, so later tests find room.
    @AfterEach
    void closeSockets() throws Exception {
        for (WebSocket socket : socketsToClose) {
            CompletableFuture<Void> closed = new CompletableFuture<>();
            socket.closeHandler(ignored -> closed.complete(null));
            socket.close();
            closed.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
        socketsToClose.clear();
    }

    @Test
    void shouldEchoText_whenSendText_givenValidSessionAndAllowedOrigin() throws Exception {
        // given
        WebSocket socket = await(client.connect(options(ALLOWED_ORIGIN, newGuest())));
        socketsToClose.add(socket);
        BlockingQueue<String> received = new LinkedBlockingQueue<>();
        socket.textMessageHandler(received::add);

        // when
        socket.writeTextMessage("hello");
        socket.writeTextMessage(LARGEST_MESSAGE);

        // then
        assertThat(received.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS), equalTo("hello"));
        assertThat(received.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS), equalTo(LARGEST_MESSAGE));
    }

    static Stream<Arguments> compressionOffers() {
        return Stream.of(
                Arguments.of(Named.of("per message", new WebSocketClientOptions().setTryUsePerMessageCompression(true))),
                Arguments.of(Named.of("per frame", new WebSocketClientOptions().setTryUsePerFrameCompression(true))));
    }

    // Browsers offer permessage-deflate, which the server declines like the
    // older per-frame extension. The client drops the handshake headers once
    // the connect future completes, so they are read in its callback.
    @ParameterizedTest
    @MethodSource("compressionOffers")
    void shouldEchoText_whenSendText_givenClientOffersCompression(WebSocketClientOptions offer) throws Exception {
        // given
        WebSocketClient compressingClient = vertx.createWebSocketClient(offer);
        CompletableFuture<String> extensions = new CompletableFuture<>();
        WebSocket socket = await(compressingClient.connect(options(ALLOWED_ORIGIN, newGuest()))
                .onSuccess(connected -> extensions.complete(connected.headers().get("Sec-WebSocket-Extensions"))));
        socketsToClose.add(socket);
        BlockingQueue<String> received = new LinkedBlockingQueue<>();
        socket.textMessageHandler(received::add);

        // when
        socket.writeTextMessage("hello");

        // then
        assertThat(extensions.get(TIMEOUT_SECONDS, TimeUnit.SECONDS), nullValue());
        assertThat(received.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS), equalTo("hello"));
    }

    @Test
    void shouldEchoEveryMessage_whenSendText_givenBurstOfMessages() throws Exception {
        // given
        WebSocket socket = await(client.connect(options(ALLOWED_ORIGIN, newGuest())));
        socketsToClose.add(socket);
        CountDownLatch echoes = new CountDownLatch(BURST_MESSAGES);
        socket.textMessageHandler(ignored -> echoes.countDown());

        // when
        for (int i = 0; i < BURST_MESSAGES; i++) {
            socket.writeTextMessage(LARGEST_MESSAGE);
        }

        // then
        assertThat(echoes.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), equalTo(true));
        assertThat(socket.isClosed(), equalTo(false));
    }

    // The server reads one frame at a time, so a burst waits in TCP and not
    // in memory. It answers in order, and drops the socket as not reading if
    // the replies come faster than the network takes them.
    @Test
    void shouldEchoInOrderOrDrop_whenSendText_givenBurstOfEmptyFrames() throws Exception {
        // given
        String token = newGuest();
        try (Socket socket = handshakeByHand(token)) {
            ByteArrayOutputStream burst = new ByteArrayOutputStream();
            ByteArrayOutputStream expected = new ByteArrayOutputStream();
            for (int i = 0; i < BURST_FRAMES; i++) {
                burst.write(maskedTextFrame(""));
                expected.write(EMPTY_ECHO);
            }
            burst.write(maskedTextFrame("hello"));
            expected.write(echoFrame("hello"));

            // when
            socket.getOutputStream().write(burst.toByteArray());

            // then
            assertThat(echoOnNewSocket(token, "hello"), equalTo("hello"));
            Received received = readUntilEnd(socket, expected.size());
            assertThat(received.bytes(), equalTo(Arrays.copyOf(expected.toByteArray(), received.bytes().length)));
            assertThat(received.wasEnded() || expected.size() == received.bytes().length, equalTo(true));
        }
    }

    @Test
    void shouldDropConnection_whenPing_givenBurstOfPings() throws Exception {
        // given
        String token = newGuest();
        try (Socket socket = handshakeByHand(token)) {
            ByteArrayOutputStream burst = new ByteArrayOutputStream();
            for (int i = 0; i < BURST_FRAMES; i++) {
                burst.write(PING_FRAME);
            }

            // when
            boolean wasDropped = wasDroppedAfterWriting(socket, burst.toByteArray());

            // then
            assertThat(wasDropped, equalTo(true));
            assertThat(echoOnNewSocket(token, "hello"), equalTo("hello"));
        }
    }

    // The 1008 would wait behind the replies the client does not read, so
    // the server drops the connection too; the close code is not certain.
    @Test
    void shouldEndSocket_whenSendText_givenClientStopsReading() throws Exception {
        // given
        WebSocket socket = await(client.connect(options(ALLOWED_ORIGIN, newGuest())));
        CompletableFuture<Void> closed = new CompletableFuture<>();
        socket.closeHandler(ignored -> closed.complete(null));

        // when
        floodWithoutReading(socket);

        // then
        closed.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @Test
    void shouldCloseWithPolicyViolation_whenSendText_givenMessageOverLimit() throws Exception {
        // given
        WebSocket socket = await(client.connect(options(ALLOWED_ORIGIN, newGuest())));
        CompletableFuture<Short> closeCode = new CompletableFuture<>();
        socket.closeHandler(ignored -> closeCode.complete(socket.closeStatusCode()));

        // when
        socket.writeTextMessage(LARGEST_MESSAGE + "\u0416");

        // then
        assertThat(closeCode.get(TIMEOUT_SECONDS, TimeUnit.SECONDS), equalTo(POLICY_VIOLATION));
    }

    // Over the frame size limit the server drops the connection without a
    // close code; a 1008 would mean the limit is gone and the endpoint
    // refused the message instead.
    @Test
    void shouldDropConnection_whenSendText_givenFrameOverSizeLimit() throws Exception {
        // given
        WebSocket socket = await(client.connect(options(ALLOWED_ORIGIN, newGuest())));
        CompletableFuture<Short> closeCode = new CompletableFuture<>();
        socket.closeHandler(ignored -> closeCode.complete(socket.closeStatusCode()));

        // when
        socket.writeTextMessage("x".repeat(OVERSIZED_FRAME_BYTES));

        // then
        assertThat(closeCode.get(TIMEOUT_SECONDS, TimeUnit.SECONDS), nullValue());
    }

    // Each frame is fetched on its own, so a message in fragments must not
    // stall the socket.
    @Test
    void shouldEchoText_whenSendText_givenFragmentedMessage() throws Exception {
        // given
        WebSocket socket = await(client.connect(options(ALLOWED_ORIGIN, newGuest())));
        socketsToClose.add(socket);
        BlockingQueue<String> received = new LinkedBlockingQueue<>();
        socket.textMessageHandler(received::add);

        // when
        socket.writeFrame(WebSocketFrame.textFrame("hel", false));
        socket.writeFrame(WebSocketFrame.continuationFrame(Buffer.buffer("lo"), true));

        // then
        assertThat(received.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS), equalTo("hello"));
    }

    // Vert.x reports a message over its limit without closing the socket;
    // the server drops it.
    @Test
    void shouldDropConnection_whenSendText_givenFragmentedMessageOverSizeLimit() throws Exception {
        // given
        WebSocket socket = await(client.connect(options(ALLOWED_ORIGIN, newGuest())));
        CompletableFuture<Short> closeCode = new CompletableFuture<>();
        socket.closeHandler(ignored -> closeCode.complete(socket.closeStatusCode()));

        // when
        socket.writeFrame(WebSocketFrame.textFrame("x".repeat(FRAGMENT_BYTES), false));
        socket.writeFrame(WebSocketFrame.continuationFrame(Buffer.buffer("x".repeat(FRAGMENT_BYTES)), true));

        // then
        assertThat(closeCode.get(TIMEOUT_SECONDS, TimeUnit.SECONDS), nullValue());
    }

    // All at once, so the handshakes overlap. Sockets that other tests left
    // open count too, so fewer than the cap may be accepted.
    @Test
    void shouldRefuseHandshake_whenConnect_givenSocketCapReached() throws Exception {
        // given
        String token = newGuest();
        List<Future<WebSocket>> connects = new ArrayList<>();
        List<Integer> refusalStatuses = new ArrayList<>();

        // when
        for (int i = 0; i < CAP_ATTEMPTS; i++) {
            connects.add(client.connect(options(ALLOWED_ORIGIN, token)));
        }
        for (Future<WebSocket> connect : connects) {
            try {
                socketsToClose.add(await(connect));
            } catch (ExecutionException e) {
                refusalStatuses.add(refusalStatus(e));
            }
        }

        // then
        assertThat(socketsToClose.size(), lessThanOrEqualTo(Rules.SOCKETS_OPEN_MAX));
        assertThat(refusalStatuses, everyItem(equalTo(WebSocketHandshakeCheck.TOO_MANY_SOCKETS)));
    }

    // Vert.x answers every ping with a pong, even after the server's close,
    // so a client that does not read could pile up pongs without end.
    @Test
    void shouldDropConnection_whenPing_givenClientStopsReading() throws Exception {
        // given
        String token = newGuest();
        try (Socket socket = handshakeByHand(token)) {

            // when
            boolean wasDropped = wasDroppedWhilePinging(socket.getOutputStream());

            // then
            assertThat(wasDropped, equalTo(true));
            assertThat(echoOnNewSocket(token, "hello"), equalTo("hello"));
        }
    }

    // After its 1008 the server still answers pings. Until the closing
    // timeout, the drop is what stops pongs piling up for a non-reader.
    @Test
    void shouldDropConnection_whenPing_givenServerClosedForLongMessage() throws Exception {
        // given
        String token = newGuest();
        try (Socket socket = handshakeByHand(token)) {
            OutputStream output = socket.getOutputStream();
            output.write(tooLongTextFrame());
            Thread.sleep(SERVER_CLOSE_MILLIS);

            // when
            boolean wasDropped = wasDroppedWhilePinging(output);

            // then
            assertThat(wasDropped, equalTo(true));
            assertThat(echoOnNewSocket(token, "hello"), equalTo("hello"));
        }
    }

    // A client that leaves during its session lookup must not stay counted,
    // or 20 such handshakes would refuse every later socket.
    @Test
    void shouldAcceptSocket_whenConnect_givenHandshakesAbandonedDuringLookup() throws Exception {
        // given
        String token = newGuest();
        for (int i = 0; i < ABANDONED_HANDSHAKES; i++) {
            sendUpgradeRequest(echoUri, token).close();
            // One at a time, so each one finds room and starts its lookup.
            Thread.sleep(PAUSE_MILLIS);
        }

        // when
        String echoed = echoOnNewSocket(token, "hello");

        // then
        assertThat(echoed, equalTo("hello"));
    }

    static Stream<Arguments> socketEndings() {
        return Stream.of(
                Arguments.of(Named.of("client close", (SocketEnding) WebSocket::close)),
                Arguments.of(Named.of("message over limit",
                        (SocketEnding) socket -> socket.writeTextMessage(LARGEST_MESSAGE + "\u0416"))),
                Arguments.of(Named.of("client stops reading", (SocketEnding) RaceEchoSocketTest::floodWithoutReading)),
                Arguments.of(Named.of("frame over size limit",
                        (SocketEnding) socket -> socket.writeTextMessage("x".repeat(OVERSIZED_FRAME_BYTES)))),
                Arguments.of(Named.of("ping", (SocketEnding) socket -> socket.writePing(Buffer.buffer()))));
    }

    // Each way of ending runs as often as the cap allows sockets, so a way
    // that left its socket counted would get 503 by the last connect.
    @ParameterizedTest
    @MethodSource("socketEndings")
    void shouldAcceptSocket_whenConnect_givenMoreSocketsEndedThanCap(SocketEnding ending) throws Exception {
        // given
        String token = newGuest();
        for (int i = 0; i < Rules.SOCKETS_OPEN_MAX; i++) {
            openAndEnd(token, ending);
        }

        // when
        String echoed = echoOnNewSocket(token, "hello");

        // then
        assertThat(echoed, equalTo("hello"));
    }

    // A refused handshake gives back the slot it took, or 20 refusals would
    // refuse every later socket.
    @Test
    void shouldAcceptSocket_whenConnect_givenMoreRefusedHandshakesThanCap() throws Exception {
        // given
        String token = newGuest();
        for (int i = 0; i < CAP_ATTEMPTS; i++) {
            refusal(options(FOREIGN_ORIGIN, token));
            refusal(options(ALLOWED_ORIGIN, null));
        }

        // when
        String echoed = echoOnNewSocket(token, "hello");

        // then
        assertThat(echoed, equalTo("hello"));
    }

    static Stream<Arguments> handshakeBodies() {
        String chunkedBody = Integer.toHexString(CHUNK_BYTES) + "\r\n" + "x".repeat(CHUNK_BYTES) + "\r\n0\r\n\r\n";
        return Stream.of(
                Arguments.of(Named.of("chunked, over 8 KB", "Transfer-Encoding: chunked\r\n"), chunkedBody),
                Arguments.of(Named.of("with a length", "Content-Length: 5\r\n"), "hello"));
    }

    // Vert.x reads a handshake's body before the upgrade, and over 8192
    // bytes it answers 413 without ever ending the upgrade, so a slot taken
    // for it would never come back.
    @ParameterizedTest
    @MethodSource("handshakeBodies")
    void shouldAcceptSocket_whenConnect_givenMoreHandshakesWithBodyThanCap(String bodyHeader, String body)
            throws Exception {
        // given
        String token = newGuest();
        List<String> statusLines = new ArrayList<>();
        for (int i = 0; i < CAP_ATTEMPTS; i++) {
            try (Socket socket = sendRequest(echoUri, token, KEY + VERSION_13 + bodyHeader, body)) {
                statusLines.add(statusLine(readHead(socket)));
            }
        }

        // when
        String echoed = echoOnNewSocket(token, "hello");

        // then
        assertThat(statusLines, everyItem(equalTo(BAD_REQUEST)));
        assertThat(echoed, equalTo("hello"));
    }

    // Vert.x routes a handshake sent right behind another once that one has
    // upgraded. A slot it left counted shows only with the whole cap open:
    // one more socket would still find room.
    @Test
    void shouldOpenSocketsUpToCap_whenConnect_givenTwoHandshakesInOneWrite() throws Exception {
        // given
        String token = newGuest();
        List<String> statusLines = new ArrayList<>();
        for (int i = 0; i < CAP_ATTEMPTS; i++) {
            try (Socket socket = sendTwoUpgradeRequests(echoUri, token)) {
                statusLines.add(statusLine(readHead(socket)));
                Thread.sleep(SECOND_HANDSHAKE_MILLIS);
            }
        }

        // when
        for (int i = 0; i < Rules.SOCKETS_OPEN_MAX; i++) {
            socketsToClose.add(connectWhenRoom(options(ALLOWED_ORIGIN, token)));
        }

        // then
        assertThat(statusLines, everyItem(equalTo(SWITCHING_PROTOCOLS)));
        assertThat(socketsToClose.size(), equalTo(Rules.SOCKETS_OPEN_MAX));
    }

    static Stream<Arguments> otherVersions() {
        return Stream.of(
                Arguments.of(Named.of("draft 75, no version or key", "")),
                Arguments.of(Named.of("version 8", KEY + "Sec-WebSocket-Version: 8\r\n")));
    }

    @ParameterizedTest
    @MethodSource("otherVersions")
    void shouldRefuseHandshake_whenConnect_givenOtherWebSocketVersion(String versionHeaders) throws Exception {
        // given
        String token = newGuest();

        // when
        String head;
        try (Socket socket = sendRequest(echoUri, token, versionHeaders, "")) {
            head = readHead(socket);
        }

        // then
        assertThat(statusLine(head), equalTo(UPGRADE_REQUIRED));
        assertThat(head, containsString("\r\n" + VERSION_13));
    }

    @Test
    void shouldRefuseHandshake_whenConnect_givenNoCookie() {
        // when
        UpgradeRejectedException refusal = refusal(options(ALLOWED_ORIGIN, null));

        // then
        assertThat(refusal.getStatus(), equalTo(WebSocketHandshakeCheck.NO_SESSION));
    }

    @Test
    void shouldRefuseHandshake_whenConnect_givenUnknownCookie() {
        // when
        UpgradeRejectedException refusal = refusal(options(ALLOWED_ORIGIN, "bm90LWEtcmVhbC1zZXNzaW9uLXRva2VuLWF0LWFsbA"));

        // then
        assertThat(refusal.getStatus(), equalTo(WebSocketHandshakeCheck.NO_SESSION));
    }

    @Test
    void shouldRefuseHandshake_whenConnect_givenExpiredCookie() throws Exception {
        // given
        String token = newGuest();
        TestSql.execute(database(), "update session set expires_at = now() - interval '1 hour' where token_hash = ?",
                Sessions.hash(token));

        // when
        UpgradeRejectedException refusal = refusal(options(ALLOWED_ORIGIN, token));

        // then
        assertThat(refusal.getStatus(), equalTo(WebSocketHandshakeCheck.NO_SESSION));
    }

    @Test
    void shouldRefuseHandshake_whenConnect_givenForeignOrigin() {
        // given
        String token = newGuest();

        // when
        UpgradeRejectedException refusal = refusal(options(FOREIGN_ORIGIN, token));

        // then
        assertThat(refusal.getStatus(), equalTo(WebSocketHandshakeCheck.FOREIGN_ORIGIN));
    }

    // The Origin is checked before the session, so a foreign page never
    // causes a database read.
    @Test
    void shouldRefuseHandshake_whenConnect_givenForeignOriginAndNoCookie() {
        // when
        UpgradeRejectedException refusal = refusal(options(FOREIGN_ORIGIN, null));

        // then
        assertThat(refusal.getStatus(), equalTo(WebSocketHandshakeCheck.FOREIGN_ORIGIN));
    }

    @Test
    void shouldAnswerNotFound_whenGet_givenNoUpgrade() {
        // when, then
        given().header("Origin", ALLOWED_ORIGIN)
                .when().get(echoUri.getPath())
                .then().statusCode(404);
    }

    @Test
    void shouldRefuseHandshake_whenConnect_givenNoOrigin() {
        // given
        String token = newGuest();

        // when
        UpgradeRejectedException refusal = refusal(options(null, token));

        // then
        assertThat(refusal.getStatus(), equalTo(WebSocketHandshakeCheck.FOREIGN_ORIGIN));
    }

    static Stream<Arguments> refusedHandshakes() {
        return Stream.of(
                Arguments.of(Named.of("no session", ALLOWED_ORIGIN), false),
                Arguments.of(Named.of("foreign origin", FOREIGN_ORIGIN), true));
    }

    @ParameterizedTest
    @MethodSource("refusedHandshakes")
    void shouldSendSecurityHeaders_whenConnect_givenRefusedHandshake(String origin, boolean hasSession) {
        // given
        String token = hasSession ? newGuest() : null;

        // when
        UpgradeRejectedException refusal = refusal(options(origin, token));

        // then
        MultiMap headers = refusal.getHeaders();
        assertThat(headers.get("Cache-Control"), equalTo("no-store"));
        assertThat(headers.get("X-Content-Type-Options"), equalTo("nosniff"));
        assertThat(headers.get("Content-Security-Policy"), equalTo("default-src 'none'; frame-ancestors 'none'"));
    }

    private static String newGuest() {
        return TestPlayers.createGuest("Racer", Rules.KIND_HUMAN);
    }

    // The client adds its own Origin when none is set, and with the Origin
    // header turned off it drops one set by hand too.
    private WebSocketConnectOptions options(String origin, String token) {
        WebSocketConnectOptions options = new WebSocketConnectOptions()
                .setHost(echoUri.getHost())
                .setPort(echoUri.getPort())
                .setURI(echoUri.getPath())
                .setAllowOriginHeader(null != origin);
        if (null != origin) {
            options.addHeader("Origin", origin);
        }
        if (null != token) {
            options.addHeader("Cookie", TestPlayers.COOKIE + "=" + token);
        }
        return options;
    }

    // The server frees a slot a moment after the client sees the close,
    // and handshakes in progress count too, so room can take a moment to show.
    private WebSocket connectWhenRoom(WebSocketConnectOptions options) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        WebSocket socket = null;
        while (null == socket) {
            try {
                socket = await(client.connect(options));
            } catch (ExecutionException e) {
                boolean shouldRetry = WebSocketHandshakeCheck.TOO_MANY_SOCKETS == refusalStatus(e)
                        && System.nanoTime() < deadline;
                if (!shouldRetry) {
                    throw e;
                }
                Thread.sleep(PAUSE_MILLIS);
            }
        }
        return socket;
    }

    private String echoOnNewSocket(String token, String text) throws Exception {
        WebSocket socket = connectWhenRoom(options(ALLOWED_ORIGIN, token));
        socketsToClose.add(socket);
        BlockingQueue<String> received = new LinkedBlockingQueue<>();
        socket.textMessageHandler(received::add);
        socket.writeTextMessage(text);
        return received.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private void openAndEnd(String token, SocketEnding ending) throws Exception {
        WebSocket socket = connectWhenRoom(options(ALLOWED_ORIGIN, token));
        CompletableFuture<Void> closed = new CompletableFuture<>();
        socket.closeHandler(ignored -> closed.complete(null));
        ending.end(socket);
        closed.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private static void floodWithoutReading(WebSocket socket) {
        socket.pause();
        for (int i = 0; i < FLOOD_MESSAGES; i++) {
            socket.writeTextMessage(LARGEST_MESSAGE);
        }
    }

    // By hand, so the client reads nothing after the handshake answer.
    private Socket handshakeByHand(String token) throws IOException {
        Socket socket = sendUpgradeRequest(echoUri, token);
        assertThat(statusLine(readHead(socket)), equalTo(SWITCHING_PROTOCOLS));
        return socket;
    }

    static Socket sendUpgradeRequest(URI uri, String token) throws IOException {
        return sendRequest(uri, token, KEY + VERSION_13, "");
    }

    static Socket sendTwoUpgradeRequests(URI uri, String token) throws IOException {
        String request = upgradeRequest(uri, token, KEY + VERSION_13, "");
        return writeOnNewConnection(uri, request + request);
    }

    private static Socket sendRequest(URI uri, String token, String handshakeHeaders, String body)
            throws IOException {
        return writeOnNewConnection(uri, upgradeRequest(uri, token, handshakeHeaders, body));
    }

    // Each of the handshake headers ends in CRLF; the body follows the blank line.
    private static String upgradeRequest(URI uri, String token, String handshakeHeaders, String body) {
        return "GET " + uri.getPath() + " HTTP/1.1\r\n"
                + "Host: " + uri.getHost() + ":" + uri.getPort() + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + handshakeHeaders
                + "Origin: " + ALLOWED_ORIGIN + "\r\n"
                + "Cookie: " + TestPlayers.COOKIE + "=" + token + "\r\n"
                + "\r\n"
                + body;
    }

    private static Socket writeOnNewConnection(URI uri, String requests) throws IOException {
        Socket socket = new Socket(uri.getHost(), uri.getPort());
        socket.setSoTimeout((int) TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
        socket.getOutputStream().write(requests.getBytes(StandardCharsets.US_ASCII));
        return socket;
    }

    // Byte by byte, so the frames after the answer stay in the stream. A
    // refusal may leave the connection open, so this never waits for its end.
    static String readHead(Socket socket) throws IOException {
        InputStream input = socket.getInputStream();
        StringBuilder head = new StringBuilder();
        int next = 0;
        while (-1 != next && !head.toString().endsWith(END_OF_HEADERS)) {
            next = input.read();
            head.append((char) next);
        }
        return head.toString();
    }

    private static String statusLine(String head) {
        return head.split("\r\n")[0];
    }

    // A write fails once the server has dropped the connection. The pauses
    // keep the flood small if the server goes on answering.
    private static boolean wasDroppedWhilePinging(OutputStream output) throws InterruptedException {
        byte[] pings = new byte[PINGS_PER_WRITE * PING_FRAME.length];
        for (int i = 0; i < PINGS_PER_WRITE; i++) {
            System.arraycopy(PING_FRAME, 0, pings, i * PING_FRAME.length, PING_FRAME.length);
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(DROP_SECONDS);
        boolean wasDropped = false;
        while (!wasDropped && System.nanoTime() < deadline) {
            try {
                output.write(pings);
            } catch (IOException e) {
                wasDropped = true;
            }
            Thread.sleep(PAUSE_MILLIS);
        }
        return wasDropped;
    }

    // One byte over the message limit, masked with a zero key. A length of
    // 126 in the second byte means the real length follows in two bytes.
    private static byte[] tooLongTextFrame() {
        int length = Rules.SOCKET_MESSAGE_BYTES_MAX + 1;
        byte[] frame = new byte[8 + length];
        frame[0] = (byte) 0x81;
        frame[1] = (byte) (0x80 | 126);
        frame[2] = (byte) (length >> 8);
        frame[3] = (byte) length;
        Arrays.fill(frame, 8, frame.length, (byte) 'x');
        return frame;
    }

    // Under 126 bytes the length fits in the second byte.
    private static byte[] maskedTextFrame(String text) {
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        byte[] frame = new byte[6 + payload.length];
        frame[0] = (byte) 0x81;
        frame[1] = (byte) (0x80 | payload.length);
        System.arraycopy(payload, 0, frame, 6, payload.length);
        return frame;
    }

    private static byte[] echoFrame(String text) {
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        byte[] frame = new byte[2 + payload.length];
        frame[0] = (byte) 0x81;
        frame[1] = (byte) payload.length;
        System.arraycopy(payload, 0, frame, 2, payload.length);
        return frame;
    }

    // A reset loses what was read before it, which is fine for a dropped
    // socket. A timeout is neither an answer nor a drop, so it fails.
    private static Received readUntilEnd(Socket socket, int length) throws IOException {
        Received received;
        try {
            byte[] bytes = socket.getInputStream().readNBytes(length);
            received = new Received(bytes, bytes.length < length);
        } catch (SocketException e) {
            received = new Received(new byte[0], true);
        }
        return received;
    }

    // Reads to the end, past the pongs for the pings read before the drop.
    private static boolean wasDroppedAfterWriting(Socket socket, byte[] frames) {
        boolean wasDropped;
        try {
            socket.getOutputStream().write(frames);
            socket.getInputStream().transferTo(OutputStream.nullOutputStream());
            wasDropped = true;
        } catch (SocketTimeoutException e) {
            wasDropped = false;
        } catch (IOException e) {
            wasDropped = true;
        }
        return wasDropped;
    }

    private static int refusalStatus(ExecutionException thrown) {
        return assertInstanceOf(UpgradeRejectedException.class, thrown.getCause()).getStatus();
    }

    private static UpgradeRejectedException refusal(WebSocketConnectOptions options) {
        ExecutionException thrown = assertThrows(ExecutionException.class, () -> await(client.connect(options)));
        return assertInstanceOf(UpgradeRejectedException.class, thrown.getCause());
    }

    private PGSimpleDataSource database() {
        Map<String, String> properties = devServices.devServicesProperties();
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(properties.get("quarkus.datasource.jdbc.url"));
        dataSource.setUser(properties.get("quarkus.datasource.username"));
        dataSource.setPassword(properties.get("quarkus.datasource.password"));
        return dataSource;
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    interface SocketEnding {
        void end(WebSocket socket) throws Exception;
    }

    record Received(byte[] bytes, boolean wasEnded) {
    }
}
