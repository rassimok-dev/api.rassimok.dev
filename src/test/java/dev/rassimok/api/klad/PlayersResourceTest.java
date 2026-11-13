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
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
        List<Object> stored = TestSql.row(dataSource, """
                select player.nick, player.nick_key, player.kind from session
                join player on player.id = session.player_id where session.token_hash = ?
                """, Sessions.hash(token));
        assertThat(stored, equalTo(List.of("NewName", "newname", Rules.KIND_AGENT)));
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
                .body(Map.of("level", 1, "shape", Rules.SHAPE_LANDSCAPE, "revision", 1,
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
}
