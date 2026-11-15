package dev.rassimok.api.klad;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.RestResponse;

import java.util.Map;
import java.util.regex.Pattern;

// The session is checked first everywhere, so a request without one learns
// nothing about the rooms.
@Path("/rooms")
@Produces(MediaType.APPLICATION_JSON)
public class RoomResource {

    private static final String FIELD_ID = "id";
    private static final String FIELD_READY = "ready";
    private static final String FIELD_LISTED = "listed";
    private static final String LISTED_TRUE = "true";
    private static final String LISTED_FALSE = "false";
    private static final String FIELD_AFTER = "after";
    private static final String FIELD_TEXT = "text";
    // Nine digits always fit an int, so no value of after can fail the conversion.
    private static final Pattern AFTER_DIGITS = Pattern.compile("[0-9]{1,9}");

    @Inject
    Sessions sessions;

    @Inject
    RoomStore rooms;

    // A query parameter, as this endpoint ignores any body. Only the exact
    // words count, so a typo is refused instead of making the room link only.
    @POST
    @Transactional
    public RestResponse<Map<String, String>> create(@QueryParam(FIELD_LISTED) String listed,
                                                    @CookieParam(Sessions.COOKIE) String token) {
        Sessions.SignedIn signedIn = sessions.require(token);
        boolean isListed = null == listed || LISTED_TRUE.equals(listed);
        if (!isListed && !LISTED_FALSE.equals(listed)) {
            throw new ApiException(Response.Status.BAD_REQUEST, ApiException.INVALID_LISTED);
        }
        String id = rooms.create(LobbyPlayer.of(signedIn.player()), isListed);
        return Sessions.withRefreshedCookie(
                RestResponse.ResponseBuilder.create(RestResponse.Status.CREATED, Map.of(FIELD_ID, id)), signedIn);
    }

    @GET
    @Transactional
    public RestResponse<RoomListView> list(@CookieParam(Sessions.COOKIE) String token) {
        Sessions.SignedIn signedIn = sessions.require(token);
        return Sessions.withRefreshedCookie(RestResponse.ResponseBuilder.ok(rooms.list()), signedIn);
    }

    // Without after, the answer holds every chat message the caller may read.
    @GET
    @Path("/{id}")
    @Transactional
    public RestResponse<RoomView> find(@PathParam(FIELD_ID) String id, @QueryParam(FIELD_AFTER) String after,
                                       @CookieParam(Sessions.COOKIE) String token) {
        Sessions.SignedIn signedIn = sessions.require(token);
        if (null != after && !AFTER_DIGITS.matcher(after).matches()) {
            throw new ApiException(Response.Status.BAD_REQUEST, ApiException.INVALID_QUERY);
        }
        int afterNumber = null == after ? 0 : Integer.parseInt(after);
        return ok(rooms.find(id, LobbyPlayer.of(signedIn.player()), afterNumber), signedIn);
    }

    @POST
    @Path("/{id}/join")
    @Transactional
    public RestResponse<RoomView> join(@PathParam(FIELD_ID) String id, @CookieParam(Sessions.COOKIE) String token) {
        Sessions.SignedIn signedIn = sessions.require(token);
        return ok(rooms.join(id, LobbyPlayer.of(signedIn.player())), signedIn);
    }

    @POST
    @Path("/{id}/leave")
    @Transactional
    public RestResponse<Void> leave(@PathParam(FIELD_ID) String id, @CookieParam(Sessions.COOKIE) String token) {
        Sessions.SignedIn signedIn = sessions.require(token);
        rooms.leave(id, LobbyPlayer.of(signedIn.player()));
        return Sessions.withRefreshedCookie(RestResponse.ResponseBuilder.<Void>create(RestResponse.Status.NO_CONTENT),
                signedIn);
    }

    @POST
    @Path("/{id}/ready")
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public RestResponse<RoomView> ready(@PathParam(FIELD_ID) String id, JsonNode body,
                                        @CookieParam(Sessions.COOKIE) String token) {
        Sessions.SignedIn signedIn = sessions.require(token);
        Boolean isReady = JsonFields.booleanValue(body, FIELD_READY);
        if (null == isReady) {
            throw new ApiException(Response.Status.BAD_REQUEST, ApiException.INVALID_READY);
        }
        return ok(rooms.setReady(id, LobbyPlayer.of(signedIn.player()), isReady), signedIn);
    }

    @POST
    @Path("/{id}/level")
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public RestResponse<RoomView> level(@PathParam(FIELD_ID) String id, JsonNode body,
                                        @CookieParam(Sessions.COOKIE) String token) {
        Sessions.SignedIn signedIn = sessions.require(token);
        Integer level = JsonFields.integer(body, Rules.FIELD_LEVEL);
        String shape = JsonFields.text(body, Rules.FIELD_SHAPE);
        if (!Rules.isBetween(level, Rules.LEVEL_MIN, Rules.LEVEL_MAX)) {
            throw new ApiException(Response.Status.BAD_REQUEST, ApiException.INVALID_LEVEL);
        }
        if (!Rules.isShape(shape)) {
            throw new ApiException(Response.Status.BAD_REQUEST, ApiException.INVALID_SHAPE);
        }
        return ok(rooms.chooseLevel(id, LobbyPlayer.of(signedIn.player()), level, shape), signedIn);
    }

    @POST
    @Path("/{id}/listed")
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public RestResponse<RoomView> listed(@PathParam(FIELD_ID) String id, JsonNode body,
                                         @CookieParam(Sessions.COOKIE) String token) {
        Sessions.SignedIn signedIn = sessions.require(token);
        Boolean isListed = JsonFields.booleanValue(body, FIELD_LISTED);
        if (null == isListed) {
            throw new ApiException(Response.Status.BAD_REQUEST, ApiException.INVALID_LISTED);
        }
        return ok(rooms.setListed(id, LobbyPlayer.of(signedIn.player()), isListed), signedIn);
    }

    @POST
    @Path("/{id}/start")
    @Transactional
    public RestResponse<RoomView> start(@PathParam(FIELD_ID) String id, @CookieParam(Sessions.COOKIE) String token) {
        Sessions.SignedIn signedIn = sessions.require(token);
        return ok(rooms.start(id, LobbyPlayer.of(signedIn.player())), signedIn);
    }

    @POST
    @Path("/{id}/messages")
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public RestResponse<RoomView.Message> send(@PathParam(FIELD_ID) String id, JsonNode body,
                                               @CookieParam(Sessions.COOKIE) String token) {
        Sessions.SignedIn signedIn = sessions.require(token);
        String text = ChatTexts.normalize(JsonFields.text(body, FIELD_TEXT));
        if (!ChatTexts.isValid(text)) {
            throw new ApiException(Response.Status.BAD_REQUEST, ApiException.INVALID_MESSAGE);
        }
        RoomView.Message message = rooms.send(id, LobbyPlayer.of(signedIn.player()), text);
        return Sessions.withRefreshedCookie(RestResponse.ResponseBuilder.create(RestResponse.Status.CREATED, message),
                signedIn);
    }

    private static RestResponse<RoomView> ok(RoomView room, Sessions.SignedIn signedIn) {
        return Sessions.withRefreshedCookie(RestResponse.ResponseBuilder.ok(room), signedIn);
    }
}
