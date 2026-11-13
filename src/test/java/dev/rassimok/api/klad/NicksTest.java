package dev.rassimok.api.klad;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

class NicksTest {

    // Every other character a nick may hold, in lower case and as capitals.
    private static final String NO_LOOKALIKES = "abcdefghijklmnopqrstuvwxyz123456789_-"
            + "\u0431\u0434\u0436\u0437\u0438\u0439\u043B\u043F\u0444\u0446\u0447\u0449\u044A\u044B\u044D\u044E\u044F"
            + "\u0451";
    private static final String NO_LOOKALIKES_IN_CAPITALS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ123456789_-"
            + "\u0411\u0414\u0416\u0417\u0418\u0419\u041B\u041F\u0424\u0426\u0427\u0429\u042A\u042B\u042D\u042E\u042F"
            + "\u0401";

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

    @ParameterizedTest
    @ValueSource(strings = {"Bob_7", "BOB_7", "bob_7", "  bOb_7 ", "\uFF22\uFF4F\uFF42_\uFF17"})
    void shouldGiveOneKey_whenKey_givenCaseAndWidthVariantsOfLatinNick(String nick) {
        // when
        String key = Nicks.key(Nicks.normalize(nick));

        // then
        assertThat(key, equalTo("bob_7"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\u0401\u0436\u0438\u043A", "\u0401\u0416\u0418\u041A", "\u0451\u0436\u0438\u043A",
            "\u0415\u0308\u0436\u0438\u043A"})
    void shouldGiveOneKey_whenKey_givenCaseAndDecomposedVariantsOfCyrillicNick(String nick) {
        // when
        String key = Nicks.key(Nicks.normalize(nick));

        // then
        assertThat(key, equalTo("\u0451\u0436\u0438k"));
    }

    // Latin B and b around a Cyrillic o, which looks like the Latin one.
    @ParameterizedTest
    @ValueSource(strings = {"B\u043Eb", "\uFF22\u043E\uFF42"})
    void shouldReject_whenIsValid_givenLookalikeLetterOfOtherScriptAfterNormalize(String nick) {
        // when
        boolean isValid = Nicks.isValid(Nicks.normalize(nick));

        // then
        assertThat(isValid, equalTo(false));
    }

    @Test
    void shouldLowerCaseCyrillic_whenKey() {
        // when
        String key = Nicks.key("\u0401\u0416\u0418\u041A");

        // then
        assertThat(key, equalTo("\u0451\u0436\u0438k"));
    }

    // Each Cyrillic lookalike in both cases, then zero.
    @ParameterizedTest
    @CsvSource({
            "\u0410, a", "\u0430, a",
            "\u0412, b", "\u0432, b",
            "\u0413, r", "\u0433, r",
            "\u0415, e", "\u0435, e",
            "\u041A, k", "\u043A, k",
            "\u041C, m", "\u043C, m",
            "\u041D, h", "\u043D, h",
            "\u041E, o", "\u043E, o",
            "\u0420, p", "\u0440, p",
            "\u0421, c", "\u0441, c",
            "\u0422, t", "\u0442, t",
            "\u0423, y", "\u0443, y",
            "\u0425, x", "\u0445, x",
            "\u0428, w", "\u0448, w",
            "\u042C, b", "\u044C, b",
            "0, o"})
    void shouldGiveLatinLetter_whenKey_givenLookalike(String lookalike, String latinLetter) {
        // when
        String key = Nicks.key(lookalike);

        // then
        assertThat(key, equalTo(latinLetter));
    }

    @ParameterizedTest
    @ValueSource(strings = {NO_LOOKALIKES, NO_LOOKALIKES_IN_CAPITALS})
    void shouldOnlyLowerCase_whenKey_givenCharactersWithoutLookalike(String characters) {
        // when
        String key = Nicks.key(characters);

        // then
        assertThat(key, equalTo(NO_LOOKALIKES));
    }

    // Cyrillic capitals, small letters and both, the digit zero, and fullwidth letters.
    @ParameterizedTest
    @ValueSource(strings = {"BOB", "bob", "Bob", "B0B", "b0b", "\u0412\u041E\u0412", "\u0432\u043E\u0432",
            "\u0412\u043E\u0432", "\u0412\u0030\u0412", "\uFF22\uFF10\uFF22"})
    void shouldGiveOneKey_whenKey_givenLookalikesOfLatinNick(String nick) {
        // when
        String key = Nicks.key(Nicks.normalize(nick));

        // then
        assertThat(key, equalTo("bob"));
    }

    // Latin B around Cyrillic O, Cyrillic Ve and O before Latin B, and Cyrillic
    // ve around Latin o: each would have the key bob.
    @ParameterizedTest
    @ValueSource(strings = {"B\u041EB", "\u0412\u041EB", "\u0432o\u0432"})
    void shouldReject_whenIsValid_givenMixedScriptsWithLatinKey(String nick) {
        // when
        boolean isValid = Nicks.isValid(Nicks.normalize(nick));

        // then
        assertThat(isValid, equalTo(false));
    }
}
