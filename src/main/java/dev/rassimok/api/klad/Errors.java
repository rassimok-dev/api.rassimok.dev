package dev.rassimok.api.klad;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;
import io.vertx.core.http.HttpServerResponse;
import jakarta.inject.Singleton;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;
import org.jboss.resteasy.reactive.server.SimpleResourceInfo;

import java.io.IOException;
import java.util.Arrays;
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

    // Only methods that take a JsonNode body are checked: @Consumes alone lets a
    // body with no content type, */* or application/* reach the reader.
    // The others ignore any body; the proxy may forward an empty chunked one.
    @ServerRequestFilter
    public Optional<Response> refuseNonJsonBody(ContainerRequestContext request, SimpleResourceInfo resource) {
        boolean isBodyEndpoint = Arrays.stream(resource.parameterTypes()).anyMatch(JsonNode.class::isAssignableFrom);
        boolean shouldRefuse = isBodyEndpoint && !isJson(parsedMediaType(request));
        Response.Status status = Response.Status.UNSUPPORTED_MEDIA_TYPE;
        return shouldRefuse ? Optional.of(response(status, codeFor(status))) : Optional.empty();
    }

    // getMediaType throws on a type that does not parse; that is not JSON either.
    private static MediaType parsedMediaType(ContainerRequestContext request) {
        MediaType mediaType;
        try {
            mediaType = request.getMediaType();
        } catch (IllegalArgumentException e) {
            mediaType = null;
        }
        return mediaType;
    }

    // Only application/json with at most a charset. MediaType.isCompatible
    // would also let */* and application/* through.
    private static boolean isJson(MediaType mediaType) {
        return null != mediaType
                && MediaType.APPLICATION_JSON_TYPE.getType().equalsIgnoreCase(mediaType.getType())
                && MediaType.APPLICATION_JSON_TYPE.getSubtype().equalsIgnoreCase(mediaType.getSubtype())
                && mediaType.getParameters().keySet().stream().allMatch(MediaType.CHARSET_PARAMETER::equalsIgnoreCase);
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

    // A chunked body over the limit fails the JSON reader after Vert.x has
    // already sent the 413. Rethrown, Quarkus logs it at debug level only;
    // mapped, it would log two error stacks for every such request.
    @ServerExceptionMapper
    public Response mapIOException(IOException exception, HttpServerResponse response) throws IOException {
        if (response.headWritten()) {
            throw exception;
        }
        return mapUnexpected(exception);
    }

    @ServerExceptionMapper
    public Response mapUnexpected(Exception exception) {
        LOG.error("Unexpected error", exception);
        return response(Response.Status.INTERNAL_SERVER_ERROR, codeFor(Response.Status.INTERNAL_SERVER_ERROR));
    }
}
