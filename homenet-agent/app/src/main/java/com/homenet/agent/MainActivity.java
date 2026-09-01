package com.homenet.agent;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private EditText routerField;
    private EditText userField;
    private EditText passField;
    private Button readButton;
    private TextView statusText;
    private LinearLayout devicesBox;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(22), dp(18), dp(28));
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("HomeNet Agent");
        title.setTextSize(26);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        title.setPadding(0, 0, 0, dp(6));
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("اختبار قراءة استهلاك الأجهزة من TP-Link WR840N");
        subtitle.setTextSize(16);
        subtitle.setGravity(Gravity.CENTER_HORIZONTAL);
        subtitle.setPadding(0, 0, 0, dp(20));
        root.addView(subtitle);

        routerField = field("عنوان الراوتر", "192.168.0.1");
        root.addView(routerField);

        userField = field("اسم مستخدم الراوتر", "admin");
        root.addView(userField);

        passField = field("كلمة مرور لوحة الراوتر", "");
        passField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(passField);

        readButton = new Button(this);
        readButton.setText("اختبار الاتصال وقراءة الأجهزة");
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        buttonParams.setMargins(0, dp(12), 0, dp(12));
        root.addView(readButton, buttonParams);

        statusText = new TextView(this);
        statusText.setText("جاهز للاختبار. يجب أن يكون الهاتف متصلًا بواي فاي البيت.");
        statusText.setTextSize(15);
        statusText.setPadding(dp(12), dp(10), dp(12), dp(16));
        root.addView(statusText);

        devicesBox = new LinearLayout(this);
        devicesBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(devicesBox);

        TextView note = new TextView(this);
        note.setText("هذه النسخة التجريبية تقرأ البيانات محليًا فقط. كلمة مرور الراوتر لا تُرسل إلى GitHub أو أي خدمة سحابية.");
        note.setTextSize(13);
        note.setPadding(dp(8), dp(22), dp(8), 0);
        root.addView(note);

        readButton.setOnClickListener(v -> runRead());
        setContentView(scroll);
    }

    private EditText field(String hint, String value) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setTextSize(16);
        e.setSingleLine(true);
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
        return e;
    }

    private void runRead() {
        final String router = routerField.getText().toString().trim();
        final String user = userField.getText().toString().trim();
        final String pass = passField.getText().toString();

        if (router.isEmpty() || user.isEmpty()) {
            statusText.setText("اكتب عنوان الراوتر واسم المستخدم.");
            return;
        }

        readButton.setEnabled(false);
        devicesBox.removeAllViews();
        statusText.setText("جاري الاتصال بالراوتر وقراءة صفحة Statistics…");

        executor.submit(() -> {
            try {
                RouterClient client = new RouterClient(router);
                RouterResult result = client.fetchStats(user, pass);
                mainHandler.post(() -> showResult(result));
            } catch (Exception ex) {
                String msg = ex.getMessage() == null ? ex.toString() : ex.getMessage();
                mainHandler.post(() -> {
                    statusText.setText("فشل الاختبار: " + msg);
                    readButton.setEnabled(true);
                });
            }
        });
    }

    private void showResult(RouterResult result) {
        readButton.setEnabled(true);
        devicesBox.removeAllViews();

        if (result.devices.isEmpty()) {
            statusText.setText("تم الوصول إلى الراوتر، لكن لم أستطع استخراج صفوف Statistics. تأكد أن Traffic Statistics = Enable ثم جرّب مرة أخرى.\nالمسار الذي تم اختباره: " + result.pathUsed);
            return;
        }

        statusText.setText("تم الاتصال بنجاح ✅  —  عدد الأجهزة في الإحصائيات: " + result.devices.size());

        for (DeviceStat d : result.devices) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(14), dp(12), dp(14), dp(12));
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cp.setMargins(0, dp(6), 0, dp(6));

            TextView ip = new TextView(this);
            ip.setText("IP: " + d.ip);
            ip.setTextSize(18);
            card.addView(ip);

            TextView mac = new TextView(this);
            mac.setText("MAC: " + d.mac);
            mac.setTextSize(14);
            card.addView(mac);

            TextView total = new TextView(this);
            total.setText("إجمالي البيانات منذ بدء العداد: " + humanBytes(d.totalBytes));
            total.setTextSize(16);
            total.setPadding(0, dp(6), 0, 0);
            card.addView(total);

            TextView current = new TextView(this);
            current.setText("آخر فترة إحصائية: " + humanBytes(d.currentBytes) + "   |   Packets: " + d.totalPackets);
            current.setTextSize(14);
            card.addView(current);

            card.setBackgroundColor(0xFFF3F6F8);
            devicesBox.addView(card, cp);
        }
    }

    private static String humanBytes(long value) {
        double b = Math.max(0, value);
        if (b >= 1_000_000_000d) return String.format(Locale.US, "%.3f GB", b / 1_000_000_000d);
        if (b >= 1_000_000d) return String.format(Locale.US, "%.2f MB", b / 1_000_000d);
        if (b >= 1_000d) return String.format(Locale.US, "%.2f KB", b / 1_000d);
        return ((long) b) + " B";
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    static final class RouterResult {
        final List<DeviceStat> devices;
        final String pathUsed;

        RouterResult(List<DeviceStat> devices, String pathUsed) {
            this.devices = devices;
            this.pathUsed = pathUsed;
        }
    }

    static final class DeviceStat {
        final String ip;
        final String mac;
        final long totalPackets;
        final long totalBytes;
        final long currentPackets;
        final long currentBytes;

        DeviceStat(String ip, String mac, long totalPackets, long totalBytes, long currentPackets, long currentBytes) {
            this.ip = ip;
            this.mac = mac;
            this.totalPackets = totalPackets;
            this.totalBytes = totalBytes;
            this.currentPackets = currentPackets;
            this.currentBytes = currentBytes;
        }
    }

    static final class RouterClient {
        private static final Pattern TOKEN_PATTERN = Pattern.compile("([A-Za-z0-9]{16})/userRpm/Index\\.htm");
        private static final Pattern STAT_ROW = Pattern.compile(
                "\\d+\\s*,\\s*\"([^\"]+)\"\\s*,\\s*\"([^\"]+)\"\\s*,\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*,\\s*(-?\\d+)",
                Pattern.MULTILINE);

        private final String base;

        RouterClient(String address) {
            String a = address.trim();
            if (a.startsWith("http://")) a = a.substring(7);
            if (a.startsWith("https://")) a = a.substring(8);
            while (a.endsWith("/")) a = a.substring(0, a.length() - 1);
            this.base = "http://" + a;
        }

        RouterResult fetchStats(String username, String password) throws Exception {
            Set<String> secretVariants = new LinkedHashSet<>();
            secretVariants.add(md5(password));
            secretVariants.add(password);

            Exception lastError = null;
            for (String secret : secretVariants) {
                String encoded = Base64.encodeToString(
                        (username + ":" + secret).getBytes(StandardCharsets.UTF_8),
                        Base64.NO_WRAP);

                String[] cookieVariants = new String[] {
                        "Authorization=Basic%20" + encoded + "; ChgPwdSubTag=",
                        "Authorization=" + encoded + "; ChgPwdSubTag="
                };

                for (String cookie : cookieVariants) {
                    try {
                        Response login = get(base + "/userRpm/LoginRpm.htm?Save=Save", cookie, base + "/");
                        String token = findToken(login.location + "\n" + login.body);

                        if (token != null) {
                            String path = "/" + token + "/userRpm/SystemStatisticRpm.htm?interval=10&sortType=1&Num_per_page=100&Goto_page=1";
                            Response stats = get(base + path, cookie, base + "/" + token + "/userRpm/Index.htm");
                            List<DeviceStat> devices = parseStats(stats.body);
                            if (!devices.isEmpty()) return new RouterResult(devices, path);
                        }

                        String directPath = "/userRpm/SystemStatisticRpm.htm?interval=10&sortType=1&Num_per_page=100&Goto_page=1";
                        Response direct = get(base + directPath, cookie, base + "/userRpm/Index.htm");
                        List<DeviceStat> devices = parseStats(direct.body);
                        if (!devices.isEmpty()) return new RouterResult(devices, directPath);

                        if (direct.code == 200 && direct.body.contains("SystemStatistic")) {
                            return new RouterResult(devices, directPath);
                        }
                    } catch (Exception ex) {
                        lastError = ex;
                    }
                }
            }

            if (lastError != null) throw lastError;
            throw new IllegalStateException("لم يقبل الراوتر بيانات الدخول أو لم يعرض صفحة Statistics بصيغة متوقعة.");
        }

        private static String findToken(String text) {
            Matcher m = TOKEN_PATTERN.matcher(text == null ? "" : text);
            return m.find() ? m.group(1) : null;
        }

        private static List<DeviceStat> parseStats(String body) {
            List<DeviceStat> out = new ArrayList<>();
            if (body == null) return out;
            Matcher m = STAT_ROW.matcher(body);
            while (m.find()) {
                String ip = m.group(1).trim();
                String mac = m.group(2).trim();
                long totalPackets = positiveLong(m.group(3));
                long totalBytes = positiveLong(m.group(4));
                long currentPackets = positiveLong(m.group(5));
                long currentBytes = positiveLong(m.group(6));
                if (ip.matches("\\d{1,3}(\\.\\d{1,3}){3}") && mac.contains(":")) {
                    out.add(new DeviceStat(ip, mac, totalPackets, totalBytes, currentPackets, currentBytes));
                }
            }
            return out;
        }

        private static long positiveLong(String s) {
            try {
                long v = Long.parseLong(s.trim());
                return Math.max(0, v);
            } catch (Exception e) {
                return 0;
            }
        }

        private static String md5(String text) throws Exception {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format(Locale.US, "%02x", b & 0xff));
            return sb.toString();
        }

        private Response get(String target, String cookie, String referer) throws Exception {
            HttpURLConnection c = (HttpURLConnection) new URL(target).openConnection();
            c.setConnectTimeout(4500);
            c.setReadTimeout(5500);
            c.setInstanceFollowRedirects(false);
            c.setRequestMethod("GET");
            c.setRequestProperty("Cookie", cookie);
            c.setRequestProperty("Referer", referer);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36");
            c.setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*");

            int code = c.getResponseCode();
            String location = c.getHeaderField("Location");
            InputStream stream = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String body = readAll(stream);
            c.disconnect();
            return new Response(code, body, location == null ? "" : location);
        }

        private static String readAll(InputStream in) throws Exception {
            if (in == null) return "";
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.ISO_8859_1))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line).append('\n');
            }
            return sb.toString();
        }

        static final class Response {
            final int code;
            final String body;
            final String location;

            Response(int code, String body, String location) {
                this.code = code;
                this.body = body;
                this.location = location;
            }
        }
    }
}
