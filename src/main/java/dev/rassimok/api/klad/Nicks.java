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

    // The characters that Unicode's confusables (UTS #39) show as another nick
    // character, listed in lower case where either case looks alike (capital I
    // as i); each counts as the one at its place below. Migration V5 repeats both.
    private static final String LOOKALIKES = "01i\u0430\u0431\u0432\u0433\u0435\u0437\u043A\u043C\u043D\u043E\u0440\u0441\u0442\u0443\u0445\u0448\u044C";
    private static final String LATIN_EQUIVALENTS = "olla6bre3kmhopctyxwb";

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
        StringBuilder key = new StringBuilder();
        for (char character : normalizedNick.toLowerCase(Locale.ROOT).toCharArray()) {
            int lookalikeIndex = LOOKALIKES.indexOf(character);
            key.append(0 > lookalikeIndex ? character : LATIN_EQUIVALENTS.charAt(lookalikeIndex));
        }
        return key.toString();
    }
}
