package dev.rassimok.api.klad;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

final class Nicks {

    // One pattern per script, so a nick never mixes Latin and Cyrillic
    // letters (lookalikes such as Latin a and Cyrillic a). The Cyrillic range
    // is A to ya in both cases, plus Yo and yo.
    private static final Pattern LATIN = Pattern.compile("[0-9_\\-A-Za-z]{3,16}");
    private static final Pattern CYRILLIC = Pattern.compile("[0-9_\\-\\u0410-\\u044F\\u0401\\u0451]{3,16}");

    private Nicks() {
    }

    static String normalize(String nick) {
        return null == nick ? null : Normalizer.normalize(nick, Normalizer.Form.NFKC).strip();
    }

    static boolean isValid(String normalizedNick) {
        return null != normalizedNick
                && (LATIN.matcher(normalizedNick).matches() || CYRILLIC.matcher(normalizedNick).matches());
    }

    static String key(String normalizedNick) {
        return normalizedNick.toLowerCase(Locale.ROOT);
    }
}
