create or replace function public.homenet_sanitize_usage_delta()
returns trigger
language plpgsql
security invoker
set search_path = public, pg_temp
as $$
declare
  previous_total bigint;
  previous_captured_at timestamptz;
  started_at timestamptz;
  elapsed_seconds numeric;
  expected_delta bigint;
  maximum_plausible_delta numeric;
begin
  select h.usage_started_at
    into started_at
    from public.homenet_homes h
   where h.id = new.home_id;

  select s.router_bytes_total, s.captured_at
    into previous_total, previous_captured_at
    from public.homenet_usage_samples s
   where s.device_id = new.device_id
     and s.home_id = new.home_id
     and s.captured_at >= started_at
     and s.captured_at < new.captured_at
   order by s.captured_at desc
   limit 1;

  if previous_captured_at is null then
    new.delta_bytes := 0;
    new.counter_reset := true;
    return new;
  end if;

  if new.router_bytes_total < previous_total then
    new.delta_bytes := 0;
    new.counter_reset := true;
    return new;
  end if;

  expected_delta := new.router_bytes_total - previous_total;
  elapsed_seconds := greatest(1, extract(epoch from (new.captured_at - previous_captured_at)));
  -- TL-WR840N ports are 100 Mbps. 200 Mbps plus a 5 MB cushion is deliberately generous.
  maximum_plausible_delta := elapsed_seconds * 25000000 + 5000000;

  if expected_delta > maximum_plausible_delta then
    new.delta_bytes := 0;
    new.counter_reset := true;
  else
    new.delta_bytes := expected_delta;
  end if;

  return new;
end;
$$;

revoke all on function public.homenet_sanitize_usage_delta() from public, anon, authenticated;

drop trigger if exists homenet_sanitize_usage_delta_before_insert
  on public.homenet_usage_samples;

create trigger homenet_sanitize_usage_delta_before_insert
before insert on public.homenet_usage_samples
for each row execute function public.homenet_sanitize_usage_delta();
