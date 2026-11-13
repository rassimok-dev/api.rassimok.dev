package dev.rassimok.api.klad;

import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.inject.Singleton;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;

import java.util.Locale;
import java.util.Optional;

/**
 * Every error leaves the API as {"error":"<code>"}, never with a stack trace
 * or a database message. Framework errors are named after their HTTP status.
 */
@Singleton
public class Errors {

    private static final Logger LOG = Logger.getLogger(Errors.class);

    @RegisterForReflection
    record ErrorBody(String error) {
    }

    static Response response(Response.Status status, String code) {
        return Response.status(status).type(MediaType.APPLICATION_JSON).entity(new ErrorBody(code)).build();
    }

    static String codeFor(Response.Status status) {
        return status.name().toLowerCase(Locale.ROOT);
    }

    // @Consumes alone lets a body without a content type, or sent as
    // application/octet-stream, through to the JSON reader.
    @ServerRequestFilter
    public Optional<Response> refuseNonJsonBody(ContainerRequestContext request) {
        boolean hasBody = 0 < request.getLength() || null != request.getHeaderString("Transfer-Encoding");
        MediaType mediaType = request.getMediaType();
        boolean isJson = null != mediaType && MediaType.APPLICATION_JSON_TYPE.isCompatible(mediaType);
        Response.Status status = Response.Status.UNSUPPORTED_MEDIA_TYPE;
        return hasBody && !isJson ? Optional.of(response(status, codeFor(status))) : Optional.empty();
    }

    @ServerExceptionMapper
    public Response mapApiException(ApiException exception) {
        return response(exception.status(), exception.code());
    }

    @ServerExceptionMapper
    public Response mapWebApplicationException(WebApplicationException exception) {
        Response original = exception.getResponse();
        Response.Status status = Response.Status.fromStatusCode(original.getStatus());
        String code = null == status ? "error" : codeFor(status);
        return Response.fromResponse(original)
                .type(MediaType.APPLICATION_JSON)
                .entity(new ErrorBody(code))
                .build();
    }

    @ServerExceptionMapper
    public Response mapUnexpected(Exception exception) {
        LOG.error("Unexpected error", exception);
        return response(Response.Status.INTERNAL_SERVER_ERROR, codeFor(Response.Status.INTERNAL_SERVER_ERROR));
    }
}
