package dev.rassimok.api.klad;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.mailer.Mailer;
import io.vertx.ext.mail.SMTPException;
import jakarta.inject.Inject;
import jakarta.persistence.PessimisticLockException;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestResponse;

import java.time.Instant;
import java.util.Map;

@Path("/")
@Produces(MediaType.APPLICATION_JSON)
public class LoginResource {

    private static final Logger LOG = Logger.getLogger(LoginResource.class);
    private static final String FIELD_EMAIL = "email";
    private static final String FIELD_CODE = "code";

    @Inject
    Sessions sessions;

    @Inject
    LoginCodes loginCodes;

    @Inject
    Mailer mailer;

    // The same answer whether or not the email has an account, so nobody
    // can find out who plays here. The mail goes out after the code is
    // stored, so no database lock is held while the mail server answers.
    @POST
    @Path("login/code")
    @Consumes(MediaType.APPLICATION_JSON)
    public RestResponse<Map<String, Object>> requestCode(JsonNode body) {
        String email = Emails.normalize(JsonFields.text(body, FIELD_EMAIL));
        if (!Emails.isValid(email)) {
            throw new ApiException(Response.Status.BAD_REQUEST, ApiException.INVALID_EMAIL);
        }
        String code;
        try {
            code = loginCodes.issue(email, Instant.now());
        } catch (PessimisticLockException e) {
            // Code requests wait at most 2 s for their lock; in a flood, try later.
            throw new ApiException(Response.Status.TOO_MANY_REQUESTS, ApiException.RATE_LIMITED);
        }
        try {
            mailer.send(LoginCodes.mail(email, code));
        } catch (RuntimeException e) {
            // The mail server's reply often repeats the address, so neither the
            // exception nor its message may reach the log.
            Throwable rootCause = rootCause(e);
            int replyCode = rootCause instanceof SMTPException smtpException ? smtpException.getReplyCode() : 0;
            LOG.errorf("Login code mail not sent: %s, reply %d", rootCause.getClass().getName(), replyCode);
            Response.Status status = Response.Status.INTERNAL_SERVER_ERROR;
            throw new ApiException(status, Errors.codeFor(status));
        }
        return RestResponse.ResponseBuilder.create(RestResponse.Status.ACCEPTED, Map.<String, Object>of()).build();
    }

    // The code is checked before anything else, so only someone holding it
    // learns whether the email has an account. dontRollbackOn keeps the
    // counted attempt and the deleted code when the answer is an error.
    @POST
    @Path("login/verify")
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional(dontRollbackOn = ApiException.class)
    public RestResponse<LoginView> verify(JsonNode body, @CookieParam(Sessions.COOKIE) String token) {
        String email = Emails.normalize(JsonFields.text(body, FIELD_EMAIL));
        Instant now = Instant.now();
        LoginCodes.consume(email, JsonFields.text(body, FIELD_CODE), now);
        PlayerSession deviceSession = sessions.findValid(token, now);
        Player devicePlayer = null == deviceSession ? null : Player.findById(deviceSession.playerId);
        Player player = Player.find("email", email).firstResult();
        if (null == player) {
            player = turnGuestIntoAccount(devicePlayer, email);
        }
        // A new token for the new identity; the device's old one stops working.
        if (null != deviceSession) {
            deviceSession.delete();
        }
        player.lastSeenAt = now;
        String newToken = sessions.create(player, now);
        return RestResponse.ResponseBuilder.ok(LoginView.of(player, false))
                .header(HttpHeaders.SET_COOKIE, Sessions.cookie(newToken))
                .build();
    }

    @POST
    @Path("logout")
    @Transactional
    public RestResponse<Void> logout(@CookieParam(Sessions.COOKIE) String token) {
        sessions.require(token);
        PlayerSession.deleteById(Sessions.hash(token));
        return RestResponse.ResponseBuilder.<Void>create(RestResponse.Status.NO_CONTENT)
                .header(HttpHeaders.SET_COOKIE, Sessions.clearedCookie())
                .build();
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable root = throwable;
        while (null != root.getCause() && root != root.getCause()) {
            root = root.getCause();
        }
        return root;
    }

    private static Player turnGuestIntoAccount(Player devicePlayer, String email) {
        boolean isGuest = null != devicePlayer && null == devicePlayer.email;
        if (!isGuest) {
            throw new ApiException(Response.Status.UNAUTHORIZED, ApiException.NO_SESSION);
        }
        if (0 < Player.count("nickKey = ?1 and email is not null", devicePlayer.nickKey)) {
            throw new ApiException(Response.Status.CONFLICT, ApiException.NICK_TAKEN);
        }
        devicePlayer.email = email;
        return devicePlayer;
    }
}
