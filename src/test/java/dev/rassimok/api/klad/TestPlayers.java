package dev.rassimok.api.klad;

import io.restassured.http.ContentType;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;

final class TestPlayers {

    static final String COOKIE = "klad_session";

    private TestPlayers() {
    }

    // The limit of codes per email would otherwise tie tests together.
    static String uniqueEmail(String name) {
        return name + "-" + UUID.randomUUID() + "@example.com";
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
                values (gen_random_uuid(), ?, lower(?), 'human', ?, now(), now())
                """, nick, nick, email);
    }
}
