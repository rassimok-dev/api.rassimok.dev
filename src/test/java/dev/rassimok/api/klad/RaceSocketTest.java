package dev.rassimok.api.klad;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.UpgradeRejectedException;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketClient;
import io.vertx.core.http.WebSocketConnectOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Whole races over real sockets and REST, no injection, so the native
 * integration test runs it against the binary. The players are made once
 * and the host's every new room closes the last one.
 */
@QuarkusTest
class RaceSocketTest {

    private static final String ALLOWED_ORIGIN = "https://rassimok.dev";
    private static final long TIMEOUT_SECONDS = 10;
    private static final String UNKNOWN_ROOM = "AAAAAAAAAA";
    private static final String PING = "{\"type\":\"ping\"}";
    private static final JsonObject PONG = new JsonObject().put("type", "pong");
    private static final JsonObject COUNTDOWN = new JsonObject().put("type", "countdown").put("seconds", 3);
    private static final String RACE_OVER = "1000 race_over";

    private static Vertx vertx;
    private static WebSocketClient client;
    private static List<Player> players;

    @TestHTTPResource("/")
    URI serverUri;

    private final List<RaceClient> clients = new ArrayList<>();

    private record Player(String nick, String token) {
    }

    @BeforeAll
    static void startClient() {
        vertx = Vertx.vertx();
        client = vertx.createWebSocketClient();
    }

    @AfterAll
    static void stopClient() throws Exception {
        await(vertx.close());
    }

    @BeforeEach
    void createPlayersOnce() {
        if (null == players) {
            List<Player> created = new ArrayList<>();
            for (String prefix : List.of("Host", "Guest", "Third", "Outside")) {
                String nick = TestPlayers.uniqueNick(prefix);
                created.add(new Player(nick, TestPlayers.createGuest(nick, Rules.KIND_HUMAN)));
            }
            players = created;
        }
    }

    @AfterEach
    void closeClients() throws Exception {
        for (RaceClient raceClient : clients) {
            raceClient.socket.close();
            raceClient.closed.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
        clients.clear();
    }

    @Test
    void shouldRunWholeRace_whenSendText_givenTwoMembers() throws Exception {
        // given
        String id = RaceRooms.startedRoom(List.of(host().token(), guest().token()));
        RaceClient hostClient = connect(host(), id);
        JsonObject hostState = hostClient.next();
        RaceClient guestClient = connect(guest(), id);
        List<JsonObject> hostStart = List.of(hostClient.next(), hostClient.next());
        List<JsonObject> guestStart = List.of(guestClient.next(), guestClient.next(), guestClient.next());

        // when
        hostClient.send("{\"type\":\"pos\",\"x\":1.5,\"y\":2,\"tick\":60}");
        JsonObject guestSees = guestClient.next();
        guestClient.send("{\"type\":\"pos\",\"x\":3,\"y\":4.25,\"tick\":61}");
        JsonObject hostSees = hostClient.next();
        guestClient.send("{\"type\":\"finish\",\"ticks\":900}");
        guestClient.send(PING);
        JsonObject guestPong = guestClient.next();
        hostClient.send("{\"type\":\"finish\",\"ticks\":800}");

        // then
        assertThat(hostState, equalTo(state("starting", new JsonArray()
                .add(player(0, host(), true, true))
                .add(player(1, guest(), false, false)), new JsonArray())));
        JsonObject go = hostStart.get(1);
        assertThat(hostStart, equalTo(List.of(COUNTDOWN, go)));
        assertThat(guestStart, equalTo(List.of(state("starting", new JsonArray()
                .add(player(0, host(), false, true))
                .add(player(1, guest(), true, true)), new JsonArray()), COUNTDOWN, go)));
        assertThat(go.getString("type"), equalTo("go"));
        assertThat(Math.abs(System.currentTimeMillis() - go.getLong("at")) < TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS),
                equalTo(true));
        assertThat(guestSees, equalTo(position(0, host(), 1.5, 2, 60)));
        assertThat(hostSees, equalTo(position(1, guest(), 3, 4.25, 61)));
        assertThat(guestPong, equalTo(PONG));
        JsonObject results = results(new JsonArray()
                .add(place(1, guest(), 1, 900))
                .add(place(0, host(), 2, 800)));
        assertThat(hostClient.next(), equalTo(results));
        assertThat(guestClient.next(), equalTo(results));
        assertThat(hostClient.closing(), equalTo(RACE_OVER));
        assertThat(guestClient.closing(), equalTo(RACE_OVER));
        assertThat(RaceRooms.state(host().token(), id), equalTo("lobby"));
        assertThat(readyMarks(id), everyItem(equalTo(false)));
    }

