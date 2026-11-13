package dev.rassimok.api.klad;

import io.restassured.http.ContentType;

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
}
