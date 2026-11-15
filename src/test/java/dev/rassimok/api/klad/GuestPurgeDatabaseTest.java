package dev.rassimok.api.klad;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

/**
 * The purge's selection against Postgres, at a purge time of the test's
 * choosing. Every test starts from an empty database, as the boards read all
 * players; its own profile gives it a database of its own, so emptying it
 * takes no players from other test classes (the shared race players). JVM
 * only: it calls the bean.
 */
@QuarkusTest
@TestProfile(GuestPurgeDatabaseTest.OwnDatabase.class)
class GuestPurgeDatabaseTest {

    public static class OwnDatabase implements QuarkusTestProfile {
    }

    private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");
    private static final Duration SECOND = Duration.ofSeconds(1);
    private static final Instant SESSION_EXPIRED_AT_NOW = NOW;
    private static final Instant LAST_SEEN_FOR_EXPIRY_AT_NOW = NOW.minus(Sessions.LIFETIME);
    private static final String SESSION_ROWS = """
            select token_hash, player_id, created_at, expires_at from session where player_id = ? order by token_hash
            """;
    private static final String RESULT_ROWS = """
            select 'run', id, levels_cleared, score, ticks_total, finished_at from game_run where player_id = ?
            union all
            select 'clear', id, level, chests, ticks, created_at from level_clear where player_id = ?
            order by 1, 2
            """;
    private static final String ROWS_OF_PLAYER = """
            select (select count(*) from player where id = ?)
                 + (select count(*) from session where player_id = ?)
                 + (select count(*) from level_clear where player_id = ?)
                 + (select count(*) from game_run where player_id = ?)
            """;

    @Inject
    DataSource dataSource;

    @Inject
    GuestPurge guestPurge;

    @Inject
    PresenceStore presence;

    @Inject
    RoomStore rooms;

    // The first request of the application carries the day's purge at the
    // real time; made here, it cannot run in the middle of a test.
    @BeforeEach
    void startFromEmptyDatabase() throws SQLException {
        given().when().get("/status").then().statusCode(200);
        TestSql.execute(dataSource, "delete from player");
    }

    @Test
    void shouldDeleteGuestWithSessionAndResults_whenPurge_givenGuestWhoseSessionExpired() throws SQLException {
        // given
        UUID guest = TestPlayers.insertGuest(dataSource, "Expired",
                LAST_SEEN_FOR_EXPIRY_AT_NOW.minus(Duration.ofDays(1)));
        TestPlayers.insertSession(dataSource, guest, SESSION_EXPIRED_AT_NOW.minus(Duration.ofDays(1)));
        TestPlayers.insertRun(dataSource, guest, NOW.minus(Duration.ofDays(100)));
        TestPlayers.insertClear(dataSource, guest, NOW.minus(Duration.ofDays(100)));

        // when
        GuestPurge.Purged purged = guestPurge.purge(NOW);

        // then
        assertThat(purged, equalTo(new GuestPurge.Purged(List.of(guest), 0)));
        assertThat(rowsOf(guest), equalTo(0L));
    }

    @Test
    void shouldDeleteGuest_whenPurge_givenGuestCreatedByRequestWhoseSessionExpiredAnHourAgo() throws SQLException {
        // given
        String token = TestPlayers.createGuest("Requested", Rules.KIND_AGENT);
        Instant now = Instant.now();
        TestSql.execute(dataSource, "update session set expires_at = ? where token_hash = ?",
                TestPlayers.utc(now.minus(Duration.ofHours(1))), Sessions.hash(token));
        UUID guest = (UUID) TestSql.row(dataSource, "select player_id from session where token_hash = ?",
                Sessions.hash(token)).getFirst();

        // when
        GuestPurge.Purged purged = guestPurge.purge(now);

        // then
        assertThat(purged, equalTo(new GuestPurge.Purged(List.of(guest), 0)));
        assertThat(rowsOf(guest), equalTo(0L));
    }

    @Test
    void shouldDeleteGuest_whenPurge_givenSessionExpiringAtPurgeTime() throws SQLException {
        // given
        UUID guest = TestPlayers.insertGuest(dataSource, "Boundary", LAST_SEEN_FOR_EXPIRY_AT_NOW);
        TestPlayers.insertSession(dataSource, guest, SESSION_EXPIRED_AT_NOW);

        // when
        GuestPurge.Purged purged = guestPurge.purge(NOW);

        // then
        assertThat(purged, equalTo(new GuestPurge.Purged(List.of(guest), 0)));
        assertThat(rowsOf(guest), equalTo(0L));
    }

