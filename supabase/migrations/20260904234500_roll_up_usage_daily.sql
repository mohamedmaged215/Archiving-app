create table public.homenet_usage_counters (
  home_id uuid not null references public.homenet_homes(id) on delete cascade,
  device_id uuid primary key references public.homenet_devices(id) on delete cascade,
  router_bytes_total bigint not null check (router_bytes_total >= 0),
  captured_at timestamptz not null,
  updated_at timestamptz not null default now()
);

create index homenet_usage_counters_home_id_idx
  on public.homenet_usage_counters (home_id);

create table public.homenet_usage_daily (
  home_id uuid not null references public.homenet_homes(id) on delete cascade,
  device_id uuid not null references public.homenet_devices(id) on delete cascade,
  usage_date date not null,
  delta_bytes bigint not null default 0 check (delta_bytes >= 0),
  sample_count bigint not null default 0 check (sample_count >= 0),
  first_captured_at timestamptz not null,
  last_captured_at timestamptz not null,
  updated_at timestamptz not null default now(),
  primary key (device_id, usage_date)
);

create index homenet_usage_daily_home_date_idx
  on public.homenet_usage_daily (home_id, usage_date desc);

alter table public.homenet_usage_counters enable row level security;
alter table public.homenet_usage_daily enable row level security;

revoke all on table public.homenet_usage_counters from anon, authenticated;
revoke all on table public.homenet_usage_daily from anon, authenticated;
grant select, insert, update, delete on table public.homenet_usage_counters to authenticated;
grant select, insert, update, delete on table public.homenet_usage_daily to authenticated;

create policy "homenet owners manage usage counters"
  on public.homenet_usage_counters for all to authenticated
  using (exists (
    select 1 from public.homenet_homes h
    where h.id = homenet_usage_counters.home_id
      and h.owner_id = (select auth.uid())
  ))
  with check (exists (
    select 1 from public.homenet_homes h
    where h.id = homenet_usage_counters.home_id
      and h.owner_id = (select auth.uid())
  ));

create policy "homenet owners manage daily usage"
  on public.homenet_usage_daily for all to authenticated
  using (exists (
    select 1 from public.homenet_homes h
    where h.id = homenet_usage_daily.home_id
      and h.owner_id = (select auth.uid())
  ))
  with check (exists (
    select 1 from public.homenet_homes h
    where h.id = homenet_usage_daily.home_id
      and h.owner_id = (select auth.uid())
  ));

insert into public.homenet_usage_daily (
  home_id,
  device_id,
  usage_date,
  delta_bytes,
  sample_count,
  first_captured_at,
  last_captured_at,
  updated_at
)
select
  s.home_id,
  s.device_id,
  (s.captured_at at time zone h.timezone)::date,
  sum(s.delta_bytes),
  count(*),
  min(s.captured_at),
  max(s.captured_at),
  now()
from public.homenet_usage_samples s
join public.homenet_homes h on h.id = s.home_id
where s.captured_at >= h.usage_started_at
group by s.home_id, s.device_id, (s.captured_at at time zone h.timezone)::date
on conflict (device_id, usage_date) do update
set delta_bytes = excluded.delta_bytes,
    sample_count = excluded.sample_count,
    first_captured_at = excluded.first_captured_at,
    last_captured_at = excluded.last_captured_at,
    updated_at = now();

insert into public.homenet_usage_counters (
  home_id,
  device_id,
  router_bytes_total,
  captured_at,
  updated_at
)
select distinct on (s.device_id)
  s.home_id,
  s.device_id,
  s.router_bytes_total,
  s.captured_at,
  now()
from public.homenet_usage_samples s
order by s.device_id, s.captured_at desc
on conflict (device_id) do update
set home_id = excluded.home_id,
    router_bytes_total = excluded.router_bytes_total,
    captured_at = excluded.captured_at,
    updated_at = now();

create or replace function public.homenet_sanitize_usage_delta()
returns trigger
language plpgsql
security invoker
set search_path = public, pg_temp
as $$
declare
  home_timezone text;
  started_at timestamptz;
  previous_total bigint;
  previous_captured_at timestamptz;
  elapsed_seconds numeric;
  effective_delta bigint := 0;
  maximum_plausible_delta numeric;
  counter_created boolean;
  local_usage_date date;
