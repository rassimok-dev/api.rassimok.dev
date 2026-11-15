package dev.rassimok.api.klad;

import java.text.Normalizer;
import java.util.regex.Pattern;

/**
 * The text of a room chat message: one line from an allow-list, so it holds
 * no emoji, no control or invisible character and no direction override.
 */
final class ChatTexts {

    // Printable ASCII (letters, digits, the space, punctuation), the Russian
    // Cyrillic letters, and the quotes and dashes that phone keyboards put in
    // by themselves, or "don't" typed on a phone would be refused.
    private static final Pattern ALLOWED = Pattern.compile(
            "[\\u0020-\\u007E\\u0410-\\u044F\\u0401\\u0451\\u00AB\\u00BB\\u2018\\u2019\\u201C\\u201D\\u2013\\u2014]{1,"
                    + Rules.CHAT_TEXT_LENGTH_MAX + "}");

    private ChatTexts() {
    }

    static String normalize(String text) {
        return null == text ? null : Normalizer.normalize(text, Normalizer.Form.NFKC).strip();
    }

    static boolean isValid(String normalizedText) {
        return null != normalizedText && ALLOWED.matcher(normalizedText).matches();
    }
}
