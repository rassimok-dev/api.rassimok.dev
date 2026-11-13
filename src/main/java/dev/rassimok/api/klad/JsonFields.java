package dev.rassimok.api.klad;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Request bodies are read as JSON trees, so a field of the wrong type is
 * null here and fails the contract's own check. Binding to a record would
 * turn 900.5 into 900 and "3" into 3, and too large a number into a
 * framework error.
 */
final class JsonFields {

    private JsonFields() {
    }

    static Integer integer(JsonNode body, String name) {
        JsonNode value = null == body ? null : body.get(name);
        return null != value && value.isInt() ? value.intValue() : null;
    }

    static String text(JsonNode body, String name) {
        JsonNode value = null == body ? null : body.get(name);
        return null != value && value.isTextual() ? value.textValue() : null;
    }

    static Boolean booleanValue(JsonNode body, String name) {
        JsonNode value = null == body ? null : body.get(name);
        return null != value && value.isBoolean() ? value.booleanValue() : null;
    }

    static boolean hasField(JsonNode body, String name) {
        return null != body && body.has(name);
    }
}
