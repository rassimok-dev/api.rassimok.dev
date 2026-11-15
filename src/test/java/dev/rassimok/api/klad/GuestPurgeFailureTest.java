package dev.rassimok.api.klad;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * A purge that fails leaves the request that carried it alone. Its own
 * profile starts a fresh application, whose first request carries the purge.
 * JVM only: it reads the log records of the application.
 */
@QuarkusTest
@TestProfile(GuestPurgeFailureTest.FreshPurge.class)
class GuestPurgeFailureTest {

    public static class FreshPurge implements QuarkusTestProfile {
    }

    private static final String FAILURE_LINE = "Guest purge failed";

    @Inject
    DataSource dataSource;

    @Test
    void shouldAnswerAndKeepGuest_whenGetBoards_givenPurgeBlockedPastLockTimeout() throws Exception {
        // given
        Instant now = Instant.now();
        String nick = TestPlayers.uniqueNick("Held");
        UUID guest = TestPlayers.insertGuest(dataSource, nick,
                now.minus(Sessions.LIFETIME).minus(Duration.ofDays(1)));
        TestPlayers.insertSession(dataSource, guest, now.minus(Duration.ofDays(1)));
        List<Object> guestRow = TestSql.row(dataSource, TestPlayers.PLAYER_ROW, guest);
        List<String> records = new CopyOnWriteArrayList<>();
        Handler collector = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(describe(record));
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Logger root = Logger.getLogger("");
        root.addHandler(collector);

        // when
        ValidatableResponse blocked;
        ValidatableResponse later;
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            TestSql.execute(holder, "select 1 from player where id = ? for update", guest);
            blocked = boards();
            holder.rollback();
            later = boards();
        } finally {
            root.removeHandler(collector);
        }

        // then
        blocked.statusCode(200).body("entries", notNullValue());
        later.statusCode(200).body("entries", notNullValue());
        assertThat(TestSql.row(dataSource, TestPlayers.PLAYER_ROW, guest), equalTo(guestRow));
        assertThat(records.stream().filter(record -> record.contains(FAILURE_LINE)).count(), equalTo(1L));
        assertThat(records.stream().filter(record -> record.contains(GuestPurgeRequestTest.PURGE_LINE)).toList(),
                empty());
        assertThat(records.stream().filter(record -> record.contains(guest.toString()) || record.contains(nick))
                .toList(), empty());
    }

    private static ValidatableResponse boards() {
        return given().queryParam("kind", Rules.KIND_AGENT).when().get("/boards/records").then();
    }

    // The message, its parameters and the whole exception chain of a record.
    private static String describe(LogRecord record) {
        StringWriter thrown = new StringWriter();
        if (null != record.getThrown()) {
            record.getThrown().printStackTrace(new PrintWriter(thrown));
        }
        return record.getMessage() + " " + Arrays.toString(record.getParameters()) + " " + thrown;
    }
}
