create or replace function public.homenet_merge_named_device_identities()
returns integer
language plpgsql
security invoker
set search_path = public, pg_temp
as $$
declare
  identity_group record;
  duplicate_device record;
  merged_count integer := 0;
  canonical_internet_enabled boolean;
begin
  for identity_group in
    select
      d.home_id,
      lower(btrim(d.router_name)) as identity_name,
      (array_agg(d.id order by d.created_at)
        filter (where nullif(btrim(d.custom_name), '') is not null))[1] as canonical_id
    from public.homenet_devices d
    where nullif(btrim(d.router_name), '') is not null
      and lower(btrim(d.router_name)) not in (
        'iphone', 'android', 'unknown', 'device', 'phone', 'mobile', 'tablet'
      )
    group by d.home_id, lower(btrim(d.router_name))
    having count(*) > 1
       and count(*) filter (where nullif(btrim(d.custom_name), '') is not null) = 1
  loop
    for duplicate_device in
      select d.*
      from public.homenet_devices d
      where d.home_id = identity_group.home_id
        and lower(btrim(d.router_name)) = identity_group.identity_name
        and d.id <> identity_group.canonical_id
        and nullif(btrim(d.custom_name), '') is null
      order by d.created_at
    loop
      insert into public.homenet_usage_daily (
        home_id, device_id, usage_date, delta_bytes, sample_count,
        first_captured_at, last_captured_at, updated_at
      )
      select
        u.home_id, identity_group.canonical_id, u.usage_date, u.delta_bytes, u.sample_count,
        u.first_captured_at, u.last_captured_at, now()
      from public.homenet_usage_daily u
      where u.device_id = duplicate_device.id
      on conflict (device_id, usage_date) do update
      set delta_bytes = public.homenet_usage_daily.delta_bytes + excluded.delta_bytes,
          sample_count = public.homenet_usage_daily.sample_count + excluded.sample_count,
          first_captured_at = least(public.homenet_usage_daily.first_captured_at, excluded.first_captured_at),
          last_captured_at = greatest(public.homenet_usage_daily.last_captured_at, excluded.last_captured_at),
          updated_at = now();

      delete from public.homenet_usage_daily
      where device_id = duplicate_device.id;

      insert into public.homenet_usage_counters (
        home_id, device_id, router_bytes_total, captured_at, updated_at
      )
      select
        c.home_id, identity_group.canonical_id, c.router_bytes_total, c.captured_at, now()
      from public.homenet_usage_counters c
      where c.device_id = duplicate_device.id
      on conflict (device_id) do update
      set router_bytes_total = excluded.router_bytes_total,
          captured_at = excluded.captured_at,
          updated_at = now()
      where excluded.captured_at > public.homenet_usage_counters.captured_at;

      delete from public.homenet_usage_counters
      where device_id = duplicate_device.id;

      update public.homenet_device_addresses
         set device_id = identity_group.canonical_id
       where device_id = duplicate_device.id;

      update public.homenet_schedules
         set device_id = identity_group.canonical_id,
             updated_at = now()
       where device_id = duplicate_device.id;

      update public.homenet_commands
         set device_id = identity_group.canonical_id,
             updated_at = now()
       where device_id = duplicate_device.id;

      delete from public.homenet_usage_samples
      where device_id = duplicate_device.id;

      update public.homenet_devices canonical
         set current_ip = case
               when canonical.last_seen_at is null
                 or duplicate_device.last_seen_at >= canonical.last_seen_at
                 then duplicate_device.current_ip
               else canonical.current_ip
             end,
             last_seen_at = greatest(canonical.last_seen_at, duplicate_device.last_seen_at),
             is_online = canonical.is_online or duplicate_device.is_online,
             internet_enabled = canonical.internet_enabled and duplicate_device.internet_enabled,
             updated_at = now()
       where canonical.id = identity_group.canonical_id;

      delete from public.homenet_devices
      where id = duplicate_device.id;

      merged_count := merged_count + 1;
    end loop;

    select d.internet_enabled
      into canonical_internet_enabled
      from public.homenet_devices d
     where d.id = identity_group.canonical_id;

    if merged_count > 0 and canonical_internet_enabled = false and not exists (
      select 1
      from public.homenet_commands c
      where c.device_id = identity_group.canonical_id
        and c.action = 'set_internet'
        and c.status in ('pending', 'processing')
        and coalesce((c.payload ->> 'enabled')::boolean, true) = false
    ) then
      insert into public.homenet_commands (home_id, device_id, action, payload)
      values (
        identity_group.home_id,
        identity_group.canonical_id,
        'set_internet',
        jsonb_build_object('enabled', false, 'reason', 'identity_mac_changed')
      );
    end if;
  end loop;

  return merged_count;
end;
$$;

revoke all on function public.homenet_merge_named_device_identities()
  from public, anon, authenticated;

select cron.schedule(
  'homenet-merge-device-identities',
  '* * * * *',
  $cron$select public.homenet_merge_named_device_identities()$cron$
);

select public.homenet_merge_named_device_identities();
