-- Orbit Music Cloud Database Schema for Supabase (PostgreSQL)
-- Run this entire script in your Supabase Dashboard -> SQL Editor -> New query.
-- User identity uses Discord Account IDs (orbit_users.uid).

create extension if not exists pgcrypto;

-- 1. User Profiles (keyed by Discord Account ID)
create table if not exists public.orbit_users (
    uid text primary key,
    display_name text not null,
    handle text not null unique,
    discord_username text unique,
    discord_avatar_url text,
    card_shared boolean not null default false,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create index if not exists idx_orbit_users_handle on public.orbit_users (handle);
create index if not exists idx_orbit_users_discord_username on public.orbit_users (discord_username);

-- 3. Friendships & Requests
create table if not exists public.friendships (
    id text primary key,
    member_uids text[] not null,
    requester_uid text not null,
    recipient_uid text not null,
    requester_name text not null,
    requester_handle text not null,
    recipient_name text not null,
    recipient_handle text not null,
    status text not null default 'pending' check (status in ('pending', 'accepted')),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create index if not exists idx_friendships_member_uids on public.friendships using gin (member_uids);

-- 4. Shared Listening Cards
create table if not exists public.listening_cards (
    uid text primary key references public.orbit_users (uid) on delete cascade,
    owner_name text not null,
    handle text not null,
    discord_username text,
    persona text not null default 'The listener',
    since_label text,
    hours_label text not null default '0 min',
    plays integer not null default 0,
    artist_count integer not null default 0,
    best_streak integer not null default 0,
    top_song_title text,
    top_song_artist text,
    top_genre text,
    top_artists text[] not null default '{}',
    viewer_uids text[] not null default '{}',
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create index if not exists idx_listening_cards_viewer_uids on public.listening_cards using gin (viewer_uids);

-- 5. Collaborative Playlists
create table if not exists public.collab_playlists (
    id uuid primary key default gen_random_uuid(),
    title text not null,
    description text,
    owner_id text not null,
    owner_name text not null,
    invite_code text not null unique,
    member_uids text[] not null default '{}',
    member_names text[] not null default '{}',
    track_count integer not null default 0,
    track_doc_ids text[] not null default '{}',
    cover_url text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create index if not exists idx_collab_playlists_member_uids on public.collab_playlists using gin (member_uids);
create index if not exists idx_collab_playlists_invite_code on public.collab_playlists (invite_code);

-- 6. Collaborative Playlist Tracks
create table if not exists public.collab_tracks (
    id uuid primary key default gen_random_uuid(),
    playlist_id uuid not null references public.collab_playlists (id) on delete cascade,
    member_uids text[] not null default '{}',
    media_id text not null,
    title text not null,
    artist_line text not null,
    album_title text,
    artwork_url text,
    duration_ms bigint,
    added_by_uid text not null,
    added_by_name text not null,
    created_at timestamptz not null default now()
);

create index if not exists idx_collab_tracks_playlist_id on public.collab_tracks (playlist_id, created_at);

-- Enable open API access for the anon key (Discord ID acts as client identity)
alter table public.orbit_users disable row level security;
alter table public.friendships disable row level security;
alter table public.listening_cards disable row level security;
alter table public.collab_playlists disable row level security;
alter table public.collab_tracks disable row level security;

grant usage on schema public to anon, authenticated;
grant all on table public.orbit_users to anon, authenticated;
grant all on table public.friendships to anon, authenticated;
grant all on table public.listening_cards to anon, authenticated;
grant all on table public.collab_playlists to anon, authenticated;
grant all on table public.collab_tracks to anon, authenticated;
