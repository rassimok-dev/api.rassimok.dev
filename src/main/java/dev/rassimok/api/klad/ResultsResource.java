package dev.rassimok.api.klad;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.RestResponse;

import java.time.Instant;
import java.util.Map;

// At the root path this resource also makes RESTEasy answer every unknown
// path, so those 404s reach the JSON exception mapper too.
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
public class ResultsResource {

    @Inject
    Sessions sessions;

    @POST
    @Path("clears")
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public RestResponse<Map<String, Object>> submitClear(JsonNode body, @CookieParam(Sessions.COOKIE) String token) {
        Sessions.SignedIn signedIn = sessions.require(token);
        ClearRequest request = ClearRequest.from(body);
        if (!request.isValid()) {
            throw new ApiException(Response.Status.BAD_REQUEST, ApiException.INVALID_RESULT);
        }
        Instant now = Instant.now();
        checkRateLimit(signedIn.player(), now);
        LevelClear clear = new LevelClear();
        clear.playerId = signedIn.player().id;
        clear.level = request.level();
        clear.shape = request.shape();
        clear.revision = request.revision();
        clear.ticks = request.ticks();
        clear.chests = request.chests();
        clear.chestsTotal = request.chestsTotal();
        clear.createdAt = now;
        clear.persist();
        return Sessions.withRefreshedCookie(RestResponse.ResponseBuilder.create(RestResponse.Status.CREATED, Map.of()), signedIn);
    }

    @POST
    @Path("runs")
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public RestResponse<Map<String, Object>> submitRun(JsonNode body, @CookieParam(Sessions.COOKIE) String token) {
        Sessions.SignedIn signedIn = sessions.require(token);
        RunRequest request = RunRequest.from(body);
        if (!request.isValid()) {
            throw new ApiException(Response.Status.BAD_REQUEST, ApiException.INVALID_RESULT);
        }
        Instant now = Instant.now();
        checkRateLimit(signedIn.player(), now);
        GameRun run = new GameRun();
        run.playerId = signedIn.player().id;
        run.levelsCleared = request.levelsCleared();
        run.score = request.score();
        run.ticksTotal = request.ticks();
        run.finishedAt = now;
        run.persist();
        return Sessions.withRefreshedCookie(RestResponse.ResponseBuilder.create(RestResponse.Status.CREATED, Map.of()), signedIn);
    }

    // Counted in the database, which outlives a restart and is shared with the
    // new revision's instance while a deploy rolls out. Locking the player
    // row makes one player's parallel submissions count one after another,
    // so a burst cannot slip past the limit.
    private static void checkRateLimit(Player player, Instant now) {
        Player.getEntityManager().lock(player, LockModeType.PESSIMISTIC_WRITE);
        Instant windowStart = now.minus(Rules.SUBMISSION_WINDOW);
        long recentSubmissions = LevelClear.count("playerId = ?1 and createdAt > ?2", player.id, windowStart)
                + GameRun.count("playerId = ?1 and finishedAt > ?2", player.id, windowStart);
        if (Rules.SUBMISSIONS_PER_WINDOW <= recentSubmissions) {
            throw new ApiException(Response.Status.TOO_MANY_REQUESTS, ApiException.RATE_LIMITED);
        }
    }
}
