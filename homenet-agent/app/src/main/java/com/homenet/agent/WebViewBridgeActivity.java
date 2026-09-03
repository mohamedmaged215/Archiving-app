package com.homenet.agent;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class WebViewBridgeActivity extends Activity {
    private WebView web;
    private TextView status;
    private TextView output;
    private final String routerBase = "http://192.168.0.1";
    private static final long AUTO_CAPTURE_INTERVAL_MS = 60_000L;
    private final List<DeviceSnapshot> latestSnapshots = new ArrayList<>();
    private HomeNetDatabase database;
    private Handler autoCaptureHandler;
    private Button autoCaptureButton;
    private boolean autoCaptureEnabled;
    private boolean readInProgress;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        database = new HomeNetDatabase(this);
        autoCaptureHandler = new Handler(Looper.getMainLooper());
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(10), dp(10), dp(10), dp(10));
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        TextView title = new TextView(this);
        title.setText("HomeNet Agent v0.2.3");
        title.setTextSize(22);
        root.addView(title);

        TextView help = new TextView(this);
        help.setText("سجّل الدخول مرة، ثم حدّث أسماء الأجهزة من DHCP Clients. تُحفظ الأسماء وتظهر مع قراءات Statistics.");
        help.setTextSize(14);
        root.addView(help);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        Button openRouter = new Button(this);
        openRouter.setText("فتح الراوتر");
        buttons.addView(openRouter, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button openStatistics = new Button(this);
        openStatistics.setText("فتح Statistics آليًا");
        buttons.addView(openStatistics, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(buttons);

        LinearLayout secondaryButtons = new LinearLayout(this);
        secondaryButtons.setOrientation(LinearLayout.HORIZONTAL);
        Button readDevices = new Button(this);
        readDevices.setText("قراءة الأجهزة");
        secondaryButtons.addView(readDevices, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        autoCaptureButton = new Button(this);
        autoCaptureButton.setText("تشغيل القراءة التلقائية");
        secondaryButtons.addView(autoCaptureButton, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(secondaryButtons);

        Button readDeviceNames = new Button(this);
        readDeviceNames.setText("تحديث أسماء الأجهزة من DHCP");
        root.addView(readDeviceNames, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button usageSummary = new Button(this);
        usageSummary.setText("ملخص الاستهلاك");
        root.addView(usageSummary, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        status = new TextView(this);
        status.setText("افتح الراوتر وسجّل الدخول أولاً.");
        status.setTextSize(13);
        root.addView(status);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowContentAccess(true);
        s.setAllowFileAccess(false);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                status.setText("الصفحة الحالية: " + url);
            }
        });
        root.addView(web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        output = new TextView(this);
        output.setText("ستظهر نتيجة القراءة هنا.");
        output.setTextSize(12);
        output.setTextIsSelectable(true);
        output.setMovementMethod(new ScrollingMovementMethod());
        root.addView(output, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(210)));

        openRouter.setOnClickListener(v -> web.loadUrl(routerBase));
        openStatistics.setOnClickListener(v -> clickStatisticsMenu(null));
        readDevices.setOnClickListener(v -> readDevices(false));
        readDeviceNames.setOnClickListener(v -> openDhcpClientsAndReadNames(0));
        usageSummary.setOnClickListener(v -> showUsageSummary());
        autoCaptureButton.setOnClickListener(v -> toggleAutoCapture());
        setContentView(root);
        web.loadUrl(routerBase);
    }

    private void showUsageSummary() {
        Calendar startOfToday = Calendar.getInstance();
        startOfToday.set(Calendar.HOUR_OF_DAY, 0);
        startOfToday.set(Calendar.MINUTE, 0);
        startOfToday.set(Calendar.SECOND, 0);
        startOfToday.set(Calendar.MILLISECOND, 0);
        long sevenDaysAgo = System.currentTimeMillis() - (7L * 24L * 60L * 60L * 1000L);

        try {
            List<HomeNetDatabase.UsageSummary> summaries = database.getUsageSummaries(
                    startOfToday.getTimeInMillis(),
                    sevenDaysAgo
            );
            StringBuilder result = new StringBuilder("ملخص الاستهلاك المحفوظ\n\n");
            for (int i = 0; i < summaries.size(); i++) {
                HomeNetDatabase.UsageSummary summary = summaries.get(i);
                result.append(i + 1).append(") ")
                        .append(displayName(summary.deviceName)).append("\n")
                        .append("IP: ").append(summary.latestIp).append("\n")
                        .append("MAC: ").append(summary.mac).append("\n")
                        .append("اليوم: ").append(formatBytes(summary.todayBytes)).append("\n")
                        .append("آخر 7 أيام: ").append(formatBytes(summary.sevenDayBytes)).append("\n")
                        .append("منذ بداية التسجيل: ").append(formatBytes(summary.totalBytes)).append("\n")
                        .append("عدد اللقطات: ").append(summary.snapshotCount).append("\n")
                        .append("آخر لقطة: ").append(formatTimestamp(summary.lastCapturedAt)).append("\n\n");
            }
            if (summaries.isEmpty()) result.append("لا توجد قراءات محفوظة بعد.");
            output.setText(result.toString().trim());
            status.setText("تم حساب الملخص من قاعدة البيانات المحلية.");
        } catch (Exception e) {
            output.setText("فشل قراءة ملخص الاستهلاك:\n" + e.getMessage());
            status.setText("حدث خطأ أثناء قراءة التاريخ المحفوظ.");
        }
    }

    private void toggleAutoCapture() {
        autoCaptureEnabled = !autoCaptureEnabled;
        autoCaptureHandler.removeCallbacksAndMessages(null);
        if (autoCaptureEnabled) {
            autoCaptureButton.setText("إيقاف القراءة التلقائية");
            status.setText("جاري فتح Statistics ثم بدء القراءة التلقائية…");
            clickStatisticsMenu(() -> readDevices(true));
        } else {
            autoCaptureButton.setText("تشغيل القراءة التلقائية");
            status.setText("تم إيقاف القراءة التلقائية.");
        }
    }

    private void clickStatisticsMenu(Runnable afterOpen) {
        status.setText("أبحث عن عنصر Statistics الأصلي داخل الـ frames…");
        String js = "(function(){" +
                "function findAndClick(w){try{" +
                "var links=w.document.getElementsByTagName('a');" +
                "for(var i=0;i<links.length;i++){" +
                "var text=(links[i].innerText||links[i].textContent||'').toLowerCase().replace(/[^a-z]/g,'');" +
                "if(text==='statistics'){links[i].click();return true;}" +
                "}" +
                "for(var j=0;j<w.frames.length;j++){if(findAndClick(w.frames[j]))return true;}" +
                "}catch(e){}return false;}" +
                "return findAndClick(window);" +
                "})()";
        web.evaluateJavascript(js, value -> {
            boolean found = "true".equalsIgnoreCase(decodeJs(value));
            if (found) {
                status.setText("تم الضغط على Statistics من القائمة الأصلية.");
                if (afterOpen != null && autoCaptureEnabled) autoCaptureHandler.postDelayed(afterOpen, 2_500L);
            } else {
                status.setText("لم أجد Statistics. تأكد من تسجيل الدخول وظهور قائمة الراوتر.");
                if (autoCaptureEnabled) {
                    autoCaptureEnabled = false;
                    autoCaptureButton.setText("تشغيل القراءة التلقائية");
                }
            }
        });
    }

    private void openDhcpClientsAndReadNames(int attempt) {
        status.setText(attempt == 0
                ? "جاري فتح DHCP Clients من قائمة الراوتر…"
                : "جاري البحث عن DHCP Clients بعد فتح قائمة DHCP…");
        String js = "(function(){" +
                "function norm(v){return String(v||'').toLowerCase().replace(/[^a-z0-9]/g,'');}" +
                "function clickText(w,target){try{" +
                "var links=w.document.getElementsByTagName('a');" +
                "for(var i=0;i<links.length;i++){if(norm(links[i].innerText||links[i].textContent)===target){links[i].click();return true;}}" +
                "for(var j=0;j<w.frames.length;j++){if(clickText(w.frames[j],target))return true;}" +
                "}catch(e){}return false;}" +
                "if(clickText(window,'dhcpclients'))return 'target';" +
                "if(clickText(window,'dhcp'))return 'parent';" +
                "return 'none';" +
                "})()";
        web.evaluateJavascript(js, value -> {
            String result = decodeJs(value);
            if ("target".equals(result)) {
                status.setText("تم فتح DHCP Clients. جاري استخراج الأسماء…");
                autoCaptureHandler.postDelayed(this::readDeviceNames, 2_500L);
            } else if ("parent".equals(result) && attempt < 3) {
                autoCaptureHandler.postDelayed(() -> openDhcpClientsAndReadNames(attempt + 1), 1_000L);
            } else {
                status.setText("لم أجد DHCP Clients. تأكد من تسجيل الدخول وظهور قائمة DHCP.");
            }
        });
    }

    private void readDeviceNames() {
        status.setText("جاري قراءة أسماء الأجهزة من جدول DHCP Clients…");
        String js = "(function(){" +
                "var out=[],seen={};" +
                "function clean(v){return String(v||'').replace(/\\s+/g,' ').trim();}" +
                "function scan(w){try{" +
                "var rows=w.document.querySelectorAll('tr');" +
                "for(var r=0;r<rows.length;r++){" +
                "var cells=rows[r].querySelectorAll('td,th'),vals=[];" +
                "for(var c=0;c<cells.length;c++)vals.push(clean(cells[c].innerText||cells[c].textContent));" +
                "var joined=vals.join(' | ');" +
                "var ips=joined.match(/(?:\\d{1,3}\\.){3}\\d{1,3}/g)||[];" +
                "var macs=joined.match(/(?:[0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}/g)||[];" +
                "if(ips.length!==1||macs.length!==1)continue;var ipm=[ips[0]],macm=[macs[0]];" +
                "var name='';" +
                "for(var i=0;i<vals.length;i++){var v=vals[i];" +
                "if(!v||v.indexOf(ipm[0])>=0||v.toUpperCase().indexOf(macm[0].toUpperCase())>=0)continue;" +
                "if(/^\\d+$/.test(v)||/^\\d{1,3}:\\d{1,2}:\\d{1,2}$/.test(v))continue;" +
                "if(/client|address|lease|assigned|refresh|id/i.test(v))continue;" +
                "name=v;break;}" +
                "var mac=macm[0].replace(/-/g,':').toUpperCase();" +
                "if(!seen[mac]){seen[mac]=true;out.push({ip:ipm[0],mac:mac,name:name});}" +
                "}" +
                "for(var j=0;j<w.frames.length;j++)scan(w.frames[j]);" +
                "}catch(e){}}" +
                "scan(window);return JSON.stringify(out);" +
                "})()";
        web.evaluateJavascript(js, value -> {
            try {
                JSONArray devices = new JSONArray(decodeJs(value));
                long capturedAt = System.currentTimeMillis();
                StringBuilder result = new StringBuilder("أسماء الأجهزة من DHCP Clients\n\n");
                int namedCount = 0;
                for (int i = 0; i < devices.length(); i++) {
                    JSONObject device = devices.getJSONObject(i);
                    String name = device.optString("name", "").trim();
                    String ip = device.getString("ip");
                    String mac = device.getString("mac");
                    database.saveDeviceIdentity(ip, mac, name, capturedAt);
                    if (!name.isEmpty()) namedCount++;
                    result.append(i + 1).append(") ").append(displayName(name)).append("\n")
                            .append("IP: ").append(ip).append("\n")
                            .append("MAC: ").append(mac).append("\n\n");
                }
                if (devices.length() == 0) result.append("لم أجد أجهزة في جدول DHCP Clients.");
                output.setText(result.toString().trim());
                status.setText("تم حفظ " + devices.length() + " جهاز، منها " + namedCount + " باسم ظاهر من الراوتر.");
            } catch (Exception e) {
                output.setText("فشل تحليل DHCP Clients:\n" + e.getMessage() + "\n\nRaw:\n" + decodeJs(value));
                status.setText("حدث خطأ أثناء قراءة أسماء الأجهزة.");
            }
        });
    }

    private void scheduleNextAutomaticCapture(long delayMs) {
        autoCaptureHandler.removeCallbacksAndMessages(null);
        if (autoCaptureEnabled) {
            autoCaptureHandler.postDelayed(() -> readDevices(true), delayMs);
        }
    }

    private void readDevices(boolean automatic) {
        if (readInProgress) {
            if (automatic) scheduleNextAutomaticCapture(5_000L);
            return;
        }
        readInProgress = true;
        status.setText(automatic
                ? "جاري حفظ لقطة تلقائية من Statistics…"
                : "جاري استخراج الأجهزة من جدول Statistics…");
        String js = "(function(){" +
                "var devices=[],seen={};" +
                "function scan(w){try{" +
                "var d=w.document;var trs=d.querySelectorAll('tr');" +
                "for(var i=0;i<trs.length;i++){" +
                "var row=(trs[i].innerText||trs[i].textContent||'').replace(/\\s+/g,' ').trim();" +
                "var ipm=row.match(/(?:\\d{1,3}\\.){3}\\d{1,3}/);" +
                "var macm=row.match(/(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}/);" +
                "if(!ipm||!macm)continue;" +
                "var after=row.substring(row.indexOf(macm[0])+macm[0].length);" +
                "var nums=after.match(/\\d[\\d,]*/g)||[];if(nums.length<4)continue;" +
                "var key=ipm[0]+'|'+macm[0].toUpperCase();if(seen[key])continue;seen[key]=true;" +
                "devices.push({ip:ipm[0],mac:macm[0].toUpperCase(),packets_total:nums[0],bytes_total:nums[1],packets_current:nums[2],bytes_current:nums[3]});" +
                "}" +
                "for(var j=0;j<w.frames.length;j++)scan(w.frames[j]);" +
                "}catch(e){}}" +
                "scan(window);return JSON.stringify(devices);" +
                "})()";
        web.evaluateJavascript(js, value -> {
            try {
                JSONArray devices = new JSONArray(decodeJs(value));
                latestSnapshots.clear();
                long capturedAt = System.currentTimeMillis();
                StringBuilder result = new StringBuilder();
                result.append("تم العثور على ").append(devices.length()).append(" جهاز\n");
                result.append("وقت اللقطة: ").append(formatTimestamp(capturedAt)).append("\n\n");
                for (int i = 0; i < devices.length(); i++) {
                    JSONObject device = devices.getJSONObject(i);
                    DeviceSnapshot snapshot = new DeviceSnapshot(
                            device.getString("ip"),
                            device.getString("mac"),
                            parseCounter(device.getString("packets_total")),
                            parseCounter(device.getString("bytes_total")),
                            parseCounter(device.getString("packets_current")),
                            parseCounter(device.getString("bytes_current")),
                            capturedAt
                    );
                    latestSnapshots.add(snapshot);
                    String deviceName = database.getDeviceName(snapshot.mac);
                    HomeNetDatabase.SaveResult saved = database.saveSnapshot(
                            snapshot.ip,
                            snapshot.mac,
                            snapshot.timestamp,
                            snapshot.packetsTotal,
                            snapshot.bytesTotal,
                            snapshot.packetsCurrent,
                            snapshot.bytesCurrent
                    );
                    result.append(i + 1).append(") ").append(displayName(deviceName)).append("\n")
                            .append("IP: ").append(snapshot.ip).append("\n")
                            .append("MAC: ").append(snapshot.mac).append("\n")
                            .append("Total Bytes: ").append(String.format(Locale.US, "%,d", snapshot.bytesTotal))
                            .append(" (").append(formatBytes(snapshot.bytesTotal)).append(")\n")
                            .append(usageSincePrevious(saved)).append("\n")
                            .append("Current Bytes: ").append(String.format(Locale.US, "%,d", snapshot.bytesCurrent)).append("/s\n")
                            .append("Packets: ").append(String.format(Locale.US, "%,d", snapshot.packetsTotal))
                            .append(" total | ").append(String.format(Locale.US, "%,d", snapshot.packetsCurrent)).append(" current\n\n");
                }
                output.setText(result.toString().trim());
                status.setText(devices.length() == 0
                        ? "لم أجد صفوف أجهزة. افتح Statistics أولًا ثم أعد القراءة."
                        : "تم حفظ اللقطة محليًا وحساب الفرق عن القراءة السابقة." +
                                (automatic ? " القراءة التالية خلال 60 ثانية." : ""));
            } catch (Exception e) {
                output.setText("فشل تحليل نتيجة Statistics:\n" + e.getMessage() + "\n\nRaw:\n" + decodeJs(value));
                status.setText("حدث خطأ أثناء تحليل الجدول.");
            } finally {
                readInProgress = false;
                if (automatic) scheduleNextAutomaticCapture(AUTO_CAPTURE_INTERVAL_MS);
            }
        });
    }

    private void readCurrentPage() {
        status.setText("جاري قراءة الصفحة الحالية وكل الـ frames بدون أي تنقّل…");
        String js = "(function(){" +
                "var out=[];" +
                "function clean(v){return String(v==null?'':v).replace(/\\r/g,'').trim();}" +
                "function dump(w,name){" +
                "try{" +
                "var d=w.document;" +
                "out.push('--- '+name+' ---');" +
                "try{out.push('URL: '+d.location.href);}catch(e){out.push('URL: [unavailable]');}" +
                "var fs=d.querySelectorAll('frame,iframe');" +
                "out.push('FRAMES: '+fs.length);" +
                "for(var i=0;i<fs.length;i++){var f=fs[i];out.push('[frame '+i+'] tag='+(f.tagName||'')+' name='+(f.name||'')+' id='+(f.id||'')+' src='+(f.getAttribute('src')||'')+' resolved='+(f.src||''));}" +
                "var rows=[];var trs=d.querySelectorAll('tr');" +
                "for(var r=0;r<trs.length;r++){var cs=trs[r].querySelectorAll('td,th');var cells=[];for(var c=0;c<cs.length;c++){var t=clean(cs[c].innerText||cs[c].textContent);if(t)cells.push(t);}if(cells.length)rows.push(cells.join(' | '));}" +
                "if(rows.length){out.push('TABLE ROWS:');out.push(rows.join('\\n'));}" +
                "var body=clean((d.body&&(d.body.innerText||d.body.textContent))||(d.documentElement&&(d.documentElement.innerText||d.documentElement.textContent))||'');" +
                "if(body){out.push('PAGE TEXT:');out.push(body);}" +
                "for(var j=0;j<w.frames.length;j++){dump(w.frames[j],name+'.frame'+j);}" +
                "}catch(e){out.push('--- '+name+' ---');out.push('ERROR: '+e);}" +
                "}" +
                "dump(window,'main');return out.join('\\n');" +
                "})()";
        web.evaluateJavascript(js, value -> {
            String v = decodeJs(value);
            if (v == null || v.trim().isEmpty()) {
                output.setText("لم يعد JavaScript أي نص من الصفحة الحالية.");
                status.setText("انتهت القراءة بدون بيانات.");
            } else {
                output.setText(v);
                status.setText("تمت قراءة الصفحة والـ frames. انسخ النتيجة أو أرسل صورة واضحة.");
            }
        });
    }

    private String decodeJs(String value) {
        if (value == null || "null".equals(value)) return "";
        try {
            Object decoded = new JSONTokener(value).nextValue();
            return decoded == null ? "" : String.valueOf(decoded);
        } catch (Exception ignored) {
            return value;
        }
    }

    private long parseCounter(String value) {
        return Long.parseLong(value.replace(",", "").trim());
    }

    private String formatBytes(long bytes) {
        if (bytes >= 1024L * 1024L * 1024L) return String.format(Locale.US, "%.3f GB", bytes / (1024.0 * 1024.0 * 1024.0));
        if (bytes >= 1024L * 1024L) return String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0));
        if (bytes >= 1024L) return String.format(Locale.US, "%.2f KB", bytes / 1024.0);
        return bytes + " B";
    }

    private String formatTimestamp(long timestamp) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(timestamp));
    }

    private String displayName(String name) {
        return name == null || name.trim().isEmpty() ? "جهاز بدون اسم" : name.trim();
    }

    private String usageSincePrevious(HomeNetDatabase.SaveResult saved) {
        if (saved.firstSnapshot) return "Usage Delta: أول قراءة محفوظة";
        if (saved.counterReset) return "Usage Delta: 0 B (بداية جلسة جديدة بعد تصفير العداد)";
        return "Usage Delta: " + String.format(Locale.US, "%,d", saved.deltaBytes) +
                " bytes (" + formatBytes(saved.deltaBytes) + ")";
    }

    private static class DeviceSnapshot {
        final String ip;
        final String mac;
        final long packetsTotal;
        final long bytesTotal;
        final long packetsCurrent;
        final long bytesCurrent;
        final long timestamp;

        DeviceSnapshot(String ip, String mac, long packetsTotal, long bytesTotal,
                       long packetsCurrent, long bytesCurrent, long timestamp) {
            this.ip = ip;
            this.mac = mac;
            this.packetsTotal = packetsTotal;
            this.bytesTotal = bytesTotal;
            this.packetsCurrent = packetsCurrent;
            this.bytesCurrent = bytesCurrent;
            this.timestamp = timestamp;
        }
    }

    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    @Override protected void onDestroy(){
        autoCaptureEnabled=false;
        if(autoCaptureHandler!=null)autoCaptureHandler.removeCallbacksAndMessages(null);
        if(database!=null)database.close();
        super.onDestroy();
    }
    @Override public void onBackPressed(){if(web!=null&&web.canGoBack())web.goBack();else super.onBackPressed();}
}
