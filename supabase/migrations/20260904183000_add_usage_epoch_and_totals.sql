alter table public.homenet_homes
  add column if not exists usage_started_at timestamptz not null default now();

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
    coalesce(sum(s.delta_bytes) filter (
      where s.captured_at >= greatest(p_from, h.usage_started_at)
        and s.captured_at < p_to
    ), 0)::bigint as filtered_bytes,
    coalesce(sum(s.delta_bytes) filter (
      where s.captured_at >= greatest(
        h.usage_started_at,
        case d.quota_period
          when 'daily' then date_trunc('day', now() at time zone h.timezone) at time zone h.timezone
          when 'weekly' then now() - interval '7 days'
          when 'monthly' then date_trunc('month', now() at time zone h.timezone) at time zone h.timezone
          else h.usage_started_at
        end
      )
    ), 0)::bigint as quota_period_bytes,
    count(s.id) filter (
      where s.captured_at >= greatest(p_from, h.usage_started_at)
        and s.captured_at < p_to
    )::bigint as sample_count
  from public.homenet_devices d
  join public.homenet_homes h on h.id = d.home_id
  left join public.homenet_usage_samples s
    on s.device_id = d.id
   and s.home_id = d.home_id
   and s.captured_at >= h.usage_started_at
  where d.home_id = p_home_id
    and (p_device_id is null or d.id = p_device_id)
  group by d.id, d.quota_period, h.usage_started_at, h.timezone;
$$;

revoke all on function public.homenet_usage_totals(uuid, timestamptz, timestamptz, uuid) from public, anon;
grant execute on function public.homenet_usage_totals(uuid, timestamptz, timestamptz, uuid) to authenticated;
