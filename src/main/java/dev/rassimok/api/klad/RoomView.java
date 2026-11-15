package dev.rassimok.api.klad;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * A room as one player sees it: nicks only, no ids or emails; `you` marks that player's own entry, and
 * `listed` is false for a room the host has made link only.
 */
record RoomView(String id, String state, Host host, List<RoomPlayer> players, int level, String shape, int size,
                @JsonProperty("listed") boolean isListed) {

    record Host(String nick) {
    }

    record RoomPlayer(String nick, String kind, @JsonProperty("ready") boolean isReady,
                      @JsonProperty("host") boolean isHost, @JsonProperty("you") boolean isYou) {
    }
}
