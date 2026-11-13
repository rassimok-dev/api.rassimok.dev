package dev.rassimok.api.klad;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

final class TestSql {

    private static final int LOCK_POLLS = 100;

    private TestSql() {
    }

    static void execute(DataSource dataSource, String sql, Object... parameters) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            execute(connection, sql, parameters);
        }
    }

    static void execute(Connection connection, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = prepare(connection, sql, parameters)) {
            statement.execute();
        }
    }

    static List<Object> row(DataSource dataSource, String sql, Object... parameters) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return row(connection, sql, parameters);
        }
    }

    static List<Object> row(Connection connection, String sql, Object... parameters) throws SQLException {
        List<Object> values = new ArrayList<>();
        try (PreparedStatement statement = prepare(connection, sql, parameters);
             ResultSet result = statement.executeQuery()) {
            if (result.next()) {
                for (int column = 1; column <= result.getMetaData().getColumnCount(); column++) {
                    values.add(result.getObject(column));
                }
            }
        }
        return values;
    }

    static List<List<Object>> rows(DataSource dataSource, String sql, Object... parameters) throws SQLException {
        List<List<Object>> rows = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = prepare(connection, sql, parameters);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                List<Object> values = new ArrayList<>();
                for (int column = 1; column <= result.getMetaData().getColumnCount(); column++) {
                    values.add(result.getObject(column));
                }
                rows.add(values);
            }
        }
        return rows;
    }

    static long count(DataSource dataSource, String sql, Object... parameters) throws SQLException {
        return ((Number) row(dataSource, sql, parameters).getFirst()).longValue();
    }

    // Polls for up to 10 s until that many statements wait for a lock.
    static long awaitLockWaiters(DataSource dataSource, long expected) throws SQLException, InterruptedException {
        long waiters = lockWaiters(dataSource);
        for (int attempt = 1; expected > waiters && LOCK_POLLS > attempt; attempt++) {
            Thread.sleep(100);
            waiters = lockWaiters(dataSource);
        }
        return waiters;
    }

    static long lockWaiters(DataSource dataSource) throws SQLException {
        return count(dataSource, "select count(*) from pg_stat_activity where wait_event_type = 'Lock'");
    }

    private static PreparedStatement prepare(Connection connection, String sql, Object... parameters)
            throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        for (int i = 0; i < parameters.length; i++) {
            statement.setObject(i + 1, parameters[i]);
        }
        return statement;
    }
}
