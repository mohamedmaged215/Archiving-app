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

import org.json.JSONTokener;

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
        title.setText("HomeNet Agent v0.1.5");
        title.setTextSize(22);
        root.addView(title);

        TextView help = new TextView(this);
        help.setText("سجّل الدخول من واجهة TP-Link، ثم افتح System Tools > Statistics يدويًا داخلها. زر القراءة لا يغيّر الرابط؛ يقرأ الصفحة الحالية وكل الـ frames.");
        help.setTextSize(14);
        root.addView(help);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        Button openRouter = new Button(this);
        openRouter.setText("فتح الراوتر");
        buttons.addView(openRouter, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button readCurrent = new Button(this);
        readCurrent.setText("اقرأ الصفحة الحالية");
        buttons.addView(readCurrent, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
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
            }
        });
        root.addView(web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        output = new TextView(this);
        output.setText("ستظهر نتيجة القراءة هنا.");
        output.setTextSize(12);
        output.setTextIsSelectable(true);
        root.addView(output, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(170)));

        openRouter.setOnClickListener(v -> web.loadUrl(routerBase));
        readCurrent.setOnClickListener(v -> readCurrentPage());
        setContentView(root);
        web.loadUrl(routerBase);
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

    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    @Override public void onBackPressed(){if(web!=null&&web.canGoBack())web.goBack();else super.onBackPressed();}
}
