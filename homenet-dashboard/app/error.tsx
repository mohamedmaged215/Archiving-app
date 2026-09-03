"use client";

export default function ErrorPage({ reset }: { error: Error & { digest?: string }; reset: () => void }) {
  return (
    <main className="center-shell" dir="rtl">
      <section className="panel narrow-panel">
        <span className="eyebrow">HomeNet</span>
        <h1>حصل خطأ غير متوقع</h1>
        <p className="muted">بياناتك محفوظة. جرّب تحميل الصفحة مرة أخرى.</p>
        <button className="primary-button" onClick={reset}>إعادة المحاولة</button>
      </section>
    </main>
  );
}

