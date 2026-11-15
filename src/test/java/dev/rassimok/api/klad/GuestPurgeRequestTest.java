package dev.rassimok.api.klad;

import io.quarkus.test.common.DevServicesContext;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;

/**
 * The purge as a request carries it. Its own profile starts a fresh
 * application, whose first request carries the purge. HTTP and plain JDBC
 * only, so the native integration test can run it against the binary.
 */
@QuarkusTest
@TestProfile(GuestPurgeRequestTest.FreshPurge.class)
class GuestPurgeRequestTest {

    public static class FreshPurge implements QuarkusTestProfile {
    }

    static final String PURGE_LINE = "Guest purge deleted";
    private static final int PARALLEL_REQUESTS = 10;

    DevServicesContext devServices;

    private final List<String> records = new CopyOnWriteArrayList<>();
    private final Handler collector = new Handler() {
        @Override
        public void publish(LogRecord record) {
            records.add(record.getMessage() + " " + Arrays.toString(record.getParameters()));
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    @BeforeEach
    void collectLog() {
        Logger.getLogger("").addHandler(collector);
    }

    @AfterEach
    void stopCollectingLog() {
        Logger.getLogger("").removeHandler(collector);
    }

    @Test
    void shouldPurgeOnce_whenGetBoards_givenParallelFirstRequests() throws Exception {
        // given
        DataSource database = TestSql.devServicesDatabase(devServices);
        Instant now = Instant.now();
        Instant expiredLastSeen = now.minus(Sessions.LIFETIME).minus(Duration.ofDays(1));
        String expiredNick = TestPlayers.uniqueNick("Gone");
        UUID expiredGuest = TestPlayers.insertGuest(database, expiredNick, expiredLastSeen);
        TestPlayers.insertSession(database, expiredGuest, now.minus(Duration.ofDays(1)));
        TestPlayers.insertRun(database, expiredGuest, expiredLastSeen);
        UUID account = TestPlayers.insertAccount(database, TestPlayers.uniqueNick("Away"),
                TestPlayers.uniqueEmail("purge-request"), now.minus(Duration.ofDays(200)));
        TestPlayers.insertSession(database, account, now.minus(Duration.ofDays(1)));
        long purgeLinesBefore = purgeLines(loggedLines());

        // when
        List<Integer> statuses = boardsInParallel();
        UUID laterGuest = TestPlayers.insertGuest(database, TestPlayers.uniqueNick("Later"), expiredLastSeen);
        TestPlayers.insertSession(database, laterGuest, now.minus(Duration.ofDays(1)));
        int laterStatus = given().queryParam("kind", Rules.KIND_AGENT).when().get("/boards/records")
                .then().extract().statusCode();

        // then
        assertThat(statuses, everyItem(equalTo(200)));
        assertThat(laterStatus, equalTo(200));
        assertThat(TestSql.count(database, """
                select (select count(*) from player where id = ?)
                     + (select count(*) from session where player_id = ?)
                     + (select count(*) from game_run where player_id = ?)
                """, expiredGuest, expiredGuest, expiredGuest), equalTo(0L));
        assertThat(TestSql.row(database, """
                select (select count(*) from player where id = ?), (select count(*) from session where player_id = ?)
                """, account, account), equalTo(List.of(1L, 0L)));
        assertThat(TestSql.count(database, "select count(*) from player where id = ?", laterGuest), equalTo(1L));
        List<String> lines = loggedLines();
        assertThat(purgeLines(lines) - purgeLinesBefore, equalTo(1L));
        assertThat(lines.stream().filter(line -> line.contains(expiredGuest.toString())
                || line.contains(expiredNick)).toList(), empty());
    }

    // The native test reads the binary's log instead of the records
    // collected here.
    protected List<String> loggedLines() throws Exception {
        return List.copyOf(records);
    }

    private static long purgeLines(List<String> lines) {
        return lines.stream().filter(line -> line.contains(PURGE_LINE)).count();
    }

    private static List<Integer> boardsInParallel() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> answers = new ArrayList<>();
        try (ExecutorService callers = Executors.newFixedThreadPool(PARALLEL_REQUESTS)) {
            for (int i = 0; i < PARALLEL_REQUESTS; i++) {
                answers.add(callers.submit(() -> {
                    start.await();
                    return given().queryParam("kind", Rules.KIND_AGENT).when().get("/boards/records")
                            .then().extract().statusCode();
                }));
            }
            start.countDown();
        }
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> answer : answers) {
            statuses.add(answer.get());
        }
        return statuses;
    }
}
