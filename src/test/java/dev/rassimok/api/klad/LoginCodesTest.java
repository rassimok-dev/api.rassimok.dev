package dev.rassimok.api.klad;

import io.quarkus.mailer.Mail;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;

class LoginCodesTest {

    private static final Instant NOW = Instant.parse("2026-11-13T12:00:00Z");
    private static final String EMAIL = "player@example.com";
    // printf 'player@example.com:012345' | shasum -a 256
    private static final String CODE_HASH = "63a81ed3fe305f843721e15e0579b03146373593dc7831d8f4faaadd2a62e379";
    // printf 'player@example.com' | shasum -a 256
    private static final String EMAIL_HASH = "46b06dcd1ce7d8bdf29ce2677575bd21fd7b3879416d5b810dcc78b77e932b02";
    private static final String OTHER_EMAIL_HASH = Sessions.hash("other@example.com");

    @Test
    void shouldHashEmailColonCodeAsLowerCaseHex_whenHash() {
        // when
        String hash = LoginCodes.hash(EMAIL, "012345");

        // then
        assertThat(hash, equalTo(CODE_HASH));
    }

    @Test
    void shouldHashEmailAsLowerCaseHex_whenSessionsHash_givenEmail() {
        // when
        String hash = Sessions.hash(EMAIL);

        // then
        assertThat(hash, equalTo(EMAIL_HASH));
    }

    @Test
    void shouldGiveSixDigitsWithLeadingZeros_whenNewCode() {
        // given
        LoginCodes loginCodes = new LoginCodes();

        // when
        List<String> codes = IntStream.range(0, 2000).mapToObj(i -> loginCodes.newCode()).toList();

        // then
        assertThat(codes, everyItem(matchesPattern("[0-9]{6}")));
        assertThat(codes.stream().filter(code -> code.startsWith("0")).count(), greaterThan(0L));
        assertThat(Set.copyOf(codes).size(), greaterThan(1900));
    }

