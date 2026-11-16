package dev.rassimok.api.klad;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestResponse;

import java.time.Instant;

/**
 * Sign-in with Google in three steps (contract section 5): the start sends
 * the browser to Google, Google's return proves the address, and the game
 * finishes the sign-in from its own page, where the session cookie is sent.
 */
@Path("/login/google")
public class GoogleLoginResource {

    private static final Logger LOG = Logger.getLogger(GoogleLoginResource.class);

    @Inject
    GoogleLogins googleLogins;

    @Inject
    AccountLogins accountLogins;

    // Without the Google settings no cookie is set and nothing is started.
    @GET
    public RestResponse<Void> start() {
        RestResponse.ResponseBuilder<Void> response;
        if (googleLogins.isAvailable()) {
            String secret = googleLogins.newSecret();
            response = redirect(googleLogins.authorizationUrl(secret))
                    .header(HttpHeaders.SET_COOKIE, GoogleLogins.cookie(secret));
        } else {
            response = redirect(googleLogins.gameUrl(GoogleLogins.Outcome.UNAVAILABLE));
        }
        return response.build();
    }

    // Always back to the game, never a JSON page. On success the cookie is
    // set again, so it lasts as long as the address waits.
    @GET
    @Path("callback")
    public RestResponse<Void> callback(@CookieParam(GoogleLogins.COOKIE) String secret,
                                       @QueryParam("state") String state,
                                       @QueryParam("code") String code,
                                       @QueryParam("error") String error) {
        GoogleLogins.Outcome outcome;
        try {
            outcome = googleLogins.acceptReturn(secret, state, code, error);
        } catch (RuntimeException e) {
            LOG.errorf("Google sign-in failed: %s", e.getClass().getName());
            outcome = GoogleLogins.Outcome.FAILED;
        }
        RestResponse.ResponseBuilder<Void> response = redirect(googleLogins.gameUrl(outcome));
        if (googleLogins.isAvailable()) {
            response.header(HttpHeaders.SET_COOKIE, GoogleLogins.Outcome.SIGNED_IN == outcome
                    ? GoogleLogins.cookie(secret)
                    : GoogleLogins.clearedCookie());
        }
        return response.build();
    }

    // The rules and the answer of POST /login/verify after its code check.
    @POST
    @Path("finish")
    @Produces(MediaType.APPLICATION_JSON)
    @Transactional
    public RestResponse<LoginView> finish(@CookieParam(GoogleLogins.COOKIE) String secret,
                                          @CookieParam(Sessions.COOKIE) String token) {
        GoogleLogins.WaitingAddress address = googleLogins.take(secret);
        RestResponse.ResponseBuilder<LoginView> response;
        try {
            response = accountLogins.signIn(address.email(), token, Instant.now());
        } catch (RuntimeException e) {
            // After 401 no_session or 409 nick_taken the game creates a guest
            // or renames it and finishes again, with no second trip to Google.
            googleLogins.giveBack(secret, address);
            throw e;
        }
        return response.header(HttpHeaders.SET_COOKIE, GoogleLogins.clearedCookie()).build();
    }

    // The Location is taken as it is, never built from the request: behind
    // the site's proxy the API sees its own host.
    private static RestResponse.ResponseBuilder<Void> redirect(String location) {
        return RestResponse.ResponseBuilder.<Void>create(RestResponse.Status.FOUND)
                .header(HttpHeaders.LOCATION, location);
    }
}
