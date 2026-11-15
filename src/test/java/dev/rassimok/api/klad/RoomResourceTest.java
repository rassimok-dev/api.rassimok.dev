package dev.rassimok.api.klad;

import io.quarkus.test.common.DevServicesContext;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;

/**
 * HTTP and plain JDBC only, so the native integration test can run it against
 * the binary. The rooms stay in the instance's memory for the whole run, so
 * every test makes at most a few, far below the cap of 100.
 */
@QuarkusTest
class RoomResourceTest {

    private static final String UNKNOWN_ID = "AAAAAAAAAA";
    private static final String HOST = "RoomHost";
    private static final String GUEST = "RoomGuest";
    private static final String LOBBY = "lobby";
    private static final String STARTING = "starting";
    private static final String READY = "{\"ready\":true}";
    private static final String LEVEL = "{\"level\":4,\"shape\":\"portrait\"}";
    private static final String LINK_ONLY = "{\"listed\":false}";
    private static final Set<String> LIST_FIELDS = Set.of("id", "state", "host", "players", "size", "level", "shape");

    @TestHTTPResource("/rooms/" + UNKNOWN_ID + "/ready")
    URI unknownReadyUri;

    DevServicesContext devServices;

    static Stream<Arguments> endpoints() {
        return Stream.of(
                Arguments.of("POST", "/presence", null),
                Arguments.of("GET", "/presence", null),
                Arguments.of("POST", "/rooms", null),
                Arguments.of("POST", "/rooms?listed=yes", null),
                Arguments.of("GET", "/rooms", null),
                Arguments.of("GET", "/rooms/" + UNKNOWN_ID, null),
                Arguments.of("POST", "/rooms/" + UNKNOWN_ID + "/join", null),
                Arguments.of("POST", "/rooms/" + UNKNOWN_ID + "/leave", null),
                Arguments.of("POST", "/rooms/" + UNKNOWN_ID + "/ready", READY),
                Arguments.of("POST", "/rooms/" + UNKNOWN_ID + "/level", LEVEL),
                Arguments.of("POST", "/rooms/" + UNKNOWN_ID + "/listed", LINK_ONLY),
                Arguments.of("POST", "/rooms/" + UNKNOWN_ID + "/start", null));
    }

    // Each case starts a room of its own, then tries one lobby action on it.
    static Stream<Arguments> lobbyActions() {
        return Stream.of(
                Arguments.of("/join", null, "Latecomer"),
                Arguments.of("/ready", READY, GUEST),
                Arguments.of("/level", LEVEL, HOST),
                Arguments.of("/start", null, HOST));
    }

