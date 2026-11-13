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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.postgresql.ds.PGSimpleDataSource;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
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
import java.util.Collections;
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
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The guards of WebSocketHandshakeCheck, run against every socket route. A
 * real WebSocket client and plain JDBC, no injection, so the native
 * integration test can run it against the binary.
 */
@QuarkusTest
class SocketGuardsTest {

    private static final String ALLOWED_ORIGIN = "https://rassimok.dev";
    private static final long TIMEOUT_SECONDS = 10;
    // 256 Cyrillic letters of two bytes each: exactly the 512-byte limit.
    private static final String LARGEST_MESSAGE = "\u0416".repeat(256);
    private static final short POLICY_VIOLATION = SocketSender.POLICY_VIOLATION;
    private static final String FOREIGN_ORIGIN = "https://example.com";
    private static final int BURST_MESSAGES = 200;
    // 60 KB of empty text frames per write. Each one is answered with the
    // route's longer bad reply, so the replies soon fill the network buffers
    // between client and server (8 MB on macOS) while the client reads nothing.
    private static final int FLOOD_FRAMES_PER_WRITE = 10_000;
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
    private static final int CLOSE_OPCODE = 0x88;
    // Below Vert.x's closing timeout of 10 s, which ends a socket by itself
    // once the server's close frame is written.
    private static final long DROP_SECONDS = 5;
    // Time for the server to send its 1008 before the first ping arrives.
    private static final long SERVER_CLOSE_MILLIS = 500;
    // The grace drop is 1 s; the rest is time to connect 20 sockets.
    private static final long GRACE_DROP_MILLIS_MAX = 3000;
    private static final String SWITCHING_PROTOCOLS = "HTTP/1.1 101 Switching Protocols";
    private static final String BAD_REQUEST = "HTTP/1.1 400 Bad Request";
    private static final String FORBIDDEN = "HTTP/1.1 403 Forbidden";
    private static final String UNAUTHORIZED = "HTTP/1.1 401 Unauthorized";
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

    @TestHTTPResource("/")
    URI serverUri;

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

    // Every socket path registered through WebSocketHandshakeCheck.
    static Stream<Arguments> routes() {
        return Stream.of(Arguments.of(Named.of("race", RaceRoute.SHARED)));
    }

    // Each route with each case.
    private static Stream<Arguments> withRoutes(Stream<Arguments> cases) {
        List<Arguments> caseList = cases.toList();
        return routes().flatMap(route -> caseList.stream().map(oneCase -> {
            List<Object> values = new ArrayList<>(List.of(route.get()));
            Collections.addAll(values, oneCase.get());
            return Arguments.of(values.toArray());
        }));
    }

    @ParameterizedTest
    @MethodSource("routes")
    void shouldReply_whenSendText_givenRequestAndLargestMessage(SocketRoute route) throws Exception {
        // given
        BlockingQueue<String> received = new LinkedBlockingQueue<>();
        WebSocket socket = connectReading(route.newSeat(), received);

        // when
        socket.writeTextMessage(route.request());
        socket.writeTextMessage(LARGEST_MESSAGE);

        // then
        assertThat(nextReply(route, received), equalTo(route.reply()));
        assertThat(nextReply(route, received), equalTo(route.badReply()));
    }

    static Stream<Arguments> compressionOffers() {
        return withRoutes(Stream.of(
                Arguments.of(Named.of("per message", new WebSocketClientOptions().setTryUsePerMessageCompression(true))),
                Arguments.of(Named.of("per frame", new WebSocketClientOptions().setTryUsePerFrameCompression(true)))));
    }

    // Browsers offer permessage-deflate, which the server declines like the
    // older per-frame extension. The client drops the handshake headers once
    // the connect future completes, so they are read in its callback.
    @ParameterizedTest
    @MethodSource("compressionOffers")
    void shouldReply_whenSendText_givenClientOffersCompression(SocketRoute route, WebSocketClientOptions offer)
            throws Exception {
        // given
        WebSocketClient compressingClient = vertx.createWebSocketClient(offer);
        CompletableFuture<String> extensions = new CompletableFuture<>();
        WebSocket socket = await(compressingClient.connect(options(ALLOWED_ORIGIN, route.newSeat()))
                .onSuccess(connected -> extensions.complete(connected.headers().get("Sec-WebSocket-Extensions"))));
        socketsToClose.add(socket);
        BlockingQueue<String> received = new LinkedBlockingQueue<>();
        socket.textMessageHandler(received::add);

        // when
        socket.writeTextMessage(route.request());

        // then
        assertThat(extensions.get(TIMEOUT_SECONDS, TimeUnit.SECONDS), nullValue());
        assertThat(nextReply(route, received), equalTo(route.reply()));
    }

