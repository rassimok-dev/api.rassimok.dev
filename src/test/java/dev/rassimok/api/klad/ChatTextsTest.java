package dev.rassimok.api.klad;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

class ChatTextsTest {

    private static final String RUSSIAN_LETTERS = "\u0410\u0411\u0412\u0413\u0414\u0415\u0401\u0416\u0417\u0418"
            + "\u0419\u041A\u041B\u041C\u041D\u041E\u041F\u0420\u0421\u0422\u0423\u0424\u0425\u0426\u0427\u0428"
            + "\u0429\u042A\u042B\u042C\u042D\u042E\u042F\u0430\u0431\u0432\u0433\u0434\u0435\u0451\u0436\u0437"
            + "\u0438\u0439\u043A\u043B\u043C\u043D\u043E\u043F\u0440\u0441\u0442\u0443\u0444\u0445\u0446\u0447"
            + "\u0448\u0449\u044A\u044B\u044C\u044D\u044E\u044F";

    @ParameterizedTest
    @ValueSource(strings = {
            "level 3?",
            "a",
            "ABCDEFGHIJKLMNOPQRSTUVWXYZ abcdefghijklmnopqrstuvwxyz 0123456789",
            "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~",
            "\u041F\u0440\u0438\u0432\u0435\u0442, \u043A\u0430\u043A \u0434\u0435\u043B\u0430?",
            "go \u043D\u0430 level 3",
            "don\u2019t \u00AB\u0441\u0442\u043E\u043F\u00BB \u2014 \u2018ok\u2019 \u201Cyes\u201D 1\u20132",
            "https://example.com/klad?room=1 <b>not bold</b>"})
    void shouldAccept_whenIsValid_givenLettersDigitsSpacesAndPunctuation(String text) {
        // when
        boolean isValid = ChatTexts.isValid(text);

        // then
        assertThat(isValid, equalTo(true));
    }

    @Test
    void shouldAccept_whenIsValid_givenEveryRussianLetter() {
        // when
        boolean isValid = ChatTexts.isValid(RUSSIAN_LETTERS);

        // then
        assertThat(isValid, equalTo(true));
    }

    @Test
    void shouldAccept_whenIsValid_given120Characters() {
        // given
        String text = "x".repeat(Rules.CHAT_TEXT_LENGTH_MAX);

        // when
        boolean isValid = ChatTexts.isValid(text);

        // then
        assertThat(isValid, equalTo(true));
    }

    @Test
    void shouldAccept_whenIsValid_given120CyrillicLetters() {
        // given
        String text = "\u0436".repeat(Rules.CHAT_TEXT_LENGTH_MAX);

        // when
        boolean isValid = ChatTexts.isValid(text);

        // then
        assertThat(isValid, equalTo(true));
    }

    @Test
    void shouldReject_whenIsValid_given121Characters() {
        // given
        String text = "x".repeat(Rules.CHAT_TEXT_LENGTH_MAX + 1);

        // when
        boolean isValid = ChatTexts.isValid(text);

        // then
        assertThat(isValid, equalTo(false));
    }

    @ParameterizedTest
    @NullAndEmptySource
    void shouldReject_whenIsValid_givenNoText(String text) {
        // when
        boolean isValid = ChatTexts.isValid(text);

        // then
        assertThat(isValid, equalTo(false));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "line one\nline two",
            "line one\rline two",
            "line one\u2028line two",
            "line one\u2029line two",
            "tab\there",
            "bell\u0007",
            "delete\u007F",
            "next line\u0085here"})
    void shouldReject_whenIsValid_givenLineBreakOrControlCharacter(String text) {
        // when
        boolean isValid = ChatTexts.isValid(text);

        // then
        assertThat(isValid, equalTo(false));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "zero\u200Bwidth",
            "zero\u200Cwidth",
            "zero\u200Dwidth",
            "word\u2060joiner",
            "byte\uFEFForder",
            "soft\u00ADhyphen",
            "invisible\u2062times"})
    void shouldReject_whenIsValid_givenInvisibleCharacter(String text) {
        // when
        boolean isValid = ChatTexts.isValid(text);

        // then
        assertThat(isValid, equalTo(false));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "\u202Eright to left",
            "\u202Dleft to right",
            "\u2066isolate\u2069",
            "mark\u200F",
            "mark\u200E",
            "arabic\u061Cmark"})
    void shouldReject_whenIsValid_givenDirectionOverrideOrMark(String text) {
        // when
        boolean isValid = ChatTexts.isValid(text);

        // then
        assertThat(isValid, equalTo(false));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "hi \uD83D\uDE00",
            "heart \u2764\uFE0F",
            "smile \u263A",
            "flag \uD83C\uDDF7\uD83C\uDDFA",
            "lone \uD83D surrogate"})
    void shouldReject_whenIsValid_givenEmoji(String text) {
        // when
        boolean isValid = ChatTexts.isValid(text);

        // then
        assertThat(isValid, equalTo(false));
    }

    // Widening the alphabet later breaks no client; narrowing it would.
    @ParameterizedTest
    @ValueSource(strings = {
            "caf\u00E9",
            "\u0430\u0301",
            "\u0456\u0434\u0438",
            "\u03B1\u03B2\u03B3",
            "\u4F60\u597D",
            "\u0661\u0662\u0663"})
    void shouldReject_whenIsValid_givenOtherLettersOrCombiningMarks(String text) {
        // when
        boolean isValid = ChatTexts.isValid(text);

        // then
        assertThat(isValid, equalTo(false));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "'  level 3?  '|level 3?",
            "'\u3000hi\u3000'|hi",
            "'\uFF48\uFF49'|hi",
            "'a\u00A0b'|a b",
            "'wait\u2026'|wait..."})
    void shouldReturnStoredForm_whenNormalize_givenSpacesOrCompatibilityCharacters(String text, String expected) {
        // when
        String normalized = ChatTexts.normalize(text);

        // then
        assertThat(normalized, equalTo(expected));
    }

    // Trimmed like a nick, so the stored text is still one line.
    @Test
    void shouldDropLineBreak_whenNormalize_givenLineBreakAtEnd() {
        // when
        String normalized = ChatTexts.normalize("line one\n");

        // then
        assertThat(normalized, equalTo("line one"));
    }

    @Test
    void shouldReject_whenIsValid_givenTextLongerThan120AfterNormalizing() {
        // given
        String text = ChatTexts.normalize("x".repeat(Rules.CHAT_TEXT_LENGTH_MAX - 1) + "\u2026");

        // when
        boolean isValid = ChatTexts.isValid(text);

        // then
        assertThat(isValid, equalTo(false));
    }

    @Test
    void shouldReject_whenIsValid_givenOnlySpacesNormalized() {
        // given
        String text = ChatTexts.normalize("   ");

        // when
        boolean isValid = ChatTexts.isValid(text);

        // then
        assertThat(isValid, equalTo(false));
    }
}