    @Test
    void shouldAccept_whenIsCorrect_givenRightCode() {
        // given
        LoginCode loginCode = loginCode(NOW.plus(Rules.CODE_LIFETIME));

        // when
        boolean isCorrect = LoginCodes.isCorrect(loginCode, EMAIL, "012345");

        // then
        assertThat(isCorrect, equalTo(true));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"012346", "12345", "0123456", " 012345"})
    void shouldReject_whenIsCorrect_givenOtherCode(String code) {
        // given
        LoginCode loginCode = loginCode(NOW.plus(Rules.CODE_LIFETIME));

        // when
        boolean isCorrect = LoginCodes.isCorrect(loginCode, EMAIL, code);

        // then
        assertThat(isCorrect, equalTo(false));
    }

    @Test
    void shouldReject_whenIsCorrect_givenRightCodeForOtherEmail() {
        // given
        LoginCode loginCode = loginCode(NOW.plus(Rules.CODE_LIFETIME));

        // when
        boolean isCorrect = LoginCodes.isCorrect(loginCode, "other@example.com", "012345");

        // then
        assertThat(isCorrect, equalTo(false));
    }

    @Test
    void shouldNotExpire_whenIsExpired_givenOneMillisecondLeft() {
        // given
        LoginCode loginCode = loginCode(NOW.plusMillis(1));

        // when
        boolean isExpired = LoginCodes.isExpired(loginCode, NOW);

        // then
        assertThat(isExpired, equalTo(false));
    }

    @Test
    void shouldExpire_whenIsExpired_givenExpiryReached() {
        // given
        LoginCode loginCode = loginCode(NOW);

        // when
        boolean isExpired = LoginCodes.isExpired(loginCode, NOW);

        // then
        assertThat(isExpired, equalTo(true));
    }

    @Test
    void shouldAllowThird_whenCanSend_givenTwoCodesToEmailInTheLastHour() {
        // given
        List<LoginMail> sent = List.of(sent(EMAIL_HASH, Duration.ofMinutes(59)), sent(EMAIL_HASH, Duration.ofMinutes(1)));

        // when
        boolean canSend = LoginCodes.canSend(sent, EMAIL_HASH, NOW);

        // then
        assertThat(canSend, equalTo(true));
    }

    @Test
    void shouldRefuseFourth_whenCanSend_givenThreeCodesToEmailInTheLastHour() {
        // given
        List<LoginMail> sent = List.of(sent(EMAIL_HASH, Duration.ofMinutes(59)), sent(EMAIL_HASH, Duration.ofMinutes(30)),
                sent(EMAIL_HASH, Duration.ofSeconds(1)));

        // when
        boolean canSend = LoginCodes.canSend(sent, EMAIL_HASH, NOW);

        // then
        assertThat(canSend, equalTo(false));
    }

    @Test
    void shouldNotCountCodeSentAnHourAgo_whenCanSend_givenThreeCodesToEmail() {
        // given
        List<LoginMail> sent = List.of(sent(EMAIL_HASH, Duration.ofHours(1)), sent(EMAIL_HASH, Duration.ofMinutes(30)),
                sent(EMAIL_HASH, Duration.ofSeconds(1)));

        // when
        boolean canSend = LoginCodes.canSend(sent, EMAIL_HASH, NOW);

        // then
        assertThat(canSend, equalTo(true));
    }

    @Test
    void shouldNotCountOtherEmails_whenCanSend_givenThreeCodesToAnotherEmail() {
        // given
        List<LoginMail> sent = Collections.nCopies(3, sent(OTHER_EMAIL_HASH, Duration.ofMinutes(5)));

        // when
        boolean canSend = LoginCodes.canSend(sent, EMAIL_HASH, NOW);

        // then
        assertThat(canSend, equalTo(true));
    }

    @Test
    void shouldAllowEightieth_whenCanSend_givenSeventyNineMailsInTheLastDay() {
        // given
        List<LoginMail> sent = Collections.nCopies(Rules.MAILS_PER_DAY - 1, sent(OTHER_EMAIL_HASH, Duration.ofHours(23)));

        // when
        boolean canSend = LoginCodes.canSend(sent, EMAIL_HASH, NOW);

        // then
        assertThat(canSend, equalTo(true));
    }

    @Test
    void shouldRefuseEightyFirst_whenCanSend_givenEightyMailsInTheLastDay() {
        // given
        List<LoginMail> sent = Collections.nCopies(Rules.MAILS_PER_DAY, sent(OTHER_EMAIL_HASH, Duration.ofHours(23)));

        // when
        boolean canSend = LoginCodes.canSend(sent, EMAIL_HASH, NOW);

        // then
        assertThat(canSend, equalTo(false));
    }

    @Test
    void shouldNotCountMailSentADayAgo_whenCanSend_givenEightyMails() {
        // given
        List<LoginMail> sent = new ArrayList<>(Collections.nCopies(Rules.MAILS_PER_DAY - 1,
                sent(OTHER_EMAIL_HASH, Duration.ofHours(23))));
        sent.add(sent(OTHER_EMAIL_HASH, Duration.ofDays(1)));

        // when
        boolean canSend = LoginCodes.canSend(sent, EMAIL_HASH, NOW);

        // then
        assertThat(canSend, equalTo(true));
    }

    @Test
    void shouldWriteRussianTextWithCodeAndLifetime_whenMail() {
        // when
        Mail mail = LoginCodes.mail(EMAIL, "012345");

        // then
        assertThat(mail.getTo(), equalTo(List.of(EMAIL)));
        assertThat(mail.getSubject(), equalTo("Код для входа в КЛАД"));
        assertThat(mail.getText(), equalTo("""
                Ваш код для входа в КЛАД: 012345

                Код действует 10 минут. Если вы не запрашивали код, просто удалите это письмо.
                """));
        assertThat(mail.getHtml(), nullValue());
    }

    private static LoginCode loginCode(Instant expiresAt) {
        LoginCode loginCode = new LoginCode();
        loginCode.email = EMAIL;
        loginCode.codeHash = CODE_HASH;
        loginCode.expiresAt = expiresAt;
        loginCode.createdAt = expiresAt.minus(Rules.CODE_LIFETIME);
        return loginCode;
    }

    private static LoginMail sent(String emailHash, Duration ago) {
        LoginMail mail = new LoginMail();
        mail.emailHash = emailHash;
        mail.sentAt = NOW.minus(ago);
        return mail;
    }
}
