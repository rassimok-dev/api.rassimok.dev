package dev.rassimok.api.klad;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "player")
public class Player extends PanacheEntityBase {

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
}