    @Test
    void shouldRelayToEveryOtherAndPlaceInOrder_whenSendText_givenThreeMembers() throws Exception {
        // given
        String id = RaceRooms.startedRoom(List.of(host().token(), guest().token(), third().token()));
        List<RaceClient> racers = List.of(connect(host(), id), connect(guest(), id), connect(third(), id));
        for (RaceClient racer : racers) {
            racer.nextOfType("go");
        }

        // when
        racers.get(1).send("{\"type\":\"pos\",\"x\":5,\"y\":5,\"tick\":1}");
        JsonObject hostSees = racers.get(0).next();
        JsonObject thirdSees = racers.get(2).next();
        finishInTurn(racers.get(2), 1000);
        finishInTurn(racers.get(0), 700);
        racers.get(1).send("{\"type\":\"finish\",\"ticks\":600}");

        // then
        JsonObject position = position(1, guest(), 5, 5, 1);
        assertThat(hostSees, equalTo(position));
        assertThat(thirdSees, equalTo(position));
        JsonObject results = results(new JsonArray()
                .add(place(2, third(), 1, 1000))
                .add(place(0, host(), 2, 700))
                .add(place(1, guest(), 3, 600)));
        for (RaceClient racer : racers) {
            assertThat(racer.nextOfType("results"), equalTo(results));
            assertThat(racer.closing(), equalTo(RACE_OVER));
        }
    }

    @Test
    void shouldResumeWithState_whenConnect_givenReconnectDuringRace() throws Exception {
        // given
        String id = RaceRooms.startedRoom(List.of(host().token(), guest().token()));
        RaceClient hostClient = connect(host(), id);
        RaceClient guestClient = connect(guest(), id);
        hostClient.nextOfType("go");
        guestClient.nextOfType("go");
        hostClient.send("{\"type\":\"pos\",\"x\":7,\"y\":8,\"tick\":120}");
        guestClient.next();
        guestClient.socket.close();
        guestClient.closed.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        // when
        RaceClient returnedClient = connect(guest(), id);

        // then
        assertThat(returnedClient.next(), equalTo(state("racing", new JsonArray()
                .add(player(0, host(), false, true))
                .add(player(1, guest(), true, true)), new JsonArray()
                .add(new JsonObject().put("slot", 0).put("x", 7.0).put("y", 8.0).put("tick", 120)))));
        returnedClient.send("{\"type\":\"finish\",\"ticks\":900}");
        returnedClient.send(PING);
        assertThat(returnedClient.next(), equalTo(PONG));
        hostClient.send("{\"type\":\"finish\",\"ticks\":950}");
        assertThat(hostClient.nextOfType("results").getJsonArray("places").getJsonObject(0).getInteger("slot"),
                equalTo(1));
    }

    @Test
    void shouldCloseOldSocketAsReplaced_whenConnect_givenMemberConnectsAgain() throws Exception {
        // given
        String id = RaceRooms.startedRoom(List.of(host().token(), guest().token()));
        RaceClient firstClient = connect(host(), id);
        firstClient.next();

        // when
        RaceClient secondClient = connect(host(), id);

        // then
        assertThat(firstClient.closing(), equalTo("1000 replaced"));
        assertThat(secondClient.next().getString("type"), equalTo("state"));
    }

    @Test
    void shouldAnswerErrorsAndStayOpen_whenSendText_givenBadMessagesWhileStarting() throws Exception {
        // given
        String id = RaceRooms.startedRoom(List.of(host().token(), guest().token()));
        RaceClient hostClient = connect(host(), id);
        hostClient.next();

        // when
        for (String message : List.of("not json", "{\"type\":\"jump\"}", "{\"type\":\"pos\",\"x\":1}",
                "{\"type\":\"pos\",\"x\":1,\"y\":2,\"tick\":3}", "{\"type\":\"finish\",\"ticks\":5}", PING)) {
            hostClient.send(message);
        }

        // then
        assertThat(List.of(hostClient.next(), hostClient.next(), hostClient.next(), hostClient.next(),
                hostClient.next(), hostClient.next()), equalTo(List.of(error("bad_message"), error("bad_message"),
                error("bad_message"), error("not_racing"), error("not_racing"), PONG)));
        assertThat(hostClient.closed.isDone(), equalTo(false));
        assertThat(RaceRooms.state(host().token(), id), equalTo("starting"));
    }

