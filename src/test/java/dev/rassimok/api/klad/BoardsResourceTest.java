package dev.rassimok.api.klad;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/** Every test starts from an empty database: the boards read all players. */
@QuarkusTest
class BoardsResourceTest {

    @Inject
    DataSource dataSource;

    @BeforeEach
    void deleteAllPlayers() throws SQLException {
        TestSql.execute(dataSource, "delete from player");
    }

    @Test
    void shouldRankByLevelsScoreTicksThenTime_whenGetRecords() {
        // given
        run(TestPlayers.createGuest("FewLevels", Rules.KIND_HUMAN), 2, 9000, 2000);
        run(TestPlayers.createGuest("LowScore", Rules.KIND_HUMAN), 3, 100, 3000);
        run(TestPlayers.createGuest("Slower", Rules.KIND_HUMAN), 3, 500, 5000);
        run(TestPlayers.createGuest("Faster", Rules.KIND_HUMAN), 3, 500, 4000);
        run(TestPlayers.createGuest("LaterTie", Rules.KIND_HUMAN), 3, 500, 5000);
        run(TestPlayers.createGuest("MostLevels", Rules.KIND_HUMAN), 4, 10, 6000);

        // when
        ValidatableResponse response = given().when().get("/boards/records").then();

        // then
        response.statusCode(200)
                .body("entries.nick", contains("MostLevels", "Faster", "Slower", "LaterTie", "LowScore", "FewLevels"))
                .body("entries.rank", contains(1, 2, 3, 4, 5, 6))
                .body("entries[0]", equalTo(Map.of("rank", 1, "nick", "MostLevels", "guest", true,
                        "levelsCleared", 4, "score", 10, "ticks", 6000)));
    }

    @Test
    void shouldListBestRunOnce_whenGetRecords_givenPlayerWithSeveralRuns() {
        // given
        String token = TestPlayers.createGuest("Repeater", Rules.KIND_HUMAN);
        run(token, 1, 100, 1000);
        run(token, 3, 50, 3000);
        run(token, 3, 40, 2000);
        run(TestPlayers.createGuest("Other", Rules.KIND_HUMAN), 2, 0, 1500);

        // when
        ValidatableResponse response = given().when().get("/boards/records").then();

        // then
        response.statusCode(200)
                .body("entries.nick", contains("Repeater", "Other"))
                .body("entries[0].score", equalTo(50));
    }

    @Test
    void shouldKeepHumansAndAgentsApart_whenGetBoards() {
        // given
        String human = TestPlayers.createGuest("Human", Rules.KIND_HUMAN);
        String agent = TestPlayers.createGuest("Agent", Rules.KIND_AGENT);
        for (String token : List.of(human, agent)) {
            run(token, 1, 10, 700);
            clear(token, 1, Rules.SHAPE_LANDSCAPE, 1, 700, 1, 1);
        }

        // when
        ValidatableResponse defaultRecords = given().when().get("/boards/records").then();
        ValidatableResponse humanChests = given().queryParam("kind", Rules.KIND_HUMAN).when().get("/boards/chests").then();
        ValidatableResponse agentRecords = given().queryParam("kind", Rules.KIND_AGENT).when().get("/boards/records").then();
        ValidatableResponse agentLevel = given().queryParam("kind", Rules.KIND_AGENT).queryParam("level", 1)
                .queryParam("shape", Rules.SHAPE_LANDSCAPE).queryParam("revision", 1)
                .when().get("/boards/level").then();

        // then
        defaultRecords.body("entries.nick", contains("Human"));
        humanChests.body("entries.nick", contains("Human"));
        agentRecords.body("entries.nick", contains("Agent"));
        agentLevel.body("fastest.nick", contains("Agent")).body("chests.nick", contains("Agent"));
    }

