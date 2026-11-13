package dev.rassimok.api.klad;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
class PlayersResourceTest {

    private static final String COOKIE_PATTERN = "klad_session=[A-Za-z0-9_-]{43}; Path=/klad/api; HttpOnly; "
            + "Secure; SameSite=Strict; Max-Age=7776000";
    private static final String CLEARED_COOKIE = "klad_session=; Path=/klad/api; HttpOnly; Secure; "
            + "SameSite=Strict; Max-Age=0";
    private static final String NICK_TAKEN_BODY = "{\"error\":\"nick_taken\"}";
    private static final String PLAYER_ROW = """
            select id, nick, nick_key, kind, email, created_at, last_seen_at from player where id = ?
            """;

    @Inject
    DataSource dataSource;

    @Test
    void shouldReturnGuestAndSetCookie_whenCreatePlayer() {
        // when
        ExtractableResponse<Response> response = given()
                .contentType(ContentType.JSON)
                .body(Map.of("nick", "  Bob  ", "kind", Rules.KIND_HUMAN))
                .when().post("/players")
                .then().extract();

        // then
        assertThat(response.statusCode(), equalTo(201));
        assertThat(response.header("Set-Cookie"), matchesPattern(COOKIE_PATTERN));
        assertThat(response.as(PlayerView.class), equalTo(new PlayerView("Bob", Rules.KIND_HUMAN, false, null)));
    }

    @Test
    void shouldStoreOnlyTokenHashAndNickKey_whenCreatePlayer() throws SQLException {
        // when
        String token = TestPlayers.createGuest("MiXeD", Rules.KIND_AGENT);

        // then
        List<Object> stored = TestSql.row(dataSource, """
                select player.nick, player.nick_key, player.kind, player.email, session.token_hash
                from session join player on player.id = session.player_id
                where session.token_hash = ?
                """, Sessions.hash(token));
        assertThat(stored, equalTo(Arrays.<Object>asList("MiXeD", "mixed", Rules.KIND_AGENT, null, Sessions.hash(token))));
        assertThat(Sessions.hash(token), matchesPattern("[0-9a-f]{64}"));
        assertThat(TestSql.count(dataSource, "select count(*) from session where token_hash = ?", token),
                equalTo(0L));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ab", "Iv\u0430n", "bad nick", "seventeen_letters"})
    void shouldRejectNick_whenCreatePlayer_givenInvalidNick(String nick) {
        // when
        ValidatableResponse response = given()
                .contentType(ContentType.JSON)
                .body(Map.of("nick", nick, "kind", Rules.KIND_HUMAN))
                .when().post("/players")
                .then();

        // then
        response.statusCode(400).body("error", equalTo(ApiException.INVALID_NICK)).header("Set-Cookie", nullValue());
    }

    @Test
    void shouldRejectNick_whenCreatePlayer_givenNoNick() {
        // when
        ValidatableResponse response = given()
                .contentType(ContentType.JSON)
                .body(Map.of("kind", Rules.KIND_HUMAN))
                .when().post("/players")
                .then();

        // then
        response.statusCode(400).body("error", equalTo(ApiException.INVALID_NICK));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "robot", "Human"})
    void shouldRejectKind_whenCreatePlayer_givenUnknownKind(String kind) {
        // when
        ValidatableResponse response = given()
                .contentType(ContentType.JSON)
                .body(Map.of("nick", "Kindless", "kind", kind))
                .when().post("/players")
                .then();

        // then
        response.statusCode(400).body("error", equalTo(ApiException.INVALID_KIND));
    }

    @Test
    void shouldRejectKind_whenCreatePlayer_givenNoKind() {
        // when
        ValidatableResponse response = given()
                .contentType(ContentType.JSON)
                .body(Map.of("nick", "Kindless"))
                .when().post("/players")
                .then();

        // then
        response.statusCode(400).body("error", equalTo(ApiException.INVALID_KIND));
    }

    @Test
    void shouldRefuseSecondPlayer_whenCreatePlayer_givenValidSession() {
        // given
        String token = TestPlayers.createGuest("First", Rules.KIND_HUMAN);

        // when
        ValidatableResponse response = given()
                .cookie(TestPlayers.COOKIE, token)
                .contentType(ContentType.JSON)
                .body(Map.of("nick", "Second", "kind", Rules.KIND_HUMAN))
                .when().post("/players")
                .then();

        // then
        response.statusCode(409).body("error", equalTo(ApiException.ALREADY_SIGNED_IN));
    }

