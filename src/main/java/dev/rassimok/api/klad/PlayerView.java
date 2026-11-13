package dev.rassimok.api.klad;

import com.fasterxml.jackson.annotation.JsonProperty;

record PlayerView(String nick, String kind, @JsonProperty("account") boolean isAccount, String email) {

    static PlayerView of(Player player) {
        return new PlayerView(player.nick, player.kind, null != player.email, player.email);
    }
}