    @Test
    void shouldAnswerAlreadyFinished_whenSendText_givenSecondFinish() throws Exception {
        // given
        String id = RaceRooms.startedRoom(List.of(host().token(), guest().token()));
        RaceClient hostClient = connect(host(), id);
        RaceClient guestClient = connect(guest(), id);
        hostClient.nextOfType("go");
        guestClient.nextOfType("go");
        hostClient.send("{\"type\":\"finish\",\"ticks\":800}");

        // when
        hostClient.send("{\"type\":\"finish\",\"ticks\":700}");

        // then
        assertThat(hostClient.next(), equalTo(error("already_finished")));
    }

    @Test
    void shouldCloseRaceSockets_whenCreateRoom_givenHostOfRacingRoom() throws Exception {
        // given
        String id = RaceRooms.startedRoom(List.of(host().token(), guest().token()));
        RaceClient hostClient = connect(host(), id);
        RaceClient guestClient = connect(guest(), id);
        hostClient.nextOfType("go");
        guestClient.nextOfType("go");

        // when
        RaceRooms.lobbyRoom(host().token());

        // then
        assertThat(hostClient.closing(), equalTo(RACE_OVER));
        assertThat(guestClient.closing(), equalTo(RACE_OVER));
        assertThat(guestClient.messages.isEmpty(), equalTo(true));
    }

    @Test
    void shouldRefuseHandshake_whenConnect_givenUnknownRoom() {
        // when
        UpgradeRejectedException refusal = refusal(host(), UNKNOWN_ROOM);

        // then
        assertThat(refusal.getStatus(), equalTo(WebSocketHandshakeCheck.UNKNOWN_ROOM));
    }

    @Test
    void shouldRefuseHandshake_whenConnect_givenPlayerNotInRoom() {
        // given
        String id = RaceRooms.startedRoom(List.of(host().token(), guest().token()));

        // when
        UpgradeRejectedException refusal = refusal(outsider(), id);

        // then
        assertThat(refusal.getStatus(), equalTo(WebSocketHandshakeCheck.NOT_IN_RACE));
    }

    @Test
    void shouldRefuseHandshake_whenConnect_givenRoomInLobby() {
        // given
        String id = RaceRooms.lobbyRoom(host().token());

        // when
        UpgradeRejectedException refusal = refusal(host(), id);

        // then
        assertThat(refusal.getStatus(), equalTo(WebSocketHandshakeCheck.NOT_IN_RACE));
    }

    @Test
    void shouldRefuseHandshake_whenConnect_givenRaceOver() throws Exception {
        // given
        String id = RaceRooms.startedRoom(List.of(host().token(), guest().token()));
        RaceClient hostClient = connect(host(), id);
        RaceClient guestClient = connect(guest(), id);
        hostClient.nextOfType("go");
        guestClient.nextOfType("go");
        finishInTurn(hostClient, 800);
        guestClient.send("{\"type\":\"finish\",\"ticks\":900}");
        guestClient.closing();

        // when
        UpgradeRejectedException refusal = refusal(guest(), id);

        // then
        assertThat(refusal.getStatus(), equalTo(WebSocketHandshakeCheck.NOT_IN_RACE));
    }

    // A replaced socket gives its slot back when its close ends, so a
    // member reconnecting again and again never locks the cap.
    @Test
    void shouldOpenSocketsUpToCap_whenConnect_givenMoreSocketsReplacedThanCap() throws Exception {
        // given
        for (int i = 0; i < Rules.SOCKETS_OPEN_MAX; i++) {
            SocketRoute.Seat seat = RaceRoute.SHARED.newSeat();
            RaceClient firstClient = connectSeat(seat);
            connectSeat(seat);
            firstClient.closing();
        }
        closeClients();

        // when
        RaceRoute.SHARED.forEachNewSeat(Rules.SOCKETS_OPEN_MAX, this::connectSeatWhenRoom);

        // then
        assertThat(clients.size(), equalTo(Rules.SOCKETS_OPEN_MAX));
    }

    // A ping and its pong prove the server read the finish before the next
    // member's finish is sent.
    private static void finishInTurn(RaceClient racer, int ticks) throws InterruptedException {
        racer.send("{\"type\":\"finish\",\"ticks\":" + ticks + "}");
        racer.send(PING);
        assertThat(racer.nextOfType("pong"), equalTo(PONG));
    }