    @Test
    void shouldRankFastestAndMostChests_whenGetLevel() {
        // given
        String first = TestPlayers.createGuest("First", Rules.KIND_HUMAN);
        String second = TestPlayers.createGuest("Second", Rules.KIND_HUMAN);
        String third = TestPlayers.createGuest("Third", Rules.KIND_HUMAN);
        clear(first, 2, Rules.SHAPE_PORTRAIT, 5, 900, 2, 5);
        clear(first, 2, Rules.SHAPE_PORTRAIT, 5, 800, 1, 5);
        clear(second, 2, Rules.SHAPE_PORTRAIT, 5, 850, 5, 5);
        clear(third, 2, Rules.SHAPE_PORTRAIT, 5, 800, 5, 5);
        clear(third, 2, Rules.SHAPE_LANDSCAPE, 5, 600, 5, 5);
        clear(third, 2, Rules.SHAPE_PORTRAIT, 4, 600, 5, 5);

        // when
        ValidatableResponse response = given().queryParam("level", 2).queryParam("shape", Rules.SHAPE_PORTRAIT)
                .queryParam("revision", 5).when().get("/boards/level").then();

        // then
        response.statusCode(200)
                .body("fastest.nick", contains("First", "Third", "Second"))
                .body("fastest.ticks", contains(800, 800, 850))
                .body("fastest.rank", contains(1, 2, 3))
                .body("chests.nick", contains("Third", "Second", "First"))
                .body("chests.rank", contains(1, 2, 3))
                .body("chests[2]", equalTo(Map.of("rank", 3, "nick", "First", "guest", true,
                        "chests", 2, "chestsTotal", 5, "ticks", 900)));
    }

    @Test
    void shouldSumBestChestsOfNewestRevision_whenGetChests() {
        // given
        String veteran = TestPlayers.createGuest("Veteran", Rules.KIND_HUMAN);
        String newcomer = TestPlayers.createGuest("Newcomer", Rules.KIND_HUMAN);
        clear(veteran, 1, Rules.SHAPE_LANDSCAPE, 1, 900, 5, 5);
        clear(veteran, 2, Rules.SHAPE_LANDSCAPE, 1, 900, 2, 4);
        clear(veteran, 2, Rules.SHAPE_LANDSCAPE, 1, 900, 3, 4);
        clear(veteran, 2, Rules.SHAPE_PORTRAIT, 1, 900, 1, 4);
        clear(newcomer, 1, Rules.SHAPE_LANDSCAPE, 2, 900, 1, 5);

        // when
        ValidatableResponse response = given().when().get("/boards/chests").then();

        // then
        response.statusCode(200)
                .body("entries", contains(
                        Map.of("rank", 1, "nick", "Veteran", "guest", true, "chests", 4),
                        Map.of("rank", 2, "nick", "Newcomer", "guest", true, "chests", 1)));
    }

    @Test
    void shouldDropOlderRevisionShare_whenGetChests_givenNewerRevisionClearedByOtherKind() {
        // given
        String human = TestPlayers.createGuest("Human", Rules.KIND_HUMAN);
        clear(human, 1, Rules.SHAPE_LANDSCAPE, 1, 900, 5, 5);
        clear(human, 2, Rules.SHAPE_LANDSCAPE, 1, 900, 3, 4);
        clear(TestPlayers.createGuest("Agent", Rules.KIND_AGENT), 1, Rules.SHAPE_LANDSCAPE, 2, 900, 1, 5);

        // when
        ValidatableResponse response = given().when().get("/boards/chests").then();

        // then
        response.statusCode(200)
                .body("entries", contains(Map.of("rank", 1, "nick", "Human", "guest", true, "chests", 3)));
    }

    @Test
    void shouldBreakTiesByNickKey_whenGetChests() {
        // given
        for (String nick : List.of("carol", "Alice", "bob")) {
            clear(TestPlayers.createGuest(nick, Rules.KIND_HUMAN), 1, Rules.SHAPE_PORTRAIT, 1, 900, 2, 2);
        }

        // when
        ValidatableResponse response = given().when().get("/boards/chests").then();

        // then
        response.body("entries.nick", contains("Alice", "bob", "carol"));
    }

    @Test
    void shouldListTwentyBest_whenGetRecords_givenTwentyOnePlayers() {
        // given
        IntStream.rangeClosed(1, 21).forEach(score -> run(TestPlayers.createGuest("Player" + score, Rules.KIND_HUMAN),
                1, score, 1000));

        // when
        ValidatableResponse response = given().when().get("/boards/records").then();

        // then
        response.body("entries", hasSize(Rules.BOARD_SIZE))
                .body("entries[0].nick", equalTo("Player21"))
                .body("entries[19]", equalTo(Map.of("rank", 20, "nick", "Player2", "guest", true,
                        "levelsCleared", 1, "score", 2, "ticks", 1000)));
    }

