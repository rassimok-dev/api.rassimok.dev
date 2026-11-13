package dev.rassimok.api.klad;

import java.util.UUID;

/**
 * A signed-in player as presence and rooms see them. The id is the key and
 * never leaves the API: two guests may share a nick.
 */
record LobbyPlayer(UUID id, String nick, String kind, boolean isGuest) {

    static LobbyPlayer of(Player player) {
        return new LobbyPlayer(player.id, player.nick, player.kind, null == player.email);
    }
}
