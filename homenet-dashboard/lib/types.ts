export type Home = {
  id: string;
  name: string;
  router_model: string;
  usage_started_at: string;
};

export type Agent = {
  id: string;
  app_version: string | null;
  last_seen_at: string | null;
};

export type Device = {
  id: string;
  router_name: string | null;
  custom_name: string | null;
  current_ip: string | null;
  is_online: boolean;
  internet_enabled: boolean;
  quota_bytes: number | null;
  quota_period: "daily" | "weekly" | "monthly" | "one_time";
  speed_limit_kbps: number | null;
  last_seen_at: string | null;
  used_bytes: number;
  quota_used_bytes: number;
  sample_count: number;
  mac_addresses: string[];
  schedule: DeviceSchedule | null;
};

export type DeviceSchedule = {
  id: string;
  block_from: string;
  block_until: string;
  days_of_week: number[];
  enabled: boolean;
};

export type UsagePreset = "today" | "week" | "month" | "custom";

export type UsageRange = {
  from: string;
  to: string;
  label: string;
};

export type Command = {
  id: string;
  action: string;
  status: "pending" | "processing" | "succeeded" | "failed" | "cancelled";
  created_at: string;
  error_message: string | null;
};