    @Test
    void shouldCreatePlayer_whenCreatePlayer_givenUnknownCookie() {
        // when
        ValidatableResponse response = given()
                .cookie(TestPlayers.COOKIE, "not-a-session")
                .contentType(ContentType.JSON)
                .body(Map.of("nick", "Fresh", "kind", Rules.KIND_HUMAN))
                .when().post("/players")
                .then();

        // then
        response.statusCode(201).header("Set-Cookie", matchesPattern(COOKIE_PATTERN));
    }

    // Fullwidth letters, which NFKC turns into plain Latin, count as the same key.
    @Test
    void shouldRefuseNickTaken_whenCreatePlayer_givenAccountHoldsNickKeyInAnyCase() throws SQLException {
        // given
        createAccount("Kept_Nick", TestPlayers.uniqueEmail("kept-nick"));
        List<String> variants = List.of("Kept_Nick", "KEPT_NICK", "kept_nick", " kEpT_nIcK ",
                "\uFF2B\uFF45\uFF50\uFF54_\uFF2E\uFF49\uFF43\uFF4B");

        // when
        List<String> answers = variants.stream()
                .map(nick -> given().contentType(ContentType.JSON).body(Map.of("nick", nick, "kind", Rules.KIND_AGENT))
                        .when().post("/players").then().extract())
                .map(response -> response.statusCode() + " " + response.body().asString() + " "
                        + response.header("Set-Cookie"))
                .toList();

        // then
        assertThat(answers, equalTo(Collections.nCopies(variants.size(), "409 " + NICK_TAKEN_BODY + " null")));
        assertThat(TestSql.count(dataSource, "select count(*) from player where nick_key = 'kept_nick'"),
                equalTo(1L));
    }

    @Test
    void shouldCreateGuest_whenCreatePlayer_givenNickOfAnotherGuest() throws SQLException {
        // given
        TestPlayers.createGuest("TwinGuest", Rules.KIND_HUMAN);

        // when
        ValidatableResponse response = given()
                .contentType(ContentType.JSON)
                .body(Map.of("nick", "TWINGUEST", "kind", Rules.KIND_HUMAN))
                .when().post("/players")
                .then();

        // then
        response.statusCode(201).body("nick", equalTo("TWINGUEST"));
        assertThat(TestSql.count(dataSource, "select count(*) from player where nick_key = 'twinguest'"),
                equalTo(2L));
    }

    @Test
    void shouldReturnPlayerWithoutCookie_whenMe_givenSessionYoungerThanADay() {
        // given
        String token = TestPlayers.createGuest("Reader", Rules.KIND_AGENT);

        // when
        ExtractableResponse<Response> response = given()
                .cookie(TestPlayers.COOKIE, token)
                .when().get("/players/me")
                .then().extract();

        // then
        assertThat(response.statusCode(), equalTo(200));
        assertThat(response.header("Set-Cookie"), nullValue());
        assertThat(response.as(PlayerView.class), equalTo(new PlayerView("Reader", Rules.KIND_AGENT, false, null)));
    }

    @Test
    void shouldRefuse_whenMe_givenNoCookie() {
        // when
        ValidatableResponse response = given().when().get("/players/me").then();

        // then
        response.statusCode(401).body("error", equalTo(ApiException.NO_SESSION));
    }

    @Test
    void shouldRefuse_whenMe_givenUnknownCookie() {
        // when
        ValidatableResponse response = given().cookie(TestPlayers.COOKIE, "not-a-session").when().get("/players/me").then();

        // then
        response.statusCode(401).body("error", equalTo(ApiException.NO_SESSION));
    }

    @Test
    void shouldRefuse_whenMe_givenExpiredSession() throws SQLException {
        // given
        String token = TestPlayers.createGuest("Expired", Rules.KIND_HUMAN);
        TestSql.execute(dataSource, "update session set expires_at = now() - interval '1 hour' where token_hash = ?",
                Sessions.hash(token));

        // when
        ValidatableResponse response = given().cookie(TestPlayers.COOKIE, token).when().get("/players/me").then();

        // then
        response.statusCode(401).body("error", equalTo(ApiException.NO_SESSION));
    }

