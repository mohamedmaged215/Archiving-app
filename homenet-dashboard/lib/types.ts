export type Home = {
  id: string;
  name: string;
  router_model: string;
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
  used_month_bytes: number;
  mac_addresses: string[];
};

export type Command = {
  id: string;
  action: string;
  status: "pending" | "processing" | "succeeded" | "failed" | "cancelled";
  created_at: string;
  error_message: string | null;
};
