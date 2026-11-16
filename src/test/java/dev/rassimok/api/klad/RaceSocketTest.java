package dev.rassimok.api.klad;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
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
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
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
            List<String> prefixes = new ArrayList<>(List.of("Host", "Guest", "Third", "Outside"));
            for (int slot = 3; slot < 10; slot++) {
                prefixes.add("Racer" + slot);
            }
            for (String prefix : prefixes) {
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

    // Each member reads its state before the next one connects, so who is
    // connected in it is certain. Every position goes out in the same
    // instant, as from clients that share one go time; a pong right after
    // the nine relays proves nothing was echoed.
    @Test
    void shouldRunWholeRace_whenSendText_givenTenMembers() throws Exception {
        // given
        List<Player> members = tenMembers();
        String id = RaceRooms.startedRoom(members.stream().map(Player::token).toList());
        List<RaceClient> racers = new ArrayList<>();
        List<JsonObject> states = new ArrayList<>();
        for (Player member : members) {
            RaceClient racer = connect(member, id);
            racers.add(racer);
            states.add(racer.next());
        }
        List<JsonObject> goes = new ArrayList<>();
        for (RaceClient racer : racers) {
            assertThat(racer.next(), equalTo(COUNTDOWN));
            goes.add(racer.next());
        }

        // when
        for (int slot = 0; slot < racers.size(); slot++) {
            racers.get(slot).send("{\"type\":\"pos\",\"x\":" + slot + ",\"y\":1,\"tick\":" + slot + "}");
        }
        List<List<JsonObject>> seen = new ArrayList<>();
        for (RaceClient racer : racers) {
            List<JsonObject> positions = new ArrayList<>();
            for (int i = 0; i < 9; i++) {
                positions.add(racer.next());
            }
            seen.add(positions);
            racer.send(PING);
        }
        List<JsonObject> pongs = new ArrayList<>();
        for (RaceClient racer : racers) {
            pongs.add(racer.next());
        }
        for (int slot = 9; slot > 0; slot--) {
            finishInTurn(racers.get(slot), 1000 + slot);
        }
        racers.getFirst().send("{\"type\":\"finish\",\"ticks\":1000}");

        // then
        for (int connected = 0; connected < 10; connected++) {
            JsonArray players = new JsonArray();
            for (int slot = 0; slot < 10; slot++) {
                players.add(player(slot, members.get(slot), connected == slot, slot <= connected));
            }
            assertThat(states.get(connected), equalTo(state("starting", players, new JsonArray())));
        }
        assertThat(goes.getFirst().getString("type"), equalTo("go"));
        assertThat(goes, everyItem(equalTo(goes.getFirst())));
        for (int receiver = 0; receiver < 10; receiver++) {
            List<JsonObject> expected = new ArrayList<>();
            for (int sender = 0; sender < 10; sender++) {
                if (receiver != sender) {
                    expected.add(position(sender, members.get(sender), sender, 1, sender));
                }
            }
            assertThat(seen.get(receiver), containsInAnyOrder(expected.toArray(new JsonObject[0])));
        }
        assertThat(pongs, everyItem(equalTo(PONG)));
        JsonArray places = new JsonArray();
        for (int place = 1; place <= 10; place++) {
            int slot = 10 - place;
            places.add(place(slot, members.get(slot), place, 1000 + slot));
        }
        for (RaceClient racer : racers) {
            assertThat(racer.nextOfType("results"), equalTo(results(places)));
            assertThat(racer.closing(), equalTo(RACE_OVER));
        }
        assertThat(RaceRooms.state(host().token(), id), equalTo("lobby"));
        assertThat(readyMarks(id), equalTo(Collections.nCopies(10, false)));
    }

    // Chat goes by REST and reaches the other members' sockets at once: before
    // go, while racing, to a member who has finished, not to one who left. A
    // ping right after a send gets the next frame, so an echo would show.
    @Test
    void shouldDeliverChatToOtherMembersOnly_whenSendMessage_givenWholeRaceOfThree() throws Exception {
        // given
        String id = RaceRooms.startedRoom(List.of(host().token(), guest().token(), third().token()));
        RaceClient hostClient = connect(host(), id);
        hostClient.next();
        RaceClient guestClient = connect(guest(), id);
        guestClient.next();
        String quotedText = "\"\u041F\u0440\u0438\u0432\u0435\u0442\" \\ ok";

        // when
        List<Integer> statuses = new ArrayList<>();
        statuses.add(sendMessage(guest(), id, "wait for me"));
        JsonObject hostSeesBeforeGo = hostClient.next();
        JsonObject guestAfterOwnBeforeGo = nextAfterPing(guestClient);
        RaceClient thirdClient = connect(third(), id);
        for (RaceClient racer : List.of(hostClient, guestClient, thirdClient)) {
            racer.nextOfType("go");
        }
        statuses.add(sendMessage(third(), id, quotedText));
        JsonObject hostSeesRacing = hostClient.next();
        JsonObject guestSeesRacing = guestClient.next();
        JsonObject thirdAfterOwn = nextAfterPing(thirdClient);
        finishInTurn(hostClient, 800);
        statuses.add(sendMessage(guest(), id, "well run"));
        JsonObject finishedHostSees = hostClient.next();
        JsonObject thirdSees = thirdClient.next();
        JsonObject guestAfterOwn = nextAfterPing(guestClient);
        given().cookie(TestPlayers.COOKIE, third().token()).when().post("/rooms/" + id + "/leave")
                .then().statusCode(204);
        String thirdClosing = thirdClient.closing();
        statuses.add(sendMessage(host(), id, "bye"));
        JsonObject guestSeesAfterLeave = guestClient.next();
        JsonObject hostAfterOwn = nextAfterPing(hostClient);
        guestClient.send("{\"type\":\"finish\",\"ticks\":900}");

        // then
        assertThat(statuses, equalTo(List.of(201, 201, 201, 201)));
        assertThat(hostSeesBeforeGo, equalTo(chat(1, 2, 1, guest(), "wait for me")));
        JsonObject fromThird = chat(2, 3, 2, third(), quotedText);
        assertThat(hostSeesRacing, equalTo(fromThird));
        assertThat(guestSeesRacing, equalTo(fromThird));
        JsonObject fromGuest = chat(3, 2, 1, guest(), "well run");
        assertThat(finishedHostSees, equalTo(fromGuest));
        assertThat(thirdSees, equalTo(fromGuest));
        assertThat(thirdClosing, equalTo(RACE_OVER));
        assertThat(thirdClient.messages.isEmpty(), equalTo(true));
        assertThat(guestSeesAfterLeave, equalTo(chat(4, 1, 0, host(), "bye")));
        assertThat(List.of(guestAfterOwnBeforeGo, thirdAfterOwn, guestAfterOwn, hostAfterOwn),
                everyItem(equalTo(PONG)));
        JsonArray places = new JsonArray()
                .add(place(0, host(), 1, 800))
                .add(place(1, guest(), 2, 900))
                .add(leftPlace(2, third()));
        assertThat(hostClient.nextOfType("results"), equalTo(results(places)));
        assertThat(guestClient.nextOfType("results"), equalTo(results(places)));
        assertThat(hostClient.closing(), equalTo(RACE_OVER));
        assertThat(roomMessages(host(), id), equalTo(new JsonArray()
                .add(roomMessage(1, 2, guest(), false, "wait for me"))
                .add(roomMessage(2, 3, third(), false, quotedText))
                .add(roomMessage(3, 2, guest(), false, "well run"))
                .add(roomMessage(4, 1, host(), true, "bye"))));
    }

    // Nothing refused reaches a socket: the host's next frames are the five
    // accepted messages and then its pong. A chat message sent on the socket
    // is an unknown type.
    @Test
    void shouldDeliverNoRefusedChat_whenSendMessage_givenRacing() throws Exception {
        // given
        String id = RaceRooms.startedRoom(List.of(host().token(), guest().token()));
        RaceClient hostClient = connect(host(), id);
        RaceClient guestClient = connect(guest(), id);
        hostClient.nextOfType("go");
        guestClient.nextOfType("go");

        // when
        int outsiderStatus = sendMessage(outsider(), id, "let me in");
        int twoLinesStatus = sendMessage(guest(), id, "two\nlines");
        List<Integer> statuses = new ArrayList<>();
        for (int number = 1; number <= Rules.CHAT_MESSAGES_PER_WINDOW + 1; number++) {
            statuses.add(sendMessage(guest(), id, "message " + number));
        }
        guestClient.send("{\"type\":\"chat\",\"text\":\"hello\"}");
        JsonObject guestAnswer = guestClient.next();
        JsonObject guestPong = nextAfterPing(guestClient);
        List<JsonObject> hostSees = new ArrayList<>();
        for (int i = 0; i < Rules.CHAT_MESSAGES_PER_WINDOW; i++) {
            hostSees.add(hostClient.next());
        }
        hostSees.add(nextAfterPing(hostClient));

        // then
        assertThat(outsiderStatus, equalTo(404));
        assertThat(twoLinesStatus, equalTo(400));
        assertThat(statuses, equalTo(List.of(201, 201, 201, 201, 201, 429)));
        assertThat(guestAnswer, equalTo(error("bad_message")));
        assertThat(guestPong, equalTo(PONG));
        assertThat(guestClient.closed.isDone(), equalTo(false));
        List<JsonObject> expected = new ArrayList<>();
        for (int number = 1; number <= Rules.CHAT_MESSAGES_PER_WINDOW; number++) {
            expected.add(chat(number, 2, 1, guest(), "message " + number));
        }
        expected.add(PONG);
        assertThat(hostSees, equalTo(expected));
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

    private static JsonObject nextAfterPing(RaceClient racer) throws InterruptedException {
        racer.send(PING);
        return racer.next();
    }

    private static int sendMessage(Player player, String roomId, String text) {
        return given().cookie(TestPlayers.COOKIE, player.token())
                .contentType(ContentType.JSON)
                .body(new JsonObject().put("text", text).encode())
                .when().post("/rooms/" + roomId + "/messages")
                .then().extract().statusCode();
    }

    private static JsonArray roomMessages(Player player, String roomId) {
        String room = given().cookie(TestPlayers.COOKIE, player.token())
                .when().get("/rooms/" + roomId)
                .then().statusCode(200)
                .extract().asString();
        return new JsonObject(room).getJsonArray("messages");
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

    private static JsonObject leftPlace(int slot, Player player) {
        return new JsonObject().put("slot", slot).put("nick", player.nick()).put("kind", Rules.KIND_HUMAN)
                .putNull("place").putNull("ticks").put("left", true);
    }

    private static JsonObject chat(int number, int member, int slot, Player sender, String text) {
        return new JsonObject().put("type", "chat").put("n", number).put("member", member).put("slot", slot)
                .put("nick", sender.nick()).put("kind", Rules.KIND_HUMAN).put("guest", true).put("text", text);
    }

    private static JsonObject roomMessage(int number, int member, Player sender, boolean isYou, String text) {
        return new JsonObject().put("n", number).put("member", member).put("nick", sender.nick())
                .put("kind", Rules.KIND_HUMAN).put("guest", true).put("you", isYou).put("text", text);
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

    // Host, guest and third, then the seven made after the outsider.
    private static List<Player> tenMembers() {
        List<Player> members = new ArrayList<>(List.of(host(), guest(), third()));
        members.addAll(players.subList(4, 11));
        return members;
    }

    private RaceClient connect(Player player, String roomId) throws Exception {
        return connectSeat(new SocketRoute.Seat(player.token(), RaceRooms.RACE_PATH + roomId));
    }

    // The client resumes a new socket before the connect future completes, so
    // the handlers are set in the callback form, which runs in the same
    // event-loop task, or the first messages could pass with none.
    private RaceClient connectSeat(SocketRoute.Seat seat) throws Exception {
        RaceClient raceClient = new RaceClient();
        CompletableFuture<Void> connected = new CompletableFuture<>();
        client.connect(options(seat), result -> {
            if (result.succeeded()) {
                raceClient.attach(result.result());
                connected.complete(null);
            } else {
                connected.completeExceptionally(result.cause());
            }
        });
        connected.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
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
