-- Nick keys are unique among accounts, in one pool for both kinds. Guests may
-- share a nick with each other, so only rows with an email are indexed; the
-- API also keeps guests off a key an account holds.
create unique index player_nick_key_account_idx on player (nick_key) where email is not null;
