package dev.rassimok.api.klad;

import java.time.Duration;

/** The values and bounds the API accepts, in one place. */
final class Rules {

    static final String KIND_HUMAN = "human";
    static final String KIND_AGENT = "agent";

    static final String SHAPE_LANDSCAPE = "landscape";
    static final String SHAPE_PORTRAIT = "portrait";

    static final int LEVEL_MIN = 1;
    static final int LEVEL_MAX = 5;
    static final int REVISION_MIN = 1;
    static final int REVISION_MAX = 1000;

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
}
