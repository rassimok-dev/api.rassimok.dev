package dev.rassimok.api.klad;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class ResultsResourceTest {

    private static final Map<String, Object> CLEAR = Map.of("level", 2, "shape", Rules.SHAPE_PORTRAIT,
            "revision", Rules.currentRevision(2), "ticks", 1500, "chests", 4, "chestsTotal", 7);
    private static final Map<String, Object> RUN = Map.of("levelsCleared", 2, "score", 4200, "ticks", 9000);

    @Inject
    DataSource dataSource;

    @Test
    void shouldStoreClear_whenSubmitClear_givenValidClear() throws SQLException {
        // given
        String token = TestPlayers.createGuest("Clearer", Rules.KIND_HUMAN);

        // when
        ValidatableResponse response = submit("/clears", token, CLEAR);

        // then
        response.statusCode(201).body("", equalTo(Map.of()));
        List<Object> stored = TestSql.row(dataSource, """
                select level, shape, revision, ticks, chests, chests_total, now() - created_at < interval '1 minute'
                from level_clear where player_id = ?
                """, playerId(token));
        assertThat(stored, equalTo(List.of(2, Rules.SHAPE_PORTRAIT, Rules.currentRevision(2), 1500, 4, 7, true)));
    }

    @Test
    void shouldStoreClearAndResendCookie_whenSubmitClear_givenSessionOlderThanADay() throws SQLException {
        // given
        String token = TestPlayers.createGuest("Returning", Rules.KIND_HUMAN);
        TestSql.execute(dataSource, "update session set expires_at = now() + interval '88 days' where token_hash = ?",
                Sessions.hash(token));

        // when
        ValidatableResponse response = submit("/clears", token, CLEAR);

        // then
        response.statusCode(201).header("Set-Cookie", equalTo(Sessions.cookie(token)));
        assertThat(TestSql.count(dataSource, "select count(*) from level_clear where player_id = ?", playerId(token)),
                equalTo(1L));
    }

    @Test
    void shouldStoreRun_whenSubmitRun_givenValidRun() throws SQLException {
        // given
        String token = TestPlayers.createGuest("Runner", Rules.KIND_AGENT);

        // when
        ValidatableResponse response = submit("/runs", token, RUN);

        // then
        response.statusCode(201).body("", equalTo(Map.of()));
        List<Object> stored = TestSql.row(dataSource, """
                select levels_cleared, score, ticks_total, now() - finished_at < interval '1 minute'
                from game_run where player_id = ?
                """, playerId(token));
        assertThat(stored, equalTo(List.of(2, 4200, 9000, true)));
    }

    // %1$d is the current revision of level 1, %2$d the one after it.
    static Stream<String> invalidClears() {
        int revision = Rules.currentRevision(1);
        return Stream.of(
                "{\"level\":6,\"shape\":\"portrait\",\"revision\":%1$d,\"ticks\":900,\"chests\":0,\"chestsTotal\":1}",
                "{\"level\":1,\"shape\":\"square\",\"revision\":%1$d,\"ticks\":900,\"chests\":0,\"chestsTotal\":1}",
                "{\"level\":1,\"shape\":\"portrait\",\"revision\":%2$d,\"ticks\":900,\"chests\":0,\"chestsTotal\":1}",
                "{\"level\":1,\"shape\":\"portrait\",\"revision\":0,\"ticks\":900,\"chests\":0,\"chestsTotal\":1}",
                "{\"level\":1,\"shape\":\"portrait\",\"revision\":%1$d,\"ticks\":599,\"chests\":0,\"chestsTotal\":1}",
                "{\"level\":1,\"shape\":\"portrait\",\"revision\":%1$d,\"ticks\":900,\"chests\":3,\"chestsTotal\":2}",
                "{\"level\":1,\"shape\":\"portrait\",\"revision\":%1$d,\"ticks\":900,\"chestsTotal\":1}",
                "{\"level\":true,\"shape\":\"portrait\",\"revision\":%1$d,\"ticks\":900,\"chests\":0,\"chestsTotal\":1}",
                "{\"level\":1,\"shape\":\"portrait\",\"revision\":%1$d,\"ticks\":99999999999,\"chests\":0,\"chestsTotal\":1}",
                "{\"level\":1,\"shape\":\"portrait\",\"revision\":%1$d,\"ticks\":900.5,\"chests\":0,\"chestsTotal\":1}",
                "[]",
                "")
                .map(body -> body.formatted(revision, revision + 1));
    }

    @ParameterizedTest
    @MethodSource("invalidClears")
    void shouldRejectAndStoreNothing_whenSubmitClear_givenInvalidClear(String body) throws SQLException {
        // given
        String token = TestPlayers.createGuest("Cheater", Rules.KIND_HUMAN);

        // when
        ValidatableResponse response = given().cookie(TestPlayers.COOKIE, token).contentType(ContentType.JSON)
                .body(body).when().post("/clears").then();

        // then
        response.statusCode(400).body("error", equalTo(ApiException.INVALID_RESULT));
        assertThat(TestSql.count(dataSource, "select count(*) from level_clear where player_id = ?", playerId(token)),
                equalTo(0L));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"levelsCleared\":6,\"score\":0,\"ticks\":4000}",
            "{\"levelsCleared\":1,\"score\":10000001,\"ticks\":4000}",
            "{\"levelsCleared\":1,\"score\":-1,\"ticks\":4000}",
            "{\"levelsCleared\":5,\"score\":100,\"ticks\":2999}",
            "{\"levelsCleared\":0,\"score\":0,\"ticks\":2160001}",
            "{\"levelsCleared\":1,\"score\":100}",
            "{\"levelsCleared\":\"many\",\"score\":100,\"ticks\":4000}",
            "{\"levelsCleared\":\"1\",\"score\":100,\"ticks\":4000}",
            "{\"levelsCleared\":1,\"score\":1e3,\"ticks\":4000}"})
    void shouldRejectAndStoreNothing_whenSubmitRun_givenInvalidRun(String body) throws SQLException {
        // given
        String token = TestPlayers.createGuest("Cheater", Rules.KIND_HUMAN);

        // when
        ValidatableResponse response = given().cookie(TestPlayers.COOKIE, token).contentType(ContentType.JSON)
                .body(body).when().post("/runs").then();

        // then
        response.statusCode(400).body("error", equalTo(ApiException.INVALID_RESULT));
        assertThat(TestSql.count(dataSource, "select count(*) from game_run where player_id = ?", playerId(token)),
                equalTo(0L));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/clears", "/runs"})
    void shouldRefuse_whenSubmit_givenNoSession(String path) {
        // when
        ValidatableResponse response = given().contentType(ContentType.JSON).body(CLEAR).when().post(path).then();

        // then
        response.statusCode(401).body("error", equalTo(ApiException.NO_SESSION));
    }

    @Test
    void shouldRefuseThirtyFirst_whenSubmit_givenThirtySubmissionsInTheLastMinute() throws SQLException {
        // given
        String token = TestPlayers.createGuest("Spammer", Rules.KIND_HUMAN);
        for (int i = 0; i < 15; i++) {
            submit("/clears", token, CLEAR).statusCode(201);
            submit("/runs", token, RUN).statusCode(201);
        }

        // when
        ValidatableResponse clear = submit("/clears", token, CLEAR);
        ValidatableResponse run = submit("/runs", token, RUN);

        // then
        clear.statusCode(429).body("error", equalTo(ApiException.RATE_LIMITED));
        run.statusCode(429).body("error", equalTo(ApiException.RATE_LIMITED));
        assertThat(storedSubmissions(playerId(token)), equalTo(30L));
    }

    @Test
    void shouldAcceptOnlyThirty_whenSubmitClear_givenFortyParallelClears() throws InterruptedException, SQLException {
        // given
        String token = TestPlayers.createGuest("Burst", Rules.KIND_HUMAN);
        List<Callable<Integer>> clears = Collections.nCopies(40,
                () -> submit("/clears", token, CLEAR).extract().statusCode());

        // when
        Map<Integer, Long> statusCounts;
        try (ExecutorService executor = Executors.newFixedThreadPool(clears.size())) {
            statusCounts = executor.invokeAll(clears).stream()
                    .collect(Collectors.groupingBy(Future::resultNow, Collectors.counting()));
        }

        // then
        assertThat(statusCounts, equalTo(Map.of(201, 30L, 429, 10L)));
        assertThat(storedSubmissions(playerId(token)), equalTo(30L));
    }

    @Test
    void shouldAccept_whenSubmit_givenThirtySubmissionsOlderThanAMinute() throws SQLException {
        // given
        String token = TestPlayers.createGuest("Patient", Rules.KIND_HUMAN);
        for (int i = 0; i < 30; i++) {
            submit("/clears", token, CLEAR).statusCode(201);
        }
        TestSql.execute(dataSource,
                "update level_clear set created_at = created_at - interval '61 seconds' where player_id = ?",
                playerId(token));

        // when
        ValidatableResponse response = submit("/runs", token, RUN);

        // then
        response.statusCode(201);
    }

    @Test
    void shouldCountEachPlayerApart_whenSubmit_givenAnotherPlayerAtTheLimit() {
        // given
        String busy = TestPlayers.createGuest("Busy", Rules.KIND_HUMAN);
        String calm = TestPlayers.createGuest("Calm", Rules.KIND_HUMAN);
        for (int i = 0; i < 30; i++) {
            submit("/runs", busy, RUN).statusCode(201);
        }

        // when
        ValidatableResponse response = submit("/runs", calm, RUN);

        // then
        response.statusCode(201);
    }

    private static ValidatableResponse submit(String path, String token, Map<String, Object> body) {
        return given().cookie(TestPlayers.COOKIE, token).contentType(ContentType.JSON)
                .body(body).when().post(path).then();
    }

    private UUID playerId(String token) throws SQLException {
        return (UUID) TestSql.row(dataSource, "select player_id from session where token_hash = ?",
                Sessions.hash(token)).getFirst();
    }

    private long storedSubmissions(UUID playerId) throws SQLException {
        return TestSql.count(dataSource, """
                select (select count(*) from level_clear where player_id = ?)
                     + (select count(*) from game_run where player_id = ?)
                """, playerId, playerId);
    }
}