    private List<Boolean> readyMarks(String id) {
        return given().cookie(TestPlayers.COOKIE, host().token())
                .when().get("/rooms/" + id)
                .then().statusCode(200)
                .extract().path("players.ready");
    }

    private static JsonObject state(String state, JsonArray players, JsonArray positions) {
        return new JsonObject().put("type", "state").put("state", state).put("level", Rules.LEVEL_MIN)
                .put("shape", Rules.SHAPE_LANDSCAPE).put("players", players).put("positions", positions);
    }

    private static JsonObject player(int slot, Player player, boolean isYou, boolean isConnected) {
        return new JsonObject().put("slot", slot).put("nick", player.nick()).put("kind", Rules.KIND_HUMAN)
                .put("you", isYou).put("connected", isConnected).put("finished", false);
    }

    private static JsonObject position(int slot, Player player, double x, double y, int tick) {
        return new JsonObject().put("type", "pos").put("slot", slot).put("nick", player.nick())
                .put("x", x).put("y", y).put("tick", tick);
    }

    private static JsonObject results(JsonArray places) {
        return new JsonObject().put("type", "results").put("places", places);
    }

    private static JsonObject place(int slot, Player player, int place, int ticks) {
        return new JsonObject().put("slot", slot).put("nick", player.nick()).put("kind", Rules.KIND_HUMAN)
                .put("place", place).put("ticks", ticks).put("left", false);
    }

    private static JsonObject error(String code) {
        return new JsonObject().put("type", "error").put("error", code);
    }

    private static Player host() {
        return players.get(0);
    }

    private static Player guest() {
        return players.get(1);
    }

    private static Player third() {
        return players.get(2);
    }

    private static Player outsider() {
        return players.get(3);
    }

    private RaceClient connect(Player player, String roomId) throws Exception {
        return connectSeat(new SocketRoute.Seat(player.token(), RaceRooms.RACE_PATH + roomId));
    }

    // The client resumes a new socket before the caller gets it, so the
    // handlers are set in the connect's own callback, or the first
    // messages could pass with none.
    private RaceClient connectSeat(SocketRoute.Seat seat) throws Exception {
        RaceClient raceClient = new RaceClient();
        await(client.connect(options(seat)).onSuccess(raceClient::attach));
        clients.add(raceClient);
        return raceClient;
    }

    private void connectSeatWhenRoom(SocketRoute.Seat seat) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        boolean isConnected = false;
        while (!isConnected) {
            try {
                connectSeat(seat);
                isConnected = true;
            } catch (ExecutionException e) {
                int status = assertInstanceOf(UpgradeRejectedException.class, e.getCause()).getStatus();
                if (WebSocketHandshakeCheck.TOO_MANY_SOCKETS != status || deadline < System.nanoTime()) {
                    throw e;
                }
                Thread.sleep(10);
            }
        }
    }

    private UpgradeRejectedException refusal(Player player, String roomId) {
        WebSocketConnectOptions options = options(new SocketRoute.Seat(player.token(), RaceRooms.RACE_PATH + roomId));
        ExecutionException thrown = assertThrows(ExecutionException.class, () -> await(client.connect(options)));
        return assertInstanceOf(UpgradeRejectedException.class, thrown.getCause());
    }

    private WebSocketConnectOptions options(SocketRoute.Seat seat) {
        return new WebSocketConnectOptions()
                .setHost(serverUri.getHost())
                .setPort(serverUri.getPort())
                .setURI(seat.path())
                .addHeader("Origin", ALLOWED_ORIGIN)
                .addHeader("Cookie", TestPlayers.COOKIE + "=" + seat.token());
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private static final class RaceClient {

        private final BlockingQueue<JsonObject> messages = new LinkedBlockingQueue<>();
        private final CompletableFuture<String> closed = new CompletableFuture<>();
        private WebSocket socket;

        private void attach(WebSocket connected) {
            socket = connected;
            socket.textMessageHandler(text -> messages.add(new JsonObject(text)));
            socket.closeHandler(ignored -> closed.complete(socket.closeStatusCode() + " " + socket.closeReason()));
        }

        private void send(String text) {
            socket.writeTextMessage(text);
        }

        private JsonObject next() throws InterruptedException {
            JsonObject message = messages.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThat(message, notNullValue());
            return message;
        }

        private JsonObject nextOfType(String type) throws InterruptedException {
            JsonObject message = next();
            while (!type.equals(message.getString("type"))) {
                message = next();
            }
            return message;
        }

        private String closing() throws Exception {
            return closed.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
    }
}
