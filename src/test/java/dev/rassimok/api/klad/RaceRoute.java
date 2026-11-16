package dev.rassimok.api.klad;

import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static io.restassured.RestAssured.given;

/**
 * The race socket for the guard tests. A seat is the host of a fresh room
 * in starting whose other member never connects. The players are made once
 * and reused, and each host's new room closes their old one, so the tests
 * keep about 20 rooms whatever their number.
 */
final class RaceRoute implements SocketRoute {

    // One for every test class, so the players and rooms are made once.
    static final RaceRoute SHARED = new RaceRoute();

    private static final String UNKNOWN_ROOM = "AAAAAAAAAA";
    // More pairs than any test holds seats at once, so a seat in use is not
    // replaced by a later one.
    private static final int PAIRS = 8;
    private static final int GROUP_PLAYERS = 2 * Rules.SOCKETS_OPEN_MAX;
    private static final Set<String> UNPROMPTED_TYPES = Set.of("state", "countdown", "go", "pos", "results", "chat");
    // 120 Cyrillic letters of two bytes each: the longest text in characters.
    private static final String LONGEST_CHAT_BODY = "{\"text\":\"" + "\u0416".repeat(Rules.CHAT_TEXT_LENGTH_MAX) + "\"}";

    private final List<String> pairTokens = new ArrayList<>();
    private final List<String> groupTokens = new ArrayList<>();
    // Host, guest, outsider and lobby host of the refused handshakes, apart
    // from the pairs, so a later seat never closes their rooms.
    private final List<String> refusalTokens = new ArrayList<>();
    private int nextPair;
    // A player made with the pools, to tell whether they still exist.
    private String probeToken;

    private RaceRoute() {
    }

    @Override
    public synchronized Seat newSeat() {
        forgetPlayersOfEarlierStart();
        if (pairTokens.isEmpty()) {
            pairTokens.addAll(guests(2 * PAIRS));
        }
        int pair = nextPair % PAIRS;
        nextPair++;
        String host = pairTokens.get(2 * pair);
        String id = RaceRooms.startedRoom(List.of(host, pairTokens.get(2 * pair + 1)));
        return new Seat(host, RaceRooms.RACE_PATH + id);
    }

    // Full rooms of ten, so twenty seats take two rooms.
    @Override
    public synchronized List<Seat> newSeats(int count) {
        forgetPlayersOfEarlierStart();
        List<Seat> seats = new ArrayList<>();
        for (int first = 0; first < count; first += Rules.ROOM_SIZE) {
            seats.addAll(newRoomOfSeats(first, count));
        }
        return seats;
    }

    // Each room's members connect within its 15 s connect wait however long
    // the earlier rooms took.
    @Override
    public synchronized void forEachNewSeat(int count, SeatUse use) throws Exception {
        forgetPlayersOfEarlierStart();
        for (int first = 0; first < count; first += Rules.ROOM_SIZE) {
            for (Seat seat : newRoomOfSeats(first, count)) {
                use.use(seat);
            }
        }
    }

    // Each stays refused for the rest of the test: a starting room nobody
    // connects to goes back to the lobby, which refuses too.
    @Override
    public synchronized List<Refusal> ownRefusals() {
        forgetPlayersOfEarlierStart();
        if (refusalTokens.isEmpty()) {
            refusalTokens.addAll(guests(4));
        }
        String host = refusalTokens.get(0);
        String outsider = refusalTokens.get(2);
        String lobbyHost = refusalTokens.get(3);
        String startedPath = RaceRooms.RACE_PATH + RaceRooms.startedRoom(List.of(host, refusalTokens.get(1)));
        String lobbyPath = RaceRooms.RACE_PATH + RaceRooms.lobbyRoom(lobbyHost);
        return List.of(
                new Refusal("unknown room", new Seat(host, RaceRooms.RACE_PATH + UNKNOWN_ROOM),
                        "HTTP/1.1 404 Not Found"),
                new Refusal("not in the room", new Seat(outsider, startedPath), "HTTP/1.1 409 Conflict"),
                new Refusal("room in the lobby", new Seat(lobbyHost, lobbyPath), "HTTP/1.1 409 Conflict"));
    }

    @Override
    public String request() {
        return "{\"type\":\"ping\"}";
    }

    @Override
    public String reply() {
        return "{\"type\":\"pong\"}";
    }

    @Override
    public String badReply() {
        return "{\"type\":\"error\",\"error\":\"bad_message\"}";
    }

    @Override
    public boolean isUnprompted(String message) {
        return UNPROMPTED_TYPES.contains(new JsonObject(message).getString("type"));
    }

    // Every member sends a 120-letter chat text as often as the rate limit
    // allows, so each socket gets the others' messages; one more is refused.
    @Override
    public void sendUnpromptedAtLimit(List<Seat> seats) {
        for (Seat seat : seats) {
            String messagesPath = "/rooms/" + seat.path().substring(RaceRooms.RACE_PATH.length()) + "/messages";
            for (int i = 0; i < Rules.CHAT_MESSAGES_PER_WINDOW; i++) {
                sendChat(seat.token(), messagesPath).statusCode(201);
            }
            sendChat(seat.token(), messagesPath).statusCode(429);
        }
    }

    @Override
    public String toString() {
        return "race";
    }

    private List<Seat> newRoomOfSeats(int first, int count) {
        if (groupTokens.isEmpty()) {
            groupTokens.addAll(guests(GROUP_PLAYERS));
        }
        List<String> members = groupTokens.subList(first, Math.min(first + Rules.ROOM_SIZE, count));
        String path = RaceRooms.RACE_PATH + RaceRooms.startedRoom(members);
        return members.stream().map(token -> new Seat(token, path)).toList();
    }

    // A test class with its own profile restarts the application with a new
    // database and new rooms, so players made before are gone.
    private void forgetPlayersOfEarlierStart() {
        boolean areGone = null == probeToken
                || 200 != given().cookie(TestPlayers.COOKIE, probeToken).when().get("/players/me").statusCode();
        if (areGone) {
            pairTokens.clear();
            groupTokens.clear();
            refusalTokens.clear();
            nextPair = 0;
            probeToken = guests(1).getFirst();
        }
    }

    private static ValidatableResponse sendChat(String token, String messagesPath) {
        return given().cookie(TestPlayers.COOKIE, token).contentType(ContentType.JSON).body(LONGEST_CHAT_BODY)
                .when().post(messagesPath).then();
    }

    private static List<String> guests(int count) {
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            tokens.add(TestPlayers.createGuest("Racer", Rules.KIND_HUMAN));
        }
        return tokens;
    }
}
