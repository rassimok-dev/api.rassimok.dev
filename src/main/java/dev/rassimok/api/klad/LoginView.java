package dev.rassimok.api.klad;

import com.fasterxml.jackson.annotation.JsonProperty;

record LoginView(String nick, String kind, @JsonProperty("account") boolean isAccount, String email,
                 @JsonProperty("merged") boolean wasMerged) {

    static LoginView of(Player player, boolean wasMerged) {
        return new LoginView(player.nick, player.kind, null != player.email, player.email, wasMerged);
    }
}
