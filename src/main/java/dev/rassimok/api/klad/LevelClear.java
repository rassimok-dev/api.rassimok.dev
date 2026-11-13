package dev.rassimok.api.klad;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "level_clear")
public class LevelClear extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "player_id")
    public UUID playerId;

    public int level;

    public String shape;

    public int revision;

    public int ticks;

    public int chests;

    @Column(name = "chests_total")
    public int chestsTotal;

    @Column(name = "created_at")
    public Instant createdAt;
}
