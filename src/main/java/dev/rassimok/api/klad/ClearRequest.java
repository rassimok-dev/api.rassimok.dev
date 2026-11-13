package dev.rassimok.api.klad;

import com.fasterxml.jackson.databind.JsonNode;

// A field is null when it is missing or not of its JSON type.
record ClearRequest(Integer level, String shape, Integer revision, Integer ticks, Integer chests, Integer chestsTotal) {

    static ClearRequest from(JsonNode body) {
        return new ClearRequest(
                JsonFields.integer(body, "level"),
                JsonFields.text(body, "shape"),
                JsonFields.integer(body, "revision"),
                JsonFields.integer(body, "ticks"),
                JsonFields.integer(body, "chests"),
                JsonFields.integer(body, "chestsTotal"));
    }

    boolean isValid() {
        return Rules.isBetween(level, Rules.LEVEL_MIN, Rules.LEVEL_MAX)
                && Rules.isShape(shape)
                && Rules.isBetween(revision, Rules.REVISION_MIN, Rules.REVISION_MAX)
                && Rules.isBetween(ticks, Rules.TICKS_FLOOR, Rules.CLEAR_TICKS_MAX)
                && Rules.isBetween(chestsTotal, Rules.CHESTS_TOTAL_MIN, Rules.CHESTS_TOTAL_MAX)
                && Rules.isBetween(chests, 0, chestsTotal);
    }
}
