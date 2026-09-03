"use client";

import { useState } from "react";
import type { Device } from "@/lib/types";

type DeviceCardProps = {
  device: Device;
  busy: boolean;
  onQueueInternet: (device: Device) => Promise<void>;
  onSaveLimits: (device: Device, quotaGb: number | null, speedMbps: number | null) => Promise<void>;
  onSaveSchedule: (device: Device, from: string, until: string) => Promise<void>;
};

const bytesToText = (bytes: number) => {
  if (bytes >= 1024 ** 3) return `${(bytes / 1024 ** 3).toFixed(2)} GB`;
  if (bytes >= 1024 ** 2) return `${(bytes / 1024 ** 2).toFixed(1)} MB`;
  return `${(bytes / 1024).toFixed(1)} KB`;
};

export function DeviceCard({ device, busy, onQueueInternet, onSaveLimits, onSaveSchedule }: DeviceCardProps) {
  const [quota, setQuota] = useState(device.quota_bytes ? String(device.quota_bytes / 1024 ** 3) : "");
  const [speed, setSpeed] = useState(device.speed_limit_kbps ? String(device.speed_limit_kbps / 1000) : "");
  const [from, setFrom] = useState("23:00");
  const [until, setUntil] = useState("07:00");

  const name = device.custom_name || device.router_name || "جهاز بدون اسم";
  const quotaBytes = device.quota_bytes ?? 0;
  const percentage = quotaBytes > 0 ? Math.min(100, (device.used_bytes / quotaBytes) * 100) : 0;

  return (
    <article className="device-card">
      <div className="device-heading">
        <div className="device-icon" aria-hidden="true">{name.includes("iPhone") ? "◫" : "▰"}</div>
        <div>
          <h3>{name}</h3>
          <p>{device.current_ip || "لم يحصل على IP بعد"}</p>
        </div>
        <span className={`status-pill ${device.is_online ? "online" : "offline"}`}>
          {device.is_online ? "متصل" : "غير متصل"}
        </span>
      </div>

      <div className="usage-line">
        <strong>{bytesToText(device.used_bytes)}</strong>
        <span>{quotaBytes ? `من ${bytesToText(quotaBytes)}` : "بدون حد جيجات"}</span>
      </div>
      <div className="progress" aria-label={`استهلاك ${percentage.toFixed(0)}%`}>
        <span style={{ width: `${percentage}%` }} />
      </div>

      <button
        className={device.internet_enabled ? "danger-button" : "success-button"}
        disabled={busy}
        onClick={() => void onQueueInternet(device)}
      >
        {device.internet_enabled ? "جدولة فصل الإنترنت" : "جدولة تشغيل الإنترنت"}
      </button>

      <details className="control-details">
        <summary>الحدود والجدول</summary>
        <div className="control-grid">
          <label>
            حد الاستهلاك (GB)
            <input inputMode="decimal" min="0" step="0.25" value={quota} onChange={(event) => setQuota(event.target.value)} placeholder="بدون حد" type="number" />
          </label>
          <label>
            حد السرعة (Mbps)
            <input inputMode="decimal" min="0" step="0.5" value={speed} onChange={(event) => setSpeed(event.target.value)} placeholder="بدون حد" type="number" />
          </label>
        </div>
        <button
          className="secondary-button"
          disabled={busy}
          onClick={() => void onSaveLimits(device, quota ? Number(quota) : null, speed ? Number(speed) : null)}
        >حفظ الحدود</button>

        <div className="schedule-row">
          <label>فصل من <input type="time" value={from} onChange={(event) => setFrom(event.target.value)} /></label>
          <label>حتى <input type="time" value={until} onChange={(event) => setUntil(event.target.value)} /></label>
        </div>
        <button className="secondary-button" disabled={busy} onClick={() => void onSaveSchedule(device, from, until)}>
          حفظ جدول يومي
        </button>
      </details>
    </article>
  );
}

