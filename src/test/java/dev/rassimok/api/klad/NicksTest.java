package dev.rassimok.api.klad;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

class NicksTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "Bob",
            "bob_99",
            "A-b_c",
            "abcdefghijklmnop",
            "\u0418\u0432\u0430\u043D",
            "\u0451\u0436\u0438\u043A_7",
            "\u0401\u041B\u041A\u0410-2",
            "123",
            "___"})
    void shouldAccept_whenIsValid_givenOneScriptDigitsAndSigns(String nick) {
        // when
        boolean isValid = Nicks.isValid(nick);

        // then
        assertThat(isValid, equalTo(true));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Iv\u0430n",
            "\u0418van",
            "bob\u0431"})
    void shouldReject_whenIsValid_givenLatinAndCyrillicMixed(String nick) {
        // when
        boolean isValid = Nicks.isValid(nick);

        // then
        assertThat(isValid, equalTo(false));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "ab",
            "abcdefghijklmnopq",
            "bob!",
            "bo b",
            "\u00DCnal",
            "\u03B1\u03B2\u03B3",
            "\u0456\u0432\u0430",
            "\u0661\u0662\u0663"})
    void shouldReject_whenIsValid_givenWrongLengthOrCharacters(String nick) {
        // when
        boolean isValid = Nicks.isValid(nick);

        // then
        assertThat(isValid, equalTo(false));
    }

    @ParameterizedTest
    @ValueSource(strings = {"  Bob  ", "\u3000Bob\u3000", "\uFF22\uFF4F\uFF42"})
    void shouldReturnPlainNick_whenNormalize_givenSpacesOrFullwidthLetters(String nick) {
        // when
        String normalized = Nicks.normalize(nick);

        // then
        assertThat(normalized, equalTo("Bob"));
    }

    @Test
    void shouldComposeLetter_whenNormalize_givenCombiningDiaeresis() {
        // given
        String decomposed = "\u0415\u0308\u043B\u043A\u0430";

        // when
        String normalized = Nicks.normalize(decomposed);

        // then
        assertThat(normalized, equalTo("\u0401\u043B\u043A\u0430"));
    }

    @Test
    void shouldReturnNull_whenNormalize_givenNull() {
        // when
        String normalized = Nicks.normalize(null);

        // then
        assertThat(normalized, nullValue());
    }

    @Test
    void shouldLowerCaseLatin_whenKey() {
        // when
        String key = Nicks.key("BoB_7");

        // then
        assertThat(key, equalTo("bob_7"));
    }

    @Test
    void shouldLowerCaseCyrillic_whenKey() {
        // when
        String key = Nicks.key("\u0401\u0416\u0418\u041A");

        // then
        assertThat(key, equalTo("\u0451\u0436\u0438\u043A"));
    }
}