    @Test
    void shouldResendCookieAndMoveExpiry_whenMe_givenSessionOlderThanADay() throws SQLException {
        // given
        String token = TestPlayers.createGuest("Returning", Rules.KIND_HUMAN);
        TestSql.execute(dataSource, """
                update session set expires_at = now() + interval '88 days' where token_hash = ?
                """, Sessions.hash(token));
        TestSql.execute(dataSource, """
                update player set last_seen_at = now() - interval '2 days'
                where id = (select player_id from session where token_hash = ?)
                """, Sessions.hash(token));

        // when
        ExtractableResponse<Response> response = given()
                .cookie(TestPlayers.COOKIE, token)
                .when().get("/players/me")
                .then().extract();

        // then
        assertThat(response.statusCode(), equalTo(200));
        assertThat(response.header("Set-Cookie"), equalTo(Sessions.cookie(token)));
        List<Object> stored = TestSql.row(dataSource, """
                select session.expires_at - now() > interval '89 days',
                       now() - player.last_seen_at < interval '1 minute'
                from session join player on player.id = session.player_id
                where session.token_hash = ?
                """, Sessions.hash(token));
        assertThat(stored, equalTo(List.of(true, true)));
    }

    @Test
    void shouldChangeOnlyNick_whenRename() throws SQLException {
        // given
        String token = TestPlayers.createGuest("OldName", Rules.KIND_AGENT);
        String playerAndSession = """
                select player.nick, player.nick_key, player.id, player.kind, player.email, player.created_at,
                       player.last_seen_at, session.created_at, session.expires_at
                from session join player on player.id = session.player_id where session.token_hash = ?
                """;
        List<Object> expected = TestSql.row(dataSource, playerAndSession, Sessions.hash(token));
        expected.set(0, "NewName");
        expected.set(1, "newname");

        // when
        ExtractableResponse<Response> response = given()
                .cookie(TestPlayers.COOKIE, token)
                .contentType(ContentType.JSON)
                .body(Map.of("nick", " NewName "))
                .when().patch("/players/me")
                .then().extract();

        // then
        assertThat(response.statusCode(), equalTo(200));
        assertThat(response.as(PlayerView.class), equalTo(new PlayerView("NewName", Rules.KIND_AGENT, false, null)));
        assertThat(TestSql.row(dataSource, playerAndSession, Sessions.hash(token)), equalTo(expected));
    }

    @Test
    void shouldRefuseNickTakenAndKeepGuest_whenRename_givenGuestAndAccountHoldsNickKey() throws SQLException {
        // given
        createAccount("Claimed", TestPlayers.uniqueEmail("claimed"));
        String token = TestPlayers.createGuest("Claimer", Rules.KIND_HUMAN);
        List<Object> expected = TestSql.row(dataSource, PLAYER_ROW, playerId(token));

        // when
        ValidatableResponse response = rename(token, "CLAIMED");

        // then
        response.statusCode(409).body(equalTo(NICK_TAKEN_BODY));
        assertThat(TestSql.row(dataSource, PLAYER_ROW, playerId(token)), equalTo(expected));
    }

    @Test
    void shouldRefuseNickTakenAndKeepAccount_whenRename_givenOtherAccountHoldsNickKey() throws SQLException {
        // given
        createAccount("FirstOwner", TestPlayers.uniqueEmail("first-owner"));
        String token = createAccount("SecondOwner", TestPlayers.uniqueEmail("second-owner"));
        List<Object> expected = TestSql.row(dataSource, PLAYER_ROW, playerId(token));

        // when
        ValidatableResponse response = rename(token, "firstowner");

        // then
        response.statusCode(409).body(equalTo(NICK_TAKEN_BODY));
        assertThat(TestSql.row(dataSource, PLAYER_ROW, playerId(token)), equalTo(expected));
    }

    @Test
    void shouldRename_whenRename_givenAccountChangesOnlyCaseOfOwnNick() throws SQLException {
        // given
        String email = TestPlayers.uniqueEmail("case-keeper");
        String token = createAccount("CaseKeeper", email);
        List<Object> expected = TestSql.row(dataSource, PLAYER_ROW, playerId(token));
        expected.set(1, "CASEKEEPER");

        // when
        ExtractableResponse<Response> response = rename(token, "CASEKEEPER").extract();

        // then
        assertThat(response.statusCode(), equalTo(200));
        assertThat(response.as(PlayerView.class), equalTo(new PlayerView("CASEKEEPER", Rules.KIND_HUMAN, true, email)));
        assertThat(TestSql.row(dataSource, PLAYER_ROW, playerId(token)), equalTo(expected));
    }

