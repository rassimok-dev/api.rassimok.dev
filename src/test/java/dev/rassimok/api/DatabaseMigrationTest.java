package dev.rassimok.api;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
class DatabaseMigrationTest {

    private static final String CHECK_VIOLATION = "23514";

    @Inject
    DataSource dataSource;

    @Test
    void shouldCreatePlayerAndSessionTables_whenMigrateAtStart() throws SQLException {
        // when
        List<String> tableNames = publicTableNames();

        // then
        assertThat(tableNames, hasItems("player", "session"));
    }

    @Test
    void shouldCreateLoginTables_whenMigrateAtStart() throws SQLException {
        // when
        List<String> tableNames = publicTableNames();

        // then
        assertThat(tableNames, hasItems("login_code", "login_mail"));
    }

    @Test
    void shouldKeyLoginCodeByEmailHashWithoutAddressColumn_whenMigrateAtStart() throws SQLException {
        // when
        List<String> columnNames = strings("""
                select column_name from information_schema.columns
                where table_schema = 'public' and table_name = 'login_code' order by ordinal_position
                """);
        List<String> primaryKey = strings("""
                select key_column.column_name from information_schema.table_constraints table_constraint
                join information_schema.key_column_usage key_column using (constraint_schema, constraint_name)
                where table_constraint.table_name = 'login_code' and table_constraint.constraint_type = 'PRIMARY KEY'
                """);

        // then
        assertThat(columnNames, equalTo(List.of("email_hash", "code_hash", "attempts", "expires_at", "created_at")));
        assertThat(primaryKey, equalTo(List.of("email_hash")));
    }

    @Test
    void shouldRejectPlayer_whenInsert_givenUnknownKind() {
        // given
        String insert = """
                insert into player (id, nick, nick_key, kind, created_at, last_seen_at)
                values (gen_random_uuid(), 'test', 'test', 'robot', now(), now())
                """;

        // when
        SQLException exception = assertThrows(SQLException.class, () -> execute(insert));

        // then
        assertThat(exception.getSQLState(), equalTo(CHECK_VIOLATION));
    }

    private List<String> publicTableNames() throws SQLException {
        return strings("select table_name from information_schema.tables where table_schema = 'public'");
    }

    private List<String> strings(String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                values.add(result.getString(1));
            }
        }
        return values;
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
