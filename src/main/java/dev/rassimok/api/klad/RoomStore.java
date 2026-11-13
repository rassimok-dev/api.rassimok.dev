package dev.rassimok.api.klad;

import jakarta.inject.Singleton;
import jakarta.ws.rs.core.Response;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The rooms of the one instance, in memory, lost on a restart. One lock
 * covers them all, because a join can leave another room and the cap counts
 * every room. Old members and rooms go on every access; nothing runs in
 * between.
 */
@Singleton
public class RoomStore {

    private static final String ID_CHARACTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-";

    private final Clock clock;
    // An instance field, not a static one, so it is seeded when the service
    // starts and not when the native image is built.
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Room> rooms = new HashMap<>();

    RoomStore() {
        this(Clock.systemUTC());
    }

    RoomStore(Clock clock) {
        this.clock = clock;
    }

    // Closing the player's old room first frees its place under the cap, so
    // a host can always replace their room.
    synchronized String create(LobbyPlayer player) {
        Instant now = clock.instant();
        removeExpired(now);
        rooms.values().removeIf(room -> room.isHostOrLastHost(player.id()));
        if (Rules.ROOMS_MAX <= rooms.size()) {
            throw new ApiException(Response.Status.TOO_MANY_REQUESTS, ApiException.RATE_LIMITED);
        }
        leaveCurrentRoom(player.id(), now);
        Room room = new Room(newId(), now);
        room.add(player, now);
        rooms.put(room.id, room);
        return room.id;
    }

    synchronized RoomView find(String roomId, LobbyPlayer player) {
        Instant now = clock.instant();
        Room room = existingRoom(roomId, now);
        room.markSeen(player, now);
        return room.view(player.id());
    }

    // The player leaves their old room only once this one has taken them.
    synchronized RoomView join(String roomId, LobbyPlayer player) {
        Instant now = clock.instant();
        Room room = existingRoom(roomId, now);
        if (null == room.member(player.id())) {
            requireLobby(room);
            if (room.isFull()) {
                throw new ApiException(Response.Status.CONFLICT, ApiException.ROOM_FULL);
            }
            leaveCurrentRoom(player.id(), now);
            room.add(player, now);
        }
        room.markSeen(player, now);
        return room.view(player.id());
    }

    synchronized void leave(String roomId, LobbyPlayer player) {
        Instant now = clock.instant();
        Room room = existingRoom(roomId, now);
        Room.Member member = room.member(player.id());
        if (null != member) {
            room.remove(member, now);
        }
    }

    // To a player who is not in it, the room is not found.
    synchronized RoomView setReady(String roomId, LobbyPlayer player, boolean isReady) {
        Instant now = clock.instant();
        Room room = existingRoom(roomId, now);
        Room.Member member = room.member(player.id());
        if (null == member) {
            throw new ApiException(Response.Status.NOT_FOUND, ApiException.NOT_FOUND);
        }
        requireLobby(room);
        member.isReady = isReady;
        room.markSeen(player, now);
        return room.view(player.id());
    }

    synchronized RoomView chooseLevel(String roomId, LobbyPlayer player, int level, String shape) {
        Instant now = clock.instant();
        Room room = existingRoom(roomId, now);
        requireHost(room, player);
        requireLobby(room);
        room.level = level;
        room.shape = shape;
        room.markSeen(player, now);
        return room.view(player.id());
    }

    synchronized RoomView start(String roomId, LobbyPlayer player) {
        Instant now = clock.instant();
        Room room = existingRoom(roomId, now);
        requireHost(room, player);
        requireLobby(room);
        if (!room.canStart()) {
            throw new ApiException(Response.Status.CONFLICT, ApiException.NOT_READY);
        }
        room.state = Room.State.STARTING;
        room.markSeen(player, now);
        return room.view(player.id());
    }

    // A presence heartbeat counts as a poll of the player's room.
    synchronized void markSeen(LobbyPlayer player) {
        Instant now = clock.instant();
        removeExpired(now);
        Room room = currentRoom(player.id());
        if (null != room) {
            room.markSeen(player, now);
        }
    }

    private Room existingRoom(String roomId, Instant now) {
        removeExpired(now);
        Room room = rooms.get(roomId);
        if (null == room) {
            throw new ApiException(Response.Status.NOT_FOUND, ApiException.NOT_FOUND);
        }
        return room;
    }

    private void removeExpired(Instant now) {
        rooms.values().forEach(room -> room.removeSilentMembers(now));
        rooms.values().removeIf(room -> room.isExpired(now));
    }

    private Room currentRoom(UUID playerId) {
        return rooms.values().stream().filter(room -> null != room.member(playerId)).findFirst().orElse(null);
    }

    private void leaveCurrentRoom(UUID playerId, Instant now) {
        Room room = currentRoom(playerId);
        if (null != room) {
            room.remove(room.member(playerId), now);
        }
    }

    private static void requireHost(Room room, LobbyPlayer player) {
        if (!room.isHost(player.id())) {
            throw new ApiException(Response.Status.FORBIDDEN, ApiException.NOT_HOST);
        }
    }

    private static void requireLobby(Room room) {
        if (Room.State.LOBBY != room.state) {
            throw new ApiException(Response.Status.CONFLICT, ApiException.NOT_IN_LOBBY);
        }
    }

    private String newId() {
        String id;
        do {
            StringBuilder builder = new StringBuilder(Rules.ROOM_ID_LENGTH);
            for (int i = 0; i < Rules.ROOM_ID_LENGTH; i++) {
                builder.append(ID_CHARACTERS.charAt(random.nextInt(ID_CHARACTERS.length())));
            }
            id = builder.toString();
        } while (rooms.containsKey(id));
        return id;
    }
}
