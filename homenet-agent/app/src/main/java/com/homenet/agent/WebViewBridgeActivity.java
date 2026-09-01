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
    private final String routerBase = "http://192.168.0.1";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(10), dp(10), dp(10), dp(10));
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        TextView title = new TextView(this);
        title.setText("HomeNet Agent v0.1.4");
        title.setTextSize(22);
        root.addView(title);

        TextView help = new TextView(this);
        help.setText("سجّل الدخول من واجهة TP-Link الأصلية. زر Statistics يبحث داخل جلسة الراوتر عن رابط الإحصائيات الحقيقي ويفتحه كما تفعل القائمة الأصلية، بدون تركيب URL يدويًا.");
        help.setTextSize(14);
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
                if (url != null && url.contains("SystemStatisticRpm")) extractStats();
            }
        });
        root.addView(web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        output = new TextView(this);
        output.setText("ستظهر نتيجة القراءة هنا.");
        output.setTextSize(12);
        output.setTextIsSelectable(true);
        root.addView(output, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(170)));

        openRouter.setOnClickListener(v -> web.loadUrl(routerBase));
        readStats.setOnClickListener(v -> findAndOpenStatistics());
        setContentView(root);
        web.loadUrl(routerBase);
    }

    private void findAndOpenStatistics() {
        status.setText("أبحث عن رابط Statistics الحقيقي داخل جلسة الراوتر…");
        String js = "(function(){" +
                "var docs=[document];" +
                "try{for(var i=0;i<window.frames.length;i++){try{docs.push(window.frames[i].document);}catch(e){}}}catch(e){}" +
                "for(var d=0;d<docs.length;d++){var as=docs[d].getElementsByTagName('a');" +
                "for(var j=0;j<as.length;j++){var h=as[j].href||'';var t=(as[j].innerText||'').toLowerCase();" +
                "if(h.indexOf('SystemStatisticRpm')>=0 || t.indexOf('statistics')>=0){return h;}}}" +
                "return '';})()";
        web.evaluateJavascript(js, value -> {
            String href = decodeJs(value);
            if (href != null && href.startsWith("http")) {
                status.setText("وجدت رابط Statistics من واجهة الراوتر؛ جاري فتحه…");
                web.loadUrl(href);
            } else {
                output.setText("لم أجد رابط Statistics في الصفحة الحالية. افتح من قائمة الراوتر System Tools ثم Statistics يدويًا مرة واحدة داخل التطبيق؛ عند فتحها سيقرأ التطبيق الجدول تلقائيًا.\n\nالصفحة الحالية: " + web.getUrl());
            }
        });
    }

    private void extractStats() {
        String js = "(function(){var rows=[];var trs=document.querySelectorAll('tr');for(var i=0;i<trs.length;i++){var c=trs[i].querySelectorAll('td,th');var a=[];for(var j=0;j<c.length;j++){var t=(c[j].innerText||c[j].textContent||'').trim();if(t)a.push(t);}if(a.length)rows.push(a.join(' | '));}return rows.join('\\n');})()";
        web.evaluateJavascript(js, value -> {
            String v = decodeJs(value);
            if (v == null || v.trim().isEmpty()) output.setText("صفحة Statistics فُتحت، لكن لم يظهر جدول قابل للقراءة بعد.");
            else output.setText(v);
        });
    }

    private String decodeJs(String value) {
        if (value == null || "null".equals(value)) return "";
        String v=value;
        if(v.length()>1 && v.startsWith("\"") && v.endsWith("\"")) v=v.substring(1,v.length()-1);
        return v.replace("\\n","\n").replace("\\\"","\"").replace("\\/","/").replace("\\\\","\\");
    }

    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    @Override public void onBackPressed(){if(web!=null&&web.canGoBack())web.goBack();else super.onBackPressed();}
}
