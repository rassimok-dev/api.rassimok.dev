package dev.rassimok.api.klad;

import io.restassured.http.ContentType;

import java.util.List;

import static io.restassured.RestAssured.given;

/** Rooms set up over REST for the socket tests, so the native tests can use them too. */
final class RaceRooms {

    static final String RACE_PATH = "/race/";

    private RaceRooms() {
    }

    // A host's new room closes their old one, so tests that reuse their
    // hosts keep the rooms far below the cap.
    static String lobbyRoom(String hostToken) {
        return given().cookie(TestPlayers.COOKIE, hostToken)
                .when().post("/rooms")
                .then().statusCode(201)
                .extract().path("id");
    }

    // The first token hosts; every member is ready, and the room is starting.
    static String startedRoom(List<String> tokens) {
        String id = lobbyRoom(tokens.getFirst());
        tokens.stream().skip(1).forEach(token -> post(token, id, "/join", null));
        tokens.forEach(token -> post(token, id, "/ready", "{\"ready\":true}"));
        post(tokens.getFirst(), id, "/start", null);
        return id;
    }

    static String state(String token, String id) {
        return given().cookie(TestPlayers.COOKIE, token)
                .when().get("/rooms/" + id)
                .then().statusCode(200)
                .extract().path("state");
    }

    private static void post(String token, String id, String action, String body) {
        given().cookie(TestPlayers.COOKIE, token)
                .contentType(ContentType.JSON)
                .body(null == body ? "" : body)
                .when().post("/rooms/" + id + action)
                .then().statusCode(200);
    }
}
