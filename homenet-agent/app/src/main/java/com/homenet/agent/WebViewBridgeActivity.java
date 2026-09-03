package com.homenet.agent;

import android.app.Activity;
import android.os.Bundle;
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
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class WebViewBridgeActivity extends Activity {
    private WebView web;
    private TextView status;
    private TextView output;
    private final String routerBase = "http://192.168.0.1";
    private final List<DeviceSnapshot> latestSnapshots = new ArrayList<>();
    private HomeNetDatabase database;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        database = new HomeNetDatabase(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(10), dp(10), dp(10), dp(10));
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        TextView title = new TextView(this);
        title.setText("HomeNet Agent v0.1.9");
        title.setTextSize(22);
        root.addView(title);

        TextView help = new TextView(this);
        help.setText("افتح System Tools > Statistics ثم اضغط قراءة الأجهزة. كل قراءة تُحفظ على الهاتف، ويُحسب الاستهلاك من فرق Total Bytes عن القراءة السابقة.");
        help.setTextSize(14);
        root.addView(help);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        Button openRouter = new Button(this);
        openRouter.setText("فتح الراوتر");
        buttons.addView(openRouter, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button readDevices = new Button(this);
        readDevices.setText("قراءة الأجهزة");
        buttons.addView(readDevices, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(buttons);

        Button diagnostics = new Button(this);
        diagnostics.setText("تشخيص الصفحة والـ frames");
        root.addView(diagnostics, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

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
        readDevices.setOnClickListener(v -> readDevices());
        diagnostics.setOnClickListener(v -> readCurrentPage());
        setContentView(root);
        web.loadUrl(routerBase);
    }

    private void readDevices() {
        status.setText("جاري استخراج الأجهزة من جدول Statistics…");
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
                    HomeNetDatabase.SaveResult saved = database.saveSnapshot(
                            snapshot.ip,
                            snapshot.mac,
                            snapshot.timestamp,
                            snapshot.packetsTotal,
                            snapshot.bytesTotal,
                            snapshot.packetsCurrent,
                            snapshot.bytesCurrent
                    );
                    result.append(i + 1).append(") ").append(snapshot.ip).append("\n")
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
                        : "تم حفظ اللقطة محليًا وحساب الفرق عن القراءة السابقة.");
            } catch (Exception e) {
                output.setText("فشل تحليل نتيجة Statistics:\n" + e.getMessage() + "\n\nRaw:\n" + decodeJs(value));
                status.setText("حدث خطأ أثناء تحليل الجدول.");
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
    @Override protected void onDestroy(){if(database!=null)database.close();super.onDestroy();}
    @Override public void onBackPressed(){if(web!=null&&web.canGoBack())web.goBack();else super.onBackPressed();}
}