    static Stream<Arguments> actionsWithBody() {
        return Stream.of(
                Arguments.of("/ready", READY),
                Arguments.of("/level", LEVEL),
                Arguments.of("/listed", LINK_ONLY),
                Arguments.of("/start", null));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("endpoints")
    void shouldAnswerNoSession_whenRequest_givenNoCookie(String method, String path, String body) {
        // when
        ValidatableResponse response = withBody(given(), body).when().request(method, path).then();

        // then
        response.statusCode(401).body("", equalTo(Map.of("error", ApiException.NO_SESSION)));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("endpoints")
    void shouldAnswerNoSession_whenRequest_givenUnknownCookie(String method, String path, String body) {
        // when
        ValidatableResponse response = withBody(given().cookie(TestPlayers.COOKIE, "unknown"), body)
                .when().request(method, path).then();

        // then
        response.statusCode(401).body("", equalTo(Map.of("error", ApiException.NO_SESSION)));
    }

    @Test
    void shouldAnswerNoSession_whenFindRoom_givenExistingRoomAndExpiredSession() throws SQLException {
        // given
        String token = guest(HOST);
        String id = createRoom(token);
        TestSql.execute(TestSql.devServicesDatabase(devServices),
                "update session set expires_at = now() - interval '1 second' where token_hash = ?",
                Sessions.hash(token));

        // when
        ValidatableResponse response = given().cookie(TestPlayers.COOKIE, token).when().get("/rooms/" + id).then();

        // then
        response.statusCode(401).body("", equalTo(Map.of("error", ApiException.NO_SESSION)));
    }

    @Test
    void shouldCreateRoomWithCallerAsHost_whenCreateRoom() {
        // given
        String token = guest(HOST);

        // when
        ValidatableResponse created = given().cookie(TestPlayers.COOKIE, token).when().post("/rooms").then();

        // then
        created.statusCode(201)
                .header("Set-Cookie", nullValue())
                .body("", aMapWithSize(1))
                .body("id", matchesPattern("[A-Za-z0-9_-]{10}"));
        String id = created.extract().path("id");
        find(token, id).statusCode(200)
                .body("", equalTo(room(id, LOBBY, HOST, List.of(member(HOST, false, true)))));
    }

    @Test
    void shouldCreateRoom_whenCreateRoom_givenBodyOfAnotherType() {
        // given
        String token = guest(HOST);

        // when
        ValidatableResponse created = given().cookie(TestPlayers.COOKIE, token)
                .contentType("text/plain").body("x".getBytes(StandardCharsets.UTF_8))
                .when().post("/rooms").then();

        // then
        created.statusCode(201).body("id", matchesPattern("[A-Za-z0-9_-]{10}"));
    }

    @Test
    void shouldCloseFirstRoom_whenCreateRoom_givenHostCreatesSecondRoom() {
        // given
        String host = guest(HOST);
        String other = guest(GUEST);
        String firstId = createRoom(host);
        join(other, firstId).statusCode(200);

        // when
        String secondId = createRoom(host);

        // then
        find(other, firstId).statusCode(404).body("", equalTo(Map.of("error", ApiException.NOT_FOUND)));
        find(host, secondId).statusCode(200)
                .body("", equalTo(room(secondId, LOBBY, HOST, List.of(member(HOST, false, true)))));
    }

    @Test
    void shouldAnswerNotFound_whenFindRoom_givenUnknownId() {
        // when
        ValidatableResponse response = find(guest("Seeker"), UNKNOWN_ID);

        // then
        response.statusCode(404).body("", equalTo(Map.of("error", ApiException.NOT_FOUND)));
    }

    @Test
    void shouldShowBothPlayers_whenJoin_givenSecondPlayer() {
        // given
        String host = guest(HOST);
        String id = createRoom(host);

        // when
        ValidatableResponse joined = join(guest(GUEST), id);

        // then
        Map<String, Object> expected = room(id, LOBBY, HOST,
                List.of(member(HOST, false, true), member(GUEST, false, false)));
        joined.statusCode(200).body("", equalTo(roomSeenBy(GUEST, id, LOBBY, HOST,
                List.of(member(HOST, false, true), member(GUEST, false, false)))));
        find(host, id).statusCode(200).body("", equalTo(expected));
    }

    @Test
    void shouldMarkOnlyOwnEntryAsYou_whenFind_givenTwoGuestsWithSameNick() {
        // given
        String first = guest("Twin");
        String second = guest("Twin");
        String id = createRoom(first);
        join(second, id).statusCode(200);

        // when
        ValidatableResponse seenByFirst = find(first, id);
        ValidatableResponse seenBySecond = find(second, id);

        // then
        seenByFirst.statusCode(200).body("players.you", equalTo(List.of(true, false)));
        seenBySecond.statusCode(200).body("players.you", equalTo(List.of(false, true)));
    }

    @Test
    void shouldAcceptTenthPlayer_whenJoin_givenNinePlayersInRoom() {
        // given
        String id = createRoom(guest(HOST));
        IntStream.range(2, 10).forEach(i -> join(guest("Joiner" + i), id).statusCode(200));

        // when
        ValidatableResponse response = join(guest("Tenth"), id);

        // then
        response.statusCode(200).body("size", equalTo(10)).body("players.size()", equalTo(10));
    }

    @Test
    void shouldAnswerRoomFull_whenJoin_givenEleventhPlayer() {
        // given
        String id = createRoom(guest(HOST));
        IntStream.range(2, 11).forEach(i -> join(guest("Joiner" + i), id).statusCode(200));

        // when
        ValidatableResponse response = join(guest("Eleventh"), id);

        // then
        response.statusCode(409).body("", equalTo(Map.of("error", ApiException.ROOM_FULL)));
    }

    @Test
    void shouldTakeExactlyNineMore_whenJoin_givenTwelvePlayersAtOnce() throws Exception {
        // given
        String host = guest(HOST);
        String id = createRoom(host);
        List<String> tokens = IntStream.range(0, 12).mapToObj(i -> guest("Rush" + i)).toList();
        List<Callable<Integer>> joins = tokens.stream()
                .<Callable<Integer>>map(token -> () -> join(token, id).extract().statusCode())
                .toList();

        // when
        List<Integer> statuses = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(joins.size())) {
            for (Future<Integer> status : executor.invokeAll(joins)) {
                statuses.add(status.get());
            }
        }

        // then
        assertThat(statuses, containsInAnyOrder(200, 200, 200, 200, 200, 200, 200, 200, 200, 409, 409, 409));
        find(host, id).statusCode(200).body("players.size()", equalTo(Rules.ROOM_SIZE));
    }

    @Test
    void shouldLeaveFirstRoom_whenJoin_givenPlayerInOtherRoom() {
        // given
        String firstHost = guest("FirstHost");
        String player = guest("Mover");
        String firstId = createRoom(firstHost);
        join(player, firstId).statusCode(200);
        String secondId = createRoom(guest("SecondHost"));

        // when
        ValidatableResponse joined = join(player, secondId);

        // then
        joined.statusCode(200).body("", equalTo(roomSeenBy("Mover", secondId, LOBBY, "SecondHost",
                List.of(member("SecondHost", false, true), member("Mover", false, false)))));
        find(firstHost, firstId).statusCode(200)
                .body("", equalTo(room(firstId, LOBBY, "FirstHost", List.of(member("FirstHost", false, true)))));
    }

    @Test
    void shouldAnswerNotFound_whenJoin_givenUnknownId() {
        // when
        ValidatableResponse response = join(guest("Seeker"), UNKNOWN_ID);

        // then
        response.statusCode(404).body("", equalTo(Map.of("error", ApiException.NOT_FOUND)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("actionsWithBody")
    void shouldAnswerNotFound_whenLobbyAction_givenUnknownId(String action, String body) {
        // when
        ValidatableResponse response = withBody(given().cookie(TestPlayers.COOKIE, guest("Seeker")), body)
                .when().post("/rooms/" + UNKNOWN_ID + action).then();

        // then
        response.statusCode(404).body("", equalTo(Map.of("error", ApiException.NOT_FOUND)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("lobbyActions")
    void shouldAnswerNotInLobby_whenLobbyAction_givenRoomStarting(String action, String body, String actor) {
        // given
        Map<String, String> tokens = Map.of(HOST, guest(HOST), GUEST, guest(GUEST),
                "Latecomer", guest("Latecomer"));
        String id = createRoom(tokens.get(HOST));
        join(tokens.get(GUEST), id).statusCode(200);
        postJson(tokens.get(HOST), "/rooms/" + id + "/ready", READY).statusCode(200);
        postJson(tokens.get(GUEST), "/rooms/" + id + "/ready", READY).statusCode(200);
        post(tokens.get(HOST), "/rooms/" + id + "/start").statusCode(200);

        // when
        ValidatableResponse response = withBody(given().cookie(TestPlayers.COOKIE, tokens.get(actor)), body)
                .when().post("/rooms/" + id + action).then();

        // then
        response.statusCode(409).body("", equalTo(Map.of("error", ApiException.NOT_IN_LOBBY)));
    }

    @Test
    void shouldRemovePlayer_whenLeave() {
        // given
        String host = guest(HOST);
        String other = guest(GUEST);
        String id = createRoom(host);
        join(other, id).statusCode(200);

        // when
        ValidatableResponse left = post(other, "/rooms/" + id + "/leave");

        // then
        left.statusCode(204).body(emptyString());
        find(host, id).statusCode(200)
                .body("", equalTo(room(id, LOBBY, HOST, List.of(member(HOST, false, true)))));
    }

    @Test
    void shouldHandHostToNextPlayer_whenLeave_givenHostLeaves() {
        // given
        String host = guest(HOST);
        String second = guest("Second");
        String id = createRoom(host);
        join(second, id).statusCode(200);
        join(guest("Third"), id).statusCode(200);

        // when
        ValidatableResponse left = post(host, "/rooms/" + id + "/leave");

        // then
        left.statusCode(204);
        find(second, id).statusCode(200).body("", equalTo(room(id, LOBBY, "Second",
                List.of(member("Second", false, true), member("Third", false, false)))));
    }

    @Test
    void shouldShowNoMembers_whenFind_givenPlayerNotInRoom() {
        // given
        String id = createRoom(guest(HOST));
        join(guest(GUEST), id).statusCode(200);

        // when
        ValidatableResponse found = find(guest("Outsider"), id);

        // then
        found.statusCode(200).body("", equalTo(seenFromOutside(id, LOBBY)));
    }

    @Test
    void shouldShowNoMembers_whenFind_givenPlayerNotInStartingRoom() {
        // given
        String host = guest(HOST);
        String other = guest(GUEST);
        String id = createRoom(host);
        join(other, id).statusCode(200);
        postJson(host, "/rooms/" + id + "/ready", READY).statusCode(200);
        postJson(other, "/rooms/" + id + "/ready", READY).statusCode(200);
        post(host, "/rooms/" + id + "/start").statusCode(200);

        // when
        ValidatableResponse found = find(guest("Outsider"), id);

        // then
        found.statusCode(200).body("", equalTo(seenFromOutside(id, STARTING)));
    }

    @Test
    void shouldShowEmptyRoom_whenFind_givenOnlyMemberLeft() {
        // given
        String host = guest(HOST);
        String id = createRoom(host);
        post(host, "/rooms/" + id + "/leave").statusCode(204);

        // when
        ValidatableResponse found = find(host, id);

        // then
        Map<String, Object> expected = new HashMap<>();
        expected.put("id", id);
        expected.put("state", LOBBY);
        expected.put("host", null);
        expected.put("players", List.of());
        expected.put("level", Rules.LEVEL_MIN);
        expected.put("shape", Rules.SHAPE_LANDSCAPE);
        expected.put("size", Rules.ROOM_SIZE);
        expected.put("listed", true);
        found.statusCode(200).body("", equalTo(expected));
    }

    @Test
    void shouldRemovePlayerAtOnce_whenDeleteAccount_givenPlayerInRoom() {
        // given
        String host = guest(HOST);
        String leavingNick = TestPlayers.uniqueNick("Leaving");
        String leaving = guest(leavingNick);
        String id = createRoom(host);
        join(leaving, id).statusCode(200);
        post(leaving, "/presence").statusCode(204);

        // when
        given().cookie(TestPlayers.COOKIE, leaving).when().delete("/players/me").then().statusCode(204);

        // then
        find(host, id).statusCode(200)
                .body("", equalTo(room(id, LOBBY, HOST, List.of(member(HOST, false, true)))));
        given().cookie(TestPlayers.COOKIE, host).when().get("/presence").then().statusCode(200)
                .body("players.nick", not(hasItem(leavingNick)));
    }

    @Test
    void shouldRemovePlayerAtOnce_whenLogout_givenPlayerInRoom() {
        // given
        String host = guest(HOST);
        String leavingNick = TestPlayers.uniqueNick("Leaving");
        String leaving = guest(leavingNick);
        String id = createRoom(host);
        join(leaving, id).statusCode(200);
        post(leaving, "/presence").statusCode(204);

        // when
        post(leaving, "/logout").statusCode(204);

        // then
        find(host, id).statusCode(200)
                .body("", equalTo(room(id, LOBBY, HOST, List.of(member(HOST, false, true)))));
        given().cookie(TestPlayers.COOKIE, host).when().get("/presence").then().statusCode(200)
                .body("players.nick", not(hasItem(leavingNick)));
    }

    @Test
    void shouldAnswerNotFound_whenLeave_givenUnknownId() {
        // when
        ValidatableResponse response = post(guest("Seeker"), "/rooms/" + UNKNOWN_ID + "/leave");

        // then
        response.statusCode(404).body("", equalTo(Map.of("error", ApiException.NOT_FOUND)));
    }

    @Test
    void shouldShowReadyMarks_whenReady() {
        // given
        String host = guest(HOST);
        String other = guest(GUEST);
        String id = createRoom(host);
        join(other, id).statusCode(200);
        postJson(host, "/rooms/" + id + "/ready", READY).statusCode(200);

        // when
        ValidatableResponse ready = postJson(other, "/rooms/" + id + "/ready", READY);
        ValidatableResponse unready = postJson(host, "/rooms/" + id + "/ready", "{\"ready\":false}");

        // then
        ready.statusCode(200).body("", equalTo(roomSeenBy(GUEST, id, LOBBY, HOST,
                List.of(member(HOST, true, true), member(GUEST, true, false)))));
        unready.statusCode(200).body("", equalTo(room(id, LOBBY, HOST,
                List.of(member(HOST, false, true), member(GUEST, true, false)))));
    }

    // The body is checked before the room, so an unknown id is enough here.
    @ParameterizedTest
    @ValueSource(strings = {"{\"ready\":\"true\"}", "{\"ready\":1}", "{\"ready\":null}", "{}", "[true]", "true"})
    void shouldAnswerInvalidReady_whenReady_givenNoBoolean(String body) {
        // when
        ValidatableResponse response = postJson(guest("Readier"), "/rooms/" + UNKNOWN_ID + "/ready", body);

        // then
        response.statusCode(400).body("", equalTo(Map.of("error", ApiException.INVALID_READY)));
    }

    @Test
    void shouldRefuseWithNoBody_whenReady_givenBodyOver4Kilobytes() throws Exception {
        // given
        String token = guest("Padded");
        String body = "{\"ready\":true,\"padding\":\"" + "x".repeat(5000) + "\"}";
        HttpRequest request = HttpRequest.newBuilder(unknownReadyUri)
                .header("Cookie", TestPlayers.COOKIE + "=" + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        // when
        HttpResponse<String> response;
        try (HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        }

        // then
        assertThat(response.statusCode(), equalTo(413));
        assertThat(response.body(), equalTo(""));
    }

    @Test
    void shouldAnswerNotFound_whenReady_givenPlayerNotInRoom() {
        // given
        String id = createRoom(guest(HOST));

        // when
        ValidatableResponse response = postJson(guest("Outsider"), "/rooms/" + id + "/ready", READY);

        // then
        response.statusCode(404).body("", equalTo(Map.of("error", ApiException.NOT_FOUND)));
    }

    @Test
    void shouldSetLevelAndShape_whenLevel_givenHost() {
        // given
        String host = guest(HOST);
        String id = createRoom(host);

        // when
        ValidatableResponse response = postJson(host, "/rooms/" + id + "/level", LEVEL);

        // then
        response.statusCode(200).body("", equalTo(Map.of("id", id, "state", LOBBY, "host", Map.of("nick", HOST),
                "players", List.of(withYou(member(HOST, false, true))), "level", 4, "shape", Rules.SHAPE_PORTRAIT,
                "size", Rules.ROOM_SIZE, "listed", true)));
    }

    @Test
    void shouldAnswerNotHost_whenLevel_givenOtherPlayer() {
        // given
        String id = createRoom(guest(HOST));
        String other = guest(GUEST);
        join(other, id).statusCode(200);

        // when
        ValidatableResponse response = postJson(other, "/rooms/" + id + "/level", LEVEL);

        // then
        response.statusCode(403).body("", equalTo(Map.of("error", ApiException.NOT_HOST)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"level\":0,\"shape\":\"portrait\"}", "{\"level\":6,\"shape\":\"portrait\"}",
            "{\"level\":1.5,\"shape\":\"portrait\"}", "{\"level\":\"3\",\"shape\":\"portrait\"}",
            "{\"shape\":\"portrait\"}", "{\"level\":99999999999,\"shape\":\"portrait\"}"})
    void shouldAnswerInvalidLevel_whenLevel_givenLevelOutsideOneToFive(String body) {
        // when
        ValidatableResponse response = postJson(guest("Chooser"), "/rooms/" + UNKNOWN_ID + "/level", body);

        // then
        response.statusCode(400).body("", equalTo(Map.of("error", ApiException.INVALID_LEVEL)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"level\":2,\"shape\":\"square\"}", "{\"level\":2,\"shape\":\"Portrait\"}",
            "{\"level\":2,\"shape\":1}", "{\"level\":2}"})
    void shouldAnswerInvalidShape_whenLevel_givenUnknownShape(String body) {
        // when
        ValidatableResponse response = postJson(guest("Chooser"), "/rooms/" + UNKNOWN_ID + "/level", body);

        // then
        response.statusCode(400).body("", equalTo(Map.of("error", ApiException.INVALID_SHAPE)));
    }

    @Test
    void shouldMoveToStarting_whenStart_givenEveryPlayerReady() {
        // given
        String host = guest(HOST);
        String other = guest(GUEST);
        String id = createRoom(host);
        join(other, id).statusCode(200);
        postJson(host, "/rooms/" + id + "/ready", READY).statusCode(200);
        postJson(other, "/rooms/" + id + "/ready", READY).statusCode(200);

        // when
        ValidatableResponse started = post(host, "/rooms/" + id + "/start");

        // then
        Map<String, Object> expected = room(id, STARTING, HOST,
                List.of(member(HOST, true, true), member(GUEST, true, false)));
        started.statusCode(200).body("", equalTo(expected));
        find(other, id).statusCode(200).body("", equalTo(roomSeenBy(GUEST, id, STARTING, HOST,
                List.of(member(HOST, true, true), member(GUEST, true, false)))));
    }

    @Test
    void shouldAnswerNotReady_whenStart_givenHostAlone() {
        // given
        String host = guest(HOST);
        String id = createRoom(host);
        postJson(host, "/rooms/" + id + "/ready", READY).statusCode(200);

        // when
        ValidatableResponse response = post(host, "/rooms/" + id + "/start");

        // then
        response.statusCode(409).body("", equalTo(Map.of("error", ApiException.NOT_READY)));
    }

    @Test
    void shouldAnswerNotReady_whenStart_givenPlayerNotReady() {
        // given
        String host = guest(HOST);
        String id = createRoom(host);
        join(guest(GUEST), id).statusCode(200);
        postJson(host, "/rooms/" + id + "/ready", READY).statusCode(200);

        // when
        ValidatableResponse response = post(host, "/rooms/" + id + "/start");

        // then
        response.statusCode(409).body("", equalTo(Map.of("error", ApiException.NOT_READY)));
        find(host, id).statusCode(200).body("state", equalTo(LOBBY));
    }

    @Test
    void shouldAnswerNotHost_whenStart_givenOtherPlayer() {
        // given
        String host = guest(HOST);
        String other = guest(GUEST);
        String id = createRoom(host);
        join(other, id).statusCode(200);
        postJson(host, "/rooms/" + id + "/ready", READY).statusCode(200);
        postJson(other, "/rooms/" + id + "/ready", READY).statusCode(200);

        // when
        ValidatableResponse response = post(other, "/rooms/" + id + "/start");

        // then
        response.statusCode(403).body("", equalTo(Map.of("error", ApiException.NOT_HOST)));
    }

    @Test
    void shouldAnswerNoSession_whenListRooms_givenExpiredSession() throws SQLException {
        // given
        String token = guest(HOST);
        TestSql.execute(TestSql.devServicesDatabase(devServices),
                "update session set expires_at = now() - interval '1 second' where token_hash = ?",
                Sessions.hash(token));

        // when
        ValidatableResponse response = listRooms(token);

        // then
        response.statusCode(401).body("", equalTo(Map.of("error", ApiException.NO_SESSION)));
    }

    // Other tests' rooms share this store and may take all 20 places, so this
    // checks the fields of every entry; RoomListTest checks which rooms are listed.
    @Test
    void shouldShowOnlyListFields_whenListRooms_givenRoomOfOtherPlayer() {
        // given
        createRoom(guest(HOST));

        // when
        ValidatableResponse response = listRooms(guest(GUEST));

        // then
        response.statusCode(200).body("", aMapWithSize(1));
        List<Map<String, Object>> entries = response.extract().path("rooms");
        List<Map<String, Object>> hosts = response.extract().path("rooms.host");
        assertThat(entries.size(), allOf(greaterThan(0), lessThanOrEqualTo(Rules.ROOMS_LISTED_MAX)));
        assertThat(entries.stream().map(Map::keySet).toList(), everyItem(equalTo(LIST_FIELDS)));
        assertThat(hosts.stream().map(Map::keySet).toList(), everyItem(equalTo(Set.of("nick"))));
    }

    @Test
    void shouldLeavePresenceUnchanged_whenListRooms_givenPlayerOnlyReadsList() {
        // given
        String readerNick = TestPlayers.uniqueNick("Reader");
        String reader = guest(readerNick);

        // when
        listRooms(reader).statusCode(200);

        // then
        given().cookie(TestPlayers.COOKIE, guest(GUEST)).when().get("/presence").then().statusCode(200)
                .body("players.nick", not(hasItem(readerNick)));
    }

    @Test
    void shouldShowLinkOnly_whenListed_givenHost() {
        // given
        String host = guest(HOST);
        String id = createRoom(host);

        // when
        ValidatableResponse response = postJson(host, "/rooms/" + id + "/listed", LINK_ONLY);

        // then
        response.statusCode(200).body("", equalTo(linkOnly(room(id, LOBBY, HOST, List.of(member(HOST, false, true))))));
    }

    @Test
    void shouldAnswerNotHost_whenListed_givenOtherPlayer() {
        // given
        String id = createRoom(guest(HOST));
        String other = guest(GUEST);
        join(other, id).statusCode(200);

        // when
        ValidatableResponse response = postJson(other, "/rooms/" + id + "/listed", LINK_ONLY);

        // then
        response.statusCode(403).body("", equalTo(Map.of("error", ApiException.NOT_HOST)));
    }

    // The body is checked before the room, so an unknown id is enough here.
    @ParameterizedTest
    @ValueSource(strings = {"{\"listed\":\"false\"}", "{\"listed\":0}", "{\"listed\":null}", "{}", "[false]", "false"})
    void shouldAnswerInvalidListed_whenListed_givenNoBoolean(String body) {
        // when
        ValidatableResponse response = postJson(guest("Lister"), "/rooms/" + UNKNOWN_ID + "/listed", body);

        // then
        response.statusCode(400).body("", equalTo(Map.of("error", ApiException.INVALID_LISTED)));
    }

    @Test
    void shouldMakeLinkOnlyRoom_whenCreateRoom_givenListedFalse() {
        // given
        String token = guest(HOST);

        // when
        ValidatableResponse created = postRooms(token, "false");

        // then
        String id = created.statusCode(201).body("", aMapWithSize(1)).extract().path("id");
        find(token, id).statusCode(200)
                .body("", equalTo(linkOnly(room(id, LOBBY, HOST, List.of(member(HOST, false, true))))));
    }

    // The framework hands an empty value in as no value, so it means listed too.
    @ParameterizedTest
    @ValueSource(strings = {"true", ""})
    void shouldMakeListedRoom_whenCreateRoom_givenListedTrueOrEmpty(String listed) {
        // given
        String token = guest(HOST);

        // when
        ValidatableResponse created = postRooms(token, listed);

        // then
        String id = created.statusCode(201).body("", aMapWithSize(1)).extract().path("id");
        find(token, id).statusCode(200)
                .body("", equalTo(room(id, LOBBY, HOST, List.of(member(HOST, false, true)))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"yes", "False", "TRUE", "0", "1", " "})
    void shouldAnswerInvalidListed_whenCreateRoom_givenNeitherTrueNorFalse(String listed) {
        // when
        ValidatableResponse response = postRooms(guest("Creator"), listed);

        // then
        response.statusCode(400).body("", equalTo(Map.of("error", ApiException.INVALID_LISTED)));
    }

    @Test
    void shouldResendCookie_whenFindRoom_givenSessionOlderThanADay() throws SQLException {
        // given
        String token = guest(HOST);
        String id = createRoom(token);
        TestSql.execute(TestSql.devServicesDatabase(devServices),
                "update session set expires_at = now() + interval '88 days' where token_hash = ?",
                Sessions.hash(token));

        // when
        ValidatableResponse response = find(token, id);

        // then
        response.statusCode(200).header("Set-Cookie", equalTo(Sessions.cookie(token)));
    }

    private static String guest(String nick) {
        return TestPlayers.createGuest(nick, Rules.KIND_HUMAN);
    }

    private static String createRoom(String token) {
        return given().cookie(TestPlayers.COOKIE, token)
                .when().post("/rooms")
                .then().statusCode(201).extract().path("id");
    }

    private static ValidatableResponse postRooms(String token, String listed) {
        return given().cookie(TestPlayers.COOKIE, token).queryParam("listed", listed).when().post("/rooms").then();
    }

    private static ValidatableResponse listRooms(String token) {
        return given().cookie(TestPlayers.COOKIE, token).when().get("/rooms").then();
    }

    private static ValidatableResponse find(String token, String id) {
        return given().cookie(TestPlayers.COOKIE, token).when().get("/rooms/" + id).then();
    }

    private static ValidatableResponse join(String token, String id) {
        return post(token, "/rooms/" + id + "/join");
    }

    private static ValidatableResponse post(String token, String path) {
        return given().cookie(TestPlayers.COOKIE, token).when().post(path).then();
    }

    private static ValidatableResponse postJson(String token, String path, String body) {
        return given().cookie(TestPlayers.COOKIE, token).contentType(ContentType.JSON).body(body)
                .when().post(path).then();
    }

    private static RequestSpecification withBody(RequestSpecification request, String body) {
        return null == body ? request : request.contentType(ContentType.JSON).body(body);
    }

    private static Map<String, Object> member(String nick, boolean isReady, boolean isHost) {
        return Map.of("nick", nick, "kind", Rules.KIND_HUMAN, "ready", isReady, "host", isHost,
                "you", false);
    }

    private static Map<String, Object> room(String id, String state, String hostNick,
                                            List<Map<String, Object>> players) {
        return roomSeenBy(hostNick, id, state, hostNick, players);
    }

    private static Map<String, Object> roomSeenBy(String viewerNick, String id, String state, String hostNick,
                                                  List<Map<String, Object>> players) {
        List<Map<String, Object>> seenPlayers = players.stream()
                .map(player -> viewerNick.equals(player.get("nick")) ? withYou(player) : player)
                .toList();
        return Map.of("id", id, "state", state, "host", Map.of("nick", hostNick), "players", seenPlayers,
                "level", Rules.LEVEL_MIN, "shape", Rules.SHAPE_LANDSCAPE, "size", Rules.ROOM_SIZE, "listed", true);
    }

    private static Map<String, Object> seenFromOutside(String id, String state) {
        Map<String, Object> room = new HashMap<>();
        room.put("id", id);
        room.put("state", state);
        room.put("host", null);
        room.put("players", List.of());
        room.put("level", Rules.LEVEL_MIN);
        room.put("shape", Rules.SHAPE_LANDSCAPE);
        room.put("size", Rules.ROOM_SIZE);
        room.put("listed", true);
        return room;
    }

    private static Map<String, Object> withYou(Map<String, Object> player) {
        Map<String, Object> you = new HashMap<>(player);
        you.put("you", true);
        return you;
    }

    private static Map<String, Object> linkOnly(Map<String, Object> room) {
        Map<String, Object> linkOnly = new HashMap<>(room);
        linkOnly.put("listed", false);
        return linkOnly;
    }
}
