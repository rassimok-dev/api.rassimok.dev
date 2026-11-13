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

@ApplicationScoped
public class LoginCodes {

    static final String SUBJECT = "Код для входа в КЛАД";
    private static final String TEXT = """
            Ваш код для входа в КЛАД: %s

            Код действует %d минут. Если вы не запрашивали код, просто удалите это письмо.
            """;
    private static final int CODE_BOUND = 1_000_000;

    // An instance field, not a static one, so it is seeded when the service
    // starts and not when the native image is built.
    private final SecureRandom random = new SecureRandom();

    // Up to three instances serve requests. The table lock makes code
    // requests count one after another, so a burst cannot slip past the
    // limits, and the per-email replace never races.
    @Transactional
    String issue(String email, Instant now) {
        LoginMail.getEntityManager().createNativeQuery("lock table login_mail in exclusive mode").executeUpdate();
        String emailHash = Sessions.hash(email);
        Instant dayAgo = now.minus(Rules.MAILS_PER_DAY_WINDOW);
        if (!canSend(LoginMail.list("sentAt > ?1", dayAgo), emailHash, now)) {
            throw new ApiException(Response.Status.TOO_MANY_REQUESTS, ApiException.RATE_LIMITED);
        }
        LoginMail.delete("sentAt <= ?1", dayAgo);
        LoginCode.delete("expiresAt <= ?1", now);
        String code = newCode();
        LoginCode loginCode = LoginCode.findById(email);
        if (null == loginCode) {
            loginCode = new LoginCode();
            loginCode.email = email;
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
    // throws.
    static void consume(String email, String code, Instant now) {
        LoginCode loginCode = Emails.isValid(email) ? LoginCode.findById(email, LockModeType.PESSIMISTIC_WRITE) : null;
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
