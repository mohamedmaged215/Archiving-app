alter table public.homenet_homes
  add column if not exists block_new_devices boolean not null default false;

alter table public.homenet_devices
  add column if not exists is_approved boolean not null default false;

-- Preserve every device that was already known before this feature was enabled.
update public.homenet_devices
set is_approved = true
where is_approved = false;

create or replace function public.homenet_require_approval_for_new_address()
returns trigger
language plpgsql
security invoker
set search_path = public, pg_temp
as $$
declare
  should_block boolean := false;
begin
  if tg_op = 'UPDATE' and old.device_id is not distinct from new.device_id then
    return new;
  end if;

  select coalesce(h.block_new_devices, false)
    into should_block
    from public.homenet_homes h
    join public.homenet_devices d
      on d.home_id = h.id
   where h.id = new.home_id
     and d.id = new.device_id;

  if not coalesce(should_block, false) then
    return new;
  end if;

  update public.homenet_devices
     set is_approved = false,
         internet_enabled = false,
         updated_at = now()
   where id = new.device_id
     and home_id = new.home_id;

  if not exists (
    select 1
      from public.homenet_commands c
     where c.home_id = new.home_id
       and c.action = 'set_internet'
       and c.status in ('pending', 'processing')
       and coalesce((c.payload ->> 'enabled')::boolean, true) = false
       and c.payload ->> 'mac' = new.mac
  ) then
    insert into public.homenet_commands (home_id, device_id, action, payload)
    values (
      new.home_id,
      new.device_id,
      'set_internet',
      jsonb_build_object(
        'enabled', false,
        'reason', 'new_device_requires_approval',
        'mac', new.mac
      )
    );
  end if;

  return new;
end;
$$;

revoke all on function public.homenet_require_approval_for_new_address()
  from public, anon, authenticated;

drop trigger if exists homenet_require_approval_for_new_address
  on public.homenet_device_addresses;

create trigger homenet_require_approval_for_new_address
after insert or update of device_id on public.homenet_device_addresses
for each row
execute function public.homenet_require_approval_for_new_address();
