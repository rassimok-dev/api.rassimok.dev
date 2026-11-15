package dev.rassimok.api.klad;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * A room as one player sees it: nicks only, no ids or emails; `you` marks that player's own entry, and
 * `listed` is false for a room the host has made link only. `member` is the number the room gave each
 * member at the join, and `messages` holds the chat messages that player may read.
 */
record RoomView(String id, String state, Host host, List<RoomPlayer> players, int level, String shape, int size,
                @JsonProperty("listed") boolean isListed, List<Message> messages) {

    record Host(String nick) {
    }

    record RoomPlayer(String nick, String kind, @JsonProperty("ready") boolean isReady,
                      @JsonProperty("host") boolean isHost, @JsonProperty("you") boolean isYou, int member) {
    }

    record Message(int n, int member, String nick, String kind, @JsonProperty("guest") boolean isGuest,
                   @JsonProperty("you") boolean isYou, String text) {
    }
}
