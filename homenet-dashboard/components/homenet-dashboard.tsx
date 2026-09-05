"use client";

import { FormEvent, useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { Session } from "@supabase/supabase-js";
import { DeviceCard } from "@/components/device-card";
import { supabase } from "@/lib/supabase";
import type { Agent, Command, Device, DeviceSchedule, Home, UsagePreset, UsageRange } from "@/lib/types";

type Notice = { kind: "ok" | "error" | "info"; text: string } | null;

const actionLabels: Record<string, string> = {
  sync_now: "مزامنة فورية", refresh_names: "تحديث أسماء الأجهزة", set_internet: "تغيير حالة الإنترنت",
  set_quota: "تحديد الجيجات", set_speed: "تحديد السرعة", apply_schedule: "تطبيق الجدول",
  reset_usage: "تصفير الاستهلاك الحقيقي",
};

function formatBytes(bytes: number) {
  if (bytes >= 1024 ** 3) return `${(bytes / 1024 ** 3).toFixed(2)} GB`;
  if (bytes >= 1024 ** 2) return `${(bytes / 1024 ** 2).toFixed(1)} MB`;
  return `${(bytes / 1024).toFixed(1)} KB`;
}

function relativeTime(value: string | null) {
  if (!value) return "لم يتصل بعد";
  const minutes = Math.max(0, Math.round((Date.now() - new Date(value).getTime()) / 60000));
  if (minutes < 1) return "الآن";
  if (minutes < 60) return `منذ ${minutes} دقيقة`;
  return `منذ ${Math.floor(minutes / 60)} ساعة`;
}

function isRecentlySeen(value: string | null) { return Boolean(value && Date.now() - new Date(value).getTime() < 3 * 60 * 1000); }
function supportsInternetControl(version: string | null) {
  if (!version) return false;
  const [major = 0, minor = 0] = version.split(".").map(Number);
  return major > 0 || minor >= 4;
}

function supportsRealReset(version: string | null) {
  if (!version) return false;
  const [major = 0, minor = 0] = version.split(".").map(Number);
  return major > 0 || minor >= 6;
}

function dateInputValue(date: Date) {
  const y = date.getFullYear();
  const m = String(date.getMonth() + 1).padStart(2, "0");
  const d = String(date.getDate()).padStart(2, "0");
  return `${y}-${m}-${d}`;
}

function rangeFor(preset: UsagePreset, fromDate: string, toDate: string): UsageRange {
  const now = new Date();
  let start = new Date(now);
  let end = new Date(now);
  end.setHours(23, 59, 59, 999);
  if (preset === "today") start.setHours(0, 0, 0, 0);
  if (preset === "week") { start.setDate(start.getDate() - 6); start.setHours(0, 0, 0, 0); }
  if (preset === "month") { start.setDate(1); start.setHours(0, 0, 0, 0); }
  if (preset === "custom") {
    const safeFrom = fromDate || dateInputValue(now);
    const safeTo = toDate || safeFrom;
    start = new Date(`${safeFrom}T00:00:00`);
    end = new Date(`${safeTo}T23:59:59.999`);
    if (start > end) [start, end] = [new Date(`${safeTo}T00:00:00`), new Date(`${safeFrom}T23:59:59.999`)];
  }
  const label = preset === "today" ? "اليوم" : preset === "week" ? "آخر 7 أيام" : preset === "month" ? "هذا الشهر" : `${fromDate} إلى ${toDate}`;
  return { from: start.toISOString(), to: end.toISOString(), label };
}

export function HomeNetDashboard() {
  const today = dateInputValue(new Date());
  const [session, setSession] = useState<Session | null>(null);
  const [loading, setLoading] = useState(true);
  const [authMode, setAuthMode] = useState<"login" | "signup">("login");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [home, setHome] = useState<Home | null>(null);
  const [agent, setAgent] = useState<Agent | null>(null);
  const [devices, setDevices] = useState<Device[]>([]);
  const [commands, setCommands] = useState<Command[]>([]);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [notice, setNotice] = useState<Notice>(null);
  const [lastLoadedAt, setLastLoadedAt] = useState<Date | null>(null);
  const [usagePreset, setUsagePreset] = useState<UsagePreset>("today");
  const [fromDate, setFromDate] = useState(today);
  const [toDate, setToDate] = useState(today);
  const hasLoadedDashboard = useRef(false);
  const usageRange = useMemo(() => rangeFor(usagePreset, fromDate, toDate), [usagePreset, fromDate, toDate]);

  const loadDashboard = useCallback(async () => {
    if (!session) return;
    if (!hasLoadedDashboard.current) setLoading(true);
    const { data: homeRow, error: homeError } = await supabase.from("homenet_homes").select("id,name,router_model,usage_started_at,block_new_devices").limit(1).maybeSingle();
    if (homeError) { setNotice({ kind: "error", text: `تعذر تحميل المنزل: ${homeError.message}` }); setLoading(false); return; }
    if (!homeRow) { setHome(null); setDevices([]); setCommands([]); hasLoadedDashboard.current = true; setLoading(false); return; }

    setHome(homeRow as Home);
    const [agentResult, devicesResult, addressesResult, totalsResult, schedulesResult, commandsResult] = await Promise.all([
      supabase.from("homenet_agents").select("id,app_version,last_seen_at").eq("home_id", homeRow.id).order("last_seen_at", { ascending: false }).limit(1).maybeSingle(),
      supabase.from("homenet_devices").select("id,router_name,custom_name,current_ip,is_online,internet_enabled,is_approved,quota_bytes,quota_period,speed_limit_kbps,last_seen_at").eq("home_id", homeRow.id).order("current_ip", { ascending: true }),
      supabase.from("homenet_device_addresses").select("device_id,mac").eq("home_id", homeRow.id),
      supabase.rpc("homenet_usage_totals", { p_home_id: homeRow.id, p_from: usageRange.from, p_to: usageRange.to, p_device_id: null }),
      supabase.from("homenet_schedules").select("id,device_id,block_from,block_until,days_of_week,enabled").eq("home_id", homeRow.id).eq("enabled", true).order("updated_at", { ascending: false }),
      supabase.from("homenet_commands").select("id,action,status,created_at,error_message").eq("home_id", homeRow.id).order("created_at", { ascending: false }).limit(8),
    ]);
    const firstError = agentResult.error || devicesResult.error || addressesResult.error || totalsResult.error || schedulesResult.error || commandsResult.error;
    if (firstError) setNotice({ kind: "error", text: `تعذر تحديث البيانات: ${firstError.message}` });

    const totals = new Map<string, { filtered: number; quota: number; samples: number }>();
    for (const row of totalsResult.data ?? []) totals.set(row.device_id, { filtered: Number(row.filtered_bytes), quota: Number(row.quota_period_bytes), samples: Number(row.sample_count) });
    const addresses = new Map<string, string[]>();
    for (const row of addressesResult.data ?? []) addresses.set(row.device_id, [...(addresses.get(row.device_id) ?? []), row.mac]);
    const schedules = new Map<string, DeviceSchedule>();
    for (const row of schedulesResult.data ?? []) if (!schedules.has(row.device_id)) schedules.set(row.device_id, row as DeviceSchedule);

    setAgent(agentResult.data ?? null);
    setDevices((devicesResult.data ?? []).map((device) => ({
      ...device,
      is_online: isRecentlySeen(device.last_seen_at),
      used_bytes: totals.get(device.id)?.filtered ?? 0,
      quota_used_bytes: totals.get(device.id)?.quota ?? 0,
      sample_count: totals.get(device.id)?.samples ?? 0,
      mac_addresses: addresses.get(device.id) ?? [],
      schedule: schedules.get(device.id) ?? null,
    })) as Device[]);
    setCommands((commandsResult.data ?? []) as Command[]);
    setLastLoadedAt(new Date());
    hasLoadedDashboard.current = true;
    setLoading(false);
  }, [session, usageRange]);

  useEffect(() => {
    void supabase.auth.getSession().then(({ data }) => { setSession(data.session); setLoading(false); });
    const { data } = supabase.auth.onAuthStateChange((_event, nextSession) => setSession(nextSession));
    return () => data.subscription.unsubscribe();
  }, []);

  useEffect(() => {
    if (!session) return;
    const initialLoad = window.setTimeout(() => void loadDashboard(), 0);
    const channel = supabase.channel(`homenet-dashboard-${session.user.id}`)
      .on("postgres_changes", { event: "*", schema: "public", table: "homenet_devices" }, () => void loadDashboard())
      .on("postgres_changes", { event: "INSERT", schema: "public", table: "homenet_usage_samples" }, () => void loadDashboard())
      .on("postgres_changes", { event: "*", schema: "public", table: "homenet_usage_daily" }, () => void loadDashboard())
      .on("postgres_changes", { event: "*", schema: "public", table: "homenet_agents" }, () => void loadDashboard())
      .on("postgres_changes", { event: "*", schema: "public", table: "homenet_commands" }, () => void loadDashboard())
      .on("postgres_changes", { event: "*", schema: "public", table: "homenet_schedules" }, () => void loadDashboard()).subscribe();
    const polling = window.setInterval(() => void loadDashboard(), 30_000);
    return () => { window.clearTimeout(initialLoad); window.clearInterval(polling); void supabase.removeChannel(channel); };
  }, [loadDashboard, session]);

  const totalUsage = useMemo(() => devices.reduce((sum, device) => sum + device.used_bytes, 0), [devices]);
  const pendingCount = commands.filter((command) => command.status === "pending" || command.status === "processing").length;
  const approvalPendingCount = devices.filter((device) => !device.is_approved).length;
  const internetControlAvailable = isRecentlySeen(agent?.last_seen_at ?? null) && supportsInternetControl(agent?.app_version ?? null);
  const realResetAvailable = supportsRealReset(agent?.app_version ?? null);

  async function submitAuth(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); setLoading(true); setNotice(null);
    const result = authMode === "login" ? await supabase.auth.signInWithPassword({ email, password }) : await supabase.auth.signUp({ email, password, options: { emailRedirectTo: window.location.origin } });
    setLoading(false);
    if (result.error) setNotice({ kind: "error", text: result.error.message });
    else if (authMode === "signup" && !result.data.session) { setNotice({ kind: "info", text: "راجع بريدك واضغط رابط التأكيد، ثم سجّل الدخول." }); setAuthMode("login"); }
  }

  async function createHome() {
    if (!session) return;
    setBusyId("home");
    const { error } = await supabase.from("homenet_homes").insert({ owner_id: session.user.id });
    setBusyId(null);
    if (error) setNotice({ kind: "error", text: error.message });
    else { setNotice({ kind: "ok", text: "تم إنشاء شبكة المنزل. الخطوة التالية ربط تطبيق الهاتف." }); await loadDashboard(); }
  }

  async function queueCommand(action: string, deviceId?: string, payload: Record<string, unknown> = {}) {
    if (!home) return false;
    const { error } = await supabase.from("homenet_commands").insert({ home_id: home.id, device_id: deviceId ?? null, action, payload });
    if (error) { setNotice({ kind: "error", text: `لم يُحفظ الأمر: ${error.message}` }); return false; }
    setNotice({ kind: "ok", text: "تم حفظ الأمر، وسيُنفّذ عند اتصال الهاتف والراوتر." }); await loadDashboard(); return true;
  }

  async function queueInternet(device: Device) {
    if (!home) return;
    setBusyId(device.id);
    const nextEnabled = !device.internet_enabled;
    if (nextEnabled && !device.is_approved) {
      const approvalResult = await supabase.from("homenet_devices")
        .update({ is_approved: true, updated_at: new Date().toISOString() })
        .eq("id", device.id)
        .eq("home_id", home.id);
      if (approvalResult.error) {
        setNotice({ kind: "error", text: `تعذر اعتماد الجهاز: ${approvalResult.error.message}` });
        setBusyId(null);
        return;
      }
      await supabase.from("homenet_commands")
        .update({ status: "cancelled", updated_at: new Date().toISOString() })
        .eq("home_id", home.id)
        .eq("device_id", device.id)
        .eq("action", "set_internet")
        .eq("status", "pending")
        .contains("payload", { enabled: false });
    }
    await queueCommand("set_internet", device.id, { enabled: nextEnabled, approved: nextEnabled && !device.is_approved });
    setBusyId(null);
  }

  async function renameDevice(device: Device, name: string) {
    if (!home) return;
    setBusyId(device.id);
    const { error } = await supabase.from("homenet_devices").update({ custom_name: name.trim(), updated_at: new Date().toISOString() }).eq("id", device.id).eq("home_id", home.id);
    setBusyId(null);
    if (error) setNotice({ kind: "error", text: `تعذر تغيير الاسم: ${error.message}` });
    else { setNotice({ kind: "ok", text: `تم تغيير اسم الجهاز إلى «${name.trim()}».` }); await loadDashboard(); }
  }

  async function saveLimits(device: Device, quotaGb: number | null, quotaPeriod: Device["quota_period"]) {
    if (!home) return;
    if (quotaGb !== null && (!Number.isFinite(quotaGb) || quotaGb < 0 || !Number.isSafeInteger(Math.round(quotaGb * 1024 ** 3)))) {
      setNotice({ kind: "error", text: "اكتب حد استهلاك صحيحًا أكبر من أو يساوي صفرًا." });
      return;
    }
    setBusyId(device.id);
    const { error } = await supabase.from("homenet_devices").update({ quota_bytes: quotaGb === null ? null : Math.round(quotaGb * 1024 ** 3), quota_period: quotaPeriod }).eq("id", device.id).eq("home_id", home.id);
    setBusyId(null);
    if (error) setNotice({ kind: "error", text: error.message }); else { setNotice({ kind: "ok", text: "تم حفظ حد الاستهلاك ومدته للمتابعة. الفصل التلقائي عند نفاد الباقة غير مفعّل حاليًا." }); await loadDashboard(); }
  }

  async function saveSchedule(device: Device, from: string, until: string) {
    if (!home) return;
    setBusyId(device.id);
    const values = { home_id: home.id, device_id: device.id, name: "جدول يومي", block_from: from, block_until: until, days_of_week: [0, 1, 2, 3, 4, 5, 6], enabled: true, updated_at: new Date().toISOString() };
    const result = device.schedule ? await supabase.from("homenet_schedules").update(values).eq("id", device.schedule.id).eq("home_id", home.id) : await supabase.from("homenet_schedules").insert(values);
    setBusyId(null);
    if (result.error) setNotice({ kind: "error", text: result.error.message }); else { setNotice({ kind: "ok", text: "تم حفظ الجدول. تطبيق الهاتف ينفّذه تلقائيًا كل يوم." }); await loadDashboard(); }
  }

  async function deleteSchedule(device: Device) {
    if (!home || !device.schedule) return;
    setBusyId(device.id);
    const { error } = await supabase.from("homenet_schedules").delete().eq("id", device.schedule.id).eq("home_id", home.id);
    setBusyId(null);
    if (error) setNotice({ kind: "error", text: error.message }); else { setNotice({ kind: "ok", text: "تم إلغاء الجدول. حالة الإنترنت الحالية لن تتغير إلا بأمر منك." }); await loadDashboard(); }
  }

  async function loadDeviceUsage(deviceId: string, range: UsageRange) {
    if (!home) return 0;
    const { data, error } = await supabase.rpc("homenet_usage_totals", { p_home_id: home.id, p_from: range.from, p_to: range.to, p_device_id: deviceId });
    if (error) { setNotice({ kind: "error", text: `تعذر حساب المدة: ${error.message}` }); return 0; }
    return Number(data?.[0]?.filtered_bytes ?? 0);
  }

  async function resetUsage() {
    if (!home || !window.confirm("سيتم حذف كل القراءات السابقة وتصفير استهلاك جميع الأجهزة. هل تريد المتابعة؟")) return;
    setBusyId("reset");
    const startedAt = new Date().toISOString();
    const homeResult = await supabase.from("homenet_homes").update({ usage_started_at: startedAt, updated_at: startedAt }).eq("id", home.id);
    if (homeResult.error) { setNotice({ kind: "error", text: `تعذر بدء عداد جديد: ${homeResult.error.message}` }); setBusyId(null); return; }
    const [deleteDailyResult, deleteCountersResult, deleteRawResult] = await Promise.all([
      supabase.from("homenet_usage_daily").delete().eq("home_id", home.id),
      supabase.from("homenet_usage_counters").delete().eq("home_id", home.id),
      supabase.from("homenet_usage_samples").delete().eq("home_id", home.id),
    ]);
    let resetCommandError: string | null = null;
    if (realResetAvailable) {
      const commandResult = await supabase.from("homenet_commands").insert({
        home_id: home.id,
        device_id: null,
        action: "reset_usage",
        payload: { requested_at: startedAt },
      });
      resetCommandError = commandResult.error?.message ?? null;
    }
    setBusyId(null);
    const deleteError = deleteDailyResult.error || deleteCountersResult.error || deleteRawResult.error;
    if (deleteError) setNotice({ kind: "info", text: `بدأ العداد الجديد، لكن تعذر تنظيف جزء من البيانات: ${deleteError.message}` });
    else if (resetCommandError) setNotice({ kind: "info", text: `تم تصفير السحابة، لكن تعذر إرسال تصفير الهاتف: ${resetCommandError}` });
    else if (realResetAvailable) setNotice({ kind: "ok", text: "تم تصفير السحابة، والهاتف سيقرأ قيم الراوتر الحالية كخط أساس جديد دون احتسابها." });
    else setNotice({ kind: "info", text: "تم تصفير السحابة. ثبّت تطبيق v0.6.0 ثم اضغط التصفير مرة أخرى لتصفير ذاكرة الهاتف أيضًا." });
    await loadDashboard();
  }

  if (loading && !session) return <main className="center-shell"><div className="loader" aria-label="جارٍ التحميل" /></main>;

  if (!session) return <main className="auth-shell"><section className="auth-intro"><span className="eyebrow">HOME NETWORK CONTROL</span><h1>شبكتك واضحة.<br /><em>جهازًا بجهاز.</em></h1><p>تابع الاستهلاك الحقيقي والأسماء، وتحكم في الإنترنت لكل جهاز من شاشة واحدة.</p><div className="feature-row"><span>● قراءة حقيقية</span><span>● جداول تلقائية</span><span>● أسماء مخصصة</span></div></section><section className="auth-card"><div className="brand"><span className="brand-mark">H</span><div><strong>HomeNet</strong><small>لوحة إدارة المنزل</small></div></div><h2>{authMode === "login" ? "أهلًا بعودتك" : "أنشئ حسابك"}</h2><p className="muted">{authMode === "login" ? "سجّل الدخول لمتابعة الشبكة" : "حساب واحد لكل بيانات منزلك"}</p>{notice && <div className={`notice ${notice.kind}`}>{notice.text}</div>}<form onSubmit={(event) => void submitAuth(event)}><label>البريد الإلكتروني<input required autoComplete="email" dir="ltr" type="email" value={email} onChange={(event) => setEmail(event.target.value)} /></label><label>كلمة المرور<input required minLength={6} autoComplete={authMode === "login" ? "current-password" : "new-password"} dir="ltr" type="password" value={password} onChange={(event) => setPassword(event.target.value)} /></label><button className="primary-button" disabled={loading} type="submit">{loading ? "لحظة..." : authMode === "login" ? "تسجيل الدخول" : "إنشاء الحساب"}</button></form><button className="text-button" onClick={() => { setAuthMode(authMode === "login" ? "signup" : "login"); setNotice(null); }}>{authMode === "login" ? "ليس لديك حساب؟ أنشئ واحدًا" : "لديك حساب؟ سجّل الدخول"}</button></section></main>;

  if (!home && !loading) return <main className="center-shell"><section className="panel narrow-panel"><div className="brand"><span className="brand-mark">H</span><div><strong>HomeNet</strong><small>البدء لأول مرة</small></div></div><span className="eyebrow">الخطوة 1 من 2</span><h1>أنشئ شبكة منزلك</h1><p className="muted">هنحفظ تحتها الأجهزة والاستهلاك والأوامر. لن نرسل كلمة مرور الراوتر للسحابة.</p>{notice && <div className={`notice ${notice.kind}`}>{notice.text}</div>}<button className="primary-button" disabled={busyId === "home"} onClick={() => void createHome()}>إنشاء شبكة المنزل</button><button className="text-button" onClick={() => void supabase.auth.signOut()}>تسجيل الخروج</button></section></main>;

  if (!home) return <main className="center-shell"><div className="loader" aria-label="جارٍ التحميل" /></main>;

  return <main className="dashboard-shell">
    <header className="topbar"><div className="brand"><span className="brand-mark">H</span><div><strong>HomeNet</strong><small>{home.name}</small></div></div><div className="header-actions"><button className="icon-button" aria-label="تحديث" disabled={loading} onClick={() => void loadDashboard()}>↻</button><button className="logout-button" onClick={() => void supabase.auth.signOut()}>خروج</button></div></header>
    <section className="hero-panel"><div><span className="eyebrow">نظرة سريعة</span><h1>شبكة المنزل</h1><p>{devices.length ? `تم العثور على ${devices.length} أجهزة محفوظة` : "بانتظار أول قراءة من الهاتف"}</p></div><div className={`agent-state ${isRecentlySeen(agent?.last_seen_at ?? null) ? "active" : "waiting"}`}><span className="pulse" /><div><strong>{isRecentlySeen(agent?.last_seen_at ?? null) ? "الهاتف يرفع قراءات" : "الهاتف لا يرفع الآن"}</strong><small>{relativeTime(agent?.last_seen_at ?? null)}</small></div></div></section>
    {notice && <div className={`notice ${notice.kind}`}>{notice.text}<button aria-label="إغلاق" onClick={() => setNotice(null)}>×</button></div>}
    <section className="system-note"><span className="system-note-icon">i</span><div><strong>{home.block_new_devices ? "حماية الضيوف مفعّلة" : internetControlAvailable ? "الفصل والتشغيل والجدولة جاهزة" : "التحكم ينتظر اتصال تطبيق الهاتف"}</strong><p>{home.block_new_devices ? `أي MAC جديد يُفصل تلقائيًا حتى توافق عليه من هنا${approvalPendingCount ? ` · لديك ${approvalPendingCount} بانتظار الموافقة` : ""}. تغيير IP وحده لا يؤثر.` : internetControlAvailable ? "الهاتف ينفّذ الأوامر والجداول في الخلفية. إصدار v0.6.0 يضيف حماية الشاشة المغلقة والتصفير الحقيقي." : "ثبّت آخر نسخة، سجّل دخول الراوتر والحساب، ثم شغّل الخدمة."}</p></div></section>

    <section className="usage-filter"><div><span className="eyebrow">فترة الاستهلاك</span><h2>اعرض المدة التي تهمك</h2></div><div className="range-buttons"><button className={usagePreset === "today" ? "active" : ""} onClick={() => setUsagePreset("today")}>اليوم</button><button className={usagePreset === "week" ? "active" : ""} onClick={() => setUsagePreset("week")}>7 أيام</button><button className={usagePreset === "month" ? "active" : ""} onClick={() => setUsagePreset("month")}>الشهر</button><button className={usagePreset === "custom" ? "active" : ""} onClick={() => setUsagePreset("custom")}>من تاريخ إلى تاريخ</button></div>{usagePreset === "custom" ? <div className="date-fields global"><label>من<input type="date" value={fromDate} onChange={(event) => setFromDate(event.target.value)} /></label><label>إلى<input type="date" value={toDate} onChange={(event) => setToDate(event.target.value)} /></label></div> : null}</section>

    <section className="stats-grid"><article className="stat-card accent"><span>استهلاك {usageRange.label}</span><strong>{formatBytes(totalUsage)}</strong><small>من نقطة البداية الجديدة فقط</small></article><article className="stat-card"><span>الأجهزة المعروفة</span><strong>{devices.length}</strong><small>{approvalPendingCount ? `${approvalPendingCount} تنتظر موافقتك` : `${devices.filter((device) => device.is_online).length} ظهرت خلال آخر 3 دقائق`}</small></article><article className="stat-card"><span>أوامر قيد التنفيذ</span><strong>{pendingCount}</strong><small>{internetControlAvailable ? "يستلمها الهاتف كل 12 ثانية" : "تنتظر اتصال وكيل التحكم"}</small></article><article className="stat-card"><span>آخر تحديث للوحة</span><strong className="small-value">{lastLoadedAt ? lastLoadedAt.toLocaleTimeString("ar-EG", { hour: "2-digit", minute: "2-digit" }) : "—"}</strong><small>تحديث تلقائي كل 30 ثانية</small></article></section>

    <section className="section-heading"><div><span className="eyebrow">الأجهزة</span><h2>أجهزة المنزل</h2><p className="muted">يمكنك تعديل أي اسم وفتح تفاصيل مستقلة لكل جهاز.</p></div><div className="toolbar"><button className="secondary-button" disabled={loading} onClick={() => void loadDashboard()}>تحديث اللوحة</button><button className="danger-outline" disabled={busyId === "reset"} onClick={() => void resetUsage()}>تصفير حقيقي لكل القراءات</button></div></section>
    {loading ? <div className="loader" aria-label="جارٍ تحديث البيانات" /> : devices.length ? <section className="devices-grid">{devices.map((device) => <DeviceCard key={device.id} device={device} busy={busyId === device.id} controlAvailable={internetControlAvailable} globalRange={usageRange} onQueueInternet={queueInternet} onRename={renameDevice} onSaveLimits={saveLimits} onSaveSchedule={saveSchedule} onDeleteSchedule={deleteSchedule} onLoadUsage={loadDeviceUsage} />)}</section> : <section className="empty-state"><div className="empty-icon">⌁</div><h3>في انتظار أول قراءة جديدة</h3><p>العداد بدأ من الصفر، وستظهر أول قراءة يرفعها الهاتف هنا تلقائيًا.</p></section>}

    <section className="queue-panel"><div className="section-heading"><div><span className="eyebrow">سجل التحكم</span><h2>أوامر الراوتر</h2><p className="muted">النجاح هنا يعني أن الهاتف حفظ التغيير فعلًا داخل الراوتر.</p></div></div>{commands.length ? <div className="command-list">{commands.map((command) => <div className="command-row" key={command.id}><span className={`command-dot ${command.status}`} /><strong>{actionLabels[command.action] ?? command.action}</strong><span>{command.status === "pending" ? "في الانتظار" : command.status === "processing" ? "جارٍ التنفيذ" : command.status === "succeeded" ? "تم" : command.status === "failed" ? "فشل" : "ملغي"}</span><small>{new Date(command.created_at).toLocaleString("ar-EG")}</small></div>)}</div> : <p className="muted">لا توجد أوامر حتى الآن.</p>}</section>
    <footer><span>HomeNet Agent</span><span>كل حساب يرى بيانات منزله فقط</span></footer>
  </main>;
}