    @Test
    void shouldRename_whenRename_givenAccountTakesNickOnlyGuestsUse() throws SQLException {
        // given
        String guest = TestPlayers.createGuest("GuestsOnly", Rules.KIND_HUMAN);
        List<Object> expectedGuest = TestSql.row(dataSource, PLAYER_ROW, playerId(guest));
        String token = createAccount("GuestsFollower", TestPlayers.uniqueEmail("guests-follower"));

        // when
        ValidatableResponse response = rename(token, "guestsonly");

        // then
        response.statusCode(200).body("nick", equalTo("guestsonly")).body("account", equalTo(true));
        assertThat(TestSql.row(dataSource, PLAYER_ROW, playerId(guest)), equalTo(expectedGuest));
    }

    // The other connection plays an instance that gives an account the key
    // after this rename has checked it; the unique index refuses the rename.
    @Test
    void shouldRefuseNickTaken_whenRename_givenOtherAccountTakesNickKeyMeanwhile() throws Exception {
        // given
        String token = createAccount("RenameRacer", TestPlayers.uniqueEmail("rename-racer"));
        List<Object> expected = TestSql.row(dataSource, PLAYER_ROW, playerId(token));

        // when
        long lockWaiters;
        ExtractableResponse<Response> response;
        try (ExecutorService executor = Executors.newSingleThreadExecutor();
             Connection otherInstance = dataSource.getConnection()) {
            otherInstance.setAutoCommit(false);
            TestPlayers.insertAccount(otherInstance, "RaceWinner", TestPlayers.uniqueEmail("race-winner"));
            Future<ExtractableResponse<Response>> request = executor.submit(
                    () -> rename(token, "RACEWINNER").extract());
            lockWaiters = TestSql.awaitLockWaiters(dataSource, 1);
            otherInstance.commit();
            response = request.get();
        }

        // then
        assertThat(lockWaiters, equalTo(1L));
        assertThat(response.statusCode(), equalTo(409));
        assertThat(response.body().asString(), equalTo(NICK_TAKEN_BODY));
        assertThat(TestSql.row(dataSource, PLAYER_ROW, playerId(token)), equalTo(expected));
    }

    @ParameterizedTest
    @ValueSource(strings = {Rules.KIND_HUMAN, Rules.KIND_AGENT})
    void shouldRefuseKind_whenRename_givenKindInBody(String kind) {
        // given
        String token = TestPlayers.createGuest("Fixed", Rules.KIND_HUMAN);

        // when
        ValidatableResponse response = given()
                .cookie(TestPlayers.COOKIE, token)
                .contentType(ContentType.JSON)
                .body(Map.of("nick", "Fixed", "kind", kind))
                .when().patch("/players/me")
                .then();

        // then
        response.statusCode(400).body("error", equalTo(ApiException.KIND_FIXED));
        given().cookie(TestPlayers.COOKIE, token).when().get("/players/me")
                .then().body("kind", equalTo(Rules.KIND_HUMAN));
    }

    @Test
    void shouldRejectNick_whenRename_givenInvalidNick() {
        // given
        String token = TestPlayers.createGuest("Valid", Rules.KIND_HUMAN);

        // when
        ValidatableResponse response = given()
                .cookie(TestPlayers.COOKIE, token)
                .contentType(ContentType.JSON)
                .body(Map.of("nick", "x"))
                .when().patch("/players/me")
                .then();

        // then
        response.statusCode(400).body("error", equalTo(ApiException.INVALID_NICK));
        given().cookie(TestPlayers.COOKIE, token).when().get("/players/me")
                .then().body("nick", equalTo("Valid"));
    }

    @Test
    void shouldRefuse_whenRename_givenNoSession() {
        // when
        ValidatableResponse response = given()
                .contentType(ContentType.JSON)
                .body(Map.of("nick", "Nobody"))
                .when().patch("/players/me")
                .then();

        // then
        response.statusCode(401).body("error", equalTo(ApiException.NO_SESSION));
    }

