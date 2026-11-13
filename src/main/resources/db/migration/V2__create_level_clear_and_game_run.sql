create table level_clear (
    id           bigint generated always as identity primary key,
    player_id    uuid not null references player (id) on delete cascade,
    level        integer not null,
    shape        text not null check (shape in ('landscape', 'portrait')),
    revision     integer not null,
    ticks        integer not null,
    chests       integer not null,
    chests_total integer not null,
    created_at   timestamptz not null
);

-- The rate limit counts a player's recent clears. The level boards and the
-- newest revision per level and shape look up by level, shape and revision.
create index level_clear_player_id_created_at_idx on level_clear (player_id, created_at);
create index level_clear_level_shape_revision_idx on level_clear (level, shape, revision);

create table game_run (
    id             bigint generated always as identity primary key,
    player_id      uuid not null references player (id) on delete cascade,
    levels_cleared integer not null,
    score          integer not null,
    ticks_total    integer not null,
    finished_at    timestamptz not null
);

-- The rate limit counts a player's recent runs. The records board sorts all
-- runs of one kind, few enough to need no index of their own.
create index game_run_player_id_finished_at_idx on game_run (player_id, finished_at);
