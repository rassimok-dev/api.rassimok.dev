create table player (
    id           uuid primary key,
    nick         text not null,
    nick_key     text not null,
    kind         text not null check (kind in ('human', 'agent')),
    email        text unique,
    created_at   timestamptz not null,
    last_seen_at timestamptz not null
);

create table session (
    token_hash text primary key,
    player_id  uuid not null references player (id) on delete cascade,
    created_at timestamptz not null,
    expires_at timestamptz not null
);

create index session_player_id_idx on session (player_id);
