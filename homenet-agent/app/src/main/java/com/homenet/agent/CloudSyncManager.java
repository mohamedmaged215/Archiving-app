package com.homenet.agent;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class CloudSyncManager {
    private static final String SUPABASE_URL = "https://gzkjladvhhntmzigyorv.supabase.co";
    private static final String PUBLISHABLE_KEY = "sb_publishable_0x-wR8lObcOtpswFQHhlbw_ok0YYm-D";
    private static final String PREFS = "homenet_cloud_session";
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 25_000;
    private static final int SYNC_BATCH_SIZE = 200;

    interface ResultCallback {
        void onResult(boolean success, String message);
    }

    private final SharedPreferences preferences;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private String accessToken;
    private String refreshToken;
    private String userId;

    CloudSyncManager(Context context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        accessToken = preferences.getString("access_token", "");
        refreshToken = preferences.getString("refresh_token", "");
        userId = preferences.getString("user_id", "");
    }

    boolean hasSession() {
        return accessToken != null && !accessToken.isEmpty() && refreshToken != null && !refreshToken.isEmpty();
    }

    void login(String email, String password, ResultCallback callback) {
        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("email", email.trim());
                body.put("password", password);
                HttpResult response = requestRaw(
                        "POST",
                        SUPABASE_URL + "/auth/v1/token?grant_type=password",
                        body.toString(),
                        null,
                        null
                );
                if (!response.isSuccess()) {
                    post(callback, false, readableError(response, "فشل تسجيل الدخول"));
                    return;
                }
                saveAuthResponse(new JSONObject(response.body));
                post(callback, true, "تم ربط الهاتف بحساب HomeNet بنجاح.");
            } catch (Exception error) {
                post(callback, false, "تعذر الاتصال بالسحابة: " + safeMessage(error));
            }
        });
    }

    void logout(ResultCallback callback) {
        accessToken = "";
        refreshToken = "";
        userId = "";
        preferences.edit().clear().apply();
        post(callback, true, "تم فصل الحساب السحابي من هذا الهاتف.");
    }

    void sync(HomeNetDatabase database, ResultCallback callback) {
        if (!hasSession()) {
            post(callback, false, "سجّل دخول حساب HomeNet أولًا.");
            return;
        }
        executor.execute(() -> {
            try {
                String homeId = ensureHome();
                ensureAgentHeartbeat(homeId);
                List<HomeNetDatabase.TrafficSnapshot> snapshots = database.getUnsyncedSnapshots(SYNC_BATCH_SIZE);
                int uploaded = 0;
                for (HomeNetDatabase.TrafficSnapshot snapshot : snapshots) {
                    String deviceId = ensureDevice(homeId, snapshot);
                    uploadUsage(homeId, deviceId, snapshot);
                    database.markSnapshotSynced(snapshot.id);
                    uploaded++;
                }
                int pending = database.countUnsyncedSnapshots();
                String message = uploaded == 0
                        ? "كل القراءات مرفوعة بالفعل. لا توجد بيانات مؤجلة."
                        : "تم رفع " + uploaded + " قراءة" + (pending > 0 ? "، ويتبقى " + pending + " للدفعة التالية." : " بنجاح.");
                post(callback, true, message);
            } catch (Exception error) {
                int pending = database.countUnsyncedSnapshots();
                post(callback, false, "تعذر الرفع الآن؛ تم الاحتفاظ بـ " + pending + " قراءة على الهاتف. " + safeMessage(error));
            }
        });
    }

    private String ensureHome() throws Exception {
        HttpResult lookup = authorizedRequest("GET", "/rest/v1/homenet_homes?select=id&limit=1", null, null);
        requireSuccess(lookup, "تعذر معرفة شبكة المنزل");
        JSONArray homes = new JSONArray(lookup.body);
        if (homes.length() > 0) return homes.getJSONObject(0).getString("id");
        if (userId == null || userId.isEmpty()) throw new IllegalStateException("جلسة المستخدم غير مكتملة");

        JSONObject body = new JSONObject();
        body.put("owner_id", userId);
        HttpResult created = authorizedRequest("POST", "/rest/v1/homenet_homes", body.toString(), "return=representation");
        requireSuccess(created, "تعذر إنشاء شبكة المنزل");
        return new JSONArray(created.body).getJSONObject(0).getString("id");
    }

    private void ensureAgentHeartbeat(String homeId) throws Exception {
        String agentId = preferences.getString("agent_id", "");
        JSONObject values = new JSONObject();
        values.put("home_id", homeId);
        values.put("name", "هاتف المراقبة");
        values.put("app_version", "0.3.0");
        values.put("last_seen_at", isoTimestamp(System.currentTimeMillis()));

        if (agentId == null || agentId.isEmpty()) {
            HttpResult created = authorizedRequest("POST", "/rest/v1/homenet_agents", values.toString(), "return=representation");
            requireSuccess(created, "تعذر تسجيل هاتف المراقبة");
            agentId = new JSONArray(created.body).getJSONObject(0).getString("id");
            preferences.edit().putString("agent_id", agentId).apply();
        } else {
            HttpResult updated = authorizedRequest("PATCH", "/rest/v1/homenet_agents?id=eq." + encode(agentId), values.toString(), "return=minimal");
            if (!updated.isSuccess()) {
                preferences.edit().remove("agent_id").apply();
                throw new IllegalStateException(readableError(updated, "تعذر تحديث حالة الهاتف"));
            }
        }
    }

    private String ensureDevice(String homeId, HomeNetDatabase.TrafficSnapshot snapshot) throws Exception {
        String addressPath = "/rest/v1/homenet_device_addresses?home_id=eq." + encode(homeId) +
                "&mac=eq." + encode(snapshot.mac) + "&select=device_id&limit=1";
        HttpResult addressLookup = authorizedRequest("GET", addressPath, null, null);
        requireSuccess(addressLookup, "تعذر مطابقة عنوان الجهاز");
        JSONArray addresses = new JSONArray(addressLookup.body);
        String deviceId = addresses.length() > 0 ? addresses.getJSONObject(0).getString("device_id") : "";

        String deviceName = snapshot.deviceName == null ? "" : snapshot.deviceName.trim();
        if (deviceId.isEmpty() && !deviceName.isEmpty()) {
            String namePath = "/rest/v1/homenet_devices?home_id=eq." + encode(homeId) +
                    "&router_name=eq." + encode(deviceName) + "&select=id&limit=1";
            HttpResult nameLookup = authorizedRequest("GET", namePath, null, null);
            requireSuccess(nameLookup, "تعذر مطابقة اسم الجهاز");
            JSONArray namedDevices = new JSONArray(nameLookup.body);
            if (namedDevices.length() > 0) deviceId = namedDevices.getJSONObject(0).getString("id");
        }

        JSONObject deviceValues = new JSONObject();
        deviceValues.put("home_id", homeId);
        if (!deviceName.isEmpty()) deviceValues.put("router_name", deviceName);
        deviceValues.put("current_ip", snapshot.ip);
        deviceValues.put("is_online", true);
        deviceValues.put("last_seen_at", isoTimestamp(snapshot.capturedAt));

        if (deviceId.isEmpty()) {
            HttpResult created = authorizedRequest("POST", "/rest/v1/homenet_devices", deviceValues.toString(), "return=representation");
            requireSuccess(created, "تعذر إنشاء الجهاز في السحابة");
            deviceId = new JSONArray(created.body).getJSONObject(0).getString("id");
        } else {
            HttpResult updated = authorizedRequest("PATCH", "/rest/v1/homenet_devices?id=eq." + encode(deviceId), deviceValues.toString(), "return=minimal");
            requireSuccess(updated, "تعذر تحديث الجهاز في السحابة");
        }

        JSONObject addressValues = new JSONObject();
        addressValues.put("home_id", homeId);
        addressValues.put("device_id", deviceId);
        addressValues.put("mac", snapshot.mac);
        addressValues.put("last_seen_at", isoTimestamp(snapshot.capturedAt));
        HttpResult addressUpsert = authorizedRequest(
                "POST",
                "/rest/v1/homenet_device_addresses?on_conflict=home_id,mac",
                addressValues.toString(),
                "resolution=merge-duplicates,return=minimal"
        );
        requireSuccess(addressUpsert, "تعذر حفظ عنوان الجهاز");
        return deviceId;
    }

    private void uploadUsage(String homeId, String deviceId, HomeNetDatabase.TrafficSnapshot snapshot) throws Exception {
        JSONObject values = new JSONObject();
        values.put("home_id", homeId);
        values.put("device_id", deviceId);
        values.put("captured_at", isoTimestamp(snapshot.capturedAt));
        values.put("router_bytes_total", snapshot.bytesTotal);
        values.put("delta_bytes", snapshot.deltaBytes);
        values.put("current_bytes_per_second", snapshot.bytesCurrent);
        values.put("counter_reset", snapshot.counterReset);
        HttpResult uploaded = authorizedRequest(
                "POST",
                "/rest/v1/homenet_usage_samples?on_conflict=device_id,captured_at",
                values.toString(),
                "resolution=ignore-duplicates,return=minimal"
        );
        requireSuccess(uploaded, "تعذر رفع قراءة الاستهلاك");
    }

    private HttpResult authorizedRequest(String method, String path, String body, String prefer) throws Exception {
        HttpResult response = requestRaw(method, SUPABASE_URL + path, body, accessToken, prefer);
        if (response.status == HttpURLConnection.HTTP_UNAUTHORIZED && refreshSession()) {
            response = requestRaw(method, SUPABASE_URL + path, body, accessToken, prefer);
        }
        return response;
    }

    private boolean refreshSession() {
        if (refreshToken == null || refreshToken.isEmpty()) return false;
        try {
            JSONObject body = new JSONObject();
            body.put("refresh_token", refreshToken);
            HttpResult response = requestRaw(
                    "POST",
                    SUPABASE_URL + "/auth/v1/token?grant_type=refresh_token",
                    body.toString(),
                    null,
                    null
            );
            if (!response.isSuccess()) return false;
            saveAuthResponse(new JSONObject(response.body));
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void saveAuthResponse(JSONObject response) throws Exception {
        accessToken = response.getString("access_token");
        refreshToken = response.getString("refresh_token");
        JSONObject user = response.optJSONObject("user");
        if (user != null) userId = user.optString("id", userId);
        preferences.edit()
                .putString("access_token", accessToken)
                .putString("refresh_token", refreshToken)
                .putString("user_id", userId)
                .apply();
    }

    private HttpResult requestRaw(String method, String urlValue, String body, String bearer, String prefer) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(urlValue).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestProperty("apikey", PUBLISHABLE_KEY);
        connection.setRequestProperty("Accept", "application/json");
        if (bearer != null && !bearer.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + bearer);
        if (prefer != null && !prefer.isEmpty()) connection.setRequestProperty("Prefer", prefer);
        if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream stream = connection.getOutputStream()) {
                stream.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        int status = connection.getResponseCode();
        InputStream stream = status >= 200 && status < 400 ? connection.getInputStream() : connection.getErrorStream();
        String responseBody = readStream(stream);
        connection.disconnect();
        return new HttpResult(status, responseBody);
    }

    private String readStream(InputStream stream) throws Exception {
        if (stream == null) return "";
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) result.append(line);
        }
        return result.toString();
    }

    private void requireSuccess(HttpResult response, String prefix) {
        if (!response.isSuccess()) throw new IllegalStateException(readableError(response, prefix));
    }

    private String readableError(HttpResult response, String prefix) {
        try {
            JSONObject error = new JSONObject(response.body);
            String detail = error.optString("msg", error.optString("message", error.optString("error_description", "")));
            return prefix + (detail.isEmpty() ? " (HTTP " + response.status + ")" : ": " + detail);
        } catch (Exception ignored) {
            return prefix + " (HTTP " + response.status + ")";
        }
    }

    private String encode(String value) throws Exception {
        return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
    }

    private String isoTimestamp(long timestamp) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(timestamp));
    }

    private String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? error.getClass().getSimpleName() : message;
    }

    private void post(ResultCallback callback, boolean success, String message) {
        mainHandler.post(() -> callback.onResult(success, message));
    }

    void close() {
        executor.shutdownNow();
    }

    private static final class HttpResult {
        final int status;
        final String body;

        HttpResult(int status, String body) {
            this.status = status;
            this.body = body;
        }

        boolean isSuccess() {
            return status >= 200 && status < 300;
        }
    }
}
