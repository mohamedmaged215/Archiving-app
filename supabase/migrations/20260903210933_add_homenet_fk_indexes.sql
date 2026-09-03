create index homenet_commands_device_id_idx
  on public.homenet_commands (device_id);

create index homenet_schedules_device_id_idx
  on public.homenet_schedules (device_id);
