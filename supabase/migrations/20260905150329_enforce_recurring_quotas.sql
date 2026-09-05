alter table public.homenet_devices
  add column manual_internet_enabled boolean not null default true,
  add column quota_exhausted boolean not null default false;

-- Preserve the current manual state; an existing scheduled block may expire.
update public.homenet_devices d
set manual_internet_enabled = d.internet_enabled or coalesce((
  select c.payload ->> 'source' = 'schedule'
  from public.homenet_commands c
  where c.device_id = d.id and c.action = 'set_internet' and c.status = 'succeeded'
  order by c.completed_at desc nulls last, c.created_at desc limit 1
), false);

create or replace function public.homenet_quota_start(p_period text, p_date date, p_epoch date)
returns date language sql immutable security invoker set search_path = public
as $$
  select greatest(p_epoch, case p_period
    when 'daily' then p_date
    when 'weekly' then p_date - ((extract(dow from p_date)::integer + 1) % 7)
    when 'monthly' then date_trunc('month', p_date)::date
    else p_epoch end);
$$;
revoke all on function public.homenet_quota_start(text, date, date) from public, anon;
grant execute on function public.homenet_quota_start(text, date, date) to authenticated;

create or replace function public.homenet_policy_state(p_home_id uuid, p_device_id uuid, p_at timestamptz)
returns table (used_bytes bigint, exhausted boolean, schedule_blocked boolean, desired_enabled boolean)
language sql stable security invoker set search_path = public
as $$
  with device as (
    select d.*, h.block_new_devices, h.usage_started_at, h.timezone,
           p_at at time zone h.timezone as local_now
    from public.homenet_devices d join public.homenet_homes h on h.id = d.home_id
    where d.home_id = p_home_id and d.id = p_device_id
  ), measured as (
    select d.*,
      coalesce((select sum(u.delta_bytes) from public.homenet_usage_daily u
        where u.home_id = d.home_id and u.device_id = d.id
          and u.usage_date >= public.homenet_quota_start(d.quota_period, d.local_now::date,
            (d.usage_started_at at time zone d.timezone)::date)
          and u.usage_date <= d.local_now::date), 0)::bigint as used,
      exists (select 1 from public.homenet_schedules s
        where s.home_id = d.home_id and s.device_id = d.id and s.enabled
        and case when s.block_from <= s.block_until then
          extract(dow from d.local_now)::smallint = any(s.days_of_week)
          and d.local_now::time >= s.block_from and d.local_now::time < s.block_until
        else
          (extract(dow from d.local_now)::smallint = any(s.days_of_week) and d.local_now::time >= s.block_from)
          or (((extract(dow from d.local_now)::integer + 6) % 7)::smallint = any(s.days_of_week)
            and d.local_now::time < s.block_until)
        end) as blocked_by_schedule
    from device d
  )
  select used, quota_bytes is not null and used >= quota_bytes, blocked_by_schedule,
    manual_internet_enabled and (not block_new_devices or is_approved)
      and not blocked_by_schedule and (quota_bytes is null or used < quota_bytes)
  from measured;
$$;
revoke all on function public.homenet_policy_state(uuid, uuid, timestamptz) from public, anon;
grant execute on function public.homenet_policy_state(uuid, uuid, timestamptz) to authenticated;

-- Manual intent must survive quota renewal and schedule changes.
create or replace function public.homenet_record_manual_internet()
returns trigger language plpgsql security invoker set search_path = public, pg_temp
as $$
declare requested boolean; policy record;
begin
  if new.action <> 'set_internet' or new.device_id is null
    or coalesce(new.payload ->> 'source', '') in ('policy', 'schedule', 'quota')
    or coalesce(new.payload ->> 'reason', '') in ('new_device_requires_approval', 'identity_mac_changed') then
    return new;
  end if;
  requested := coalesce((new.payload ->> 'enabled')::boolean, true);
  update public.homenet_devices set manual_internet_enabled = requested, updated_at = now()
  where id = new.device_id and home_id = new.home_id;
  if not found then raise exception 'الجهاز غير موجود في هذا المنزل'; end if;
  select * into policy from public.homenet_policy_state(new.home_id, new.device_id, now());
  if requested and not policy.desired_enabled then
    if policy.exhausted then raise exception 'الباقة انتهت. زوّد الحد أو ألغِه لتشغيل الإنترنت.'; end if;
    if policy.schedule_blocked then raise exception 'الجهاز داخل موعد الفصل. عدّل الجدول أولًا.'; end if;
    raise exception 'الجهاز يحتاج موافقتك أولًا.';
  end if;
  update public.homenet_commands set status = 'cancelled', updated_at = now()
  where device_id = new.device_id and home_id = new.home_id and action = 'set_internet'
    and status = 'pending';
  return new;
end;
$$;
revoke all on function public.homenet_record_manual_internet() from public, anon, authenticated;
create trigger homenet_record_manual_internet before insert on public.homenet_commands
for each row execute function public.homenet_record_manual_internet();

