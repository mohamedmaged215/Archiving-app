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
    private static final int DATABASE_VERSION = 3;
    // The TL-WR840N is a 100 Mbps router. This deliberately generous ceiling
    // rejects parser/counter jumps without clipping legitimate traffic.
    private static final long MAX_PLAUSIBLE_BYTES_PER_SECOND = 25_000_000L;
    private static final long DELTA_CUSHION_BYTES = 5_000_000L;

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
                "counter_reset INTEGER NOT NULL DEFAULT 0," +
                "cloud_synced INTEGER NOT NULL DEFAULT 0" +
                ")");
        db.execSQL("CREATE INDEX idx_traffic_snapshots_mac_time " +
                "ON traffic_snapshots(mac, captured_at DESC, id DESC)");
        db.execSQL("CREATE INDEX idx_traffic_snapshots_cloud_pending " +
                "ON traffic_snapshots(cloud_synced, captured_at, id)");
        createDeviceProfilesTable(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) createDeviceProfilesTable(db);
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE traffic_snapshots ADD COLUMN cloud_synced INTEGER NOT NULL DEFAULT 0");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_traffic_snapshots_cloud_pending " +
                    "ON traffic_snapshots(cloud_synced, captured_at, id)");
        }
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
        long previousCapturedAt = capturedAt;
        boolean firstSnapshot = true;

        try (Cursor cursor = db.query(
                "traffic_snapshots",
                new String[]{"bytes_total", "captured_at"},
                "mac = ?",
                new String[]{mac},
                null,
                null,
                "captured_at DESC, id DESC",
                "1")) {
            if (cursor.moveToFirst()) {
                firstSnapshot = false;
                previousTotal = cursor.getLong(0);
                previousCapturedAt = cursor.getLong(1);
            }
        }

        boolean counterReset = !firstSnapshot && bytesTotal < previousTotal;
        long rawDelta = firstSnapshot || counterReset ? 0 : bytesTotal - previousTotal;
        long elapsedSeconds = Math.max(1L, (capturedAt - previousCapturedAt) / 1000L);
        long maximumPlausibleDelta = elapsedSeconds > (Long.MAX_VALUE - DELTA_CUSHION_BYTES)
                / MAX_PLAUSIBLE_BYTES_PER_SECOND
                ? Long.MAX_VALUE
                : elapsedSeconds * MAX_PLAUSIBLE_BYTES_PER_SECOND + DELTA_CUSHION_BYTES;
        boolean implausibleJump = !firstSnapshot && !counterReset && rawDelta > maximumPlausibleDelta;
        counterReset = counterReset || implausibleJump;
        long deltaBytes = firstSnapshot || counterReset ? 0 : rawDelta;

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

    void resetUsageWithBaselines(List<RouterRpcClient.TrafficStat> stats, long capturedAt) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("traffic_snapshots", null, null);
            for (RouterRpcClient.TrafficStat stat : stats) {
                ensureDeviceProfile(db, stat.mac, stat.ip, capturedAt);
                ContentValues values = new ContentValues();
                values.put("ip", stat.ip);
                values.put("mac", stat.mac);
                values.put("captured_at", capturedAt);
                values.put("packets_total", stat.totalPackets);
                values.put("bytes_total", stat.totalBytes);
                values.put("packets_current", stat.currentPackets);
                values.put("bytes_current", stat.currentBytes);
                values.put("delta_bytes", 0);
                values.put("counter_reset", 1);
                // A baseline establishes the new zero; it is not usage to upload.
                values.put("cloud_synced", 1);
                db.insertOrThrow("traffic_snapshots", null, values);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
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

    List<TrafficSnapshot> getUnsyncedSnapshots(int limit) {
        List<TrafficSnapshot> snapshots = new ArrayList<>();
        String sql = "SELECT s.id,s.ip,s.mac,s.captured_at,s.packets_total,s.bytes_total," +
                "s.packets_current,s.bytes_current,s.delta_bytes,s.counter_reset," +
                "COALESCE(p.device_name, '') " +
                "FROM traffic_snapshots s LEFT JOIN device_profiles p ON p.mac = s.mac " +
                "WHERE s.cloud_synced = 0 ORDER BY s.captured_at,s.id LIMIT ?";
        try (Cursor cursor = getReadableDatabase().rawQuery(sql, new String[]{String.valueOf(limit)})) {
            while (cursor.moveToNext()) {
                snapshots.add(new TrafficSnapshot(
                        cursor.getLong(0), cursor.getString(1), cursor.getString(2), cursor.getLong(3),
                        cursor.getLong(4), cursor.getLong(5), cursor.getLong(6), cursor.getLong(7),
                        cursor.getLong(8), cursor.getInt(9) == 1, cursor.getString(10)
                ));
            }
        }
        return snapshots;
    }

    void markSnapshotSynced(long snapshotId) {
        ContentValues values = new ContentValues();
        values.put("cloud_synced", 1);
        getWritableDatabase().update("traffic_snapshots", values, "id = ?", new String[]{String.valueOf(snapshotId)});
    }

    int countUnsyncedSnapshots() {
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM traffic_snapshots WHERE cloud_synced = 0", null)) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
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

    static final class TrafficSnapshot {
        final long id;
        final String ip;
        final String mac;
        final long capturedAt;
        final long packetsTotal;
        final long bytesTotal;
        final long packetsCurrent;
        final long bytesCurrent;
        final long deltaBytes;
        final boolean counterReset;
        final String deviceName;

        TrafficSnapshot(long id, String ip, String mac, long capturedAt, long packetsTotal, long bytesTotal,
                        long packetsCurrent, long bytesCurrent, long deltaBytes, boolean counterReset,
                        String deviceName) {
            this.id = id;
            this.ip = ip;
            this.mac = mac;
            this.capturedAt = capturedAt;
            this.packetsTotal = packetsTotal;
            this.bytesTotal = bytesTotal;
            this.packetsCurrent = packetsCurrent;
            this.bytesCurrent = bytesCurrent;
            this.deltaBytes = deltaBytes;
            this.counterReset = counterReset;
            this.deviceName = deviceName;
        }
    }
}
