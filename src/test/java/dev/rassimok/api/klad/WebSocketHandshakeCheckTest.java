package dev.rassimok.api.klad;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

class WebSocketHandshakeCheckTest {

    private static final String ALLOWED_ORIGIN = "https://game.example";

    private final WebSocketHandshakeCheck check = new WebSocketHandshakeCheck(ALLOWED_ORIGIN, token -> true,
            new OpenSockets());

    @Test
    void shouldAllowOrigin_whenIsAllowedOrigin_givenAllowedOrigin() {
        // when
        boolean isAllowed = check.isAllowedOrigin(ALLOWED_ORIGIN);

        // then
        assertThat(isAllowed, equalTo(true));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "null", "https://example.com", "http://game.example", "https://GAME.example",
            "https://game.example/", "https://game.example:443", "https://www.game.example",
            "https://game.example.evil.example", " https://game.example"})
    void shouldRefuseOrigin_whenIsAllowedOrigin_givenOtherOrigin(String origin) {
        // when
        boolean isAllowed = check.isAllowedOrigin(origin);

        // then
        assertThat(isAllowed, equalTo(false));
    }
}