-- Reconcile all reasons together so renewal cannot override another block.
-- p_at and p_home_id allow isolated transaction tests without sending router commands.
create or replace function public.homenet_reconcile_policies(p_at timestamptz default now(), p_home_id uuid default null)
returns integer language plpgsql security invoker set search_path = public, pg_temp
as $$
declare d record; policy record; queued integer := 0; conflicting_inflight boolean;
begin
  for d in select * from public.homenet_devices
    where p_home_id is null or home_id = p_home_id order by id for update
  loop
    select * into policy from public.homenet_policy_state(d.home_id, d.id, p_at);
    if not found then continue; end if;
    if d.quota_exhausted is distinct from policy.exhausted then
      update public.homenet_devices set quota_exhausted = policy.exhausted, updated_at = now() where id = d.id;
    end if;

    -- Drop obsolete automatic commands accumulated while the phone was offline.
    update public.homenet_commands set status = 'cancelled', updated_at = now()
    where device_id = d.id and home_id = d.home_id and action = 'set_internet' and status = 'pending'
      and coalesce(payload ->> 'source', '') in ('policy', 'schedule', 'quota')
      and (payload ->> 'enabled')::boolean is distinct from policy.desired_enabled;

    select exists (select 1 from public.homenet_commands c where c.device_id = d.id
      and c.action = 'set_internet' and c.status = 'processing'
      and (c.payload ->> 'enabled')::boolean is distinct from policy.desired_enabled)
    into conflicting_inflight;

    if (d.internet_enabled is distinct from policy.desired_enabled or conflicting_inflight)
      and exists (select 1 from public.homenet_device_addresses a where a.device_id = d.id and a.home_id = d.home_id)
      and not exists (select 1 from public.homenet_commands c where c.device_id = d.id
        and c.action = 'set_internet' and c.status in ('pending', 'processing')
        and (c.payload ->> 'enabled')::boolean = policy.desired_enabled)
      and not exists (select 1 from public.homenet_commands c where c.device_id = d.id
        and c.action = 'set_internet' and c.status = 'failed' and c.payload ->> 'source' = 'policy'
        and (c.payload ->> 'enabled')::boolean = policy.desired_enabled
        and c.created_at > now() - interval '5 minutes') then
      insert into public.homenet_commands(home_id, device_id, action, payload)
      values (d.home_id, d.id, 'set_internet', jsonb_build_object(
        'enabled', policy.desired_enabled, 'source', 'policy',
        'reason', case when policy.exhausted then 'quota_exhausted'
          when policy.schedule_blocked then 'schedule' when not d.manual_internet_enabled then 'manual'
          when not d.is_approved then 'approval' else 'renewal_or_unblocked' end,
        'quota_used_bytes', policy.used_bytes));
      queued := queued + 1;
    end if;
  end loop;
  return queued;
end;
$$;
revoke all on function public.homenet_reconcile_policies(timestamptz, uuid) from public, anon, authenticated;

-- Keep the installed minute job, replacing competing schedule-only decisions.
create or replace function public.homenet_enqueue_due_schedules()
returns integer language sql security invoker set search_path = public
as $$ select public.homenet_reconcile_policies(); $$;
revoke all on function public.homenet_enqueue_due_schedules() from public, anon, authenticated;

create or replace function public.homenet_usage_totals(
  p_home_id uuid, p_from timestamptz, p_to timestamptz, p_device_id uuid default null
)
returns table (device_id uuid, filtered_bytes bigint, quota_period_bytes bigint, sample_count bigint)
language sql stable security invoker set search_path = public
as $$
  select d.id,
    coalesce(sum(u.delta_bytes) filter (
      where u.usage_date >= (greatest(p_from, h.usage_started_at) at time zone h.timezone)::date
        and u.usage_date <= (p_to at time zone h.timezone)::date), 0)::bigint,
    coalesce(sum(u.delta_bytes) filter (
      where u.usage_date >= public.homenet_quota_start(d.quota_period, (now() at time zone h.timezone)::date,
        (h.usage_started_at at time zone h.timezone)::date)
        and u.usage_date <= (now() at time zone h.timezone)::date), 0)::bigint,
    coalesce(sum(u.sample_count) filter (
      where u.usage_date >= (greatest(p_from, h.usage_started_at) at time zone h.timezone)::date
        and u.usage_date <= (p_to at time zone h.timezone)::date), 0)::bigint
  from public.homenet_devices d join public.homenet_homes h on h.id = d.home_id
  left join public.homenet_usage_daily u on u.device_id = d.id and u.home_id = d.home_id
  where d.home_id = p_home_id and (p_device_id is null or d.id = p_device_id)
  group by d.id, d.quota_period, h.usage_started_at, h.timezone;
$$;
revoke all on function public.homenet_usage_totals(uuid,timestamptz,timestamptz,uuid) from public, anon;
grant execute on function public.homenet_usage_totals(uuid,timestamptz,timestamptz,uuid) to authenticated;
