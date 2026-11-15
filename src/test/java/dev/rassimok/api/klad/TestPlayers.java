package dev.rassimok.api.klad;

import io.restassured.http.ContentType;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;

final class TestPlayers {

    static final String COOKIE = "klad_session";
    static final String PLAYER_ROW = """
            select id, nick, nick_key, kind, email, created_at, last_seen_at from player where id = ?
            """;

    private TestPlayers() {
    }

    // The limit of codes per email would otherwise tie tests together.
    static String uniqueEmail(String name) {
        return name + "-" + UUID.randomUUID() + "@example.com";
    }

    // Guests may share a nick; a unique one lets a test find its own player
    // in a list that other tests fill too. The prefix has at most 8 letters.
    static String uniqueNick(String prefix) {
        return prefix + UUID.randomUUID().toString().substring(0, 8);
    }

    static String createGuest(String nick, String kind) {
        return given()
                .contentType(ContentType.JSON)
                .body(Map.of("nick", nick, "kind", kind))
                .when().post("/players")
                .then().statusCode(201)
                .extract().cookie(COOKIE);
    }

    // An account written straight into the table, as another instance would.
    static void insertAccount(Connection connection, String nick, String email) throws SQLException {
        TestSql.execute(connection, """
                insert into player (id, nick, nick_key, kind, email, created_at, last_seen_at)
                values (gen_random_uuid(), ?, ?, 'human', ?, now(), now())
                """, nick, Nicks.key(nick), email);
    }

    // Players, sessions and results written straight into the tables with
    // times of the test's choosing, all of the agent kind.
    static UUID insertGuest(DataSource dataSource, String nick, Instant lastSeenAt) throws SQLException {
        UUID id = UUID.randomUUID();
        TestSql.execute(dataSource, """
                insert into player (id, nick, nick_key, kind, email, created_at, last_seen_at)
                values (?, ?, ?, 'agent', null, ?, ?)
                """, id, nick, Nicks.key(nick), utc(lastSeenAt), utc(lastSeenAt));
        return id;
    }

    static UUID insertAccount(DataSource dataSource, String nick, String email, Instant lastSeenAt)
            throws SQLException {
        UUID id = UUID.randomUUID();
        TestSql.execute(dataSource, """
                insert into player (id, nick, nick_key, kind, email, created_at, last_seen_at)
                values (?, ?, ?, 'agent', ?, ?, ?)
                """, id, nick, Nicks.key(nick), email, utc(lastSeenAt), utc(lastSeenAt));
        return id;
    }

    static String insertSession(DataSource dataSource, UUID playerId, Instant expiresAt) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return insertSession(connection, playerId, expiresAt);
        }
    }

    static String insertSession(Connection connection, UUID playerId, Instant expiresAt) throws SQLException {
        String token = UUID.randomUUID().toString();
        TestSql.execute(connection, """
                insert into session (token_hash, player_id, created_at, expires_at) values (?, ?, ?, ?)
                """, Sessions.hash(token), playerId, utc(expiresAt.minus(Sessions.LIFETIME)), utc(expiresAt));
        return token;
    }

    static void insertRun(DataSource dataSource, UUID playerId, Instant finishedAt) throws SQLException {
        TestSql.execute(dataSource, """
                insert into game_run (player_id, levels_cleared, score, ticks_total, finished_at)
                values (?, 3, 500, 3000, ?)
                """, playerId, utc(finishedAt));
    }

    static void insertClear(DataSource dataSource, UUID playerId, Instant createdAt) throws SQLException {
        TestSql.execute(dataSource, """
                insert into level_clear (player_id, level, shape, revision, ticks, chests, chests_total, created_at)
                values (?, 1, 'landscape', ?, 900, 2, 5, ?)
                """, playerId, Rules.currentRevision(1), utc(createdAt));
    }

    static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
