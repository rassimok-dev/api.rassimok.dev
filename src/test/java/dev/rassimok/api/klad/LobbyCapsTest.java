package dev.rassimok.api.klad;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * JVM only: it fills the stores through the beans, which a native test
 * cannot reach. Its own profile starts a fresh application, so the full
 * stores never reach the other test classes. Each test fills a different
 * store.
 */
@QuarkusTest
@TestProfile(LobbyCapsTest.FreshStores.class)
class LobbyCapsTest {

    public static class FreshStores implements QuarkusTestProfile {
    }

    @Inject
    RoomStore rooms;

    @Inject
    PresenceStore presence;

    @Test
    void shouldAnswerRateLimited_whenCreateRoom_givenRoomCapReached() {
        // given
        String token = TestPlayers.createGuest("CapHost", Rules.KIND_HUMAN);
        for (int i = 0; i < Rules.ROOMS_MAX; i++) {
            rooms.create(filler());
        }

        // when
        ValidatableResponse response = given().cookie(TestPlayers.COOKIE, token).when().post("/rooms").then();

        // then
        response.statusCode(429).body("", equalTo(Map.of("error", ApiException.RATE_LIMITED)));
    }

    @Test
    void shouldRefuseOnlyNewPlayers_whenHeartbeat_givenPresenceCapReached() {
        // given
        String knownToken = TestPlayers.createGuest("CapKnown", Rules.KIND_HUMAN);
        String newToken = TestPlayers.createGuest("CapNew", Rules.KIND_HUMAN);
        given().cookie(TestPlayers.COOKIE, knownToken).when().post("/presence").then().statusCode(204);
        for (int i = 1; i < Rules.PRESENCE_MAX; i++) {
            presence.heartbeat(filler());
        }

        // when
        ValidatableResponse known = given().cookie(TestPlayers.COOKIE, knownToken).when().post("/presence").then();
        ValidatableResponse refused = given().cookie(TestPlayers.COOKIE, newToken).when().post("/presence").then();

        // then
        known.statusCode(204);
        refused.statusCode(429).body("", equalTo(Map.of("error", ApiException.RATE_LIMITED)));
    }

    private static LobbyPlayer filler() {
        return new LobbyPlayer(UUID.randomUUID(), "Filler", Rules.KIND_HUMAN, true);
    }
}