    @ParameterizedTest
    @MethodSource("routes")
    void shouldReplyToEveryMessage_whenSendText_givenBurstOfMessages(SocketRoute route) throws Exception {
        // given
        WebSocket socket = await(client.connect(options(ALLOWED_ORIGIN, route.newSeat())));
        socketsToClose.add(socket);
        CountDownLatch replies = new CountDownLatch(BURST_MESSAGES);
        socket.textMessageHandler(message -> {
            if (!route.isUnprompted(message)) {
                replies.countDown();
            }
        });

        // when
        for (int i = 0; i < BURST_MESSAGES; i++) {
            socket.writeTextMessage(LARGEST_MESSAGE);
        }

        // then
        assertThat(replies.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), equalTo(true));
        assertThat(socket.isClosed(), equalTo(false));
    }

    // The server reads one frame at a time, so a burst waits in TCP and not
    // in memory. It answers in order, and drops the socket as not reading if
    // the replies come faster than the network takes them.
    @ParameterizedTest
    @MethodSource("routes")
    void shouldReplyInOrderOrDrop_whenSendText_givenBurstOfEmptyFrames(SocketRoute route) throws Exception {
        // given
        ByteArrayOutputStream burst = new ByteArrayOutputStream();
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < BURST_FRAMES; i++) {
            burst.write(maskedTextFrame(""));
            expected.add(route.badReply());
        }
        burst.write(maskedTextFrame(route.request()));
        expected.add(route.reply());

        // when
        String repliedMeanwhile;
        Replies replies;
        try (Socket socket = handshakeByHand(route.newSeat())) {
            socket.getOutputStream().write(burst.toByteArray());
            repliedMeanwhile = replyOnNewSocket(route);
            replies = readReplies(socket, route, expected.size());
        }
        int openSockets = openSocketsUpToCap(route);

        // then
        assertThat(repliedMeanwhile, equalTo(route.reply()));
        assertThat(replies.texts(), equalTo(expected.subList(0, replies.texts().size())));
        assertThat(replies.wasEnded() || expected.size() == replies.texts().size(), equalTo(true));
        assertThat(openSockets, equalTo(Rules.SOCKETS_OPEN_MAX));
    }

    @ParameterizedTest
    @MethodSource("routes")
    void shouldDropConnection_whenPing_givenBurstOfPings(SocketRoute route) throws Exception {
        // given
        ByteArrayOutputStream burst = new ByteArrayOutputStream();
        for (int i = 0; i < BURST_FRAMES; i++) {
            burst.write(PING_FRAME);
        }

        // when
        boolean wasDropped;
        try (Socket socket = handshakeByHand(route.newSeat())) {
            wasDropped = wasDroppedAfterWriting(socket, burst.toByteArray());
        }
        int openSockets = openSocketsUpToCap(route);

        // then
        assertThat(wasDropped, equalTo(true));
        assertThat(openSockets, equalTo(Rules.SOCKETS_OPEN_MAX));
    }

    // The 1008 would wait behind the replies the client does not read, so
    // the server drops the connection too; the close code is not certain.
    @ParameterizedTest
    @MethodSource("routes")
    void shouldEndSocket_whenSendText_givenClientStopsReading(SocketRoute route) throws Exception {
        // when
        boolean wasDropped = wasDroppedWhileFlooding(route.newSeat());
        int openSockets = openSocketsUpToCap(route);

        // then
        assertThat(wasDropped, equalTo(true));
        assertThat(openSockets, equalTo(Rules.SOCKETS_OPEN_MAX));
    }

    @ParameterizedTest
    @MethodSource("routes")
    void shouldCloseWithPolicyViolation_whenSendText_givenMessageOverLimit(SocketRoute route) throws Exception {
        // given
        WebSocket socket = await(client.connect(options(ALLOWED_ORIGIN, route.newSeat())));
        CompletableFuture<Short> closeCode = new CompletableFuture<>();
        socket.closeHandler(ignored -> closeCode.complete(socket.closeStatusCode()));

        // when
        socket.writeTextMessage(LARGEST_MESSAGE + "\u0416");

        // then
        assertThat(closeCode.get(TIMEOUT_SECONDS, TimeUnit.SECONDS), equalTo(POLICY_VIOLATION));
    }

    // Over the frame size limit the server drops the connection without a
    // close code; a 1008 would mean the limit is gone and the check refused
    // the message instead.
    @ParameterizedTest
    @MethodSource("routes")
    void shouldDropConnection_whenSendText_givenFrameOverSizeLimit(SocketRoute route) throws Exception {
        // given
        WebSocket socket = await(client.connect(options(ALLOWED_ORIGIN, route.newSeat())));
        CompletableFuture<Short> closeCode = new CompletableFuture<>();
        socket.closeHandler(ignored -> closeCode.complete(socket.closeStatusCode()));

        // when
        socket.writeTextMessage("x".repeat(OVERSIZED_FRAME_BYTES));

        // then
        assertThat(closeCode.get(TIMEOUT_SECONDS, TimeUnit.SECONDS), nullValue());
    }

    // Each frame is fetched on its own, so a message in fragments must not
    // stall the socket.
    @ParameterizedTest
    @MethodSource("routes")
    void shouldReply_whenSendText_givenFragmentedMessage(SocketRoute route) throws Exception {
        // given
        BlockingQueue<String> received = new LinkedBlockingQueue<>();
        WebSocket socket = connectReading(route.newSeat(), received);
        String request = route.request();
        int half = request.length() / 2;

        // when
        socket.writeFrame(WebSocketFrame.textFrame(request.substring(0, half), false));
        socket.writeFrame(WebSocketFrame.continuationFrame(Buffer.buffer(request.substring(half)), true));

        // then
        assertThat(nextReply(route, received), equalTo(route.reply()));
    }

    // Vert.x reports a message over its limit without closing the socket;
    // the server drops it.
    @ParameterizedTest
    @MethodSource("routes")
    void shouldDropConnection_whenSendText_givenFragmentedMessageOverSizeLimit(SocketRoute route) throws Exception {
        // given
        WebSocket socket = await(client.connect(options(ALLOWED_ORIGIN, route.newSeat())));
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
    @ParameterizedTest
    @MethodSource("routes")
    void shouldRefuseHandshake_whenConnect_givenSocketCapReached(SocketRoute route) throws Exception {
        // given
        List<SocketRoute.Seat> seats = route.newSeats(CAP_ATTEMPTS);
        List<Future<WebSocket>> connects = new ArrayList<>();
        List<Integer> refusalStatuses = new ArrayList<>();

        // when
        for (SocketRoute.Seat seat : seats) {
            connects.add(client.connect(options(ALLOWED_ORIGIN, seat)));
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
    @ParameterizedTest
    @MethodSource("routes")
    void shouldDropConnection_whenPing_givenClientStopsReading(SocketRoute route) throws Exception {
        // when
        boolean wasDropped;
        try (Socket socket = handshakeByHand(route.newSeat())) {
            wasDropped = wasDroppedWhilePinging(socket.getOutputStream());
        }
        int openSockets = openSocketsUpToCap(route);

        // then
        assertThat(wasDropped, equalTo(true));
        assertThat(openSockets, equalTo(Rules.SOCKETS_OPEN_MAX));
    }

    // After its 1008 the server still answers pings. Until the closing
    // timeout, the drop is what stops pongs piling up for a non-reader.
    @ParameterizedTest
    @MethodSource("routes")
    void shouldDropConnection_whenPing_givenServerClosedForLongMessage(SocketRoute route) throws Exception {
        // when
        boolean wasDropped;
        try (Socket socket = handshakeByHand(route.newSeat())) {
            OutputStream output = socket.getOutputStream();
            output.write(tooLongTextFrame());
            Thread.sleep(SERVER_CLOSE_MILLIS);
            wasDropped = wasDroppedWhilePinging(output);
        }
        int openSockets = openSocketsUpToCap(route);

        // then
        assertThat(wasDropped, equalTo(true));
        assertThat(openSockets, equalTo(Rules.SOCKETS_OPEN_MAX));
    }

    // A client that answers neither the 1008 nor anything else would hold its
    // slot until Vert.x's closing timeout of 10 s; the grace drop ends it
    // after about 1 s, and the whole cap opens again.
    @ParameterizedTest
    @MethodSource("routes")
    void shouldOpenSocketsUpToCap_whenConnect_givenClientsIgnoringServerClose(SocketRoute route)
            throws Exception {
        // given
        List<Socket> silentClients = new ArrayList<>();
        try {
            for (SocketRoute.Seat seat : route.newSeats(Rules.SOCKETS_OPEN_MAX)) {
                silentClients.add(handshakeByHand(seat));
            }

            // when
            long start = System.nanoTime();
            for (Socket silentClient : silentClients) {
                silentClient.getOutputStream().write(tooLongTextFrame());
            }
            int openSockets = openSocketsUpToCap(route);
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            // then
            assertThat(openSockets, equalTo(Rules.SOCKETS_OPEN_MAX));
            assertThat(elapsedMillis, lessThan(GRACE_DROP_MILLIS_MAX));
        } finally {
            for (Socket silentClient : silentClients) {
                silentClient.close();
            }
        }
    }

    // The same for a socket the server replaces: ten members each connect
    // twice, the first sockets read nothing, and the cap is full until the
    // grace drop ends them.
    @ParameterizedTest
    @MethodSource("routes")
    void shouldOpenSocketsUpToCap_whenConnect_givenReplacedClientsIgnoringServerClose(SocketRoute route)
            throws Exception {
        // given
        List<Socket> clients = new ArrayList<>();
        try {
            List<SocketRoute.Seat> seats = route.newSeats(Rules.SOCKETS_OPEN_MAX / 2);
            for (SocketRoute.Seat seat : seats) {
                clients.add(handshakeByHand(seat));
            }

            // when
            long start = System.nanoTime();
            for (SocketRoute.Seat seat : seats) {
                clients.add(handshakeByHand(seat));
            }
            for (Socket replacingClient : clients.subList(seats.size(), clients.size())) {
                replacingClient.close();
            }
            int openSockets = openSocketsUpToCap(route);
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            // then
            assertThat(openSockets, equalTo(Rules.SOCKETS_OPEN_MAX));
            assertThat(elapsedMillis, lessThan(GRACE_DROP_MILLIS_MAX));
        } finally {
            for (Socket client : clients) {
                client.close();
            }
        }
    }

    // A client that leaves during its session lookup must not stay counted,
    // or 20 such handshakes would refuse every later socket.
    @ParameterizedTest
    @MethodSource("routes")
    void shouldOpenSocketsUpToCap_whenConnect_givenHandshakesAbandonedDuringLookup(SocketRoute route)
            throws Exception {
        // given
        SocketRoute.Seat seat = route.newSeat();
        for (int i = 0; i < ABANDONED_HANDSHAKES; i++) {
            sendUpgradeRequest(serverUri, seat).close();
            // One at a time, so each one finds room and starts its lookup.
            Thread.sleep(PAUSE_MILLIS);
        }

        // when
        int openSockets = openSocketsUpToCap(route);

        // then
        assertThat(openSockets, equalTo(Rules.SOCKETS_OPEN_MAX));
    }

    enum SocketEnding {
        CLIENT_CLOSE, MESSAGE_OVER_LIMIT, CLIENT_STOPS_READING, FRAME_OVER_SIZE_LIMIT, PING
    }

    static Stream<Arguments> socketEndings() {
        return withRoutes(Arrays.stream(SocketEnding.values()).map(Arguments::of));
    }

    // Each way of ending runs as often as the cap allows sockets, so a way
    // that left its socket counted would get 503 by the last connect.
    @ParameterizedTest
    @MethodSource("socketEndings")
    void shouldOpenSocketsUpToCap_whenConnect_givenMoreSocketsEndedThanCap(SocketRoute route, SocketEnding ending)
            throws Exception {
        // given
        for (int i = 0; i < Rules.SOCKETS_OPEN_MAX; i++) {
            end(route.newSeat(), ending);
        }

        // when
        int openSockets = openSocketsUpToCap(route);

        // then
        assertThat(openSockets, equalTo(Rules.SOCKETS_OPEN_MAX));
    }

    // A refused handshake gives back the slot it took, or 20 refusals would
    // refuse every later socket.
    @ParameterizedTest
    @MethodSource("routes")
    void shouldOpenSocketsUpToCap_whenConnect_givenMoreRefusedHandshakesThanCap(SocketRoute route) throws Exception {
        // given
        SocketRoute.Seat seat = route.newSeat();
        List<SocketRoute.Refusal> ownRefusals = route.ownRefusals();
        for (int i = 0; i < CAP_ATTEMPTS; i++) {
            refusal(options(FOREIGN_ORIGIN, seat));
            refusal(options(ALLOWED_ORIGIN, new SocketRoute.Seat(null, seat.path())));
            for (SocketRoute.Refusal ownRefusal : ownRefusals) {
                refusal(options(ALLOWED_ORIGIN, ownRefusal.seat()));
            }
        }

        // when
        int openSockets = openSocketsUpToCap(route);

        // then
        assertThat(openSockets, equalTo(Rules.SOCKETS_OPEN_MAX));
    }

    @ParameterizedTest
    @MethodSource("routes")
    void shouldRefuseHandshake_whenConnect_givenOwnRefusalOfRoute(SocketRoute route) {
        // given
        List<SocketRoute.Refusal> ownRefusals = route.ownRefusals();

        // when
        List<Integer> statuses = ownRefusals.stream()
                .map(ownRefusal -> refusal(options(ALLOWED_ORIGIN, ownRefusal.seat())).getStatus())
                .toList();

        // then
        assertThat(statuses, equalTo(ownRefusals.stream()
                .map(ownRefusal -> Integer.parseInt(ownRefusal.statusLine().split(" ")[1]))
                .toList()));
    }

    // Ending a refusal routes the request queued behind it at once. The slot
    // must be back by then, or that request would count as a second
    // handshake on the connection and get no answer.
    @ParameterizedTest
    @MethodSource("routes")
    void shouldUpgradeSecondRequest_whenConnect_givenRefusedHandshakeInSameWrite(SocketRoute route) throws Exception {
        // given
        SocketRoute.Seat refusedSeat = route.newSeat();
        List<SocketRoute.Refusal> refusals = new ArrayList<>(List.of(
                new SocketRoute.Refusal("foreign origin", refusedSeat, FORBIDDEN),
                new SocketRoute.Refusal("no session", new SocketRoute.Seat("unknown", refusedSeat.path()),
                        UNAUTHORIZED)));
        refusals.addAll(route.ownRefusals());

        // when
        List<List<String>> statusLines = new ArrayList<>();
        for (SocketRoute.Refusal refused : refusals) {
            String origin = FORBIDDEN.equals(refused.statusLine()) ? FOREIGN_ORIGIN : ALLOWED_ORIGIN;
            SocketRoute.Seat acceptedSeat = route.newSeat();
            String requests = upgradeRequest(serverUri, origin, refused.seat(), KEY + VERSION_13, "")
                    + upgradeRequest(serverUri, ALLOWED_ORIGIN, acceptedSeat, KEY + VERSION_13, "");
            try (Socket socket = writeOnNewConnection(serverUri, requests)) {
                statusLines.add(List.of(statusLine(readHead(socket)), statusLine(readHead(socket))));
            }
        }
        int openSockets = openSocketsUpToCap(route);

        // then
        assertThat(statusLines, equalTo(refusals.stream()
                .map(refused -> List.of(refused.statusLine(), SWITCHING_PROTOCOLS)).toList()));
        assertThat(openSockets, equalTo(Rules.SOCKETS_OPEN_MAX));
    }

    static Stream<Arguments> handshakeBodies() {
        String chunkedBody = Integer.toHexString(CHUNK_BYTES) + "\r\n" + "x".repeat(CHUNK_BYTES) + "\r\n0\r\n\r\n";
        return withRoutes(Stream.of(
                Arguments.of(Named.of("chunked, over 8 KB", "Transfer-Encoding: chunked\r\n"), chunkedBody,
                        BAD_REQUEST),
                Arguments.of(Named.of("with a length", "Content-Length: 5\r\n"), "hello", BAD_REQUEST),
                // A front end may add it to a request without a body.
                Arguments.of(Named.of("length 0", "Content-Length: 0\r\n"), "", SWITCHING_PROTOCOLS)));
    }

    // Vert.x reads a handshake's body before the upgrade, and over 8192
    // bytes it answers 413 without ever ending the upgrade, so a slot taken
    // for it would come back only when the connection closes.
    @ParameterizedTest
    @MethodSource("handshakeBodies")
    void shouldOpenSocketsUpToCap_whenConnect_givenHandshakesWithBodyHeader(SocketRoute route, String bodyHeader,
                                                                            String body, String expectedStatusLine)
            throws Exception {
        // given
        List<String> statusLines = new ArrayList<>();
        for (int i = 0; i < CAP_ATTEMPTS; i++) {
            try (Socket socket = sendRequest(serverUri, route.newSeat(), KEY + VERSION_13 + bodyHeader, body)) {
                statusLines.add(statusLine(readHead(socket)));
            }
        }

        // when
        int openSockets = openSocketsUpToCap(route);

        // then
        assertThat(statusLines, everyItem(equalTo(expectedStatusLine)));
        assertThat(openSockets, equalTo(Rules.SOCKETS_OPEN_MAX));
    }

    // Vert.x routes a handshake sent right behind another once that one has
    // upgraded.
    @ParameterizedTest
    @MethodSource("routes")
    void shouldOpenSocketsUpToCap_whenConnect_givenTwoHandshakesInOneWrite(SocketRoute route) throws Exception {
        // given
        List<String> statusLines = new ArrayList<>();
        for (int i = 0; i < CAP_ATTEMPTS; i++) {
            try (Socket socket = sendTwoUpgradeRequests(serverUri, route.newSeat())) {
                statusLines.add(statusLine(readHead(socket)));
                Thread.sleep(SECOND_HANDSHAKE_MILLIS);
            }
        }

        // when
        int openSockets = openSocketsUpToCap(route);

        // then
        assertThat(statusLines, everyItem(equalTo(SWITCHING_PROTOCOLS)));
        assertThat(openSockets, equalTo(Rules.SOCKETS_OPEN_MAX));
    }

    static Stream<Arguments> otherVersions() {
        return withRoutes(Stream.of(
                Arguments.of(Named.of("draft 75, no version or key", "")),
                Arguments.of(Named.of("version 8", KEY + "Sec-WebSocket-Version: 8\r\n"))));
    }

    // Refused before a slot is taken; refused as often as the cap has
    // slots twice over, so a refusal that took one would lock the cap.
    @ParameterizedTest
    @MethodSource("otherVersions")
    void shouldRefuseHandshake_whenConnect_givenOtherWebSocketVersion(SocketRoute route, String versionHeaders)
            throws Exception {
        // given
        SocketRoute.Seat seat = route.newSeat();

        // when
        List<String> heads = new ArrayList<>();
        for (int i = 0; i < CAP_ATTEMPTS; i++) {
            try (Socket socket = sendRequest(serverUri, seat, versionHeaders, "")) {
                heads.add(readHead(socket));
            }
        }
        int openSockets = openSocketsUpToCap(route);

        // then
        assertThat(heads, everyItem(startsWith(UPGRADE_REQUIRED + "\r\n")));
        assertThat(heads, everyItem(containsString("\r\n" + VERSION_13)));
        assertThat(openSockets, equalTo(Rules.SOCKETS_OPEN_MAX));
    }

    @ParameterizedTest
    @MethodSource("routes")
    void shouldRefuseHandshake_whenConnect_givenNoCookie(SocketRoute route) {
        // given
        String path = route.newSeat().path();

        // when
        UpgradeRejectedException refusal = refusal(options(ALLOWED_ORIGIN, new SocketRoute.Seat(null, path)));

        // then
        assertThat(refusal.getStatus(), equalTo(WebSocketHandshakeCheck.NO_SESSION));
    }

    @ParameterizedTest
    @MethodSource("routes")
    void shouldRefuseHandshake_whenConnect_givenUnknownCookie(SocketRoute route) {
        // given
        SocketRoute.Seat seat = new SocketRoute.Seat("bm90LWEtcmVhbC1zZXNzaW9uLXRva2VuLWF0LWFsbA",
                route.newSeat().path());

        // when
        UpgradeRejectedException refusal = refusal(options(ALLOWED_ORIGIN, seat));

        // then
        assertThat(refusal.getStatus(), equalTo(WebSocketHandshakeCheck.NO_SESSION));
    }

    @ParameterizedTest
    @MethodSource("routes")
    void shouldRefuseHandshake_whenConnect_givenExpiredCookie(SocketRoute route) throws Exception {
        // given
        String token = TestPlayers.createGuest("Racer", Rules.KIND_HUMAN);
        TestSql.execute(database(), "update session set expires_at = now() - interval '1 hour' where token_hash = ?",
                Sessions.hash(token));

        // when
        UpgradeRejectedException refusal = refusal(options(ALLOWED_ORIGIN,
                new SocketRoute.Seat(token, route.newSeat().path())));

        // then
        assertThat(refusal.getStatus(), equalTo(WebSocketHandshakeCheck.NO_SESSION));
    }

    @ParameterizedTest
    @MethodSource("routes")
    void shouldRefuseHandshake_whenConnect_givenForeignOrigin(SocketRoute route) {
        // when
        UpgradeRejectedException refusal = refusal(options(FOREIGN_ORIGIN, route.newSeat()));

        // then
        assertThat(refusal.getStatus(), equalTo(WebSocketHandshakeCheck.FOREIGN_ORIGIN));
    }

    // The Origin is checked before the session, so a foreign page never
    // causes a database read.
    @ParameterizedTest
    @MethodSource("routes")
    void shouldRefuseHandshake_whenConnect_givenForeignOriginAndNoCookie(SocketRoute route) {
        // given
        String path = route.newSeat().path();

        // when
        UpgradeRejectedException refusal = refusal(options(FOREIGN_ORIGIN, new SocketRoute.Seat(null, path)));

        // then
        assertThat(refusal.getStatus(), equalTo(WebSocketHandshakeCheck.FOREIGN_ORIGIN));
    }

    @ParameterizedTest
    @MethodSource("routes")
    void shouldAnswerNotFound_whenGet_givenNoUpgrade(SocketRoute route) {
        // given
        SocketRoute.Seat seat = route.newSeat();

        // when, then
        given().header("Origin", ALLOWED_ORIGIN).cookie(TestPlayers.COOKIE, seat.token())
                .when().get(seat.path())
                .then().statusCode(404);
    }

    @ParameterizedTest
    @MethodSource("routes")
    void shouldRefuseHandshake_whenConnect_givenNoOrigin(SocketRoute route) {
        // when
        UpgradeRejectedException refusal = refusal(options(null, route.newSeat()));

        // then
        assertThat(refusal.getStatus(), equalTo(WebSocketHandshakeCheck.FOREIGN_ORIGIN));
    }

    @ParameterizedTest
    @MethodSource("routes")
    void shouldSendSecurityHeaders_whenConnect_givenRefusedHandshake(SocketRoute route) {
        // given
        SocketRoute.Seat seat = route.newSeat();
        List<WebSocketConnectOptions> refusedHandshakes = new ArrayList<>(List.of(
                options(ALLOWED_ORIGIN, new SocketRoute.Seat(null, seat.path())),
                options(FOREIGN_ORIGIN, seat)));
        route.ownRefusals().forEach(ownRefusal -> refusedHandshakes.add(options(ALLOWED_ORIGIN, ownRefusal.seat())));

        // when
        List<MultiMap> headers = refusedHandshakes.stream().map(handshake -> refusal(handshake).getHeaders()).toList();

        // then
        for (MultiMap header : headers) {
            assertThat(header.get("Cache-Control"), equalTo("no-store"));
            assertThat(header.get("X-Content-Type-Options"), equalTo("nosniff"));
            assertThat(header.get("Content-Security-Policy"), equalTo("default-src 'none'; frame-ancestors 'none'"));
        }
    }

    // The client adds its own Origin when none is set, and with the Origin
    // header turned off it drops one set by hand too.
    private WebSocketConnectOptions options(String origin, SocketRoute.Seat seat) {
        WebSocketConnectOptions options = new WebSocketConnectOptions()
                .setHost(serverUri.getHost())
                .setPort(serverUri.getPort())
                .setURI(seat.path())
                .setAllowOriginHeader(null != origin);
        if (null != origin) {
            options.addHeader("Origin", origin);
        }
        if (null != seat.token()) {
            options.addHeader("Cookie", TestPlayers.COOKIE + "=" + seat.token());
        }
        return options;
    }

    private WebSocket connectReading(SocketRoute.Seat seat, BlockingQueue<String> received) throws Exception {
        WebSocket socket = await(client.connect(options(ALLOWED_ORIGIN, seat)));
        socketsToClose.add(socket);
        socket.textMessageHandler(received::add);
        return socket;
    }

    // Skips what the route sends on its own; null after the timeout.
    private static String nextReply(SocketRoute route, BlockingQueue<String> received) throws InterruptedException {
        String message = received.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        while (null != message && route.isUnprompted(message)) {
            message = received.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
        return message;
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

    // A slot that never came back shows only with every slot taken at once:
    // with one lost, a single new socket would still find room. The seats
    // are all different, so no socket replaces another.
    private int openSocketsUpToCap(SocketRoute route) throws Exception {
        closeSockets();
        route.forEachNewSeat(Rules.SOCKETS_OPEN_MAX,
                seat -> socketsToClose.add(connectWhenRoom(options(ALLOWED_ORIGIN, seat))));
        return socketsToClose.size();
    }

    private String replyOnNewSocket(SocketRoute route) throws Exception {
        WebSocket socket = connectWhenRoom(options(ALLOWED_ORIGIN, route.newSeat()));
        socketsToClose.add(socket);
        BlockingQueue<String> received = new LinkedBlockingQueue<>();
        socket.textMessageHandler(received::add);
        socket.writeTextMessage(route.request());
        return nextReply(route, received);
    }

    // Returns once the socket has ended.
    private void end(SocketRoute.Seat seat, SocketEnding ending) throws Exception {
        switch (ending) {
            case CLIENT_CLOSE -> openAndEnd(seat, WebSocket::close);
            case MESSAGE_OVER_LIMIT -> openAndEnd(seat, socket -> socket.writeTextMessage(LARGEST_MESSAGE + "\u0416"));
            case CLIENT_STOPS_READING -> assertThat(wasDroppedWhileFlooding(seat), equalTo(true));
            case FRAME_OVER_SIZE_LIMIT ->
                    openAndEnd(seat, socket -> socket.writeTextMessage("x".repeat(OVERSIZED_FRAME_BYTES)));
            case PING -> openAndEnd(seat, socket -> socket.writePing(Buffer.buffer()));
        }
    }

    private void openAndEnd(SocketRoute.Seat seat, SocketAction action) throws Exception {
        WebSocket socket = connectWhenRoom(options(ALLOWED_ORIGIN, seat));
        CompletableFuture<Void> closed = new CompletableFuture<>();
        socket.closeHandler(ignored -> closed.complete(null));
        action.run(socket);
        closed.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    // By hand, so the client reads nothing at all and its frames go out in
    // large writes; a write fails once the server has dropped the connection.
    private boolean wasDroppedWhileFlooding(SocketRoute.Seat seat) throws Exception {
        ByteArrayOutputStream flood = new ByteArrayOutputStream();
        for (int i = 0; i < FLOOD_FRAMES_PER_WRITE; i++) {
            flood.write(maskedTextFrame(""));
        }
        byte[] frames = flood.toByteArray();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        boolean wasDropped = false;
        try (Socket socket = handshakeByHand(seat)) {
            OutputStream output = socket.getOutputStream();
            while (!wasDropped && System.nanoTime() < deadline) {
                try {
                    output.write(frames);
                } catch (IOException e) {
                    wasDropped = true;
                }
            }
        }
        return wasDropped;
    }

    // By hand, so the client reads nothing after the handshake answer.
    private Socket handshakeByHand(SocketRoute.Seat seat) throws IOException {
        Socket socket = sendUpgradeRequest(serverUri, seat);
        assertThat(statusLine(readHead(socket)), equalTo(SWITCHING_PROTOCOLS));
        return socket;
    }

    static Socket sendUpgradeRequest(URI server, SocketRoute.Seat seat) throws IOException {
        return sendRequest(server, seat, KEY + VERSION_13, "");
    }

    static Socket sendTwoUpgradeRequests(URI server, SocketRoute.Seat seat) throws IOException {
        String request = upgradeRequest(server, ALLOWED_ORIGIN, seat, KEY + VERSION_13, "");
        return writeOnNewConnection(server, request + request);
    }

    private static Socket sendRequest(URI server, SocketRoute.Seat seat, String handshakeHeaders, String body)
            throws IOException {
        return writeOnNewConnection(server, upgradeRequest(server, ALLOWED_ORIGIN, seat, handshakeHeaders, body));
    }

    // Each of the handshake headers ends in CRLF; the body follows the blank line.
    private static String upgradeRequest(URI server, String origin, SocketRoute.Seat seat, String handshakeHeaders,
                                         String body) {
        return "GET " + seat.path() + " HTTP/1.1\r\n"
                + "Host: " + server.getHost() + ":" + server.getPort() + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + handshakeHeaders
                + "Origin: " + origin + "\r\n"
                + "Cookie: " + TestPlayers.COOKIE + "=" + seat.token() + "\r\n"
                + "\r\n"
                + body;
    }

    private static Socket writeOnNewConnection(URI server, String requests) throws IOException {
        Socket socket = new Socket(server.getHost(), server.getPort());
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
    static byte[] maskedTextFrame(String text) {
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        byte[] frame = new byte[6 + payload.length];
        frame[0] = (byte) 0x81;
        frame[1] = (byte) (0x80 | payload.length);
        System.arraycopy(payload, 0, frame, 6, payload.length);
        return frame;
    }

    // The server's frames are not masked; its replies here are whole text
    // frames under 64 KB. A close frame or a reset ends the reading, which
    // is fine for a dropped socket; a timeout is neither, so it fails.
    private static Replies readReplies(Socket socket, SocketRoute route, int count) throws IOException {
        DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        List<String> texts = new ArrayList<>();
        boolean wasEnded = false;
        try {
            while (!wasEnded && texts.size() < count) {
                int opcode = input.readUnsignedByte();
                int length = input.readUnsignedByte();
                if (126 == length) {
                    length = input.readUnsignedShort();
                }
                String text = new String(input.readNBytes(length), StandardCharsets.UTF_8);
                if (CLOSE_OPCODE == opcode) {
                    wasEnded = true;
                } else if (!route.isUnprompted(text)) {
                    texts.add(text);
                }
            }
        } catch (EOFException | SocketException e) {
            wasEnded = true;
        }
        return new Replies(texts, wasEnded);
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

    interface SocketAction {
        void run(WebSocket socket) throws Exception;
    }

    record Replies(List<String> texts, boolean wasEnded) {
    }
}
