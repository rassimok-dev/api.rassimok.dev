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
