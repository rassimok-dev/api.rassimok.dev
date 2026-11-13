package dev.rassimok.api.klad;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.RestResponse;

@Path("/presence")
@Produces(MediaType.APPLICATION_JSON)
public class PresenceResource {

    @Inject
    Sessions sessions;

    @Inject
    PresenceStore presence;

    @Inject
    RoomStore rooms;

    @POST
    @Transactional
    public RestResponse<Void> heartbeat(@CookieParam(Sessions.COOKIE) String token) {
        Sessions.SignedIn signedIn = sessions.require(token);
        LobbyPlayer player = LobbyPlayer.of(signedIn.player());
        rooms.markSeen(player);
        presence.heartbeat(player);
        return Sessions.withRefreshedCookie(RestResponse.ResponseBuilder.<Void>create(RestResponse.Status.NO_CONTENT),
                signedIn);
    }

    @GET
    @Transactional
    public RestResponse<PresenceStore.PresenceView> online(@CookieParam(Sessions.COOKIE) String token) {
        Sessions.SignedIn signedIn = sessions.require(token);
        return Sessions.withRefreshedCookie(RestResponse.ResponseBuilder.ok(presence.online()), signedIn);
    }
}