    @Test
    void shouldKeepGuest_whenPurge_givenSessionValidForOneMoreSecond() throws SQLException {
        // given
        UUID guest = TestPlayers.insertGuest(dataSource, "StillValid", LAST_SEEN_FOR_EXPIRY_AT_NOW.plus(SECOND));
        TestPlayers.insertSession(dataSource, guest, SESSION_EXPIRED_AT_NOW.plus(SECOND));
        TestPlayers.insertRun(dataSource, guest, NOW.minus(Duration.ofDays(10)));
        List<List<Object>> before = rowsOfPlayer(guest);

        // when
        GuestPurge.Purged purged = guestPurge.purge(NOW);

        // then
        assertThat(purged, equalTo(new GuestPurge.Purged(List.of(), 0)));
        assertThat(rowsOfPlayer(guest), equalTo(before));
    }

    @Test
    void shouldKeepGuest_whenPurge_givenOldGuestWithValidSession() throws SQLException {
        // given
        UUID guest = TestPlayers.insertGuest(dataSource, "Inconsistent", NOW.minus(Duration.ofDays(400)));
        TestPlayers.insertSession(dataSource, guest, NOW.plus(Duration.ofDays(1)));
        List<List<Object>> before = rowsOfPlayer(guest);

        // when
        GuestPurge.Purged purged = guestPurge.purge(NOW);

        // then
        assertThat(purged, equalTo(new GuestPurge.Purged(List.of(), 0)));
        assertThat(rowsOfPlayer(guest), equalTo(before));
    }

    @Test
    void shouldDeleteGuest_whenPurge_givenGuestWithoutSessionLastSeen90DaysAgo() throws SQLException {
        // given
        UUID guest = TestPlayers.insertGuest(dataSource, "LoggedOut", LAST_SEEN_FOR_EXPIRY_AT_NOW);
        TestPlayers.insertRun(dataSource, guest, NOW.minus(Duration.ofDays(100)));

        // when
        GuestPurge.Purged purged = guestPurge.purge(NOW);

        // then
        assertThat(purged, equalTo(new GuestPurge.Purged(List.of(guest), 0)));
        assertThat(rowsOf(guest), equalTo(0L));
    }

    @Test
    void shouldKeepGuest_whenPurge_givenGuestWithoutSessionLastSeenUnder90DaysAgo() throws SQLException {
        // given
        UUID guest = TestPlayers.insertGuest(dataSource, "OtherKind", LAST_SEEN_FOR_EXPIRY_AT_NOW.plus(SECOND));
        TestPlayers.insertRun(dataSource, guest, NOW.minus(Duration.ofDays(10)));
        List<List<Object>> before = rowsOfPlayer(guest);

        // when
        GuestPurge.Purged purged = guestPurge.purge(NOW);

        // then
        assertThat(purged, equalTo(new GuestPurge.Purged(List.of(), 0)));
        assertThat(rowsOfPlayer(guest), equalTo(before));
    }

    @Test
    void shouldKeepAccountAndResults_whenPurge_givenAccountWithOnlyExpiredSessions() throws SQLException {
        // given
        UUID account = TestPlayers.insertAccount(dataSource, "Away", TestPlayers.uniqueEmail("away"),
                NOW.minus(Duration.ofDays(400)));
        TestPlayers.insertSession(dataSource, account, NOW.minus(Duration.ofDays(310)));
        TestPlayers.insertSession(dataSource, account, SESSION_EXPIRED_AT_NOW);
        TestPlayers.insertRun(dataSource, account, NOW.minus(Duration.ofDays(400)));
        TestPlayers.insertClear(dataSource, account, NOW.minus(Duration.ofDays(400)));
        List<Object> player = TestSql.row(dataSource, TestPlayers.PLAYER_ROW, account);
        List<List<Object>> results = TestSql.rows(dataSource, RESULT_ROWS, account, account);

        // when
        GuestPurge.Purged purged = guestPurge.purge(NOW);

        // then
        assertThat(purged, equalTo(new GuestPurge.Purged(List.of(), 2)));
        assertThat(TestSql.row(dataSource, TestPlayers.PLAYER_ROW, account), equalTo(player));
        assertThat(TestSql.rows(dataSource, RESULT_ROWS, account, account), equalTo(results));
        assertThat(TestSql.rows(dataSource, SESSION_ROWS, account), empty());
    }

