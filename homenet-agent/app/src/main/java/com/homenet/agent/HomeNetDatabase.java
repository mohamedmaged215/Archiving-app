package com.homenet.agent;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

final class HomeNetDatabase extends SQLiteOpenHelper {
    private static final String DATABASE_NAME = "homenet.db";
    private static final int DATABASE_VERSION = 2;

    HomeNetDatabase(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE traffic_snapshots (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "ip TEXT NOT NULL," +
                "mac TEXT NOT NULL," +
                "captured_at INTEGER NOT NULL," +
                "packets_total INTEGER NOT NULL," +
                "bytes_total INTEGER NOT NULL," +
                "packets_current INTEGER NOT NULL," +
                "bytes_current INTEGER NOT NULL," +
                "delta_bytes INTEGER NOT NULL," +
                "counter_reset INTEGER NOT NULL DEFAULT 0" +
                ")");
        db.execSQL("CREATE INDEX idx_traffic_snapshots_mac_time " +
                "ON traffic_snapshots(mac, captured_at DESC, id DESC)");
        createDeviceProfilesTable(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) createDeviceProfilesTable(db);
    }

    private void createDeviceProfilesTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS device_profiles (" +
                "mac TEXT PRIMARY KEY," +
                "device_name TEXT," +
                "last_ip TEXT," +
                "updated_at INTEGER NOT NULL" +
                ")");
    }

    SaveResult saveSnapshot(String ip, String mac, long capturedAt,
                            long packetsTotal, long bytesTotal,
                            long packetsCurrent, long bytesCurrent) {
        SQLiteDatabase db = getWritableDatabase();
        ensureDeviceProfile(db, mac, ip, capturedAt);
        long previousTotal = 0;
        boolean firstSnapshot = true;

        try (Cursor cursor = db.query(
                "traffic_snapshots",
                new String[]{"bytes_total"},
                "mac = ?",
                new String[]{mac},
                null,
                null,
                "captured_at DESC, id DESC",
                "1")) {
            if (cursor.moveToFirst()) {
                firstSnapshot = false;
                previousTotal = cursor.getLong(0);
            }
        }

        boolean counterReset = !firstSnapshot && bytesTotal < previousTotal;
        long deltaBytes = firstSnapshot || counterReset ? 0 : bytesTotal - previousTotal;

        ContentValues values = new ContentValues();
        values.put("ip", ip);
        values.put("mac", mac);
        values.put("captured_at", capturedAt);
        values.put("packets_total", packetsTotal);
        values.put("bytes_total", bytesTotal);
        values.put("packets_current", packetsCurrent);
        values.put("bytes_current", bytesCurrent);
        values.put("delta_bytes", deltaBytes);
        values.put("counter_reset", counterReset ? 1 : 0);
        db.insertOrThrow("traffic_snapshots", null, values);

        return new SaveResult(firstSnapshot, counterReset, previousTotal, deltaBytes);
    }

    private void ensureDeviceProfile(SQLiteDatabase db, String mac, String ip, long updatedAt) {
        ContentValues initial = new ContentValues();
        initial.put("mac", mac);
        initial.put("last_ip", ip);
        initial.put("updated_at", updatedAt);
        db.insertWithOnConflict("device_profiles", null, initial, SQLiteDatabase.CONFLICT_IGNORE);

        ContentValues latest = new ContentValues();
        latest.put("last_ip", ip);
        latest.put("updated_at", updatedAt);
        db.update("device_profiles", latest, "mac = ?", new String[]{mac});
    }

    void saveDeviceIdentity(String ip, String mac, String deviceName, long updatedAt) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("mac", mac);
        values.put("last_ip", ip);
        values.put("updated_at", updatedAt);
        if (deviceName != null && !deviceName.trim().isEmpty()) {
            values.put("device_name", deviceName.trim());
        }
        db.insertWithOnConflict("device_profiles", null, values, SQLiteDatabase.CONFLICT_IGNORE);
        db.update("device_profiles", values, "mac = ?", new String[]{mac});
    }

    String getDeviceName(String mac) {
        try (Cursor cursor = getReadableDatabase().query(
                "device_profiles",
                new String[]{"device_name"},
                "mac = ?",
                new String[]{mac},
                null,
                null,
                null,
                "1")) {
            if (cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getString(0);
        }
        return "";
    }

    List<UsageSummary> getUsageSummaries(long todayStartedAt, long sevenDaysAgo) {
        SQLiteDatabase db = getReadableDatabase();
        List<UsageSummary> summaries = new ArrayList<>();
        String sql = "SELECT s.mac," +
                "(SELECT latest.ip FROM traffic_snapshots latest WHERE latest.mac = s.mac " +
                "ORDER BY latest.captured_at DESC, latest.id DESC LIMIT 1) AS latest_ip," +
                "COALESCE((SELECT p.device_name FROM device_profiles p WHERE p.mac = s.mac), '') AS device_name," +
                "SUM(CASE WHEN s.captured_at >= ? THEN s.delta_bytes ELSE 0 END) AS today_usage," +
                "SUM(CASE WHEN s.captured_at >= ? THEN s.delta_bytes ELSE 0 END) AS seven_day_usage," +
                "SUM(s.delta_bytes) AS total_usage," +
                "COUNT(*) AS snapshot_count," +
                "MAX(s.captured_at) AS last_captured_at " +
                "FROM traffic_snapshots s GROUP BY s.mac ORDER BY total_usage DESC";

        try (Cursor cursor = db.rawQuery(sql, new String[]{
                String.valueOf(todayStartedAt),
                String.valueOf(sevenDaysAgo)
        })) {
            while (cursor.moveToNext()) {
                summaries.add(new UsageSummary(
                        cursor.getString(0),
                        cursor.getString(1),
                        cursor.getString(2),
                        cursor.getLong(3),
                        cursor.getLong(4),
                        cursor.getLong(5),
                        cursor.getLong(6),
                        cursor.getLong(7)
                ));
            }
        }
        return summaries;
    }

    static final class SaveResult {
        final boolean firstSnapshot;
        final boolean counterReset;
        final long previousTotal;
        final long deltaBytes;

        SaveResult(boolean firstSnapshot, boolean counterReset, long previousTotal, long deltaBytes) {
            this.firstSnapshot = firstSnapshot;
            this.counterReset = counterReset;
            this.previousTotal = previousTotal;
            this.deltaBytes = deltaBytes;
        }
    }

    static final class UsageSummary {
        final String mac;
        final String latestIp;
        final String deviceName;
        final long todayBytes;
        final long sevenDayBytes;
        final long totalBytes;
        final long snapshotCount;
        final long lastCapturedAt;

        UsageSummary(String mac, String latestIp, String deviceName, long todayBytes, long sevenDayBytes,
                     long totalBytes, long snapshotCount, long lastCapturedAt) {
            this.mac = mac;
            this.latestIp = latestIp;
            this.deviceName = deviceName;
            this.todayBytes = todayBytes;
            this.sevenDayBytes = sevenDayBytes;
            this.totalBytes = totalBytes;
            this.snapshotCount = snapshotCount;
            this.lastCapturedAt = lastCapturedAt;
        }
    }
}