    @Test
    void shouldShowCurrentNickAndAccountMark_whenGetRecords() throws SQLException {
        // given
        String renamed = TestPlayers.createGuest("Before", Rules.KIND_HUMAN);
        String account = TestPlayers.createGuest("Account", Rules.KIND_HUMAN);
        run(renamed, 2, 10, 2000);
        run(account, 1, 10, 2000);
        given().cookie(TestPlayers.COOKIE, renamed).contentType(ContentType.JSON).body(Map.of("nick", "After"))
                .when().patch("/players/me").then().statusCode(200);
        TestSql.execute(dataSource, "update player set email = 'account@example.com' where nick = 'Account'");

        // when
        ValidatableResponse response = given().when().get("/boards/records").then();

        // then
        response.body("entries.nick", contains("After", "Account"))
                .body("entries.guest", contains(true, false));
    }

    @Test
    void shouldReturnEmptyBoards_whenGetBoards_givenNoResults() {
        // when
        ValidatableResponse records = given().when().get("/boards/records").then();
        ValidatableResponse level = given().queryParam("level", 1).queryParam("shape", Rules.SHAPE_PORTRAIT)
                .queryParam("revision", 1).when().get("/boards/level").then();
        ValidatableResponse chests = given().when().get("/boards/chests").then();

        // then
        records.statusCode(200).body("entries", empty());
        level.statusCode(200).body("fastest", empty()).body("chests", empty());
        chests.statusCode(200).body("entries", empty());
    }

    @Test
    void shouldAnswerBareInternalServerError_whenGetRecords_givenDatabaseError() throws SQLException {
        // given
        TestSql.execute(dataSource, "alter table game_run rename to game_run_hidden");

        // when
        ValidatableResponse response;
        try {
            response = given().when().get("/boards/records").then();
        } finally {
            TestSql.execute(dataSource, "alter table game_run_hidden rename to game_run");
        }

        // then
        response.statusCode(500)
                .header("Cache-Control", "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'")
                .body(equalTo("{\"error\":\"internal_server_error\"}"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/boards/records", "/boards/chests",
            "/boards/level?level=1&shape=portrait&revision=1"})
    void shouldRejectKind_whenGetBoard_givenUnknownKind(String path) {
        // when
        ValidatableResponse response = given().queryParam("kind", "robot").when().get(path).then();

        // then
        response.statusCode(400).body("error", equalTo(ApiException.INVALID_KIND));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "shape=portrait&revision=1",
            "level=0&shape=portrait&revision=1",
            "level=6&shape=portrait&revision=1",
            "level=one&shape=portrait&revision=1",
            "level=1&revision=1",
            "level=1&shape=square&revision=1",
            "level=1&shape=portrait",
            "level=1&shape=portrait&revision=0",
            "level=1&shape=portrait&revision=1001",
            "level=1&shape=portrait&revision=1.5",
            "level=1&shape=portrait&revision=99999999999"})
    void shouldRejectQuery_whenGetLevel_givenMissingOrInvalidKey(String query) {
        // when
        ValidatableResponse response = given().when().get("/boards/level?" + query).then();

        // then
        response.statusCode(400).body("error", equalTo(ApiException.INVALID_QUERY));
    }

    private static void run(String token, int levelsCleared, int score, int ticks) {
        given().cookie(TestPlayers.COOKIE, token).contentType(ContentType.JSON)
                .body(Map.of("levelsCleared", levelsCleared, "score", score, "ticks", ticks))
                .when().post("/runs")
                .then().statusCode(201);
    }

    private static void clear(String token, int level, String shape, int revision, int ticks, int chests,
                              int chestsTotal) {
        given().cookie(TestPlayers.COOKIE, token).contentType(ContentType.JSON)
                .body(Map.of("level", level, "shape", shape, "revision", revision, "ticks", ticks,
                        "chests", chests, "chestsTotal", chestsTotal))
                .when().post("/clears")
                .then().statusCode(201);
    }
}
