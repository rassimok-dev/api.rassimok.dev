package dev.rassimok.api.klad;

import java.time.Duration;
import java.util.List;

/** The values and bounds the API accepts, in one place. */
final class Rules {

    static final String FIELD_KIND = "kind";
    static final String FIELD_LEVEL = "level";
    static final String FIELD_SHAPE = "shape";
    static final String FIELD_TICKS = "ticks";

    static final String KIND_HUMAN = "human";
    static final String KIND_AGENT = "agent";

    static final String SHAPE_LANDSCAPE = "landscape";
    static final String SHAPE_PORTRAIT = "portrait";

    static final int LEVEL_MIN = 1;
    static final int LEVEL_MAX = 5;
    // The current revision of each level, level 1 first. It must change
    // together with the revisions in the site's klad/levels.js; until the
    // API deploys, clears of a new revision are refused.
    static final List<Integer> LEVEL_REVISIONS = List.of(1, 1, 1, 1, 1);

    // 5 s at the engine's 120 ticks a second, below any real clear of a
    // level. Revise it once real clear times are known.
    static final int TICKS_FLOOR = 600;
    static final int CLEAR_TICKS_MAX = 432_000;
    static final int RUN_TICKS_MAX = 2_160_000;

    static final int CHESTS_TOTAL_MIN = 1;
    static final int CHESTS_TOTAL_MAX = 100;
    static final int RUN_SCORE_MAX = 10_000_000;

    static final int SUBMISSIONS_PER_WINDOW = 30;
    static final Duration SUBMISSION_WINDOW = Duration.ofSeconds(60);

    static final int BOARD_SIZE = 20;

    static final int EMAIL_MAX_LENGTH = 254;
    static final Duration CODE_LIFETIME = Duration.ofMinutes(10);
    static final int CODE_ATTEMPTS = 5;
    static final int CODES_PER_EMAIL = 3;
    static final Duration CODES_PER_EMAIL_WINDOW = Duration.ofHours(1);
    // Resend's free plan sends 100 mails a day; the rest is left for test
    // mails sent by hand.
    static final int MAILS_PER_DAY = 80;
    static final Duration MAILS_PER_DAY_WINDOW = Duration.ofDays(1);

    private Rules() {
    }

    static boolean isKind(String kind) {
        return KIND_HUMAN.equals(kind) || KIND_AGENT.equals(kind);
    }

    static boolean isShape(String shape) {
        return SHAPE_LANDSCAPE.equals(shape) || SHAPE_PORTRAIT.equals(shape);
    }

    static boolean isBetween(Integer value, int minimum, int maximum) {
        return null != value && minimum <= value && value <= maximum;
    }

    static int currentRevision(int level) {
        return LEVEL_REVISIONS.get(level - LEVEL_MIN);
    }

    static boolean isCurrentRevision(Integer level, Integer revision) {
        return isBetween(level, LEVEL_MIN, LEVEL_MAX) && null != revision && currentRevision(level) == revision;
    }
}
