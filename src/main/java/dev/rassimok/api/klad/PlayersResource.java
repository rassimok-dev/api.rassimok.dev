package dev.rassimok.api.klad;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.RestResponse;

import java.time.Instant;
import java.util.UUID;

@Path("/players")
@Produces(MediaType.APPLICATION_JSON)
public class PlayersResource {

    private static final String FIELD_NICK = "nick";

    @Inject
    Sessions sessions;

    @Inject
    PresenceStore presence;

    @Inject
    RoomStore rooms;

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public RestResponse<PlayerView> create(JsonNode body, @CookieParam(Sessions.COOKIE) String token) {
        String nick = Nicks.normalize(JsonFields.text(body, FIELD_NICK));
        String kind = JsonFields.text(body, Rules.FIELD_KIND);
        if (!Nicks.isValid(nick)) {
            throw new ApiException(Response.Status.BAD_REQUEST, ApiException.INVALID_NICK);
        }
        if (!Rules.isKind(kind)) {
            throw new ApiException(Response.Status.BAD_REQUEST, ApiException.INVALID_KIND);
        }
        if (sessions.isSignedIn(token)) {
            throw new ApiException(Response.Status.CONFLICT, ApiException.ALREADY_SIGNED_IN);
        }
        Instant now = Instant.now();
        Player player = new Player();
        player.id = UUID.randomUUID();
        player.nick = nick;
        player.nickKey = Nicks.key(nick);
        if (Player.isNickKeyTaken(player.nickKey, player.id)) {
            throw new ApiException(Response.Status.CONFLICT, ApiException.NICK_TAKEN);
        }
        player.kind = kind;
        player.createdAt = now;
        player.lastSeenAt = now;
        player.persist();
        String newToken = sessions.create(player, now);
        return RestResponse.ResponseBuilder.create(RestResponse.Status.CREATED, PlayerView.of(player))
                .header(HttpHeaders.SET_COOKIE, Sessions.cookie(newToken))
                .build();
    }

    @GET
    @Path("/me")
    @Transactional
    public RestResponse<PlayerView> me(@CookieParam(Sessions.COOKIE) String token) {
        Sessions.SignedIn signedIn = sessions.require(token);
        return Sessions.withRefreshedCookie(RestResponse.ResponseBuilder.ok(PlayerView.of(signedIn.player())), signedIn);
    }

    @PATCH
    @Path("/me")
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public RestResponse<PlayerView> rename(JsonNode body, @CookieParam(Sessions.COOKIE) String token) {
        Sessions.SignedIn signedIn = sessions.require(token);
        if (JsonFields.hasField(body, Rules.FIELD_KIND)) {
            throw new ApiException(Response.Status.BAD_REQUEST, ApiException.KIND_FIXED);
        }
        String nick = Nicks.normalize(JsonFields.text(body, FIELD_NICK));
        if (!Nicks.isValid(nick)) {
            throw new ApiException(Response.Status.BAD_REQUEST, ApiException.INVALID_NICK);
        }
        Player player = signedIn.player();
        String nickKey = Nicks.key(nick);
        if (Player.isNickKeyTaken(nickKey, player.id)) {
            throw new ApiException(Response.Status.CONFLICT, ApiException.NICK_TAKEN);
        }
        player.nick = nick;
        player.nickKey = nickKey;
        Player.flushNickKey();
        return Sessions.withRefreshedCookie(RestResponse.ResponseBuilder.ok(PlayerView.of(player)), signedIn);
    }

    @DELETE
    @Path("/me")
    @Transactional
    public RestResponse<Void> delete(@CookieParam(Sessions.COOKIE) String token) {
        Player player = sessions.require(token).player();
        // The code row before the player: verify locks them in that order, so
        // deleting them the other way round could deadlock with it.
        if (null != player.email) {
            LoginCode.delete("emailHash", Sessions.hash(player.email));
        }
        // The player's sessions and results go with it: on delete cascade.
        Player.delete("id", player.id);
        presence.forget(player.id);
        rooms.forget(player.id);
        return RestResponse.ResponseBuilder.<Void>create(RestResponse.Status.NO_CONTENT)
                .header(HttpHeaders.SET_COOKIE, Sessions.clearedCookie())
                .build();
    }
}
