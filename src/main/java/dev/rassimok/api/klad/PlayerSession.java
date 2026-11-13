package dev.rassimok.api.klad;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "session")
public class PlayerSession extends PanacheEntityBase {

    @Id
    @Column(name = "token_hash")
    public String tokenHash;

    @Column(name = "player_id")
    public UUID playerId;

    @Column(name = "created_at")
    public Instant createdAt;

    @Column(name = "expires_at")
    public Instant expiresAt;
}
