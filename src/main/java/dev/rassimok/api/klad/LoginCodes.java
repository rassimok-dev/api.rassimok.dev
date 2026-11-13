package dev.rassimok.api.klad;

import io.quarkus.mailer.Mail;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.core.Response;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

@ApplicationScoped
public class LoginCodes {

    static final String SUBJECT = "Код для входа в КЛАД";
    private static final String TEXT = """
            Ваш код для входа в КЛАД: %s

            Код действует %d минут. Если вы не запрашивали код, просто удалите это письмо.
            """;
    private static final int CODE_BOUND = 1_000_000;
    private static final Pattern CODE = Pattern.compile("[0-9]{6}");

    // An instance field, not a static one, so it is seeded when the service
    // starts and not when the native image is built.
    private final SecureRandom random = new SecureRandom();

    // Up to three instances serve requests. The table lock makes code
    // requests count one after another, so a burst cannot slip past the
    // limits, and the per-email replace never races. Old rows are deleted on
    // every request; dontRollbackOn keeps that when the answer is 429.
    @Transactional(dontRollbackOn = ApiException.class)
    String issue(String email, Instant now) {
        LoginMail.getEntityManager().createNativeQuery("lock table login_mail in exclusive mode").executeUpdate();
        Instant dayAgo = now.minus(Rules.MAILS_PER_DAY_WINDOW);
        LoginMail.delete("sentAt <= ?1", dayAgo);
        LoginCode.delete("expiresAt <= ?1", now);
        String emailHash = Sessions.hash(email);
        if (!canSend(LoginMail.list("sentAt > ?1", dayAgo), emailHash, now)) {
            throw new ApiException(Response.Status.TOO_MANY_REQUESTS, ApiException.RATE_LIMITED);
        }
        String code = newCode();
        LoginCode loginCode = LoginCode.findById(emailHash);
        if (null == loginCode) {
            loginCode = new LoginCode();
            loginCode.emailHash = emailHash;
        }
        loginCode.codeHash = hash(email, code);
        loginCode.attempts = 0;
        loginCode.expiresAt = now.plus(Rules.CODE_LIFETIME);
        loginCode.createdAt = now;
        loginCode.persist();
        LoginMail sent = new LoginMail();
        sent.emailHash = emailHash;
        sent.sentAt = now;
        sent.persist();
        return code;
    }

    // Runs in the caller's transaction, which must not roll back when this
    // throws. A code that is not 6 digits can never be right, so it is
    // refused without reading the row or counting an attempt.
    static void consume(String email, String code, Instant now) {
        boolean isWellFormed = Emails.isValid(email) && isCode(code);
        LoginCode loginCode = isWellFormed
                ? LoginCode.findById(Sessions.hash(email), LockModeType.PESSIMISTIC_WRITE)
                : null;
        if (null == loginCode) {
            throw new ApiException(Response.Status.BAD_REQUEST, ApiException.INVALID_CODE);
        }
        if (isExpired(loginCode, now)) {
            loginCode.delete();
            throw new ApiException(Response.Status.BAD_REQUEST, ApiException.INVALID_CODE);
        }
        if (!isCorrect(loginCode, email, code)) {
            loginCode.attempts++;
            if (Rules.CODE_ATTEMPTS <= loginCode.attempts) {
                loginCode.delete();
                throw new ApiException(Response.Status.TOO_MANY_REQUESTS, ApiException.TOO_MANY_ATTEMPTS);
            }
            throw new ApiException(Response.Status.BAD_REQUEST, ApiException.INVALID_CODE);
        }
        loginCode.delete();
    }

    static boolean isCode(String code) {
        return null != code && CODE.matcher(code).matches();
    }

    String newCode() {
        return "%06d".formatted(random.nextInt(CODE_BOUND));
    }

    static String hash(String email, String code) {
        return Sessions.hash(email + ":" + code);
    }

    static boolean isExpired(LoginCode loginCode, Instant now) {
        return !now.isBefore(loginCode.expiresAt);
    }

    // MessageDigest.isEqual takes the same time wherever the hashes differ.
    static boolean isCorrect(LoginCode loginCode, String email, String code) {
        byte[] expected = loginCode.codeHash.getBytes(StandardCharsets.US_ASCII);
        byte[] actual = hash(email, null == code ? "" : code).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, actual);
    }

    static boolean canSend(List<LoginMail> sentMails, String emailHash, Instant now) {
        Instant emailWindowStart = now.minus(Rules.CODES_PER_EMAIL_WINDOW);
        Instant dayWindowStart = now.minus(Rules.MAILS_PER_DAY_WINDOW);
        long sentToEmail = sentMails.stream()
                .filter(sent -> emailHash.equals(sent.emailHash) && sent.sentAt.isAfter(emailWindowStart))
                .count();
        long sentInDay = sentMails.stream().filter(sent -> sent.sentAt.isAfter(dayWindowStart)).count();
        return Rules.CODES_PER_EMAIL > sentToEmail && Rules.MAILS_PER_DAY > sentInDay;
    }

    static Mail mail(String email, String code) {
        return Mail.withText(email, SUBJECT, TEXT.formatted(code, Rules.CODE_LIFETIME.toMinutes()));
    }
}
