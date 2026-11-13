package dev.rassimok.api.klad;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * HTTP only, so the native integration test can run it against the binary.
 * Its own board key and the agent kind keep it apart from other tests' data.
 */
@QuarkusTest
class KladRoundTripTest {

    private static final int REVISION = 777;
    // Fullwidth letters, which NFKC turns into plain Latin.
    private static final String FULLWIDTH_NICK = "\uFF2E\uFF41\uFF54\uFF49\uFF56\uFF45";
    // E followed by a combining diaeresis, which NFKC composes into Yo.
    private static final String DECOMPOSED_CYRILLIC_NICK = "\u0415\u0308\u0436\u0438\u043A";
    private static final String CYRILLIC_NICK = "\u0401\u0436\u0438\u043A";

    @Test
    void shouldCreateGuestWithCookie_whenCreatePlayer_givenFullwidthNick() {
        // when
        ValidatableResponse response = given()
                .contentType(ContentType.JSON)
                .body(Map.of("nick", FULLWIDTH_NICK, "kind", Rules.KIND_AGENT))
                .when().post("/players")
                .then();

        // then
        response.statusCode(201)
                .cookie(TestPlayers.COOKIE, notNullValue())
                .body("nick", equalTo("Native"))
                .body("kind", equalTo(Rules.KIND_AGENT))
                .body("account", equalTo(false))
                .body("$", hasKey("email"))
                .body("email", nullValue());
    }

    @Test
    void shouldComposeCyrillicNick_whenCreatePlayer_givenDecomposedLetters() {
        // when
        ValidatableResponse response = given()
                .contentType(ContentType.JSON)
                .body(Map.of("nick", DECOMPOSED_CYRILLIC_NICK, "kind", Rules.KIND_AGENT))
                .when().post("/players")
                .then();

        // then
        response.statusCode(201).body("nick", equalTo(CYRILLIC_NICK));
    }

    @Test
    void shouldListSubmittedResults_whenGetBoards_givenGuestSubmittedClearAndRun() {
        // given
        String token = TestPlayers.createGuest("RoundTrip", Rules.KIND_AGENT);
        given().cookie(TestPlayers.COOKIE, token).contentType(ContentType.JSON)
                .body(Map.of("level", 3, "shape", Rules.SHAPE_LANDSCAPE, "revision", REVISION,
                        "ticks", 1200, "chests", 4, "chestsTotal", 6))
                .when().post("/clears")
                .then().statusCode(201).body("", equalTo(Map.of()));
        given().cookie(TestPlayers.COOKIE, token).contentType(ContentType.JSON)
                .body(Map.of("levelsCleared", 5, "score", 10_000_000, "ticks", 3000))
                .when().post("/runs")
                .then().statusCode(201);

        // when
        ValidatableResponse level = given()
                .queryParam("kind", Rules.KIND_AGENT).queryParam("level", 3)
                .queryParam("shape", Rules.SHAPE_LANDSCAPE).queryParam("revision", REVISION)
                .when().get("/boards/level")
                .then();
        ValidatableResponse records = given().queryParam("kind", Rules.KIND_AGENT).when().get("/boards/records").then();
        ValidatableResponse chests = given().queryParam("kind", Rules.KIND_AGENT).when().get("/boards/chests").then();

        // then
        level.statusCode(200)
                .body("fastest[0]", equalTo(Map.of("rank", 1, "nick", "RoundTrip", "guest", true, "ticks", 1200)))
                .body("chests[0]", equalTo(Map.of("rank", 1, "nick", "RoundTrip", "guest", true,
                        "chests", 4, "chestsTotal", 6, "ticks", 1200)));
        records.statusCode(200).body("entries.nick", hasItem("RoundTrip"));
        chests.statusCode(200).body("entries.nick", hasItem("RoundTrip"));
    }

    @Test
    void shouldForgetPlayer_whenDeletePlayer_givenRenamedGuest() {
        // given
        String token = TestPlayers.createGuest("Leaving", Rules.KIND_AGENT);
        given().cookie(TestPlayers.COOKIE, token).contentType(ContentType.JSON)
                .body(Map.of("nick", "Renamed"))
                .when().patch("/players/me")
                .then().statusCode(200).body("nick", equalTo("Renamed"));

        // when
        ValidatableResponse deleted = given().cookie(TestPlayers.COOKIE, token).when().delete("/players/me").then();

        // then
        deleted.statusCode(204);
        given().cookie(TestPlayers.COOKIE, token)
                .when().get("/players/me")
                .then().statusCode(401).body("error", equalTo(ApiException.NO_SESSION));
    }
}
