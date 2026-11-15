package dev.rassimok.api.klad;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

/**
 * Its own profile starts a fresh application, so its lists hold only the
 * rooms made here; in the shared one, other tests' rooms may take all 20
 * places. Only the join test leaves a room on the list, so its whole answer
 * holds in any test order. RoomResourceIT checks every entry's fields on
 * the binary.
 */
@QuarkusTest
@TestProfile(RoomListTest.FreshRooms.class)
class RoomListTest {

    public static class FreshRooms implements QuarkusTestProfile {
    }

    @Test
    void shouldShowRoomWhoseIdJoinsIt_whenListRooms_givenRoomOfOtherPlayer() {
        // given
        String host = TestPlayers.createGuest("ListHost", Rules.KIND_HUMAN);
        String id = createRoom(host);
        String other = TestPlayers.createGuest("ListGuest", Rules.KIND_HUMAN);

        // when
        ValidatableResponse listed = listRooms(other);

        // then
        listed.statusCode(200).body("", equalTo(Map.of("rooms", List.of(Map.of("id", id, "state", "lobby",
                "host", Map.of("nick", "ListHost"), "players", 1, "size", Rules.ROOM_SIZE,
                "level", Rules.LEVEL_MIN, "shape", Rules.SHAPE_LANDSCAPE)))));
        String listedId = listed.extract().path("rooms[0].id");
        given().cookie(TestPlayers.COOKIE, other).when().post("/rooms/" + listedId + "/join")
                .then().statusCode(200).body("players.nick", equalTo(List.of("ListHost", "ListGuest")));
    }

    @Test
    void shouldLeaveOutRoom_whenListRooms_givenHostSwitchedToLinkOnly() {
        // given
        String host = TestPlayers.createGuest("LinkHost", Rules.KIND_HUMAN);
        String other = TestPlayers.createGuest("LinkGuest", Rules.KIND_HUMAN);
        String id = createRoom(host);
        listRooms(other).statusCode(200).body("rooms.id", hasItem(id));
        given().cookie(TestPlayers.COOKIE, host).contentType(ContentType.JSON).body("{\"listed\":false}")
                .when().post("/rooms/" + id + "/listed")
                .then().statusCode(200);

        // when
        ValidatableResponse response = listRooms(other);

        // then
        response.statusCode(200).body("rooms.id", not(hasItem(id)));
    }

    @Test
    void shouldLeaveOutRoom_whenListRooms_givenRoomCreatedLinkOnly() {
        // given
        String host = TestPlayers.createGuest("MadeLinkHost", Rules.KIND_HUMAN);
        String other = TestPlayers.createGuest("MadeLinkGuest", Rules.KIND_HUMAN);
        String id = given().cookie(TestPlayers.COOKIE, host).queryParam("listed", "false")
                .when().post("/rooms")
                .then().statusCode(201).extract().path("id");

        // when
        ValidatableResponse response = listRooms(other);

        // then
        response.statusCode(200).body("rooms.id", not(hasItem(id)));
    }

    @Test
    void shouldLeaveOutRoom_whenListRooms_givenEmptyRoom() {
        // given
        String host = TestPlayers.createGuest("EmptyHost", Rules.KIND_HUMAN);
        String other = TestPlayers.createGuest("EmptyGuest", Rules.KIND_HUMAN);
        String id = createRoom(host);
        listRooms(other).statusCode(200).body("rooms.id", hasItem(id));
        given().cookie(TestPlayers.COOKIE, host).when().post("/rooms/" + id + "/leave").then().statusCode(204);

        // when
        ValidatableResponse response = listRooms(other);

        // then
        response.statusCode(200).body("rooms.id", not(hasItem(id)));
    }

    private static String createRoom(String token) {
        return given().cookie(TestPlayers.COOKIE, token)
                .when().post("/rooms")
                .then().statusCode(201).extract().path("id");
    }

    private static ValidatableResponse listRooms(String token) {
        return given().cookie(TestPlayers.COOKIE, token).when().get("/rooms").then();
    }
}
