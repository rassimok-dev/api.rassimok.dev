package dev.rassimok.api.klad;

import jakarta.ws.rs.core.Response;

class ApiException extends RuntimeException {

    static final String INVALID_NICK = "invalid_nick";
    static final String INVALID_KIND = "invalid_kind";
    static final String KIND_FIXED = "kind_fixed";
    static final String ALREADY_SIGNED_IN = "already_signed_in";
    static final String NO_SESSION = "no_session";
    static final String INVALID_RESULT = "invalid_result";
    static final String INVALID_QUERY = "invalid_query";
    static final String RATE_LIMITED = "rate_limited";
    static final String INVALID_EMAIL = "invalid_email";
    static final String INVALID_CODE = "invalid_code";
    static final String TOO_MANY_ATTEMPTS = "too_many_attempts";
    static final String NICK_TAKEN = "nick_taken";
    static final String NOT_FOUND = "not_found";
    static final String ROOM_FULL = "room_full";
    static final String NOT_IN_LOBBY = "not_in_lobby";
    static final String NOT_HOST = "not_host";
    static final String NOT_READY = "not_ready";
    static final String INVALID_READY = "invalid_ready";
    static final String INVALID_LEVEL = "invalid_level";
    static final String INVALID_SHAPE = "invalid_shape";

    private final Response.Status status;
    private final String code;

    ApiException(Response.Status status, String code) {
        super(code);
        this.status = status;
        this.code = code;
    }

    Response.Status status() {
        return status;
    }

    String code() {
        return code;
    }
}
