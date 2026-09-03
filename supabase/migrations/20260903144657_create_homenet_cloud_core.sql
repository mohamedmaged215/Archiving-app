create table public.homenet_homes (
  id uuid primary key default gen_random_uuid(),
  owner_id uuid not null references auth.users(id) on delete cascade,
  name text not null default 'شبكة المنزل',
  router_model text not null default 'TP-Link TL-WR840N',
  timezone text not null default 'Africa/Cairo',
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create unique index homenet_homes_owner_name_key
  on public.homenet_homes (owner_id, name);

create table public.homenet_agents (
  id uuid primary key default gen_random_uuid(),
  home_id uuid not null references public.homenet_homes(id) on delete cascade,
  name text not null default 'هاتف المراقبة',
  token_hash text unique,
  app_version text,
  last_seen_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create index homenet_agents_home_id_idx on public.homenet_agents (home_id);

create table public.homenet_devices (
  id uuid primary key default gen_random_uuid(),
  home_id uuid not null references public.homenet_homes(id) on delete cascade,
  router_name text,
  custom_name text,
  current_ip inet,
  is_online boolean not null default false,
  internet_enabled boolean not null default true,
  quota_bytes bigint check (quota_bytes is null or quota_bytes >= 0),
  quota_period text not null default 'monthly'
    check (quota_period in ('daily', 'weekly', 'monthly', 'one_time')),
  speed_limit_kbps integer check (speed_limit_kbps is null or speed_limit_kbps > 0),
  first_seen_at timestamptz not null default now(),
  last_seen_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create index homenet_devices_home_id_idx on public.homenet_devices (home_id);
create index homenet_devices_home_online_idx on public.homenet_devices (home_id, is_online);

create table public.homenet_device_addresses (
  home_id uuid not null references public.homenet_homes(id) on delete cascade,
  device_id uuid not null references public.homenet_devices(id) on delete cascade,
  mac text not null check (mac ~ '^[0-9A-F]{2}(:[0-9A-F]{2}){5}$'),
  first_seen_at timestamptz not null default now(),
  last_seen_at timestamptz not null default now(),
  primary key (home_id, mac)
);

create index homenet_device_addresses_device_id_idx
  on public.homenet_device_addresses (device_id);

create table public.homenet_usage_samples (
  id bigint generated always as identity primary key,
  home_id uuid not null references public.homenet_homes(id) on delete cascade,
  device_id uuid not null references public.homenet_devices(id) on delete cascade,
  captured_at timestamptz not null,
  router_bytes_total bigint not null check (router_bytes_total >= 0),
  delta_bytes bigint not null check (delta_bytes >= 0),
  current_bytes_per_second bigint not null default 0 check (current_bytes_per_second >= 0),
  counter_reset boolean not null default false,
  created_at timestamptz not null default now(),
  unique (device_id, captured_at)
);

create index homenet_usage_samples_home_time_idx
  on public.homenet_usage_samples (home_id, captured_at desc);
create index homenet_usage_samples_device_time_idx
  on public.homenet_usage_samples (device_id, captured_at desc);

create table public.homenet_schedules (
  id uuid primary key default gen_random_uuid(),
  home_id uuid not null references public.homenet_homes(id) on delete cascade,
  device_id uuid not null references public.homenet_devices(id) on delete cascade,
  name text not null,
  days_of_week smallint[] not null default array[0,1,2,3,4,5,6]::smallint[],
  block_from time not null,
  block_until time not null,
  enabled boolean not null default true,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  check (days_of_week <@ array[0,1,2,3,4,5,6]::smallint[])
);

create index homenet_schedules_home_device_idx
  on public.homenet_schedules (home_id, device_id);

create table public.homenet_commands (
  id uuid primary key default gen_random_uuid(),
  home_id uuid not null references public.homenet_homes(id) on delete cascade,
  device_id uuid references public.homenet_devices(id) on delete cascade,
  action text not null check (action in (
    'sync_now', 'set_internet', 'set_quota', 'set_speed', 'apply_schedule', 'refresh_names'
  )),
  payload jsonb not null default '{}'::jsonb,
  status text not null default 'pending'
    check (status in ('pending', 'processing', 'succeeded', 'failed', 'cancelled')),
  scheduled_for timestamptz not null default now(),
  attempts integer not null default 0 check (attempts >= 0),
  error_message text,
  claimed_at timestamptz,
  completed_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create index homenet_commands_agent_queue_idx
  on public.homenet_commands (home_id, status, scheduled_for, created_at);

alter table public.homenet_homes enable row level security;
alter table public.homenet_agents enable row level security;
alter table public.homenet_devices enable row level security;
alter table public.homenet_device_addresses enable row level security;
alter table public.homenet_usage_samples enable row level security;
alter table public.homenet_schedules enable row level security;
alter table public.homenet_commands enable row level security;

revoke all on table public.homenet_homes from anon, authenticated;
revoke all on table public.homenet_agents from anon, authenticated;
revoke all on table public.homenet_devices from anon, authenticated;
revoke all on table public.homenet_device_addresses from anon, authenticated;
revoke all on table public.homenet_usage_samples from anon, authenticated;
revoke all on table public.homenet_schedules from anon, authenticated;
revoke all on table public.homenet_commands from anon, authenticated;

grant select, insert, update, delete on table public.homenet_homes to authenticated;
grant select, insert, update, delete on table public.homenet_agents to authenticated;
grant select, insert, update, delete on table public.homenet_devices to authenticated;
grant select, insert, update, delete on table public.homenet_device_addresses to authenticated;
grant select, insert, update, delete on table public.homenet_usage_samples to authenticated;
grant select, insert, update, delete on table public.homenet_schedules to authenticated;
grant select, insert, update, delete on table public.homenet_commands to authenticated;
grant usage, select on all sequences in schema public to authenticated;

create policy "homenet owners select homes"
  on public.homenet_homes for select to authenticated
  using ((select auth.uid()) = owner_id);
create policy "homenet owners insert homes"
  on public.homenet_homes for insert to authenticated
  with check ((select auth.uid()) = owner_id);
create policy "homenet owners update homes"
  on public.homenet_homes for update to authenticated
  using ((select auth.uid()) = owner_id)
  with check ((select auth.uid()) = owner_id);
create policy "homenet owners delete homes"
  on public.homenet_homes for delete to authenticated
  using ((select auth.uid()) = owner_id);

create policy "homenet owners manage agents"
  on public.homenet_agents for all to authenticated
  using (exists (
    select 1 from public.homenet_homes h
    where h.id = homenet_agents.home_id and h.owner_id = (select auth.uid())
  ))
  with check (exists (
    select 1 from public.homenet_homes h
    where h.id = homenet_agents.home_id and h.owner_id = (select auth.uid())
  ));

create policy "homenet owners manage devices"
  on public.homenet_devices for all to authenticated
  using (exists (
    select 1 from public.homenet_homes h
    where h.id = homenet_devices.home_id and h.owner_id = (select auth.uid())
  ))
  with check (exists (
    select 1 from public.homenet_homes h
    where h.id = homenet_devices.home_id and h.owner_id = (select auth.uid())
  ));

create policy "homenet owners manage device addresses"
  on public.homenet_device_addresses for all to authenticated
  using (exists (
    select 1 from public.homenet_homes h
    where h.id = homenet_device_addresses.home_id and h.owner_id = (select auth.uid())
  ))
  with check (exists (
    select 1 from public.homenet_homes h
    where h.id = homenet_device_addresses.home_id and h.owner_id = (select auth.uid())
  ));

create policy "homenet owners manage usage samples"
  on public.homenet_usage_samples for all to authenticated
  using (exists (
    select 1 from public.homenet_homes h
    where h.id = homenet_usage_samples.home_id and h.owner_id = (select auth.uid())
  ))
  with check (exists (
    select 1 from public.homenet_homes h
    where h.id = homenet_usage_samples.home_id and h.owner_id = (select auth.uid())
  ));

create policy "homenet owners manage schedules"
  on public.homenet_schedules for all to authenticated
  using (exists (
    select 1 from public.homenet_homes h
    where h.id = homenet_schedules.home_id and h.owner_id = (select auth.uid())
  ))
  with check (exists (
    select 1 from public.homenet_homes h
    where h.id = homenet_schedules.home_id and h.owner_id = (select auth.uid())
  ));

create policy "homenet owners manage commands"
  on public.homenet_commands for all to authenticated
  using (exists (
    select 1 from public.homenet_homes h
    where h.id = homenet_commands.home_id and h.owner_id = (select auth.uid())
  ))
  with check (exists (
    select 1 from public.homenet_homes h
    where h.id = homenet_commands.home_id and h.owner_id = (select auth.uid())
  ));
