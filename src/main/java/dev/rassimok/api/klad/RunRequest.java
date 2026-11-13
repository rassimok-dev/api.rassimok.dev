package dev.rassimok.api.klad;

import com.fasterxml.jackson.databind.JsonNode;

// A field is null when it is missing or not a JSON integer.
record RunRequest(Integer levelsCleared, Integer score, Integer ticks) {

    static RunRequest from(JsonNode body) {
        return new RunRequest(
                JsonFields.integer(body, "levelsCleared"),
                JsonFields.integer(body, "score"),
                JsonFields.integer(body, "ticks"));
    }

    boolean isValid() {
        return Rules.isBetween(levelsCleared, 0, Rules.LEVEL_MAX)
                && Rules.isBetween(score, 0, Rules.RUN_SCORE_MAX)
                && Rules.isBetween(ticks, Rules.TICKS_FLOOR * levelsCleared, Rules.RUN_TICKS_MAX);
    }
}
