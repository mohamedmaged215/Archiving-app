package com.homenet.agent;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class HomeNetBackgroundService extends Service {
    static final String ACTION_START = "com.homenet.agent.START_BACKGROUND";
    static final String ACTION_STOP = "com.homenet.agent.STOP_BACKGROUND";
    static final String ACTION_CAPTURE_NOW = "com.homenet.agent.CAPTURE_NOW";
    private static final String PREFS = "homenet_background_service";
    private static final String CHANNEL_ID = "homenet_agent_monitor";
    private static final int NOTIFICATION_ID = 840;
    private static final long CAPTURE_INTERVAL_MS = 60_000L;
    private static final long COMMAND_INTERVAL_MS = 12_000L;
    private static final long NAME_REFRESH_INTERVAL_MS = 15L * 60L * 1000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService routerExecutor = Executors.newSingleThreadExecutor();
    private HomeNetDatabase database;
    private CloudSyncManager cloudSync;
    private RouterCredentialsStore credentialsStore;
    private boolean captureRunning;
    private boolean commandRunning;
    private long lastNamesAt;
    private PowerManager.WakeLock serviceWakeLock;
    private WifiManager.WifiLock wifiLock;

    static void start(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled", true).apply();
        Intent intent = new Intent(context, HomeNetBackgroundService.class).setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent);
        else context.startService(intent);
    }

    static void captureNow(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled", true).apply();
        Intent intent = new Intent(context, HomeNetBackgroundService.class).setAction(ACTION_CAPTURE_NOW);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent);
        else context.startService(intent);
    }

    static void stop(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled", false).apply();
        context.stopService(new Intent(context, HomeNetBackgroundService.class));
    }

    static boolean isEnabled(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("enabled", false);
    }

    static String lastStatus(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString("last_status", "الخدمة لم تبدأ بعد.");
    }

    @Override
    public void onCreate() {
        super.onCreate();
        database = new HomeNetDatabase(getApplicationContext());
        cloudSync = new CloudSyncManager(getApplicationContext());
        credentialsStore = new RouterCredentialsStore(getApplicationContext());
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("enabled", false).apply();
            stopSelf();
            return START_NOT_STICKY;
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("enabled", true).apply();
        startForeground(NOTIFICATION_ID, notification("بدء مراقبة الراوتر…"));
        acquireContinuousLocks();
        scheduleCapture(intent != null && ACTION_CAPTURE_NOW.equals(intent.getAction()) ? 0 : 1_500L);
        scheduleCommandPoll(4_000L);
        return START_STICKY;
    }

    private void scheduleCapture(long delayMs) {
        handler.removeCallbacks(captureRunnable);
        handler.postDelayed(captureRunnable, delayMs);
    }

    private final Runnable captureRunnable = new Runnable() {
        @Override public void run() {
            if (!isEnabled(HomeNetBackgroundService.this)) return;
            if (captureRunning) {
                scheduleCapture(10_000L);
                return;
            }
            captureRunning = true;
            routerExecutor.execute(HomeNetBackgroundService.this::captureInBackground);
        }
    };

    private void captureInBackground() {
        PowerManager.WakeLock wakeLock = acquireShortWakeLock("capture");
        try {
            RouterCredentialsStore.Credentials credentials = credentialsStore.load();
            RouterRpcClient router = new RouterRpcClient(credentials);
            router.authenticate();
            long now = System.currentTimeMillis();
            if (lastNamesAt == 0 || now - lastNamesAt >= NAME_REFRESH_INTERVAL_MS) {
                saveDeviceNames(router.readDhcpClients(), now);
                lastNamesAt = now;
            }
            List<RouterRpcClient.TrafficStat> stats = router.readTrafficStats();
            for (RouterRpcClient.TrafficStat stat : stats) {
                database.saveSnapshot(stat.ip, stat.mac, now, stat.totalPackets, stat.totalBytes,
                        stat.currentPackets, stat.currentBytes);
            }
            updateStatus("تعمل الآن — آخر قراءة: " + stats.size() + " أجهزة", true);
            if (cloudSync.hasSession()) {
                cloudSync.sync(database, (success, message) -> {
                    if (!success) updateStatus("القراءات محفوظة؛ انتظار رجوع الإنترنت", false);
                });
            }
        } catch (RouterRpcClient.AuthenticationException error) {
            updateStatus("تحقق من بيانات دخول الراوتر داخل التطبيق", false);
        } catch (Exception error) {
            updateStatus("انتظار اتصال الهاتف براوتر المنزل…", false);
        } finally {
            release(wakeLock);
            captureRunning = false;
            scheduleCapture(CAPTURE_INTERVAL_MS);
        }
    }

    private void saveDeviceNames(List<RouterRpcClient.DeviceIdentity> devices, long capturedAt) {
        for (RouterRpcClient.DeviceIdentity device : devices) {
            database.saveDeviceIdentity(device.ip, device.mac, device.name, capturedAt);
        }
    }

    private void scheduleCommandPoll(long delayMs) {
        handler.removeCallbacks(commandRunnable);
        handler.postDelayed(commandRunnable, delayMs);
    }

    private final Runnable commandRunnable = new Runnable() {
        @Override public void run() {
            if (!isEnabled(HomeNetBackgroundService.this)) return;
            if (commandRunning || !cloudSync.hasSession() || !credentialsStore.hasCredentials()) {
                scheduleCommandPoll(COMMAND_INTERVAL_MS);
                return;
            }
            commandRunning = true;
            cloudSync.claimNextCommand((command, message) -> {
                if (command == null) {
                    commandRunning = false;
                    scheduleCommandPoll(COMMAND_INTERVAL_MS);
                    return;
                }
                routerExecutor.execute(() -> executeCommand(command));
            });
        }
    };

    private void executeCommand(CloudSyncManager.RouterCommand command) {
        PowerManager.WakeLock wakeLock = acquireShortWakeLock("command");
        try {
            if ("set_internet".equals(command.action)) {
                RouterRpcClient router = connectedRouter();
                router.setInternetAccess(command.mac, command.deviceId,
                        command.payload.optBoolean("enabled", true));
                completeCommand(command, true, null);
            } else if ("refresh_names".equals(command.action)) {
                RouterRpcClient router = connectedRouter();
                saveDeviceNames(router.readDhcpClients(), System.currentTimeMillis());
                lastNamesAt = System.currentTimeMillis();
                completeCommand(command, true, null);
            } else if ("sync_now".equals(command.action)) {
                scheduleCapture(0);
                completeCommand(command, true, null);
            } else if ("reset_usage".equals(command.action)) {
                RouterRpcClient router = connectedRouter();
                List<RouterRpcClient.TrafficStat> stats = router.readTrafficStats();
                long baselineAt = System.currentTimeMillis();
                database.resetUsageWithBaselines(stats, baselineAt);
                completeCommand(command, true, null);
            } else {
                completeCommand(command, false, "هذا النوع من الأوامر سيُضاف في إصدار لاحق.");
            }
        } catch (RouterRpcClient.AuthenticationException error) {
            completeCommand(command, false, error.getMessage());
            updateStatus("أمر متوقف: بيانات دخول الراوتر غير صحيحة", false);
        } catch (IOException error) {
            long retryDelay = Math.min(15L * 60L * 1000L, Math.max(60_000L, command.attempts * 60_000L));
            cloudSync.retryCommand(command, error.getMessage(), retryDelay, (success, message) -> {
                commandRunning = false;
                updateStatus("الأمر محفوظ وسيُعاد بعد رجوع الراوتر", false);
                scheduleCommandPoll(COMMAND_INTERVAL_MS);
            });
        } catch (Exception error) {
            completeCommand(command, false, safeMessage(error));
        } finally {
            release(wakeLock);
        }
    }

    private RouterRpcClient connectedRouter() throws Exception {
        RouterRpcClient router = new RouterRpcClient(credentialsStore.load());
        router.authenticate();
        return router;
    }

    private void completeCommand(CloudSyncManager.RouterCommand command, boolean success, String error) {
        cloudSync.finishCommand(command, success, error, (saved, message) -> {
            commandRunning = false;
            updateStatus(success ? "تم تنفيذ أمر الموقع بنجاح" : "تعذر تنفيذ أمر الموقع", success);
            scheduleCommandPoll(3_000L);
        });
    }

    private PowerManager.WakeLock acquireShortWakeLock(String name) {
        try {
            PowerManager manager = (PowerManager) getSystemService(POWER_SERVICE);
            PowerManager.WakeLock lock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    "HomeNetAgent:" + name);
            lock.setReferenceCounted(false);
            lock.acquire(120_000L);
            return lock;
        } catch (Exception ignored) {
            return null;
        }
    }

    private void release(PowerManager.WakeLock wakeLock) {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }

    @SuppressWarnings("deprecation")
    private void acquireContinuousLocks() {
        try {
            if (serviceWakeLock == null) {
                PowerManager manager = (PowerManager) getSystemService(POWER_SERVICE);
                serviceWakeLock = manager.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        "HomeNetAgent:continuous-monitor"
                );
                serviceWakeLock.setReferenceCounted(false);
            }
            if (!serviceWakeLock.isHeld()) serviceWakeLock.acquire();
        } catch (Exception ignored) {
            serviceWakeLock = null;
        }

        try {
            if (wifiLock == null) {
                WifiManager manager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                wifiLock = manager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                        "HomeNetAgent:router-wifi");
                wifiLock.setReferenceCounted(false);
            }
            if (!wifiLock.isHeld()) wifiLock.acquire();
        } catch (Exception ignored) {
            wifiLock = null;
        }
    }

    private void releaseContinuousLocks() {
        try {
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        } catch (Exception ignored) {
            // The operating system may already have released it.
        }
        try {
            if (serviceWakeLock != null && serviceWakeLock.isHeld()) serviceWakeLock.release();
        } catch (Exception ignored) {
            // The operating system may already have released it.
        }
    }

    private void updateStatus(String message, boolean healthy) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("last_status", message)
                .putLong("last_update", System.currentTimeMillis())
                .putBoolean("healthy", healthy)
                .apply();
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_ID, notification(message));
    }

    private Notification notification(String message) {
        Intent openIntent = new Intent(this, WebViewBridgeActivity.class);
        PendingIntent open = PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | immutableFlag());
        Intent stopIntent = new Intent(this, HomeNetBackgroundService.class).setAction(ACTION_STOP);
        PendingIntent stop = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? PendingIntent.getForegroundService(this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT | immutableFlag())
                : PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT | immutableFlag());

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        builder.setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("HomeNet يعمل في الخلفية")
                .setContentText(message)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_media_pause, "إيقاف", stop).build());
        return builder.build();
    }

    private int immutableFlag() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "مراقبة شبكة HomeNet",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("إشعار ضروري لاستمرار قراءة الراوتر وتنفيذ الأوامر في الخلفية.");
        channel.setShowBadge(false);
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        if (isEnabled(this)) scheduleSafetyRestart();
        super.onTaskRemoved(rootIntent);
    }

    private void scheduleSafetyRestart() {
        try {
            Intent restartIntent = new Intent(this, HomeNetBackgroundService.class).setAction(ACTION_START);
            PendingIntent restart = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? PendingIntent.getForegroundService(this, 8405, restartIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | immutableFlag())
                    : PendingIntent.getService(this, 8405, restartIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | immutableFlag());
            AlarmManager alarm = (AlarmManager) getSystemService(ALARM_SERVICE);
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 8_000L, restart);
        } catch (Exception ignored) {
            // START_STICKY remains the primary restart mechanism.
        }
    }

    private String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? error.getClass().getSimpleName() : message;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        routerExecutor.shutdownNow();
        if (cloudSync != null) cloudSync.close();
        if (database != null) database.close();
        releaseContinuousLocks();
        if (isEnabled(this)) scheduleSafetyRestart();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
