package dev.rassimok.api.klad;

import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.List;

/** A race socket that keeps what the room sends it, and its first close. */
final class FakeSocket implements PlayerSocket {

    final List<JsonObject> messages = new ArrayList<>();
    String closeReason;

    @Override
    public void send(String message) {
        messages.add(new JsonObject(message));
    }

    @Override
    public void close(String reason) {
        if (null == closeReason) {
            closeReason = reason;
        }
    }
}