    @Test
    void shouldDeleteOnlyExpiredSession_whenPurge_givenAccountWithValidAndExpiredSession() throws SQLException {
        // given
        UUID account = TestPlayers.insertAccount(dataSource, "TwoDevices", TestPlayers.uniqueEmail("two-devices"),
                NOW.minus(Duration.ofDays(10)));
        String expired = TestPlayers.insertSession(dataSource, account, NOW.minus(Duration.ofDays(5)));
        String valid = TestPlayers.insertSession(dataSource, account, NOW.plus(SECOND));
        List<Object> player = TestSql.row(dataSource, TestPlayers.PLAYER_ROW, account);
        List<Object> validSession = TestSql.row(dataSource,
                "select token_hash, player_id, created_at, expires_at from session where token_hash = ?",
                Sessions.hash(valid));

        // when
        GuestPurge.Purged purged = guestPurge.purge(NOW);

        // then
        assertThat(purged, equalTo(new GuestPurge.Purged(List.of(), 1)));
        assertThat(TestSql.row(dataSource, TestPlayers.PLAYER_ROW, account), equalTo(player));
        assertThat(TestSql.rows(dataSource, SESSION_ROWS, account), equalTo(List.of(validSession)));
        assertThat(TestSql.count(dataSource, "select count(*) from session where token_hash = ?",
                Sessions.hash(expired)), equalTo(0L));
    }

    @Test
    void shouldListOnlyRemainingPlayers_whenGetBoards_givenPurgedGuest() throws SQLException {
        // given
        UUID gone = TestPlayers.insertGuest(dataSource, "Gone", LAST_SEEN_FOR_EXPIRY_AT_NOW.minus(SECOND));
        TestPlayers.insertSession(dataSource, gone, SESSION_EXPIRED_AT_NOW.minus(SECOND));
        TestPlayers.insertRun(dataSource, gone, NOW.minus(Duration.ofDays(100)));
        TestPlayers.insertClear(dataSource, gone, NOW.minus(Duration.ofDays(100)));
        UUID staying = TestPlayers.insertGuest(dataSource, "Staying", NOW.minus(Duration.ofDays(1)));
        TestPlayers.insertSession(dataSource, staying, NOW.plus(Duration.ofDays(89)));
        TestPlayers.insertRun(dataSource, staying, NOW.minus(Duration.ofDays(1)));
        TestPlayers.insertClear(dataSource, staying, NOW.minus(Duration.ofDays(1)));
        records().body("entries.nick", contains("Gone", "Staying"));

        // when
        guestPurge.purge(NOW);

        // then
        records().statusCode(200).body("entries.nick", contains("Staying"));
        level().statusCode(200)
                .body("fastest.nick", contains("Staying"))
                .body("chests.nick", contains("Staying"));
        chests().statusCode(200).body("entries.nick", contains("Staying"));
    }

    @Test
    void shouldForgetPurgedGuest_whenPurge_givenGuestStillInPresenceAndRoom() throws SQLException {
        // given
        String nick = TestPlayers.uniqueNick("Lingers");
        UUID guest = TestPlayers.insertGuest(dataSource, nick, LAST_SEEN_FOR_EXPIRY_AT_NOW.minus(Duration.ofDays(1)));
        LobbyPlayer lingering = new LobbyPlayer(guest, nick, Rules.KIND_AGENT, true);
        presence.heartbeat(lingering);
        String roomId = rooms.create(lingering);

        // when
        guestPurge.purge(NOW);

        // then
        assertThat(presence.online().players().stream().map(PresenceStore.OnlinePlayer::nick).toList(),
                not(hasItem(nick)));
        assertThat(rooms.list().rooms().stream().map(RoomListView.ListedRoom::id).toList(), not(hasItem(roomId)));
    }

