-- Transaction-only fixtures: no test commands are visible to a phone.
begin;
do $$
declare
  test_home uuid;
  test_device uuid;
  owner_uuid uuid;
  state record;
  amount bigint := 3 * 1073741824::bigint;
  friday timestamptz := '2026-09-11 23:59:00 Africa/Cairo';
  saturday timestamptz := '2026-09-12 00:00:00 Africa/Cairo';
  command_count integer;
begin
  select owner_id into strict owner_uuid from public.homenet_homes order by created_at limit 1;
  insert into public.homenet_homes(owner_id, name, usage_started_at, timezone)
  values(owner_uuid, 'quota-test-' || gen_random_uuid(), '2026-09-01 00:00:00 Africa/Cairo', 'Africa/Cairo')
  returning id into test_home;
  insert into public.homenet_devices(home_id, router_name, is_approved, quota_bytes, quota_period)
  values(test_home, 'QUOTA-TEST', true, amount, 'daily') returning id into test_device;
  insert into public.homenet_device_addresses(home_id,device_id,mac)
  values(test_home,test_device,'02:00:00:00:00:FD');
  update public.homenet_homes set block_new_devices=true where id=test_home;

  insert into public.homenet_usage_daily(home_id,device_id,usage_date,delta_bytes,sample_count,first_captured_at,last_captured_at)
  values(test_home,test_device,'2026-09-11',amount-1,1,friday,friday);
  select * into state from public.homenet_policy_state(test_home,test_device,friday);
  if state.exhausted or not state.desired_enabled or state.used_bytes<>amount-1 then
    raise exception 'below-limit test failed';
  end if;
  update public.homenet_usage_daily set delta_bytes=amount where device_id=test_device;
  perform public.homenet_reconcile_policies(friday,test_home);
  if not exists(select 1 from public.homenet_commands where device_id=test_device and status='pending'
    and payload @> '{"enabled":false,"reason":"quota_exhausted"}'::jsonb) then
    raise exception 'exact-limit block test failed';
  end if;
  perform public.homenet_reconcile_policies(friday,test_home);
  select count(*) into command_count from public.homenet_commands where device_id=test_device and status='pending';
  if command_count<>1 then raise exception 'duplicate block command test failed'; end if;

  -- Model the existing phone's completion acknowledgement.
  update public.homenet_commands set status='succeeded',completed_at=friday where device_id=test_device;
  update public.homenet_devices set internet_enabled=false where id=test_device;
  perform public.homenet_reconcile_policies(saturday,test_home);
  select * into state from public.homenet_policy_state(test_home,test_device,saturday);
  if state.used_bytes<>0 or state.exhausted or not state.desired_enabled then
    raise exception 'daily midnight renewal test failed';
  end if;
  if not exists(select 1 from public.homenet_commands where device_id=test_device and status='pending'
    and payload @> '{"enabled":true}'::jsonb) then raise exception 'renewal command test failed'; end if;
  perform public.homenet_reconcile_policies(saturday,test_home);
  select count(*) into command_count from public.homenet_commands where device_id=test_device and status='pending';
  if command_count<>1 then raise exception 'duplicate renewal command test failed'; end if;

  insert into public.homenet_commands(home_id,device_id,action,payload)
  values(test_home,test_device,'set_internet','{"enabled":false}');
  select * into state from public.homenet_policy_state(test_home,test_device,saturday);
  if state.desired_enabled then raise exception 'manual block lost at renewal'; end if;
  if exists(select 1 from public.homenet_commands where device_id=test_device and status='pending'
    and payload @> '{"enabled":true}'::jsonb) then raise exception 'manual block did not cancel pending renewal'; end if;
  delete from public.homenet_commands where home_id=test_home;
  update public.homenet_devices set manual_internet_enabled=true where id=test_device;

  insert into public.homenet_schedules(home_id,device_id,name,block_from,block_until)
  values(test_home,test_device,'test night','23:00','08:00');
  select * into state from public.homenet_policy_state(test_home,test_device,saturday);
  if not state.schedule_blocked or state.desired_enabled then raise exception 'overnight schedule bypassed at renewal'; end if;
  perform public.homenet_reconcile_policies(saturday,test_home);
  if exists(select 1 from public.homenet_commands where device_id=test_device and status='pending'
    and payload @> '{"enabled":true}'::jsonb) then raise exception 'renewal opened a scheduled block'; end if;
  delete from public.homenet_schedules where home_id=test_home;
  update public.homenet_devices set is_approved=false where id=test_device;
  select * into state from public.homenet_policy_state(test_home,test_device,saturday);
  if state.desired_enabled then raise exception 'renewal bypassed guest approval'; end if;
  update public.homenet_devices set is_approved=true,quota_period='weekly' where id=test_device;

  select * into state from public.homenet_policy_state(test_home,test_device,friday);
  if not state.exhausted then raise exception 'weekly consumption test failed'; end if;
  select * into state from public.homenet_policy_state(test_home,test_device,saturday);
  if state.exhausted or state.used_bytes<>0 or not state.desired_enabled then raise exception 'Saturday renewal test failed'; end if;
  if public.homenet_quota_start('weekly','2026-09-06','2026-09-01')<>'2026-09-05'::date
    or public.homenet_quota_start('weekly','2026-09-11','2026-09-01')<>'2026-09-05'::date then
    raise exception 'weekly start boundary test failed';
  end if;
  update public.homenet_devices set quota_period='monthly' where id=test_device;
  select * into state from public.homenet_policy_state(test_home,test_device,'2026-10-01 00:00:00 Africa/Cairo');
  if state.used_bytes<>0 or state.exhausted then raise exception 'monthly renewal test failed'; end if;
  update public.homenet_devices set quota_period='one_time' where id=test_device;
  select * into state from public.homenet_policy_state(test_home,test_device,saturday);
  if not state.exhausted then raise exception 'one-time quota unexpectedly renewed'; end if;
  update public.homenet_devices set quota_bytes=null where id=test_device;
  select * into state from public.homenet_policy_state(test_home,test_device,friday);
  if state.exhausted or not state.desired_enabled then raise exception 'quota removal test failed'; end if;
  update public.homenet_devices set quota_bytes=0 where id=test_device;
  select * into state from public.homenet_policy_state(test_home,test_device,saturday);
  if not state.exhausted or state.desired_enabled then raise exception 'zero quota test failed'; end if;

  -- Existing queued block must be discarded if the phone reconnects after renewal.
  delete from public.homenet_commands where home_id=test_home;
  update public.homenet_devices set quota_bytes=amount,quota_period='daily',internet_enabled=true where id=test_device;
  perform public.homenet_reconcile_policies(friday,test_home);
  perform public.homenet_reconcile_policies(saturday,test_home);
  if exists(select 1 from public.homenet_commands where home_id=test_home and status='pending') then
    raise exception 'obsolete offline block was not cancelled';
  end if;
  if has_function_privilege('authenticated','public.homenet_reconcile_policies(timestamptz,uuid)','execute')
    or has_function_privilege('anon','public.homenet_policy_state(uuid,uuid,timestamptz)','execute') then
    raise exception 'function permissions test failed';
  end if;
  perform set_config('request.jwt.claim.sub',owner_uuid::text,true);
  execute 'set local role authenticated';
  select * into state from public.homenet_policy_state(test_home,test_device,friday);
  if state.used_bytes<>amount then raise exception 'owner RLS read failed'; end if;
  perform set_config('request.jwt.claim.sub',gen_random_uuid()::text,true);
  if exists(select 1 from public.homenet_policy_state(test_home,test_device,friday)) then
    raise exception 'cross-owner RLS isolation failed';
  end if;
  execute 'reset role';
end;
$$;
rollback;
