create extension if not exists pg_cron with schema pg_catalog;

alter table public.homenet_schedules
  add column if not exists last_applied_blocked boolean,
  add column if not exists last_applied_at timestamptz;

create or replace function public.homenet_enqueue_due_schedules()
returns integer
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  item record;
  local_now timestamp;
  today smallint;
  previous_day smallint;
  active_now boolean;
  created_count integer := 0;
begin
  for item in
    select s.*, h.timezone
      from public.homenet_schedules s
      join public.homenet_homes h on h.id = s.home_id
     where s.enabled
  loop
    local_now := clock_timestamp() at time zone item.timezone;
    today := extract(dow from local_now)::smallint;
    previous_day := ((today + 6) % 7)::smallint;

    if item.block_from <= item.block_until then
      active_now := today = any(item.days_of_week)
        and local_now::time >= item.block_from
        and local_now::time < item.block_until;
    else
      active_now := (today = any(item.days_of_week) and local_now::time >= item.block_from)
        or (previous_day = any(item.days_of_week) and local_now::time < item.block_until);
    end if;

    if item.last_applied_blocked is distinct from active_now then
      insert into public.homenet_commands (home_id, device_id, action, payload)
      values (
        item.home_id,
        item.device_id,
        'set_internet',
        jsonb_build_object(
          'enabled', not active_now,
          'source', 'schedule',
          'schedule_id', item.id,
          'scheduled_blocked', active_now
        )
      );

      update public.homenet_schedules
         set last_applied_blocked = active_now,
             last_applied_at = clock_timestamp(),
             updated_at = clock_timestamp()
       where id = item.id;
      created_count := created_count + 1;
    end if;
  end loop;

  return created_count;
end;
$$;

revoke all on function public.homenet_enqueue_due_schedules() from public, anon, authenticated;

select cron.schedule(
  'homenet-schedule-dispatch',
  '* * * * *',
  'select public.homenet_enqueue_due_schedules()'
);
