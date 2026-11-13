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
@Table(name = "game_run")
public class GameRun extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "player_id")
    public UUID playerId;

    @Column(name = "levels_cleared")
    public int levelsCleared;

    public int score;

    @Column(name = "ticks_total")
    public int ticksTotal;

    @Column(name = "finished_at")
    public Instant finishedAt;
}