    @Test
    void shouldDeletePlayerSessionsAndResults_whenDelete() throws SQLException {
        // given
        String token = TestPlayers.createGuest("Goodbye", Rules.KIND_HUMAN);
        UUID playerId = (UUID) TestSql.row(dataSource, "select player_id from session where token_hash = ?",
                Sessions.hash(token)).getFirst();
        given().cookie(TestPlayers.COOKIE, token).contentType(ContentType.JSON)
                .body(Map.of("level", 1, "shape", Rules.SHAPE_LANDSCAPE, "revision", Rules.currentRevision(1),
                        "ticks", 700, "chests", 1, "chestsTotal", 2))
                .when().post("/clears").then().statusCode(201);
        given().cookie(TestPlayers.COOKIE, token).contentType(ContentType.JSON)
                .body(Map.of("levelsCleared", 1, "score", 10, "ticks", 700))
                .when().post("/runs").then().statusCode(201);

        // when
        ExtractableResponse<Response> response = given()
                .cookie(TestPlayers.COOKIE, token)
                .when().delete("/players/me")
                .then().extract();

        // then
        assertThat(response.statusCode(), equalTo(204));
        assertThat(response.header("Set-Cookie"), equalTo(CLEARED_COOKIE));
        List<Object> remaining = TestSql.row(dataSource, """
                select (select count(*) from player where id = ?),
                       (select count(*) from session where player_id = ?),
                       (select count(*) from level_clear where player_id = ?),
                       (select count(*) from game_run where player_id = ?)
                """, playerId, playerId, playerId, playerId);
        assertThat(remaining, equalTo(List.of(0L, 0L, 0L, 0L)));
    }

    @Test
    void shouldDeleteEverything_whenDelete_givenSessionOlderThanADay() throws SQLException {
        // given
        String token = TestPlayers.createGuest("Returner", Rules.KIND_HUMAN);
        UUID playerId = (UUID) TestSql.row(dataSource, "select player_id from session where token_hash = ?",
                Sessions.hash(token)).getFirst();
        TestSql.execute(dataSource, "update session set expires_at = now() + interval '88 days' where player_id = ?",
                playerId);

        // when
        ExtractableResponse<Response> response = given()
                .cookie(TestPlayers.COOKIE, token)
                .when().delete("/players/me")
                .then().extract();

        // then
        assertThat(response.statusCode(), equalTo(204));
        assertThat(response.header("Set-Cookie"), equalTo(CLEARED_COOKIE));
        List<Object> remaining = TestSql.row(dataSource, """
                select (select count(*) from player where id = ?), (select count(*) from session where player_id = ?)
                """, playerId, playerId);
        assertThat(remaining, equalTo(List.of(0L, 0L)));
    }

    @Test
    void shouldRefuseKind_whenRename_givenNullKind() {
        // given
        String token = TestPlayers.createGuest("NullKind", Rules.KIND_HUMAN);

        // when
        ValidatableResponse response = given()
                .cookie(TestPlayers.COOKIE, token)
                .contentType(ContentType.JSON)
                .body("{\"nick\":\"NullKind\",\"kind\":null}")
                .when().patch("/players/me")
                .then();

        // then
        response.statusCode(400).body("error", equalTo(ApiException.KIND_FIXED));
    }

    @Test
    void shouldKeepOtherPlayers_whenDelete() {
        // given
        String staying = TestPlayers.createGuest("Staying", Rules.KIND_HUMAN);
        String leaving = TestPlayers.createGuest("Leaving", Rules.KIND_HUMAN);

        // when
        given().cookie(TestPlayers.COOKIE, leaving).when().delete("/players/me").then().statusCode(204);

        // then
        given().cookie(TestPlayers.COOKIE, staying).when().get("/players/me")
                .then().statusCode(200).body("nick", equalTo("Staying"));
    }

    @Test
    void shouldRefuse_whenDelete_givenNoSession() {
        // when
        ValidatableResponse response = given().when().delete("/players/me").then();

        // then
        response.statusCode(401).body("error", equalTo(ApiException.NO_SESSION));
    }

    // Made in the table: these tests are about nicks, not the login.
    private String createAccount(String nick, String email) throws SQLException {
        String token = TestPlayers.createGuest(nick, Rules.KIND_HUMAN);
        TestSql.execute(dataSource, "update player set email = ? where id = ?", email, playerId(token));
        return token;
    }

    private UUID playerId(String token) throws SQLException {
        return (UUID) TestSql.row(dataSource, "select player_id from session where token_hash = ?",
                Sessions.hash(token)).getFirst();
    }

    private static ValidatableResponse rename(String token, String nick) {
        return given().cookie(TestPlayers.COOKIE, token).contentType(ContentType.JSON).body(Map.of("nick", nick))
                .when().patch("/players/me").then();
    }
}
