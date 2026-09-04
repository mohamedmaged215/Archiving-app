"use client";

import { FormEvent, useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { Session } from "@supabase/supabase-js";
import { DeviceCard } from "@/components/device-card";
import { supabase } from "@/lib/supabase";
import type { Agent, Command, Device, Home } from "@/lib/types";

type Notice = { kind: "ok" | "error" | "info"; text: string } | null;

const actionLabels: Record<string, string> = {
  sync_now: "مزامنة فورية",
  refresh_names: "تحديث أسماء الأجهزة",
  set_internet: "تغيير حالة الإنترنت",
  set_quota: "تحديد الجيجات",
  set_speed: "تحديد السرعة",
  apply_schedule: "تطبيق الجدول",
};

function formatBytes(bytes: number) {
  if (bytes >= 1024 ** 3) return `${(bytes / 1024 ** 3).toFixed(2)} GB`;
  return `${(bytes / 1024 ** 2).toFixed(1)} MB`;
}

function relativeTime(value: string | null) {
  if (!value) return "لم يتصل بعد";
  const minutes = Math.max(0, Math.round((Date.now() - new Date(value).getTime()) / 60000));
  if (minutes < 1) return "الآن";
  if (minutes < 60) return `منذ ${minutes} دقيقة`;
  return `منذ ${Math.floor(minutes / 60)} ساعة`;
}

function periodStart(period: Device["quota_period"]) {
  const value = new Date();
  if (period === "one_time") return 0;
  if (period === "weekly") return value.getTime() - (7 * 24 * 60 * 60 * 1000);
  if (period === "monthly") value.setDate(1);
  value.setHours(0, 0, 0, 0);
  return value.getTime();
}

function isRecentlySeen(value: string | null) {
  return Boolean(value && Date.now() - new Date(value).getTime() < 3 * 60 * 1000);
}

function supportsInternetControl(version: string | null) {
  if (!version) return false;
  const [major = 0, minor = 0] = version.split(".").map((part) => Number(part));
  return major > 0 || minor >= 4;
}

export function HomeNetDashboard() {
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
  const hasLoadedDashboard = useRef(false);

  const loadDashboard = useCallback(async () => {
    if (!session) return;
    if (!hasLoadedDashboard.current) setLoading(true);

    const { data: homeRow, error: homeError } = await supabase
      .from("homenet_homes")
      .select("id,name,router_model")
      .limit(1)
      .maybeSingle();

    if (homeError) {
      setNotice({ kind: "error", text: `تعذر تحميل المنزل: ${homeError.message}` });
      setLoading(false);
      return;
    }

    if (!homeRow) {
      setHome(null);
      setDevices([]);
      setCommands([]);
      hasLoadedDashboard.current = true;
      setLoading(false);
      return;
    }

    setHome(homeRow);
    const [agentResult, devicesResult, addressesResult, usageResult, commandsResult] = await Promise.all([
      supabase.from("homenet_agents").select("id,app_version,last_seen_at").eq("home_id", homeRow.id).order("last_seen_at", { ascending: false }).limit(1).maybeSingle(),
      supabase.from("homenet_devices").select("id,router_name,custom_name,current_ip,is_online,internet_enabled,quota_bytes,quota_period,speed_limit_kbps,last_seen_at").eq("home_id", homeRow.id).order("current_ip", { ascending: true }),
      supabase.from("homenet_device_addresses").select("device_id,mac").eq("home_id", homeRow.id),
      supabase.from("homenet_usage_samples").select("device_id,delta_bytes,captured_at").eq("home_id", homeRow.id),
      supabase.from("homenet_commands").select("id,action,status,created_at,error_message").eq("home_id", homeRow.id).order("created_at", { ascending: false }).limit(8),
    ]);

    const firstError = agentResult.error || devicesResult.error || addressesResult.error || usageResult.error || commandsResult.error;
    if (firstError) setNotice({ kind: "error", text: `تعذر تحديث البيانات: ${firstError.message}` });

    const monthlyTotals = new Map<string, number>();
    const periodTotals = new Map<string, number>();
    const monthStart = periodStart("monthly");
    const devicePeriods = new Map((devicesResult.data ?? []).map((device) => [device.id, device.quota_period as Device["quota_period"]]));
    for (const row of usageResult.data ?? []) {
      const capturedAt = new Date(row.captured_at).getTime();
      if (capturedAt >= monthStart) monthlyTotals.set(row.device_id, (monthlyTotals.get(row.device_id) ?? 0) + Number(row.delta_bytes));
      if (capturedAt >= periodStart(devicePeriods.get(row.device_id) ?? "monthly")) {
        periodTotals.set(row.device_id, (periodTotals.get(row.device_id) ?? 0) + Number(row.delta_bytes));
      }
    }

    const addresses = new Map<string, string[]>();
    for (const row of addressesResult.data ?? []) {
      addresses.set(row.device_id, [...(addresses.get(row.device_id) ?? []), row.mac]);
    }

    setAgent(agentResult.data ?? null);
    setDevices((devicesResult.data ?? []).map((device) => ({
      ...device,
      is_online: isRecentlySeen(device.last_seen_at),
      used_bytes: periodTotals.get(device.id) ?? 0,
      used_month_bytes: monthlyTotals.get(device.id) ?? 0,
      mac_addresses: addresses.get(device.id) ?? [],
    })) as Device[]);
    setCommands((commandsResult.data ?? []) as Command[]);
    setLastLoadedAt(new Date());
    hasLoadedDashboard.current = true;
    setLoading(false);
  }, [session]);

  useEffect(() => {
    void supabase.auth.getSession().then(({ data }) => {
      setSession(data.session);
      setLoading(false);
    });
    const { data } = supabase.auth.onAuthStateChange((_event, nextSession) => setSession(nextSession));
    return () => data.subscription.unsubscribe();
  }, []);

  useEffect(() => {
    if (!session) return;
    const initialLoad = window.setTimeout(() => void loadDashboard(), 0);

    const channel = supabase
      .channel(`homenet-dashboard-${session.user.id}`)
      .on("postgres_changes", { event: "*", schema: "public", table: "homenet_devices" }, () => void loadDashboard())
      .on("postgres_changes", { event: "INSERT", schema: "public", table: "homenet_usage_samples" }, () => void loadDashboard())
      .on("postgres_changes", { event: "*", schema: "public", table: "homenet_agents" }, () => void loadDashboard())
      .on("postgres_changes", { event: "*", schema: "public", table: "homenet_commands" }, () => void loadDashboard())
      .subscribe();
    const polling = window.setInterval(() => void loadDashboard(), 30_000);

    return () => {
      window.clearTimeout(initialLoad);
      window.clearInterval(polling);
      void supabase.removeChannel(channel);
    };
  }, [loadDashboard, session]);

  const totalUsage = useMemo(() => devices.reduce((sum, device) => sum + device.used_month_bytes, 0), [devices]);
  const pendingCount = commands.filter((command) => command.status === "pending" || command.status === "processing").length;
  const internetControlAvailable = isRecentlySeen(agent?.last_seen_at ?? null) && supportsInternetControl(agent?.app_version ?? null);

  async function submitAuth(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setLoading(true);
    setNotice(null);
    const result = authMode === "login"
      ? await supabase.auth.signInWithPassword({ email, password })
      : await supabase.auth.signUp({
          email,
          password,
          options: { emailRedirectTo: window.location.origin },
        });
    setLoading(false);
    if (result.error) {
      setNotice({ kind: "error", text: result.error.message });
    } else if (authMode === "signup" && !result.data.session) {
      setNotice({ kind: "info", text: "راجع بريدك واضغط رابط التأكيد، ثم سجّل الدخول." });
      setAuthMode("login");
    }
  }

  async function createHome() {
    if (!session) return;
    setBusyId("home");
    const { error } = await supabase.from("homenet_homes").insert({ owner_id: session.user.id });
    setBusyId(null);
    if (error) setNotice({ kind: "error", text: error.message });
    else {
      setNotice({ kind: "ok", text: "تم إنشاء شبكة المنزل. الخطوة التالية ربط تطبيق الهاتف." });
      await loadDashboard();
    }
  }

  async function queueCommand(action: string, deviceId?: string, payload: Record<string, unknown> = {}) {
    if (!home) return false;
    const { error } = await supabase.from("homenet_commands").insert({
      home_id: home.id,
      device_id: deviceId ?? null,
      action,
      payload,
    });
    if (error) {
      setNotice({ kind: "error", text: `لم يُحفظ الأمر: ${error.message}` });
      return false;
    }
    setNotice({ kind: "ok", text: "تم حفظ الأمر في الطابور، وسيُنفّذ عند اتصال الهاتف والراوتر." });
    await loadDashboard();
    return true;
  }

  async function queueInternet(device: Device) {
    setBusyId(device.id);
    await queueCommand("set_internet", device.id, { enabled: !device.internet_enabled });
    setBusyId(null);
  }

  async function saveLimits(device: Device, quotaGb: number | null, speedMbps: number | null, quotaPeriod: Device["quota_period"]) {
    if (!home) return;
    setBusyId(device.id);
    const quotaBytes = quotaGb === null ? null : Math.round(quotaGb * 1024 ** 3);
    const speedKbps = speedMbps === null ? null : Math.round(speedMbps * 1000);
    const { error } = await supabase.from("homenet_devices").update({ quota_bytes: quotaBytes, quota_period: quotaPeriod, speed_limit_kbps: speedKbps }).eq("id", device.id);
    if (error) setNotice({ kind: "error", text: error.message });
    else {
      setNotice({ kind: "ok", text: "تم حفظ حد الاستخدام ومدته. التطبيق على الراوتر سيتفعّل بعد إكمال ربط Access Control." });
      await loadDashboard();
    }
    setBusyId(null);
  }

  async function saveSchedule(device: Device, from: string, until: string) {
    if (!home) return;
    setBusyId(device.id);
    const { error } = await supabase.from("homenet_schedules").insert({
      home_id: home.id,
      device_id: device.id,
      name: "جدول يومي",
      block_from: from,
      block_until: until,
    });
    if (error) setNotice({ kind: "error", text: error.message });
    else setNotice({ kind: "ok", text: "تم حفظ الجدول. التنفيذ الفعلي سيتفعّل بعد إكمال ربط Access Control." });
    setBusyId(null);
  }

  if (loading && !session) {
    return <main className="center-shell"><div className="loader" aria-label="جارٍ التحميل" /></main>;
  }

  if (!session) {
    return (
      <main className="auth-shell">
        <section className="auth-intro">
          <span className="eyebrow">HOME NETWORK CONTROL</span>
          <h1>شبكتك واضحة.<br /><em>جهازًا بجهاز.</em></h1>
          <p>تابع الاستهلاك الحقيقي والأسماء، واحفظ حد الجيجات المناسب لكل جهاز من شاشة واحدة.</p>
          <div className="feature-row"><span>● قراءة حقيقية</span><span>● 8 أجهزة محفوظة</span><span>● أسماء أجهزتك</span></div>
        </section>
        <section className="auth-card">
          <div className="brand"><span className="brand-mark">H</span><div><strong>HomeNet</strong><small>لوحة إدارة المنزل</small></div></div>
          <h2>{authMode === "login" ? "أهلًا بعودتك" : "أنشئ حسابك"}</h2>
          <p className="muted">{authMode === "login" ? "سجّل الدخول لمتابعة الشبكة" : "حساب واحد لكل بيانات منزلك"}</p>
          {notice && <div className={`notice ${notice.kind}`}>{notice.text}</div>}
          <form onSubmit={(event) => void submitAuth(event)}>
            <label>البريد الإلكتروني<input required autoComplete="email" dir="ltr" type="email" value={email} onChange={(event) => setEmail(event.target.value)} /></label>
            <label>كلمة المرور<input required minLength={6} autoComplete={authMode === "login" ? "current-password" : "new-password"} dir="ltr" type="password" value={password} onChange={(event) => setPassword(event.target.value)} /></label>
            <button className="primary-button" disabled={loading} type="submit">{loading ? "لحظة..." : authMode === "login" ? "تسجيل الدخول" : "إنشاء الحساب"}</button>
          </form>
          <button className="text-button" onClick={() => { setAuthMode(authMode === "login" ? "signup" : "login"); setNotice(null); }}>
            {authMode === "login" ? "ليس لديك حساب؟ أنشئ واحدًا" : "لديك حساب؟ سجّل الدخول"}
          </button>
        </section>
      </main>
    );
  }

  if (!home && !loading) {
    return (
      <main className="center-shell">
        <section className="panel narrow-panel">
          <div className="brand"><span className="brand-mark">H</span><div><strong>HomeNet</strong><small>البدء لأول مرة</small></div></div>
          <span className="eyebrow">الخطوة 1 من 2</span>
          <h1>أنشئ شبكة منزلك</h1>
          <p className="muted">هنحفظ تحتها الأجهزة والاستهلاك والأوامر. لن نرسل كلمة مرور الراوتر للسحابة.</p>
          {notice && <div className={`notice ${notice.kind}`}>{notice.text}</div>}
          <button className="primary-button" disabled={busyId === "home"} onClick={() => void createHome()}>إنشاء شبكة المنزل</button>
          <button className="text-button" onClick={() => void supabase.auth.signOut()}>تسجيل الخروج</button>
        </section>
      </main>
    );
  }

  return (
    <main className="dashboard-shell">
      <header className="topbar">
        <div className="brand"><span className="brand-mark">H</span><div><strong>HomeNet</strong><small>{home?.name}</small></div></div>
        <div className="header-actions">
          <button className="icon-button" aria-label="تحديث" disabled={loading} onClick={() => void loadDashboard()}>↻</button>
          <button className="logout-button" onClick={() => void supabase.auth.signOut()}>خروج</button>
        </div>
      </header>

      <section className="hero-panel">
        <div><span className="eyebrow">نظرة سريعة</span><h1>شبكة المنزل</h1><p>{devices.length ? `تم العثور على ${devices.length} أجهزة محفوظة` : "بانتظار أول قراءة من الهاتف"}</p></div>
        <div className={`agent-state ${isRecentlySeen(agent?.last_seen_at ?? null) ? "active" : "waiting"}`}>
          <span className="pulse" /><div><strong>{isRecentlySeen(agent?.last_seen_at ?? null) ? "الهاتف يرفع قراءات" : "الهاتف لا يرفع الآن"}</strong><small>{relativeTime(agent?.last_seen_at ?? null)}</small></div>
        </div>
      </section>

      {notice && <div className={`notice ${notice.kind}`}>{notice.text}<button aria-label="إغلاق" onClick={() => setNotice(null)}>×</button></div>}

      <section className="system-note">
        <span className="system-note-icon">i</span>
        <div><strong>{internetControlAvailable ? "فصل وتشغيل الإنترنت جاهز" : "المراقبة تعمل، والتحكم ينتظر تطبيق الهاتف v0.4.0"}</strong><p>{internetControlAvailable ? "اترك تطبيق الهاتف مفتوحًا وزر التحكم من الموقع مُشغّلًا. تحديد السرعة سيُفعّل بعد تثبيت IP وكتابة سرعة الخط الكلية." : "ثبّت النسخة الجديدة، سجّل دخول الراوتر، ثم شغّل استقبال أوامر الموقع."}</p></div>
      </section>

      <section className="stats-grid">
        <article className="stat-card accent"><span>استهلاك الشهر</span><strong>{formatBytes(totalUsage)}</strong><small>من أول الشهر الميلادي</small></article>
        <article className="stat-card"><span>الأجهزة المعروفة</span><strong>{devices.length}</strong><small>{devices.filter((device) => device.is_online).length} ظهرت خلال آخر 3 دقائق</small></article>
        <article className="stat-card"><span>أوامر قيد التنفيذ</span><strong>{pendingCount}</strong><small>{internetControlAvailable ? "يستلمها الهاتف كل 10 ثوانٍ" : "تنتظر اتصال وكيل التحكم"}</small></article>
        <article className="stat-card"><span>آخر تحديث للوحة</span><strong className="small-value">{lastLoadedAt ? lastLoadedAt.toLocaleTimeString("ar-EG", { hour: "2-digit", minute: "2-digit" }) : "—"}</strong><small>تحديث تلقائي كل 30 ثانية</small></article>
      </section>

      <section className="section-heading">
        <div><span className="eyebrow">الأجهزة</span><h2>أجهزة المنزل</h2></div>
        <div className="toolbar">
          <button className="secondary-button" disabled={loading} onClick={() => void loadDashboard()}>تحديث اللوحة</button>
          <button className="secondary-button" disabled title="سيعمل بعد ربط أوامر تطبيق الهاتف">تحديث الأسماء من الراوتر</button>
        </div>
      </section>

      {loading ? <div className="loader" aria-label="جارٍ تحديث البيانات" /> : devices.length ? (
        <section className="devices-grid">
          {devices.map((device) => (
            <DeviceCard key={device.id} device={device} busy={busyId === device.id} controlAvailable={internetControlAvailable} onQueueInternet={queueInternet} onSaveLimits={saveLimits} onSaveSchedule={saveSchedule} />
          ))}
        </section>
      ) : (
        <section className="empty-state">
          <div className="empty-icon">⌁</div><h3>الهاتف لم يرفع الأجهزة بعد</h3>
          <p>قاعدة البيانات واللوحة جاهزتان. بعد تثبيت نسخة الربط القادمة، ستظهر هنا الأسماء والاستهلاك الحقيقي تلقائيًا.</p>
        </section>
      )}

      <section className="queue-panel">
        <div className="section-heading"><div><span className="eyebrow">سجل التحكم</span><h2>أوامر الراوتر</h2><p className="muted">النجاح هنا يعني أن الهاتف حفظ التغيير فعلًا داخل الراوتر.</p></div></div>
        {commands.length ? <div className="command-list">{commands.map((command) => (
          <div className="command-row" key={command.id}>
            <span className={`command-dot ${command.status}`} />
            <strong>{actionLabels[command.action] ?? command.action}</strong>
            <span>{command.status === "pending" ? "في الانتظار" : command.status === "processing" ? "جارٍ التنفيذ" : command.status === "succeeded" ? "تم" : command.status === "failed" ? "فشل" : "ملغي"}</span>
            <small>{new Date(command.created_at).toLocaleString("ar-EG")}</small>
          </div>
        ))}</div> : <p className="muted">لا توجد أوامر حتى الآن.</p>}
      </section>

      <footer><span>HomeNet Agent</span><span>البيانات مشفّرة والصلاحيات معزولة لكل حساب</span></footer>
    </main>
  );
}
