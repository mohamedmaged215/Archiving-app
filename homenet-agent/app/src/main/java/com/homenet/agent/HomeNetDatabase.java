package com.homenet.agent;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

final class HomeNetDatabase extends SQLiteOpenHelper {
    private static final String DATABASE_NAME = "homenet.db";
    private static final int DATABASE_VERSION = 1;

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
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Future schema upgrades will preserve collected traffic history.
    }

    SaveResult saveSnapshot(String ip, String mac, long capturedAt,
                            long packetsTotal, long bytesTotal,
                            long packetsCurrent, long bytesCurrent) {
        SQLiteDatabase db = getWritableDatabase();
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
}