    // The guest left over keeps its expired session for the next run.
    @Test
    void shouldDeleteOneBatchOfGuests_whenPurge_givenMoreExpiredGuestsThanBatch() throws SQLException {
        // given
        insertGuestsExpiringAtNow(Rules.PURGE_BATCH + 1);

        // when
        GuestPurge.Purged firstRun = guestPurge.purge(NOW);
        List<Object> leftOver = TestSql.row(dataSource, "select count(*), (select count(*) from session) from player");
        GuestPurge.Purged secondRun = guestPurge.purge(NOW);

        // then
        assertThat(List.of(firstRun.guestIds().size(), firstRun.expiredSessions()),
                equalTo(List.of(Rules.PURGE_BATCH, 0)));
        assertThat(leftOver, equalTo(List.of(1L, 1L)));
        assertThat(List.of(secondRun.guestIds().size(), secondRun.expiredSessions()), equalTo(List.of(1, 0)));
        assertThat(TestSql.count(dataSource, "select count(*) from player"), equalTo(0L));
    }

    @Test
    void shouldDeleteOneBatchOfSessions_whenPurge_givenMoreExpiredSessionsThanBatch() throws SQLException {
        // given
        UUID account = TestPlayers.insertAccount(dataSource, "ManyDevices", TestPlayers.uniqueEmail("many-devices"),
                NOW.minus(Duration.ofDays(100)));
        TestSql.execute(dataSource, """
                insert into session (token_hash, player_id, created_at, expires_at)
                select md5(n::text), ?, ?, ? from generate_series(1, ?) as n
                """, account, TestPlayers.utc(NOW.minus(Sessions.LIFETIME)),
                TestPlayers.utc(SESSION_EXPIRED_AT_NOW), Rules.PURGE_BATCH + 1);

        // when
        int firstRun = guestPurge.purge(NOW).expiredSessions();
        int secondRun = guestPurge.purge(NOW).expiredSessions();

        // then
        assertThat(List.of(firstRun, secondRun), equalTo(List.of(Rules.PURGE_BATCH, 1)));
        assertThat(TestSql.count(dataSource, "select count(*) from player where id = ?", account), equalTo(1L));
    }

    // Inserted last, so a scan in table order would reach it last.
    @Test
    void shouldDeleteOldestGuestFirst_whenPurge_givenMoreExpiredGuestsThanBatch() throws SQLException {
        // given
        insertGuestsExpiringAtNow(Rules.PURGE_BATCH);
        UUID oldest = TestPlayers.insertGuest(dataSource, "Oldest",
                LAST_SEEN_FOR_EXPIRY_AT_NOW.minus(Duration.ofDays(30)));
        TestPlayers.insertSession(dataSource, oldest, SESSION_EXPIRED_AT_NOW.minus(Duration.ofDays(30)));

        // when
        GuestPurge.Purged purged = guestPurge.purge(NOW);

        // then
        assertThat(purged.guestIds(), hasItem(oldest));
        assertThat(TestSql.count(dataSource, "select count(*) from player"), equalTo(1L));
    }

    // The other connection does what POST /login/verify does with a guest in
    // its session's last moment; the purge has picked the guest and waits.
    @Test
    void shouldKeepAccount_whenPurge_givenGuestTurnedIntoAccountWhilePurgeWaits() throws Exception {
        // given
        UUID guest = TestPlayers.insertGuest(dataSource, "LastMoment", LAST_SEEN_FOR_EXPIRY_AT_NOW);
        String oldToken = TestPlayers.insertSession(dataSource, guest, SESSION_EXPIRED_AT_NOW);
        TestPlayers.insertRun(dataSource, guest, NOW.minus(Duration.ofDays(100)));
        TestPlayers.insertClear(dataSource, guest, NOW.minus(Duration.ofDays(100)));
        List<List<Object>> expected = new ArrayList<>();

        // when
        long lockWaiters;
        GuestPurge.Purged purged;
        try (ExecutorService executor = Executors.newSingleThreadExecutor();
             Connection verifier = dataSource.getConnection()) {
            verifier.setAutoCommit(false);
            TestSql.execute(verifier, "update player set email = ?, last_seen_at = ? where id = ?",
                    TestPlayers.uniqueEmail("last-moment"), TestPlayers.utc(NOW), guest);
            TestSql.execute(verifier, "delete from session where token_hash = ?", Sessions.hash(oldToken));
            TestPlayers.insertSession(verifier, guest, NOW.plus(Sessions.LIFETIME));
            expected.add(TestSql.row(verifier, TestPlayers.PLAYER_ROW, guest));
            expected.add(TestSql.row(verifier, SESSION_ROWS, guest));
            expected.addAll(TestSql.rows(dataSource, RESULT_ROWS, guest, guest));
            Future<GuestPurge.Purged> purge = executor.submit(() -> guestPurge.purge(NOW));
            lockWaiters = TestSql.awaitLockWaiters(dataSource, 1);
            verifier.commit();
            purged = purge.get();
        }

        // then
        assertThat(lockWaiters, equalTo(1L));
        assertThat(purged, equalTo(new GuestPurge.Purged(List.of(), 0)));
        assertThat(rowsOfPlayer(guest), equalTo(expected));
    }

