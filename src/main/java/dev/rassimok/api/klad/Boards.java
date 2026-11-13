package dev.rassimok.api.klad;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.hibernate.Session;
import org.hibernate.query.NativeQuery;

import java.util.ArrayList;
import java.util.List;

/**
 * Top 20 per board, one entry per player (their best), ranked from 1. The
 * kind comes from the player, and the earlier result wins a tie.
 */
@ApplicationScoped
public class Boards {

    private static final String RECORDS = """
            select player.nick, player.email is null, best.levels_cleared, best.score, best.ticks_total
            from (
                select distinct on (game_run.player_id) game_run.*
                from game_run
                join player on player.id = game_run.player_id
                where player.kind = :kind
                order by game_run.player_id, game_run.levels_cleared desc, game_run.score desc,
                         game_run.ticks_total, game_run.finished_at, game_run.id
            ) best
            join player on player.id = best.player_id
            order by best.levels_cleared desc, best.score desc, best.ticks_total, best.finished_at, best.id
            limit :size
            """;

    private static final String FASTEST = """
            select player.nick, player.email is null, best.ticks
            from (
                select distinct on (level_clear.player_id) level_clear.*
                from level_clear
                join player on player.id = level_clear.player_id
                where player.kind = :kind and level_clear.level = :level
                  and level_clear.shape = :shape and level_clear.revision = :revision
                order by level_clear.player_id, level_clear.ticks, level_clear.created_at, level_clear.id
            ) best
            join player on player.id = best.player_id
            order by best.ticks, best.created_at, best.id
            limit :size
            """;

    private static final String LEVEL_CHESTS = """
            select player.nick, player.email is null, best.chests, best.chests_total, best.ticks
            from (
                select distinct on (level_clear.player_id) level_clear.*
                from level_clear
                join player on player.id = level_clear.player_id
                where player.kind = :kind and level_clear.level = :level
                  and level_clear.shape = :shape and level_clear.revision = :revision
                order by level_clear.player_id, level_clear.chests desc, level_clear.ticks,
                         level_clear.created_at, level_clear.id
            ) best
            join player on player.id = best.player_id
            order by best.chests desc, best.ticks, best.created_at, best.id
            limit :size
            """;

    // Only the newest revision of each level and shape that anyone cleared
    // counts, so a changed layout resets its share of the total.
    private static final String CHESTS = """
            with newest as (
                select level, shape, max(revision) as revision
                from level_clear
                group by level, shape
            ),
            best as (
                select level_clear.player_id, max(level_clear.chests) as chests
                from level_clear
                join newest using (level, shape, revision)
                group by level_clear.player_id, level_clear.level, level_clear.shape
            )
            select player.nick, player.email is null, sum(best.chests) as total_chests
            from best
            join player on player.id = best.player_id
            where player.kind = :kind
            group by player.id
            order by total_chests desc, player.nick_key, player.id
            limit :size
            """;

    @Inject
    Session hibernateSession;

    record RecordEntry(int rank, String nick, @JsonProperty("guest") boolean isGuest,
                       int levelsCleared, int score, int ticks) {
    }

    record FastestEntry(int rank, String nick, @JsonProperty("guest") boolean isGuest, int ticks) {
    }

    record LevelChestsEntry(int rank, String nick, @JsonProperty("guest") boolean isGuest,
                            int chests, int chestsTotal, int ticks) {
    }

    record ChestsEntry(int rank, String nick, @JsonProperty("guest") boolean isGuest, int chests) {
    }

    record RecordsBoard(List<RecordEntry> entries) {
    }

    record LevelBoard(List<FastestEntry> fastest, List<LevelChestsEntry> chests) {
    }

    record ChestsBoard(List<ChestsEntry> entries) {
    }

    RecordsBoard records(String kind) {
        List<RecordEntry> entries = new ArrayList<>();
        for (Object[] row : rows(query(RECORDS, kind))) {
            entries.add(new RecordEntry(entries.size() + 1, (String) row[0], (Boolean) row[1],
                    number(row[2]), number(row[3]), number(row[4])));
        }
        return new RecordsBoard(entries);
    }

    LevelBoard level(String kind, int level, String shape, int revision) {
        List<FastestEntry> fastest = new ArrayList<>();
        for (Object[] row : rows(levelQuery(FASTEST, kind, level, shape, revision))) {
            fastest.add(new FastestEntry(fastest.size() + 1, (String) row[0], (Boolean) row[1], number(row[2])));
        }
        List<LevelChestsEntry> chests = new ArrayList<>();
        for (Object[] row : rows(levelQuery(LEVEL_CHESTS, kind, level, shape, revision))) {
            chests.add(new LevelChestsEntry(chests.size() + 1, (String) row[0], (Boolean) row[1],
                    number(row[2]), number(row[3]), number(row[4])));
        }
        return new LevelBoard(fastest, chests);
    }

    ChestsBoard chests(String kind) {
        List<ChestsEntry> entries = new ArrayList<>();
        for (Object[] row : rows(query(CHESTS, kind))) {
            entries.add(new ChestsEntry(entries.size() + 1, (String) row[0], (Boolean) row[1], number(row[2])));
        }
        return new ChestsBoard(entries);
    }

    private NativeQuery<Object[]> query(String sql, String kind) {
        return hibernateSession.createNativeQuery(sql, Object[].class).setParameter("kind", kind);
    }

    private NativeQuery<Object[]> levelQuery(String sql, String kind, int level, String shape, int revision) {
        return query(sql, kind)
                .setParameter("level", level)
                .setParameter("shape", shape)
                .setParameter("revision", revision);
    }

    private static List<Object[]> rows(NativeQuery<Object[]> query) {
        return query.setParameter("size", Rules.BOARD_SIZE).getResultList();
    }

    // Postgres returns integer columns as Integer and sums as Long.
    private static int number(Object value) {
        return ((Number) value).intValue();
    }
}