begin
  select h.timezone, h.usage_started_at
    into home_timezone, started_at
    from public.homenet_homes h
   where h.id = new.home_id;

  insert into public.homenet_usage_counters (
    home_id, device_id, router_bytes_total, captured_at, updated_at
  ) values (
    new.home_id, new.device_id, new.router_bytes_total, new.captured_at, now()
  )
  on conflict (device_id) do nothing
  returning true into counter_created;

  if coalesce(counter_created, false) then
    return null;
  end if;

  select c.router_bytes_total, c.captured_at
    into previous_total, previous_captured_at
    from public.homenet_usage_counters c
   where c.device_id = new.device_id
     and c.home_id = new.home_id
   for update;

  if previous_captured_at is null or new.captured_at <= previous_captured_at then
    return null;
  end if;

  if new.router_bytes_total >= previous_total then
    effective_delta := new.router_bytes_total - previous_total;
    elapsed_seconds := greatest(1, extract(epoch from (new.captured_at - previous_captured_at)));
    maximum_plausible_delta := elapsed_seconds * 25000000 + 5000000;
    if effective_delta > maximum_plausible_delta then
      effective_delta := 0;
    end if;
  end if;

  update public.homenet_usage_counters
     set router_bytes_total = new.router_bytes_total,
         captured_at = new.captured_at,
         updated_at = now()
   where device_id = new.device_id
     and home_id = new.home_id;

  if new.captured_at >= started_at then
    local_usage_date := (new.captured_at at time zone home_timezone)::date;
    insert into public.homenet_usage_daily (
      home_id,
      device_id,
      usage_date,
      delta_bytes,
      sample_count,
      first_captured_at,
      last_captured_at,
      updated_at
    ) values (
      new.home_id,
      new.device_id,
      local_usage_date,
      effective_delta,
      1,
      new.captured_at,
      new.captured_at,
      now()
    )
    on conflict (device_id, usage_date) do update
    set delta_bytes = public.homenet_usage_daily.delta_bytes + excluded.delta_bytes,
        sample_count = public.homenet_usage_daily.sample_count + 1,
        first_captured_at = least(public.homenet_usage_daily.first_captured_at, excluded.first_captured_at),
        last_captured_at = greatest(public.homenet_usage_daily.last_captured_at, excluded.last_captured_at),
        updated_at = now();
  end if;

  -- The minute-level row is intentionally discarded after updating the compact rollup.
  return null;
end;
$$;

revoke all on function public.homenet_sanitize_usage_delta() from public, anon, authenticated;

create or replace function public.homenet_usage_totals(
  p_home_id uuid,
  p_from timestamptz,
  p_to timestamptz,
  p_device_id uuid default null
)
returns table (
  device_id uuid,
  filtered_bytes bigint,
  quota_period_bytes bigint,
  sample_count bigint
)
language sql
stable
security invoker
set search_path = public
as $$
  select
    d.id as device_id,
    coalesce(sum(u.delta_bytes) filter (
      where u.usage_date >= (greatest(p_from, h.usage_started_at) at time zone h.timezone)::date
        and u.usage_date <= (p_to at time zone h.timezone)::date
    ), 0)::bigint as filtered_bytes,
    coalesce(sum(u.delta_bytes) filter (
      where u.usage_date >= case d.quota_period
        when 'daily' then (now() at time zone h.timezone)::date
        when 'weekly' then (now() at time zone h.timezone)::date - 6
        when 'monthly' then date_trunc('month', now() at time zone h.timezone)::date
        else (h.usage_started_at at time zone h.timezone)::date
      end
    ), 0)::bigint as quota_period_bytes,
    coalesce(sum(u.sample_count) filter (
      where u.usage_date >= (greatest(p_from, h.usage_started_at) at time zone h.timezone)::date
        and u.usage_date <= (p_to at time zone h.timezone)::date
    ), 0)::bigint as sample_count
  from public.homenet_devices d
  join public.homenet_homes h on h.id = d.home_id
  left join public.homenet_usage_daily u
    on u.device_id = d.id
   and u.home_id = d.home_id
  where d.home_id = p_home_id
    and (p_device_id is null or d.id = p_device_id)
  group by d.id, d.quota_period, h.usage_started_at, h.timezone;
$$;

revoke all on function public.homenet_usage_totals(uuid, timestamptz, timestamptz, uuid)
  from public, anon;
grant execute on function public.homenet_usage_totals(uuid, timestamptz, timestamptz, uuid)
  to authenticated;

delete from public.homenet_usage_samples;

select cron.schedule(
  'homenet-usage-retention',
  '15 3 * * *',
  $cron$
    delete from public.homenet_usage_daily
    where usage_date < (current_date - interval '13 months')::date
  $cron$
);
