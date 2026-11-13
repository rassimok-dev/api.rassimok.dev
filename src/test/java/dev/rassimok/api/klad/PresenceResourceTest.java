package dev.rassimok.api.klad;

import io.quarkus.test.common.DevServicesContext;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

/** HTTP and plain JDBC only, so the native integration test can run it against the binary. */
@QuarkusTest
class PresenceResourceTest {

    @TestHTTPResource("/presence")
    URI presenceUri;

    DevServicesContext devServices;

    @Test
    void shouldListPlayer_whenGetPresence_givenHeartbeat() {
        // given
        String nick = TestPlayers.uniqueNick("Online");
        String token = TestPlayers.createGuest(nick, Rules.KIND_AGENT);

        // when
        ValidatableResponse heartbeat = given().cookie(TestPlayers.COOKIE, token).when().post("/presence").then();
        ValidatableResponse online = given().cookie(TestPlayers.COOKIE, token).when().get("/presence").then();

        // then
        heartbeat.statusCode(204).body(emptyString()).header("Set-Cookie", nullValue());
        online.statusCode(200)
                .body("", aMapWithSize(1))
                .body("players", hasItem(Map.of("nick", nick, "guest", true, "kind", Rules.KIND_AGENT)));
    }

    @Test
    void shouldListAccountAsNoGuest_whenGetPresence_givenAccountHeartbeat() throws SQLException {
        // given
        String nick = TestPlayers.uniqueNick("Account");
        String token = accountToken(nick);
        given().cookie(TestPlayers.COOKIE, token).when().post("/presence").then().statusCode(204);

        // when
        ValidatableResponse online = given().cookie(TestPlayers.COOKIE, token).when().get("/presence").then();

        // then
        online.statusCode(200).body("players", hasItem(Map.of("nick", nick, "guest", false, "kind", Rules.KIND_HUMAN)));
    }

    @Test
    void shouldNotListPlayer_whenGetPresence_givenNoHeartbeat() {
        // given
        String nick = TestPlayers.uniqueNick("Quiet");
        String token = TestPlayers.createGuest(nick, Rules.KIND_HUMAN);

        // when
        ValidatableResponse online = given().cookie(TestPlayers.COOKIE, token).when().get("/presence").then();

        // then
        online.statusCode(200).body("players.nick", not(hasItem(nick)));
    }

    @Test
    void shouldListPlayerOnce_whenGetPresence_givenTwoHeartbeats() {
        // given
        String nick = TestPlayers.uniqueNick("Twice");
        String token = TestPlayers.createGuest(nick, Rules.KIND_HUMAN);
        given().cookie(TestPlayers.COOKIE, token).when().post("/presence").then().statusCode(204);
        given().cookie(TestPlayers.COOKIE, token).when().post("/presence").then().statusCode(204);

        // when
        ValidatableResponse online = given().cookie(TestPlayers.COOKIE, token).when().get("/presence").then();

        // then
        online.statusCode(200).body("players.findAll { it.nick == '" + nick + "' }.size()", equalTo(1));
    }

    @Test
    void shouldAnswerNoSession_whenHeartbeat_givenExpiredSession() throws SQLException {
        // given
        String token = TestPlayers.createGuest("Expired", Rules.KIND_HUMAN);
        TestSql.execute(TestSql.devServicesDatabase(devServices),
                "update session set expires_at = now() - interval '1 second' where token_hash = ?",
                Sessions.hash(token));

        // when
        ValidatableResponse response = given().cookie(TestPlayers.COOKIE, token).when().post("/presence").then();

        // then
        response.statusCode(401).body("", equalTo(Map.of("error", ApiException.NO_SESSION)));
    }

    @Test
    void shouldResendCookie_whenGetPresence_givenSessionOlderThanADay() throws SQLException {
        // given
        String token = TestPlayers.createGuest("Returning", Rules.KIND_HUMAN);
        TestSql.execute(TestSql.devServicesDatabase(devServices),
                "update session set expires_at = now() + interval '88 days' where token_hash = ?",
                Sessions.hash(token));

        // when
        ValidatableResponse response = given().cookie(TestPlayers.COOKIE, token).when().get("/presence").then();

        // then
        response.statusCode(200).header("Set-Cookie", equalTo(Sessions.cookie(token)));
    }

    // The proxy may forward a bodiless request as an empty chunked body.
    @Test
    void shouldAcceptHeartbeat_whenHeartbeat_givenEmptyChunkedBodyWithoutContentType() throws Exception {
        // given
        String token = TestPlayers.createGuest("Chunked", Rules.KIND_HUMAN);
        HttpRequest request = HttpRequest.newBuilder(presenceUri)
                .header("Cookie", TestPlayers.COOKIE + "=" + token)
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(new byte[0])))
                .build();

        // when
        HttpResponse<String> response;
        try (HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        }

        // then
        assertThat(response.statusCode(), equalTo(204));
    }

    private String accountToken(String nick) throws SQLException {
        String token = UUID.randomUUID().toString();
        String email = TestPlayers.uniqueEmail("presence");
        try (Connection connection = TestSql.devServicesDatabase(devServices).getConnection()) {
            TestPlayers.insertAccount(connection, nick, email);
            TestSql.execute(connection, """
                    insert into session (token_hash, player_id, created_at, expires_at)
                    select ?, id, now(), now() + interval '90 days' from player where email = ?
                    """, Sessions.hash(token), email);
        }
        return token;
    }
}
