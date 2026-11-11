-- Reserved structure only. No ownership mutation API until shared rules are approved.
-- A road_segment_id is NOT a road cell ID. No automatic cell->conquest conversion.
create table public.conquest_road_catalog (
  road_segment_id text primary key,
  dataset_version text not null,
  region_id text not null,
  path extensions.geometry not null
);
create table public.conquest_ownership (
  road_segment_id text primary key references public.conquest_road_catalog(road_segment_id),
  owner_user_id uuid references auth.users(id) on delete set null,
  revision bigint not null default 0,
  changed_at timestamptz not null default now()
);
alter table public.conquest_road_catalog enable row level security;
alter table public.conquest_ownership enable row level security;
revoke all on public.conquest_road_catalog, public.conquest_ownership from anon,authenticated;
-- Intentionally no client access, seed or claim function before the collaborator contract.
