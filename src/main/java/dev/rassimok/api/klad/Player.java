package dev.rassimok.api.klad;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.ws.rs.core.Response;
import org.hibernate.exception.ConstraintViolationException;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "player")
public class Player extends PanacheEntityBase {

    static final String ACCOUNT_NICK_KEY_INDEX = "player_nick_key_account_idx";

    @Id
    public UUID id;

    public String nick;

    @Column(name = "nick_key")
    public String nickKey;

    public String kind;

    public String email;

    @Column(name = "created_at")
    public Instant createdAt;

    @Column(name = "last_seen_at")
    public Instant lastSeenAt;

    static boolean isNickKeyTaken(String nickKey, UUID playerId) {
        return 0 < count("nickKey = ?1 and email is not null and id <> ?2", nickKey, playerId);
    }

    // Another instance can give an account the same nick key between the
    // check and this write; the unique index then refuses the write.
    static void flushNickKey() {
        try {
            flush();
        } catch (ConstraintViolationException e) {
            if (ACCOUNT_NICK_KEY_INDEX.equals(e.getConstraintName())) {
                // The failed write has aborted the database transaction, so it
                // must roll back even where an ApiException would keep the writes.
                QuarkusTransaction.setRollbackOnly();
                throw new ApiException(Response.Status.CONFLICT, ApiException.NICK_TAKEN);
            }
            throw e;
        }
    }
}
