package com.homenet.agent;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
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
    private Handler commandHandler;
    private Button autoCaptureButton;
    private Button remoteControlButton;
    private boolean autoCaptureEnabled;
    private boolean remoteControlEnabled;
    private boolean readInProgress;
    private boolean cloudSyncInProgress;
    private boolean commandInProgress;
    private CloudSyncManager cloudSync;
    private EditText cloudEmail;
    private EditText cloudPassword;
    private Button cloudLoginButton;
    private Button cloudSyncButton;
    private TextView cloudState;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        database = new HomeNetDatabase(this);
        cloudSync = new CloudSyncManager(this);
        autoCaptureHandler = new Handler(Looper.getMainLooper());
        commandHandler = new Handler(Looper.getMainLooper());
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(10), dp(10), dp(10), dp(10));
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        TextView title = new TextView(this);
        title.setText("HomeNet Agent v0.4.0");
        title.setTextSize(22);
        root.addView(title);

        TextView help = new TextView(this);
        help.setText("اربط حساب HomeNet وسجّل دخول الراوتر. اترك التطبيق مفتوحًا لتسجيل الاستهلاك وتنفيذ أوامر الفصل الآمنة القادمة من الموقع.");
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

        remoteControlButton = new Button(this);
        remoteControlButton.setText("تشغيل التحكم من الموقع");
        root.addView(remoteControlButton, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        cloudEmail = new EditText(this);
        cloudEmail.setHint("بريد حساب HomeNet");
        cloudEmail.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        cloudEmail.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        root.addView(cloudEmail, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        cloudPassword = new EditText(this);
        cloudPassword.setHint("كلمة مرور HomeNet");
        cloudPassword.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        cloudPassword.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        root.addView(cloudPassword, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (cloudSync.hasSession()) {
            cloudEmail.setVisibility(View.GONE);
            cloudPassword.setVisibility(View.GONE);
        }

        LinearLayout cloudButtons = new LinearLayout(this);
        cloudButtons.setOrientation(LinearLayout.HORIZONTAL);
        cloudLoginButton = new Button(this);
        cloudLoginButton.setText(cloudSync.hasSession() ? "فصل الحساب" : "ربط الحساب");
        cloudButtons.addView(cloudLoginButton, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        cloudSyncButton = new Button(this);
        cloudSyncButton.setText("مزامنة السحابة");
        cloudButtons.addView(cloudSyncButton, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(cloudButtons);

        cloudState = new TextView(this);
        cloudState.setText(cloudSync.hasSession()
                ? "الحساب مربوط. سيتم رفع أي قراءات مؤجلة تلقائيًا."
                : "أنشئ حسابًا من homenet-control.vercel.app ثم اربطه هنا.");
        cloudState.setTextSize(12);
        root.addView(cloudState);

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
        remoteControlButton.setOnClickListener(v -> toggleRemoteControl());
        autoCaptureButton.setOnClickListener(v -> toggleAutoCapture());
        cloudLoginButton.setOnClickListener(v -> handleCloudLogin());
        cloudSyncButton.setOnClickListener(v -> syncCloud(true));
        setContentView(root);
        web.loadUrl(routerBase);
        if (cloudSync.hasSession()) syncCloud(false);
    }

    private void handleCloudLogin() {
        if (cloudSync.hasSession()) {
            cloudSync.logout((success, message) -> {
                cloudLoginButton.setText("ربط الحساب");
                cloudEmail.setVisibility(View.VISIBLE);
                cloudPassword.setVisibility(View.VISIBLE);
                cloudState.setText(message);
            });
            return;
        }
        String email = cloudEmail.getText().toString().trim();
        String password = cloudPassword.getText().toString();
        if (email.isEmpty() || password.isEmpty()) {
            cloudState.setText("اكتب بريد وكلمة مرور حساب HomeNet.");
            return;
        }
        cloudLoginButton.setEnabled(false);
        cloudState.setText("جاري ربط الهاتف بالحساب…");
        cloudSync.login(email, password, (success, message) -> {
            cloudLoginButton.setEnabled(true);
            cloudState.setText(message);
            if (success) {
                cloudLoginButton.setText("فصل الحساب");
                cloudPassword.setText("");
                cloudEmail.setVisibility(View.GONE);
                cloudPassword.setVisibility(View.GONE);
                syncCloud(false);
            }
        });
    }

    private void syncCloud(boolean announce) {
        if (cloudSyncInProgress) return;
        cloudSyncInProgress = true;
        cloudSyncButton.setEnabled(false);
        if (announce) cloudState.setText("جاري رفع القراءات المحفوظة…");
        cloudSync.sync(database, (success, message) -> {
            cloudSyncInProgress = false;
            cloudSyncButton.setEnabled(true);
            cloudState.setText(message);
        });
    }

    private void toggleRemoteControl() {
        if (!cloudSync.hasSession()) {
            cloudState.setText("اربط حساب HomeNet أولًا ثم شغّل التحكم.");
            return;
        }
        remoteControlEnabled = !remoteControlEnabled;
        commandHandler.removeCallbacksAndMessages(null);
        if (remoteControlEnabled) {
            remoteControlButton.setText("إيقاف التحكم من الموقع");
            cloudState.setText("التحكم يعمل. اترك التطبيق مفتوحًا ومسجّلًا داخل الراوتر.");
            pollCommands();
        } else {
            remoteControlButton.setText("تشغيل التحكم من الموقع");
            cloudState.setText("تم إيقاف استقبال أوامر الموقع.");
        }
    }

    private void scheduleCommandPoll(long delayMs) {
        commandHandler.removeCallbacksAndMessages(null);
        if (remoteControlEnabled) commandHandler.postDelayed(this::pollCommands, delayMs);
    }

    private void pollCommands() {
        if (!remoteControlEnabled || commandInProgress) return;
        commandInProgress = true;
        cloudSync.claimNextCommand((command, message) -> {
            if (!remoteControlEnabled) {
                commandInProgress = false;
                return;
            }
            if (command == null) {
                commandInProgress = false;
                if (message != null && message.startsWith("تعذر")) cloudState.setText(message);
                scheduleCommandPoll(10_000L);
                return;
            }
            cloudState.setText("وصل أمر " + command.action + " للجهاز " + displayName(command.deviceName) + ".");
            executeRouterCommand(command);
        });
    }

    private void executeRouterCommand(CloudSyncManager.RouterCommand command) {
        if (!"set_internet".equals(command.action)) {
            finishRouterCommand(command, false, "هذا الإصدار ينفذ فصل وتشغيل الإنترنت فقط.");
            return;
        }
        if (command.mac == null || command.mac.trim().isEmpty()) {
            finishRouterCommand(command, false, "لا يوجد MAC صالح للجهاز.");
            return;
        }
        boolean internetEnabled = command.payload.optBoolean("enabled", true);
        String compactMac = command.mac.replace(":", "").replace("-", "").toUpperCase(Locale.US);
        String hostName = "HN_" + compactMac.substring(Math.max(0, compactMac.length() - 6));
        String compactDeviceId = command.deviceId.replace("-", "");
        String ruleName = "HN_BLOCK_" + compactDeviceId.substring(0, Math.min(8, compactDeviceId.length()));

        status.setText("جاري تجهيز Access Control بالسياسة الآمنة…");
        openAccessControlPage("rule", () -> configureSafeAccessPolicy(ok -> {
            if (!ok) {
                finishRouterCommand(command, false, "تعذر ضبط سياسة Access Control الآمنة. تأكد من تسجيل دخول الراوتر.");
                return;
            }
            status.setText("جاري تعريف الجهاز داخل Access Control…");
            openAccessControlPage("host", () -> ensureAccessHost(hostName, command.mac, hostOk -> {
                if (!hostOk) {
                    finishRouterCommand(command, false, "تعذر إنشاء تعريف MAC للجهاز على الراوتر.");
                    return;
                }
                status.setText(internetEnabled ? "جاري تشغيل الإنترنت للجهاز…" : "جاري فصل الإنترنت عن الجهاز…");
                openAccessControlPage("rule", () -> setBlockingRule(
                        ruleName,
                        hostName,
                        !internetEnabled,
                        ruleOk -> finishRouterCommand(
                                command,
                                ruleOk,
                                ruleOk ? null : "تعذر حفظ قاعدة الجهاز على الراوتر."
                        )
                ));
            }));
        }));
    }

    private void finishRouterCommand(CloudSyncManager.RouterCommand command, boolean success, String error) {
        cloudSync.finishCommand(command, success, error, (saved, message) -> {
            commandInProgress = false;
            cloudState.setText(message);
            status.setText(success ? "اكتمل أمر الراوتر بنجاح." : error);
            scheduleCommandPoll(4_000L);
        });
    }

    private interface BooleanStep { void done(boolean success); }

    private void openAccessControlPage(String child, Runnable afterOpen) {
        String parentScript = clickRouterLinkScript("accesscontrol");
        web.evaluateJavascript(parentScript, ignored -> commandHandler.postDelayed(() -> {
            web.evaluateJavascript(clickRouterLinkScript(child), value -> {
                boolean clicked = "true".equalsIgnoreCase(decodeJs(value));
                if (!clicked) {
                    status.setText("لم أجد قائمة Access Control. سجّل دخول الراوتر ثم أعد المحاولة.");
                    afterOpen.run();
                    return;
                }
                commandHandler.postDelayed(afterOpen, 650L);
            });
        }, 300L));
    }

    private String clickRouterLinkScript(String normalizedTarget) {
        return "(function(){" +
                "function n(v){return String(v||'').toLowerCase().replace(/[^a-z0-9]/g,'');}" +
                "function scan(w){try{var a=w.document.getElementsByTagName('a');" +
                "for(var i=0;i<a.length;i++){if(n(a[i].innerText||a[i].textContent)==='" + normalizedTarget + "'){a[i].click();return true;}}" +
                "for(var j=0;j<w.frames.length;j++){if(scan(w.frames[j]))return true;}}catch(e){}return false;}" +
                "return scan(window);})()";
    }

    private void configureSafeAccessPolicy(BooleanStep callback) {
        String js = "(function(){function scan(w){try{var d=w.document,e=d.getElementById('enableFw');" +
                "if(e){var allow=d.getElementById('act_en'),deny=d.getElementById('act_dis');" +
                "if(allow)allow.checked=true;if(deny)deny.checked=false;e.checked=true;" +
                "var b=d.querySelectorAll('input,button');for(var i=0;i<b.length;i++){" +
                "if(String(b[i].value||b[i].innerText||'').trim().toLowerCase()==='save'){b[i].click();return true;}}return false;}" +
                "for(var j=0;j<w.frames.length;j++){var r=scan(w.frames[j]);if(r)return r;}}catch(x){}return false;}" +
                "return scan(window);})()";
        web.evaluateJavascript(js, value -> commandHandler.postDelayed(
                () -> callback.done("true".equalsIgnoreCase(decodeJs(value))),
                650L
        ));
    }

    private void ensureAccessHost(String hostName, String mac, BooleanStep callback) {
        String inspect = "(function(){var mac=" + JSONObject.quote(mac.toUpperCase(Locale.US)) + ";" +
                "function scan(w){try{var d=w.document,body=String(d.body?d.body.innerText:'').toUpperCase();" +
                "if(d.getElementById('mode')&&d.getElementById('entryName'))return 'form';" +
                "if(body.indexOf('HOST SETTINGS')>=0){if(body.indexOf(mac)>=0)return 'exists';" +
                "var b=d.querySelectorAll('input,button');for(var i=0;i<b.length;i++){var t=String(b[i].value||b[i].innerText||'').toLowerCase();" +
                "if(t.indexOf('add new')>=0){b[i].click();return 'opening';}}}" +
                "for(var j=0;j<w.frames.length;j++){var r=scan(w.frames[j]);if(r!=='none')return r;}}catch(e){}return 'none';}" +
                "return scan(window);})()";
        web.evaluateJavascript(inspect, value -> {
            String state = decodeJs(value);
            if ("exists".equals(state)) {
                callback.done(true);
            } else if ("opening".equals(state) || "form".equals(state)) {
                commandHandler.postDelayed(() -> fillAndSaveHost(hostName, mac, callback), 400L);
            } else callback.done(false);
        });
    }

    private void fillAndSaveHost(String hostName, String mac, BooleanStep callback) {
        String setMode = "(function(){function pick(s,label){for(var i=0;i<s.options.length;i++){if(String(s.options[i].text).trim().toLowerCase()===label){s.selectedIndex=i;if(s.onchange)s.onchange();return true;}}return false;}" +
                "function scan(w){try{var m=w.document.getElementById('mode');if(m)return pick(m,'mac address');for(var j=0;j<w.frames.length;j++){if(scan(w.frames[j]))return true;}}catch(e){}return false;}return scan(window);})()";
        web.evaluateJavascript(setMode, ignored -> commandHandler.postDelayed(() -> {
            String fill = "(function(){var name=" + JSONObject.quote(hostName) + ",mac=" + JSONObject.quote(mac.toUpperCase(Locale.US)) + ";" +
                    "function scan(w){try{var d=w.document,n=d.getElementById('entryName'),m=d.getElementById('macAddr');if(n&&m){n.value=name;m.value=mac;" +
                    "var b=d.querySelectorAll('input,button');for(var i=0;i<b.length;i++){if(String(b[i].value||b[i].innerText||'').trim().toLowerCase()==='save'){b[i].click();return true;}}}" +
                    "for(var j=0;j<w.frames.length;j++){if(scan(w.frames[j]))return true;}}catch(e){}return false;}return scan(window);})()";
            web.evaluateJavascript(fill, result -> commandHandler.postDelayed(
                    () -> callback.done("true".equalsIgnoreCase(decodeJs(result))),
                    650L
            ));
        }, 250L));
    }

    private void setBlockingRule(String ruleName, String hostName, boolean blocked, BooleanStep callback) {
        String inspect = "(function(){var rule=" + JSONObject.quote(ruleName) + ";function scan(w){try{var d=w.document,rows=d.querySelectorAll('tr');" +
                "for(var i=0;i<rows.length;i++){if(String(rows[i].innerText||rows[i].textContent||'').indexOf(rule)>=0){var a=rows[i].getElementsByTagName('a');" +
                "for(var k=0;k<a.length;k++){if(String(a[k].innerText||a[k].textContent||'').toLowerCase().indexOf('edit')>=0){a[k].click();return 'edit';}}}}" +
                "var body=String(d.body?d.body.innerText:'').toLowerCase();if(body.indexOf('access control rule management')>=0){" +
                "if(!" + blocked + ")return 'allowed';var b=d.querySelectorAll('input,button');for(var q=0;q<b.length;q++){if(String(b[q].value||b[q].innerText||'').toLowerCase().indexOf('add new')>=0){b[q].click();return 'add';}}}" +
                "for(var j=0;j<w.frames.length;j++){var r=scan(w.frames[j]);if(r!=='none')return r;}}catch(e){}return 'none';}return scan(window);})()";
        web.evaluateJavascript(inspect, value -> {
            String state = decodeJs(value);
            if ("allowed".equals(state)) {
                callback.done(true);
                return;
            }
            if (!"edit".equals(state) && !"add".equals(state)) {
                callback.done(false);
                return;
            }
            commandHandler.postDelayed(() -> fillAndSaveRule(ruleName, hostName, blocked, callback), 450L);
        });
    }

    private void fillAndSaveRule(String ruleName, String hostName, boolean blocked, BooleanStep callback) {
        String js = "(function(){var rn=" + JSONObject.quote(ruleName) + ",hn=" + JSONObject.quote(hostName) + ";" +
                "function selectText(s,text){if(!s)return false;for(var i=0;i<s.options.length;i++){if(String(s.options[i].text).trim().toLowerCase()===String(text).toLowerCase()){s.selectedIndex=i;return true;}}return false;}" +
                "function scan(w){try{var d=w.document,n=d.getElementById('ruleName');if(n){n.value=rn;" +
                "selectText(d.getElementById('internalHostRef'),hn);selectText(d.getElementById('externalHostRef'),'Any Host');" +
                "selectText(d.getElementById('scheduleRef'),'Any Time');selectText(d.getElementById('action'),'Deny');" +
                "selectText(d.getElementById('enable'),'" + (blocked ? "Enabled" : "Disabled") + "');selectText(d.getElementById('direction'),'OUT');selectText(d.getElementById('protocol'),'ALL');" +
                "var b=d.querySelectorAll('input,button');for(var i=0;i<b.length;i++){if(String(b[i].value||b[i].innerText||'').trim().toLowerCase()==='save'){b[i].click();return true;}}}" +
                "for(var j=0;j<w.frames.length;j++){if(scan(w.frames[j]))return true;}}catch(e){}return false;}return scan(window);})()";
        web.evaluateJavascript(js, result -> commandHandler.postDelayed(
                () -> callback.done("true".equalsIgnoreCase(decodeJs(result))),
                700L
        ));
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
                "for(var i=0;i<links.length;i++){var n=norm(links[i].innerText||links[i].textContent);" +
                "if(n===target||(target==='dhcpclients'&&n.indexOf('dhcpclients')===0)){links[i].click();return true;}}" +
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
                if (cloudSync.hasSession()) syncCloud(false);
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
                if (devices.length() > 0 && cloudSync.hasSession()) syncCloud(false);
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
        remoteControlEnabled=false;
        if(autoCaptureHandler!=null)autoCaptureHandler.removeCallbacksAndMessages(null);
        if(commandHandler!=null)commandHandler.removeCallbacksAndMessages(null);
        if(cloudSync!=null)cloudSync.close();
        if(database!=null)database.close();
        super.onDestroy();
    }
    @Override public void onBackPressed(){if(web!=null&&web.canGoBack())web.goBack();else super.onBackPressed();}
}
