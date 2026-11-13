package dev.rassimok.api.klad;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Flyway has run V5 on an empty database before the tests start, so these
 * tests run its file again on rows written with the old key, the nick in
 * lower case, in a transaction that is rolled back.
 */
@QuarkusTest
class NickKeyMigrationTest {

    private static final String MIGRATION = "db/migration/V5__recompute_nick_key_as_skeleton.sql";
    private static final String UNIQUE_VIOLATION = "23505";
    private static final String PLAYER_ROW = """
            select id, nick, nick_key, kind, email, created_at, last_seen_at from player where id = ?
            """;

    @Inject
    DataSource dataSource;

    @Test
    void shouldSetSameKeyAsNicks_whenMigrate_givenOldKeyOfEveryNickCharacter() throws SQLException, IOException {
        // given
        List<String> nicks = List.of(everyNickCharacter(), "\u0412\u041E\u0412_35", "B0B_35",
                "\u0401\u0436\u0438\u043A_35");
        List<List<Object>> expected = new ArrayList<>();
        List<List<Object>> migrated = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            List<UUID> ids = new ArrayList<>();
            for (String nick : nicks) {
                UUID id = insertPlayer(connection, nick, null);
                List<Object> row = TestSql.row(connection, PLAYER_ROW, id);
                row.set(2, Nicks.key(nick));
                expected.add(row);
                ids.add(id);
            }

            // when
            TestSql.execute(connection, migration());
            for (UUID id : ids) {
                migrated.add(TestSql.row(connection, PLAYER_ROW, id));
            }
            connection.rollback();
        }

        // then
        assertThat(migrated, equalTo(expected));
    }

    // Different keys before, one key after: the unique index refuses the update.
    @Test
    void shouldRefuseMigration_whenMigrate_givenTwoAccountsWithLookalikeNicks() throws SQLException, IOException {
        // given
        String migration = migration();
        SQLException exception;
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            insertPlayer(connection, "Moxa_35", "latin-moxa@example.com");
            insertPlayer(connection, "\u041C\u041E\u0425\u0410_35", "cyrillic-moxa@example.com");

            // when
            exception = assertThrows(SQLException.class, () -> TestSql.execute(connection, migration));
            connection.rollback();
        }

        // then
        assertThat(exception.getSQLState(), equalTo(UNIQUE_VIOLATION));
    }

    // Every character a nick may hold: the migration and Nicks.key must agree
    // on each of them.
    private static String everyNickCharacter() {
        StringBuilder characters = new StringBuilder(
                "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-\u0401\u0451");
        for (char cyrillic = '\u0410'; '\u044F' >= cyrillic; cyrillic++) {
            characters.append(cyrillic);
        }
        return characters.toString();
    }

    // The key the API wrote before the migration.
    private static UUID insertPlayer(Connection connection, String nick, String email) throws SQLException {
        UUID id = UUID.randomUUID();
        TestSql.execute(connection, """
                insert into player (id, nick, nick_key, kind, email, created_at, last_seen_at)
                values (?, ?, ?, 'human', ?, now(), now())
                """, id, nick, nick.toLowerCase(Locale.ROOT), email);
        return id;
    }

    private static String migration() throws IOException {
        String sql;
        try (InputStream file = Thread.currentThread().getContextClassLoader().getResourceAsStream(MIGRATION)) {
            sql = new String(file.readAllBytes(), StandardCharsets.UTF_8);
        }
        return sql;
    }
}
