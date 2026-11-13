package dev.rassimok.api.klad;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

final class TestSql {

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
        List<Object> values = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = prepare(connection, sql, parameters);
             ResultSet result = statement.executeQuery()) {
            if (result.next()) {
                for (int column = 1; column <= result.getMetaData().getColumnCount(); column++) {
                    values.add(result.getObject(column));
                }
            }
        }
        return values;
    }

    static long count(DataSource dataSource, String sql, Object... parameters) throws SQLException {
        return ((Number) row(dataSource, sql, parameters).getFirst()).longValue();
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
