package dev.rassimok.api.klad;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

class EmailsTest {

    @Test
    void shouldTrimAndLowerCase_whenNormalize_givenPaddedMixedCaseEmail() {
        // when
        String normalized = Emails.normalize("  Player.One@Example.COM \t");

        // then
        assertThat(normalized, equalTo("player.one@example.com"));
    }

    @Test
    void shouldKeepNull_whenNormalize_givenNull() {
        // when
        String normalized = Emails.normalize(null);

        // then
        assertThat(normalized, nullValue());
    }

    @ParameterizedTest
    @ValueSource(strings = {"a@b", "player@example.com", "first.last+klad@mail.example.org", "игрок@почта.рф"})
    void shouldAccept_whenIsValid_givenOneAtWithTextOnBothSides(String email) {
        // when
        boolean isValid = Emails.isValid(email);

        // then
        assertThat(isValid, equalTo(true));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"player", "@example.com", "player@", "a@b@c", "a b@c", "a@b\r\nbcc: x@y", "a@b\u0000",
            "x <victim@example.com>", "a@b,c@d", "a;b@c", "\"a\"@b", "a@[127.0.0.1]", "a\\b@c", "a@b c"})
    void shouldReject_whenIsValid_givenNoEmail(String email) {
        // when
        boolean isValid = Emails.isValid(email);

        // then
        assertThat(isValid, equalTo(false));
    }

    @Test
    void shouldAcceptUpTo254Characters_whenIsValid() {
        // given
        String longest = "a".repeat(64) + "@" + "b".repeat(189);
        String tooLong = longest + "c";

        // when
        boolean isLongestValid = Emails.isValid(longest);
        boolean isTooLongValid = Emails.isValid(tooLong);

        // then
        assertThat(longest.length(), equalTo(Rules.EMAIL_MAX_LENGTH));
        assertThat(isLongestValid, equalTo(true));
        assertThat(isTooLongValid, equalTo(false));
    }
}
