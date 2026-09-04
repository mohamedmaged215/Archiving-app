"use client";

import { useState } from "react";
import type { Device } from "@/lib/types";

type DeviceCardProps = {
  device: Device;
  busy: boolean;
  controlAvailable: boolean;
  onQueueInternet: (device: Device) => Promise<void>;
  onSaveLimits: (device: Device, quotaGb: number | null, speedMbps: number | null, period: Device["quota_period"]) => Promise<void>;
  onSaveSchedule: (device: Device, from: string, until: string) => Promise<void>;
};

const bytesToText = (bytes: number) => {
  if (bytes >= 1024 ** 3) return `${(bytes / 1024 ** 3).toFixed(2)} GB`;
  if (bytes >= 1024 ** 2) return `${(bytes / 1024 ** 2).toFixed(1)} MB`;
  return `${(bytes / 1024).toFixed(1)} KB`;
};

const periodLabels: Record<Device["quota_period"], string> = {
  daily: "اليوم",
  weekly: "آخر 7 أيام",
  monthly: "الشهر الحالي",
  one_time: "من بداية التسجيل",
};

export function DeviceCard({ device, busy, controlAvailable, onQueueInternet, onSaveLimits, onSaveSchedule }: DeviceCardProps) {
  const [quota, setQuota] = useState(device.quota_bytes ? String(device.quota_bytes / 1024 ** 3) : "");
  const [speed, setSpeed] = useState(device.speed_limit_kbps ? String(device.speed_limit_kbps / 1000) : "");
  const [period, setPeriod] = useState<Device["quota_period"]>(device.quota_period);
  const [from, setFrom] = useState("23:00");
  const [until, setUntil] = useState("07:00");
  const [settingsOpen, setSettingsOpen] = useState(false);

  const name = device.custom_name || device.router_name || "جهاز بدون اسم";
  const quotaBytes = device.quota_bytes ?? 0;
  const percentage = quotaBytes > 0 ? Math.min(100, (device.used_bytes / quotaBytes) * 100) : 0;

  return (
    <article className="device-card">
      <div className="device-heading">
        <div className="device-icon" aria-hidden="true">{name.toLowerCase().includes("iphone") ? "▯" : "▰"}</div>
        <div className="device-identity">
          <h3>{name}</h3>
          <p>{device.current_ip || "IP غير معروف"} · {device.mac_addresses[0] || "MAC غير معروف"}</p>
        </div>
        <span className={`status-pill ${device.is_online ? "online" : "offline"}`}>
          {device.is_online ? "نشط الآن" : "غير نشط"}
        </span>
      </div>

      <div className="usage-line">
        <div><span>استهلاك {periodLabels[device.quota_period]}</span><strong>{bytesToText(device.used_bytes)}</strong></div>
        <span>{quotaBytes ? `من أصل ${bytesToText(quotaBytes)}` : "لم تحدد باقة"}</span>
      </div>
      <div className="progress" aria-label={`استهلاك ${percentage.toFixed(0)}%`}>
        <span style={{ width: `${percentage}%` }} />
      </div>

      <div className="device-actions">
        <button
          className={device.internet_enabled ? "danger-button" : "success-button"}
          disabled={busy || !controlAvailable}
          title={controlAvailable ? undefined : "يلزم إنهاء ربط صفحة Access Control في الراوتر أولًا"}
          onClick={() => void onQueueInternet(device)}
        >
          {device.internet_enabled ? "فصل الإنترنت" : "تشغيل الإنترنت"}
        </button>
        <button className="secondary-button" type="button" onClick={() => setSettingsOpen((open) => !open)}>
          {settingsOpen ? "إخفاء الإعدادات" : "الباقة والجدول"}
        </button>
      </div>
      {!controlAvailable ? <p className="control-hint">الفصل الفعلي ينتظر ربط Access Control في نسخة الهاتف القادمة.</p> : null}

      {settingsOpen ? <section className="control-details" aria-label={`إعدادات ${name}`}>
        <h4>حد الاستخدام والسرعة</h4>
        <div className="control-grid">
          <label>
            حد الاستهلاك (GB)
            <input inputMode="decimal" min="0" step="0.25" value={quota} onChange={(event) => setQuota(event.target.value)} placeholder="بدون حد" type="number" />
          </label>
          <label>
            حد السرعة (Mbps)
            <input inputMode="decimal" min="0" step="0.5" value={speed} onChange={(event) => setSpeed(event.target.value)} placeholder="بدون حد" type="number" />
          </label>
          <label className="period-field">
            مدة حد الاستهلاك
            <select value={period} onChange={(event) => setPeriod(event.target.value as Device["quota_period"])}>
              <option value="daily">يوميًا</option>
              <option value="weekly">كل 7 أيام</option>
              <option value="monthly">كل شهر ميلادي</option>
              <option value="one_time">من بداية التسجيل</option>
            </select>
          </label>
        </div>
        <button
          className="secondary-button full-button"
          disabled={busy}
          onClick={() => void onSaveLimits(device, quota ? Number(quota) : null, speed ? Number(speed) : null, period)}
        >حفظ الحدود</button>

        <h4>جدول يومي للفصل</h4>
        <div className="schedule-row">
          <label>فصل من <input type="time" value={from} onChange={(event) => setFrom(event.target.value)} /></label>
          <label>حتى <input type="time" value={until} onChange={(event) => setUntil(event.target.value)} /></label>
        </div>
        <button className="secondary-button full-button" disabled={busy} onClick={() => void onSaveSchedule(device, from, until)}>
          حفظ جدول يومي
        </button>
      </section> : null}
    </article>
  );
}
