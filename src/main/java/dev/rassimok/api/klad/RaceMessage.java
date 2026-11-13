package dev.rassimok.api.klad;

import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonObject;

/** A message from a race client, one JSON object per text frame (contract section 8). */
sealed interface RaceMessage {

    String FIELD_TYPE = "type";
    String FIELD_X = "x";
    String FIELD_Y = "y";
    String FIELD_TICK = "tick";
    String TYPE_PING = "ping";
    String TYPE_POS = "pos";
    String TYPE_FINISH = "finish";

    record Ping() implements RaceMessage {
    }

    record Position(double x, double y, long tick) implements RaceMessage {
    }

    record Finish(long ticks) implements RaceMessage {
    }

    // Not JSON, not an object, an unknown type, or a missing or wrong field.
    record Bad() implements RaceMessage {
    }

    static RaceMessage parse(String text) {
        JsonObject json = object(text);
        Object type = null == json ? null : json.getValue(FIELD_TYPE);
        RaceMessage message;
        if (TYPE_PING.equals(type)) {
            message = new Ping();
        } else if (TYPE_POS.equals(type) && isFiniteNumber(json.getValue(FIELD_X))
                && isFiniteNumber(json.getValue(FIELD_Y)) && isCount(json.getValue(FIELD_TICK))) {
            message = new Position(json.getDouble(FIELD_X), json.getDouble(FIELD_Y), json.getLong(FIELD_TICK));
        } else if (TYPE_FINISH.equals(type) && isCount(json.getValue(Rules.FIELD_TICKS))) {
            message = new Finish(json.getLong(Rules.FIELD_TICKS));
        } else {
            message = new Bad();
        }
        return message;
    }

    // Text that does not start with a brace is no object. Skipping the parser
    // spares a flood of empty or junk frames an exception each.
    private static JsonObject object(String text) {
        JsonObject json;
        try {
            json = text.strip().startsWith("{") ? new JsonObject(text) : null;
        } catch (DecodeException e) {
            json = null;
        }
        return json;
    }

    // 1e999 parses as infinity, which would go out as the string "Infinity".
    private static boolean isFiniteNumber(Object value) {
        return value instanceof Number number && Double.isFinite(number.doubleValue());
    }

    // A non-negative JSON integer within a long: 5.0 is not one.
    private static boolean isCount(Object value) {
        return (value instanceof Integer || value instanceof Long) && 0 <= ((Number) value).longValue();
    }
}