    // The other connection refreshes the account's session in its last
    // moment, as a request does; the purge has picked the session and waits.
    @Test
    void shouldKeepRefreshedSession_whenPurge_givenAccountSessionRefreshedWhilePurgeWaits() throws Exception {
        // given
        UUID account = TestPlayers.insertAccount(dataSource, "Refreshed", TestPlayers.uniqueEmail("refreshed"),
                LAST_SEEN_FOR_EXPIRY_AT_NOW);
        String token = TestPlayers.insertSession(dataSource, account, SESSION_EXPIRED_AT_NOW);
        List<List<Object>> expected = new ArrayList<>();

        // when
        long lockWaiters;
        GuestPurge.Purged purged;
        try (ExecutorService executor = Executors.newSingleThreadExecutor();
             Connection request = dataSource.getConnection()) {
            request.setAutoCommit(false);
            TestSql.execute(request, "update session set expires_at = ? where token_hash = ?",
                    TestPlayers.utc(NOW.plus(Sessions.LIFETIME)), Sessions.hash(token));
            TestSql.execute(request, "update player set last_seen_at = ? where id = ?",
                    TestPlayers.utc(NOW), account);
            expected.add(TestSql.row(request, TestPlayers.PLAYER_ROW, account));
            expected.add(TestSql.row(request, SESSION_ROWS, account));
            Future<GuestPurge.Purged> purge = executor.submit(() -> guestPurge.purge(NOW));
            lockWaiters = TestSql.awaitLockWaiters(dataSource, 1);
            request.commit();
            purged = purge.get();
        }

        // then
        assertThat(lockWaiters, equalTo(1L));
        assertThat(purged, equalTo(new GuestPurge.Purged(List.of(), 0)));
        assertThat(rowsOfPlayer(account), equalTo(expected));
    }

    private long rowsOf(UUID playerId) throws SQLException {
        return TestSql.count(dataSource, ROWS_OF_PLAYER, playerId, playerId, playerId, playerId);
    }

    // Guests of the agent kind whose sessions expire at the purge time.
    private void insertGuestsExpiringAtNow(int count) throws SQLException {
        TestSql.execute(dataSource, """
                with guest as (
                    insert into player (id, nick, nick_key, kind, email, created_at, last_seen_at)
                    select gen_random_uuid(), 'Old' || n, 'old' || n, 'agent', null, ?, ?
                    from generate_series(1, ?) as n
                    returning id)
                insert into session (token_hash, player_id, created_at, expires_at)
                select md5(id::text), id, ?, ? from guest
                """, TestPlayers.utc(LAST_SEEN_FOR_EXPIRY_AT_NOW), TestPlayers.utc(LAST_SEEN_FOR_EXPIRY_AT_NOW), count,
                TestPlayers.utc(LAST_SEEN_FOR_EXPIRY_AT_NOW), TestPlayers.utc(SESSION_EXPIRED_AT_NOW));
    }

    private List<List<Object>> rowsOfPlayer(UUID playerId) throws SQLException {
        List<List<Object>> rows = new ArrayList<>();
        rows.add(TestSql.row(dataSource, TestPlayers.PLAYER_ROW, playerId));
        rows.addAll(TestSql.rows(dataSource, SESSION_ROWS, playerId));
        rows.addAll(TestSql.rows(dataSource, RESULT_ROWS, playerId, playerId));
        return rows;
    }

    private static ValidatableResponse records() {
        return given().queryParam("kind", Rules.KIND_AGENT).when().get("/boards/records").then();
    }

    private static ValidatableResponse level() {
        return given().queryParam("kind", Rules.KIND_AGENT).queryParam("level", 1)
                .queryParam("shape", Rules.SHAPE_LANDSCAPE).when().get("/boards/level").then();
    }

    private static ValidatableResponse chests() {
        return given().queryParam("kind", Rules.KIND_AGENT).when().get("/boards/chests").then();
    }
}
