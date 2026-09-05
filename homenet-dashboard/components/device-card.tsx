"use client";

import { useState } from "react";
import type { Device, UsagePreset, UsageRange } from "@/lib/types";

type DeviceCardProps = {
  device: Device;
  busy: boolean;
  controlAvailable: boolean;
  globalRange: UsageRange;
  onQueueInternet: (device: Device) => Promise<void>;
  onRename: (device: Device, name: string) => Promise<void>;
  onSaveLimits: (device: Device, quotaGb: number | null, period: Device["quota_period"]) => Promise<void>;
  onSaveSchedule: (device: Device, from: string, until: string) => Promise<void>;
  onDeleteSchedule: (device: Device) => Promise<void>;
  onLoadUsage: (deviceId: string, range: UsageRange) => Promise<number>;
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
  one_time: "من بداية العداد",
};

function dateInputValue(date: Date) {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
}

function makeRange(preset: UsagePreset, fromDate: string, toDate: string): UsageRange {
  const now = new Date();
  let start = new Date(now);
  let end = new Date(now);
  end.setHours(23, 59, 59, 999);
  if (preset === "today") start.setHours(0, 0, 0, 0);
  if (preset === "week") { start.setDate(start.getDate() - 6); start.setHours(0, 0, 0, 0); }
  if (preset === "month") { start.setDate(1); start.setHours(0, 0, 0, 0); }
  if (preset === "custom") { start = new Date(`${fromDate}T00:00:00`); end = new Date(`${toDate}T23:59:59.999`); }
  const label = preset === "today" ? "اليوم" : preset === "week" ? "آخر 7 أيام" : preset === "month" ? "هذا الشهر" : `${fromDate} إلى ${toDate}`;
  return { from: start.toISOString(), to: end.toISOString(), label };
}

