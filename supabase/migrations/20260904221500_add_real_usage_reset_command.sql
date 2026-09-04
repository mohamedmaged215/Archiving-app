alter table public.homenet_commands
  drop constraint if exists homenet_commands_action_check;

alter table public.homenet_commands
  add constraint homenet_commands_action_check
  check (action in (
    'sync_now',
    'set_internet',
    'set_quota',
    'set_speed',
    'apply_schedule',
    'refresh_names',
    'reset_usage'
  ));
