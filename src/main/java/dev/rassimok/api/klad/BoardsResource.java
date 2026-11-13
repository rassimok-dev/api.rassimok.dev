package dev.rassimok.api.klad;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.regex.Pattern;

// Query parameters arrive as strings: a failed int conversion would be a
// 404 by the JAX-RS rules, not the contract's 400.
@Path("/boards")
@Produces(MediaType.APPLICATION_JSON)
public class BoardsResource {

    private static final Pattern DIGITS = Pattern.compile("[0-9]{1,4}");

    @Inject
    Boards boards;

    @GET
    @Path("/records")
    public Boards.RecordsBoard records(@QueryParam("kind") String kind) {
        return boards.records(checkedKind(kind));
    }

    @GET
    @Path("/level")
    public Boards.LevelBoard level(@QueryParam("kind") String kind, @QueryParam("level") String level,
                                   @QueryParam("shape") String shape, @QueryParam("revision") String revision) {
        String checkedKind = checkedKind(kind);
        int levelNumber = checkedNumber(level, Rules.LEVEL_MIN, Rules.LEVEL_MAX);
        int revisionNumber = checkedNumber(revision, Rules.REVISION_MIN, Rules.REVISION_MAX);
        if (!Rules.isShape(shape)) {
            throw new ApiException(Response.Status.BAD_REQUEST, ApiException.INVALID_QUERY);
        }
        return boards.level(checkedKind, levelNumber, shape, revisionNumber);
    }

    @GET
    @Path("/chests")
    public Boards.ChestsBoard chests(@QueryParam("kind") String kind) {
        return boards.chests(checkedKind(kind));
    }

    private static String checkedKind(String kind) {
        String checkedKind = null == kind ? Rules.KIND_HUMAN : kind;
        if (!Rules.isKind(checkedKind)) {
            throw new ApiException(Response.Status.BAD_REQUEST, ApiException.INVALID_KIND);
        }
        return checkedKind;
    }

    private static int checkedNumber(String value, int minimum, int maximum) {
        Integer number = null != value && DIGITS.matcher(value).matches() ? Integer.valueOf(value) : null;
        if (!Rules.isBetween(number, minimum, maximum)) {
            throw new ApiException(Response.Status.BAD_REQUEST, ApiException.INVALID_QUERY);
        }
        return number;
    }
}
