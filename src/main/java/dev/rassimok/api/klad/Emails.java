package dev.rassimok.api.klad;

import java.util.Locale;
import java.util.regex.Pattern;

final class Emails {

    // One @ with text on both sides. Spaces, control characters and the
    // characters that separate or decorate addresses in a mail header are
    // refused, so an address can never add a second recipient or a header.
    private static final Pattern EMAIL = Pattern.compile(
            "[^@\\s\\p{Cntrl}<>()\\[\\],;:\"\\\\]+@[^@\\s\\p{Cntrl}<>()\\[\\],;:\"\\\\]+",
            Pattern.UNICODE_CHARACTER_CLASS);

    private Emails() {
    }

    static String normalize(String email) {
        return null == email ? null : email.strip().toLowerCase(Locale.ROOT);
    }

    static boolean isValid(String normalizedEmail) {
        return null != normalizedEmail
                && Rules.EMAIL_MAX_LENGTH >= normalizedEmail.length()
                && EMAIL.matcher(normalizedEmail).matches();
    }
}
