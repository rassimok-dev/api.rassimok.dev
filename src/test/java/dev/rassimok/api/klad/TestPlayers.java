package dev.rassimok.api.klad;

import io.restassured.http.ContentType;

import java.util.Map;

import static io.restassured.RestAssured.given;

final class TestPlayers {

    static final String COOKIE = "klad_session";

    private TestPlayers() {
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
