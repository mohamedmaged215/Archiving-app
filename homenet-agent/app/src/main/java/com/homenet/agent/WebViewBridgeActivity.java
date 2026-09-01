package com.homenet.agent;

import android.app.Activity;
import android.os.Bundle;
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

public class WebViewBridgeActivity extends Activity {
    private WebView web;
    private TextView status;
    private TextView output;
    private String routerBase = "http://192.168.0.1";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(10), dp(10), dp(10), dp(10));
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        TextView title = new TextView(this);
        title.setText("HomeNet Agent v0.1.3");
        title.setTextSize(22);
        root.addView(title);

        TextView help = new TextView(this);
        help.setText("نسخة WebView: سجّل دخولك للراوتر من الصفحة الأصلية مرة واحدة، ثم اضغط قراءة Statistics. بهذه الطريقة نستخدم نفس الجلسة التي ينجح بها المتصفح بدل محاكاة تسجيل الدخول.");
        help.setTextSize(14);
        help.setPadding(0, dp(6), 0, dp(8));
        root.addView(help);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);

        Button openRouter = new Button(this);
        openRouter.setText("فتح الراوتر");
        buttons.addView(openRouter, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        Button readStats = new Button(this);
        readStats.setText("قراءة Statistics");
        buttons.addView(readStats, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        root.addView(buttons);

        status = new TextView(this);
        status.setText("متصل بواي فاي البيت ثم افتح الراوتر وسجّل الدخول كالمعتاد.");
        status.setTextSize(13);
        status.setPadding(0, dp(5), 0, dp(5));
        root.addView(status);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowContentAccess(true);
        s.setAllowFileAccess(false);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setUserAgentString("Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36");
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                status.setText("الصفحة الحالية: " + url);
                if (url != null && url.contains("SystemStatisticRpm")) {
                    extractStats();
                }
            }
        });

        root.addView(web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        output = new TextView(this);
        output.setText("ستظهر نتيجة القراءة هنا.");
        output.setTextSize(12);
        output.setTextIsSelectable(true);
        output.setPadding(0, dp(6), 0, 0);
        root.addView(output, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150)));

        openRouter.setOnClickListener(v -> web.loadUrl(routerBase));
        readStats.setOnClickListener(v -> openStatistics());

        setContentView(root);
        web.loadUrl(routerBase);
    }

    private void openStatistics() {
        String current = web.getUrl();
        if (current == null) current = routerBase;

        String prefix = routerBase;
        int rpm = current.indexOf("/userRpm/");
        if (rpm > 0) {
            prefix = current.substring(0, rpm);
        } else {
            int tokenRpm = current.indexOf("/userRpm");
            if (tokenRpm > 0) prefix = current.substring(0, tokenRpm);
        }

        String target = prefix + "/userRpm/SystemStatisticRpm.htm?interval=10&sortType=1&Num_per_page=100&Goto_page=1";
        status.setText("جاري فتح صفحة Statistics بنفس جلسة المتصفح…");
        web.loadUrl(target);
    }

    private void extractStats() {
        String js = "(function(){" +
                "var rows=[];" +
                "var trs=document.querySelectorAll('tr');" +
                "for(var i=0;i<trs.length;i++){" +
                " var c=trs[i].querySelectorAll('td,th'); var a=[];" +
                " for(var j=0;j<c.length;j++){var t=(c[j].innerText||c[j].textContent||'').trim(); if(t)a.push(t);}" +
                " if(a.length) rows.push(a.join(' | '));" +
                "}" +
                "return rows.join('\\n');" +
                "})()";
        web.evaluateJavascript(js, value -> {
            String v = value == null ? "" : value;
            if (v.length() > 1 && v.startsWith("\"") && v.endsWith("\"")) {
                v = v.substring(1, v.length() - 1)
                        .replace("\\n", "\n")
                        .replace("\\\"", "\"")
                        .replace("\\\\", "\\");
            }
            if (v.trim().isEmpty() || "null".equals(v)) {
                output.setText("فتحت صفحة Statistics لكن لم أستخرج صفوفًا بعد. صوّر الصفحة الحالية داخل التطبيق.");
            } else {
                output.setText(v);
            }
        });
    }

    private int dp(int n) {
        return Math.round(n * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack(); else super.onBackPressed();
    }
}
