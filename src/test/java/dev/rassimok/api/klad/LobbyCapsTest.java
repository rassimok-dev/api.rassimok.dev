package dev.rassimok.api.klad;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * JVM only: it fills the stores through the beans, which a native test
 * cannot reach. Its own profile starts a fresh application, so the full
 * stores never reach the other test classes. The room tests fill the room
 * store with the same hosts.
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
        fillRooms();

        // when
        ValidatableResponse response = given().cookie(TestPlayers.COOKIE, token).when().post("/rooms").then();

        // then
        response.statusCode(429).body("", equalTo(Map.of("error", ApiException.RATE_LIMITED)));
    }

    @Test
    void shouldAnswerInvalidListed_whenCreateRoom_givenRoomCapReachedAndNeitherTrueNorFalse() {
        // given
        String token = TestPlayers.createGuest("CapLister", Rules.KIND_HUMAN);
        fillRooms();

        // when
        ValidatableResponse response = given().cookie(TestPlayers.COOKIE, token).queryParam("listed", "yes")
                .when().post("/rooms").then();

        // then
        response.statusCode(400).body("", equalTo(Map.of("error", ApiException.INVALID_LISTED)));
    }

    @Test
    void shouldListTwentyOldestRooms_whenListRooms_givenRoomCapReached() {
        // given
        String token = TestPlayers.createGuest("CapReader", Rules.KIND_HUMAN);
        List<String> ids = fillRooms();

        // when
        ValidatableResponse response = given().cookie(TestPlayers.COOKIE, token).when().get("/rooms").then();

        // then
        List<Map<String, Object>> expected = ids.stream().limit(Rules.ROOMS_LISTED_MAX)
                .<Map<String, Object>>map(id -> Map.of("id", id, "state", "lobby", "host", Map.of("nick", "Filler"),
                        "players", 1, "size", Rules.ROOM_SIZE, "level", Rules.LEVEL_MIN,
                        "shape", Rules.SHAPE_LANDSCAPE))
                .toList();
        response.statusCode(200).body("", equalTo(Map.of("rooms", expected)));
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

    // The same hosts in every test: a host's new room replaces their old one,
    // so the store holds exactly the cap whichever test runs first.
    private List<String> fillRooms() {
        return IntStream.range(0, Rules.ROOMS_MAX).mapToObj(i -> rooms.create(roomHost(i))).toList();
    }

    private static LobbyPlayer roomHost(int number) {
        return new LobbyPlayer(new UUID(0, number), "Filler", Rules.KIND_HUMAN, true);
    }

    private static LobbyPlayer filler() {
        return new LobbyPlayer(UUID.randomUUID(), "Filler", Rules.KIND_HUMAN, true);
    }
}
