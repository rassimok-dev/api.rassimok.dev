-- One code per email: a new request replaces the row. Rows are deleted once
-- used or expired. Keyed by the SHA-256 of the address, so a code request
-- never stores the address itself; only an account keeps it, in player.
create table login_code (
    email_hash text primary key,
    code_hash  text not null,
    attempts   integer not null default 0,
    expires_at timestamptz not null,
    created_at timestamptz not null
);

-- One row per mail sent, for the limits. It keeps the SHA-256 of the
-- address, not the address, and rows older than a day are deleted, so the
-- limits themselves keep it under a hundred rows and it needs no index.
create table login_mail (
    id         bigint generated always as identity primary key,
    email_hash text not null,
    sent_at    timestamptz not null
);