export function DeviceCard({ device, busy, controlAvailable, globalRange, onQueueInternet, onRename, onSaveLimits, onSaveSchedule, onDeleteSchedule, onLoadUsage }: DeviceCardProps) {
  const today = dateInputValue(new Date());
  const [quota, setQuota] = useState(device.quota_bytes ? String(device.quota_bytes / 1024 ** 3) : "");
  const [period, setPeriod] = useState<Device["quota_period"]>(device.quota_period);
  const [from, setFrom] = useState(device.schedule?.block_from.slice(0, 5) ?? "23:00");
  const [until, setUntil] = useState(device.schedule?.block_until.slice(0, 5) ?? "07:00");
  const [detailsOpen, setDetailsOpen] = useState(false);
  const [editingName, setEditingName] = useState(false);
  const [newName, setNewName] = useState(device.custom_name || device.router_name || "");
  const [usagePreset, setUsagePreset] = useState<UsagePreset>("today");
  const [fromDate, setFromDate] = useState(today);
  const [toDate, setToDate] = useState(today);
  const [detailUsage, setDetailUsage] = useState<number | null>(null);
  const [usageLoading, setUsageLoading] = useState(false);

  const name = device.custom_name || device.router_name || "جهاز بدون اسم";
  const quotaBytes = device.quota_bytes ?? 0;
  const percentage = quotaBytes > 0 ? Math.min(100, (device.quota_used_bytes / quotaBytes) * 100) : 0;

  async function loadDetailUsage(preset: UsagePreset) {
    if (preset === "custom" && (!fromDate || !toDate || fromDate > toDate)) return;
    setUsageLoading(true);
    setUsagePreset(preset);
    const value = await onLoadUsage(device.id, makeRange(preset, fromDate, toDate));
    setDetailUsage(value);
    setUsageLoading(false);
  }

  return (
    <article className="device-card">
      <div className="device-heading">
        <div className="device-icon" aria-hidden="true">{name.toLowerCase().includes("iphone") ? "▯" : "▰"}</div>
        <div className="device-identity">
          {editingName ? <div className="rename-row"><input aria-label="اسم الجهاز الجديد" autoFocus value={newName} onChange={(event) => setNewName(event.target.value)} /><button className="mini-button" disabled={busy || !newName.trim()} onClick={() => void onRename(device, newName).then(() => setEditingName(false))}>حفظ</button><button className="mini-button ghost" onClick={() => setEditingName(false)}>إلغاء</button></div> : <div className="name-row"><h3>{name}</h3><button className="edit-name" onClick={() => setEditingName(true)}>تعديل الاسم</button></div>}
          <p>{device.current_ip || "IP غير معروف"} · {device.mac_addresses[0] || "MAC غير معروف"}</p>
        </div>
        <span className={`status-pill ${!device.is_approved ? "approval" : device.is_online ? "online" : "offline"}`}>{!device.is_approved ? "بانتظار موافقتك" : device.is_online ? "نشط الآن" : "غير نشط"}</span>
      </div>

      <div className="usage-line"><div><span>استهلاك {globalRange.label}</span><strong>{bytesToText(device.used_bytes)}</strong></div><span>{quotaBytes ? `${bytesToText(device.quota_used_bytes)} من ${bytesToText(quotaBytes)} (${periodLabels[device.quota_period]})` : "لم تحدد باقة"}</span></div>
      <div className="progress" aria-label={`استهلاك ${percentage.toFixed(0)}%`}><span style={{ width: `${percentage}%` }} /></div>

      <div className="device-actions"><button className={device.internet_enabled ? "danger-button" : "success-button"} disabled={busy || !controlAvailable} onClick={() => void onQueueInternet(device)}>{!device.is_approved ? "السماح للضيف وتشغيل الإنترنت" : device.internet_enabled ? "فصل الإنترنت" : "تشغيل الإنترنت"}</button><button className="secondary-button" type="button" onClick={() => setDetailsOpen((open) => !open)}>{detailsOpen ? "إخفاء التفاصيل" : "التفاصيل والتحكم"}</button></div>
      {!device.is_approved ? <p className="approval-hint">هذا عنوان جديد؛ الإنترنت محظور حتى تسمح له أنت.</p> : null}
      {!controlAvailable ? <p className="control-hint">يلزم تثبيت آخر نسخة وتشغيل خدمة HomeNet على الهاتف.</p> : null}

      {detailsOpen ? <section className="control-details" aria-label={`إعدادات ${name}`}>
        <p className="control-hint">آخر قراءة: {device.last_seen_at ? new Date(device.last_seen_at).toLocaleString("ar-EG", { timeZone: "Africa/Cairo" }) : "لم تصل قراءة بعد"} — بتوقيت القاهرة. يحتفظ النظام بالجهاز وسجله عند توقف القراءات؛ «غير نشط» لا يؤكد وحده أنه مفصول عن الواي فاي.</p>
        <h4>استهلاك الجهاز حسب المدة</h4>
        <div className="range-buttons compact"><button className={usagePreset === "today" ? "active" : ""} onClick={() => void loadDetailUsage("today")}>اليوم</button><button className={usagePreset === "week" ? "active" : ""} onClick={() => void loadDetailUsage("week")}>7 أيام</button><button className={usagePreset === "month" ? "active" : ""} onClick={() => void loadDetailUsage("month")}>الشهر</button><button className={usagePreset === "custom" ? "active" : ""} onClick={() => setUsagePreset("custom")}>من–إلى</button></div>
        {usagePreset === "custom" ? <div className="date-fields"><label>من<input type="date" value={fromDate} onChange={(event) => setFromDate(event.target.value)} /></label><label>إلى<input type="date" value={toDate} onChange={(event) => setToDate(event.target.value)} /></label><button className="secondary-button" onClick={() => void loadDetailUsage("custom")}>عرض</button></div> : null}
        <div className="detail-usage"><span>{usageLoading ? "جارٍ الحساب…" : detailUsage === null ? `المعروض أعلى البطاقة: ${globalRange.label}` : "استهلاك المدة المختارة"}</span><strong>{usageLoading ? "—" : bytesToText(detailUsage ?? device.used_bytes)}</strong></div>

        <h4>حد الاستهلاك</h4>
        <div className="control-grid"><label>حد الاستهلاك (GB)<input inputMode="decimal" min="0" step="0.01" value={quota} onChange={(event) => setQuota(event.target.value)} placeholder="بدون حد" type="number" /></label><label>مدة حد الاستهلاك<select value={period} onChange={(event) => setPeriod(event.target.value as Device["quota_period"])}><option value="daily">اليوم الحالي</option><option value="weekly">آخر 7 أيام (فترة متحركة)</option><option value="monthly">الشهر الميلادي الحالي</option><option value="one_time">من بداية العداد</option></select></label></div>
        <button className="secondary-button full-button" disabled={busy || (quota !== "" && (!Number.isFinite(Number(quota)) || Number(quota) < 0))} onClick={() => void onSaveLimits(device, quota ? Number(quota) : null, period)}>حفظ حد الاستهلاك</button>
        <p className="control-hint">للمتابعة حاليًا: يعرض المستهلك من الباقة، ولا يفصل الإنترنت تلقائيًا عند بلوغ الحد. تُحتسب قراءات المدة كاملة، بما فيها الاستهلاك السابق لحفظ الحد.</p>

        <h4>جدول الفصل اليومي {device.schedule?.enabled ? <span className="schedule-live">● فعال</span> : null}</h4>
        <div className="schedule-row"><label>فصل من<input type="time" value={from} onChange={(event) => setFrom(event.target.value)} /></label><label>حتى<input type="time" value={until} onChange={(event) => setUntil(event.target.value)} /></label></div>
        <div className="schedule-actions"><button className="secondary-button" disabled={busy || !from || !until} onClick={() => void onSaveSchedule(device, from, until)}>{device.schedule ? "تحديث الجدول" : "تشغيل الجدول"}</button>{device.schedule ? <button className="danger-outline" disabled={busy} onClick={() => void onDeleteSchedule(device)}>إلغاء الجدول</button> : null}</div>
        <p className="control-hint">المواعيد بتوقيت القاهرة، وتتكرر يوميًا. تُراجع كل دقيقة ويستلمها الهاتف أثناء اتصال خدمته بالإنترنت والراوتر؛ التنفيذ قد يتأخر عن الموعد. هذه مواعيد فصل وتشغيل وليست عداد ساعات استخدام.</p>
      </section> : null}
    </article>
  );
}
